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
//!
//! `confirmed` holds an entry per confirmed record, but not for ever: once an entry has been there
//! for `C2_ORACLE_RETAIN_CONFIRMED_SECS` `apply_retention` drops it and notes its seq in `pruned`, a
//! set of ranges, so the file stays about a retention long instead of growing by 4 MB a day. A
//! dropped seq still answers `confirmed` and is never accepted again -- whatever record comes for it:
//! its content is gone, so a different record is not told from a repeat (the trade-off, and why a build
//! older than the retention must not read a pruned file, are written down in `chain/README.md`).
//! `QueueCache` keeps the parsed file between calls, so that a tick, a `POST` or a `GET` that finds it
//! unchanged does not parse it.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError};

use anyhow::Result;
use c2_cli::state::ProofDto;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use serde::{Deserialize, Serialize};

use crate::atomic_file::{FileStamp, file_stamp, read_json_or_default, write_json_atomic};
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
    /// When this service put the entry here, unix seconds -- the time the retention counts from
    /// (`QueueFile::apply_retention`: an entry is dropped once it has been here for
    /// `C2_ORACLE_RETAIN_CONFIRMED_SECS`). `None` for an entry of a file written before the time was
    /// kept: the first retention pass gives it the time of that pass, so an old entry is kept for a
    /// whole retention from the upgrade and never for less than it was promised. Written out only
    /// when there is one, like `record`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub confirmed_at_unix: Option<i64>,
}

/// `GET /v1/oracle/records/{seq}`'s `state` (FIXED INTERFACE (b)), derived
/// from the queue's actual persisted state rather than from the
/// `last_accepted_seq` watermark -- see `QueueFile::record_state`'s doc
/// comment for why the watermark alone is not a safe way to answer "is this
/// seq already accounted for".
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RecordState {
    /// On chain, identical to what this service holds. `height` and `coin_id` of the confirming
    /// spend are `None` for a seq whose entry was dropped by the retention (`QueueFile::pruned`):
    /// the service still knows it is confirmed, no longer by which spend.
    Confirmed { height: Option<u32>, coin_id: Option<String> },
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
    /// The `seq`s whose `confirmed` entry the retention has dropped (`apply_retention`), as inclusive
    /// ranges `first -> last`, disjoint and never adjacent (a range that touches another is one
    /// range): `seq`s are issued as one ordered stream, so a service that has confirmed a million
    /// records holds a handful of ranges. What a dropped entry knew -- the spend that confirmed it, the
    /// record -- is gone; what stays is that the seq is confirmed, and that is what the server needs
    /// for as long as it may ask: its reconciliation asks `GET /v1/oracle/records/{seq}` about every
    /// row it has not seen confirmed, however old, and answers `unknown` with a re-POST, which
    /// `enqueue` would take for a late record and publish again. Written out only when there is a
    /// range, so a file that never pruned reads and writes back the same.
    #[serde(default, skip_serializing_if = "BTreeMap::is_empty")]
    pub pruned: BTreeMap<u64, u64>,
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

/// What `QueueFile::apply_retention` did to the confirmed entries.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RetentionOutcome {
    /// Entries that had no confirmation time (a file written before it was kept) and were given one.
    pub stamped: usize,
    /// Entries dropped from `confirmed`; their seqs are in `QueueFile::pruned` now.
    pub pruned: usize,
    /// The lowest and the highest seq among them.
    pub pruned_seqs: Option<(u64, u64)>,
    /// Entries `confirmed` holds afterwards.
    pub kept: usize,
}

impl RetentionOutcome {
    /// Whether anything here actually needs persisting.
    pub fn changed(&self) -> bool {
        self.stamped > 0 || self.pruned > 0
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
    ///
    /// A seq whose confirmed entry the retention dropped (`pruned`) is not "truly nowhere": it is
    /// `Confirmed` without the spend that confirmed it. Were it `Unknown`, the one who asks about it
    /// after the retention -- the server's reconciliation, for a row it never saw confirmed -- would be
    /// told "lost" and send the record again, and `enqueue` would publish it a second time.
    pub fn record_state(&self, seq: u64) -> RecordState {
        if let Some(info) = self.confirmed.get(&seq) {
            return RecordState::Confirmed { height: info.height, coin_id: Some(info.coin_id.clone()) };
        }
        if self.is_pruned(seq) {
            return RecordState::Confirmed { height: None, coin_id: None };
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
    /// without content: an entry from before the content was kept (`ConfirmedRecordInfo::record`), or
    /// one the retention has dropped (`pruned`).
    pub fn held_record(&self, seq: u64) -> Option<OracleRecord> {
        if let Some(info) = self.confirmed.get(&seq) {
            return info.record;
        }
        if self.is_pruned(seq) {
            return None;
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
    /// `conflicts`, the end of the last `pruned` range, and every pending and
    /// in-flight record -- 0 for an empty queue (`GET /v1/oracle/status`'s
    /// `maxKnownSeq`, record v2). Each source is needed: after a reconciliation
    /// against the chain `confirmed` can hold seqs above `last_accepted_seq`,
    /// and pending/in-flight records can be above `last_confirmed_seq`; a
    /// hand-edited or partially restored file can disagree with itself, so no
    /// one field is trusted to be the maximum. The retention never lowers it:
    /// what it drops from `confirmed` it keeps in `pruned`.
    pub fn max_known_seq(&self) -> u64 {
        [
            self.last_accepted_seq,
            self.last_confirmed_seq,
            self.confirmed.keys().next_back().copied(),
            self.pruned.values().next_back().copied(),
            self.conflicts.keys().next_back().copied(),
            self.pending.iter().map(|r| r.seq).max(),
            self.in_flight.as_ref().and_then(|f| f.records.iter().map(|r| r.seq).max()),
        ]
        .into_iter()
        .flatten()
        .max()
        .unwrap_or(0)
    }

    /// Whether `seq`'s confirmed entry was dropped by the retention (`pruned`).
    pub fn is_pruned(&self, seq: u64) -> bool {
        self.pruned.range(..=seq).next_back().is_some_and(|(_, last)| *last >= seq)
    }

    /// Notes that `seq` is confirmed and no longer has an entry (`pruned`), merging it into the range
    /// it extends or joins -- whatever order the seqs come in, the ranges stay disjoint and never
    /// adjacent.
    fn add_pruned(&mut self, seq: u64) {
        if let Some((&first, &last)) = self.pruned.range(..=seq).next_back() {
            if last >= seq {
                return;
            }
            if last + 1 == seq {
                self.pruned.insert(first, seq);
                self.join_pruned_range_with_the_next(first);
                return;
            }
        }
        self.pruned.insert(seq, seq);
        self.join_pruned_range_with_the_next(seq);
    }

    /// The range that starts at `first` has just grown to a new end: if the next range starts right
    /// after it, they are one.
    fn join_pruned_range_with_the_next(&mut self, first: u64) {
        let last = self.pruned[&first];
        if let Some(next_first) = last.checked_add(1)
            && let Some(next_last) = self.pruned.remove(&next_first)
        {
            self.pruned.insert(first, next_last);
        }
    }

    /// The retention (`C2_ORACLE_RETAIN_CONFIRMED_SECS`): drops from `confirmed` every entry that has
    /// been there for `retain_secs` or longer as of `now_unix` -- at `now_unix - confirmed_at_unix >=
    /// retain_secs` -- and notes its seq in `pruned`, so that the seq stays confirmed for everyone who
    /// asks and is never accepted a second time (`record_state`). Only `confirmed` entries are ever
    /// dropped: a record that is pending, in flight or in `conflicts` is not confirmed and stays for
    /// as long as it is what it is, whatever the clock says. The watermarks (`last_accepted_seq`,
    /// `last_confirmed_seq`, `last_confirmed_height`) are not touched, so `maxKnownSeq` does not move.
    ///
    /// An entry without a confirmation time (a file from before it was kept) is given `now_unix` first,
    /// and so is kept for a whole retention from now. A clock that has gone backwards (`now_unix` before
    /// the time of an entry) drops nothing of it. `retain_secs == 0` turns the retention off: nothing
    /// is stamped, nothing dropped.
    pub fn apply_retention(&mut self, now_unix: i64, retain_secs: u64) -> RetentionOutcome {
        let mut outcome = RetentionOutcome { kept: self.confirmed.len(), ..RetentionOutcome::default() };
        if retain_secs == 0 {
            return outcome;
        }
        let retain = i64::try_from(retain_secs).unwrap_or(i64::MAX);
        for info in self.confirmed.values_mut().filter(|info| info.confirmed_at_unix.is_none()) {
            info.confirmed_at_unix = Some(now_unix);
            outcome.stamped += 1;
        }
        let expired: Vec<u64> = self
            .confirmed
            .iter()
            .filter(|(_, info)| info.confirmed_at_unix.is_some_and(|at| now_unix.saturating_sub(at) >= retain))
            .map(|(seq, _)| *seq)
            .collect();
        for seq in &expired {
            self.confirmed.remove(seq);
            self.add_pruned(*seq);
        }
        outcome.pruned = expired.len();
        outcome.pruned_seqs = expired.first().copied().zip(expired.last().copied());
        outcome.kept = self.confirmed.len();
        outcome
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
    ///
    /// Every entry this puts into `confirmed` is stamped `now_unix`, the time the retention counts from
    /// (`ConfirmedRecordInfo::confirmed_at_unix`, `apply_retention`).
    pub fn reconcile_with_chain(&mut self, walk: &LineageWalkOutcome, now_unix: i64) -> ReconcileOutcome {
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
                            confirmed_at_unix: Some(now_unix),
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
                    Some(_) => {
                        let resolved = self.resolve_consumed_in_flight(
                            &in_flight,
                            generation,
                            &queued_before,
                            walk,
                            now_unix,
                            &mut outcome,
                        );
                        outcome.resolved_in_flight = Some(resolved);
                    }
                    None => {
                        hold_checkpoint = Some(generation.spent_coin);
                        outcome.in_flight_unreadable = true;
                    }
                },
                None => self.reconcile_unconsumed_in_flight(&in_flight, walk, now_unix, &mut outcome),
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

    /// The in-flight spend's singleton coin was consumed by `generation`, which announced what its
    /// `announced` lists: account for every in-flight record from the chain (see `ResolvedInFlight`)
    /// and clear the in-flight spend. Called only with a generation the walk read completely.
    fn resolve_consumed_in_flight(
        &mut self,
        in_flight: &InFlightSpend,
        generation: &WalkedGeneration,
        queued_before: &BTreeMap<u64, OracleRecord>,
        walk: &LineageWalkOutcome,
        now_unix: i64,
        outcome: &mut ReconcileOutcome,
    ) -> ResolvedInFlight {
        let announced = generation.announced.as_deref().expect("a generation the walk read completely");
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
                            confirmed_at_unix: Some(now_unix),
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
        now_unix: i64,
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
                        confirmed_at_unix: Some(now_unix),
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

/// The parsed queue file as this process last saw it, so that a tick, a `POST` or a `GET` that finds
/// the file as it was left does not read and parse it again (`Publisher::load_queue`).
///
/// The file holds a confirmed entry of about 410 bytes (measured, `docs/research/chain_service.md`
/// §18.2) for every record confirmed within the retention (`C2_ORACLE_RETAIN_CONFIRMED_SECS`, a day
/// by default): some 10 000 records a day on the stand, 4 MB -- read in full and parsed at least twice
/// per tick (every 10 s), on every `GET /v1/oracle/records/{seq}` the server's reconciliation asks
/// for, on every status and health read, and on every `POST`.
///
/// What is kept is the content of the file plus its [`FileStamp`] from before the read. A load that
/// finds the same stamp hands out the same `Arc`; any other stamp -- the file written by something
/// else, replaced, removed, or not there yet -- is read afresh. The stamp is taken *before* the read
/// on purpose: a write that lands in between leaves a copy that is newer than its stamp, which costs
/// one more read the next time; the other order would pair an old copy with the new stamp for good.
/// [`QueueCache::save`] writes the file and keeps what it wrote under the stamp of the new file, so
/// this process's own writes are not read back either. A write that failed drops the copy (the file
/// may or may not have changed).
///
/// Holds no lock across the read, the parse or the write: two loads that miss at the same moment both
/// read the file, and both are right.
///
/// It also remembers when the retention (`QueueFile::apply_retention`) last ran in this process, so
/// that the pass -- a write of the whole file -- is made every few minutes and not on every tick.
#[derive(Default)]
pub struct QueueCache {
    entry: Mutex<Option<CacheEntry>>,
    reads: AtomicU64,
    /// Unix seconds of the last retention pass this process started; 0 before the first.
    retention_ran_at: AtomicI64,
}

struct CacheEntry {
    /// The queue file this copy is of: a cache belongs to one file, and asked for another it holds nothing.
    path: PathBuf,
    /// `None`: the file did not exist, and `queue` is the empty default that was handed out for it.
    stamp: Option<FileStamp>,
    queue: Arc<QueueFile>,
}

impl QueueCache {
    /// The queue file's content: the copy this cache holds while the file is the one it was read
    /// from, else the file, read and parsed (an absent file is an empty queue, as with
    /// `QueueFile::load`).
    pub fn load(&self, data_dir: &Path, name: &str) -> Result<Arc<QueueFile>> {
        let path = QueueFile::path(data_dir, name);
        let stamp = file_stamp(&path)?;
        if let Some(entry) = self.entry.lock().unwrap_or_else(PoisonError::into_inner).as_ref()
            && entry.path == path
            && entry.stamp == stamp
        {
            return Ok(Arc::clone(&entry.queue));
        }
        let queue = Arc::new(QueueFile::load(data_dir, name)?);
        self.reads.fetch_add(1, Ordering::Relaxed);
        *self.entry.lock().unwrap_or_else(PoisonError::into_inner) =
            Some(CacheEntry { path, stamp, queue: Arc::clone(&queue) });
        Ok(queue)
    }

    /// `queue` written to the queue file (atomically, `QueueFile::save_atomic`) and kept as what the
    /// file now holds.
    pub fn save(&self, queue: &QueueFile, data_dir: &Path, name: &str) -> Result<()> {
        let written = queue.save_atomic(data_dir, name);
        let kept = match &written {
            Ok(()) => {
                let path = QueueFile::path(data_dir, name);
                file_stamp(&path).ok().flatten().map(|stamp| CacheEntry {
                    path,
                    stamp: Some(stamp),
                    queue: Arc::new(queue.clone()),
                })
            }
            Err(_) => None,
        };
        *self.entry.lock().unwrap_or_else(PoisonError::into_inner) = kept;
        written
    }

    /// How many times this cache has read and parsed the queue file since the process started --
    /// a diagnostic: a service that has nothing to do should not move it.
    pub fn reads(&self) -> u64 {
        self.reads.load(Ordering::Relaxed)
    }

    /// Whether it is time for a retention pass -- at least `every_secs` since the last one this
    /// process started, or none yet -- and if it is, notes that this one has started (a pass that then
    /// fails is not repeated before the next turn: the failure is reported by the tick that made it).
    pub fn claim_retention_pass(&self, now_unix: i64, every_secs: i64) -> bool {
        if now_unix.saturating_sub(self.retention_ran_at.load(Ordering::Relaxed)) < every_secs {
            return false;
        }
        self.retention_ran_at.store(now_unix, Ordering::Relaxed);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::oracle::lineage::ChainRecord;
    use crate::oracle::types::Source;

    /// The clock of the tests that stamp or age entries, and the retention they use (a day).
    const NOW: i64 = 1_790_001_000;
    const DAY: u64 = 86_400;

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
        ConfirmedRecordInfo { height: Some(4_765_102), coin_id: "0x01".into(), record, confirmed_at_unix: None }
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

    /// Every way the chain reconciliation confirms a record writes the record down with its entry, and the time it
    /// was confirmed (the retention counts from it): a stale pending record the chain already has, the records of
    /// an in-flight spend whose singleton coin the chain consumed, and the records of an in-flight spend that the
    /// chain has all of identically.
    #[test]
    fn reconciliation_keeps_the_content_of_every_record_it_confirms() {
        // a pending record the chain already has
        let mut queue = QueueFile { pending: vec![record(1)], ..QueueFile::default() };
        queue.reconcile_with_chain(&walk_over(&[(record(1), 100)], vec![]), NOW);
        assert_eq!(
            queue.confirmed[&1],
            ConfirmedRecordInfo { height: Some(100), coin_id: "0x01".into(), record: Some(record(1)), confirmed_at_unix: Some(NOW) }
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
        queue.reconcile_with_chain(&walk_over(&[(record(2), 101), (record(3), 101)], vec![consumed]), NOW);
        assert!(queue.in_flight.is_none());
        for seq in [2, 3] {
            assert_eq!(queue.confirmed[&seq].record, Some(record(seq)), "seq {seq}");
            assert_eq!(queue.confirmed[&seq].confirmed_at_unix, Some(NOW), "seq {seq}: stamped with the time of the pass");
        }

        // an in-flight spend whose coin the walk did not see consumed, but whose records the chain all has
        let mut queue = QueueFile { in_flight: Some(in_flight_of(&[record(4), record(5)])), ..QueueFile::default() };
        queue.reconcile_with_chain(&walk_over(&[(record(4), 102), (record(5), 102)], vec![]), NOW);
        assert!(queue.in_flight.is_none());
        for seq in [4, 5] {
            assert_eq!(queue.confirmed[&seq].record, Some(record(seq)), "seq {seq}");
            assert_eq!(queue.confirmed[&seq].confirmed_at_unix, Some(NOW), "seq {seq}: stamped with the time of the pass");
        }
    }

    /// The queue file format: a confirmed entry written before the content was kept has no `record` and loads
    /// as `None`; an entry without content is written back without the key (so such a file reads and writes
    /// back the same); one with content round-trips.
    #[test]
    fn a_confirmed_entry_from_before_the_content_was_kept_loads_and_is_written_back_unchanged() {
        let old: ConfirmedRecordInfo =
            serde_json::from_str(r#"{"height":4765102,"coin_id":"0xabc"}"#).expect("an entry of an old queue file");
        assert_eq!(old, ConfirmedRecordInfo { height: Some(4_765_102), coin_id: "0xabc".into(), record: None, confirmed_at_unix: None });
        let written = serde_json::to_value(&old).unwrap();
        assert_eq!(written, serde_json::json!({"height": 4765102, "coin_id": "0xabc"}), "no `record: null` noise");

        let with_content = confirmed_with(Some(OracleRecord { asset: 3, ..record(205) }));
        let json = serde_json::to_string(&with_content).unwrap();
        assert!(json.contains("\"record\""), "{json}");
        assert_eq!(serde_json::from_str::<ConfirmedRecordInfo>(&json).unwrap(), with_content);
    }

    // --- QueueCache --------------------------------------------------------------------------------------------

    /// A queue file with `n` pending records, written by something that is not the cache.
    fn write_queue(dir: &Path, n: u64) {
        QueueFile { pending: (1..=n).map(record).collect(), ..QueueFile::default() }.save_atomic(dir, "operator").unwrap();
    }

    /// The point of the cache: a load that finds the file as it was does not read it again, and hands out the
    /// very copy it holds.
    #[test]
    fn a_load_that_finds_the_file_as_it_was_does_not_read_it_again() {
        let dir = tempfile::tempdir().unwrap();
        write_queue(dir.path(), 3);
        let cache = QueueCache::default();

        let first = cache.load(dir.path(), "operator").unwrap();
        assert_eq!((first.pending.len(), cache.reads()), (3, 1));
        for _ in 0..5 {
            let again = cache.load(dir.path(), "operator").unwrap();
            assert!(Arc::ptr_eq(&first, &again), "the same copy, not a new parse of the same file");
        }
        assert_eq!(cache.reads(), 1, "one read for six loads");
    }

    /// What the cache writes it keeps: the next load is the copy that was written, not the file read back.
    #[test]
    fn a_save_is_kept_as_what_the_file_holds_and_is_not_read_back() {
        let dir = tempfile::tempdir().unwrap();
        let cache = QueueCache::default();
        let mut queue = (*cache.load(dir.path(), "operator").unwrap()).clone();
        assert_eq!(cache.reads(), 1, "the file is not there yet: an empty queue, read once");
        assert!(queue.pending.is_empty());

        for n in 1..=4 {
            queue.pending.push(record(n));
            cache.save(&queue, dir.path(), "operator").unwrap();
            let loaded = cache.load(dir.path(), "operator").unwrap();
            assert_eq!(loaded.pending.len() as u64, n, "after save {n}");
            // what the cache holds is what is on disk
            assert_eq!(QueueFile::load(dir.path(), "operator").unwrap().pending, loaded.pending);
        }
        assert_eq!(cache.reads(), 1, "four saves and four loads, and the file was parsed once, by the first load");
    }

    /// A file that something else wrote -- renamed into place like every version of it, or overwritten where it
    /// stands -- is read again; so is one that was removed.
    #[test]
    fn a_file_written_by_something_else_is_read_again() {
        let dir = tempfile::tempdir().unwrap();
        write_queue(dir.path(), 2);
        let cache = QueueCache::default();
        assert_eq!(cache.load(dir.path(), "operator").unwrap().pending.len(), 2);

        // another atomic write of exactly the same length: a new file, whatever the clock says
        let mut other = QueueFile { pending: vec![record(7), record(8)], ..QueueFile::default() };
        other.save_atomic(dir.path(), "operator").unwrap();
        assert_eq!(cache.load(dir.path(), "operator").unwrap().pending, vec![record(7), record(8)]);
        assert_eq!(cache.reads(), 2);

        // in place, a different length
        other.pending.push(record(9));
        std::fs::write(QueueFile::path(dir.path(), "operator"), serde_json::to_string_pretty(&other).unwrap()).unwrap();
        assert_eq!(cache.load(dir.path(), "operator").unwrap().pending.len(), 3);
        assert_eq!(cache.reads(), 3);

        // removed: an empty queue, as for a file that was never there
        std::fs::remove_file(QueueFile::path(dir.path(), "operator")).unwrap();
        assert!(cache.load(dir.path(), "operator").unwrap().pending.is_empty());
        assert_eq!(cache.reads(), 4);
        assert!(cache.load(dir.path(), "operator").unwrap().pending.is_empty());
        assert_eq!(cache.reads(), 4, "the absence itself is cached");

        // and written again
        write_queue(dir.path(), 1);
        assert_eq!(cache.load(dir.path(), "operator").unwrap().pending.len(), 1);
        assert_eq!(cache.reads(), 5);
    }

    /// A cache belongs to one file: asked for another, it holds nothing.
    #[test]
    fn a_cache_asked_about_another_file_reads_that_file() {
        let (a, b) = (tempfile::tempdir().unwrap(), tempfile::tempdir().unwrap());
        write_queue(a.path(), 1);
        write_queue(b.path(), 2);
        let cache = QueueCache::default();
        assert_eq!(cache.load(a.path(), "operator").unwrap().pending.len(), 1);
        assert_eq!(cache.load(b.path(), "operator").unwrap().pending.len(), 2);
        assert_eq!(cache.load(a.path(), "operator").unwrap().pending.len(), 1);
        assert_eq!(cache.reads(), 3);
    }

    /// A write that failed leaves no copy behind: the file may or may not have changed, so the next load asks it.
    #[test]
    fn a_failed_save_drops_the_copy() {
        let dir = tempfile::tempdir().unwrap();
        write_queue(dir.path(), 2);
        let cache = QueueCache::default();
        cache.load(dir.path(), "operator").unwrap();

        // a "directory" that is a regular file: the write cannot even start
        let blocker = dir.path().join("blocker");
        std::fs::write(&blocker, b"x").unwrap();
        assert!(cache.save(&QueueFile::default(), &blocker.join("sub"), "operator").is_err());

        assert_eq!(cache.load(dir.path(), "operator").unwrap().pending.len(), 2, "the file is as it was");
        assert_eq!(cache.reads(), 2, "read again: nothing was kept across the failed write");
    }

    /// The retention pass runs every `every` seconds by the clock, and the first time at once.
    #[test]
    fn the_retention_pass_is_claimed_at_once_and_then_every_so_often() {
        let cache = QueueCache::default();
        assert!(cache.claim_retention_pass(NOW, 600), "the first pass of a process is due");
        assert!(!cache.claim_retention_pass(NOW, 600), "not twice in the same second");
        assert!(!cache.claim_retention_pass(NOW + 599, 600));
        assert!(cache.claim_retention_pass(NOW + 600, 600));
        assert!(!cache.claim_retention_pass(NOW + 1_199, 600), "counted from the last one that started");
        assert!(!cache.claim_retention_pass(NOW - 10_000, 600), "a clock that went back waits for itself");
    }

    // --- the retention of confirmed entries ---------------------------------------------------------------------

    /// A confirmed entry as the stand's file holds them, confirmed at `at` (`None`: before the time was kept).
    fn confirmed_at(seq: u64, at: Option<i64>) -> (u64, ConfirmedRecordInfo) {
        (
            seq,
            ConfirmedRecordInfo {
                height: Some(4_000_000 + seq as u32),
                coin_id: format!("0x{seq:064x}"),
                record: Some(record(seq)),
                confirmed_at_unix: at,
            },
        )
    }

    fn queue_confirmed(entries: impl IntoIterator<Item = (u64, ConfirmedRecordInfo)>) -> QueueFile {
        QueueFile { confirmed: entries.into_iter().collect(), ..QueueFile::default() }
    }

    /// An entry is dropped once it has been there for the retention -- exactly the retention counts --, the younger
    /// ones stay; the entries that are dropped are noted in `pruned`; and a second pass at the same time has nothing
    /// left to do.
    #[test]
    fn retention_drops_what_has_been_confirmed_for_the_retention_and_keeps_the_younger() {
        let day = DAY as i64;
        let mut queue = queue_confirmed([
            confirmed_at(1, Some(NOW - day - 5)),
            confirmed_at(2, Some(NOW - day)),
            confirmed_at(3, Some(NOW - day + 1)),
            confirmed_at(4, Some(NOW)),
            confirmed_at(5, Some(NOW + 100)), // the clock of whoever stamped it was ahead
        ]);

        let outcome = queue.apply_retention(NOW, DAY);
        assert_eq!(outcome, RetentionOutcome { stamped: 0, pruned: 2, pruned_seqs: Some((1, 2)), kept: 3 });
        assert!(outcome.changed());
        assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), vec![3, 4, 5]);
        assert_eq!(queue.pruned, BTreeMap::from([(1, 2)]));
        assert!(queue.is_pruned(1) && queue.is_pruned(2) && !queue.is_pruned(3) && !queue.is_pruned(0));

        let again = queue.apply_retention(NOW, DAY);
        assert_eq!(again, RetentionOutcome { stamped: 0, pruned: 0, pruned_seqs: None, kept: 3 });
        assert!(!again.changed(), "nothing to write");

        // a day later the next ones are due, and the range grows
        let later = queue.apply_retention(NOW + day, DAY);
        assert_eq!((later.pruned, later.pruned_seqs, later.kept), (2, Some((3, 4)), 1));
        assert_eq!(queue.pruned, BTreeMap::from([(1, 4)]), "adjacent ranges are one");
    }

    /// The entries of a file written before the time was kept have none: the first pass gives them its own, so
    /// they stay for a whole retention from the upgrade -- never for less than the retention promised -- and go
    /// then.
    #[test]
    fn an_entry_without_a_confirmation_time_gets_the_time_of_the_first_pass_and_goes_a_retention_later() {
        let mut queue = queue_confirmed([confirmed_at(1, None), confirmed_at(2, None), confirmed_at(3, Some(NOW - 10))]);

        let first = queue.apply_retention(NOW, DAY);
        assert_eq!(first, RetentionOutcome { stamped: 2, pruned: 0, pruned_seqs: None, kept: 3 });
        assert!(first.changed(), "the stamps have to be written");
        assert_eq!(
            queue.confirmed.values().map(|info| info.confirmed_at_unix).collect::<Vec<_>>(),
            vec![Some(NOW), Some(NOW), Some(NOW - 10)],
            "the one that had a time keeps it"
        );

        assert!(!queue.apply_retention(NOW + DAY as i64 - 11, DAY).changed());
        let due = queue.apply_retention(NOW + DAY as i64 - 10, DAY);
        assert_eq!((due.stamped, due.pruned, due.pruned_seqs), (0, 1, Some((3, 3))), "seq 3 was stamped earlier");
        let due = queue.apply_retention(NOW + DAY as i64, DAY);
        assert_eq!((due.pruned, due.pruned_seqs, due.kept), (2, Some((1, 2)), 0));
        assert_eq!(queue.pruned, BTreeMap::from([(1, 3)]));
    }

    /// 0 is a setting, not "unset": no retention, nothing stamped, nothing dropped.
    #[test]
    fn a_retention_of_zero_keeps_everything_and_stamps_nothing() {
        let mut queue = queue_confirmed([confirmed_at(1, None), confirmed_at(2, Some(1))]);
        let outcome = queue.apply_retention(NOW + 1_000 * DAY as i64, 0);
        assert_eq!(outcome, RetentionOutcome { stamped: 0, pruned: 0, pruned_seqs: None, kept: 2 });
        assert!(!outcome.changed());
        assert_eq!(queue.confirmed[&1].confirmed_at_unix, None);
        assert!(queue.pruned.is_empty());
    }

    /// A clock that went backwards does not make anything old: an entry stamped in what is now the future is kept.
    #[test]
    fn a_clock_that_went_backwards_drops_nothing() {
        let mut queue = queue_confirmed([confirmed_at(1, Some(NOW + 10 * DAY as i64))]);
        assert!(!queue.apply_retention(NOW, DAY).changed());
        assert_eq!(queue.confirmed.len(), 1);
        // the extremes do not wrap or panic
        assert!(!queue.apply_retention(i64::MIN, u64::MAX).changed());
        assert!(!queue.apply_retention(i64::MAX, u64::MAX).changed());
    }

    /// The retention drops confirmed entries and nothing else: whatever is pending, in flight or in conflict stays
    /// however old the clock says everything is, and so do the watermarks -- `maxKnownSeq` does not move.
    #[test]
    fn retention_never_drops_what_is_not_confirmed_and_never_lowers_the_max_known_seq() {
        let mut queue = queue_confirmed([confirmed_at(1, Some(1)), confirmed_at(2, Some(1))]);
        queue.pending = vec![record(5)];
        queue.in_flight = Some(in_flight_of(&[record(3), record(4)]));
        queue.conflicts.insert(
            6,
            ConflictInfo { local: record(6), chain: OracleRecord { ts: 1, ..record(6) }, chain_height: Some(9), chain_coin_id: "0x06".into() },
        );
        queue.last_accepted_seq = Some(6);
        queue.last_confirmed_seq = Some(2);
        queue.last_confirmed_height = Some(4_000_002);
        let before = (queue.pending.clone(), queue.in_flight.as_ref().map(|f| f.records.clone()), queue.conflicts.clone());

        let outcome = queue.apply_retention(NOW + 1_000 * DAY as i64, DAY);
        assert_eq!((outcome.pruned, outcome.kept), (2, 0));
        assert_eq!(
            (queue.pending.clone(), queue.in_flight.as_ref().map(|f| f.records.clone()), queue.conflicts.clone()),
            before
        );
        assert_eq!(queue.record_state(5), RecordState::Queued);
        assert_eq!(queue.record_state(3), RecordState::InFlight);
        assert!(matches!(queue.record_state(6), RecordState::Conflict { .. }));
        assert_eq!((queue.last_accepted_seq, queue.last_confirmed_seq, queue.last_confirmed_height), (Some(6), Some(2), Some(4_000_002)));
        assert_eq!(queue.max_known_seq(), 6);

        // a queue that has only what the retention left of a long life: the pruned range alone says how far it got
        let mut only_ranges = queue_confirmed((1..=50).map(|seq| confirmed_at(seq, Some(1))));
        only_ranges.apply_retention(NOW, DAY);
        assert!(only_ranges.confirmed.is_empty());
        assert_eq!(only_ranges.max_known_seq(), 50, "no watermark, no entry, one range: 50");
    }

    /// What the retention drops, the queue still knows is confirmed: `record_state` says so (without the spend that
    /// confirmed it), `held_record` has no content, and a record sent for the seq -- the server's reconciliation does
    /// that for a row it never saw confirmed -- is the silent no-op it was, never a late record to publish again and
    /// never a collision to report (there is nothing to compare it with). A seq above the range is as new as ever.
    #[test]
    fn a_pruned_seq_is_confirmed_without_detail_and_is_never_accepted_again() {
        let mut queue = queue_confirmed((1..=3).map(|seq| confirmed_at(seq, Some(NOW - 2 * DAY as i64))));
        assert_eq!(queue.record_state(2), RecordState::Confirmed { height: Some(4_000_002), coin_id: Some(format!("0x{:064x}", 2)) });
        queue.apply_retention(NOW, DAY);
        assert!(queue.confirmed.is_empty());

        for seq in 1..=3 {
            assert_eq!(queue.record_state(seq), RecordState::Confirmed { height: None, coin_id: None }, "seq {seq}");
            assert_eq!(queue.held_record(seq), None, "no content: nothing to compare with");
        }
        let outcome = queue.enqueue(vec![record(2), OracleRecord { asset: 7, price_cents: 1, ..record(2) }, record(3)]);
        assert_eq!(outcome, EnqueueOutcome::default(), "neither accepted again nor reported");
        assert!(queue.pending.is_empty());

        assert_eq!(queue.record_state(4), RecordState::Unknown);
        assert_eq!(queue.record_state(0), RecordState::Unknown);
        assert_eq!(queue.enqueue(vec![record(4)]), EnqueueOutcome { accepted: vec![4], conflicts: vec![] });
    }

    /// `pruned` is a set of seqs kept as ranges: in whatever order the seqs are dropped the ranges end up disjoint and
    /// merged, and `is_pruned` agrees with a plain set for every number around them.
    #[test]
    fn pruned_ranges_stay_merged_whatever_the_order_the_seqs_are_dropped_in() {
        // a fixed pseudo-random order (xorshift), several different sets
        let mut state = 0x2545_f491_4f6c_dd1du64;
        let mut next = move || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        for round in 0..40u64 {
            let universe = 5 + round * 3;
            let mut seqs: Vec<u64> = (1..=universe).filter(|_| next() % 3 != 0).collect();
            // shuffle
            for i in (1..seqs.len()).rev() {
                let j = (next() % (i as u64 + 1)) as usize;
                seqs.swap(i, j);
            }
            let mut queue = QueueFile::default();
            for seq in &seqs {
                queue.add_pruned(*seq);
                queue.add_pruned(*seq); // twice: the same
            }
            let model: std::collections::BTreeSet<u64> = seqs.iter().copied().collect();
            for seq in 0..=universe + 1 {
                assert_eq!(queue.is_pruned(seq), model.contains(&seq), "round {round}: seq {seq}");
            }
            // maximal runs of the model, as the ranges must be
            let mut expected: BTreeMap<u64, u64> = BTreeMap::new();
            for seq in &model {
                match expected.iter_mut().next_back() {
                    Some((_, last)) if *last + 1 == *seq => *last = *seq,
                    _ => {
                        expected.insert(*seq, *seq);
                    }
                }
            }
            assert_eq!(queue.pruned, expected, "round {round}: ranges disjoint and never adjacent");
        }
        // the end of the number line does not wrap
        let mut queue = QueueFile::default();
        queue.add_pruned(u64::MAX);
        queue.add_pruned(u64::MAX - 1);
        assert_eq!(queue.pruned, BTreeMap::from([(u64::MAX - 1, u64::MAX)]));
        assert!(queue.is_pruned(u64::MAX) && !queue.is_pruned(u64::MAX - 2));
    }

    /// The file: one that never pruned and never had a time reads and writes back exactly as before (no new keys); one
    /// that has ranges and times round-trips.
    #[test]
    fn the_new_fields_are_written_only_when_they_have_something_to_say() {
        let plain = queue_confirmed([confirmed_at(1, None)]);
        let json = serde_json::to_value(&plain).unwrap();
        assert!(json.get("pruned").is_none(), "{json}");
        assert!(json["confirmed"]["1"].get("confirmed_at_unix").is_none(), "{json}");
        let read: QueueFile = serde_json::from_value(json).unwrap();
        assert_eq!(read.confirmed, plain.confirmed);

        let mut queue = queue_confirmed([confirmed_at(1, Some(NOW - 2 * DAY as i64)), confirmed_at(2, Some(NOW))]);
        queue.apply_retention(NOW, DAY);
        let json = serde_json::to_value(&queue).unwrap();
        assert_eq!(json["pruned"], serde_json::json!({"1": 1}));
        assert_eq!(json["confirmed"]["2"]["confirmed_at_unix"], serde_json::json!(NOW));
        let read: QueueFile = serde_json::from_value(json).unwrap();
        assert_eq!((read.pruned, read.confirmed), (queue.pruned, queue.confirmed));
    }
}
