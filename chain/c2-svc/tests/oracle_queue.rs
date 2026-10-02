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

mod oracle_golden;

use std::collections::HashMap;
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
use c2_svc::oracle::queue::{EnqueueOutcome, QueueFile, ReconcileOutcome};
use c2_svc::oracle::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use c2_svc::oracle::types::{OracleRecord, OracleRecordStatusResponse, Source};
use c2_svc::state_file::save_oracle_state_atomic;

use oracle_golden::{SIX_ELEMENT_MESSAGE_HEX, announcing_coin_spend, fixed_record};

// --- fake chain client -------------------------------------------------

struct FakeChainClientInner {
    /// coin_id -> confirmed/spent info the test controls directly.
    coin_records: HashMap<Bytes32, CoinRecordInfo>,
    fee_mojos: u64,
    /// `fee_estimate_mojos` fails instead of answering `fee_mojos` ("Upstream request timed out",
    /// the 2026-10-02 incident).
    fee_estimate_fails: bool,
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
            // A small, non-zero estimate: since `docs/research/chain_service.md` §17.2 an estimate of
            // 0 (like a failed one) is replaced by the fallback rate, which for the 1,000,000-mojo
            // wallet coin of this fake is too much for the bigger batches -- tests that care about
            // the fee set their own estimate (`set_fee_estimate`).
            fee_mojos: 1_000,
            fee_estimate_fails: false,
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

    /// Registers an additional wallet coin `unspent_coins` reports, alongside the
    /// always-present default `[7u8; 32]`-parented one (task fix,
    /// `docs/research/chain_service.md` §15).
    fn add_funding_coin(&self, coin: Coin) {
        self.0.lock().unwrap().extra_funding_coins.push(coin);
    }


    /// The amount of the default `[7u8; 32]`-parented wallet coin (1,000,000 unless set).
    fn set_funding_amount(&self, amount: u64) {
        self.0.lock().unwrap().funding_amount = amount;
    }

    /// Controls what the *next* `fee_estimate_mojos` call returns (task
    /// instruction: "re-estimate with get_fee_estimate ... on each
    /// consecutive fee rejection").
    fn set_fee_estimate(&self, fee_mojos: u64) {
        self.0.lock().unwrap().fee_mojos = fee_mojos;
    }

    /// Makes `fee_estimate_mojos` fail (or answer again) -- "get_fee_estimate failed: Upstream
    /// request timed out".
    fn set_fee_estimate_fails(&self, fails: bool) {
        self.0.lock().unwrap().fee_estimate_fails = fails;
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
        let (fee, fails, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.fee_mojos, inner.fee_estimate_fails, inner.delay)
        };
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            if fails {
                anyhow::bail!("Upstream request timed out");
            }
            Ok(fee)
        }
    }

    fn unspent_coins(&self, master_sk: &SecretKey) -> impl Future<Output = Result<Vec<(WalletKey, Coin)>>> + Send {
        let wallet_key = derive_wallet_key(master_sk, 0);
        let (amount, delay, extra, coin_records) = {
            let inner = self.0.lock().unwrap();
            (inner.funding_amount, inner.delay, inner.extra_funding_coins.clone(), inner.coin_records.clone())
        };
        let mut candidates = vec![Coin::new(Bytes32::from([7u8; 32]), wallet_key.puzzle_hash, amount)];
        candidates.extend(extra);
        let puzzle_hash = wallet_key.puzzle_hash;
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            // Every candidate the chain has not shown spent, each with a fresh copy of the key
            // (`WalletKey` is not `Clone`) -- what the real wallet scan returns.
            Ok(candidates
                .into_iter()
                .filter(|c| !coin_records.get(&c.coin_id()).is_some_and(|r| r.spent))
                .map(|c| {
                    debug_assert_eq!(c.puzzle_hash, puzzle_hash, "fake wallet coins belong to the operator");
                    (derive_wallet_key(master_sk, 0), c)
                })
                .collect())
        }
    }

    /// No oracle-publisher test here involves a pending fee-coin split (those are in
    /// `tests/fee_allocator.rs`, whose fake records mempool coins), so nothing is ever "in the
    /// mempool" for this fake.
    fn coin_in_mempool(&self, _coin_id: Bytes32) -> impl Future<Output = Result<bool>> + Send {
        let delay = self.0.lock().unwrap().delay;
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            Ok(false)
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
        mempool_replace_after: Duration::from_secs(180),
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
        asset: 0,
    }
}

/// Registers on the fake chain the confirmation of `in_flight`'s own bundle the way the real chain
/// shows it: the singleton coin spent -- with the very spend the bundle contains, so a lineage walk
/// can read its announcements -- and the coin it recreated. Since a bundle that was rebuilt or
/// replaced has earlier versions, the publisher no longer takes "the recreated coin exists" for
/// "all my records are on chain" (`docs/research/chain_service.md` §17.4): it reads the spend.
fn confirm_in_flight(chain: &FakeChainClient, in_flight: &c2_svc::oracle::queue::InFlightSpend, height: u32) {
    let singleton_spend = in_flight
        .bundle
        .coin_spends
        .iter()
        .find(|spend| spend.coin == in_flight.spent_coin)
        .expect("every bundle spends the singleton coin")
        .clone();
    chain.set_lineage_spend(in_flight.spent_coin, height, height, singleton_spend);
    chain.set_confirmed(in_flight.next_coin.coin_id(), height);
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
    fx.publisher.chain.set_fee_estimate(1_000); // a fee the mempool will not take
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some(
            "Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string(),
        ),
    });

    let outcome = fx.publisher.tick().await?; // start_new_batch: broadcasts at the estimated fee, rejected
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: false });
    assert_eq!(fx.publisher.chain.push_call_count(), 1);

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let first_in_flight = queue.in_flight.expect("should be in flight after the rejected broadcast");
    assert_eq!(first_in_flight.fee_mojos, 1_000);
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

    confirm_in_flight(&fx.publisher.chain, &rebuilt, 4_800_000);
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

    confirm_in_flight(&fx.publisher.chain, &rebuilt, 4_738_950);
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
        mempool_replace_after: Duration::from_secs(180),
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

    confirm_in_flight(&restarted.chain, &rebuilt, 4_738_950);
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
    fx.publisher.chain.set_fee_estimate(10); // below the cap, so the first rejection can still be escalated
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });

    let outcome = fx.publisher.tick().await?; // Broadcast at fee=10
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

/// Record v2, `GET /v1/oracle/status`'s `maxKnownSeq` (a server with a lost
/// counter raises it to this before handing out the next seq): the highest seq
/// in ANY state -- each source below is made the sole maximum in turn, so a
/// field that forgot one of them would be caught -- 0 for an empty queue, and
/// `recordFormat` says 2.
#[tokio::test]
async fn status_reports_the_highest_seq_known_in_any_state_and_record_format_2() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let status = fx.publisher.status().await?;
    assert_eq!(status.max_known_seq, 0, "nothing known -> 0");
    assert_eq!(status.record_format, 2);

    type MakeMaximum = Box<dyn Fn(&mut QueueFile)>;
    let low = |q: &mut QueueFile| {
        q.last_accepted_seq = Some(3);
        q.last_confirmed_seq = Some(2);
        q.confirmed.insert(
            1,
            c2_svc::oracle::queue::ConfirmedRecordInfo { height: Some(1), coin_id: "0x01".into(), record: None },
        );
        q.pending = vec![record(2)];
    };
    let scenarios: Vec<(&str, MakeMaximum)> = vec![
        ("last_accepted_seq", Box::new(|q| q.last_accepted_seq = Some(100))),
        ("last_confirmed_seq", Box::new(|q| q.last_confirmed_seq = Some(101))),
        (
            "confirmed map key",
            Box::new(|q| {
                q.confirmed.insert(
                    102,
                    c2_svc::oracle::queue::ConfirmedRecordInfo { height: None, coin_id: "0x66".into(), record: None },
                );
            }),
        ),
        (
            "conflicts key",
            Box::new(|q| {
                q.conflicts.insert(
                    103,
                    c2_svc::oracle::queue::ConflictInfo {
                        local: record(103),
                        chain: record(103),
                        chain_height: None,
                        chain_coin_id: "0x67".into(),
                    },
                );
            }),
        ),
        ("pending record", Box::new(|q| q.pending.push(record(104)))),
        (
            "in-flight record",
            Box::new(|q| {
                let mut f = in_flight_stub(&[record(105), record(7)]);
                f.spent_coin = Coin::new(Bytes32::from([1u8; 32]), Bytes32::from([2u8; 32]), 1);
                q.in_flight = Some(f);
            }),
        ),
    ];
    for (i, (source, make_maximum)) in scenarios.iter().enumerate() {
        let mut queue = QueueFile::default();
        low(&mut queue);
        make_maximum(&mut queue);
        let expected = 100 + i as u64;
        assert_eq!(queue.max_known_seq(), expected, "{source} must count towards maxKnownSeq");
        queue.save_atomic(&fx.publisher.data_dir, "operator")?;
        assert_eq!(fx.publisher.status().await?.max_known_seq, expected, "{source}: via GET /v1/oracle/status");
    }
    Ok(())
}

/// A minimal in-flight spend around `records`, for tests that only look at the
/// queue file's bookkeeping.
fn in_flight_stub(records: &[OracleRecord]) -> c2_svc::oracle::queue::InFlightSpend {
    c2_svc::oracle::queue::InFlightSpend {
        records: records.to_vec(),
        spent_coin: Coin::new(Bytes32::from([3u8; 32]), Bytes32::from([4u8; 32]), 1),
        next_coin: Coin::new(Bytes32::from([5u8; 32]), Bytes32::from([4u8; 32]), 1),
        next_proof: c2_cli::state::ProofDto::Eve { parent_parent_coin_info: Bytes32::from([6u8; 32]), parent_amount: 1 },
        bundle: SpendBundle::new(vec![], chia_bls::Signature::default()),
        bundle_id: "0x00".into(),
        fee_mojos: 0,
        broadcast_at_unix: 0,
        last_attempt_at_unix: 0,
        attempts: 1,
        last_push_accepted: true,
        last_push_error: None,
        superseded: Vec::new(),
        replace_blocked: None,
    }
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
        mempool_replace_after: Duration::from_secs(180),
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
    fx.publisher.chain.set_fee_estimate(1_000);
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

/// Bootstraps a fixture whose chain announced `record(1)` with `chain_asset`
/// while the local queue still holds the same seq -- identical price, hash and
/// timestamps -- with `local_asset`.
async fn bootstrap_against_chain_record_with_asset(
    chain_asset: u32,
    local_asset: u32,
) -> Result<(Fixture, ReconcileOutcome)> {
    let fx = set_up(Duration::from_secs(300))?;
    let (gen1_coin, gen1_proof) = publish_lineage_generation(
        &fx.publisher.chain,
        fx.launcher_id,
        &fx.manager,
        fx.eve_coin,
        fx.eve_proof,
        1_000_000,
        &[OracleRecord { asset: chain_asset, ..record(1) }],
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
    queue.pending = vec![OracleRecord { asset: local_asset, ..record(1) }];
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;

    let outcome = fx.publisher.bootstrap_from_chain().await?;
    Ok((fx, outcome))
}

/// Record v2: the reconcile comparison includes `asset`. A record that differs
/// from the chain's ONLY in its market index is a different record -- a
/// conflict surfaced to the operator, never "already confirmed" (that would
/// mark an ETH round anchored when the chain holds a BTC one) and never
/// republished. Both directions: chain 6-element vs local asset 3, and chain
/// 7-element (asset 3) vs local asset 0.
#[tokio::test]
async fn reconcile_flags_a_conflict_when_only_the_asset_differs() -> Result<()> {
    for (chain_asset, local_asset) in [(0, 3), (3, 0), (3, 4)] {
        let (fx, outcome) = bootstrap_against_chain_record_with_asset(chain_asset, local_asset).await?;
        assert_eq!(outcome.new_conflicts, vec![1], "chain asset {chain_asset} vs local asset {local_asset}");
        assert!(outcome.dropped_confirmed.is_empty());
        assert_eq!(fx.publisher.chain.push_call_count(), 0, "a conflicting record must never be published");

        let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
        assert!(queue.pending.is_empty());
        assert!(!queue.confirmed.contains_key(&1), "an asset mismatch must not be recorded as confirmed");
        let conflict = queue.conflicts.get(&1).expect("seq 1 must be recorded as a conflict");
        assert_eq!(conflict.local.asset, local_asset);
        assert_eq!(conflict.chain.asset, chain_asset);
        assert_eq!(fx.publisher.record_status(1)?.state, "conflict");
        assert_eq!(fx.publisher.status().await?.conflicts, vec![1]);
    }
    Ok(())
}

/// Positive control for the test above: the same record with the same non-zero
/// asset on both sides is identical, so it is dropped as already confirmed.
#[tokio::test]
async fn reconcile_treats_an_identical_seven_element_record_as_already_confirmed() -> Result<()> {
    let (fx, outcome) = bootstrap_against_chain_record_with_asset(3, 3).await?;
    assert_eq!(outcome.dropped_confirmed, vec![1]);
    assert!(outcome.new_conflicts.is_empty());
    assert_eq!(fx.publisher.record_status(1)?.state, "confirmed");
    assert_eq!(fx.publisher.chain.push_call_count(), 0);
    Ok(())
}

/// Records with different assets queue, persist and ride in one spend side by
/// side; the asset is still on every record in the in-flight spend and after a
/// reload from disk (a restart must not turn an asset-3 record into BTC).
#[tokio::test]
async fn records_of_different_assets_share_one_spend_and_keep_their_asset() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let accepted = fx
        .publisher
        .enqueue(vec![record(1), OracleRecord { asset: 3, ..record(2) }, OracleRecord { asset: 200, ..record(3) }])
        .await?;
    assert_eq!(accepted, vec![1, 2, 3]);

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 3, accepted: true });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("one spend in flight");
    assert_eq!(in_flight.records.iter().map(|r| (r.seq, r.asset)).collect::<Vec<_>>(), vec![(1, 0), (2, 3), (3, 200)]);
    Ok(())
}

/// `MAX_RECORDS_PER_SPEND` (40) caps a fresh batch: 45 pending records go out
/// as 40 now and 5 on the next spend, oldest first, none lost.
#[tokio::test]
async fn a_new_batch_takes_at_most_the_maximum_records_per_spend() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let total = MAX_RECORDS_PER_SPEND as u64 + 5;
    fx.publisher.enqueue((1..=total).map(record).collect()).await?;

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: MAX_RECORDS_PER_SPEND, accepted: true });
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let in_flight = queue.in_flight.clone().expect("in flight");
    assert_eq!(in_flight.records.iter().map(|r| r.seq).collect::<Vec<_>>(), (1..=MAX_RECORDS_PER_SPEND as u64).collect::<Vec<_>>());
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), (MAX_RECORDS_PER_SPEND as u64 + 1..=total).collect::<Vec<_>>());

    fx.publisher.chain.set_confirmed(in_flight.next_coin.coin_id(), 100);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: MAX_RECORDS_PER_SPEND });
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 5, accepted: true });
    Ok(())
}

/// The other place that caps a spend: the rebuild merge. 38 records in a
/// rejected spend plus 5 newly queued ones: the rebuilt spend takes exactly the
/// 2 that still fit under the maximum, the other 3 stay pending.
#[tokio::test]
async fn the_rebuild_merge_never_exceeds_the_maximum_records_per_spend() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    let first = MAX_RECORDS_PER_SPEND as u64 - 2;
    fx.publisher.enqueue((1..=first).map(record).collect()).await?;
    fx.publisher.chain.set_fee_estimate(1_000);
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: first as usize, accepted: false });

    fx.publisher.enqueue((first + 1..=first + 5).map(record).collect()).await?;
    fx.publisher.chain.set_fee_estimate(1_000_000);
    fx.publisher.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    match fx.publisher.tick().await? {
        TickOutcome::Rebuilt { records, .. } => assert_eq!(records, MAX_RECORDS_PER_SPEND),
        other => panic!("expected Rebuilt, got {other:?}"),
    }
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(queue.in_flight.as_ref().unwrap().records.len(), MAX_RECORDS_PER_SPEND);
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![first + 3, first + 4, first + 5]);
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

/// Record v2 characterization (2026-10-01, written before `asset` existed):
/// the lineage decoder reads the 6-element announcement every BTC record has
/// ever been published as. The announcement here is the frozen golden byte
/// string fed through a hand-made spend, not the production encoder's output,
/// so this keeps proving "old history still decodes" even if the encoder
/// changes.
#[tokio::test]
async fn lineage_decodes_the_golden_six_element_announcement() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let message = hex::decode(SIX_ELEMENT_MESSAGE_HEX)?;
    fx.publisher.chain.set_lineage_spend(
        fx.eve_coin,
        1_000_000,
        1_000_000,
        announcing_coin_spend(fx.eve_coin, &message),
    );

    let outcome = walk_lineage(&fx.publisher.chain, fx.eve_coin).await?;

    assert!(outcome.decode_warnings.is_empty(), "unexpected decode warnings: {:?}", outcome.decode_warnings);
    let on_chain = outcome.confirmed.get(&1145).expect("seq 1145 must decode out of the 6-element message");
    assert_eq!(on_chain.record, fixed_record());
    assert_eq!(on_chain.height, Some(1_000_000));
    assert_eq!(outcome.generations_advanced, 1);
    Ok(())
}

/// The very first announcement on the real singleton (week 1, before Р-41:
/// `(priceCents ts seq)`, three elements, `docs/research/oracle_verification.md`
/// §5) is not an oracle record. It must be reported as a warning and skipped,
/// never misread as a record with shifted fields -- and the walk goes on.
#[tokio::test]
async fn lineage_skips_the_three_field_week_one_announcement_with_a_warning() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let three_field = hex::decode("ff8400801ed4ff846ab6a055ff0180")?;
    fx.publisher.chain.set_lineage_spend(
        fx.eve_coin,
        1_000_000,
        1_000_000,
        announcing_coin_spend(fx.eve_coin, &three_field),
    );

    let outcome = walk_lineage(&fx.publisher.chain, fx.eve_coin).await?;

    assert!(outcome.confirmed.is_empty(), "a 3-element announcement must not decode into a record");
    assert_eq!(outcome.decode_warnings.len(), 1, "{:?}", outcome.decode_warnings);
    assert!(outcome.decode_warnings[0].contains("did not decode as an oracle record"));
    assert_eq!(outcome.generations_advanced, 1);
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

// --- C1: a failed fee estimate is never a fee of 0 (`docs/research/chain_service.md` §17.2) -----------------------

/// The cost the publisher priced a bundle at: of the singleton spend alone, which is what
/// `start_new_batch` and `rebuild_in_flight` measure (the fee spend is added after the fee is chosen).
fn draft_cost_of(in_flight: &c2_svc::oracle::queue::InFlightSpend) -> u64 {
    let spends: Vec<CoinSpend> =
        in_flight.bundle.coin_spends.iter().filter(|spend| spend.coin == in_flight.spent_coin).cloned().collect();
    c2_cli::cost::measure_bundle_cost(&spends).expect("cost").total_cost
}

/// `ceil(rate * cost / 1000)`, the fee at a rate in millimojos per unit of cost.
fn fee_at(rate_millimojos: u64, cost: u64) -> u64 {
    (u128::from(rate_millimojos) * u128::from(cost)).div_ceil(1000) as u64
}

/// 2026-10-02: `get_fee_estimate` answered "Upstream request timed out", the publisher fell back to
/// fee 0, the node accepted the fee-0 bundle and it sat in the mempool for ten minutes. Now the first
/// attempt is priced at the floor rate (0.1 mojo per cost) times the bundle's cost, with a fee coin
/// attached -- never nothing -- and the same holds when the estimate "succeeds" with 0.
#[tokio::test]
async fn a_failed_or_zero_fee_estimate_is_priced_at_the_floor_rate_never_at_zero() -> Result<()> {
    for failing in [true, false] {
        let fx = set_up(Duration::from_secs(300))?;
        fx.publisher.chain.set_funding_amount(100_000_000);
        if failing {
            fx.publisher.chain.set_fee_estimate_fails(true);
        } else {
            fx.publisher.chain.set_fee_estimate(0);
        }
        fx.publisher.enqueue((1..=18).map(record).collect()).await?;
        assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 18, accepted: true });

        let in_flight = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
        let expected = fee_at(100, draft_cost_of(&in_flight));
        assert!(expected > 1_000_000, "sanity: 0.1 mojo per cost of an 18-record bundle is millions of mojos, got {expected}");
        assert_eq!(in_flight.fee_mojos, expected, "failing={failing}: priced at the floor rate times the draft's cost");
        assert!(fee_coin_of(&in_flight.bundle, in_flight.spent_coin).is_some(), "a fee coin pays it");
    }
    Ok(())
}

/// After a successful estimate the fallback is *that* rate, not the floor.
#[tokio::test]
async fn after_a_successful_estimate_a_failure_uses_that_rate() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.chain.set_funding_amount(100_000_000);
    fx.publisher.chain.set_fee_estimate(500_000);
    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 2, accepted: true });
    let first = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    assert_eq!(first.fee_mojos, 500_000);
    let remembered_rate = (500_000u128 * 1000).div_ceil(u128::from(draft_cost_of(&first))) as u64;
    assert!(remembered_rate < 100, "the estimate is cheaper than the floor, so the two are told apart: {remembered_rate}");

    confirm_in_flight(&fx.publisher.chain, &first, 100);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 2 });

    // The estimate is down for the next batch.
    fx.publisher.chain.set_fee_estimate_fails(true);
    fx.publisher.enqueue(vec![record(3), record(4), record(5)]).await?;
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 3, accepted: true });
    let second = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    assert_eq!(second.fee_mojos, fee_at(remembered_rate, draft_cost_of(&second)), "the last successful estimate's rate");
    assert!(second.fee_mojos > 0);
    Ok(())
}

/// Whatever the fallback comes to, `C2_MAX_FEE_MOJOS` caps it.
#[tokio::test]
async fn the_fallback_fee_is_capped_at_the_maximum_fee() -> Result<()> {
    let mut fx = set_up(Duration::from_secs(300))?;
    fx.publisher.max_fee_mojos = 700_000;
    fx.publisher.chain.set_fee_estimate_fails(true);
    fx.publisher.enqueue((1..=18).map(record).collect()).await?;
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 18, accepted: true });
    let in_flight = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    assert!(fee_at(100, draft_cost_of(&in_flight)) > 700_000, "the floor alone would be above the cap");
    assert_eq!(in_flight.fee_mojos, 700_000);
    Ok(())
}

/// A rebuild after a fee rejection with `get_fee_estimate` down: the old code's "falling back to 0"
/// plus `.max(1)` could pay ONE mojo; now the estimate is the fallback rate and the usual doubling
/// applies on top.
#[tokio::test]
async fn a_rebuild_with_the_estimate_down_is_priced_at_the_fallback_not_at_one_mojo() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.chain.set_funding_amount(100_000_000);
    fx.publisher.chain.set_fee_estimate_fails(true);
    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 2, accepted: false });
    let first = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    let floor_fee = fee_at(100, draft_cost_of(&first));
    assert_eq!(first.fee_mojos, floor_fee);

    fx.publisher.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    match fx.publisher.tick().await? {
        TickOutcome::Rebuilt { fee_mojos, accepted, .. } => {
            assert!(accepted);
            assert_eq!(fee_mojos, 2 * floor_fee, "max(2 x old, fallback) with the estimate still down");
        }
        other => panic!("expected Rebuilt, got {other:?}"),
    }
    Ok(())
}

// --- C3: settling the confirmation of the in-flight bundle (`docs/research/chain_service.md` §17.5) ----------------------

/// A crash after `operator.oracle.json` was advanced but before the queue file was saved: the queue
/// still shows the bundle in flight although the chain consumed its coin and the state has moved on.
/// The next pass reaches the same accounting from the same chain and does not advance the state a
/// second time.
#[tokio::test]
async fn settling_a_confirmation_is_idempotent_after_a_crash_between_the_two_saves() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 2, accepted: true });
    let in_flight = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    let queue_path = QueueFile::path(&fx.publisher.data_dir, "operator");
    let queue_before = std::fs::read(&queue_path)?;

    confirm_in_flight(&fx.publisher.chain, &in_flight, 300);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 2 });
    let state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    assert_eq!((state.current_coin, state.next_sequence), (in_flight.next_coin, 2));

    // The queue file is as it was before the settlement: the crash.
    std::fs::write(&queue_path, queue_before)?;
    assert!(QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.is_some());

    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 2 });
    let state = c2_svc::state_file::load_oracle_state(&fx.publisher.data_dir, "operator")?;
    assert_eq!((state.current_coin, state.next_sequence), (in_flight.next_coin, 2), "advanced once, not twice");
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.in_flight.is_none());
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), vec![1, 2]);
    Ok(())
}

/// The consuming spend is on chain but coinset cannot give it up in a form that reads (here: an
/// announcement of the old three-field shape). For a bundle that has earlier versions -- here: a
/// rebuild -- the publisher does not guess which records were published: it waits, keeps the scan
/// checkpoint at the coin so the next scan reads the spend again, and settles from what the chain
/// then says.
#[tokio::test]
async fn a_confirming_spend_that_cannot_be_read_is_not_guessed_at_for_a_bundle_with_earlier_versions() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.enqueue(vec![record(1), record(2)]).await?;
    fx.publisher.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction 0xabc, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 2, accepted: false });
    fx.publisher.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    assert!(matches!(fx.publisher.tick().await?, TickOutcome::Rebuilt { .. }));
    let rebuilt = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    assert_eq!(rebuilt.superseded.len(), 1, "the rejected first version is on record");

    // The coin is consumed, its successor exists -- but the spend announces something unreadable.
    let three_field = hex::decode("ff8400801ed4ff846ab6a055ff0180")?;
    fx.publisher.chain.set_lineage_spend(
        rebuilt.spent_coin,
        300,
        300,
        announcing_coin_spend(rebuilt.spent_coin, &three_field),
    );
    fx.publisher.chain.set_confirmed(rebuilt.next_coin.coin_id(), 300);

    assert_eq!(fx.publisher.tick().await?, TickOutcome::WaitingForConfirmation);
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.in_flight.is_some(), "nothing is concluded from a spend that cannot be read");
    assert!(queue.confirmed.is_empty(), "no record is marked confirmed on a guess");
    assert_eq!(queue.chain_scan_checkpoint, Some(rebuilt.spent_coin), "the scan will read that generation again");

    // The spend becomes readable (here: the real one). The next tick settles it from the chain.
    confirm_in_flight(&fx.publisher.chain, &rebuilt, 300);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 2 });
    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert!(queue.in_flight.is_none());
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), vec![1, 2]);
    Ok(())
}

// --- a collision is visible: `conflictSeqs` and the held content (`docs/research/chain_service.md` §18.2) -------

/// `GET /v1/oracle/records/{seq}` carries the content of the record the service holds.
fn assert_holds(status: &OracleRecordStatusResponse, record: &OracleRecord) {
    assert_eq!(status.results_hash, Some(format!("0x{}", record.results_hash)), "{status:?}");
    assert_eq!(status.price_cents, Some(record.price_cents), "{status:?}");
    assert_eq!(status.asset, Some(record.asset), "{status:?}");
    assert_eq!(status.ts, Some(record.ts), "{status:?}");
}

fn assert_holds_nothing(status: &OracleRecordStatusResponse) {
    assert_eq!(
        (&status.results_hash, status.price_cents, status.asset, status.ts),
        (&None, None, None, None),
        "{status:?}"
    );
}

/// What `submit` says about `incoming`, and that it left the queue file exactly as it was.
async fn submit_changing_nothing(fx: &Fixture, incoming: Vec<OracleRecord>) -> Result<EnqueueOutcome> {
    let path = QueueFile::path(&fx.publisher.data_dir, "operator");
    let before = std::fs::read(&path)?;
    let outcome = fx.publisher.submit(incoming).await?;
    assert_eq!(std::fs::read(&path)?, before, "{outcome:?} wrote the queue file");
    Ok(outcome)
}

/// One record through its whole life -- queued, in flight, confirmed by the chain walk -- and at every stage the
/// same two questions: a resend of the record is the silent no-op it always was, and a record that differs under the
/// same `seq` is reported in `conflictSeqs`, not queued, and changes nothing. `GET` carries the content of what the
/// service holds, so the server can tell its own record from the foreign one.
#[tokio::test]
async fn a_different_record_under_a_held_seq_is_reported_at_every_stage_and_never_queued() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    let mine = OracleRecord { asset: 3, ..record(1) };
    let foreign = OracleRecord { price_cents: mine.price_cents + 1, ..mine };
    let status = fx.publisher.record_status(1)?;
    assert_eq!(status.state, "unknown");
    assert_holds_nothing(&status);

    // queued
    assert_eq!(fx.publisher.submit(vec![mine]).await?, EnqueueOutcome { accepted: vec![1], conflicts: vec![] });
    let status = fx.publisher.record_status(1)?;
    assert_eq!(status.state, "queued");
    assert_holds(&status, &mine);
    assert_eq!(submit_changing_nothing(&fx, vec![mine]).await?, EnqueueOutcome::default());
    assert_eq!(submit_changing_nothing(&fx, vec![foreign]).await?, EnqueueOutcome { accepted: vec![], conflicts: vec![1] });

    // in flight
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    let status = fx.publisher.record_status(1)?;
    assert_eq!(status.state, "in_flight");
    assert_holds(&status, &mine);
    assert_eq!(submit_changing_nothing(&fx, vec![mine]).await?, EnqueueOutcome::default());
    assert_eq!(submit_changing_nothing(&fx, vec![foreign]).await?, EnqueueOutcome { accepted: vec![], conflicts: vec![1] });

    // confirmed: the chain walk reads the spend, the way every healthy batch is settled
    let in_flight = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight");
    confirm_in_flight(&fx.publisher.chain, &in_flight, 4_800_000);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 1 });
    let status = fx.publisher.record_status(1)?;
    assert_eq!((status.state, status.height), ("confirmed", Some(4_800_000)));
    assert_holds(&status, &mine);
    assert_eq!(submit_changing_nothing(&fx, vec![mine]).await?, EnqueueOutcome::default());
    assert_eq!(submit_changing_nothing(&fx, vec![foreign]).await?, EnqueueOutcome { accepted: vec![], conflicts: vec![1] });

    // One request, several answers; and the plain `enqueue` still returns only what was accepted.
    let outcome = fx.publisher.submit(vec![record(3), foreign, mine, record(2)]).await?;
    assert_eq!(outcome, EnqueueOutcome { accepted: vec![2, 3], conflicts: vec![1] });
    assert_eq!(fx.publisher.enqueue(vec![foreign, record(4)]).await?, vec![4]);
    Ok(())
}

/// The same when the record is confirmed the other way a spend is settled: the confirming spend cannot be read, and a
/// bundle that was never replaced is taken to be the one that confirmed (`settle_confirmed_in_flight`).
#[tokio::test]
async fn a_record_confirmed_without_a_readable_spend_keeps_its_content_too() -> Result<()> {
    let fx = set_up(Duration::from_secs(300))?;
    let mine = record(1);
    fx.publisher.enqueue(vec![mine]).await?;
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    let next_coin = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight").next_coin;
    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 4_900_000);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 1 });

    let queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    assert_eq!(queue.confirmed[&1].record, Some(mine));
    assert_holds(&fx.publisher.record_status(1)?, &mine);
    let different = OracleRecord { ts: mine.ts + 1, ..mine };
    assert_eq!(fx.publisher.submit(vec![different]).await?.conflicts, vec![1]);
    Ok(())
}

/// A seq the chain walk found on chain with other content than this service had queued is `conflict`: `GET` carries
/// the record the CHAIN has, like the height and the coin it reports. The service's own record for it, sent again, is
/// a collision; the chain's is a repeat.
#[tokio::test]
async fn a_conflicting_seq_answers_with_the_record_the_chain_has() -> Result<()> {
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
    save_oracle_state_atomic(
        &fx.publisher.data_dir,
        "operator",
        &OracleState {
            launcher_id: fx.launcher_id,
            manager_synthetic_pk: fx.manager.synthetic_pk,
            current_coin: gen1_coin,
            current_proof_dto: gen1_proof.into(),
            next_sequence: 2,
        },
    )?;
    let mut queue = QueueFile::load(&fx.publisher.data_dir, "operator")?;
    let divergent = OracleRecord { price_cents: record(1).price_cents + 1, ..record(1) };
    queue.pending = vec![divergent];
    queue.save_atomic(&fx.publisher.data_dir, "operator")?;
    fx.publisher.bootstrap_from_chain().await?;

    let status = fx.publisher.record_status(1)?;
    assert_eq!((status.state, status.height), ("conflict", Some(1_000_000)));
    assert_holds(&status, &record(1));
    assert_ne!(status.price_cents, Some(divergent.price_cents), "not the record that lost");
    assert_eq!(submit_changing_nothing(&fx, vec![record(1)]).await?, EnqueueOutcome::default());
    assert_eq!(submit_changing_nothing(&fx, vec![divergent]).await?.conflicts, vec![1]);
    Ok(())
}

/// A queue file written before the content was kept -- confirmed entries with a height and a coin and nothing else, as
/// the stand's file is. It loads; its entries answer exactly as they did, with the four content fields `null`; a record
/// sent for one of them is the silent no-op it was, whatever it contains (there is nothing to compare it with); and the
/// file is written back with those entries unchanged -- while the records confirmed after the upgrade keep their content.
#[tokio::test]
async fn a_queue_file_written_before_the_content_was_kept_still_loads_and_answers_as_before() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    let old_file = r#"{
  "pending": [],
  "in_flight": null,
  "last_accepted_seq": 3,
  "last_confirmed_seq": 3,
  "last_confirmed_height": 4765102,
  "confirmed": {
    "1": {"height": 4765100, "coin_id": "0x1111111111111111111111111111111111111111111111111111111111111111"},
    "2": {"height": 4765101, "coin_id": "0x2222222222222222222222222222222222222222222222222222222222222222"},
    "3": {"height": 4765102, "coin_id": "0x3333333333333333333333333333333333333333333333333333333333333333"}
  },
  "conflicts": {},
  "chain_scan_checkpoint": null
}"#;
    std::fs::write(QueueFile::path(&fx.publisher.data_dir, "operator"), old_file)?;
    let old_confirmed = serde_json::from_str::<serde_json::Value>(old_file)?["confirmed"].clone();

    let status = fx.publisher.record_status(2)?;
    assert_eq!((status.state, status.height), ("confirmed", Some(4_765_101)));
    assert_eq!(status.coin_id.as_deref(), Some("0x2222222222222222222222222222222222222222222222222222222222222222"));
    assert_holds_nothing(&status);
    for seq in [1, 2, 3] {
        assert_eq!(
            submit_changing_nothing(&fx, vec![OracleRecord { asset: 7, ..record(seq) }, record(seq)]).await?,
            EnqueueOutcome::default(),
            "seq {seq}: no content to compare with, so nothing is reported -- and nothing queued"
        );
    }
    assert_eq!(fx.publisher.status().await?.max_known_seq, 3);

    // The next record is confirmed after the upgrade: it keeps its content, the old entries do not gain any.
    assert_eq!(fx.publisher.submit(vec![record(4)]).await?, EnqueueOutcome { accepted: vec![4], conflicts: vec![] });
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    let next_coin = QueueFile::load(&fx.publisher.data_dir, "operator")?.in_flight.expect("in flight").next_coin;
    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 4_765_200);
    assert_eq!(fx.publisher.tick().await?, TickOutcome::Confirmed { records: 1 });

    assert_holds(&fx.publisher.record_status(4)?, &record(4));
    assert_eq!(fx.publisher.submit(vec![OracleRecord { ts: 1, ..record(4) }]).await?.conflicts, vec![4]);
    assert_holds_nothing(&fx.publisher.record_status(1)?);
    let written: serde_json::Value = serde_json::from_slice(&std::fs::read(QueueFile::path(&fx.publisher.data_dir, "operator"))?)?;
    for seq in ["1", "2", "3"] {
        assert_eq!(written["confirmed"][seq], old_confirmed[seq], "the old entry {seq} was written back unchanged");
    }
    assert!(written["confirmed"]["4"]["record"].is_object());
    Ok(())
}
