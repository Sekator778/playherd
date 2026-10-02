//! DTOs (and the validated internal withdrawal-request type) for the PLAY
//! withdraw/deposit FIXED INTERFACE (Р-6, decision Р-53 2026-09-26):
//! `POST /v1/play/withdrawals`, `GET /v1/play/withdrawals/{requestId}`,
//! `POST /v1/play/deposit-address`, `GET /v1/play/deposits`. See
//! `docs/research/chain_service.md` §13.

use chia_protocol::Bytes32;
use chia_sdk_utils::Address;
use serde::{Deserialize, Serialize};

use c2_cli::keys::TXCH_PREFIX;

/// Wire shape of `POST /v1/play/withdrawals`'s body (exact field names).
/// `Serialize` too (this service itself only ever deserializes it -- it's
/// the request side) so `tests/contract_fixtures.rs` can write a genuine
/// example straight from this type, same as `oracle::types::OracleRecordDto`
/// already does for its own request DTO.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WithdrawalRequestDto {
    pub request_id: String,
    pub to_address: String,
    pub amount_mojos: u64,
}

/// Validated, internal shape -- `to_address` decoded to a puzzle hash once,
/// up front, so a bad address is rejected at `POST` time (`400`) rather than
/// discovered later while building a batch spend. Mirrors
/// `oracle::types::OracleRecord`'s own validated-DTO split.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct WithdrawalRequest {
    pub request_id: String,
    pub to_address: String,
    pub to_puzzle_hash: Bytes32,
    pub amount_mojos: u64,
}

impl WithdrawalRequest {
    pub fn from_dto(dto: &WithdrawalRequestDto) -> Result<Self, String> {
        if dto.request_id.trim().is_empty() {
            return Err("requestId must not be empty".to_string());
        }
        if dto.amount_mojos == 0 {
            return Err("amountMojos must be > 0".to_string());
        }
        let to_puzzle_hash = Address::decode(&dto.to_address)
            .map_err(|e| format!("toAddress: invalid bech32m address: {e}"))?
            .expect_prefix(TXCH_PREFIX)
            .map_err(|e| format!("toAddress must be a txch1... (testnet11) address: {e}"))?;
        Ok(Self {
            request_id: dto.request_id.clone(),
            to_address: dto.to_address.clone(),
            to_puzzle_hash,
            amount_mojos: dto.amount_mojos,
        })
    }

    /// What this request's payout is on chain: the recipient's puzzle hash and the amount. Every payout of
    /// a batch is a child of the one treasury coin and the CAT puzzle hash of a payout is a function of the
    /// recipient's, so two requests with the same key create the *same coin* -- and a spend that creates
    /// the same coin twice is invalid (`DUPLICATE_OUTPUT`, `chia-consensus` `conditions.rs`): one batch can
    /// carry at most one request per key (`WithdrawalQueueFile::take_batch`,
    /// `docs/research/chain_service.md` §18.1). The address string is no part of the key, only the puzzle
    /// hash it decodes to.
    pub fn payout_key(&self) -> (Bytes32, u64) {
        (self.to_puzzle_hash, self.amount_mojos)
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WithdrawalAcceptedResponse {
    pub accepted: bool,
}

/// Why `POST /v1/play/withdrawals` refused a `requestId` this service already holds
/// (`docs/research/chain_service.md` §17.6).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConflictKind {
    /// The id is held with a recipient or an amount other than the ones now submitted.
    DifferentContent,
    /// The id is held (confirmed or failed) but the recipient and the amount were not recorded:
    /// the entry predates content tracking, so a repeat of that withdrawal cannot be told from a
    /// different withdrawal reusing the id. Refused, never answered with the old entry's status.
    ContentNotRecorded,
}

/// A `requestId` that this service holds -- queued, in flight, confirmed or failed -- was submitted
/// again with a different recipient or amount (or with no way to tell). Never the old request's
/// status: a payout that the server believes happened must have happened for *its* request.
/// `app.rs` answers it with HTTP 409 and a `WithdrawalConflictResponse`.
///
/// The same `requestId` with the same recipient and amount is a repeat and stays idempotent
/// (`WithdrawalQueueFile::enqueue` returns `Ok(false)`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RequestIdConflict {
    pub request_id: String,
    pub kind: ConflictKind,
}

impl std::fmt::Display for RequestIdConflict {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self.kind {
            ConflictKind::DifferentContent => write!(
                f,
                "requestId {} is already held by this service with a different recipient or amount; a requestId \
                 identifies exactly one withdrawal -- submit a new requestId for a new withdrawal",
                self.request_id
            ),
            ConflictKind::ContentNotRecorded => write!(
                f,
                "requestId {} is already held by this service, but its recipient and amount were not recorded \
                 (the entry predates content tracking), so this service cannot tell a repeat of that withdrawal \
                 from a different withdrawal reusing the id -- submit a new requestId",
                self.request_id
            ),
        }
    }
}

impl std::error::Error for RequestIdConflict {}

/// The `code` of a `WithdrawalConflictResponse`.
pub const REQUEST_ID_CONFLICT_CODE: &str = "request_id_conflict";

/// `POST /v1/play/withdrawals` -> HTTP 409 body for a `RequestIdConflict`. A superset of the plain
/// `{"error": "..."}` every other refusal of this service has: `code` is stable and meant for a
/// program, `error` for a person, `requestId` is the id that was refused. Deliberately carries
/// nothing about the withdrawal the id belongs to.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WithdrawalConflictResponse {
    pub error: String,
    pub code: &'static str,
    pub request_id: String,
}

impl From<&RequestIdConflict> for WithdrawalConflictResponse {
    fn from(conflict: &RequestIdConflict) -> Self {
        Self { error: conflict.to_string(), code: REQUEST_ID_CONFLICT_CODE, request_id: conflict.request_id.clone() }
    }
}

/// `GET /v1/play/withdrawals/{requestId}`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WithdrawalStatusResponse {
    pub request_id: String,
    /// `"queued" | "in_flight" | "confirmed" | "failed"` (task's FIXED
    /// INTERFACE) -- plus `"unknown"` for a `requestId` this service has
    /// never seen (never submitted, or a typo), same "additive, not part of
    /// the closed enum the task named" convention `oracle::types::
    /// OracleRecordStatusResponse` already uses for its own `"unknown"`.
    pub state: &'static str,
    pub coin_id: Option<String>,
    pub height: Option<u32>,
    pub reason: Option<String>,
}

/// `POST /v1/play/deposit-address`'s body. `Serialize` too, same reason as
/// `WithdrawalRequestDto`'s own doc comment.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DepositAddressRequestDto {
    pub player_index: u32,
}

/// `POST /v1/play/deposit-address`'s response.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DepositAddressResponse {
    pub address: String,
    pub inner_puzzle_hash: String,
    pub cat_puzzle_hash: String,
}

/// One element of `GET /v1/play/deposits`'s `deposits` array.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DepositDto {
    pub coin_id: String,
    pub player_index: u32,
    pub amount_mojos: u64,
    pub height: u32,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DepositsResponse {
    pub deposits: Vec<DepositDto>,
    pub tip_height: u32,
}
