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

use std::collections::HashMap;
use std::future::Future;
use std::sync::Mutex;
use std::time::Duration;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, SpendBundle};

use c2_cli::builders::oracle::build_oracle_launch;
use c2_cli::keys::{WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::OracleState;
use c2_svc::oracle::chain_client::{ChainClient, CoinRecordInfo, PushOutcome};
use c2_svc::oracle::publisher::{Publisher, TickOutcome};
use c2_svc::oracle::queue::QueueFile;
use c2_svc::oracle::types::{OracleRecord, Source};
use c2_svc::state_file::save_oracle_state_atomic;

// --- fake chain client -------------------------------------------------

struct FakeChainClientInner {
    /// coin_id -> confirmed/spent info the test controls directly.
    coin_records: HashMap<Bytes32, CoinRecordInfo>,
    fee_mojos: u64,
    funding_amount: u64,
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
}

pub struct FakeChainClient(Mutex<FakeChainClientInner>);

impl FakeChainClient {
    fn new() -> Self {
        Self(Mutex::new(FakeChainClientInner {
            coin_records: HashMap::new(),
            fee_mojos: 0,
            funding_amount: 1_000_000,
            push_calls: 0,
            next_push_outcome: PushOutcome {
                accepted: true,
                status: Some("SUCCESS".to_string()),
                error: None,
            },
            in_mempool: HashMap::new(),
            delay: Duration::ZERO,
        }))
    }

    fn set_delay(&self, delay: Duration) {
        self.0.lock().unwrap().delay = delay;
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
            },
        );
    }

    fn set_push_outcome(&self, outcome: PushOutcome) {
        self.0.lock().unwrap().next_push_outcome = outcome;
    }

    fn push_call_count(&self) -> u32 {
        self.0.lock().unwrap().push_calls
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
    ) -> impl Future<Output = Result<(WalletKey, Coin)>> + Send {
        let (amount, delay) = {
            let inner = self.0.lock().unwrap();
            (inner.funding_amount, inner.delay)
        };
        let wallet_key = derive_wallet_key(master_sk, 0);
        let coin = Coin::new(Bytes32::from([7u8; 32]), wallet_key.puzzle_hash, amount);
        async move {
            if !delay.is_zero() {
                tokio::time::sleep(delay).await;
            }
            if amount < min_amount {
                anyhow::bail!("fake funding coin has {amount} mojos, need >= {min_amount}");
            }
            Ok((wallet_key, coin))
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
}

// --- fixture -------------------------------------------------------------

struct Fixture {
    _tmp: tempfile::TempDir,
    publisher: Publisher<FakeChainClient>,
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
    };

    Ok(Fixture { _tmp: tmp, publisher })
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

    let queued = fx.publisher.enqueue(vec![record(1), record(2), record(3)])?;
    assert_eq!(queued, 3);

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

    fx.publisher.enqueue(vec![record(1)])?;
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 1, accepted: true });

    // More records arrive while the first batch is still unconfirmed.
    let queued = fx.publisher.enqueue(vec![record(2), record(3)])?;
    assert_eq!(queued, 2);

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

    fx.publisher.enqueue(vec![record(1)])?;
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
    fx.publisher.enqueue(vec![record(1)])?;
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
    fx.publisher.enqueue(vec![record(1)])?;

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
    fx.publisher.enqueue(vec![record(1)])?;

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

    fx.publisher.enqueue(vec![record(1), record(2)])?;
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

    fx.publisher.enqueue(vec![record(1)])?;
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

/// Task instruction: once the fee has been bumped up to `max_fee_mojos` and
/// still gets rejected, the publisher must stop escalating and clearly
/// report "needs operator attention" with a reason, both from `tick()` and
/// from `GET /v1/oracle/status`.
#[tokio::test]
async fn fee_cap_reached_reports_stuck_with_reason() -> Result<()> {
    let mut fx = set_up(Duration::from_secs(0))?;
    fx.publisher.max_fee_mojos = 100; // tiny cap -- saturates on the very first rebuild

    fx.publisher.enqueue(vec![record(1)])?;
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
    fx.publisher.enqueue(vec![record(1)])?;
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

/// Task instruction: "the previous bundle IS in the mempool but pending too
/// long: leave it". Even for a fee-looking rejection, if coinset's own
/// mempool lookup shows the bundle present, the publisher must not rebuild
/// or resubmit -- only wait.
#[tokio::test]
async fn no_rebuild_while_bundle_still_in_mempool() -> Result<()> {
    let fx = set_up(Duration::from_secs(0))?;
    fx.publisher.enqueue(vec![record(1)])?;
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
