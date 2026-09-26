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
#[derive(Debug, Clone, Deserialize)]
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
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WithdrawalAcceptedResponse {
    pub accepted: bool,
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

/// `POST /v1/play/deposit-address`'s body.
#[derive(Debug, Clone, Deserialize)]
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
