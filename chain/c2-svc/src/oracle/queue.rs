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
use chia_protocol::{Coin, SpendBundle};
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};

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
    InFlight,
    Queued,
    /// Neither pending, in flight, nor confirmed -- either never submitted,
    /// or (before this fix) silently lost. `enqueue` treats this the same
    /// way regardless of which: accept it (task instruction, FIXED
    /// INTERFACE (b): "a record whose seq is below the watermark but is NOT
    /// known as confirmed/queued/in_flight is accepted as a LATE record").
    Unknown,
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
}
