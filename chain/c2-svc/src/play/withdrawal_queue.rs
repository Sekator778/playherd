//! Durable withdrawal-request queue (`<data_dir>/<name>.play_withdrawal_
//! queue.json`) -- same "queue durably before broadcasting, never
//! double-spend, retry with backoff" discipline as the oracle publisher's own
//! queue (`oracle/queue.rs`), reused here as a parallel structure rather than
//! a shared one (task instruction: "reuse its patterns", not "reuse its
//! code" -- the oracle queue tracks a self-recreating *singleton* keyed by
//! `seq`, with a chain-lineage-walk reconciliation layered on top after three
//! real incidents, `docs/research/chain_service.md` §9/§11/§12; this one
//! tracks a self-recreating ordinary CAT coin keyed by `requestId`, with no
//! lineage walk -- see `withdrawal_publisher.rs`'s module doc for why a
//! shared generic type isn't worth the contortion).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use anyhow::Result;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use chia_puzzle_types::LineageProof;
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};

pub use super::types::WithdrawalRequest;

/// Hand-written serde mirror of `chia_puzzle_types::LineageProof` (same
/// reason `c2_cli::state::ProofDto` exists: that crate never derives serde).
/// Only ever `LineageProof`, never the `Eve` case -- the treasury's PLAY CAT
/// coin is only ever the eve coin for the single `issue-play` spend that
/// minted it in the first place, which `c2-svc` never tracks itself (task
/// instruction: `operator.play.json` already holds `PlayCoinState`;
/// `c2_cli::state::PlayCoinState::lineage_proof()` itself bails on an `Eve`
/// proof for the exact same reason).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct LineageProofDto {
    pub parent_parent_coin_info: Bytes32,
    pub parent_inner_puzzle_hash: Bytes32,
    pub parent_amount: u64,
}

impl From<LineageProof> for LineageProofDto {
    fn from(p: LineageProof) -> Self {
        Self {
            parent_parent_coin_info: p.parent_parent_coin_info,
            parent_inner_puzzle_hash: p.parent_inner_puzzle_hash,
            parent_amount: p.parent_amount,
        }
    }
}

impl From<LineageProofDto> for LineageProof {
    fn from(d: LineageProofDto) -> Self {
        Self {
            parent_parent_coin_info: d.parent_parent_coin_info,
            parent_inner_puzzle_hash: d.parent_inner_puzzle_hash,
            parent_amount: d.parent_amount,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InFlightWithdrawalBatch {
    /// Requests this batch pays out, in the order they were taken from
    /// `pending` (`WithdrawalQueueFile::take_batch`).
    pub requests: Vec<WithdrawalRequest>,
    /// `requests[i]`'s own payout coin id, same order and same length as
    /// `requests` (task fix, `docs/research/chain_service.md` §13:
    /// `advance_in_flight` used to record every request in a batch as
    /// confirmed at the treasury's *change* coin's id instead of each
    /// request's own -- see `withdrawal_spend::WithdrawalBatchResult::
    /// payout_coin_ids`'s doc comment for how these are computed).
    pub payout_coin_ids: Vec<Bytes32>,
    /// The treasury PLAY CAT coin this spend consumes (== `PlayCoinState::
    /// coin` at the time this batch was built).
    pub spent_coin: Coin,
    pub next_coin: Coin,
    pub next_lineage_proof: LineageProofDto,
    /// Fully signed, ready to `push_tx` as-is -- replayed verbatim on
    /// retry/restart, never rebuilt from scratch (same reasoning as
    /// `oracle::queue::InFlightSpend::bundle`'s doc comment).
    pub bundle: SpendBundle,
    /// Locally computed identifier for `GET /v1/play/withdrawals/{requestId}`
    /// (**предложение**, same construction and same caveat as
    /// `oracle::publisher::bundle_display_id`: `sha256` of the aggregated
    /// signature, not Chia's canonical `SpendBundle::name()`).
    pub bundle_id: String,
    pub fee_mojos: u64,
    pub broadcast_at_unix: i64,
    pub last_attempt_at_unix: i64,
    pub attempts: u32,
    pub last_push_accepted: bool,
    pub last_push_error: Option<String>,
}

/// `GET /v1/play/withdrawals/{requestId}`'s `state`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WithdrawalState {
    Queued,
    InFlight,
    Confirmed { coin_id: Bytes32, height: Option<u32> },
    /// This exact `requestId`'s batch exhausted its retry budget (fee already
    /// at the cap, or the rejection wasn't fee-related) and will not be
    /// retried automatically any further -- the task's FIXED INTERFACE names
    /// this state explicitly (unlike the oracle status endpoint's
    /// `stuckReason`, which only ever *annotates* a still-pending spend, this
    /// is terminal for the request: the treasury coin this batch would have
    /// spent is left untouched, so a fresh `POST` with a new `requestId`
    /// retries cleanly against the next batch).
    Failed { reason: String },
    Unknown,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WithdrawalQueueFile {
    pub pending: Vec<WithdrawalRequest>,
    /// At most one at a time -- the "never double-spend the treasury coin"
    /// invariant, same as the oracle singleton's `in_flight`.
    pub in_flight: Option<InFlightWithdrawalBatch>,
    pub confirmed: BTreeMap<String, (Bytes32, Option<u32>)>,
    pub failed: BTreeMap<String, String>,
}

impl WithdrawalQueueFile {
    pub fn path(data_dir: &Path, name: &str) -> PathBuf {
        data_dir.join(format!("{name}.play_withdrawal_queue.json"))
    }

    pub fn load(data_dir: &Path, name: &str) -> Result<Self> {
        read_json_or_default(&Self::path(data_dir, name), Self::default)
    }

    pub fn save_atomic(&self, data_dir: &Path, name: &str) -> Result<()> {
        write_json_atomic(&Self::path(data_dir, name), self)
    }

    pub fn state(&self, request_id: &str) -> WithdrawalState {
        if let Some((coin_id, height)) = self.confirmed.get(request_id) {
            return WithdrawalState::Confirmed { coin_id: *coin_id, height: *height };
        }
        if let Some(reason) = self.failed.get(request_id) {
            return WithdrawalState::Failed { reason: reason.clone() };
        }
        if let Some(in_flight) = &self.in_flight
            && in_flight.requests.iter().any(|r| r.request_id == request_id)
        {
            return WithdrawalState::InFlight;
        }
        if self.pending.iter().any(|r| r.request_id == request_id) {
            return WithdrawalState::Queued;
        }
        WithdrawalState::Unknown
    }

    /// Idempotent by `requestId` (task's FIXED INTERFACE: "idempotent by
    /// requestId") -- a request already known queued/in-flight/confirmed/
    /// failed is a silent no-op, `true` iff it was newly accepted into
    /// `pending`.
    pub fn enqueue(&mut self, request: WithdrawalRequest) -> bool {
        if !matches!(self.state(&request.request_id), WithdrawalState::Unknown) {
            return false;
        }
        self.pending.push(request);
        true
    }

    /// Takes up to `max` of the oldest pending requests (FIFO, insertion
    /// order) out of `pending` for `withdrawal_spend::
    /// build_play_withdrawal_batch`. Does **not** touch `in_flight` -- same
    /// "caller persists atomically in the same write" discipline as
    /// `oracle::queue::QueueFile::take_batch`.
    pub fn take_batch(&mut self, max: usize) -> Vec<WithdrawalRequest> {
        let n = self.pending.len().min(max);
        self.pending.drain(..n).collect()
    }

    pub fn pending_count(&self) -> usize {
        self.pending.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(id: &str) -> WithdrawalRequest {
        WithdrawalRequest {
            request_id: id.to_string(),
            to_address: "txch1test".to_string(),
            to_puzzle_hash: Bytes32::from([1u8; 32]),
            amount_mojos: 1_000,
        }
    }

    #[test]
    fn enqueue_is_idempotent_by_request_id() {
        let mut queue = WithdrawalQueueFile::default();
        assert!(queue.enqueue(request("r1")));
        assert!(!queue.enqueue(request("r1")));
        assert_eq!(queue.pending_count(), 1);
    }

    #[test]
    fn state_reports_every_lifecycle_stage() {
        let mut queue = WithdrawalQueueFile::default();
        assert_eq!(queue.state("r1"), WithdrawalState::Unknown);

        queue.enqueue(request("r1"));
        assert_eq!(queue.state("r1"), WithdrawalState::Queued);

        let batch = queue.take_batch(10);
        queue.in_flight = Some(InFlightWithdrawalBatch {
            payout_coin_ids: vec![Bytes32::from([3u8; 32])],
            requests: batch,
            spent_coin: Coin::new(Bytes32::from([0u8; 32]), Bytes32::from([0u8; 32]), 1),
            next_coin: Coin::new(Bytes32::from([0u8; 32]), Bytes32::from([0u8; 32]), 1),
            next_lineage_proof: LineageProofDto {
                parent_parent_coin_info: Bytes32::from([0u8; 32]),
                parent_inner_puzzle_hash: Bytes32::from([0u8; 32]),
                parent_amount: 1,
            },
            bundle: SpendBundle::new(vec![], chia_bls::Signature::default()),
            bundle_id: "0xtest".to_string(),
            fee_mojos: 0,
            broadcast_at_unix: 0,
            last_attempt_at_unix: 0,
            attempts: 1,
            last_push_accepted: true,
            last_push_error: None,
        });
        assert_eq!(queue.state("r1"), WithdrawalState::InFlight);

        queue.in_flight = None;
        queue.confirmed.insert("r1".to_string(), (Bytes32::from([2u8; 32]), Some(42)));
        assert_eq!(queue.state("r1"), WithdrawalState::Confirmed { coin_id: Bytes32::from([2u8; 32]), height: Some(42) });
    }
}
