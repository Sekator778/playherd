//! DTOs and the internal record type for FIXED INTERFACE (3)'s
//! `POST /v1/oracle/records` / `GET /v1/oracle/status`, and the CLVM
//! announcement message they turn into (task instruction: "Oracle
//! announcement per record = `CREATE_PUZZLE_ANNOUNCEMENT` with message =
//! CLVM list `(seq ts priceCents resultsHash sourceFlag sourceTs)`,
//! `sourceFlag` 0=live 1=replay").
//!
//! **Record format v2** (`docs/research/markets_architecture.md` §7,
//! `docs/research/chain_service.md` §16): a record also carries `asset`, the
//! index of its market in the public registry (BTC = 0, the only asset
//! before v2). The announcement gets a seventh element `asset` only when
//! `asset > 0`, so every BTC announcement stays byte-identical to the 6-element
//! history already on chain; decoders read both shapes (`asset` defaults to 0).

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
    /// Index of the record's market in the public registry (record v2).
    /// Absent on the wire (an older server) means 0 = BTC, the only market
    /// there was.
    #[serde(default)]
    pub asset: u32,
}

// Serialize is additive (task item 5, contract test Java<->Rust): production code only ever
// deserializes this off the wire, but tests/contract_fixtures.rs needs to serialize a real,
// constructed instance to write it out as a shared JSON fixture.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OracleRecordsRequest {
    pub records: Vec<OracleRecordDto>,
}

/// FIXED INTERFACE (b): `queued` is `acceptedSeqs.len()` (kept alongside it
/// for a caller that only wants the count), `acceptedSeqs` is exactly the
/// `seq`s newly accepted this call -- a `seq` already known
/// confirmed/in-flight/pending is silently omitted (idempotent re-POST, not
/// an error), so this can be shorter than `req.records.len()`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OracleRecordsResponse {
    pub queued: usize,
    pub accepted_seqs: Vec<u64>,
}

/// `GET /v1/oracle/records/{seq}` (FIXED INTERFACE (b)).
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OracleRecordStatusResponse {
    pub seq: u64,
    /// `"queued" | "in_flight" | "confirmed" | "unknown"`.
    pub state: &'static str,
    pub height: Option<u32>,
    pub coin_id: Option<String>,
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
    /// Record v2: market index (BTC = 0). `#[serde(default)]` is mandatory,
    /// not cosmetic: the live queue file already holds records without this
    /// field (`pending`, `in_flight`, `conflicts`), and `QueueFile::load`
    /// fails the whole file on any parse error -- which would make `enqueue`,
    /// `tick`, `status` and `health_check` all fail right after an upgrade.
    #[serde(default)]
    pub asset: u32,
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
            asset: dto.asset,
        })
    }
}

/// The record/announcement format this build understands and publishes,
/// reported as `GET /v1/oracle/status`'s `recordFormat`. 1 = the original 6-field
/// record (no `asset` field exists at all); 2 = record v2 (`asset`, announced
/// as an optional 7th element). A server that wants to send a record with
/// `asset > 0` must first see `recordFormat >= 2` here: an older chainsvc would
/// drop the unknown JSON key and publish the record as BTC, silently
/// (`OracleRecordDto` has no `deny_unknown_fields`).
pub const RECORD_FORMAT: u32 = 2;

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
    /// Task fix (`docs/research/chain_service.md` §11): `seq`s the
    /// chain-lineage walk found already confirmed on chain with content
    /// that disagrees with what this service has locally queued or
    /// in-flight -- never silently dropped nor republished. Ascending,
    /// empty when there are none. Full detail (local vs. chain content) for
    /// one of these is `GET /v1/oracle/records/{seq}` (`state: "conflict"`).
    pub conflicts: Vec<u64>,
    /// Record v2 (`docs/research/markets_architecture.md` §7 item 3): the highest
    /// `seq` known to this service in ANY state -- accepted, confirmed
    /// (including the per-record `confirmed` map and the chain-lineage
    /// reconciliation that fills it), conflicting, pending or in flight; 0 when
    /// nothing is known. A server whose own counter was lost (empty database
    /// against a chainsvc that already published 1..N) raises it to this value
    /// before it hands out the next `seq` -- `lastConfirmedSeq` alone is not
    /// enough, pending and in-flight records can be higher.
    pub max_known_seq: u64,
    /// See [`RECORD_FORMAT`]. Always 2 from this build on.
    pub record_format: u32,
    /// Fee-coin reserve (`docs/research/chain_service.md` §16): how many
    /// unreserved standard coins of at least `C2_FEE_COIN_MOJOS` the operator
    /// wallet holds right now, from the last wallet scan (refreshed every
    /// `publisher_tick`, never fetched inside this request -- same rule as
    /// `operatorBalanceMojos`). `None` until the first scan has completed or
    /// when the last one failed. The maintenance step splits a coin when this
    /// falls below `C2_FEE_COINS_MIN`.
    pub fee_coins_free: Option<u32>,
    /// Sum, in mojos, of those `feeCoinsFree` coins -- a decimal string, like
    /// `operatorBalanceMojos`, so a u64 amount never loses precision in a JSON
    /// number. `None` exactly when `feeCoinsFree` is.
    pub fee_coins_free_mojos: Option<String>,
}
