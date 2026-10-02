//! The durable record queue (task instruction: "queue records durably (a
//! file in the data dir) while a spend is in flight"): `<data_dir>/
//! <name>.oracle_queue.json`, written atomically (`atomic_file.rs`).
//! `pending` holds records not yet included in any broadcast spend;
//! `in_flight` (at most one at a time -- the "never double-spend the
//! singleton" invariant) holds the records **and the fully-signed
//! `SpendBundle`** of the currently-broadcast-but-unconfirmed spend, so a
//! process restart can replay `push_tx` on the exact same bytes instead of
//! re-deriving anything (no dependency on the operator's live UTXO set
//! being unchanged across the restart).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use anyhow::Result;
use c2_cli::state::ProofDto;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};
use crate::fee_allocator::fee_coin_of;

use super::lineage::{LineageWalkOutcome, WalkedGeneration};
use super::types::OracleRecord;

/// One record's on-chain confirmation (`GET /v1/oracle/records/{seq}`,
/// FIXED INTERFACE (b)) -- `height`/`coin_id` of the singleton generation
/// whose spend announced this exact `seq`. Recorded per-record (not just as
/// a single running high-water mark) precisely because a LATE/backfill
/// record can confirm inside a batch alongside much-higher new seqs, so "the
/// last record in the confirming batch" is no longer a reliable stand-in for
/// "every seq up to here is confirmed" once out-of-order publication is
/// possible (see `docs/research/chain_service.md` §10.3).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConfirmedRecordInfo {
    pub height: Option<u32>,
    /// `0x`-hex `Coin::coin_id()` of the next singleton generation created by
    /// the confirming spend (the same coin `GET /v1/oracle/status` would
    /// track next).
    pub coin_id: String,
}

/// `GET /v1/oracle/records/{seq}`'s `state` (FIXED INTERFACE (b)), derived
/// from the queue's actual persisted state rather than from the
/// `last_accepted_seq` watermark -- see `QueueFile::record_state`'s doc
/// comment for why the watermark alone is not a safe way to answer "is this
/// seq already accounted for".
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordState {
    Confirmed { height: Option<u32>, coin_id: String },
    /// Task fix, `docs/research/chain_service.md` §11: the chain-lineage
    /// walk found this `seq` already confirmed on chain with DIFFERENT
    /// content than what this service holds locally -- never silently
    /// dropped or republished, see `QueueFile::conflicts`.
    Conflict { chain_height: Option<u32>, chain_coin_id: String },
    InFlight,
    Queued,
    /// Neither pending, in flight, nor confirmed -- either never submitted,
    /// or (before this fix) silently lost. `enqueue` treats this the same
    /// way regardless of which: accept it (task instruction, FIXED
    /// INTERFACE (b): "a record whose seq is below the watermark but is NOT
    /// known as confirmed/queued/in_flight is accepted as a LATE record").
    Unknown,
}

/// One locally-known record (pending or in-flight) whose content disagrees
/// with what the chain-lineage walk found already confirmed for the same
/// `seq` (task fix, `docs/research/chain_service.md` §11: "with DIFFERENT
/// content -> do not publish, mark conflict, surface in status -- never
/// silently"). Never auto-resolved by this service -- an operator has to
/// look at both versions and decide; the whole point of this type existing
/// is that the two disagree and picking one automatically would be exactly
/// the kind of silent data loss this fix exists to prevent.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ConflictInfo {
    /// What this service had queued (or in flight) for this `seq`.
    pub local: OracleRecord,
    /// What the chain-lineage walk actually found confirmed for this `seq`.
    pub chain: OracleRecord,
    pub chain_height: Option<u32>,
    pub chain_coin_id: String,
}

/// An earlier version of the in-flight bundle: a bundle for the *same singleton coin* that the
/// publisher replaced (in the mempool, `Publisher::replace_in_mempool`) or rebuilt (after a
/// rejection, `Publisher::rebuild_in_flight`) with a later one. Only one bundle can spend a coin,
/// so at most one version ever confirms -- but which one is for the chain to say, and an earlier
/// version can still win (another node's mempool may hold it; the replacement may have been
/// rejected). The publisher keeps what it needs to account for either outcome: the seqs the
/// version carried (which version won is read off the chain's announcements), its fee (for the
/// log line), and the fee coin it spent (so a coin reserved for a version that lost is released).
/// `docs/research/chain_service.md` §17.4.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SupersededBundle {
    /// `SpendBundle::name()` of that version (the node's own id for it).
    pub bundle_name: Bytes32,
    pub fee_mojos: u64,
    /// The `seq` of every record that version announces, ascending.
    pub record_seqs: Vec<u64>,
    /// Id of the fee coin that version spent, if it had one.
    #[serde(default)]
    pub fee_coin: Option<Bytes32>,
    pub superseded_at_unix: i64,
    /// `"replaced in the mempool"` or `"rebuilt after a rejection"`.
    pub reason: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InFlightSpend {
    /// Records this spend announces, ascending by `seq` (see
    /// `QueueFile::start_batch`).
    pub records: Vec<OracleRecord>,
    /// The oracle singleton coin this spend consumes (== the
    /// `OracleState::current_coin` this spend was built against).
    pub spent_coin: Coin,
    pub next_coin: Coin,
    pub next_proof: ProofDto,
    /// Fully signed, ready to `push_tx` as-is -- replayed verbatim on
    /// retry/restart, never rebuilt (rebuilding could pick a different fee
    /// coin from the live UTXO set and would need re-signing).
    pub bundle: SpendBundle,
    /// Locally computed identifier for `GET /v1/oracle/status`'s
    /// `inFlightBundleId` (**предложение**: `sha256` of the aggregated
    /// signature's bytes -- `chia-sdk-coinset`'s `PushTxResponse` does not
    /// expose coinset's own `tx_id` field, see `chain_client.rs`; this is
    /// not claimed to be Chia's canonical spend-bundle name, only a stable
    /// per-attempt label for the status page/operator, see
    /// `docs/research/chain_service.md` §5).
    pub bundle_id: String,
    pub fee_mojos: u64,
    pub broadcast_at_unix: i64,
    pub last_attempt_at_unix: i64,
    pub attempts: u32,
    pub last_push_accepted: bool,
    pub last_push_error: Option<String>,
    /// Earlier versions of this bundle, oldest first -- empty for a bundle that was never replaced
    /// or rebuilt. `#[serde(default)]`: a queue file written before this field existed has none.
    #[serde(default)]
    pub superseded: Vec<SupersededBundle>,
    /// Why this bundle, which sits in the mempool past `C2_MEMPOOL_REPLACE_AFTER_SECS`, could not
    /// be replaced (the fee cap leaves no valid replacement, the fee coin is too small, the node
    /// refused the replacement, ...). Set and cleared by the publisher only; `Some` is what
    /// `GET /v1/oracle/status`'s `stuckReason` reports while the bundle keeps waiting.
    /// `#[serde(default)]` as above.
    #[serde(default)]
    pub replace_blocked: Option<String>,
}

impl InFlightSpend {
    /// The `seq` of every record this bundle announces, ascending.
    pub fn record_seqs(&self) -> Vec<u64> {
        let mut seqs: Vec<u64> = self.records.iter().map(|r| r.seq).collect();
        seqs.sort_unstable();
        seqs
    }

    /// This version as a `SupersededBundle`, for the bundle that replaces it to carry.
    pub fn as_superseded(&self, reason: &str, now_unix: i64) -> SupersededBundle {
        SupersededBundle {
            bundle_name: self.bundle.name(),
            fee_mojos: self.fee_mojos,
            record_seqs: self.record_seqs(),
            fee_coin: fee_coin_of(&self.bundle, self.spent_coin).map(|coin| coin.coin_id()),
            superseded_at_unix: now_unix,
            reason: reason.to_string(),
        }
    }

    /// Ids of the fee coins this version and every earlier one spent -- all of them are released
    /// once the singleton coin is consumed, whichever version that was (releasing is idempotent).
    pub fn every_fee_coin(&self) -> Vec<Bytes32> {
        let mut coins: Vec<Bytes32> = fee_coin_of(&self.bundle, self.spent_coin).map(|c| c.coin_id()).into_iter().collect();
        coins.extend(self.superseded.iter().filter_map(|version| version.fee_coin));
        coins.sort_unstable();
        coins.dedup();
        coins
    }

    /// The fee of the version that the chain says confirmed: the one whose record set is exactly
    /// what the spend announced. `None` when no version matches (a foreign spend, or the spend
    /// carried a subset that no version had) and when versions with different fees carry exactly
    /// the same records -- the announcements cannot tell them apart, and the log says nothing
    /// rather than the fee of the wrong one.
    pub fn fee_of_version_announcing(&self, announced: &[u64]) -> Option<u64> {
        let mut wanted = announced.to_vec();
        wanted.sort_unstable();
        wanted.dedup();
        let mut fees: Vec<u64> = self
            .superseded
            .iter()
            .filter(|version| version.record_seqs == wanted)
            .map(|version| version.fee_mojos)
            .collect();
        if self.record_seqs() == wanted {
            fees.push(self.fee_mojos);
        }
        fees.sort_unstable();
        fees.dedup();
        match fees.as_slice() {
            [fee] => Some(*fee),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct QueueFile {
    pub pending: Vec<OracleRecord>,
    pub in_flight: Option<InFlightSpend>,
    /// Highest `seq` ever accepted into the queue -- purely informational
    /// now (see `record_state`'s doc comment for why it stopped being the
    /// basis for dedup/idempotency); still exposed for operator visibility.
    pub last_accepted_seq: Option<u64>,
    pub last_confirmed_seq: Option<u64>,
    pub last_confirmed_height: Option<u32>,
    /// Per-record confirmation info, FIXED INTERFACE (b)'s
    /// `GET /v1/oracle/records/{seq}`. `#[serde(default)]`: the real stand's
    /// existing `<name>.oracle_queue.json` predates this field.
    #[serde(default)]
    pub confirmed: BTreeMap<u64, ConfirmedRecordInfo>,
    /// Records whose chain-confirmed content disagrees with what's locally
    /// queued/in-flight for the same `seq` -- task fix, `docs/research/
    /// chain_service.md` §11. `#[serde(default)]`: no queue file predating
    /// this fix has ever had one (nothing ever checked, which is exactly
    /// this incident's root cause).
    #[serde(default)]
    pub conflicts: BTreeMap<u64, ConflictInfo>,
    /// Where the chain-lineage walk (`oracle/lineage.rs::walk_lineage`) last
    /// left off -- the singleton coin that was still unspent as of the last
    /// scan. The next scan (another periodic tick, or the next process's
    /// startup bootstrap) resumes from here instead of re-walking the whole
    /// lineage from the launcher every time (task instruction: "persist the
    /// checkpoint so later restarts only scan forward"). `#[serde(default)]`:
    /// absent until the first scan (or on any queue file predating this
    /// fix).
    #[serde(default)]
    pub chain_scan_checkpoint: Option<Coin>,
}

/// What `QueueFile::reconcile_with_chain` actually changed -- returned so
/// `Publisher` can decide whether the reconciled queue needs saving at all
/// (most periodic scans, once caught up, change nothing: just one
/// `coin_record` call confirming the tip is still unspent) and so it has
/// something to log/report (task fix: "surface... never silently" applies
/// just as much to the operator's logs as to `GET /v1/oracle/status`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ReconcileOutcome {
    /// `pending`/`in_flight` records dropped because the chain already has
    /// them, byte-identical -- exactly what this incident republished.
    pub dropped_confirmed: Vec<u64>,
    /// `pending`/`in_flight` records moved into `conflicts` because the
    /// chain has *different* content for the same `seq`.
    pub new_conflicts: Vec<u64>,
    /// The in-flight spend was fully superseded (every one of its records
    /// already confirmed identically on chain) and cleared.
    pub in_flight_cleared: bool,
    /// The persisted checkpoint moved forward (or was set for the first
    /// time).
    pub checkpoint_advanced: bool,
    pub generations_advanced: u64,
    pub decode_warnings: Vec<String>,
    /// Set by `Publisher::reconcile_queue_with_chain` (not by this method --
    /// it has no access to `OracleState`), not `queue.rs` itself: the stale
    /// `OracleState.current_coin` (spent, or simply behind) that was replaced
    /// by adopting the chain's actual tip, `None` when nothing needed
    /// adopting this round. Task fix, `docs/research/chain_service.md` §12.
    /// Carried on this type purely for logging/test visibility alongside the
    /// rest of one reconciliation pass's outcome -- it does not affect
    /// `changed()` (that's about whether the *queue file* needs re-saving;
    /// adopting a chain tip saves `operator.oracle.json`, a separate file,
    /// unconditionally and immediately, not gated on this outcome at all).
    pub adopted_stale_coin: Option<Coin>,
    /// Set when the chain walk found the spend that consumed the in-flight bundle's singleton coin
    /// (the bundle can never confirm any more -- the coin is gone) and its announcements could be
    /// read: what became of each in-flight record, and whether the spend was ours. The
    /// in-flight spend is already cleared in `QueueFile` when this is `Some`; the publisher
    /// finishes the job (oracle state, fee coin, log line).
    pub resolved_in_flight: Option<ResolvedInFlight>,
    /// The spend that consumed the in-flight bundle's singleton coin was found but could not be
    /// read completely (`WalkedGeneration::announced == None`): the in-flight spend was left
    /// alone and the scan checkpoint held back at that coin, so the next scan reads it again.
    pub in_flight_unreadable: bool,
}

/// What the chain says became of the in-flight bundle, once the walk found the spend that consumed
/// its singleton coin. The records of the bundle -- whichever version of it (`SupersededBundle`)
/// was the one the chain took, or none of them -- are accounted for from the chain's own
/// announcements: a record the spend announced is confirmed, a record the spend did not announce
/// goes back to `pending` and a later batch publishes it. Never "all records confirmed because the
/// singleton advanced" (`docs/research/chain_service.md` §17.4).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedInFlight {
    /// In-flight records the chain confirms, identical, now in `confirmed`.
    pub confirmed: Vec<u64>,
    /// In-flight records the consuming spend did not announce and the chain has nowhere else:
    /// back in `pending`.
    pub requeued: Vec<u64>,
    /// In-flight records the chain has with different content: now in `conflicts`.
    pub conflicts: Vec<u64>,
    /// Every seq the consuming spend announced.
    pub announced: Vec<u64>,
    /// The spend announced at least one record and every one of them is a record this queue holds
    /// -- in the in-flight bundle or pending -- identical: it was this service's bundle (this
    /// version, an earlier one, or a replacement that was refused). `false`: someone else spent
    /// the singleton.
    pub ours: bool,
    pub height: Option<u32>,
    /// The singleton coin the spend recreated.
    pub next_coin: Coin,
}

impl ReconcileOutcome {
    /// Whether anything here actually needs persisting.
    pub fn changed(&self) -> bool {
        !self.dropped_confirmed.is_empty()
            || !self.new_conflicts.is_empty()
            || self.in_flight_cleared
            || self.checkpoint_advanced
    }
}

impl QueueFile {
    pub fn path(data_dir: &Path, name: &str) -> PathBuf {
        data_dir.join(format!("{name}.oracle_queue.json"))
    }

    pub fn load(data_dir: &Path, name: &str) -> Result<Self> {
        read_json_or_default(&Self::path(data_dir, name), Self::default)
    }

    pub fn save_atomic(&self, data_dir: &Path, name: &str) -> Result<()> {
        write_json_atomic(&Self::path(data_dir, name), self)
    }

    /// `GET /v1/oracle/records/{seq}`'s state, and `enqueue`'s idempotency
    /// check -- both ask the same question ("what do we actually know about
    /// this seq") of the same three real, persisted sources of truth
    /// (`confirmed`, `in_flight`, `pending`), never of the `last_accepted_seq`
    /// watermark. Root-cause fix (`docs/research/chain_service.md` §10): the
    /// old code treated "`seq <= last_accepted_seq`" as proof a record was
    /// already durably queued -- but a concurrent writer could advance that
    /// same watermark past a record whose actual `pending`/`in_flight` entry
    /// got clobbered by a lost update (see `Publisher::queue_lock`'s doc
    /// comment for the concurrency half of that fix), silently and
    /// permanently swallowing every future resubmission of that exact seq.
    /// Checking real state instead means a seq below the watermark that
    /// truly isn't anywhere is simply `Unknown` -- indistinguishable from a
    /// genuinely new one -- and `enqueue` accepts it either way (FIXED
    /// INTERFACE (b): "every seq exactly once", late/backfill included).
    pub fn record_state(&self, seq: u64) -> RecordState {
        if let Some(info) = self.confirmed.get(&seq) {
            return RecordState::Confirmed { height: info.height, coin_id: info.coin_id.clone() };
        }
        if let Some(conflict) = self.conflicts.get(&seq) {
            return RecordState::Conflict {
                chain_height: conflict.chain_height,
                chain_coin_id: conflict.chain_coin_id.clone(),
            };
        }
        if let Some(in_flight) = &self.in_flight
            && in_flight.records.iter().any(|r| r.seq == seq)
        {
            return RecordState::InFlight;
        }
        if self.pending.iter().any(|r| r.seq == seq) {
            return RecordState::Queued;
        }
        RecordState::Unknown
    }

    /// Accepts every record in `incoming` that isn't already known
    /// confirmed/in-flight/pending (`record_state`), in ascending `seq`
    /// order. A record whose `seq` is already accounted for is ignored
    /// idempotently (task instruction, FIXED INTERFACE (b): "not an error");
    /// otherwise it's accepted -- including a LATE record whose `seq` is
    /// below `last_accepted_seq` but was never actually recorded anywhere
    /// (backfill). Returns the `seq`s newly accepted, in the order accepted
    /// (`POST /v1/oracle/records`'s `acceptedSeqs`).
    pub fn enqueue(&mut self, mut incoming: Vec<OracleRecord>) -> Vec<u64> {
        incoming.sort_by_key(|r| r.seq);
        let mut accepted = Vec::new();
        for record in incoming {
            if !matches!(self.record_state(record.seq), RecordState::Unknown) {
                continue; // already confirmed / queued / in-flight: idempotent no-op
            }
            if record.seq > self.last_accepted_seq.unwrap_or(0) {
                self.last_accepted_seq = Some(record.seq);
            }
            accepted.push(record.seq);
            self.pending.push(record);
        }
        accepted
    }

    /// Takes up to `max` of the oldest pending records (ascending `seq`)
    /// out of `pending`, for `spend.rs::build_oracle_publish_batch`. Does
    /// **not** touch `in_flight` -- callers persist the result into
    /// `in_flight` themselves in the same atomic write that removes these
    /// records from `pending` (see `publisher.rs::tick`), so a crash
    /// in-between can't lose or duplicate a record.
    pub fn take_batch(&mut self, max: usize) -> Vec<OracleRecord> {
        self.pending.sort_by_key(|r| r.seq);
        let n = self.pending.len().min(max);
        self.pending.drain(..n).collect()
    }

    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }

    /// The highest `seq` this queue knows of in any state: the accepted
    /// watermark, the confirmed high-water mark, every key of `confirmed` and
    /// `conflicts`, and every pending and in-flight record -- 0 for an empty
    /// queue (`GET /v1/oracle/status`'s `maxKnownSeq`, record v2). Each source
    /// is needed: after a reconciliation against the chain `confirmed` can hold
    /// seqs above `last_accepted_seq`, and pending/in-flight records can be above
    /// `last_confirmed_seq`; a hand-edited or partially restored file can
    /// disagree with itself, so no one field is trusted to be the maximum.
    pub fn max_known_seq(&self) -> u64 {
        [
            self.last_accepted_seq,
            self.last_confirmed_seq,
            self.confirmed.keys().next_back().copied(),
            self.conflicts.keys().next_back().copied(),
            self.pending.iter().map(|r| r.seq).max(),
            self.in_flight.as_ref().and_then(|f| f.records.iter().map(|r| r.seq).max()),
        ]
        .into_iter()
        .flatten()
        .max()
        .unwrap_or(0)
    }

    /// Applies a chain-lineage walk's findings (`oracle/lineage.rs::
    /// walk_lineage`) to this queue -- the reconciliation half of the task
    /// fix (`docs/research/chain_service.md` §11): "pending/in_flight
    /// records whose seq is already on-chain with identical content -> drop
    /// as confirmed; with DIFFERENT content -> do not publish, mark
    /// conflict, surface in status -- never silently. Persist the
    /// checkpoint so later restarts only scan forward."
    ///
    /// `pending` is reconciled unconditionally -- this is exactly what this
    /// incident republished (a stale/race-corrupted queue file's `pending`
    /// holding records the chain already had). `in_flight` is reconciled in
    /// two ways. When the walk passed through the spend that consumed the
    /// in-flight bundle's singleton coin the bundle is finished whatever it
    /// was (our own bundle or an earlier version of it, §17.4; someone
    /// else's spend): every record is accounted for from the chain's
    /// announcements and the in-flight spend is cleared
    /// (`resolve_consumed_in_flight`). When the coin is still unspent the
    /// original defensive rule applies (`reconcile_unconsumed_in_flight`):
    /// only fully cleared when *every* one of its records is already
    /// confirmed identically on chain (nothing left to broadcast or wait
    /// for); a partial or outright conflicting match is surfaced via
    /// `conflicts` but the in-flight spend itself is left alone --
    /// unwinding an already-broadcast spend is `advance_in_flight`'s job via
    /// its own existing chain checks, not this reconciliation pass's.
    pub fn reconcile_with_chain(&mut self, walk: &LineageWalkOutcome) -> ReconcileOutcome {
        let mut outcome = ReconcileOutcome {
            generations_advanced: walk.generations_advanced,
            decode_warnings: walk.decode_warnings.clone(),
            ..Default::default()
        };

        // What this queue holds before the walk's findings are applied: whether a spend the walk
        // found is "ours" is judged against every record the queue knows, pending ones included
        // (the records a refused replacement merged and returned to `pending` are ours too).
        let queued_before: BTreeMap<u64, OracleRecord> = self.pending.iter().map(|r| (r.seq, *r)).collect();

        let stale_pending = std::mem::take(&mut self.pending);
        for record in stale_pending {
            match walk.confirmed.get(&record.seq) {
                Some(chain_record) if chain_record.record == record => {
                    self.confirmed.insert(
                        record.seq,
                        ConfirmedRecordInfo { height: chain_record.height, coin_id: chain_record.coin_id.clone() },
                    );
                    outcome.dropped_confirmed.push(record.seq);
                }
                Some(chain_record) => {
                    self.conflicts.insert(
                        record.seq,
                        ConflictInfo {
                            local: record,
                            chain: chain_record.record,
                            chain_height: chain_record.height,
                            chain_coin_id: chain_record.coin_id.clone(),
                        },
                    );
                    outcome.new_conflicts.push(record.seq);
                }
                None => self.pending.push(record),
            }
        }
        self.pending.sort_by_key(|r| r.seq);

        // The in-flight spend. Its singleton coin consumed on chain means the bundle can never
        // confirm any more, whichever version of it the chain took: resolve it from what the
        // chain says (`resolve_consumed_in_flight`). Not consumed: the defensive reconciliation
        // the first version of this code had (`reconcile_unconsumed_in_flight`).
        let mut hold_checkpoint = None;
        if let Some(in_flight) = self.in_flight.clone() {
            match walk.generation_spending(&in_flight.spent_coin) {
                Some(generation) => match &generation.announced {
                    Some(announced) => {
                        let resolved = self.resolve_consumed_in_flight(
                            &in_flight,
                            generation,
                            announced,
                            &queued_before,
                            walk,
                            &mut outcome,
                        );
                        outcome.resolved_in_flight = Some(resolved);
                    }
                    None => {
                        hold_checkpoint = Some(generation.spent_coin);
                        outcome.in_flight_unreadable = true;
                    }
                },
                None => self.reconcile_unconsumed_in_flight(&in_flight, walk, &mut outcome),
            }
        }

        if let Some((&max_seq, chain_record)) = walk.confirmed.iter().next_back()
            && max_seq > self.last_confirmed_seq.unwrap_or(0)
        {
            self.last_confirmed_seq = Some(max_seq);
            self.last_confirmed_height = chain_record.height.or(self.last_confirmed_height);
        }

        // Normally the tip. Held back at the spent coin of an in-flight generation the walk could
        // not read, so the next scan walks it again and reads it once coinset has its data.
        let new_checkpoint = hold_checkpoint.unwrap_or(walk.tip_coin);
        let checkpoint_advanced = self.chain_scan_checkpoint != Some(new_checkpoint);
        if checkpoint_advanced {
            self.chain_scan_checkpoint = Some(new_checkpoint);
        }
        outcome.checkpoint_advanced = checkpoint_advanced;

        outcome
    }

    /// The in-flight spend's singleton coin was consumed by `generation`, which announced
    /// `announced`: account for every in-flight record from the chain (see `ResolvedInFlight`) and
    /// clear the in-flight spend. Called only with a generation the walk read completely.
    fn resolve_consumed_in_flight(
        &mut self,
        in_flight: &InFlightSpend,
        generation: &WalkedGeneration,
        announced: &[u64],
        queued_before: &BTreeMap<u64, OracleRecord>,
        walk: &LineageWalkOutcome,
        outcome: &mut ReconcileOutcome,
    ) -> ResolvedInFlight {
        // "Ours": the spend announced something, and everything it announced is a record this queue
        // holds -- in the bundle, or pending (a replacement that was refused and put its merged
        // records back) -- identical to what the chain has.
        let ours = !announced.is_empty()
            && announced.iter().all(|seq| {
                let held = in_flight.records.iter().find(|r| r.seq == *seq).or_else(|| queued_before.get(seq));
                held.zip(walk.confirmed.get(seq)).is_some_and(|(record, chain_record)| chain_record.record == *record)
            });

        // The in-flight spend is gone before its records are placed, so `record_state` below
        // never sees them as "in flight".
        self.in_flight = None;
        outcome.in_flight_cleared = true;

        let mut confirmed = Vec::new();
        let mut requeued = Vec::new();
        let mut conflicts = Vec::new();
        for record in &in_flight.records {
            match walk.confirmed.get(&record.seq) {
                Some(chain_record) if chain_record.record == *record => {
                    self.confirmed.insert(
                        record.seq,
                        ConfirmedRecordInfo { height: chain_record.height, coin_id: chain_record.coin_id.clone() },
                    );
                    confirmed.push(record.seq);
                }
                Some(chain_record) => {
                    self.conflicts.insert(
                        record.seq,
                        ConflictInfo {
                            local: *record,
                            chain: chain_record.record,
                            chain_height: chain_record.height,
                            chain_coin_id: chain_record.coin_id.clone(),
                        },
                    );
                    outcome.new_conflicts.push(record.seq);
                    conflicts.push(record.seq);
                }
                None => {
                    // Not on chain: a later batch publishes it. Never twice in `pending`, never
                    // when the queue already accounts for the seq some other way.
                    if matches!(self.record_state(record.seq), RecordState::Unknown) {
                        self.pending.push(*record);
                    }
                    requeued.push(record.seq);
                }
            }
        }
        self.pending.sort_by_key(|r| r.seq);

        ResolvedInFlight {
            confirmed,
            requeued,
            conflicts,
            announced: announced.to_vec(),
            ours,
            height: generation.height,
            next_coin: generation.next_coin,
        }
    }

    /// The defensive reconciliation of an in-flight spend whose singleton coin the walk did *not*
    /// see consumed -- unchanged from before the consumed case existed: cleared only when every
    /// one of its records is already confirmed identically on chain (nothing left to broadcast or
    /// wait for); a partial or outright conflicting match is surfaced via `conflicts` but the
    /// in-flight spend itself is left alone -- unwinding an already-broadcast spend is
    /// `advance_in_flight`'s job via its own chain checks.
    fn reconcile_unconsumed_in_flight(
        &mut self,
        in_flight: &InFlightSpend,
        walk: &LineageWalkOutcome,
        outcome: &mut ReconcileOutcome,
    ) {
        let mismatched: Vec<u64> = in_flight
            .records
            .iter()
            .filter(|r| walk.confirmed.get(&r.seq).is_some_and(|cr| cr.record != **r))
            .map(|r| r.seq)
            .collect();
        let all_confirmed = !in_flight.records.is_empty()
            && in_flight.records.iter().all(|r| walk.confirmed.get(&r.seq).is_some_and(|cr| cr.record == *r));

        if all_confirmed {
            for record in &in_flight.records {
                let chain_record = &walk.confirmed[&record.seq];
                self.confirmed.insert(
                    record.seq,
                    ConfirmedRecordInfo { height: chain_record.height, coin_id: chain_record.coin_id.clone() },
                );
                outcome.dropped_confirmed.push(record.seq);
            }
            self.in_flight = None;
            outcome.in_flight_cleared = true;
        } else {
            for record in in_flight.records.iter().filter(|r| mismatched.contains(&r.seq)) {
                let chain_record = &walk.confirmed[&record.seq];
                self.conflicts.insert(
                    record.seq,
                    ConflictInfo {
                        local: *record,
                        chain: chain_record.record,
                        chain_height: chain_record.height,
                        chain_coin_id: chain_record.coin_id.clone(),
                    },
                );
                outcome.new_conflicts.push(record.seq);
            }
        }
    }
}
