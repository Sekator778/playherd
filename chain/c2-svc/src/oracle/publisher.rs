//! Orchestrates the durable queue (`queue.rs`), the multi-record spend
//! builder (`spend.rs`), and a `ChainClient` (`chain_client.rs`) into the
//! oracle publisher's state machine. Generic over `C: ChainClient` so
//! `tests/oracle_queue.rs` can drive it against an in-memory fake (task
//! instruction: "queueing logic (unit with a fake chain client)") without
//! any real network access; `main.rs` instantiates it with
//! `CoinsetChainClient`.
//!
//! One `tick()` call does exactly one of, in priority order:
//! 1. an in-flight spend just confirmed -> finalize (advance
//!    `operator.oracle.json`, clear `in_flight`);
//! 2. an in-flight spend is still unconfirmed -> either wait (still sitting
//!    in the mempool, or backoff hasn't elapsed), rebuild it with a higher
//!    fee (rejected for being underpriced, task instruction), or retry the
//!    exact same bundle (any other rejection reason, task instruction
//!    "retries with backoff");
//! 3. no in-flight spend and records are pending -> build, sign, persist
//!    (durably, before broadcasting), and broadcast a new batch (<=20
//!    records, task instruction);
//! 4. nothing pending -> idle.
//!
//! "Never double-spend the singleton" (task instruction) is enforced two
//! ways: only one `in_flight` spend is ever persisted at a time (case 3
//! only runs when `in_flight` is `None`), and both case 3 and the
//! fee-rebuild path in case 2 additionally check coinset that the singleton
//! coin they're about to spend isn't already spent, *and* (for a rebuild)
//! that the previous bundle isn't sitting in the mempool, before building on
//! top of it (guards against a previous crash having left stale local
//! state, or against a "rejected" push that actually made it into the
//! mempool anyway -- see `docs/research/chain_service.md` §5).
//!
//! **Fee-rejection recovery** (task fix, `docs/research/chain_service.md`):
//! a push the node rejects outright (e.g. `INVALID_FEE_TOO_CLOSE_TO_ZERO`)
//! never spends the singleton coin -- it is not "in flight" in any sense
//! that blocks progress. Once `advance_in_flight` confirms via coinset that
//! the coin is genuinely still unspent *and* the rejected bundle is genuinely
//! absent from the mempool, it rebuilds the same records (plus any newly
//! queued ones, still <=20) with a re-estimated, multiplied fee (capped at
//! `Publisher::max_fee_mojos`) and broadcasts that instead -- resetting the
//! per-bundle `attempts` counter, since it's a materially different
//! transaction with its own retry budget. This is also how a stuck spend
//! from a *previous* process run recovers automatically on the next tick
//! after restart: `attempts` from before the restart no longer permanently
//! blocks progress once a fee bump is possible (see `stuck_reason`).

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use chia_bls::Signature;
use chia_protocol::{CoinSpend, SpendBundle};
use chia_puzzle_types::Proof;
use sha2::{Digest, Sha256};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::state::{OracleState, ProofDto};

use crate::keys_file::{load_operator_master_sk, load_operator_wallet_key};
use crate::state_file::{load_oracle_state, save_oracle_state_atomic};

use super::chain_client::ChainClient;
use super::lineage::{genesis_coin, tip_lineage_proof, walk_lineage};
use super::queue::{ConfirmedRecordInfo, InFlightSpend, QueueFile, ReconcileOutcome, RecordState};
use super::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use super::types::{OracleRecord, OracleRecordStatusResponse, OracleStatusResponse};

/// Task instruction: "A push that the node REJECTS (never entered the
/// mempool -- e.g. INVALID_FEE_TOO_CLOSE_TO_ZERO, fee too low) is not 'in
/// flight'". `INVALID_FEE_TOO_CLOSE_TO_ZERO` is the exact rejection this
/// service observed live on this stand (see `docs/research/
/// chain_service.md`) and the same one already documented from a live
/// testnet11 `push_tx` in `docs/research/week1_testnet_results.md` §5.1
/// (`c2 issue-play`, same mempool-manager rejection). The broader
/// case-insensitive "contains FEE" match below is a **предложение**, not a
/// verified enumeration of every Chia fee-rejection error code -- it only
/// ever *widens* which rejections get the extra chain-verified rebuild
/// treatment below; the actual "never double-spend" safety comes from
/// `advance_in_flight`'s coin-unspent/not-in-mempool checks before this is
/// ever consulted, not from this string match.
fn is_fee_rejection(error: Option<&str>) -> bool {
    error.is_some_and(|msg| msg.to_ascii_uppercase().contains("FEE"))
}

/// The "needs operator attention" message for a stuck in-flight spend --
/// shared by `advance_in_flight`'s `StuckInFlight` outcome and
/// `GET /v1/oracle/status`'s `stuckReason`, so both always agree. `Some`
/// only once `in_flight.attempts` has exhausted `max_retry_attempts` *on its
/// current bundle* -- a rebuild resets that counter (see module doc), so a
/// spend that's still being automatically fee-bumped is not yet "stuck".
fn stuck_reason(in_flight: &InFlightSpend, max_retry_attempts: u32, max_fee_mojos: u64) -> Option<String> {
    if in_flight.attempts < max_retry_attempts {
        return None;
    }
    let fee_note = if in_flight.fee_mojos >= max_fee_mojos {
        format!("fee cap of {max_fee_mojos} mojos reached")
    } else {
        format!("fee {} mojos, cap {max_fee_mojos} mojos", in_flight.fee_mojos)
    };
    Some(format!(
        "bundle {} exceeded {max_retry_attempts} retry attempts ({fee_note}): {}",
        in_flight.bundle_id,
        in_flight.last_push_error.as_deref().unwrap_or("(no error message)")
    ))
}

/// **предложение**: `sha256` of the aggregated signature's bytes, a stable
/// per-attempt label for `GET /v1/oracle/status`'s `inFlightBundleId` --
/// *not* Chia's canonical spend-bundle name (`SpendBundle::name()`, used
/// instead wherever the real chain/mempool needs to identify this exact
/// bundle, e.g. `advance_in_flight`'s `in_mempool` check). See
/// `queue.rs::InFlightSpend::bundle_id`'s doc comment.
fn bundle_display_id(signature: &Signature) -> String {
    let mut hasher = Sha256::new();
    hasher.update(signature.to_bytes());
    format!("0x{}", hex::encode(hasher.finalize()))
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TickOutcome {
    /// Nothing in flight, nothing pending.
    Idle,
    /// A batch was built and signed but not broadcast (`push_enabled ==
    /// false`); the queue is unchanged (records stay `pending`).
    DryRun { records: usize },
    /// A new batch was persisted `in_flight` and `push_tx` was called.
    Broadcast { records: usize, accepted: bool },
    /// An already-in-flight spend's `push_tx` was retried this tick.
    Retried { accepted: bool },
    /// The previous bundle was rejected for being underpriced and coinset
    /// confirmed it's genuinely not in flight (coin unspent, bundle absent
    /// from the mempool) -- rebuilt with a higher fee and broadcast (task
    /// instruction: "rebuild the spend with a higher fee").
    Rebuilt { records: usize, accepted: bool, fee_mojos: u64 },
    /// An in-flight spend exists but isn't due for a retry yet, or is
    /// sitting in the mempool / has its coin already spent by something
    /// else -- either way, nothing to do this tick but wait.
    WaitingForConfirmation,
    /// An in-flight spend hit `max_retry_attempts` on its current bundle and
    /// can no longer be escalated (fee already at `max_fee_mojos`, or the
    /// rejection reason wasn't fee-related) -- needs operator attention
    /// (surfaced via `GET /v1/oracle/status`'s `stuckReason`).
    StuckInFlight { reason: String },
    /// The in-flight spend's `next_coin` was found confirmed on chain;
    /// `operator.oracle.json` was advanced and the queue's `in_flight` was
    /// cleared.
    Confirmed { records: usize },
}

/// Consecutive `tick()` failures (task fix, `docs/research/chain_service.md`
/// §12: "any repeated tick failure (N consecutive) must set stuckReason ...
/// and /health must report degraded"). This is what catches the reported
/// incident's actual failure mode: `start_new_batch`'s "refusing to build a
/// new spend on stale state" `bail!` is a hard `Err` from `tick()` itself,
/// never even reaching a broadcast spend -- so the older, narrower
/// `stuck_reason` check below (which only ever looks at a specific
/// `InFlightSpend`'s exhausted retry count) had nothing to look at and
/// nothing to report; `status` stayed `stuckReason: null` and `/health`
/// stayed healthy for as long as the underlying bug kept the singleton
/// wedged. In-memory only, deliberately not persisted: a fresh process
/// starts with a clean slate and re-derives whether it's actually stuck from
/// its own next ticks, exactly like every other per-process piece of
/// `tick()`'s state (`queue_lock`, `balance_snapshot`).
#[derive(Debug, Clone, Default)]
pub struct TickFailureState {
    pub consecutive_failures: u32,
    pub last_error: Option<String>,
}

pub struct Publisher<C: ChainClient> {
    pub data_dir: PathBuf,
    pub operator_name: String,
    pub push_enabled: bool,
    pub chain: C,
    pub retry_backoff: Duration,
    pub max_retry_attempts: u32,
    /// Hard ceiling (mojos) on a rebuilt spend's fee -- see
    /// `Config::max_fee_mojos` (`C2_MAX_FEE_MOJOS`, task instruction:
    /// "configurable cap").
    pub max_fee_mojos: u64,
    /// `target_times` seconds passed to `get_fee_estimate` (task
    /// instruction: "fees from coinset get_fee_estimate with the dry-run
    /// cost"). **предложение**: 60s, see `Config::retry_backoff` doc.
    pub fee_target_time_secs: u32,
    /// `GET /v1/oracle/status`'s `operatorBalanceMojos`, refreshed out of
    /// band by `refresh_balance_snapshot` (called from `main.rs`'s own
    /// background loop, decoupled from `tick()`). `status()` only ever reads
    /// this cache -- it must never itself await a coinset round-trip (task
    /// fix: `status()` used to call `self.chain.balance(...)` directly,
    /// which is `c2_cli::chain::balance`'s 20 sequential, un-timed-out
    /// `get_coin_records_by_puzzle_hash` calls -- slow or stalled coinset
    /// made `GET /v1/oracle/status` hang along with it; `/health` was
    /// unaffected because it never touches chain state). **предложение**:
    /// caching the balance instead of computing it per-request.
    pub balance_snapshot: tokio::sync::RwLock<Option<String>>,
    /// Serializes every read-modify-write cycle over the durable queue file
    /// (`<name>.oracle_queue.json`) between the HTTP `enqueue()` handler and
    /// the background `tick()` loop. Root-cause fix, see
    /// `docs/research/chain_service.md` §10 ("lost update" root cause of
    /// seq 2/3/4/62 being acknowledged but never published): both `enqueue`
    /// and `tick` used to independently `QueueFile::load` -> mutate ->
    /// `save_atomic` with no mutual exclusion at all. `tick()`'s
    /// `advance_in_flight`/`rebuild_in_flight` path in particular awaits
    /// several chain round-trips (`coin_record` x2, `in_mempool`,
    /// `fee_estimate_mojos`, `find_funding_coin`) *between* its initial
    /// `QueueFile::load` (at the top of `tick()`) and its final
    /// `save_atomic` -- a wide window in which a concurrent `POST
    /// /v1/oracle/records` could load the same stale on-disk state, append
    /// its own record, and save -- durably, fsync'ed, 202 already returned
    /// to the caller -- only for `tick()`'s own later `save_atomic` (built
    /// from ITS stale in-memory copy, taken before that POST ever ran) to
    /// silently overwrite it right back out of existence. Held for the
    /// *entire* body of both `enqueue()` and `tick()`, not just around the
    /// file I/O, precisely so nothing can slip in between another task's
    /// load and its save. `status()` deliberately does *not* take this lock
    /// (see its own doc comment) -- it only ever does a plain, eventually-
    /// consistent read of the atomically-renamed file, which is always
    /// either the previous or the new complete state, never a torn write.
    pub queue_lock: tokio::sync::Mutex<()>,
    /// See `TickFailureState`'s doc comment. Updated by the public `tick()`
    /// wrapper after every call (success resets it, failure increments it),
    /// read by `degraded_reason` (shared by `status()`'s `stuckReason` and
    /// `GET /health`).
    pub tick_failures: tokio::sync::RwLock<TickFailureState>,
    /// N consecutive `tick()` failures before `degraded_reason` reports
    /// degraded (`C2_TICK_FAILURE_THRESHOLD`, task instruction: "e.g. 3").
    pub tick_failure_threshold: u32,
}

impl<C: ChainClient> Publisher<C> {
    /// `POST /v1/oracle/records`'s handler: durably enqueues newly
    /// submitted records, deduped against records already known
    /// confirmed/in-flight/pending (see `QueueFile::enqueue`,
    /// FIXED INTERFACE (b)). Returns the `seq`s newly accepted
    /// (`acceptedSeqs`); a resubmitted or already-known `seq` is silently
    /// omitted, not an error.
    pub async fn enqueue(&self, records: Vec<OracleRecord>) -> Result<Vec<u64>> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        let accepted = queue.enqueue(records);
        if !accepted.is_empty() {
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting newly queued oracle records")?;
        }
        Ok(accepted)
    }

    /// `GET /v1/oracle/records/{seq}`'s handler (FIXED INTERFACE (b)): a
    /// plain, unlocked read of the queue's current persisted state -- same
    /// eventual-consistency reasoning as `status()` (see its doc comment and
    /// `queue_lock`'s).
    pub fn record_status(&self, seq: u64) -> Result<OracleRecordStatusResponse> {
        let queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        Ok(match queue.record_state(seq) {
            RecordState::Confirmed { height, coin_id } => {
                OracleRecordStatusResponse { seq, state: "confirmed", height, coin_id: Some(coin_id) }
            }
            // Task fix (`docs/research/chain_service.md` §11): surfaced here
            // (per-record detail) as well as in `GET /v1/oracle/status`'s
            // `conflicts` (list-level summary) -- "never silently".
            RecordState::Conflict { chain_height, chain_coin_id } => {
                OracleRecordStatusResponse { seq, state: "conflict", height: chain_height, coin_id: Some(chain_coin_id) }
            }
            RecordState::InFlight => OracleRecordStatusResponse { seq, state: "in_flight", height: None, coin_id: None },
            RecordState::Queued => OracleRecordStatusResponse { seq, state: "queued", height: None, coin_id: None },
            RecordState::Unknown => OracleRecordStatusResponse { seq, state: "unknown", height: None, coin_id: None },
        })
    }

    /// `GET /v1/oracle/status`'s handler. Must never await a network call:
    /// everything here is either a local file read (`QueueFile::load`,
    /// `load_oracle_state` -- fast, and the queue/state files are exactly
    /// what a concurrent `enqueue`/`tick` is durably writing, so this still
    /// reflects the live queue) or a read of `balance_snapshot`, the cache
    /// `refresh_balance_snapshot` fills in from off to the side. See
    /// `balance_snapshot`'s doc comment for why (task fix).
    pub async fn status(&self) -> Result<OracleStatusResponse> {
        let queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        let oracle_state = load_oracle_state(&self.data_dir, &self.operator_name).ok();
        let operator_balance_mojos = self.balance_snapshot.read().await.clone();

        let in_flight_records = queue.in_flight.as_ref().map(|f| f.records.len()).unwrap_or(0);
        let stuck = self.degraded_reason(&queue).await;
        // Task fix (`docs/research/chain_service.md` §11): "surface in
        // status (stuckReason/conflicts) -- never silently". Ascending by
        // seq for free (`conflicts` is a `BTreeMap`).
        let conflicts: Vec<u64> = queue.conflicts.keys().copied().collect();

        Ok(OracleStatusResponse {
            launcher_id: oracle_state
                .as_ref()
                .map(|s| format!("0x{}", s.launcher_id))
                .unwrap_or_default(),
            network: "testnet11",
            last_confirmed_seq: queue.last_confirmed_seq,
            last_confirmed_height: queue.last_confirmed_height,
            pending_records: queue.pending_count() + in_flight_records,
            in_flight_bundle_id: queue.in_flight.as_ref().map(|f| f.bundle_id.clone()),
            operator_balance_mojos,
            stuck_reason: stuck,
            conflicts,
        })
    }

    /// The single signal both `GET /v1/oracle/status`'s `stuckReason` and
    /// `GET /health`'s degraded state are derived from (task fix,
    /// `docs/research/chain_service.md` §12: "any repeated tick failure (N
    /// consecutive) must set stuckReason ... and /health must report
    /// degraded"). Checks the broader, service-level signal first --
    /// `tick()` itself hard-failing `tick_failure_threshold` times in a row,
    /// which is exactly this incident's failure mode (a `bail!` before ever
    /// reaching a broadcast spend, so there's no `InFlightSpend` for the
    /// narrower check below to inspect at all) -- and only falls back to the
    /// existing, narrower "this one specific in-flight spend exhausted its
    /// own retry budget" check when the service as a whole is still
    /// executing ticks fine. A plain local read (`queue`'s caller already
    /// loaded it) plus one in-memory `RwLock` read -- never a network call,
    /// same reasoning as `status()`'s own doc comment, so a slow/stuck
    /// coinset can never make `GET /health` itself hang or false-negative.
    async fn degraded_reason(&self, queue: &QueueFile) -> Option<String> {
        let failures = self.tick_failures.read().await;
        if failures.consecutive_failures >= self.tick_failure_threshold {
            return Some(format!(
                "publisher tick has failed {} consecutive times (threshold {}): {}",
                failures.consecutive_failures,
                self.tick_failure_threshold,
                failures.last_error.as_deref().unwrap_or("(no error message)")
            ));
        }
        drop(failures);
        queue
            .in_flight
            .as_ref()
            .and_then(|f| stuck_reason(f, self.max_retry_attempts, self.max_fee_mojos))
    }

    /// `GET /health`'s handler support (task fix, `docs/research/
    /// chain_service.md` §12): `Ok(None)` = healthy (`{"ok": true}`, HTTP
    /// 200), `Ok(Some(reason))` = degraded (`{"ok": false, "reason": ...}`,
    /// HTTP 503, so the Docker healthcheck can turn unhealthy). `Err` only on
    /// a genuinely unreadable/corrupt local queue file -- `app.rs` treats
    /// that the same as degraded (fail toward "unhealthy" on unknown local
    /// state, not toward silently reporting healthy).
    pub async fn health_check(&self) -> Result<Option<String>> {
        let queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        Ok(self.degraded_reason(&queue).await)
    }

    /// Refreshes `balance_snapshot` from the chain -- the one network call
    /// `status()` used to make inline. `main.rs` runs this on its own
    /// periodic background loop, independent of `tick()`'s, so a slow or
    /// stuck coinset balance scan can never delay `tick()`'s in-flight-spend
    /// confirmation checks either (both loops only ever share this
    /// `RwLock`, held just long enough to swap the cached value, never
    /// across a network call).
    pub async fn refresh_balance_snapshot(&self) {
        let snapshot = match load_operator_master_sk(&self.data_dir, &self.operator_name) {
            Ok(master_sk) => self.chain.balance(&master_sk).await.ok().map(|mojos| mojos.to_string()),
            Err(_) => None,
        };
        *self.balance_snapshot.write().await = snapshot;
    }

    /// Startup bootstrap (task fix, `docs/research/chain_service.md` §11):
    /// walks the oracle singleton lineage and reconciles the local queue
    /// against it. `main.rs` calls this once, synchronously, before opening
    /// any listener or spawning the periodic `tick()`/balance-refresh loops
    /// -- "before accepting or publishing anything". Safe to call again
    /// later too (e.g. after an operator clears `chain_scan_checkpoint` by
    /// hand) -- it's the exact same reconciliation `tick()` now also runs on
    /// every regular cycle (see that method's own doc comment), just
    /// invoked directly and awaited to completion up front.
    pub async fn bootstrap_from_chain(&self) -> Result<ReconcileOutcome> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = QueueFile::load(&self.data_dir, &self.operator_name)?;
        let outcome = self.reconcile_queue_with_chain(&mut queue).await?;
        if outcome.changed() {
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting chain-reconciled queue (bootstrap)")?;
        }
        Ok(outcome)
    }

    /// Walks the chain from `queue.chain_scan_checkpoint` (or the
    /// singleton's genesis coin, on a fresh queue -- see `lineage::
    /// genesis_coin`) and reconciles the result into `queue` in place (task
    /// fix, `docs/research/chain_service.md` §11: "never trust the local
    /// queue file's bookkeeping alone"). Assumes the caller already holds
    /// `queue_lock` and does **not** save `queue` itself -- both callers
    /// (`bootstrap_from_chain`, `tick()`) decide separately whether the
    /// result actually needs persisting (`ReconcileOutcome::changed`).
    /// A queue with no oracle state yet (`c2 oracle-launch` not run) is a
    /// no-op, not an error -- there is nothing to reconcile against before
    /// the singleton even exists.
    async fn reconcile_queue_with_chain(&self, queue: &mut QueueFile) -> Result<ReconcileOutcome> {
        let Ok(mut oracle_state) = load_oracle_state(&self.data_dir, &self.operator_name) else {
            return Ok(ReconcileOutcome::default());
        };
        let start = queue
            .chain_scan_checkpoint
            .unwrap_or_else(|| genesis_coin(oracle_state.launcher_id, oracle_state.manager_synthetic_pk));
        let walk = walk_lineage(&self.chain, start)
            .await
            .context("walking oracle singleton lineage for chain reconciliation")?;
        let mut outcome = queue.reconcile_with_chain(&walk);

        for warning in &outcome.decode_warnings {
            tracing::warn!(%warning, "oracle lineage walk");
        }
        if !outcome.dropped_confirmed.is_empty() {
            tracing::info!(
                seqs = ?outcome.dropped_confirmed,
                "chain-lineage walk: dropped already-confirmed records from the local queue \
                 (never republished -- task fix, docs/research/chain_service.md §11)"
            );
        }
        if !outcome.new_conflicts.is_empty() {
            tracing::warn!(
                seqs = ?outcome.new_conflicts,
                "chain-lineage walk: local record content disagrees with the chain -- \
                 needs operator attention, see GET /v1/oracle/status's conflicts"
            );
        }

        // Task fix (`docs/research/chain_service.md` §12): "make the chain
        // the source of truth for the singleton position ... whenever local
        // OracleState.current_coin is spent/behind, adopt the chain tip
        // (atomically persisted), then continue publishing." This is what
        // the reported incident's `all_confirmed` case above actually needed
        // -- `queue.reconcile_with_chain` (a pure `QueueFile` <-> chain-walk
        // function, no `OracleState` access) can and does clear `in_flight`
        // once every one of its records is confirmed identically on chain,
        // but advancing `current_coin`/`current_proof_dto`/`next_sequence`
        // was only ever done by `advance_in_flight` -- which `tick()` never
        // reaches once this reconciliation has already emptied `in_flight`
        // first. Deliberately gated on `queue.in_flight.is_none()`: an
        // in-flight spend that simply hasn't confirmed yet is *expected* to
        // leave `current_coin` pointing at a coin the chain still shows
        // spent underneath it (a partial/conflicting in-flight match is left
        // alone by `queue.reconcile_with_chain` above for the same reason) --
        // that gap is `advance_in_flight`'s job to close, not this one's, so
        // this never runs concurrently with (or in place of) the existing
        // never-double-spend checks there. With `in_flight` empty, though,
        // there is nothing left that could still explain `current_coin`
        // disagreeing with the chain's actual tip except staleness -- spent
        // out from under this process (this incident), or simply behind by
        // any number of generations (a stale/restored/redeployed
        // `operator.oracle.json`) -- so adopt unconditionally.
        if queue.in_flight.is_none() && oracle_state.current_coin != walk.tip_coin {
            let stale_coin = oracle_state.current_coin;
            match tip_lineage_proof(&self.chain, oracle_state.launcher_id, oracle_state.manager_synthetic_pk, walk.tip_coin)
                .await
                .context("deriving the chain tip's lineage proof for adoption")?
            {
                Some(proof) => {
                    oracle_state.current_coin = walk.tip_coin;
                    oracle_state.current_proof_dto = proof.into();
                    // **предложение**: one singleton spend is exactly one
                    // generation regardless of how many records it batched
                    // (same convention `advance_in_flight` already uses,
                    // `+= 1` there too) -- `next_sequence` is informational
                    // only (mirrors `c2-cli`'s single-record `OracleState`
                    // shape for on-disk compatibility; `c2-svc` itself never
                    // reads it to decide what to publish next, see
                    // `docs/research/chain_service.md` §12), so being off by
                    // count on a multi-generation adoption jump has no effect
                    // on publishing correctness.
                    oracle_state.next_sequence = oracle_state.next_sequence.saturating_add(1);
                    save_oracle_state_atomic(&self.data_dir, &self.operator_name, &oracle_state)
                        .context("persisting adopted chain-tip oracle state")?;
                    tracing::warn!(
                        stale_coin = %stale_coin.coin_id(),
                        adopted_coin = %walk.tip_coin.coin_id(),
                        "oracle state's current_coin was stale (spent, or behind the chain tip) -- \
                         adopted the chain tip (task fix, docs/research/chain_service.md §12)"
                    );
                    outcome.adopted_stale_coin = Some(stale_coin);
                }
                None => {
                    tracing::warn!(
                        stale_coin = %stale_coin.coin_id(),
                        chain_tip = %walk.tip_coin.coin_id(),
                        "oracle state's current_coin disagrees with the chain tip but the tip's \
                         lineage proof could not be derived -- not adopting; a repeated tick \
                         failure will surface this via stuckReason/health instead"
                    );
                }
            }
        }

        Ok(outcome)
    }

    /// Runs one iteration of the publisher state machine (see module doc),
    /// then records the outcome into `tick_failures` (task fix,
    /// `docs/research/chain_service.md` §12: "any repeated tick failure (N
    /// consecutive) must set stuckReason ... and /health must report
    /// degraded") -- a success resets the counter, a failure increments it
    /// and remembers the error text, both read back by `degraded_reason`.
    /// The `Result` itself is passed through unchanged either way; callers
    /// (`main.rs`'s background loop, every existing test) see exactly the
    /// same `tick()` behavior as before this fix.
    pub async fn tick(&self) -> Result<TickOutcome> {
        let result = self.tick_once().await;
        {
            let mut failures = self.tick_failures.write().await;
            match &result {
                Ok(_) => *failures = TickFailureState::default(),
                Err(e) => {
                    failures.consecutive_failures += 1;
                    failures.last_error = Some(format!("{e:#}"));
                }
            }
        }
        result
    }

    /// The actual publisher state machine -- see module doc. Renamed from
    /// the old public `tick()`; the wrapper above adds failure tracking
    /// around it without changing its own logic at all.
    async fn tick_once(&self) -> Result<TickOutcome> {
        // See `queue_lock`'s doc comment: held for this entire call
        // (including the chain round-trips `advance_in_flight`/
        // `rebuild_in_flight` await below) so a concurrent `enqueue()` can
        // never load a stale snapshot and clobber whatever this tick ends up
        // persisting.
        let _guard = self.queue_lock.lock().await;
        let mut queue = QueueFile::load(&self.data_dir, &self.operator_name)?;

        // Task fix (`docs/research/chain_service.md` §11): "also run that
        // forward scan periodically (cheap) so the confirmed map always
        // reflects the chain, not only local bookkeeping" -- cheap once
        // caught up (checkpointed): a single `coin_record` call confirming
        // the tip is still unspent. Runs before anything else below so a
        // stale/conflicting record already on chain never reaches
        // `start_new_batch` this tick either.
        let reconcile_outcome = self.reconcile_queue_with_chain(&mut queue).await?;
        if reconcile_outcome.changed() {
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("persisting chain-reconciled queue")?;
        }

        if let Some(in_flight) = queue.in_flight.clone() {
            return self.advance_in_flight(&mut queue, in_flight).await;
        }

        if queue.pending.is_empty() {
            return Ok(TickOutcome::Idle);
        }

        self.start_new_batch(&mut queue).await
    }

    async fn advance_in_flight(&self, queue: &mut QueueFile, in_flight: InFlightSpend) -> Result<TickOutcome> {
        let next_coin_id = in_flight.next_coin.coin_id();
        let record_info = self
            .chain
            .coin_record(next_coin_id)
            .await
            .context("checking in-flight spend confirmation")?;

        if record_info.exists {
            let mut oracle_state = load_oracle_state(&self.data_dir, &self.operator_name)
                .context("loading oracle state to finalize a confirmed spend")?;
            oracle_state.current_coin = in_flight.next_coin;
            oracle_state.current_proof_dto = in_flight.next_proof.clone();
            oracle_state.next_sequence += 1;
            save_oracle_state_atomic(&self.data_dir, &self.operator_name, &oracle_state)
                .context("persisting advanced oracle state")?;

            // Per-record confirmation (FIXED INTERFACE (b),
            // `GET /v1/oracle/records/{seq}`): every record in this batch,
            // not just "the last one" -- `in_flight.records` is no longer
            // guaranteed sorted ascending by seq once a LATE/backfill record
            // (arbitrary seq) can be merged into a rebuild alongside newer
            // ones (`rebuild_in_flight`), so `.last()`/`.max()` on it would
            // both be the wrong question for per-seq tracking, only the
            // right one for the *overall* high-water mark below.
            let confirmed_coin_id = format!("{}", in_flight.next_coin.coin_id());
            for record in &in_flight.records {
                queue.confirmed.insert(
                    record.seq,
                    ConfirmedRecordInfo { height: record_info.confirmed_height, coin_id: confirmed_coin_id.clone() },
                );
            }
            let confirmed_count = in_flight.records.len();
            queue.last_confirmed_seq =
                in_flight.records.iter().map(|r| r.seq).max().max(queue.last_confirmed_seq);
            queue.last_confirmed_height = record_info.confirmed_height.or(queue.last_confirmed_height);
            queue.in_flight = None;
            queue
                .save_atomic(&self.data_dir, &self.operator_name)
                .context("clearing in-flight spend after confirmation")?;

            tracing::info!(records = confirmed_count, "oracle publish batch confirmed");
            return Ok(TickOutcome::Confirmed { records: confirmed_count });
        }

        // Backoff / dry-run gating happens before anything else below --
        // including for an already-exhausted spend, so a stuck spend is
        // reported via `status()`/logs rather than hammering coinset every
        // tick (unchanged from the old behavior).
        let elapsed = now_unix() - in_flight.last_attempt_at_unix;
        if elapsed < self.retry_backoff.as_secs() as i64 {
            return Ok(TickOutcome::WaitingForConfirmation);
        }
        if !self.push_enabled {
            return Ok(TickOutcome::WaitingForConfirmation);
        }

        // Never-double-spend checks, done *before* consulting
        // `max_retry_attempts` below: a spend that exhausted its attempts in
        // a previous process run (e.g. before this fix existed) must not
        // stay permanently stuck once it's actually safe to rebuild -- see
        // module doc "recovers automatically on the next tick after
        // restart".
        let spent_record = self
            .chain
            .coin_record(in_flight.spent_coin.coin_id())
            .await
            .context("checking singleton coin before acting on an in-flight spend")?;
        if spent_record.spent {
            // Something else (or a race with the `next_coin` check above)
            // already spent it -- don't touch anything, the next tick's
            // `next_coin` check will resolve it once the recreated coin is
            // visible.
            return Ok(TickOutcome::WaitingForConfirmation);
        }
        let in_mempool = self
            .chain
            .in_mempool(in_flight.bundle.name())
            .await
            .context("checking mempool for the in-flight spend's bundle")?;
        if in_mempool {
            // Task instruction: "the previous bundle IS in the mempool but
            // pending too long: leave it". A Chia mempool replacement
            // requires spending a *superset* of the same coins at a
            // strictly higher fee (`chia-blockchain`'s
            // `mempool_manager.py::check_removals`/`MempoolItem` replacement
            // rule) -- a same-coin, higher-fee rebuild does not qualify, so
            // resubmitting it would just be rejected as a conflicting spend.
            // Not implemented; just wait for it to confirm or fall out of
            // the mempool on its own.
            return Ok(TickOutcome::WaitingForConfirmation);
        }

        // Not in the mempool and the coin is still unspent: the last push
        // was genuinely rejected (or evicted). If it looks like an
        // underpriced-fee rejection and there's still room under the fee
        // cap, rebuild with a higher fee rather than resubmitting the exact
        // bytes that were just rejected.
        if !in_flight.last_push_accepted
            && is_fee_rejection(in_flight.last_push_error.as_deref())
            && in_flight.fee_mojos < self.max_fee_mojos
        {
            return self.rebuild_in_flight(queue, in_flight).await;
        }

        // Can't (or don't need to) escalate the fee -- exhausted this
        // bundle's dedicated retry budget?
        if let Some(reason) = stuck_reason(&in_flight, self.max_retry_attempts, self.max_fee_mojos) {
            tracing::warn!(
                bundle_id = %in_flight.bundle_id,
                attempts = in_flight.attempts,
                fee_mojos = in_flight.fee_mojos,
                reason = %reason,
                "in-flight oracle publish spend needs operator attention"
            );
            return Ok(TickOutcome::StuckInFlight { reason });
        }

        // Plain retry of the exact same bundle: covers a rejection that
        // isn't fee-related (no point rebuilding -- a higher fee wouldn't
        // fix it), or the fee-capped case that still has attempts left.
        let outcome = self
            .chain
            .push_tx(in_flight.bundle.clone())
            .await
            .context("retrying push_tx for an in-flight spend")?;

        let mut in_flight = in_flight;
        in_flight.attempts += 1;
        in_flight.last_attempt_at_unix = now_unix();
        in_flight.last_push_accepted = outcome.accepted;
        in_flight.last_push_error = outcome.error.clone();
        let accepted = outcome.accepted;
        queue.in_flight = Some(in_flight);
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting retried in-flight spend")?;

        Ok(TickOutcome::Retried { accepted })
    }

    /// Rebuilds the in-flight spend's records (plus any newly queued ones,
    /// task instruction: "merging any newly queued records into the
    /// rebuilt spend (<=20)") against the *same* singleton coin, with a
    /// re-estimated and multiplied fee, and broadcasts it in place of the
    /// rejected bundle. Only ever called after `advance_in_flight` has
    /// confirmed the singleton coin is unspent and the previous bundle is
    /// absent from the mempool (never-double-spend guard).
    async fn rebuild_in_flight(&self, queue: &mut QueueFile, in_flight: InFlightSpend) -> Result<TickOutcome> {
        let manager = load_operator_wallet_key(&self.data_dir, &self.operator_name)
            .context("loading operator wallet key")?;
        let oracle_state = load_oracle_state(&self.data_dir, &self.operator_name)
            .context("loading oracle state to rebuild a rejected spend")?;
        if oracle_state.current_coin != in_flight.spent_coin {
            // Belt-and-suspenders: `advance_in_flight` already checked
            // `in_flight.spent_coin` unspent on-chain, and `current_coin` is
            // only ever advanced on a *confirmed* spend (which would have
            // taken the early `Confirmed` return above) -- so these must
            // already match. Refuse rather than rebuild against a
            // mismatched coin if that invariant is ever violated.
            bail!(
                "oracle state current_coin {} no longer matches the in-flight spend's coin {} -- \
                 refusing to rebuild (needs manual reconciliation, see docs/research/chain_service.md §5)",
                oracle_state.current_coin.coin_id(),
                in_flight.spent_coin.coin_id()
            );
        }

        // Merge any newly queued pending records into this rebuilt batch,
        // oldest first, capped at `MAX_RECORDS_PER_SPEND` total.
        let mut records = in_flight.records.clone();
        let room = MAX_RECORDS_PER_SPEND.saturating_sub(records.len());
        if room > 0 {
            records.extend(queue.take_batch(room));
        }
        let batch_len = records.len();

        let current_proof: Proof = oracle_state.current_proof();
        let draft = build_oracle_publish_batch(
            oracle_state.launcher_id,
            oracle_state.current_coin,
            current_proof,
            &manager,
            &records,
        )
        .context("rebuilding oracle-publish batch spend with a higher fee")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends).context("measuring rebuilt draft spend cost")?;

        // Task instruction: "re-estimate with get_fee_estimate for the real
        // cost at a shorter target time" -- a rejected, congested-mempool
        // spend needs to outbid the mempool *sooner* than a fresh batch's
        // default target, not just match it. **предложение**: a third of
        // `fee_target_time_secs`, floor 1s.
        let shorter_target = (self.fee_target_time_secs / 3).max(1);
        let chain_estimate = match self.chain.fee_estimate_mojos(draft_cost.total_cost, shorter_target).await {
            Ok(fee) => fee,
            Err(e) => {
                tracing::warn!("get_fee_estimate failed while rebuilding a rejected spend, falling back to 0: {e:#}");
                0
            }
        };
        // Task instruction: "on each consecutive fee rejection multiply the
        // fee (e.g. x2) up to a configurable cap".
        let fee_mojos = in_flight
            .fee_mojos
            .saturating_mul(2)
            .max(chain_estimate)
            .max(1)
            .min(self.max_fee_mojos);

        let (coin_spends, signature) = self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await?;
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let next_proof_dto: ProofDto = draft.next_proof.into();
        let now = now_unix();

        queue.in_flight = Some(InFlightSpend {
            records,
            spent_coin: oracle_state.current_coin,
            next_coin: draft.next_coin,
            next_proof: next_proof_dto,
            bundle: bundle.clone(),
            bundle_id,
            fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            // A materially different (higher-fee) transaction gets its own
            // retry budget rather than inheriting the rejected bundle's
            // exhausted count (module doc: "resetting the per-bundle
            // `attempts` counter").
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
        });
        // Durability first, same invariant as `start_new_batch`.
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting rebuilt in-flight spend before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for rebuilt batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting rebuilt push_tx outcome")?;

        tracing::info!(
            records = batch_len,
            fee_mojos,
            accepted = outcome.accepted,
            "rebuilt oracle publish batch with a higher fee after a rejection"
        );
        Ok(TickOutcome::Rebuilt {
            records: batch_len,
            accepted: outcome.accepted,
            fee_mojos,
        })
    }

    /// Adds a fee-only coin spend (and its signature) for `fee_mojos` to a
    /// draft's coin spends/signature, when `fee_mojos > 0`. Shared by
    /// `start_new_batch` and `rebuild_in_flight` -- the only difference
    /// between them is how `fee_mojos` itself is computed.
    async fn attach_fee_coin(
        &self,
        mut coin_spends: Vec<CoinSpend>,
        signature: Signature,
        fee_mojos: u64,
    ) -> Result<(Vec<CoinSpend>, Signature)> {
        if fee_mojos == 0 {
            return Ok((coin_spends, signature));
        }
        let master_sk =
            load_operator_master_sk(&self.data_dir, &self.operator_name).context("loading operator master key")?;
        let (payer, fee_coin) = self
            .chain
            .find_funding_coin(&master_sk, fee_mojos)
            .await
            .context("no funding coin to pay the oracle-publish batch fee with")?;
        let fee_result = build_fee_only(fee_coin, &payer, fee_mojos).context("building fee-only coin spend")?;
        coin_spends.extend(fee_result.coin_spends);
        let signature = signature + &fee_result.signature;
        Ok((coin_spends, signature))
    }

    async fn start_new_batch(&self, queue: &mut QueueFile) -> Result<TickOutcome> {
        let manager = load_operator_wallet_key(&self.data_dir, &self.operator_name)
            .context("loading operator wallet key")?;
        let oracle_state: OracleState =
            load_oracle_state(&self.data_dir, &self.operator_name).context("loading oracle state")?;

        // Never double-spend the singleton: refuse to build on stale local
        // state (see module doc + `docs/research/chain_service.md` §5).
        let current_record = self
            .chain
            .coin_record(oracle_state.current_coin.coin_id())
            .await
            .context("checking oracle singleton current_coin before starting a new batch")?;
        if current_record.spent {
            bail!(
                "oracle singleton current_coin {} is already spent on chain but there is no \
                 matching in-flight spend in the local queue -- refusing to build a new spend \
                 on stale state (needs manual reconciliation, see \
                 docs/research/chain_service.md §5)",
                oracle_state.current_coin.coin_id()
            );
        }

        let batch = queue.take_batch(MAX_RECORDS_PER_SPEND);
        let batch_len = batch.len();
        let current_proof: Proof = oracle_state.current_proof();

        let draft = build_oracle_publish_batch(
            oracle_state.launcher_id,
            oracle_state.current_coin,
            current_proof,
            &manager,
            &batch,
        )
        .context("building oracle-publish batch spend")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends).context("measuring draft spend cost")?;

        let fee_mojos = match self
            .chain
            .fee_estimate_mojos(draft_cost.total_cost, self.fee_target_time_secs)
            .await
        {
            Ok(fee) => fee,
            Err(e) => {
                tracing::warn!("get_fee_estimate failed, falling back to fee=0: {e:#}");
                0
            }
        };

        if !self.push_enabled {
            tracing::info!(
                records = batch_len,
                cost = draft_cost.total_cost,
                fee_mojos,
                "dry-run (C2_PUSH=0/--dry-run): built oracle-publish batch spend, not broadcasting"
            );
            // Put the drained batch back -- dry-run must not change any
            // persisted state (mirrors `c2-cli`'s own `--push` convention).
            // Also skips `attach_fee_coin`'s `find_funding_coin` network
            // call below -- a dry run has no reason to touch the chain past
            // the fee estimate above (task fix: incidental improvement, the
            // old code called it unconditionally even in dry-run mode).
            queue.pending.splice(0..0, batch);
            return Ok(TickOutcome::DryRun { records: batch_len });
        }

        let (coin_spends, signature) = self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await?;
        let bundle_id = bundle_display_id(&signature);
        let bundle = SpendBundle::new(coin_spends, signature);
        let next_proof_dto: ProofDto = draft.next_proof.into();

        let now = now_unix();
        queue.in_flight = Some(InFlightSpend {
            records: batch,
            spent_coin: oracle_state.current_coin,
            next_coin: draft.next_coin,
            next_proof: next_proof_dto,
            bundle: bundle.clone(),
            bundle_id,
            fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
        });
        // Durability first (task instruction: "queue records durably ...
        // while a spend is in flight"): this atomic write is what removes
        // the batch from `pending`. A crash right after this line and
        // before `push_tx` below just means the next tick's retry path
        // (`advance_in_flight`) broadcasts this exact persisted bundle.
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting new in-flight spend before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for new batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        queue
            .save_atomic(&self.data_dir, &self.operator_name)
            .context("persisting push_tx outcome")?;

        Ok(TickOutcome::Broadcast {
            records: batch_len,
            accepted: outcome.accepted,
        })
    }
}
