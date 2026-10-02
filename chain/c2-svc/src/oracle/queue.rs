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
    /// The record that was confirmed -- what the service answers to "is this seq yours?"
    /// (`QueueFile::held_record`: `GET /v1/oracle/records/{seq}`'s content fields, and the
    /// `conflictSeqs` of `POST /v1/oracle/records`, `docs/research/chain_service.md` §18.2).
    /// `None` for an entry confirmed before the content was kept: a queue file written by an earlier
    /// version has no such field (`#[serde(default)]`), and the entry keeps answering as it did, with
    /// no content. Written out only when there is one, so a file that never had it reads and writes back
    /// the same.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub record: Option<OracleRecord>,
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

/// What `QueueFile::enqueue` did with a request's records.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EnqueueOutcome {
    /// The `seq`s newly accepted into `pending`, in the order accepted (`acceptedSeqs`).
    pub accepted: Vec<u64>,
    /// The `seq`s the queue already holds with different content than the request's record for them,
    /// ascending, each once (`conflictSeqs`) -- not queued.
    pub conflicts: Vec<u64>,
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

    /// The record this queue holds under `seq`, when it still has its content -- the record `GET
    /// /v1/oracle/records/{seq}` describes and the one `enqueue` compares an incoming record with
    /// (`docs/research/chain_service.md` §18.2). Same precedence as `record_state`: confirmed, then
    /// conflicting -- the record the CHAIN has, which is what `height`/`coinId` of that state describe
    /// too --, then in flight, then pending. `None` when the seq is unknown, and when it is confirmed
    /// without content: an entry from before the content was kept (`ConfirmedRecordInfo::record`).
    pub fn held_record(&self, seq: u64) -> Option<OracleRecord> {
        if let Some(info) = self.confirmed.get(&seq) {
            return info.record;
        }
        if let Some(conflict) = self.conflicts.get(&seq) {
            return Some(conflict.chain);
        }
        if let Some(in_flight) = &self.in_flight
            && let Some(record) = in_flight.records.iter().find(|r| r.seq == seq)
        {
            return Some(*record);
        }
        self.pending.iter().find(|r| r.seq == seq).copied()
    }

    /// Accepts every record in `incoming` that isn't already known
    /// confirmed/in-flight/pending (`record_state`), in ascending `seq`
    /// order. A record whose `seq` is already accounted for is ignored
    /// idempotently (task instruction, FIXED INTERFACE (b): "not an error");
    /// otherwise it's accepted -- including a LATE record whose `seq` is
    /// below `last_accepted_seq` but was never actually recorded anywhere
    /// (backfill). Returns the `seq`s newly accepted, in the order accepted
    /// (`POST /v1/oracle/records`'s `acceptedSeqs`).
    ///
    /// What is accepted is exactly that; what is *reported* is more
    /// (`docs/research/chain_service.md` §18.2): an ignored record whose `seq` is held with DIFFERENT
    /// content -- another server's record, or one the same server issued before it learned the floor --
    /// is not a repeat but a collision, and its `seq` is in `EnqueueOutcome::conflicts`
    /// (`conflictSeqs`). A seq held with the same content stays the silent no-op it was, and so does a
    /// confirmed entry whose content was not kept (`held_record` is `None`): there is nothing to compare
    /// with. A request that carries one `seq` twice with different content is compared in order, like two
    /// requests: the first is accepted, the second collides with it.
    pub fn enqueue(&mut self, mut incoming: Vec<OracleRecord>) -> EnqueueOutcome {
        incoming.sort_by_key(|r| r.seq);
        let mut outcome = EnqueueOutcome::default();
        for record in incoming {
            if !matches!(self.record_state(record.seq), RecordState::Unknown) {
                // already confirmed / queued / in-flight / conflicting: idempotent no-op -- unless it is
                // a different record
                if self.held_record(record.seq).is_some_and(|held| held != record)
                    && outcome.conflicts.last() != Some(&record.seq)
                {
                    outcome.conflicts.push(record.seq);
                }
                continue;
            }
            if record.seq > self.last_accepted_seq.unwrap_or(0) {
                self.last_accepted_seq = Some(record.seq);
            }
            outcome.accepted.push(record.seq);
            self.pending.push(record);
        }
        outcome
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
                        ConfirmedRecordInfo {
                            height: chain_record.height,
                            coin_id: chain_record.coin_id.clone(),
                            record: Some(record),
                        },
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
                        ConfirmedRecordInfo {
                            height: chain_record.height,
                            coin_id: chain_record.coin_id.clone(),
                            record: Some(*record),
                        },
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
                    ConfirmedRecordInfo {
                        height: chain_record.height,
                        coin_id: chain_record.coin_id.clone(),
                        record: Some(*record),
                    },
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oracle::lineage::ChainRecord;
    use crate::oracle::types::Source;

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

    /// The six ways a record under the same `seq` can differ in content.
    fn variants_of(record: OracleRecord) -> Vec<(&'static str, OracleRecord)> {
        vec![
            ("ts", OracleRecord { ts: record.ts + 1, ..record }),
            ("priceCents", OracleRecord { price_cents: record.price_cents + 1, ..record }),
            ("resultsHash", OracleRecord { results_hash: Bytes32::from([0xee; 32]), ..record }),
            ("source", OracleRecord { source_flag: 1 - record.source_flag, ..record }),
            ("sourceTs", OracleRecord { source_ts: record.source_ts + 1, ..record }),
            ("asset", OracleRecord { asset: record.asset + 3, ..record }),
        ]
    }

    fn in_flight_of(records: &[OracleRecord]) -> InFlightSpend {
        InFlightSpend {
            records: records.to_vec(),
            spent_coin: Coin::new(Bytes32::from([3u8; 32]), Bytes32::from([4u8; 32]), 1),
            next_coin: Coin::new(Bytes32::from([5u8; 32]), Bytes32::from([4u8; 32]), 1),
            next_proof: ProofDto::Eve { parent_parent_coin_info: Bytes32::from([6u8; 32]), parent_amount: 1 },
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

    fn confirmed_with(record: Option<OracleRecord>) -> ConfirmedRecordInfo {
        ConfirmedRecordInfo { height: Some(4_765_102), coin_id: "0x01".into(), record }
    }

    /// A record held in `pending`: the same record again is the silent no-op of a resend; a record that differs
    /// in ANY field is a collision -- reported, and still not queued.
    #[test]
    fn a_different_record_under_a_pending_seq_is_a_conflict_and_the_same_one_is_not() {
        let mut queue = QueueFile::default();
        assert_eq!(queue.enqueue(vec![record(7)]), EnqueueOutcome { accepted: vec![7], conflicts: vec![] });

        assert_eq!(queue.enqueue(vec![record(7)]), EnqueueOutcome::default(), "a resend is silent");
        for (field, different) in variants_of(record(7)) {
            let outcome = queue.enqueue(vec![different]);
            assert_eq!(outcome, EnqueueOutcome { accepted: vec![], conflicts: vec![7] }, "{field} differs");
        }
        assert_eq!(queue.pending, vec![record(7)], "what is held is untouched by every one of them");
    }

    /// The same for a record in flight.
    #[test]
    fn a_different_record_under_an_in_flight_seq_is_a_conflict() {
        let mut queue = QueueFile { in_flight: Some(in_flight_of(&[record(1), record(2)])), ..QueueFile::default() };
        assert_eq!(queue.enqueue(vec![record(1), record(2)]), EnqueueOutcome::default());
        for (field, different) in variants_of(record(2)) {
            assert_eq!(queue.enqueue(vec![different]).conflicts, vec![2], "{field} differs");
        }
        assert!(queue.pending.is_empty());
    }

    /// A confirmed record keeps its content (from this version on): the same record is silent, a different one a
    /// conflict. An entry whose content was not kept -- every entry of a queue file written before -- cannot be
    /// compared with anything: whatever comes is the silent no-op it always was.
    #[test]
    fn a_confirmed_seq_conflicts_only_when_its_content_is_known() {
        let mut queue = QueueFile::default();
        queue.confirmed.insert(1, confirmed_with(Some(record(1))));
        queue.confirmed.insert(2, confirmed_with(None));

        assert_eq!(queue.enqueue(vec![record(1), record(2)]), EnqueueOutcome::default());
        for (field, different) in variants_of(record(1)) {
            assert_eq!(queue.enqueue(vec![different]).conflicts, vec![1], "{field} differs");
        }
        for (field, different) in variants_of(record(2)) {
            assert_eq!(queue.enqueue(vec![different]), EnqueueOutcome::default(), "{field}: no content to compare with");
        }
        assert!(queue.pending.is_empty());
    }

    /// A seq the chain walk found confirmed with other content than the service had queued (`conflicts`): the record
    /// that counts is the chain's. The service's own record for it, sent again, is a collision -- it will never be
    /// published --; the chain's, sent by whoever owns it, is a repeat.
    #[test]
    fn a_conflicting_seq_is_compared_with_the_record_the_chain_has() {
        let (local, chain) = (record(5), OracleRecord { price_cents: 1, ..record(5) });
        let mut queue = QueueFile::default();
        queue.conflicts.insert(
            5,
            ConflictInfo { local, chain, chain_height: Some(10), chain_coin_id: "0x05".into() },
        );
        assert_eq!(queue.held_record(5), Some(chain));
        assert_eq!(queue.enqueue(vec![chain]), EnqueueOutcome::default(), "the chain's own record is a repeat");
        assert_eq!(queue.enqueue(vec![local]).conflicts, vec![5], "the record that lost to the chain's is a collision");
    }

    /// What is accepted does not change: a seq the queue knows nothing of is accepted (a late record below the
    /// watermark included), whatever else the request carries; and the conflicts come back ascending, once each.
    #[test]
    fn a_request_mixes_new_repeated_and_colliding_records_and_a_seq_twice_is_compared_in_order() {
        let mut queue = QueueFile { last_accepted_seq: Some(10), ..QueueFile::default() };
        queue.pending = vec![record(4), record(9)];
        queue.confirmed.insert(6, confirmed_with(Some(record(6))));

        let colliding_9 = OracleRecord { ts: 5, ..record(9) };
        let colliding_6 = OracleRecord { asset: 2, ..record(6) };
        let outcome = queue.enqueue(vec![
            colliding_9,
            record(2), // late: below the watermark, unknown everywhere
            record(4), // a repeat
            colliding_6,
            colliding_6, // the same collision twice: reported once
            record(12),
            record(12), // a repeat of what this very request just accepted
            OracleRecord { price_cents: 9, ..record(12) }, // a different record under it: collides with the first
        ]);
        assert_eq!(outcome.accepted, vec![2, 12]);
        assert_eq!(outcome.conflicts, vec![6, 9, 12]);
        assert_eq!(queue.last_accepted_seq, Some(12));
        assert_eq!(queue.pending, vec![record(4), record(9), record(2), record(12)]);
    }

    /// `held_record` answers in the order `record_state` does.
    #[test]
    fn held_record_follows_the_precedence_of_record_state() {
        let a = record(1);
        let (b, c, d) = (OracleRecord { ts: 2, ..a }, OracleRecord { ts: 3, ..a }, OracleRecord { ts: 4, ..a });
        let mut queue = QueueFile::default();
        assert_eq!(queue.held_record(1), None, "unknown");
        queue.pending.push(a);
        assert_eq!(queue.held_record(1), Some(a));
        queue.in_flight = Some(in_flight_of(&[b]));
        assert_eq!(queue.held_record(1), Some(b), "in flight before pending");
        queue.conflicts.insert(1, ConflictInfo { local: b, chain: c, chain_height: None, chain_coin_id: "0x".into() });
        assert_eq!(queue.held_record(1), Some(c), "the chain's record of a conflict before the in-flight one");
        queue.confirmed.insert(1, confirmed_with(Some(d)));
        assert_eq!(queue.held_record(1), Some(d), "confirmed before everything");
        queue.confirmed.insert(1, confirmed_with(None));
        assert_eq!(queue.held_record(1), None, "confirmed without content: nothing, not the record of a lower state");
    }

    fn chain_record(record: OracleRecord, height: u32) -> (u64, ChainRecord) {
        (record.seq, ChainRecord { height: Some(height), coin_id: format!("0x{:02x}", record.seq), record })
    }

    fn walk_over(records: &[(OracleRecord, u32)], generations: Vec<WalkedGeneration>) -> LineageWalkOutcome {
        LineageWalkOutcome {
            confirmed: records.iter().map(|(record, height)| chain_record(*record, *height)).collect(),
            tip_coin: Coin::new(Bytes32::from([9u8; 32]), Bytes32::from([4u8; 32]), 1),
            generations_advanced: generations.len() as u64,
            generations,
            decode_warnings: Vec::new(),
        }
    }

    /// Every way the chain reconciliation confirms a record writes the record down with its entry: a stale
    /// pending record the chain already has, the records of an in-flight spend whose singleton coin the chain
    /// consumed, and the records of an in-flight spend that the chain has all of identically.
    #[test]
    fn reconciliation_keeps_the_content_of_every_record_it_confirms() {
        // a pending record the chain already has
        let mut queue = QueueFile { pending: vec![record(1)], ..QueueFile::default() };
        queue.reconcile_with_chain(&walk_over(&[(record(1), 100)], vec![]));
        assert_eq!(
            queue.confirmed[&1],
            ConfirmedRecordInfo { height: Some(100), coin_id: "0x01".into(), record: Some(record(1)) }
        );
        assert!(queue.pending.is_empty());

        // an in-flight spend whose singleton coin the walk saw consumed, announcing seq 2 and 3
        let in_flight = in_flight_of(&[record(2), record(3)]);
        let consumed = WalkedGeneration {
            spent_coin: in_flight.spent_coin,
            next_coin: in_flight.next_coin,
            height: Some(101),
            announced: Some(vec![2, 3]),
        };
        let mut queue = QueueFile { in_flight: Some(in_flight), ..QueueFile::default() };
        queue.reconcile_with_chain(&walk_over(&[(record(2), 101), (record(3), 101)], vec![consumed]));
        assert!(queue.in_flight.is_none());
        for seq in [2, 3] {
            assert_eq!(queue.confirmed[&seq].record, Some(record(seq)), "seq {seq}");
        }

        // an in-flight spend whose coin the walk did not see consumed, but whose records the chain all has
        let mut queue = QueueFile { in_flight: Some(in_flight_of(&[record(4), record(5)])), ..QueueFile::default() };
        queue.reconcile_with_chain(&walk_over(&[(record(4), 102), (record(5), 102)], vec![]));
        assert!(queue.in_flight.is_none());
        for seq in [4, 5] {
            assert_eq!(queue.confirmed[&seq].record, Some(record(seq)), "seq {seq}");
        }
    }

    /// The queue file format: a confirmed entry written before the content was kept has no `record` and loads
    /// as `None`; an entry without content is written back without the key (so such a file reads and writes
    /// back the same); one with content round-trips.
    #[test]
    fn a_confirmed_entry_from_before_the_content_was_kept_loads_and_is_written_back_unchanged() {
        let old: ConfirmedRecordInfo =
            serde_json::from_str(r#"{"height":4765102,"coin_id":"0xabc"}"#).expect("an entry of an old queue file");
        assert_eq!(old, ConfirmedRecordInfo { height: Some(4_765_102), coin_id: "0xabc".into(), record: None });
        let written = serde_json::to_value(&old).unwrap();
        assert_eq!(written, serde_json::json!({"height": 4765102, "coin_id": "0xabc"}), "no `record: null` noise");

        let with_content = confirmed_with(Some(OracleRecord { asset: 3, ..record(205) }));
        let json = serde_json::to_string(&with_content).unwrap();
        assert!(json.contains("\"record\""), "{json}");
        assert_eq!(serde_json::from_str::<ConfirmedRecordInfo>(&json).unwrap(), with_content);
    }
}
