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

use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};

use anyhow::Result;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use chia_puzzle_types::LineageProof;
use serde::{Deserialize, Serialize};

use crate::atomic_file::{read_json_or_default, write_json_atomic};
use crate::fee_allocator::fee_coin_of;

pub use super::types::{ConflictKind, RequestIdConflict, WithdrawalRequest};

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

/// An earlier version of the in-flight batch: a bundle for the *same treasury coin* that the publisher
/// replaced (in the mempool, `WithdrawalPublisher::replace_in_mempool`), rebuilt (after a rejection), or
/// pushed as a replacement the node then did not take -- the withdrawal counterpart of
/// `oracle::queue::SupersededBundle` (`docs/research/chain_service.md` §17.4, §17.8). Only one bundle can
/// spend the treasury coin, so at most one version ever confirms; the chain says which, and an earlier
/// version can still win (another node's mempool may hold it, a parked replacement may be retried by the
/// node at a new block). What is kept is what it takes to account for that: the requests that version
/// pays -- in full, because a request that went back to `pending` when its replacement was refused is
/// paid all the same if that replacement is the one the chain takes -- the coin each payout created
/// (the proof of the payout), the treasury change coin it would leave (its existence on chain is how the
/// winning version is recognised -- a version that pays more leaves a smaller change coin, so two
/// versions with different payouts never leave the same one), and its fee coin.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SupersededWithdrawalBatch {
    /// `SpendBundle::name()` of that version.
    pub bundle_name: Bytes32,
    pub fee_mojos: u64,
    /// The requests that version pays, in payout order; `payout_coin_ids[i]` is `requests[i]`'s coin.
    pub requests: Vec<WithdrawalRequest>,
    pub payout_coin_ids: Vec<Bytes32>,
    /// The treasury change coin that version creates.
    pub next_coin: Coin,
    #[serde(default)]
    pub fee_coin: Option<Bytes32>,
    pub superseded_at_unix: i64,
    /// `"replaced in the mempool"`, `"rebuilt after a rejection"` or `"replacement not taken by the node"`.
    pub reason: String,
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
    /// (**proposal**, same construction and same caveat as
    /// `oracle::publisher::bundle_display_id`: `sha256` of the aggregated
    /// signature, not Chia's canonical `SpendBundle::name()`).
    pub bundle_id: String,
    pub fee_mojos: u64,
    pub broadcast_at_unix: i64,
    pub last_attempt_at_unix: i64,
    pub attempts: u32,
    pub last_push_accepted: bool,
    pub last_push_error: Option<String>,
    /// Earlier versions of this batch, oldest first -- empty for a batch never replaced or rebuilt.
    /// `#[serde(default)]`: a queue file written before this field existed has none.
    #[serde(default)]
    pub superseded: Vec<SupersededWithdrawalBatch>,
    /// Why this batch, which sits in the mempool past `C2_MEMPOOL_REPLACE_AFTER_SECS`, could not be
    /// replaced; `GET /health` reports it while the batch keeps waiting. `#[serde(default)]` as above.
    #[serde(default)]
    pub replace_blocked: Option<String>,
}

impl InFlightWithdrawalBatch {
    /// This version as a `SupersededWithdrawalBatch`, for the batch that replaces it to carry.
    pub fn as_superseded(&self, reason: &str, now_unix: i64) -> SupersededWithdrawalBatch {
        SupersededWithdrawalBatch {
            bundle_name: self.bundle.name(),
            fee_mojos: self.fee_mojos,
            requests: self.requests.clone(),
            payout_coin_ids: self.payout_coin_ids.clone(),
            next_coin: self.next_coin,
            fee_coin: fee_coin_of(&self.bundle, self.spent_coin).map(|coin| coin.coin_id()),
            superseded_at_unix: now_unix,
            reason: reason.to_string(),
        }
    }

    /// Ids of the fee coins this version and every earlier one spent (released once the treasury coin
    /// is consumed, whichever version that was; releasing is idempotent).
    pub fn every_fee_coin(&self) -> Vec<Bytes32> {
        let mut coins: Vec<Bytes32> = fee_coin_of(&self.bundle, self.spent_coin).map(|c| c.coin_id()).into_iter().collect();
        coins.extend(self.superseded.iter().filter_map(|version| version.fee_coin));
        coins.sort_unstable();
        coins.dedup();
        coins
    }
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

/// Recipient and amount of a request that has left `pending`/`in_flight` for `confirmed` or
/// `failed` -- the two maps keep only the outcome, so without this a `requestId` reused with other
/// content could not be told from a repeat (`WithdrawalQueueFile::enqueue`, §17.6).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct SettledRequest {
    pub to_puzzle_hash: Bytes32,
    pub amount_mojos: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WithdrawalQueueFile {
    pub pending: Vec<WithdrawalRequest>,
    /// At most one at a time -- the "never double-spend the treasury coin"
    /// invariant, same as the oracle singleton's `in_flight`.
    pub in_flight: Option<InFlightWithdrawalBatch>,
    pub confirmed: BTreeMap<String, (Bytes32, Option<u32>)>,
    pub failed: BTreeMap<String, String>,
    /// What each `confirmed`/`failed` request was for. `#[serde(default)]`: a queue file written
    /// before this field existed has none, and its confirmed/failed entries then count as "held,
    /// content not recorded" (`ConflictKind::ContentNotRecorded`) -- the stand's file from before
    /// 2026-10-02 holds `w-11` ... `w-15` confirmed exactly like that.
    #[serde(default)]
    pub settled: BTreeMap<String, SettledRequest>,
}

/// Requests `WithdrawalQueueFile::take_batch` took out of `pending`, each with the place it had there,
/// so that `put_back` can return them exactly (the requests that were not taken keep their order, and so
/// do these when they go back).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TakenRequests {
    /// `(index in pending before the take, the request)`, ascending by index.
    items: Vec<(usize, WithdrawalRequest)>,
}

impl TakenRequests {
    pub fn len(&self) -> usize {
        self.items.len()
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    /// The taken requests, oldest first.
    pub fn iter(&self) -> impl Iterator<Item = &WithdrawalRequest> {
        self.items.iter().map(|(_, request)| request)
    }

    pub fn to_vec(&self) -> Vec<WithdrawalRequest> {
        self.iter().cloned().collect()
    }
}

/// Whether no two of `requests` would create the same payout coin (`WithdrawalRequest::payout_key`):
/// whether one batch can carry all of them.
pub fn is_collision_free(requests: &[WithdrawalRequest]) -> bool {
    let mut seen = HashSet::new();
    requests.iter().all(|request| seen.insert(request.payout_key()))
}

/// Splits `requests`, in order, into the ones one batch can carry together -- the first request with each
/// payout key -- and the ones that would create the same coin as an earlier one.
pub fn split_identical_payouts(requests: &[WithdrawalRequest]) -> (Vec<WithdrawalRequest>, Vec<WithdrawalRequest>) {
    let mut seen = HashSet::new();
    requests.iter().cloned().partition(|request| seen.insert(request.payout_key()))
}

/// What `WithdrawalQueueFile::enqueue` found under a `requestId`.
enum Held {
    Nothing,
    Known { to_puzzle_hash: Bytes32, amount_mojos: u64 },
    Unrecorded,
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
    /// failed *with the same recipient and amount* is a silent no-op: `Ok(true)`
    /// iff it was newly accepted into `pending`.
    ///
    /// The same `requestId` with a different recipient or amount is a conflict
    /// (`RequestIdConflict`, HTTP 409), never the old request's status: on 2026-10-02 the server
    /// started on a fresh database whose withdrawal ids began at `w-1` again, and its 11th withdrawal
    /// would have been answered with the confirmed status of the *old* `w-11` -- another recipient,
    /// another amount -- and been marked paid without a payout. A confirmed or failed entry written
    /// before `settled` existed has no recorded content to compare with and is refused as well
    /// (`ConflictKind::ContentNotRecorded`): fail closed.
    pub fn enqueue(&mut self, request: WithdrawalRequest) -> Result<bool, RequestIdConflict> {
        match self.held(&request.request_id) {
            Held::Nothing => {
                self.pending.push(request);
                Ok(true)
            }
            Held::Known { to_puzzle_hash, amount_mojos }
                if to_puzzle_hash == request.to_puzzle_hash && amount_mojos == request.amount_mojos =>
            {
                Ok(false)
            }
            Held::Known { .. } => {
                Err(RequestIdConflict { request_id: request.request_id, kind: ConflictKind::DifferentContent })
            }
            Held::Unrecorded => {
                Err(RequestIdConflict { request_id: request.request_id, kind: ConflictKind::ContentNotRecorded })
            }
        }
    }

    /// Same precedence as `state`: confirmed, failed, in flight, queued.
    fn held(&self, request_id: &str) -> Held {
        if self.confirmed.contains_key(request_id) || self.failed.contains_key(request_id) {
            return match self.settled.get(request_id) {
                Some(settled) => {
                    Held::Known { to_puzzle_hash: settled.to_puzzle_hash, amount_mojos: settled.amount_mojos }
                }
                None => Held::Unrecorded,
            };
        }
        let in_flight_request = self.in_flight.as_ref().and_then(|f| f.requests.iter().find(|r| r.request_id == request_id));
        let pending_request = self.pending.iter().find(|r| r.request_id == request_id);
        match in_flight_request.or(pending_request) {
            Some(r) => Held::Known { to_puzzle_hash: r.to_puzzle_hash, amount_mojos: r.amount_mojos },
            None => Held::Nothing,
        }
    }

    /// `request` was paid out: its own payout coin and the block, and what it was for.
    pub fn record_confirmed(&mut self, request: &WithdrawalRequest, payout_coin_id: Bytes32, height: Option<u32>) {
        self.confirmed.insert(request.request_id.clone(), (payout_coin_id, height));
        self.settled.insert(
            request.request_id.clone(),
            SettledRequest { to_puzzle_hash: request.to_puzzle_hash, amount_mojos: request.amount_mojos },
        );
    }

    /// `request` will not be paid by this service (terminal): why, and what it was for.
    pub fn record_failed(&mut self, request: &WithdrawalRequest, reason: String) {
        self.failed.insert(request.request_id.clone(), reason);
        self.settled.insert(
            request.request_id.clone(),
            SettledRequest { to_puzzle_hash: request.to_puzzle_hash, amount_mojos: request.amount_mojos },
        );
    }

    /// Takes up to `room` of the oldest pending requests (FIFO, insertion order) out of `pending` for
    /// `withdrawal_spend::build_play_withdrawal_batch`, **never two that would create the same coin**
    /// (`WithdrawalRequest::payout_key`): a request whose recipient and amount equal those of a request
    /// already in the batch -- one of `carried` (the requests the batch has already, when a rebuild or a
    /// replacement merges newly pending ones into an in-flight batch; empty for a new batch) or one taken
    /// earlier in this very call -- is skipped. It stays in `pending`, in its place among the others,
    /// and goes into a later batch (`docs/research/chain_service.md` §18.1); nothing is dropped, failed
    /// or paid twice, and the order of everything else is the arrival order. A spend that creates the same
    /// coin twice is invalid, and one invalid batch would hold up every payout behind it.
    ///
    /// Does **not** touch `in_flight` -- same "caller persists atomically in the same write" discipline as
    /// `oracle::queue::QueueFile::take_batch`. What is taken remembers where it came from:
    /// `put_back` returns it to exactly those places.
    pub fn take_batch(&mut self, carried: &[WithdrawalRequest], room: usize) -> TakenRequests {
        let mut used: HashSet<(Bytes32, u64)> = carried.iter().map(WithdrawalRequest::payout_key).collect();
        let mut taken = Vec::new();
        let mut kept = Vec::with_capacity(self.pending.len());
        for (index, request) in std::mem::take(&mut self.pending).into_iter().enumerate() {
            // `insert` last, so that a key is only claimed by a request that is really taken.
            if taken.len() < room && used.insert(request.payout_key()) {
                taken.push((index, request));
            } else {
                kept.push(request);
            }
        }
        self.pending = kept;
        TakenRequests { items: taken }
    }

    /// Returns what `take_batch` took to the places in `pending` it came from -- for a batch that was
    /// not made after all (dry run, the fee-coin split holding the wallet's only fee coin, a replacement
    /// the node refused). Exact only if `pending` has not been changed since the take, which is so
    /// wherever it is used: the publisher holds `queue_lock` for the whole tick, and `enqueue` works on a
    /// copy of its own.
    pub fn put_back(&mut self, taken: TakenRequests) {
        for (index, request) in taken.items {
            self.pending.insert(index.min(self.pending.len()), request);
        }
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
        assert_eq!(queue.enqueue(request("r1")), Ok(true));
        assert_eq!(queue.enqueue(request("r1")), Ok(false));
        assert_eq!(queue.pending_count(), 1);
    }

    /// A queue file written before `superseded`, `replace_blocked` and `settled` existed -- the stand's
    /// own file is one -- still loads: every new field is `#[serde(default)]`, and its confirmed entries
    /// are "held, content not recorded".
    #[test]
    fn a_queue_file_from_before_the_replacement_fields_still_loads() {
        let mut queue = WithdrawalQueueFile::default();
        queue.enqueue(request("r1")).unwrap();
        let batch = queue.take_batch(&[], 1).to_vec();
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
            superseded: Vec::new(),
            replace_blocked: None,
        });
        queue.confirmed.insert("w-11".to_string(), (Bytes32::from([2u8; 32]), Some(42)));

        let mut json = serde_json::to_value(&queue).unwrap();
        let in_flight = json["in_flight"].as_object_mut().unwrap();
        assert!(in_flight.remove("superseded").is_some() && in_flight.remove("replace_blocked").is_some());
        assert!(json.as_object_mut().unwrap().remove("settled").is_some());

        let old_file: WithdrawalQueueFile = serde_json::from_value(json).unwrap();
        assert!(old_file.in_flight.as_ref().unwrap().superseded.is_empty());
        assert_eq!(old_file.in_flight.as_ref().unwrap().replace_blocked, None);
        assert_eq!(old_file.state("w-11"), WithdrawalState::Confirmed { coin_id: Bytes32::from([2u8; 32]), height: Some(42) });
        let mut old_file = old_file;
        let conflict = old_file.enqueue(request("w-11")).unwrap_err();
        assert_eq!(conflict.kind, ConflictKind::ContentNotRecorded);
    }

    /// A request to the recipient `who` (a puzzle hash of repeated bytes) for `amount`.
    fn payout(id: &str, who: u8, amount: u64) -> WithdrawalRequest {
        WithdrawalRequest {
            request_id: id.to_string(),
            to_address: format!("txch1recipient{who}"),
            to_puzzle_hash: Bytes32::from([who; 32]),
            amount_mojos: amount,
        }
    }

    const MAX_BATCH: usize = 20;

    fn ids(requests: &[WithdrawalRequest]) -> Vec<&str> {
        requests.iter().map(|r| r.request_id.as_str()).collect()
    }

    fn queue_of(requests: Vec<WithdrawalRequest>) -> WithdrawalQueueFile {
        WithdrawalQueueFile { pending: requests, ..WithdrawalQueueFile::default() }
    }

    /// Two requests that create the same coin are never taken into one batch: the later one stays pending, in
    /// its place among the others, and the next batch takes it -- which is again the first of its kind.
    #[test]
    fn take_batch_keeps_a_request_that_repeats_a_payout_pending_for_a_later_batch() {
        let mut queue = queue_of(vec![
            payout("a1", 1, 100),
            payout("a2", 1, 100), // alice again, the same amount
            payout("b", 2, 50),
            payout("a3", 1, 100), // and again
            payout("c", 3, 7),
        ]);

        let first = queue.take_batch(&[], MAX_BATCH);
        assert_eq!(ids(&first.to_vec()), vec!["a1", "b", "c"], "arrival order, one request per payout");
        assert_eq!(ids(&queue.pending), vec!["a2", "a3"], "the repeats stay pending in their order");

        let second = queue.take_batch(&[], MAX_BATCH);
        assert_eq!(ids(&second.to_vec()), vec!["a2"]);
        let third = queue.take_batch(&[], MAX_BATCH);
        assert_eq!(ids(&third.to_vec()), vec!["a3"]);
        assert!(queue.pending.is_empty());
        assert!(queue.take_batch(&[], MAX_BATCH).is_empty());
    }

    /// What a rebuild or a replacement merges into an in-flight batch is taken beside what the batch already
    /// carries: a pending request that repeats one of those is left alone.
    #[test]
    fn take_batch_beside_a_carried_request_skips_the_one_that_repeats_it() {
        let carried = vec![payout("w1", 1, 100)];
        let mut queue = queue_of(vec![payout("w2", 1, 100), payout("w3", 2, 50), payout("w4", 1, 100), payout("w5", 3, 9)]);

        let merged = queue.take_batch(&carried, MAX_BATCH);
        assert_eq!(ids(&merged.to_vec()), vec!["w3", "w5"]);
        assert_eq!(ids(&queue.pending), vec!["w2", "w4"]);
    }

    /// Only requests that are really taken count against the room: a skipped repeat does not use up a place.
    #[test]
    fn take_batch_counts_only_what_it_takes_against_the_room() {
        let mut queue = queue_of(vec![payout("r1", 1, 100), payout("r2", 1, 100), payout("r3", 2, 50), payout("r4", 3, 7)]);
        let taken = queue.take_batch(&[], 2);
        assert_eq!(ids(&taken.to_vec()), vec!["r1", "r3"], "the skipped r2 does not use up a place");
        assert_eq!(ids(&queue.pending), vec!["r2", "r4"]);

        // With no room nothing is taken and nothing moves.
        let none = queue.take_batch(&[], 0);
        assert!(none.is_empty());
        assert_eq!(ids(&queue.pending), vec!["r2", "r4"]);
    }

    /// The same recipient with another amount, or the same amount for another recipient, is another coin:
    /// they share a batch. The address string is not what is compared, the puzzle hash is.
    #[test]
    fn only_the_recipient_and_the_amount_together_make_two_payouts_the_same_coin() {
        let mut queue = queue_of(vec![
            payout("same-recipient-other-amount-1", 1, 100),
            payout("same-recipient-other-amount-2", 1, 101),
            payout("same-amount-other-recipient", 2, 100),
        ]);
        assert_eq!(queue.take_batch(&[], MAX_BATCH).len(), 3);

        let mut written_differently = payout("written-differently", 1, 100);
        written_differently.to_address = "txch1another-spelling-of-the-same-hash".to_string();
        let mut queue = queue_of(vec![payout("first", 1, 100), written_differently]);
        let taken = queue.take_batch(&[], MAX_BATCH);
        assert_eq!(ids(&taken.to_vec()), vec!["first"], "the same puzzle hash and amount is the same coin whatever the address string");
        assert_eq!(ids(&queue.pending), vec!["written-differently"]);
    }

    /// A batch that is not made after all gives its requests back where they were: nothing is reordered, the
    /// skipped ones included.
    #[test]
    fn put_back_restores_pending_exactly() {
        let original = vec![
            payout("p1", 1, 100),
            payout("p2", 1, 100),
            payout("p3", 2, 50),
            payout("p4", 1, 100),
            payout("p5", 3, 7),
            payout("p6", 2, 50),
            payout("p7", 4, 1),
        ];
        for carried in [vec![], vec![payout("in-flight", 3, 7)]] {
            for room in [0, 1, 2, 3, 20] {
                let mut queue = queue_of(original.clone());
                let taken = queue.take_batch(&carried, room);
                queue.put_back(taken);
                assert_eq!(queue.pending, original, "carried {} room {room}", carried.len());
            }
        }
    }

    #[test]
    fn identical_payouts_are_told_apart_in_a_list_of_requests() {
        let requests = vec![payout("x1", 1, 100), payout("x2", 2, 100), payout("x3", 1, 100), payout("x4", 1, 101), payout("x5", 2, 100)];
        assert!(!is_collision_free(&requests));
        let (kept, repeats) = split_identical_payouts(&requests);
        assert_eq!(ids(&kept), vec!["x1", "x2", "x4"]);
        assert_eq!(ids(&repeats), vec!["x3", "x5"]);
        assert!(is_collision_free(&kept) && is_collision_free(&[]));
    }

    #[test]
    fn state_reports_every_lifecycle_stage() {
        let mut queue = WithdrawalQueueFile::default();
        assert_eq!(queue.state("r1"), WithdrawalState::Unknown);

        queue.enqueue(request("r1")).unwrap();
        assert_eq!(queue.state("r1"), WithdrawalState::Queued);

        let batch = queue.take_batch(&[], 10).to_vec();
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
            superseded: Vec::new(),
            replace_blocked: None,
        });
        assert_eq!(queue.state("r1"), WithdrawalState::InFlight);

        queue.in_flight = None;
        queue.confirmed.insert("r1".to_string(), (Bytes32::from([2u8; 32]), Some(42)));
        assert_eq!(queue.state("r1"), WithdrawalState::Confirmed { coin_id: Bytes32::from([2u8; 32]), height: Some(42) });
    }
}
