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

use std::path::{Path, PathBuf};

use anyhow::Result;
use c2_cli::state::ProofDto;
use chia_protocol::{Coin, SpendBundle};
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};

use super::types::OracleRecord;

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
    /// Highest `seq` ever accepted into the queue (`pending`, `in_flight`,
    /// or already confirmed) -- dedupes a record a caller resubmits (e.g.
    /// after a timed-out `POST /v1/oracle/records` it isn't sure went
    /// through).
    pub last_accepted_seq: Option<u64>,
    pub last_confirmed_seq: Option<u64>,
    pub last_confirmed_height: Option<u32>,
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

    /// Accepts every record in `incoming` whose `seq` is strictly greater
    /// than the running watermark (`last_accepted_seq`), processed in
    /// ascending `seq` order so the watermark advances monotonically even
    /// within one batch. Returns how many were newly accepted (the
    /// `queued` count `POST /v1/oracle/records` responds with).
    pub fn enqueue(&mut self, mut incoming: Vec<OracleRecord>) -> usize {
        incoming.sort_by_key(|r| r.seq);
        let mut accepted = 0;
        for record in incoming {
            let watermark = self.last_accepted_seq.unwrap_or(0);
            if self.last_accepted_seq.is_some() && record.seq <= watermark {
                continue; // duplicate / out-of-order resubmission, ignored
            }
            self.last_accepted_seq = Some(record.seq);
            self.pending.push(record);
            accepted += 1;
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
