//! Queueing-logic unit tests (task instruction: "queueing logic (unit with
//! a fake chain client)") -- no network, no simulator, just
//! `Publisher::{enqueue, tick}` against `FakeChainClient` and a temp data
//! dir. Exercises: "several pending records go into ONE singleton spend",
//! "records queue while a spend is in flight", "detect confirmation via
//! coinset", "retries with backoff", and "never double-spend the
//! singleton" (all task instruction phrases).
//!
//! Setup uses `c2_cli::builders::oracle::build_oracle_launch` directly
//! (pure local CLVM construction, no chain/simulator needed) to get a
//! self-consistent `(launcher_id, eve_coin, eve_proof)` triple for the
//! fixture `operator.oracle.json` -- the same reason `c2 oracle-launch`
//! itself works fully offline in dry-run mode.

use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::sync::Mutex;
use std::time::Duration;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, CoinSpend, SpendBundle};
use chia_puzzle_types::Proof;

use c2_cli::builders::oracle::build_oracle_launch;
use c2_cli::keys::{WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::OracleState;
use c2_svc::fee_allocator::{FeeCoinAllocator, fee_coin_of};
use c2_svc::oracle::chain_client::{ChainClient, CoinRecordInfo, PushOutcome};
use c2_svc::oracle::lineage::walk_lineage;
use c2_svc::oracle::publisher::{Publisher, TickFailureState, TickOutcome};
use c2_svc::oracle::queue::QueueFile;
use c2_svc::oracle::spend::build_oracle_publish_batch;
use c2_svc::oracle::types::{OracleRecord, Source};
use c2_svc::state_file::save_oracle_state_atomic;

// --- fake chain client -------------------------------------------------

struct FakeChainClientInner {
    /// coin_id -> confirmed/spent info the test controls directly.
    coin_records: HashMap<Bytes32, CoinRecordInfo>,
    fee_mojos: u64,
    funding_amount: u64,
    /// Extra funding-coin candidates a test registers explicitly (task fix,
    /// `docs/research/chain_service.md` §15: tests that need more than one
    /// distinct fee-coin candidate, e.g. to prove a rebuild picks a
    /// *different* coin once the first is spent/reserved). Empty by
    /// default, so every pre-existing test keeps seeing the single
    /// always-available `[7u8; 32]`-parented coin it already relies on.
    extra_funding_coins: Vec<Coin>,
    push_calls: u32,
    /// Every `push_tx` call returns this outcome; tests flip it between
    /// calls to simulate a failed-then-retried push.
    next_push_outcome: PushOutcome,
    /// tx_id (`SpendBundle::name()`) -> whether `in_mempool` reports it
    /// present. Defaults to "not in mempool" for any tx_id not explicitly
    /// set, matching a rejected push that never entered the mempool.
    in_mempool: HashMap<Bytes32, bool>,
    /// Artificial delay every trait method sleeps before returning --
    /// stands in for a slow/stuck coinset, so a test can prove
    /// `Publisher::status()` answers without waiting on whatever chain call
    /// a concurrent `tick()` is in the middle of (task fix, see
    /// `status_answers_while_a_publish_is_in_flight`).
    delay: Duration,
    /// coin_id -> the `CoinSpend` that spent it, for `puzzle_and_solution`
    /// (the chain-lineage walk, `oracle/lineage.rs`, task fix
    /// `docs/research/chain_service.md` §11). Populated by
    /// `set_puzzle_and_solution` / `push_lineage_generation` below.
    coin_spends: HashMap<Bytes32, CoinSpend>,
}

pub struct FakeChainClient(Mutex<FakeChainClientInner>);

impl FakeChainClient {
    fn new() -> Self {
        Self(Mutex::new(FakeChainClientInner {
            coin_records: HashMap::new(),
            fee_mojos: 0,
            funding_amount: 1_000_000,
            extra_funding_coins: Vec::new(),
            push_calls: 0,
            next_push_outcome: PushOutcome {
                accepted: true,
                status: Some("SUCCESS".to_string()),
                error: None,
            },
            in_mempool: HashMap::new(),
            delay: Duration::ZERO,
            coin_spends: HashMap::new(),
        }))
    }

    fn set_delay(&self, delay: Duration) {
        self.0.lock().unwrap().delay = delay;
    }

    /// Registers an additional fee-coin candidate `find_funding_coin` may
    /// offer, alongside the always-present default `[7u8; 32]`-parented one
    /// (task fix, `docs/research/chain_service.md` §15).
    fn add_funding_coin(&self, coin: Coin) {
        self.0.lock().unwrap().extra_funding_coins.push(coin);
    }

    /// Controls what the *next* `fee_estimate_mojos` call returns (task
    /// instruction: "re-estimate with get_fee_estimate ... on each
    /// consecutive fee rejection").
    fn set_fee_estimate(&self, fee_mojos: u64) {
        self.0.lock().unwrap().fee_mojos = fee_mojos;
    }

    /// Controls whether `in_mempool(tx_id)` reports `tx_id` present.
    fn set_in_mempool(&self, tx_id: Bytes32, present: bool) {
        self.0.lock().unwrap().in_mempool.insert(tx_id, present);
    }

    fn set_confirmed(&self, coin_id: Bytes32, height: u32) {
        self.0.lock().unwrap().coin_records.insert(
            coin_id,
            CoinRecordInfo {
                exists: true,
                confirmed_height: Some(height),
                spent: false,
                spent_height: None,
                coin: None,
            },
        );
    }

    fn set_spent(&self, coin_id: Bytes32, confirmed_height: u32, spent_height: u32) {
        self.0.lock().unwrap().coin_records.insert(
            coin_id,
            CoinRecordInfo {
                exists: true,
                confirmed_height: Some(confirmed_height),
                spent: true,
                spent_height: Some(spent_height),
                coin: None,
            },
        );
    }

    fn set_push_outcome(&self, outcome: PushOutcome) {
        self.0.lock().unwrap().next_push_outcome = outcome;
    }

    fn push_call_count(&self) -> u32 {
        self.0.lock().unwrap().push_calls
    }

    /// Registers `coin_spend` as the spend that consumed `coin`, and marks
    /// `coin` spent at `spent_height` (`confirmed_height` is whatever it
    /// already was, or `spent_height` if never set) -- one call sets up
    /// everything `walk_lineage` needs for that one generation. Takes the
    /// full `Coin` (not just its id) so `CoinRecordInfo::coin` is populated
    /// too -- needed by `oracle::lineage::tip_lineage_proof` (task fix,
    /// `docs/research/chain_service.md` §12) whenever a test's chain tip
    /// turns out to be this coin's *child* (i.e. every existing lineage test,
    /// which chains generations forward exactly this way).
    fn set_lineage_spend(&self, coin: Coin, confirmed_height: u32, spent_height: u32, coin_spend: CoinSpend) {
        let coin_id = coin.coin_id();
        let mut inner = self.0.lock().unwrap();
        inner.coin_records.insert(
            coin_id,
            CoinRecordInfo {
                exists: true,
                confirmed_height: Some(confirmed_height),
                spent: true,
                spent_height: Some(spent_height),
                coin: Some(coin),
            },
        );
        inner.coin_spends.insert(coin_id, coin_spend);
    }
}

impl ChainClient for FakeChainClient {
    fn coin_record(&self, coin_id: Bytes32) -> impl Future<Output = Result<CoinRecordInfo>> + Send {
        let (info, delay) = {
            let inner = self.0.lock().unwrap();
            (
                inner.coin_records.get(&coin_id).copied().unwrap_or(CoinRecordInfo {
                    exists: false,
                    confirmed_height: None,
                    spent: false,
                    spent_height: None,
                    coin: None,
                }),
                inner.delay,
            )
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(info)
        }
    }

    fn fee_estimate_mojos(&self, _cost: u64, _target_time_secs: u32) -> impl Future<Output = Result<u64>> + Send {
        let (fee, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.fee_mojos, inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(fee)
        }
    }

    fn find_funding_coin(
        &self,
        master_sk: &SecretKey,
        min_amount: u64,
        exclude: &HashSet<Bytes32>,
    ) -> impl Future<Output = Result<(WalletKey, Coin)>> + Send {
        let wallet_key = derive_wallet_key(master_sk, 0);
        let (amount, delay, extra, coin_records) = {
            let inner = self.0.lock().unwrap();
            (inner.funding_amount, inner.delay, inner.extra_funding_coins.clone(), inner.coin_records.clone())
        };
        let default_coin = Coin::new(Bytes32::from([7u8; 32]), wallet_key.puzzle_hash, amount);
        let mut candidates = vec![default_coin];
        candidates.extend(extra);
        let exclude = exclude.clone();
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            let chosen = candidates.into_iter().find(|c| {
                c.amount >= min_amount
                    && !exclude.contains(&c.coin_id())
                    && !coin_records.get(&c.coin_id()).is_some_and(|r| r.spent)
            });
            match chosen {
                Some(coin) => Ok((wallet_key, coin)),
                None => anyhow::bail!(
                    "fake chain client: no unspent, unreserved funding coin >= {min_amount} mojos \
                     (excluding {} reserved)",
                    exclude.len()
                ),
            }
        }
    }

    fn balance(&self, _master_sk: &SecretKey) -> impl Future<Output = Result<u64>> + Send {
        let (amount, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.funding_amount, inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(amount)
        }
    }

    fn push_tx(&self, _bundle: SpendBundle) -> impl Future<Output = Result<PushOutcome>> + Send {
        let (outcome, delay) = {
            let mut inner = self.0.lock().unwrap();
            inner.push_calls += 1;
            (inner.next_push_outcome.clone(), inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(outcome)
        }
    }

    fn in_mempool(&self, tx_id: Bytes32) -> impl Future<Output = Result<bool>> + Send {
        let (present, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.in_mempool.get(&tx_id).copied().unwrap_or(false), inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(present)
        }
    }

    fn puzzle_and_solution(
        &self,
        coin_id: Bytes32,
        _height: Option<u32>,
    ) -> impl Future<Output = Result<Option<CoinSpend>>> + Send {
        let (coin_spend, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.coin_spends.get(&coin_id).cloned(), inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(coin_spend)
        }
    }
}

// --- fixture -------------------------------------------------------------

struct Fixture {
    _tmp: tempfile::TempDir,
    publisher: Publisher<FakeChainClient>,
    /// Kept for the chain-lineage-walk tests (`bootstrap_from_chain`,
    /// `reconcile_with_chain`): they build *further* real generations of the
    /// exact same singleton this fixture's `operator.oracle.json` already
    /// describes, via `publish_lineage_generation` below, which needs the
    /// same manager key / launcher id `set_up` used to launch it.
    manager: WalletKey,
    launcher_id: Bytes32,
    eve_coin: Coin,
    eve_proof: Proof,
}

fn set_up(retry_backoff: Duration) -> Result<Fixture> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);

    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-09-25T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // Pure local CLVM construction (no chain/simulator) -- see module doc.
    let funding_coin = Coin::new(Bytes32::from([9u8; 32]), manager.puzzle_hash, 1);
    let launch = build_oracle_launch(funding_coin, &manager, 0)?;

    let oracle_state = OracleState {
        launcher_id: launch.launcher_id,
        manager_synthetic_pk: manager.synthetic_pk,
        current_coin: launch.eve_coin,
        current_proof_dto: launch.eve_proof.into(),
        next_sequence: 1,
    };
    save_oracle_state_atomic(&data_dir, "operator", &oracle_state)?;

    let publisher = Publisher {
        data_dir,
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakeChainClient::new(),
        retry_backoff,
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: std::sync::Arc::new(FeeCoinAllocator::new()),
    };

    Ok(Fixture {
        _tmp: tmp,
        publisher,
        manager,
        launcher_id: launch.launcher_id,
        eve_coin: launch.eve_coin,
        eve_proof: launch.eve_proof,
    })
}

/// Builds one real oracle-publish-batch spend (the exact same builder the
/// production `Publisher` uses, `spend.rs::build_oracle_publish_batch` --
/// not a hand-rolled announcement encoding) recreating `current_coin` and
/// announcing `records`, and registers it with `chain` as the spend that
/// confirmed `current_coin` at `spent_height` (chain-lineage-walk fixture
/// helper, `oracle/lineage.rs`, task fix `docs/research/chain_service.md`
/// §11). Returns the next generation's `(Coin, Proof)` to chain into a
/// further call, mirroring how `Publisher::start_new_batch` itself advances
/// generation to generation.
fn publish_lineage_generation(
    chain: &FakeChainClient,
    launcher_id: Bytes32,
    manager: &WalletKey,
    current_coin: Coin,
    current_proof: Proof,
    spent_height: u32,
    records: &[OracleRecord],
) -> Result<(Coin, Proof)> {
    let draft = build_oracle_publish_batch(launcher_id, current_coin, current_proof, manager, records)?;
    let coin_spend = draft
        .coin_spends
        .into_iter()
        .next()
        .expect("build_oracle_publish_batch always emits exactly one (singleton) coin spend");
    chain.set_lineage_spend(current_coin, spent_height, spent_height, coin_spend);
    Ok((draft.next_coin, draft.next_proof))
}

fn record(seq: u64) -> OracleRecord {
    OracleRecord {
        seq,
        ts: 1_790_000_000 + seq as i64,
        price_cents: 65_432_100,
        results_hash: Bytes32::from([seq as u8; 32]),
        source_flag: Source::Live.flag(),
        source_ts: 1_789_999_000 + seq as i64,
    }
}

// --- tests -----------------------------------------------------------------

/// "several pending records go into ONE singleton spend" + "detect
/// confirmation via coinset": one batch, one broadcast, confirmation
/// clears it and advances `operator.oracle.json`.
#[tokio::test]
async fn batch_broadcasts_once_and_confirms() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let queued = fx.publisher.enqueue(vec![record(1), record(2), record(3)]).await?;
    assert_eq!(queued, vec![1, 2, 3]);

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 3, accepted: true });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("spend should be in flight");
    assert_eq!(in_flight.records.len(), 3);
    assert!(queue.pending.is_empty());

    // Not confirmed yet -> another tick must not start a second spend.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::WaitingForConfirmation);
    assert_eq!(fx.publisher.chain.push_call_count(), 1, "must not re-push before backoff elapses");

    fx.publisher.chain.set_confirmed(in_flight.next_coin.coin_id(), 4_735_705);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 3 });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.in_flight.is_none());
    assert_eq!(queue.last_confirmed_seq, Some(3));
    assert_eq!(queue.last_confirmed_height, Some(4_735_705));

    let oracle_state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    assert_eq!(oracle_state.current_coin, in_flight.next_coin);
    assert_eq!(oracle_state.next_sequence, 2);

    let status = fx.publisher.status().await?;
    assert_eq!(status.last_confirmed_seq, Some(3));
    assert_eq!(status.pending_records, 0);
    assert!(status.in_flight_bundle_id.is_none());

    Ok(())
}

/// "records queue while a spend is in flight": records submitted after a
/// batch is already broadcast must not trigger a second, concurrent spend
/// -- they wait in `pending` until the in-flight one confirms.
#[tokio::test]
async fn records_queue_while_a_spend_is_in_flight() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    fx.publisher.enqueue(vec![record(1)]).await?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: true });

    // More records arrive while the first batch is still unconfirmed.
    let queued = fx.publisher.enqueue(vec![record(2), record(3)]).await?;
    assert_eq!(queued, vec![2, 3]);

    let status = fx.publisher.status().await?;
    assert_eq!(status.pending_records, 3, "1 in flight + 2 pending");

    // Ticking again must not start a second spend (only one in_flight at a
    // time) and must not touch push_tx again yet.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::WaitingForConfirmation);
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(queue.pending.len(), 2, "new records stayed queued, did not join the in-flight spend");
    let next_coin = queue.in_flight.unwrap().next_coin;

    // Confirm the first batch -> the queued records become eligible for the
    // *next* spend.
    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 100);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 1 });

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 2, accepted: true });
    assert_eq!(fx.publisher.chain.push_call_count(), 2);

    Ok(())
}

/// "retries with backoff": a rejected/still-unconfirmed push is retried
/// only after `retry_backoff` has elapsed, reusing the exact same signed
/// bundle (no rebuild).
#[tokio::test]
async fn retries_after_backoff_elapses() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?; // zero backoff: every tick is eligible to retry

    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: false, status: None, error: Some("mempool full".to_string()) });

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    // Still not confirmed, still failing -> backoff is zero, so this tick
    // retries `push_tx` again with the same persisted bundle.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Retried { accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 2);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("still in flight after failed pushes");
    assert_eq!(in_flight.attempts, 2);
    assert_eq!(in_flight.last_push_error.as_deref(), Some("mempool full"));

    // Now let it succeed and confirm.
    fx.publisher.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    fx.publisher.chain.set_confirmed(in_flight.next_coin.coin_id(), 200);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 1 });

    Ok(())
}

/// A stuck spend (exhausted retries) is surfaced, not silently dropped or
/// endlessly retried past the configured cap.
#[tokio::test]
async fn stuck_in_flight_stops_auto_retrying_at_max_attempts() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: false, status: None, error: Some("rejected".to_string()) });

    // max_retry_attempts = 3 (set_up): first tick broadcasts (attempts=1),
    // then 2 more ticks retry (attempts=2, attempts=3); a 4th tick must stop
    // retrying and report `StuckInFlight` instead of calling push_tx again.
    fx.publisher.tick().await?; // attempts = 1
    fx.publisher.tick().await?; // attempts = 2
    fx.publisher.tick().await?; // attempts = 3
    assert_eq!(fx.publisher.chain.push_call_count(), 3);

    let outcome = fx.publisher.tick().await?;
    match outcome {
        TickOutcome::StuckInFlight { reason } => {
            assert!(reason.contains("3 retry attempts"), "reason should name the attempt cap: {reason}");
        }
        other => panic!("expected StuckInFlight, got {other:?}"),
    }
    assert_eq!(fx.publisher.chain.push_call_count(), 3, "must not push_tx again past max_retry_attempts");

    let status = fx.publisher.status().await?;
    assert!(status.in_flight_bundle_id.is_some(), "stuck spend still surfaced via status");
    assert!(status.stuck_reason.is_some(), "stuck spend's reason surfaced via status too");

    Ok(())
}

/// "never double-spend the singleton": if the chain says the current
/// generation's coin is already spent but local state has no matching
/// in-flight spend (stale state, e.g. after an unexpected local state
/// reset), a new batch must be refused rather than built on top of it.
#[tokio::test]
async fn refuses_to_build_on_stale_spent_current_coin() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.enqueue(vec![record(1)]).await?;

    let oracle_state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    fx.publisher
        .chain
        .set_spent(oracle_state.current_coin.coin_id(), 50, 51);

    let result = fx.publisher.tick().await;
    assert!(result.is_err(), "must refuse to spend an already-spent current_coin");
    assert_eq!(fx.publisher.chain.push_call_count(), 0);

    Ok(())
}

/// Task fix: `GET /v1/oracle/status` must answer immediately even while a
/// publish (`tick()`) is genuinely in the middle of a slow/stuck chain call
/// -- it used to make its own `chain.balance()` round-trip inline
/// (`c2_cli::chain::balance`: 20 sequential, un-timed-out coinset lookups),
/// so a slow coinset made the whole handler hang along with it. `status()`
/// now only reads local files plus the `balance_snapshot` cache, never the
/// chain directly.
#[tokio::test]
async fn status_answers_while_a_publish_is_in_flight() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.enqueue(vec![record(1)]).await?;

    // Every chain call the fake makes (including the `coin_record` check
    // `tick()`'s `start_new_batch` does before building a new spend) now
    // sleeps 2s -- comfortably longer than `status()` must ever be allowed
    // to take.
    fx.publisher.chain.set_delay(Duration::from_secs(2));

    // Drive `tick()` (slow: 3 delayed chain calls back to back, ~6s total)
    // and `status()` concurrently on the same task. `tokio::join!` doesn't
    // return until *both* branches finish, so timing is taken inside the
    // `status()` branch itself -- from when it starts (the same poll cycle
    // `tick()`'s first delayed chain call suspends on) to when it completes
    // -- rather than around the whole `join!`, which would just measure
    // `tick()`'s much longer runtime.
    let (tick_outcome, (status, status_elapsed)) = tokio::join!(fx.publisher.tick(), async {
        let started = std::time::Instant::now();
        let status = fx.publisher.status().await;
        (status, started.elapsed())
    });
    let status = status?;

    assert!(
        status_elapsed < Duration::from_millis(500),
        "status() took {status_elapsed:?} while a publish was in flight -- status must not block on the \
         chain call a publish is making"
    );
    assert_eq!(status.pending_records, 1, "status still reflects the live queue, not a stale snapshot");

    // The slow tick() itself still completes correctly once its delays
    // elapse -- this fix only decouples status(), it doesn't change tick().
    assert_eq!(tick_outcome?, TickOutcome::Broadcast { records: 1, accepted: true });

    Ok(())
}

/// A dry run (`push_enabled: false`) builds and signs but never calls
/// `push_tx` and never mutates persisted state -- task instruction
/// "--dry-run / env C2_PUSH=0 mode for tests".
#[tokio::test]
async fn dry_run_never_pushes_or_mutates_state() -> Result<()> {
    let mut fx = set_up(Duration::from_secs(300))?;
    fx.publisher.push_enabled = false;

    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::DryRun { records: 2 });
    assert_eq!(fx.publisher.chain.push_call_count(), 0);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.in_flight.is_none());
    assert_eq!(queue.pending.len(), 2, "dry run must not drain the pending queue");

    Ok(())
}

/// Task fix: a push the node REJECTS outright (never entered the mempool,
/// e.g. `INVALID_FEE_TOO_CLOSE_TO_ZERO`) does not spend the singleton coin,
/// so it isn't really "in flight" -- once coinset confirms the coin is
/// still unspent and the bundle absent from the mempool, the publisher
/// rebuilds with a higher, freshly re-estimated fee and broadcasts that
/// instead, and the rebuilt bundle can go on to confirm normally.
#[tokio::test]
async fn fee_too_low_rejection_rebuilds_with_higher_fee_then_confirms() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?; // zero backoff: every tick is eligible to act

    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_fee_estimate(0);
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some(
            "Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string(),
        ),
    });

    let outcome = fx.publisher.tick().await?; // start_new_batch: broadcasts at fee=0, rejected
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let first_in_flight = queue.in_flight.expect("should be in flight after the rejected broadcast");
    assert_eq!(first_in_flight.fee_mojos, 0);
    assert_eq!(first_in_flight.attempts, 1);

    // The mempool has since eased: a real, higher fee is now both estimated
    // and sufficient for the node to accept the push.
    fx.publisher.chain.set_fee_estimate(1_000_000);
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });

    let outcome = fx.publisher.tick().await?;
    match outcome {
        TickOutcome::Rebuilt { records, accepted, fee_mojos } => {
            assert_eq!(records, 1);
            assert!(accepted);
            assert_eq!(fee_mojos, 1_000_000, "bumped fee = max(old_fee * 2, chain estimate)");
        }
        other => panic!("expected Rebuilt, got {other:?}"),
    }
    assert_eq!(fx.publisher.chain.push_call_count(), 2, "rebuild pushes exactly once, no extra retry");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let rebuilt = queue.in_flight.expect("still in flight, now awaiting confirmation");
    assert_eq!(rebuilt.fee_mojos, 1_000_000);
    assert_eq!(rebuilt.attempts, 1, "a rebuilt bundle gets its own fresh retry budget");
    assert_ne!(
        rebuilt.bundle_id, first_in_flight.bundle_id,
        "rebuilding (higher fee) produces a materially different bundle"
    );

    fx.publisher.chain.set_confirmed(rebuilt.next_coin.coin_id(), 4_800_000);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 1 });

    let status = fx.publisher.status().await?;
    assert_eq!(status.last_confirmed_seq, Some(1));
    assert!(status.stuck_reason.is_none());

    Ok(())
}

/// Task fix (`docs/research/chain_service.md` §15): a rejection naming a
/// double-spend, with the singleton confirmed unspent, can only be this
/// bundle's *fee* coin having been spent by something else (e.g. another
/// publisher sharing the same operator wallet, the incident this closes) --
/// the publisher must identify that, drop the dead fee coin, and rebuild
/// with a fresh one immediately, spending zero of its retry budget (a
/// DOUBLE_SPEND on an already-dead coin will never resolve itself by
/// retrying the identical bytes).
#[tokio::test]
async fn fee_coin_double_spent_elsewhere_rebuilds_immediately_without_burning_a_retry() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    // Non-zero, so the very first broadcast actually attaches a fee coin
    // (a zero fee estimate, as in the fee-too-low tests above, skips fee
    // coin attachment entirely -- not the scenario this test is about).
    fx.publisher.chain.set_fee_estimate(1_000);

    // A second, distinct funding-coin candidate -- stands in for "another
    // coin in the operator's wallet", available once the allocator excludes
    // (or, here, the chain shows spent) the first one.
    let second_fee_coin = Coin::new(Bytes32::from([11u8; 32]), fx.manager.puzzle_hash, 2_000_000);
    fx.publisher.chain.add_funding_coin(second_fee_coin);

    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some(
            "Failed to include transaction 0xb7ba8f64, error DOUBLE_SPEND".to_string(),
        ),
    });

    let outcome = fx.publisher.tick().await?; // start_new_batch: picks the default fee coin, rejected
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let first_in_flight = queue.in_flight.expect("should be in flight after the rejected broadcast");
    let dead_fee_coin =
        fee_coin_of(&first_in_flight.bundle, first_in_flight.spent_coin).expect("bundle must have a fee coin");
    assert_ne!(dead_fee_coin, second_fee_coin, "first attempt must use the default coin, not the second one");

    // The fee coin (not the singleton) is now spent on chain -- e.g. another
    // publisher's batch confirmed first (the incident this closes).
    fx.publisher.chain.set_spent(dead_fee_coin.coin_id(), 4_738_900, 4_738_927);
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });

    let outcome = fx.publisher.tick().await?;
    match outcome {
        TickOutcome::Rebuilt { records, accepted, .. } => {
            assert_eq!(records, 1);
            assert!(accepted);
        }
        other => panic!("expected Rebuilt, got {other:?}"),
    }
    // Exactly one more push (the rebuild's) -- no `Retried` outcome burned a
    // retry attempt on the doomed original bundle in between.
    assert_eq!(fx.publisher.chain.push_call_count(), 2);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let rebuilt = queue.in_flight.expect("still in flight, now awaiting confirmation");
    assert_eq!(rebuilt.attempts, 1, "a rebuilt bundle gets its own fresh retry budget, not attempts=9");
    let new_fee_coin =
        fee_coin_of(&rebuilt.bundle, rebuilt.spent_coin).expect("rebuilt bundle must have a fee coin");
    assert_eq!(new_fee_coin, second_fee_coin, "must pick the OTHER coin, not retry the dead one");
    assert!(
        fx.publisher.fee_allocator.is_reserved(second_fee_coin.coin_id()).await,
        "the new fee coin must be reserved while its bundle is in flight"
    );
    assert!(
        !fx.publisher.fee_allocator.is_reserved(dead_fee_coin.coin_id()).await,
        "the dead fee coin's reservation must have been released"
    );

    fx.publisher.chain.set_confirmed(rebuilt.next_coin.coin_id(), 4_738_950);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 1 });
    assert!(
        !fx.publisher.fee_allocator.is_reserved(second_fee_coin.coin_id()).await,
        "confirming must release the fee coin reservation too"
    );

    Ok(())
}

/// Task fix (`docs/research/chain_service.md` §15): "restart with an
/// in-flight bundle whose fee coin was spent elsewhere -> recovers" -- a
/// brand-new `Publisher` (fresh chain client, fresh *empty* in-memory
/// `FeeCoinAllocator`, e.g. after a process restart) reading the same
/// `data_dir` must, on its very first tick, both re-seed its allocator from
/// the persisted in-flight bundle's fee coin (the way `main.rs` does right
/// after startup bootstrap) and correctly recognize+recover from that same
/// fee coin having been spent while the process was down -- exactly one
/// rebuild, no wasted retry, full confirmation.
#[tokio::test]
async fn restart_recovers_when_the_in_flight_fee_coin_was_spent_while_down() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    // Non-zero, so the pre-restart broadcast actually attaches a fee coin.
    fx.publisher.chain.set_fee_estimate(1_000);
    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xdead, error DOUBLE_SPEND".to_string()),
    });
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.clone().expect("should be in flight after the rejected broadcast");
    let dead_fee_coin = fee_coin_of(&in_flight.bundle, in_flight.spent_coin).expect("bundle must have a fee coin");

    // Simulate a process restart: brand-new `Publisher` (fresh chain client,
    // fresh in-memory state, including a fresh, empty `FeeCoinAllocator`)
    // pointed at the *same* data dir (same convention as
    // `restart_resumes_a_pending_in_flight_spend` above).
    let restarted = Publisher {
        data_dir: fx.publisher.data_dir.clone(),
        operator_name: fx.publisher.operator_name.clone(),
        push_enabled: true,
        chain: FakeChainClient::new(),
        retry_backoff: Duration::from_secs(0),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: std::sync::Arc::new(FeeCoinAllocator::new()),
    };

    // `main.rs`'s own startup sequence: seed the allocator from whatever
    // in-flight bundle the durable queue file already committed to, before
    // the first tick.
    let seeded = restarted.in_flight_fee_coin_id()?;
    assert_eq!(seeded, Some(dead_fee_coin.coin_id()));
    restarted.fee_allocator.reserve_specific(seeded.unwrap()).await;

    // While the process was down, something else spent the fee coin (the
    // singleton itself is untouched -- still confirmed unspent).
    restarted.chain.set_spent(dead_fee_coin.coin_id(), 4_738_900, 4_738_927);
    let fresh_fee_coin = Coin::new(Bytes32::from([33u8; 32]), fx.manager.puzzle_hash, 2_000_000);
    restarted.chain.add_funding_coin(fresh_fee_coin);
    restarted.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });

    let outcome = restarted.tick().await?;
    match outcome {
        TickOutcome::Rebuilt { records, accepted, .. } => {
            assert_eq!(records, 1);
            assert!(accepted);
        }
        other => panic!("expected Rebuilt (immediate recovery, not a wasted retry), got {other:?}"),
    }

    let queue = QueueFile::load(&restarted.data_dir, "operator")?;
    let rebuilt = queue.in_flight.expect("still in flight, awaiting confirmation");
    assert_eq!(rebuilt.attempts, 1, "the restarted process's rebuild must not inherit pre-restart attempts");
    let new_fee_coin = fee_coin_of(&rebuilt.bundle, rebuilt.spent_coin).expect("must have a fee coin");
    assert_eq!(new_fee_coin, fresh_fee_coin);

    restarted.chain.set_confirmed(rebuilt.next_coin.coin_id(), 4_738_950);
    let outcome = restarted.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 1 }, "restart must fully recover to a confirmed publish");

    Ok(())
}

/// Task instruction: once the fee has been bumped up to `max_fee_mojos` and
/// still gets rejected, the publisher must stop escalating and clearly
/// report "needs operator attention" with a reason, both from `tick()` and
/// from `GET /v1/oracle/status`.
#[tokio::test]
async fn fee_cap_reached_reports_stuck_with_reason() -> Result<()> {
    let mut fx = set_up(Duration::from_secs(0))?;
    fx.publisher.max_fee_mojos = 100; // tiny cap -- saturates on the very first rebuild

    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });

    let outcome = fx.publisher.tick().await?; // Broadcast at fee=0
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });

    fx.publisher.chain.set_fee_estimate(999_999); // far above the cap
    let outcome = fx.publisher.tick().await?; // rebuild: fee clamped to the 100-mojo cap
    match outcome {
        TickOutcome::Rebuilt { fee_mojos, accepted, .. } => {
            assert_eq!(fee_mojos, 100, "fee must be clamped to max_fee_mojos, not the raw estimate");
            assert!(!accepted);
        }
        other => panic!("expected Rebuilt, got {other:?}"),
    }
    assert_eq!(fx.publisher.chain.push_call_count(), 2);

    // Fee is already at the cap -- nothing left to escalate, so further
    // rejections just retry the same (capped-fee) bundle up to
    // max_retry_attempts (3, from `set_up`), then report stuck.
    let outcome = fx.publisher.tick().await?; // attempts: 1 -> 2
    assert_eq!(outcome, TickOutcome::Retried { accepted: false });
    let outcome = fx.publisher.tick().await?; // attempts: 2 -> 3 == max_retry_attempts
    assert_eq!(outcome, TickOutcome::Retried { accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 4);

    let outcome = fx.publisher.tick().await?;
    match outcome {
        TickOutcome::StuckInFlight { reason } => {
            assert!(reason.contains("fee cap of 100 mojos reached"), "reason should name the cap: {reason}");
        }
        other => panic!("expected StuckInFlight, got {other:?}"),
    }
    assert_eq!(fx.publisher.chain.push_call_count(), 4, "must not push_tx again once genuinely stuck");

    let status = fx.publisher.status().await?;
    assert!(
        status.stuck_reason.as_deref().is_some_and(|r| r.contains("fee cap of 100 mojos reached")),
        "status() must surface the same clear reason: {:?}",
        status.stuck_reason
    );

    Ok(())
}

/// "never double-spend the singleton" guard for the rebuild path: if
/// coinset shows the singleton coin this rejected bundle would spend is
/// already spent (e.g. by a race, or a push that actually landed despite a
/// misleading rejection response), the publisher must not rebuild on top of
/// it -- just wait for the next tick's `next_coin` confirmation check.
#[tokio::test]
async fn no_rebuild_while_singleton_coin_already_spent() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("should be in flight");
    fx.publisher.chain.set_spent(in_flight.spent_coin.coin_id(), 50, 51);

    let outcome = fx.publisher.tick().await?;
    assert_eq!(
        outcome,
        TickOutcome::WaitingForConfirmation,
        "must not rebuild on top of an already-spent singleton coin"
    );
    assert_eq!(fx.publisher.chain.push_call_count(), 1, "must not push_tx again");

    Ok(())
}

// --- FIXED INTERFACE (b) / root-cause fix regression tests ---------------
// (`docs/research/chain_service.md` §10: why seq 2/3/4/62 were acknowledged
// but never published, and the fix.)

/// FIXED INTERFACE (b): "`POST /v1/oracle/records` returns 202 only after
/// the records are fsync'ed in the durable queue file" -- by the time
/// `enqueue()` returns, a completely independent read of the file (a fresh
/// `QueueFile::load`, not through the `Publisher` at all) must already see
/// it, byte for byte. This is what a caller's 202 response is supposed to
/// mean.
#[tokio::test]
async fn enqueue_persists_durably_before_returning() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let accepted = fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    assert_eq!(accepted, vec![1, 2]);

    let on_disk = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(on_disk.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![1, 2]);
    Ok(())
}

/// FIXED INTERFACE (b): "A record whose seq is already confirmed or queued
/// is ignored idempotently (not an error)". Covers all three "already
/// known" states -- pending, in-flight, and confirmed -- none of them
/// re-added or double-counted.
#[tokio::test]
async fn resubmitting_a_known_record_is_idempotent_in_every_state() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;

    // Still pending.
    fx.publisher.enqueue(vec![record(1)]).await?;
    let accepted = fx.publisher.enqueue(vec![record(1)]).await?;
    assert_eq!(accepted, Vec::<u64>::new(), "re-POSTing a pending seq must accept nothing new");
    assert_eq!(QueueFile::load(&fx.publisher.data_dir, "operator")?.pending.len(), 1);

    // In flight.
    fx.publisher.tick().await?; // broadcasts seq 1
    let accepted = fx.publisher.enqueue(vec![record(1)]).await?;
    assert_eq!(accepted, Vec::<u64>::new(), "re-POSTing an in-flight seq must accept nothing new");

    // Confirmed.
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let next_coin = queue.in_flight.unwrap().next_coin;
    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 100);
    fx.publisher.tick().await?; // confirms seq 1
    let accepted = fx.publisher.enqueue(vec![record(1)]).await?;
    assert_eq!(accepted, Vec::<u64>::new(), "re-POSTing a confirmed seq must accept nothing new");
    assert!(QueueFile::load(&fx.publisher.data_dir, "operator")?.pending.is_empty());

    Ok(())
}

/// FIXED INTERFACE (b): "A record whose seq is below the watermark but is
/// NOT known as confirmed/queued/in_flight is accepted as a LATE record
/// (backfill) ... the guarantee becomes 'every seq exactly once'". This is
/// the direct unit test for the root-cause bug itself (independent of *how*
/// a seq could end up missing -- a concurrent-write race, an operator
/// manually clearing a stuck entry, anything): the old watermark-only dedup
/// (`seq <= last_accepted_seq` => duplicate) would have silently swallowed
/// this forever; state-based dedup (`QueueFile::record_state`) accepts it.
#[tokio::test]
async fn late_record_below_watermark_and_unknown_everywhere_is_backfilled() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    // Establish a high watermark (seq 10) with seq 2 conspicuously absent
    // from pending/in_flight/confirmed -- exactly the state a lost update
    // would leave behind.
    let mut queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    queue.last_accepted_seq = Some(10);
    queue.pending.push(record(10));
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;

    let status = fx.publisher.record_status(2)?;
    assert_eq!(status.state, "unknown", "seq 2 must show as unknown before backfill, not falsely confirmed");

    let accepted = fx.publisher.enqueue(vec![record(2)]).await?;
    assert_eq!(accepted, vec![2], "a seq below the watermark but unknown everywhere must be accepted, not dropped");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.pending.iter().any(|r| r.seq == 2), "backfilled record must actually be queryable, not just acked");
    assert_eq!(queue.last_accepted_seq, Some(10), "backfill must not move the watermark backward");

    Ok(())
}

/// `GET /v1/oracle/records/{seq}` (FIXED INTERFACE (b)): all four states,
/// each independently observable.
#[tokio::test]
async fn record_status_reports_every_state() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    assert_eq!(fx.publisher.record_status(1)?.state, "unknown");

    fx.publisher.enqueue(vec![record(1)]).await?;
    assert_eq!(fx.publisher.record_status(1)?.state, "queued");

    fx.publisher.tick().await?; // broadcasts -> in flight
    let status = fx.publisher.record_status(1)?;
    assert_eq!(status.state, "in_flight");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let next_coin = queue.in_flight.unwrap().next_coin;
    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 4_800_000);
    fx.publisher.tick().await?; // confirms

    let status = fx.publisher.record_status(1)?;
    assert_eq!(status.state, "confirmed");
    assert_eq!(status.height, Some(4_800_000));
    assert!(status.coin_id.is_some(), "confirmed state must name the resulting singleton coin");

    Ok(())
}

/// Task instruction: "restart with records in queue/in_flight" -- a `tick()`
/// on a brand-new `Publisher` (fresh in-memory state, e.g. after a process
/// restart) reading the *same* `data_dir` must correctly resume: it
/// recognizes the persisted in-flight spend and finalizes it once the chain
/// shows it confirmed, exactly as the original process would have.
#[tokio::test]
async fn restart_resumes_a_pending_in_flight_spend() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 2, accepted: true });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let next_coin = queue.in_flight.unwrap().next_coin;

    // A brand-new `Publisher` (fresh `FakeChainClient`, no shared in-memory
    // state at all) pointed at the *same* data dir -- stands in for a
    // process restart.
    let restarted = Publisher {
        data_dir: fx.publisher.data_dir.clone(),
        operator_name: fx.publisher.operator_name.clone(),
        push_enabled: true,
        chain: FakeChainClient::new(),
        retry_backoff: Duration::from_secs(300),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: std::sync::Arc::new(FeeCoinAllocator::new()),
    };

    restarted.chain.set_confirmed(next_coin.coin_id(), 4_900_000);
    let outcome = restarted.tick().await?;
    assert_eq!(outcome, TickOutcome::Confirmed { records: 2 }, "restart must resume and confirm the persisted spend");

    for seq in [1, 2] {
        assert_eq!(restarted.record_status(seq)?.state, "confirmed");
    }
    Ok(())
}

/// Task instruction: "rebuild merge keeps every record" -- records newly
/// queued *while* a rejected spend is awaiting its fee-bumped rebuild must
/// all end up in the rebuilt batch alongside the original ones, none lost,
/// none duplicated.
#[tokio::test]
async fn rebuild_merge_keeps_every_original_and_newly_queued_record() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;

    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    fx.publisher.chain.set_fee_estimate(0);
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });
    let outcome = fx.publisher.tick().await?; // broadcasts [1, 2] at fee=0, rejected
    assert_eq!(outcome, TickOutcome::Broadcast { records: 2, accepted: false });

    // More records arrive while the rejected spend is awaiting rebuild.
    fx.publisher.enqueue(vec![record(3), record(4)]).await?;

    fx.publisher.chain.set_fee_estimate(1_000_000);
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    let outcome = fx.publisher.tick().await?;
    match outcome {
        TickOutcome::Rebuilt { records, accepted, .. } => {
            assert_eq!(records, 4, "rebuild must merge both the original 2 records and the 2 newly queued ones");
            assert!(accepted);
        }
        other => panic!("expected Rebuilt, got {other:?}"),
    }

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let mut seqs: Vec<u64> = queue.in_flight.as_ref().unwrap().records.iter().map(|r| r.seq).collect();
    seqs.sort();
    assert_eq!(seqs, vec![1, 2, 3, 4], "no record lost or duplicated across the rebuild merge");
    assert!(queue.pending.is_empty());

    Ok(())
}

/// Root-cause regression test (`docs/research/chain_service.md` §10): a
/// `POST /v1/oracle/records` landing *while* `tick()` is deep in
/// `advance_in_flight`/`rebuild_in_flight` (several delayed chain
/// round-trips between its own `QueueFile::load` and its final
/// `save_atomic`) must not be silently lost. Before the `queue_lock` fix,
/// `tick()`'s own later `save_atomic` -- built from a `queue` snapshot taken
/// *before* the concurrent `enqueue()` ever ran -- would overwrite the file
/// and erase the just-acknowledged record, even though `enqueue()` had
/// already durably fsync'ed it and returned. This reproduces the exact
/// interleaving (mirrors `status_answers_while_a_publish_is_in_flight`'s
/// `tokio::join!` pattern) and asserts the record survives on disk.
#[tokio::test]
async fn concurrent_post_during_a_slow_rebuild_does_not_lose_the_record() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;

    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });
    fx.publisher.tick().await?; // broadcasts seq 1 at fee=0, rejected -> in flight

    // The next tick() will re-check the chain (coin_record x2, in_mempool,
    // fee_estimate, find_funding_coin) before it ever saves again -- make
    // every one of those calls slow, and race a POST for seq 2 against it.
    fx.publisher.chain.set_delay(Duration::from_millis(150));
    fx.publisher
        .chain
        .set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });

    let (tick_outcome, enqueue_result) =
        tokio::join!(fx.publisher.tick(), fx.publisher.enqueue(vec![record(2)]));
    assert!(matches!(tick_outcome?, TickOutcome::Rebuilt { .. }));
    let accepted = enqueue_result?;
    assert_eq!(accepted, vec![2], "the concurrent POST must still be accepted");

    // The record must actually be there afterward -- not just acked in
    // memory. It missed this rebuild (the lock serialized it to run after),
    // so it must be sitting in `pending`, ready for the next tick.
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(
        queue.pending.iter().any(|r| r.seq == 2)
            || queue.in_flight.as_ref().is_some_and(|f| f.records.iter().any(|r| r.seq == 2)),
        "seq 2 must survive the race, in pending or in flight -- queue on disk: {queue:?}"
    );
    assert_eq!(fx.publisher.record_status(2)?.state, "queued");

    Ok(())
}

/// Task instruction: "the previous bundle IS in the mempool but pending too
/// long: leave it". Even for a fee-looking rejection, if coinset's own
/// mempool lookup shows the bundle present, the publisher must not rebuild
/// or resubmit -- only wait.
#[tokio::test]
async fn no_rebuild_while_bundle_still_in_mempool() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.enqueue(vec![record(1)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("should be in flight");
    // Coinset's own mempool read disagrees with the cached
    // `last_push_accepted: false` -- the bundle actually made it in.
    fx.publisher.chain.set_in_mempool(in_flight.bundle.name(), true);

    let outcome = fx.publisher.tick().await?;
    assert_eq!(
        outcome,
        TickOutcome::WaitingForConfirmation,
        "must not rebuild/resubmit a bundle coinset shows is already in the mempool"
    );
    assert_eq!(fx.publisher.chain.push_call_count(), 1, "must not push_tx again");

    Ok(())
}

// --- chain-lineage bootstrap/reconciliation (task fix, incident writeup
// `docs/research/chain_service.md` §11: redeploying the new chainsvc onto
// an old/race-corrupted queue file republished 76 already-confirmed
// records, byte-identical, because nothing ever checked the chain itself
// before publishing) ---------------------------------------------------

/// The exact incident this fix exists to prevent: a stale/race-corrupted
/// queue file whose `pending` still holds a record the chain already
/// confirmed, byte-identical, must never be republished.
#[tokio::test]
async fn bootstrap_drops_stale_pending_records_already_confirmed_on_chain() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    // Real generation 1 on chain: seq 1 confirmed, singleton advances to
    // `gen1_coin` (still unspent -- the chain's current tip).
    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen1_coin.coin_id(), 1_000_000);

    // `operator.oracle.json` correctly reflects that (it's a separate file
    // from the corrupted queue below, and in the real incident it was
    // consistent -- only the queue file was stale).
    let oracle_state = OracleState {
        launcher_id: fx.launcher_id,
        manager_synthetic_pk: fx.manager.synthetic_pk,
        current_coin: gen1_coin,
        current_proof_dto: gen1_proof.into(),
        next_sequence: 2,
    };
    save_oracle_state_atomic(&fx.publisher.data_dir, "operator", &oracle_state)?;

    // The stale/race-corrupted queue file: still thinks seq 1 is pending,
    // no checkpoint yet (as if predating this fix entirely).
    let mut queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    queue.pending = vec![record(1)];
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    assert_eq!(outcome.dropped_confirmed, vec![1]);
    assert!(outcome.new_conflicts.is_empty());
    assert_eq!(fx.publisher.chain.push_call_count(), 0, "must never republish an already-confirmed record");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.pending.is_empty(), "stale pending record must be dropped, not republished");
    assert_eq!(queue.confirmed.get(&1).map(|c| c.coin_id.clone()), Some(format!("{}", gen1_coin.coin_id())));
    assert_eq!(fx.publisher.record_status(1)?.state, "confirmed");

    // A follow-up tick() (which now also reconciles first, see module doc)
    // must not build/broadcast anything either.
    let tick_outcome = fx.publisher.tick().await?;
    assert_eq!(tick_outcome, TickOutcome::Idle);
    assert_eq!(fx.publisher.chain.push_call_count(), 0);

    Ok(())
}

/// A locally-queued record whose content *disagrees* with what the chain
/// actually has for the same `seq` must be pulled out of `pending` (never
/// published) but never silently dropped either -- it has to surface as a
/// conflict.
#[tokio::test]
async fn bootstrap_flags_conflict_when_chain_content_differs() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen1_coin.coin_id(), 1_000_000);

    let oracle_state = OracleState {
        launcher_id: fx.launcher_id,
        manager_synthetic_pk: fx.manager.synthetic_pk,
        current_coin: gen1_coin,
        current_proof_dto: gen1_proof.into(),
        next_sequence: 2,
    };
    save_oracle_state_atomic(&fx.publisher.data_dir, "operator", &oracle_state)?;

    // Local pending record for seq 1 has DIFFERENT content than what's
    // actually confirmed on chain.
    let mut divergent = record(1);
    divergent.price_cents += 1;
    let mut queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    queue.pending = vec![divergent];
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    assert_eq!(outcome.new_conflicts, vec![1]);
    assert!(outcome.dropped_confirmed.is_empty());
    assert_eq!(fx.publisher.chain.push_call_count(), 0);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.pending.is_empty(), "a conflicting record must be pulled out of pending too -- never republished");
    assert!(!queue.confirmed.contains_key(&1), "a conflict must not be recorded as confirmed");
    let conflict = queue.conflicts.get(&1).expect("seq 1 must be recorded as a conflict");
    assert_eq!(conflict.local, divergent);
    assert_eq!(conflict.chain, record(1));

    let status = fx.publisher.status().await?;
    assert_eq!(status.conflicts, vec![1], "must surface in GET /v1/oracle/status, never silently");
    assert_eq!(fx.publisher.record_status(1)?.state, "conflict");

    Ok(())
}

/// "Persist the checkpoint so later restarts only scan forward": a second
/// reconciliation must resume from the previous scan's tip, not re-walk the
/// whole lineage from the launcher every time.
#[tokio::test]
async fn checkpoint_resume_only_scans_forward_from_last_tip() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen1_coin.coin_id(), 1_000_000);

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    assert_eq!(outcome.generations_advanced, 1);
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(queue.chain_scan_checkpoint, Some(gen1_coin));

    // Chain advances one more generation.
    let (gen2_coin, _gen2_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        gen1_coin,
        gen1_proof,
        1_000_010,
        &[record(2)],
    )?;
    fx.publisher.chain.set_confirmed(gen2_coin.coin_id(), 1_000_010);

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    // Resuming from the checkpoint (`gen1_coin`) walks exactly the one NEW
    // generation -- re-walking from the launcher every time would report 2
    // (eve->gen1->gen2), not 1.
    assert_eq!(
        outcome.generations_advanced, 1,
        "must resume from the persisted checkpoint, not rescan from the launcher"
    );

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(queue.chain_scan_checkpoint, Some(gen2_coin));

    Ok(())
}

/// "Lineage with duplicates: first occurrence wins" -- exactly this
/// incident's shape (a stale queue file's pending rebuilt into a fresh,
/// duplicate spend, byte-identical content, at a later generation). The
/// authoritative confirmed map must keep pointing at the *first* on-chain
/// occurrence, never the later duplicate.
#[tokio::test]
async fn lineage_duplicate_seq_keeps_first_occurrence() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen1_coin.coin_id(), 1_000_000);

    let outcome = walk_lineage(&fx.publisher.chain, fx.eve_coin).await?;
    let first = outcome.confirmed.get(&1).cloned().expect("seq 1 must be found");
    assert_eq!(first.height, Some(1_000_000));
    assert_eq!(first.coin_id, format!("{}", gen1_coin.coin_id()));

    // A later, buggy republish announces seq 1 again with byte-identical
    // content at a different (later) generation -- this incident exactly.
    let (gen2_coin, _gen2_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        gen1_coin,
        gen1_proof,
        1_000_010,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen2_coin.coin_id(), 1_000_010);

    let outcome = walk_lineage(&fx.publisher.chain, fx.eve_coin).await?;
    let still_first = outcome.confirmed.get(&1).cloned().expect("seq 1 must still be found");
    assert_eq!(still_first, first, "the first on-chain occurrence must win, not the later duplicate");
    assert_ne!(
        still_first.coin_id,
        format!("{}", gen2_coin.coin_id()),
        "must not have been overwritten by the duplicate"
    );
    assert_eq!(outcome.generations_advanced, 2);

    Ok(())
}

/// Task instruction item 2 ("also run that forward scan periodically
/// (cheap)"): the *regular* `tick()` loop reconciles against the chain on
/// its own, not just the one-off startup `bootstrap_from_chain()` -- a
/// stale/corrupted `pending` must never be republished even if the operator
/// never explicitly re-bootstraps (e.g. a long-running process whose queue
/// file got clobbered by something external without a restart).
#[tokio::test]
async fn tick_also_reconciles_and_never_republishes_stale_pending() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[record(1)],
    )?;
    fx.publisher.chain.set_confirmed(gen1_coin.coin_id(), 1_000_000);

    let oracle_state = OracleState {
        launcher_id: fx.launcher_id,
        manager_synthetic_pk: fx.manager.synthetic_pk,
        current_coin: gen1_coin,
        current_proof_dto: gen1_proof.into(),
        next_sequence: 2,
    };
    save_oracle_state_atomic(&fx.publisher.data_dir, "operator", &oracle_state)?;

    let mut queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    queue.pending = vec![record(1)];
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;

    // No explicit `bootstrap_from_chain()` call here -- only the regular
    // periodic `tick()`, exactly as `main.rs`'s background loop drives it.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Idle, "the stale record was reconciled away, nothing left to publish");
    assert_eq!(fx.publisher.chain.push_call_count(), 0, "must never republish an already-confirmed record");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.pending.is_empty());
    assert_eq!(queue.confirmed.get(&1).map(|c| c.coin_id.clone()), Some(format!("{}", gen1_coin.coin_id())));
    assert_eq!(queue.chain_scan_checkpoint, Some(gen1_coin), "tick() must also persist the scan checkpoint");

    Ok(())
}

// --- publisher-wedged incident and its fix (task fix, `docs/research/
// chain_service.md` §12: reconciliation could clear an all-confirmed
// `in_flight` without ever advancing `OracleState`, since only
// `advance_in_flight` used to do that and `tick()`'s reconciliation runs
// first -- every following tick then bailed with "already spent on chain
// but there is no matching in-flight spend", forever, with `stuckReason`
// staying `null` and `/health` staying healthy throughout) ------------------

/// The exact reported incident. Before this fix: the second `tick()` below
/// would return `Err` (`start_new_batch`'s "refusing to build a new spend on
/// stale state") instead of publishing seq 2, because reconciliation had
/// already cleared `in_flight` (all its records confirmed identically on
/// chain) without ever advancing `OracleState.current_coin` -- and by the
/// time `tick()` reaches the `in_flight`/`pending` checks below
/// reconciliation, `in_flight` is already empty, so `advance_in_flight` (the
/// only code that used to do that advancing) never runs at all this tick.
#[tokio::test]
async fn reconciliation_observing_confirmation_first_still_advances_oracle_state() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    fx.publisher.enqueue(vec![record(1)]).await?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: true });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.clone().expect("batch should be in flight");

    // A second record arrives while the first batch is still unconfirmed --
    // this is the "next pending batch" the fix must still publish once state
    // adopts the tip.
    fx.publisher.enqueue(vec![record(2)]).await?;

    // Register the confirming spend via the *lineage-walk* path, not via
    // `advance_in_flight`'s own `next_coin` check (deliberately never calls
    // `set_confirmed` on `in_flight.next_coin`) -- same deterministic inputs
    // (launcher_id, current_coin, proof, manager, records) `tick()`'s own
    // `start_new_batch` already used, so this produces the exact same
    // `next_coin` (asserted below) with byte-identical announced content,
    // all `reconcile_with_chain`'s `all_confirmed` check needs.
    let (confirmed_next_coin, _) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        5_000_000,
        &[record(1)],
    )?;
    assert_eq!(
        confirmed_next_coin, in_flight.next_coin,
        "sanity: the independently-built confirming spend must match the real in-flight one"
    );

    let outcome = fx.publisher.tick().await?;
    assert_eq!(
        outcome,
        TickOutcome::Broadcast { records: 1, accepted: true },
        "the next pending batch (seq 2) must publish against the adopted tip in the same tick \
         that reconciliation -- not advance_in_flight -- observed the confirmation"
    );
    assert_eq!(fx.publisher.chain.push_call_count(), 2);

    let oracle_state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    assert_eq!(
        oracle_state.current_coin, in_flight.next_coin,
        "OracleState.current_coin must advance to the chain tip even though reconciliation, not \
         advance_in_flight, is what observed the confirmation"
    );

    assert_eq!(fx.publisher.record_status(1)?.state, "confirmed");

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let second_in_flight = queue.in_flight.expect("seq 2's batch should now be in flight");
    assert_eq!(
        second_in_flight.spent_coin, in_flight.next_coin,
        "the new batch must spend the adopted tip, not the stale coin"
    );

    Ok(())
}

/// Task fix: "restart with stale current_coin behind the chain tip -> adopts
/// tip". Simulates a redeployed/restored `operator.oracle.json` that still
/// thinks the singleton is at the eve coin while the chain has already moved
/// two real generations past it -- `bootstrap_from_chain()` (what `main.rs`
/// runs "before accepting or publishing anything") must adopt the actual
/// chain tip, and publishing must continue correctly from there.
#[tokio::test]
async fn bootstrap_adopts_chain_tip_when_current_coin_is_stale_and_behind() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;

    // Two real generations happen on the fake chain -- `operator.oracle.json`
    // is deliberately never updated to match (still at `eve_coin`, `set_up`'s
    // fixture default): exactly what a stale/restored/redeployed state file
    // looks like.
    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        100,
        &[record(1)],
    )?;
    let (gen2_coin, _gen2_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        gen1_coin,
        gen1_proof,
        101,
        &[record(2)],
    )?;

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    assert_eq!(
        outcome.adopted_stale_coin,
        Some(fx.eve_coin),
        "bootstrap must adopt the tip away from the stale eve coin"
    );

    let oracle_state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    assert_eq!(
        oracle_state.current_coin, gen2_coin,
        "current_coin must now be the actual chain tip (behind by two generations), not the stale eve coin"
    );

    // Publishing continues correctly from the adopted tip.
    fx.publisher.enqueue(vec![record(3)]).await?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: true });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("seq 3's batch should be in flight");
    assert_eq!(in_flight.spent_coin, gen2_coin, "must build the new batch against the adopted tip");

    Ok(())
}

/// Task fix: "any repeated tick failure (N consecutive, e.g. 3) must set
/// stuckReason ... and /health must report degraded". Reproduces the
/// reported incident's actual failure mode directly -- `tick()` hard-failing
/// (`start_new_batch`'s "refusing to build a new spend on stale state") with
/// no `InFlightSpend` at all for the older, narrower per-bundle
/// `stuck_reason` check to see -- and asserts both `GET /v1/oracle/status`'s
/// `stuckReason` and the new `GET /health` signal (`Publisher::health_check`)
/// pick it up only once the configured threshold is reached, then clear
/// again once ticking succeeds.
#[tokio::test]
async fn repeated_tick_failures_surface_stuck_reason_and_degrade_health() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.enqueue(vec![record(1)]).await?;

    let oracle_state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    // No lineage data registered for this coin at all (unlike the adoption
    // tests above) -- `tip_lineage_proof` can't derive a proof, so
    // reconciliation declines to adopt and `start_new_batch`'s existing
    // stale-state guard keeps failing every tick, exactly as it did live on
    // the wedged stand before this fix.
    fx.publisher.chain.set_spent(oracle_state.current_coin.coin_id(), 50, 51);

    // Below the threshold (3, `set_up`'s fixture default): not yet degraded.
    assert!(fx.publisher.tick().await.is_err());
    assert!(fx.publisher.tick().await.is_err());
    let status = fx.publisher.status().await?;
    assert!(
        status.stuck_reason.is_none(),
        "must not report stuck before the failure threshold: {:?}",
        status.stuck_reason
    );
    assert!(
        fx.publisher.health_check().await?.is_none(),
        "must not report degraded before the failure threshold"
    );

    // Third consecutive failure crosses the threshold.
    assert!(fx.publisher.tick().await.is_err());
    let status = fx.publisher.status().await?;
    let reason = status.stuck_reason.expect("stuckReason must be set after 3 consecutive tick failures");
    assert!(reason.contains("3 consecutive"), "reason should name the count: {reason}");
    assert!(
        reason.contains("already spent on chain"),
        "reason should include the underlying tick() error text: {reason}"
    );

    let health_reason = fx.publisher.health_check().await?.expect("health_check must report degraded too");
    assert_eq!(health_reason, reason, "status and health must agree -- same underlying signal");

    // Recovery: once the underlying condition clears and a tick succeeds
    // again, the counter resets and both signals clear immediately.
    fx.publisher.chain.set_confirmed(oracle_state.current_coin.coin_id(), 50);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: true });
    let status = fx.publisher.status().await?;
    assert!(status.stuck_reason.is_none(), "a successful tick must reset the consecutive-failure counter");
    assert!(fx.publisher.health_check().await?.is_none());

    Ok(())
}
