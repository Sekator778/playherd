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
//!    in the mempool, or backoff hasn't elapsed), replace it once it has
//!    sat in the mempool past `mempool_replace_after` (`replace_in_mempool`,
//!    below), rebuild it with a higher fee (rejected for being underpriced,
//!    task instruction), or retry the exact same bundle (any other rejection
//!    reason, task instruction "retries with backoff");
//! 3. no in-flight spend and records are pending -> build, sign, persist
//!    (durably, before broadcasting), and broadcast a new batch (<=40
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
//! queued ones, still <=`MAX_RECORDS_PER_SPEND`) with a re-estimated, multiplied fee (capped at
//! `Publisher::max_fee_mojos`) and broadcasts that instead -- resetting the
//! per-bundle `attempts` counter, since it's a materially different
//! transaction with its own retry budget. This is also how a stuck spend
//! from a *previous* process run recovers automatically on the next tick
//! after restart: `attempts` from before the restart no longer permanently
//! blocks progress once a fee bump is possible (see `stuck_reason`).
//!
//! **Waiting in the mempool is not forever** (`docs/research/chain_service.md` §17): a bundle the
//! node accepted but does not confirm -- the fee-0 bundle of 2026-10-02 sat there for ten minutes while
//! full blocks went by -- is replaced once it has waited `mempool_replace_after`
//! (`replace_in_mempool`), with a bundle that satisfies the node's replacement rule
//! (`mempool_rule.rs`): the same singleton coin, the fee coin the old bundle spent (or a new one
//! when it had none), a fee at least 10,000,000 mojos higher. Both bundles spend the same coin, so
//! at most one confirms; which one the chain took is read off its announcements, never assumed
//! (`QueueFile::resolve_consumed_in_flight`): records the chain has are confirmed, records it does
//! not have go back to `pending`.
//!
//! **Our own confirmation is not an incident** (§17.5): the chain walk that opens every tick sees
//! our bundle's confirmation first, and `reconcile_queue_with_chain` settles it as what it is -- an
//! INFO line, the records confirmed, the fee coin released, the oracle state advanced -- and warns
//! only for a spend that is not ours.

use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result, bail};
use chia_bls::{SecretKey, Signature};
use chia_protocol::{Coin, CoinSpend, SpendBundle};
use chia_puzzle_types::Proof;
use sha2::{Digest, Sha256};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::keys::WalletKey;
use c2_cli::state::{OracleState, ProofDto};

use crate::fee_allocator::{FeeCoinAllocator, NoFeeCoin, fee_coin_of, is_coin_spent_rejection};
use crate::fee_reserve::{FeeCoinsBusy, MaintenanceOutcome, MaintenanceParams};
use crate::keys_file::{load_operator_master_sk, load_operator_wallet_key};
use crate::mempool_rule::{MEMPOOL_MIN_FEE_INCREASE, required_replacement_fee};
use crate::state_file::{load_oracle_state, save_oracle_state_atomic};

use super::chain_client::ChainClient;
use super::lineage::{genesis_coin, tip_lineage_proof, walk_lineage};
use super::queue::{
    ConfirmedRecordInfo, EnqueueOutcome, InFlightSpend, QueueCache, QueueFile, ReconcileOutcome, RecordState,
    ResolvedInFlight, RetentionOutcome,
};
use super::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use super::types::{OracleRecord, OracleRecordStatusResponse, OracleStatusResponse, RECORD_FORMAT};

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
    // A bundle in the mempool that waited past `C2_MEMPOOL_REPLACE_AFTER_SECS` and cannot be
    // replaced (`replace_in_mempool`): it keeps waiting -- it may still confirm -- and this is how
    // an operator is told why nothing is being done about it.
    if let Some(blocked) = &in_flight.replace_blocked {
        return Some(format!(
            "bundle {} has waited in the mempool without confirming and could not be replaced: {blocked}",
            in_flight.bundle_id
        ));
    }
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

/// How often `tick` runs the retention of confirmed records (`Publisher::retain_confirmed`): a pass
/// that drops anything is a write of the whole queue file, and records confirm every few seconds, so
/// the pass is made every ten minutes, not every tick -- the file then holds the last
/// `C2_ORACLE_RETAIN_CONFIRMED_SECS` plus at most these ten minutes. **предложение**.
const RETENTION_EVERY_SECS: i64 = 600;

/// A replacement bundle `replace_in_mempool` is ready to make durable and push.
struct ReplacementBundle {
    bundle: SpendBundle,
    bundle_id: String,
    fee_mojos: u64,
    next_coin: Coin,
    next_proof: ProofDto,
    /// The fee coin reserved for this replacement alone (the old bundle had none): released if the
    /// replacement is not taken. `None` when the old bundle's own fee coin is kept.
    newly_reserved: Option<Coin>,
}

/// What `prepare_replacement` came to.
enum Prepared {
    Ready(Box<ReplacementBundle>),
    /// No valid replacement can be made now; the reason goes to `stuckReason`.
    Blocked(String),
    /// The wallet's only coin big enough is held by the fee-coin split: wait, it is a few blocks.
    FeeCoinsBusy,
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
    /// The in-flight bundle sat in the node's mempool past `mempool_replace_after` and was
    /// replaced by a bundle for the same singleton coin that pays `fee_mojos` instead of
    /// `replaced_fee_mojos` (and carries `records`: the old ones plus any newly pending, at most
    /// `MAX_RECORDS_PER_SPEND`), accepted by the node (`replace_in_mempool`).
    Replaced { records: usize, fee_mojos: u64, replaced_fee_mojos: u64 },
    /// The in-flight bundle sat in the mempool past `mempool_replace_after` but no valid
    /// replacement could be made or the node did not take it; the old bundle stays in flight and
    /// `reason` is in `GET /v1/oracle/status`'s `stuckReason`.
    ReplacementBlocked { reason: String },
    /// An in-flight spend exists but isn't due for a retry yet, or is
    /// sitting in the mempool / has its coin already spent by something
    /// else -- either way, nothing to do this tick but wait. Also: a new
    /// batch is ready but the wallet's only free fee coin is held by the
    /// fee-coin split that is waiting for confirmation (`fee_reserve.rs`).
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
    /// How long a bundle may sit in the node's mempool unconfirmed before `replace_in_mempool`
    /// replaces it (`C2_MEMPOOL_REPLACE_AFTER_SECS`, `Config::mempool_replace_after`); zero turns
    /// the replacement off.
    pub mempool_replace_after: Duration,
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
    /// Shared with every other publisher in this process that also funds
    /// spends from the operator's plain-XCH wallet (today: `play::
    /// withdrawal_publisher::WithdrawalPublisher`) -- task fix,
    /// `docs/research/chain_service.md` §15: prevents two publishers from
    /// picking the same not-yet-confirmed fee coin. See `fee_allocator`'s
    /// module doc for the incident this closes.
    pub fee_allocator: std::sync::Arc<FeeCoinAllocator>,
    /// The parsed queue file as this process last saw it (`QueueCache`): every read of the file in
    /// this struct goes through `load_queue`, which reads and parses it only when it is not the file
    /// the cache holds, and every write through `save_queue`, which keeps what it wrote -- so a tick,
    /// a `POST` or a `GET` that finds the file as the last write left it does not parse it again.
    pub queue_cache: QueueCache,
    /// How long a confirmed record stays in the queue file after its confirmation
    /// (`C2_ORACLE_RETAIN_CONFIRMED_SECS`, `Config::oracle_retain_confirmed`): then `tick` drops its
    /// entry (`QueueFile::apply_retention`) and the seq is answered as confirmed without the detail
    /// (`QueueFile::pruned`). Records that are not confirmed -- pending, in flight, in conflict -- are
    /// never dropped. Zero keeps every entry for good.
    pub retain_confirmed: Duration,
}

impl<C: ChainClient> Publisher<C> {
    /// The queue file's content, from the cache while the file is the one it was read from
    /// (`QueueCache::load`). Shared and not to be changed: a caller that is going to change the queue
    /// takes a copy, `(*self.load_queue()?).clone()`, under `queue_lock`, and `save_queue`s it.
    fn load_queue(&self) -> Result<std::sync::Arc<QueueFile>> {
        self.queue_cache.load(&self.data_dir, &self.operator_name)
    }

    /// Writes the queue file atomically and keeps what was written as the cache's copy.
    fn save_queue(&self, queue: &QueueFile) -> Result<()> {
        self.queue_cache.save(queue, &self.data_dir, &self.operator_name)
    }

    /// `POST /v1/oracle/records`'s handler: durably enqueues newly
    /// submitted records, deduped against records already known
    /// confirmed/in-flight/pending (see `QueueFile::enqueue`,
    /// FIXED INTERFACE (b)), and reports the collisions. `accepted` is the `seq`s newly accepted
    /// (`acceptedSeqs`); a resubmitted or already-known `seq` is silently
    /// omitted, not an error. `conflicts` (`conflictSeqs`, `docs/research/chain_service.md` §18.2) is
    /// the `seq`s of this request that the service already holds with *different* content: not queued,
    /// as always, but no longer silent -- the caller can tell its own record from a foreign one that
    /// took the same `seq`, and the log says so too.
    pub async fn submit(&self, records: Vec<OracleRecord>) -> Result<EnqueueOutcome> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = (*self.load_queue()?).clone();
        let outcome = queue.enqueue(records);
        if !outcome.accepted.is_empty() {
            self.save_queue(&queue).context("persisting newly queued oracle records")?;
        }
        if !outcome.conflicts.is_empty() {
            tracing::warn!(
                seqs = ?outcome.conflicts,
                "POST /v1/oracle/records: seqs this service already holds with different content -- not queued, \
                 reported in conflictSeqs (another server on this singleton, or a database that issued a seq \
                 before it learned the floor; docs/research/chain_service.md §18.2)"
            );
        }
        Ok(outcome)
    }

    /// `submit`, for a caller that only wants the `seq`s newly accepted.
    pub async fn enqueue(&self, records: Vec<OracleRecord>) -> Result<Vec<u64>> {
        Ok(self.submit(records).await?.accepted)
    }

    /// `GET /v1/oracle/records/{seq}`'s handler (FIXED INTERFACE (b)): a
    /// plain, unlocked read of the queue's current persisted state -- same
    /// eventual-consistency reasoning as `status()` (see its doc comment and
    /// `queue_lock`'s).
    pub fn record_status(&self, seq: u64) -> Result<OracleRecordStatusResponse> {
        let queue = self.load_queue()?;
        // The held record's content rides along (`resultsHash`, `priceCents`, `asset`, `ts`,
        // `docs/research/chain_service.md` §18.2) wherever the queue still has it.
        let held = queue.held_record(seq);
        Ok(match queue.record_state(seq) {
            // `height`, `coinId` and the content are all `null` for a seq whose entry the retention has
            // dropped: confirmed, and no longer known by which spend.
            RecordState::Confirmed { height, coin_id } => {
                OracleRecordStatusResponse::new(seq, "confirmed", height, coin_id).with_record(held)
            }
            // Task fix (`docs/research/chain_service.md` §11): surfaced here
            // (per-record detail) as well as in `GET /v1/oracle/status`'s
            // `conflicts` (list-level summary) -- "never silently". The content is the chain's, like
            // the height and the coin.
            RecordState::Conflict { chain_height, chain_coin_id } => {
                OracleRecordStatusResponse::new(seq, "conflict", chain_height, Some(chain_coin_id)).with_record(held)
            }
            RecordState::InFlight => OracleRecordStatusResponse::new(seq, "in_flight", None, None).with_record(held),
            RecordState::Queued => OracleRecordStatusResponse::new(seq, "queued", None, None).with_record(held),
            RecordState::Unknown => OracleRecordStatusResponse::new(seq, "unknown", None, None),
        })
    }

    /// The retention of confirmed records on `queue`, the working copy of a caller that holds
    /// `queue_lock` (`QueueFile::apply_retention` with `retain_confirmed`): what it changed -- entries
    /// dropped, or old entries given the time of this pass -- is written out at once, and the log says
    /// how many went and which seqs. Nothing happens while the retention is off.
    fn retain_confirmed_records(&self, queue: &mut QueueFile, now: i64) -> Result<RetentionOutcome> {
        let outcome = queue.apply_retention(now, self.retain_confirmed.as_secs());
        if !outcome.changed() {
            return Ok(outcome);
        }
        self.save_queue(queue).context("persisting the queue after its retention pass")?;
        if outcome.stamped > 0 {
            tracing::info!(
                stamped = outcome.stamped,
                retain_secs = self.retain_confirmed.as_secs(),
                "oracle queue retention: confirmed entries written before their confirmation time was kept have \
                 the time of this pass now and are kept for a whole retention from it"
            );
        }
        if let Some((first, last)) = outcome.pruned_seqs {
            tracing::info!(
                pruned = outcome.pruned,
                first_seq = first,
                last_seq = last,
                kept = outcome.kept,
                retain_secs = self.retain_confirmed.as_secs(),
                "oracle queue retention: dropped confirmed entries that are older than the retention -- their \
                 seqs still answer `confirmed`, without height, coin and content (`QueueFile::pruned`)"
            );
        }
        Ok(outcome)
    }

    /// One retention pass as of `now_unix`, on its own: what `tick` does by the clock every few
    /// minutes, for a caller that names the time -- a test that does not wait a day, or an operator
    /// tool. Takes the queue lock like every writer. Does nothing while the retention is off.
    pub async fn apply_retention_at(&self, now_unix: i64) -> Result<RetentionOutcome> {
        let _guard = self.queue_lock.lock().await;
        let mut queue = (*self.load_queue()?).clone();
        self.retain_confirmed_records(&mut queue, now_unix)
    }

    /// `GET /v1/oracle/status`'s handler. Must never await a network call:
    /// everything here is either a local file read (`load_queue`, which
    /// reads the queue file only when it is not the copy already held,
    /// `load_oracle_state` -- fast, and the queue/state files are exactly
    /// what a concurrent `enqueue`/`tick` is durably writing, so this still
    /// reflects the live queue) or a read of `balance_snapshot`, the cache
    /// `refresh_balance_snapshot` fills in from off to the side. See
    /// `balance_snapshot`'s doc comment for why (task fix).
    pub async fn status(&self) -> Result<OracleStatusResponse> {
        let queue = self.load_queue()?;
        let oracle_state = load_oracle_state(&self.data_dir, &self.operator_name).ok();
        let operator_balance_mojos = self.balance_snapshot.read().await.clone();
        // The fee-coin gauge is the last wallet scan's view, read from memory like the balance --
        // never a chain call here (see `balance_snapshot`'s doc comment).
        let fee_coin_view = self.fee_allocator.view();

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
            max_known_seq: queue.max_known_seq(),
            record_format: RECORD_FORMAT,
            fee_coins_free: fee_coin_view.map(|view| u32::try_from(view.free_coins).unwrap_or(u32::MAX)),
            fee_coins_free_mojos: fee_coin_view.map(|view| view.free_mojos.to_string()),
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
        let queue = self.load_queue()?;
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
        // One wallet scan feeds both numbers: the balance is the sum of the coins, and the same
        // coins are what the fee-coin gauge (`feeCoinsFree`) counts.
        let snapshot = match load_operator_master_sk(&self.data_dir, &self.operator_name) {
            Ok(master_sk) => match self.chain.unspent_coins(&master_sk).await {
                Ok(coins) => {
                    self.fee_allocator.record_scan(&coins).await;
                    let balance: u64 = coins.iter().map(|(_, coin)| coin.amount).sum();
                    Some(balance.to_string())
                }
                Err(_) => {
                    self.fee_allocator.clear_view();
                    None
                }
            },
            Err(_) => {
                self.fee_allocator.clear_view();
                None
            }
        };
        *self.balance_snapshot.write().await = snapshot;
    }

    /// One pass of the fee-coin reserve (`fee_reserve.rs`, `docs/research/chain_service.md`
    /// §16) on behalf of this publisher: polls a split that is in flight and, only when
    /// `may_start` (the oracle has nothing in flight), starts one if the wallet holds fewer free
    /// fee coins than `C2_FEE_COINS_MIN` and no fee coin is reserved by anyone. Best effort by
    /// design: a failed pass is logged and forgotten -- housekeeping never fails a tick, so it can
    /// never degrade `/health` or hold up a publish. `None` = nothing ran (reserve off, dry run, no
    /// operator key yet) or the pass failed.
    pub async fn maintain_fee_coins(&self, may_start: bool) -> Option<MaintenanceOutcome> {
        if !self.push_enabled || self.fee_allocator.policy().min_free == 0 {
            return None;
        }
        let master_sk = load_operator_master_sk(&self.data_dir, &self.operator_name).ok()?;
        let params = MaintenanceParams {
            push_enabled: self.push_enabled,
            max_fee_mojos: self.max_fee_mojos,
            fee_target_time_secs: self.fee_target_time_secs,
            retry_backoff: self.retry_backoff,
        };
        match self.fee_allocator.maintain(&self.chain, &master_sk, &params, may_start).await {
            Ok(outcome) => {
                match &outcome {
                    MaintenanceOutcome::SplitPushed { input, outputs, fee_mojos } => tracing::info!(
                        input = %input,
                        outputs,
                        fee_mojos,
                        "fee-coin reserve: pushed a split of the largest wallet coin into fee-sized coins"
                    ),
                    MaintenanceOutcome::SplitConfirmed => tracing::info!("fee-coin reserve: the split confirmed"),
                    MaintenanceOutcome::SplitDropped => tracing::warn!(
                        "fee-coin reserve: the split is neither confirmed nor in the mempool -- will try again after the backoff"
                    ),
                    MaintenanceOutcome::SplitRejected { error } => {
                        tracing::warn!(%error, "fee-coin reserve: the node rejected the split -- will try again after the backoff")
                    }
                    MaintenanceOutcome::NoCoinLargeEnough { largest, needed } => tracing::warn!(
                        largest,
                        needed,
                        "fee-coin reserve is below C2_FEE_COINS_MIN but no wallet coin is big enough to split -- fund the operator wallet"
                    ),
                    other => tracing::debug!(?other, "fee-coin reserve"),
                }
                Some(outcome)
            }
            Err(e) => {
                tracing::warn!("fee-coin reserve pass failed (will retry): {e:#}");
                None
            }
        }
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
        let mut queue = (*self.load_queue()?).clone();
        let outcome = self.reconcile_queue_with_chain(&mut queue).await?;
        if outcome.changed() {
            self.save_queue(&queue).context("persisting chain-reconciled queue (bootstrap)")?;
        }
        Ok(outcome)
    }

    /// The fee coin id a persisted in-flight spend (if any) currently
    /// depends on -- used once at startup (`main.rs`, right after
    /// `bootstrap_from_chain`) to seed the shared `FeeCoinAllocator` before
    /// any tick runs, so a fresh process's in-memory reservation set
    /// doesn't forget a fee coin a surviving in-flight bundle from before
    /// the restart still needs (task fix, `docs/research/chain_service.md`
    /// §15).
    pub fn in_flight_fee_coin_id(&self) -> Result<Option<chia_protocol::Bytes32>> {
        let queue = self.load_queue()?;
        Ok(queue.in_flight.as_ref().and_then(|f| fee_coin_of(&f.bundle, f.spent_coin)).map(|c| c.coin_id()))
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
        let in_flight_before = queue.in_flight.clone();
        let mut outcome = queue.reconcile_with_chain(&walk, now_unix());

        for warning in &outcome.decode_warnings {
            tracing::warn!(%warning, "oracle lineage walk");
        }

        // Whenever the chain walk takes the in-flight spend off the queue -- its singleton coin was
        // consumed, or every record of it is already on chain -- the bundle is finished and so is
        // every fee coin reserved for it, whichever version of the bundle it was (task C3/(e):
        // the coin reserved for a version that lost the race is released too). Releasing is
        // idempotent; a coin that was never reserved in this process is a no-op.
        if let Some(before) = &in_flight_before
            && queue.in_flight.is_none()
        {
            for coin_id in before.every_fee_coin() {
                self.fee_allocator.release(coin_id).await;
            }
        }
        // The spend that consumed the singleton coin was found and read: settle it -- the oracle
        // state moves on, and the log says what happened (INFO for our own bundle, WARN for a spend
        // that was not ours) instead of reading like an incident on every healthy batch.
        if let (Some(resolved), Some(before)) = (&outcome.resolved_in_flight, &in_flight_before) {
            self.settle_resolved_in_flight(&mut oracle_state, before, resolved)?;
        }
        if outcome.in_flight_unreadable {
            tracing::warn!(
                "the spend that consumed the in-flight bundle's singleton coin could not be read completely \
                 (no puzzle/solution from coinset, or an announcement that does not decode) -- not concluding \
                 anything about the bundle's records, will read it again on the next scan"
            );
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

    /// The part of settling an in-flight spend that the chain walk found consumed
    /// (`ResolvedInFlight`) that is not queue bookkeeping: advances `operator.oracle.json` to the
    /// singleton the spend recreated and says what happened. Idempotent against a crash between
    /// this function and the caller's save of the queue: when `current_coin` is no longer the
    /// consumed coin there is nothing to advance, and the next pass reaches the same accounting
    /// from the same chain.
    ///
    /// Why the next coin and proof of the *latest* version of the bundle are right whichever version
    /// the chain took: the successor of a singleton spend has the id of (parent id, puzzle hash,
    /// amount), and the proof to spend it is the lineage proof of the consumed coin -- neither
    /// depends on the records or the fee of the bundle (`spend::build_oracle_publish_batch`).
    fn settle_resolved_in_flight(
        &self,
        oracle_state: &mut OracleState,
        in_flight: &InFlightSpend,
        resolved: &ResolvedInFlight,
    ) -> Result<()> {
        if oracle_state.current_coin == in_flight.spent_coin {
            oracle_state.current_coin = in_flight.next_coin;
            oracle_state.current_proof_dto = in_flight.next_proof.clone();
            oracle_state.next_sequence = oracle_state.next_sequence.saturating_add(1);
            save_oracle_state_atomic(&self.data_dir, &self.operator_name, oracle_state)
                .context("persisting oracle state advanced by the confirmed in-flight spend")?;
        }

        let fee_mojos = in_flight.fee_of_version_announcing(&resolved.announced);
        if resolved.ours {
            tracing::info!(
                records = resolved.confirmed.len(),
                requeued = resolved.requeued.len(),
                height = ?resolved.height,
                fee_mojos = ?fee_mojos,
                bundle_id = %in_flight.bundle_id,
                "oracle publish batch confirmed"
            );
            if !resolved.requeued.is_empty() {
                tracing::info!(
                    seqs = ?resolved.requeued,
                    "the chain took an earlier version of the in-flight bundle: the records it did not \
                     carry are back in the queue and a later batch publishes them (docs/research/chain_service.md §17.4)"
                );
            }
        } else {
            tracing::warn!(
                announced = ?resolved.announced,
                height = ?resolved.height,
                requeued = ?resolved.requeued,
                confirmed = ?resolved.confirmed,
                conflicts = ?resolved.conflicts,
                bundle_id = %in_flight.bundle_id,
                "the spend that consumed the in-flight bundle's singleton coin is not this service's bundle \
                 (it announced records the bundle does not carry, or none) -- someone else published; the \
                 records the chain does not have are back in the queue, the rest are accounted for from the chain"
            );
        }
        Ok(())
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
        // Fee-coin reserve housekeeping (`fee_reserve.rs`) -- before the state machine, and
        // deliberately outside `queue_lock`: a wallet scan is 20 sequential coinset calls (~12 s
        // measured on testnet11, 2026-10-01), and holding the lock that long would stall every
        // `POST /v1/oracle/records` behind it. The pass never touches the queue file; it only
        // reads `in_flight` (written solely by this task's own `tick_once`, so an unlocked read
        // here cannot race -- `enqueue` only ever appends to `pending`). It polls a split in
        // flight on every tick and may start one only while the oracle has nothing in flight (and,
        // inside the pass, nothing reserved by anyone). Records that are merely pending do not
        // block it: the only thing a split can ever delay is a batch that wants the wallet's one
        // and only usable coin, by the few blocks the split needs, once -- and that single-coin
        // trap is exactly what the reserve exists to end (`start_new_batch` waits, it does not
        // fail, see `FeeCoinsBusy`). Never fails the tick; not counted in `tick_failures`.
        let may_start = self.load_queue().is_ok_and(|queue| queue.in_flight.is_none());
        self.maintain_fee_coins(may_start).await;

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
        let mut queue = (*self.load_queue()?).clone();

        // Task fix (`docs/research/chain_service.md` §11): "also run that
        // forward scan periodically (cheap) so the confirmed map always
        // reflects the chain, not only local bookkeeping" -- cheap once
        // caught up (checkpointed): a single `coin_record` call confirming
        // the tip is still unspent. Runs before anything else below so a
        // stale/conflicting record already on chain never reaches
        // `start_new_batch` this tick either.
        let reconcile_outcome = self.reconcile_queue_with_chain(&mut queue).await?;
        if reconcile_outcome.changed() {
            self.save_queue(&queue).context("persisting chain-reconciled queue")?;
        }

        // Retention of confirmed records, every `RETENTION_EVERY_SECS`: housekeeping, like the fee-coin
        // reserve in `tick` -- a pass that fails is logged and left to the next turn, the tick goes on.
        let now = now_unix();
        if self.queue_cache.claim_retention_pass(now, RETENTION_EVERY_SECS)
            && let Err(e) = self.retain_confirmed_records(&mut queue, now)
        {
            tracing::warn!("oracle queue retention pass failed (the next one is due in {RETENTION_EVERY_SECS} s): {e:#}");
        }

        if let Some(in_flight) = queue.in_flight.clone() {
            return self.advance_in_flight(&mut queue, in_flight).await;
        }

        if queue.pending.is_empty() {
            // A tick whose chain walk settled our own bundle (and has nothing more to publish)
            // reports the confirmation, like the tick that used to settle it in
            // `advance_in_flight`; the INFO line was logged by the walk.
            return Ok(match &reconcile_outcome.resolved_in_flight {
                Some(resolved) if resolved.ours => TickOutcome::Confirmed { records: resolved.confirmed.len() },
                _ => TickOutcome::Idle,
            });
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
            return self.settle_confirmed_in_flight(queue, in_flight, record_info.confirmed_height).await;
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
            // In the mempool: wait for it -- for `mempool_replace_after`. Past that the bundle is
            // replaced (`replace_in_mempool`): the first version of this code left it alone
            // forever ("Not implemented; just wait"), and on 2026-10-02 a fee-0 bundle sat there
            // for ten minutes while every batch behind it piled up (§17.1). The rule the
            // replacement has to satisfy is `mempool_rule.rs` (§17.3): it spends a *superset* of
            // the old bundle's coins -- the same singleton coin and the fee coin the old bundle
            // spent -- at a fee at least `MEMPOOL_MIN_FEE_INCREASE` higher; the earlier note here
            // that a same-coin, higher-fee bundle "does not qualify" was wrong: same coins *is* a
            // superset.
            return self.replace_in_mempool_if_due(queue, in_flight).await;
        }

        // Not in the mempool any more (or never): whatever kept the bundle from being replaced
        // is over. Clear it so `stuckReason` stops naming it.
        let mut in_flight = in_flight;
        if in_flight.replace_blocked.take().is_some() {
            queue.in_flight = Some(in_flight.clone());
            self.save_queue(queue).context("clearing a stale 'replacement blocked' note from the in-flight spend")?;
        }

        // Task fix (`docs/research/chain_service.md` §15): the singleton
        // coin was just confirmed unspent above and this bundle isn't in
        // the mempool -- so if the push was rejected as a double-spend, the
        // input that's actually spent elsewhere cannot be the singleton
        // (already ruled out); it can only be this bundle's *fee* coin
        // (`attach_fee_coin`). Verify that on-chain (never act on the error
        // text alone) before treating it as "safe to drop and replace": if
        // it really is spent, release the dead reservation and rebuild
        // immediately with a freshly allocated fee coin. This is a
        // deterministic failure -- the exact same push will fail with the
        // exact same error forever otherwise -- so it must never burn
        // `max_retry_attempts` the way the old code did (the incident this
        // fixes: 8 wasted retries, then stuck forever).
        if !in_flight.last_push_accepted
            && is_coin_spent_rejection(in_flight.last_push_error.as_deref())
            && let Some(fee_coin) = fee_coin_of(&in_flight.bundle, in_flight.spent_coin)
        {
            let fee_coin_record = self
                .chain
                .coin_record(fee_coin.coin_id())
                .await
                .context("checking the fee coin before treating a rejection as a spent fee coin")?;
            if fee_coin_record.spent {
                tracing::warn!(
                    bundle_id = %in_flight.bundle_id,
                    fee_coin = %fee_coin.coin_id(),
                    "in-flight oracle publish spend's fee coin was spent elsewhere (not the \
                     singleton) -- dropping it and rebuilding with a fresh fee coin, no retry \
                     attempt spent"
                );
                return self.rebuild_in_flight(queue, in_flight).await;
            }
            // Couldn't positively identify a spent fee coin (neither input
            // is actually spent -- e.g. a transient node-side hiccup that
            // merely *said* DOUBLE_SPEND) -- safer to fall through to the
            // generic retry/stuck handling below than to guess.
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

        in_flight.attempts += 1;
        in_flight.last_attempt_at_unix = now_unix();
        in_flight.last_push_accepted = outcome.accepted;
        in_flight.last_push_error = outcome.error.clone();
        let accepted = outcome.accepted;
        queue.in_flight = Some(in_flight);
        self.save_queue(queue).context("persisting retried in-flight spend")?;

        Ok(TickOutcome::Retried { accepted })
    }

    /// `in_flight.next_coin` exists on chain: the singleton coin the bundle spends was consumed by
    /// *some* spend -- the successor of a singleton spend has the same id whichever bundle made
    /// it, so this alone does not say that it was this bundle, nor which version of it.
    ///
    /// The chain walk that opens every tick (`reconcile_queue_with_chain`) normally settles this
    /// first and there is no in-flight spend left to reach here -- the INFO line
    /// "oracle publish batch confirmed" comes from there (§17.5). This branch still runs when a
    /// block arrived between that walk and the check in `advance_in_flight`, and when the walk
    /// could not read the consuming spend. It walks again, now that the coin is known to be consumed,
    /// and settles from what the chain announced. If the spend still cannot be read:
    ///
    /// * a bundle that was never replaced or rebuilt is taken to be the one that confirmed, as it
    ///   always was (every record confirmed at `confirmed_height`);
    /// * a bundle with earlier versions is **not** guessed at -- the records of the version that
    ///   lost must not be marked confirmed -- it waits until the chain can be read.
    async fn settle_confirmed_in_flight(
        &self,
        queue: &mut QueueFile,
        in_flight: InFlightSpend,
        confirmed_height: Option<u32>,
    ) -> Result<TickOutcome> {
        let outcome = self.reconcile_queue_with_chain(queue).await?;
        if queue.in_flight.is_none() {
            self.save_queue(queue).context("clearing in-flight spend after confirmation")?;
            let records = outcome
                .resolved_in_flight
                .as_ref()
                .map_or(outcome.dropped_confirmed.len(), |resolved| resolved.confirmed.len());
            return Ok(TickOutcome::Confirmed { records });
        }

        if !in_flight.superseded.is_empty() {
            if outcome.changed() {
                self.save_queue(queue)
                    .context("persisting chain-reconciled queue while waiting for the confirming spend")?;
            }
            tracing::warn!(
                bundle_id = %in_flight.bundle_id,
                versions = in_flight.superseded.len() + 1,
                "the in-flight bundle's singleton coin is consumed but the spend cannot be read yet, and the bundle \
                 has earlier versions -- not deciding which records were published until the chain can be read"
            );
            return Ok(TickOutcome::WaitingForConfirmation);
        }

        let mut oracle_state = load_oracle_state(&self.data_dir, &self.operator_name)
            .context("loading oracle state to finalize a confirmed spend")?;
        oracle_state.current_coin = in_flight.next_coin;
        oracle_state.current_proof_dto = in_flight.next_proof.clone();
        oracle_state.next_sequence += 1;
        save_oracle_state_atomic(&self.data_dir, &self.operator_name, &oracle_state)
            .context("persisting advanced oracle state")?;

        // Per-record confirmation (FIXED INTERFACE (b), `GET /v1/oracle/records/{seq}`): every
        // record in this batch, not just "the last one" -- `in_flight.records` is no longer
        // guaranteed sorted ascending by seq once a LATE/backfill record (arbitrary seq) can be
        // merged into a rebuild alongside newer ones (`rebuild_in_flight`), so `.last()`/`.max()`
        // on it would both be the wrong question for per-seq tracking, only the right one for the
        // *overall* high-water mark below.
        let confirmed_coin_id = format!("{}", in_flight.next_coin.coin_id());
        let confirmed_at = now_unix();
        for record in &in_flight.records {
            queue.confirmed.insert(
                record.seq,
                ConfirmedRecordInfo {
                    height: confirmed_height,
                    coin_id: confirmed_coin_id.clone(),
                    record: Some(*record),
                    confirmed_at_unix: Some(confirmed_at),
                },
            );
        }
        let confirmed_count = in_flight.records.len();
        queue.last_confirmed_seq = in_flight.records.iter().map(|r| r.seq).max().max(queue.last_confirmed_seq);
        queue.last_confirmed_height = confirmed_height.or(queue.last_confirmed_height);
        // The fee coin this confirmed spend consumed is now genuinely, permanently spent on chain --
        // release its reservation (task fix, `docs/research/chain_service.md` §15): a gap-scan will
        // never offer it again anyway, this is just housekeeping so the shared allocator's reserved
        // set doesn't grow forever.
        for coin_id in in_flight.every_fee_coin() {
            self.fee_allocator.release(coin_id).await;
        }
        queue.in_flight = None;
        self.save_queue(queue).context("clearing in-flight spend after confirmation")?;

        tracing::info!(
            records = confirmed_count,
            height = ?confirmed_height,
            fee_mojos = in_flight.fee_mojos,
            "oracle publish batch confirmed (the chain walk could not read the confirming spend; the bundle was \
             never replaced, so it is taken to be the one that confirmed)"
        );
        Ok(TickOutcome::Confirmed { records: confirmed_count })
    }

    /// The in-flight bundle is in the node's mempool: replace it once it has waited
    /// `mempool_replace_after` since the last time this publisher acted on it
    /// (`last_attempt_at_unix`: the last push, or the last time a replacement was tried and could
    /// not be made -- so a refusal is retried after another full wait, not on every tick).
    async fn replace_in_mempool_if_due(&self, queue: &mut QueueFile, in_flight: InFlightSpend) -> Result<TickOutcome> {
        if self.mempool_replace_after.is_zero() {
            return Ok(TickOutcome::WaitingForConfirmation);
        }
        let waited = now_unix() - in_flight.last_attempt_at_unix;
        if waited < i64::try_from(self.mempool_replace_after.as_secs()).unwrap_or(i64::MAX) {
            return Ok(TickOutcome::WaitingForConfirmation);
        }
        self.replace_in_mempool(queue, in_flight).await
    }

    /// Records that the replacement could not be made, on the bundle that stays in flight, and
    /// restarts the wait: `last_attempt_at_unix` is when this publisher last acted on the bundle.
    /// The bundle keeps waiting in the mempool -- it may still confirm -- and `stuckReason` says why
    /// nothing better is happening to it.
    fn block_replacement(&self, queue: &mut QueueFile, mut in_flight: InFlightSpend, reason: String) -> Result<TickOutcome> {
        tracing::warn!(
            bundle_id = %in_flight.bundle_id,
            fee_mojos = in_flight.fee_mojos,
            %reason,
            "in-flight bundle has waited in the mempool past C2_MEMPOOL_REPLACE_AFTER_SECS but cannot be replaced -- \
             it keeps waiting"
        );
        in_flight.replace_blocked = Some(reason.clone());
        in_flight.last_attempt_at_unix = now_unix();
        queue.in_flight = Some(in_flight);
        self.save_queue(queue).context("persisting the 'replacement blocked' note on the in-flight spend")?;
        Ok(TickOutcome::ReplacementBlocked { reason })
    }

    /// Replaces the in-flight bundle, which the node holds in its mempool but does not confirm, with
    /// a bundle that the node's replacement rule accepts (`mempool_rule.rs`, `docs/research/
    /// chain_service.md` §17.3) and that pays enough to be taken:
    ///
    /// * **the same singleton coin**, with the old records plus any newly pending (at most
    ///   `MAX_RECORDS_PER_SPEND`, like `rebuild_in_flight`) -- both bundles spend that coin, so at
    ///   most one of them can ever confirm (the chain says which, `QueueFile::resolve_consumed_in_flight`);
    /// * **a superset of the old bundle's coins**: a bundle that had no fee coin gains one (reserved
    ///   from the shared allocator); a bundle that had one keeps spending that very coin, with a
    ///   higher fee -- the replacement may not drop a coin the old bundle spent;
    /// * **a fee** of `max(2 x old, the estimate for the new cost, old + 10,000,000)`, raised to the
    ///   exact minimum of the fee-per-cost rule if the new cost needs it, never above
    ///   `C2_MAX_FEE_MOJOS` (nor above what the fee coin holds). When the cap leaves no valid
    ///   replacement the old bundle keeps waiting and `stuckReason` says so
    ///   (`ReplacementBlocked`) -- nothing is pushed and nothing is retried before another full wait.
    ///
    /// Persistence follows `start_new_batch`: the replacement is made durable *before* it is
    /// pushed. If the node then does not take it (a `FAILED` answer, or `PENDING` -- parked in
    /// the node's conflict cache, not in the mempool), the old bundle is put back as the in-flight
    /// spend, the records merged from `pending` return to it, and a fee coin newly reserved for the
    /// replacement is released: the persisted state keeps describing what the node actually holds.
    /// Whichever of the two the chain ends up taking, nothing is lost or published twice -- see
    /// `ResolvedInFlight`.
    async fn replace_in_mempool(&self, queue: &mut QueueFile, in_flight: InFlightSpend) -> Result<TickOutcome> {
        let manager = load_operator_wallet_key(&self.data_dir, &self.operator_name)
            .context("loading operator wallet key")?;
        let master_sk = load_operator_master_sk(&self.data_dir, &self.operator_name)
            .context("loading operator master key")?;
        let oracle_state = load_oracle_state(&self.data_dir, &self.operator_name)
            .context("loading oracle state to replace a bundle in the mempool")?;
        if oracle_state.current_coin != in_flight.spent_coin {
            // Same belt-and-suspenders as `rebuild_in_flight`: the singleton coin was just checked
            // unspent by `advance_in_flight`, and `current_coin` only moves on a confirmed spend.
            bail!(
                "oracle state current_coin {} no longer matches the in-flight spend's coin {} -- \
                 refusing to replace (needs manual reconciliation, see docs/research/chain_service.md §5)",
                oracle_state.current_coin.coin_id(),
                in_flight.spent_coin.coin_id()
            );
        }

        let old_fee = in_flight.fee_mojos;
        let old_cost = measure_bundle_cost(&in_flight.bundle.coin_spends)
            .context("measuring the in-mempool bundle's cost")?
            .total_cost;
        let minimum_by_increase = old_fee.saturating_add(MEMPOOL_MIN_FEE_INCREASE);
        if minimum_by_increase > self.max_fee_mojos {
            return self.block_replacement(
                queue,
                in_flight,
                format!(
                    "the node accepts a replacement only if it pays at least {MEMPOOL_MIN_FEE_INCREASE} mojos more than the \
                     bundle it replaces ({old_fee} mojos): {minimum_by_increase} mojos, and C2_MAX_FEE_MOJOS is {} mojos",
                    self.max_fee_mojos
                ),
            );
        }

        // The newly pending records ride along, like in `rebuild_in_flight`.
        let mut records = in_flight.records.clone();
        let room = MAX_RECORDS_PER_SPEND.saturating_sub(records.len());
        let merged: Vec<OracleRecord> = if room > 0 { queue.take_batch(room) } else { Vec::new() };
        records.extend(merged.iter().copied());

        let prepared = match self
            .prepare_replacement(&in_flight, &records, &oracle_state, &manager, &master_sk, old_cost)
            .await
        {
            Ok(prepared) => prepared,
            Err(e) => {
                queue.pending.splice(0..0, merged);
                return Err(e);
            }
        };
        let ready = match prepared {
            Prepared::Ready(ready) => *ready,
            Prepared::Blocked(reason) => {
                queue.pending.splice(0..0, merged);
                return self.block_replacement(queue, in_flight, reason);
            }
            Prepared::FeeCoinsBusy => {
                queue.pending.splice(0..0, merged);
                tracing::info!("not replacing the bundle in the mempool yet: the fee-coin split is holding the wallet's only fee coin");
                return Ok(TickOutcome::WaitingForConfirmation);
            }
        };

        let now = now_unix();
        let mut superseded = in_flight.superseded.clone();
        superseded.push(in_flight.as_superseded("replaced in the mempool", now));
        let replacement = InFlightSpend {
            records,
            spent_coin: oracle_state.current_coin,
            next_coin: ready.next_coin,
            next_proof: ready.next_proof,
            bundle: ready.bundle.clone(),
            bundle_id: ready.bundle_id,
            fee_mojos: ready.fee_mojos,
            broadcast_at_unix: now,
            last_attempt_at_unix: now,
            // A materially different transaction gets its own retry budget.
            attempts: 1,
            last_push_accepted: false,
            last_push_error: None,
            superseded,
            replace_blocked: None,
        };
        let batch_len = replacement.records.len();
        let replacement_fee = replacement.fee_mojos;
        queue.in_flight = Some(replacement);
        // Durability first, same invariant as `start_new_batch`: a crash after this line and
        // before the push leaves a persisted bundle that the next tick pushes.
        if let Err(e) = self.save_queue(queue) {
            // Nothing reached the disk, so nothing refers to a fee coin reserved for this
            // replacement; the next tick starts over and must be able to pick it again.
            if let Some(coin) = ready.newly_reserved {
                self.fee_allocator.release(coin.coin_id()).await;
            }
            return Err(e.context("persisting the replacement in-flight spend before broadcasting"));
        }

        let bundle_name = ready.bundle.name();
        let pushed = self.chain.push_tx(ready.bundle).await.context("push_tx for the replacement bundle")?;
        // `push_tx` answers success with `status: "PENDING"` when it parked the bundle in its
        // conflict cache instead of replacing the old one (`mempool_rule.rs` module doc), so
        // "accepted" is not enough: it has to be a `SUCCESS`, or the mempool has to say it holds it.
        let taken = pushed.accepted
            && (pushed.status.as_deref() == Some("SUCCESS")
                || self.chain.in_mempool(bundle_name).await.unwrap_or(false));

        if taken {
            if let Some(current) = queue.in_flight.as_mut() {
                current.last_push_accepted = true;
                current.last_push_error = None;
            }
            self.save_queue(queue).context("persisting the replacement's push outcome")?;
            tracing::info!(
                records = batch_len,
                fee_mojos = replacement_fee,
                replaced_fee_mojos = old_fee,
                waited_secs = now - in_flight.last_attempt_at_unix,
                "replaced the oracle publish bundle that sat in the mempool"
            );
            return Ok(TickOutcome::Replaced { records: batch_len, fee_mojos: replacement_fee, replaced_fee_mojos: old_fee });
        }

        // The node did not take the replacement: put the old bundle back as the in-flight spend,
        // the merged records back in `pending`, and release the fee coin that was reserved for the
        // replacement only. What the node holds is still the old bundle.
        let detail = match (&pushed.error, &pushed.status) {
            (Some(error), _) => error.clone(),
            (None, Some(status)) => format!("status {status}"),
            (None, None) => "no answer".to_string(),
        };
        if let Some(coin) = ready.newly_reserved {
            self.fee_allocator.release(coin.coin_id()).await;
        }
        queue.pending.splice(0..0, merged);
        self.block_replacement(
            queue,
            in_flight,
            format!("the node did not take the replacement bundle (fee {replacement_fee} mojos): {detail}"),
        )
    }

    /// Builds the replacement bundle for `replace_in_mempool`: the draft spend of the singleton for
    /// `records`, a fee spend that satisfies the node's replacement rule (or the reason it cannot
    /// be made), signed and priced. Reserves a fee coin only when the old bundle had none; releases
    /// it again on every path that does not end in `Prepared::Ready`.
    async fn prepare_replacement(
        &self,
        in_flight: &InFlightSpend,
        records: &[OracleRecord],
        oracle_state: &OracleState,
        manager: &WalletKey,
        master_sk: &SecretKey,
        old_cost: u64,
    ) -> Result<Prepared> {
        let old_fee = in_flight.fee_mojos;
        let cap = self.max_fee_mojos;
        let minimum_by_increase = old_fee.saturating_add(MEMPOOL_MIN_FEE_INCREASE);

        let draft = build_oracle_publish_batch(
            oracle_state.launcher_id,
            oracle_state.current_coin,
            oracle_state.current_proof(),
            manager,
            records,
        )
        .context("building the replacement oracle-publish batch spend")?;
        let draft_cost = measure_bundle_cost(&draft.coin_spends)
            .context("measuring the replacement draft's cost")?
            .total_cost;

        // What the fee should be: at least the old one doubled (like a rebuild), at least what
        // the chain asks for this cost at a shorter target than a fresh batch (or the fallback rate
        // when the estimate is not available -- never nothing), at least what the node's rule
        // demands -- and never above the cap.
        let shorter_target = (self.fee_target_time_secs / 3).max(1);
        let estimate = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost, shorter_target, u64::MAX)
            .await
            .fee_mojos;
        let wanted = old_fee.saturating_mul(2).max(estimate).max(minimum_by_increase);

        // The fee coin. A replacement must spend every coin the old bundle spent (rule 1): the
        // old bundle's fee coin stays; a bundle that had none gets one.
        let (payer, fee_coin, newly_reserved, ceiling) = match fee_coin_of(&in_flight.bundle, in_flight.spent_coin) {
            Some(coin) => {
                let Some(payer) = FeeCoinAllocator::owner_key_of(master_sk, &coin) else {
                    return Ok(Prepared::Blocked(format!(
                        "the old bundle's fee coin {} is not one of this wallet's first {} addresses, so it cannot be \
                         spent again, and the node only accepts a replacement that spends every coin the old bundle spends",
                        coin.coin_id(),
                        c2_cli::chain::ADDRESS_GAP
                    )));
                };
                let record = self
                    .chain
                    .coin_record(coin.coin_id())
                    .await
                    .context("checking the old bundle's fee coin before replacing the bundle")?;
                if record.spent {
                    return Ok(Prepared::Blocked(format!(
                        "the old bundle's fee coin {} is already spent on chain: that bundle cannot confirm and will \
                         leave the mempool, a replacement that must spend the same coin is impossible",
                        coin.coin_id()
                    )));
                }
                let ceiling = cap.min(coin.amount);
                if minimum_by_increase > ceiling {
                    return Ok(Prepared::Blocked(format!(
                        "the replacement must spend the old bundle's fee coin ({} mojos) and pay at least \
                         {minimum_by_increase} mojos, C2_MAX_FEE_MOJOS is {cap} mojos",
                        coin.amount
                    )));
                }
                (payer, coin, None, ceiling)
            }
            None => {
                let (payer, coin) = match self.fee_allocator.reserve(&self.chain, master_sk, wanted.min(cap)).await {
                    Ok(reserved) => reserved,
                    Err(e) if e.downcast_ref::<FeeCoinsBusy>().is_some() => return Ok(Prepared::FeeCoinsBusy),
                    Err(e) if e.downcast_ref::<NoFeeCoin>().is_some() => {
                        return Ok(Prepared::Blocked(format!(
                            "the old bundle paid no fee coin and the wallet has no unspent, unreserved coin that can pay the \
                             replacement's fee of up to {} mojos: {e:#}",
                            wanted.min(cap)
                        )));
                    }
                    Err(e) => return Err(e.context("no funding coin to pay the replacement bundle's fee with")),
                };
                let ceiling = cap.min(coin.amount);
                (payer, coin, Some(coin), ceiling)
            }
        };

        // From here on a fee coin that was reserved for this replacement is given back on any way
        // out that is not `Ready`.
        let release_on_exit = async |reserved: Option<Coin>| {
            if let Some(coin) = reserved {
                self.fee_allocator.release(coin.coin_id()).await;
            }
        };

        let mut fee = wanted.min(ceiling);
        // The cost of the real bundle -- fee spend included -- decides the fee-per-cost rule; the
        // fee spend's own bytes depend a little on the amounts in it, so settle in a few rounds
        // (one is normal: `required_replacement_fee` already leaves slack).
        for _ in 0..3 {
            let fee_spend = match build_fee_only(fee_coin, &payer, fee) {
                Ok(spend) => spend,
                Err(e) => {
                    release_on_exit(newly_reserved).await;
                    return Err(e.context("building the replacement's fee-only coin spend"));
                }
            };
            let mut coin_spends = draft.coin_spends.clone();
            coin_spends.extend(fee_spend.coin_spends);
            let new_cost = match measure_bundle_cost(&coin_spends) {
                Ok(cost) => cost.total_cost,
                Err(e) => {
                    release_on_exit(newly_reserved).await;
                    return Err(e.context("measuring the replacement bundle's cost"));
                }
            };
            let required = required_replacement_fee(old_fee, old_cost, new_cost);
            if fee >= required {
                let signature = draft.signature.clone() + &fee_spend.signature;
                let bundle_id = bundle_display_id(&signature);
                return Ok(Prepared::Ready(Box::new(ReplacementBundle {
                    bundle: SpendBundle::new(coin_spends, signature),
                    bundle_id,
                    fee_mojos: fee,
                    next_coin: draft.next_coin,
                    next_proof: draft.next_proof.into(),
                    newly_reserved,
                })));
            }
            if required > ceiling {
                release_on_exit(newly_reserved).await;
                return Ok(Prepared::Blocked(format!(
                    "the node's replacement rule needs this bundle (cost {new_cost}) to pay at least {required} mojos -- more than \
                     the {old_fee} mojos of the bundle it replaces (cost {old_cost}) plus {MEMPOOL_MIN_FEE_INCREASE}, and a higher \
                     fee per cost -- but it may pay at most {ceiling} mojos (C2_MAX_FEE_MOJOS {cap}, fee coin {} mojos)",
                    fee_coin.amount
                )));
            }
            fee = required;
        }
        release_on_exit(newly_reserved).await;
        Ok(Prepared::Blocked("the replacement's fee did not settle after three rounds".to_string()))
    }

    /// Rebuilds the in-flight spend's records (plus any newly queued ones,
    /// task instruction: "merging any newly queued records into the
    /// rebuilt spend (<=`MAX_RECORDS_PER_SPEND`)") against the *same* singleton coin, with a
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

        // The bundle being rebuilt is dropped in its entirety -- release
        // whatever fee coin it held (task fix, `docs/research/
        // chain_service.md` §15) *before* `attach_fee_coin` below reserves
        // a new one, whether this rebuild was triggered by an underpriced
        // fee or by the fee coin having been spent elsewhere. Harmless to
        // release a coin that's still perfectly spendable (the fee-rejection
        // case): `attach_fee_coin` is free to pick that exact same coin
        // again if nothing else has reserved it meanwhile.
        if let Some(old_fee_coin) = fee_coin_of(&in_flight.bundle, in_flight.spent_coin) {
            self.fee_allocator.release(old_fee_coin.coin_id()).await;
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
        // A failed or zero estimate is the fallback rate (`fee_estimate.rs`, §17.2), not 0: the old
        // "falling back to 0" followed by `.max(1)` below is how a rebuild after a fee rejection
        // could come out at a fee of one mojo.
        let chain_estimate = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost.total_cost, shorter_target, u64::MAX)
            .await
            .fee_mojos;
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
            // The bundle this one replaces stays on record: the chain, not this process, decides
            // which version confirms (`QueueFile::resolve_consumed_in_flight`).
            superseded: {
                let mut versions = in_flight.superseded.clone();
                versions.push(in_flight.as_superseded("rebuilt after a rejection", now));
                versions
            },
            replace_blocked: None,
        });
        // Durability first, same invariant as `start_new_batch`.
        self.save_queue(queue).context("persisting rebuilt in-flight spend before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for rebuilt batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        self.save_queue(queue).context("persisting rebuilt push_tx outcome")?;

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
            .fee_allocator
            .reserve(&self.chain, &master_sk, fee_mojos)
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

        // The first attempt: the estimate; when `get_fee_estimate` fails or answers 0 the fallback
        // (the last successful estimate's rate, else the configured floor) -- never a fee of 0
        // because an estimate was unavailable (`fee_estimate.rs`, §17.2: a fee-0 bundle sat in a
        // busy mempool for ten minutes on 2026-10-02). Capped at `C2_MAX_FEE_MOJOS` just like every
        // rebuild: an estimate from a briefly insane mempool must not be able to spend more than the
        // operator allowed.
        let fee_mojos = self
            .fee_allocator
            .fees()
            .estimate(&self.chain, draft_cost.total_cost, self.fee_target_time_secs, self.max_fee_mojos)
            .await
            .fee_mojos;

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

        let (coin_spends, signature) = match self.attach_fee_coin(draft.coin_spends, draft.signature, fee_mojos).await {
            Ok(attached) => attached,
            // The only wallet coin big enough is the input of the fee-coin split that is waiting for
            // confirmation (`fee_reserve.rs`): not a failure, a wait of a few blocks. The drained
            // batch goes back to `pending`, unsaved -- nothing on disk changed.
            Err(e) if e.downcast_ref::<FeeCoinsBusy>().is_some() => {
                tracing::info!("not starting a batch yet: the fee-coin split is holding the wallet's only fee coin");
                queue.pending.splice(0..0, batch);
                return Ok(TickOutcome::WaitingForConfirmation);
            }
            Err(e) => return Err(e),
        };
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
            superseded: Vec::new(),
            replace_blocked: None,
        });
        // Durability first (task instruction: "queue records durably ...
        // while a spend is in flight"): this atomic write is what removes
        // the batch from `pending`. A crash right after this line and
        // before `push_tx` below just means the next tick's retry path
        // (`advance_in_flight`) broadcasts this exact persisted bundle.
        self.save_queue(queue).context("persisting new in-flight spend before broadcasting")?;

        let outcome = self.chain.push_tx(bundle).await.context("push_tx for new batch")?;
        if let Some(in_flight) = queue.in_flight.as_mut() {
            in_flight.last_push_accepted = outcome.accepted;
            in_flight.last_push_error = outcome.error.clone();
        }
        self.save_queue(queue).context("persisting push_tx outcome")?;

        Ok(TickOutcome::Broadcast {
            records: batch_len,
            accepted: outcome.accepted,
        })
    }
}
