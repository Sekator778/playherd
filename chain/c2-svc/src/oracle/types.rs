//! DTOs and the internal record type for FIXED INTERFACE (3)'s
//! `POST /v1/oracle/records` / `GET /v1/oracle/status`, and the CLVM
//! announcement message they turn into (task instruction: "Oracle
//! announcement per record = `CREATE_PUZZLE_ANNOUNCEMENT` with message =
//! CLVM list `(seq ts priceCents resultsHash sourceFlag sourceTs)`,
//! `sourceFlag` 0=live 1=replay").

use chia_protocol::Bytes32;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    Live,
    Replay,
}

impl Source {
    /// `sourceFlag` in the CLVM announcement message (task instruction).
    pub fn flag(self) -> i64 {
        match self {
            Source::Live => 0,
            Source::Replay => 1,
        }
    }
}

/// Wire shape of one element of `POST /v1/oracle/records`'s `records` array
/// (FIXED INTERFACE (3), exact field names/casing).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OracleRecordDto {
    pub seq: u64,
    /// Unix seconds.
    pub ts: i64,
    pub price_cents: i64,
    /// `0x` + 32 bytes hex (a settled/cancelled round's `resultsHash`, Р-41).
    pub results_hash: String,
    pub source: Source,
    /// Unix seconds.
    pub source_ts: i64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OracleRecordsRequest {
    pub records: Vec<OracleRecordDto>,
}

#[derive(Debug, Clone, Serialize)]
pub struct OracleRecordsResponse {
    pub queued: usize,
}

/// Validated, internal record shape -- what actually goes into the CLVM
/// announcement message and the durable queue file. `results_hash` is
/// already a `Bytes32` (not a hex string) so it can be handed straight to
/// `clvm_traits::clvm_list!` (`Bytes32`/`BytesImpl<32>` has a `ToClvm` impl,
/// see `docs/research/chain_service.md` §2). `chia_protocol::Bytes32`
/// already implements `Serialize`/`Deserialize` (as `0x`-hex, the same
/// encoding every other `Bytes32` field in this codebase's JSON files uses,
/// e.g. `c2_cli::state::OracleState::launcher_id`), so this struct derives
/// both directly -- no separate hex (de)serialization needed for the queue
/// file.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
pub struct OracleRecord {
    pub seq: u64,
    pub ts: i64,
    pub price_cents: i64,
    pub results_hash: Bytes32,
    /// 0 = live, 1 = replay (`Source::flag`).
    pub source_flag: i64,
    pub source_ts: i64,
}

impl OracleRecord {
    pub fn from_dto(dto: &OracleRecordDto) -> Result<Self, String> {
        let stripped = dto
            .results_hash
            .strip_prefix("0x")
            .or_else(|| dto.results_hash.strip_prefix("0X"))
            .unwrap_or(&dto.results_hash);
        let bytes = hex::decode(stripped)
            .map_err(|e| format!("record seq {}: resultsHash: invalid hex ({e})", dto.seq))?;
        let array: [u8; 32] = bytes.as_slice().try_into().map_err(|_| {
            format!(
                "record seq {}: resultsHash: expected 32 bytes, got {}",
                dto.seq,
                bytes.len()
            )
        })?;
        Ok(Self {
            seq: dto.seq,
            ts: dto.ts,
            price_cents: dto.price_cents,
            results_hash: Bytes32::from(array),
            source_flag: dto.source.flag(),
            source_ts: dto.source_ts,
        })
    }
}

/// `GET /v1/oracle/status` (FIXED INTERFACE (3); also the shape
/// `ServerInfoDto.oracle` reuses field-for-field).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OracleStatusResponse {
    pub launcher_id: String,
    pub network: &'static str,
    pub last_confirmed_seq: Option<u64>,
    pub last_confirmed_height: Option<u32>,
    pub pending_records: usize,
    pub in_flight_bundle_id: Option<String>,
    pub operator_balance_mojos: Option<String>,
    /// **предложение** (extends the FIXED INTERFACE (3) status shape the
    /// same way `inFlightBundleId`/`operatorBalanceMojos` already do, see
    /// `chain_service.md` §4 -- `chainsvc` is an internal service, not part
    /// of the public web contract). `Some` once the in-flight spend has
    /// exhausted `max_retry_attempts` on its current bundle and either the
    /// fee is already at `C2_MAX_FEE_MOJOS` or the rejection reason isn't
    /// fee-related (task instruction: "clear 'operator attention' status
    /// with the reason"), `None` otherwise -- including while a fee-bump
    /// rebuild is still automatically in progress, see
    /// `publisher.rs::stuck_reason`.
    pub stuck_reason: Option<String>,
}
