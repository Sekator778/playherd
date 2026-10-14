//! Extends the oracle publisher's `ChainClient` (`oracle/chain_client.rs`)
//! with the two chain reads the PLAY withdraw/deposit endpoints need (Р-6,
//! decision Р-53 2026-09-26) that it doesn't already provide: a batched
//! puzzle-hash coin scan (deposit detection across every issued deposit
//! address) and the current chain tip height (`GET /v1/play/deposits`'s
//! `tipHeight`).
//!
//! A separate supertrait rather than new methods added directly to
//! `oracle::chain_client::ChainClient` -- that trait's existing fake
//! (`chain/c2-svc/tests/oracle_queue.rs`'s `FakeChainClient`, exercised by
//! 73 tests, `docs/research/chain_service.md` §11/§12) would otherwise have
//! to grow methods none of those tests use; this way that file is untouched
//! and the PLAY withdraw/deposit tests (`tests/play_withdrawals.rs`,
//! `tests/play_deposits.rs`) get their own fake instead.

use std::future::Future;

use anyhow::{Context, Result, bail};
use chia_protocol::Bytes32;
use chia_sdk_coinset::ChiaRpcClient;

use crate::oracle::chain_client::{ChainClient, CoinsetChainClient};

/// One coin coinset reported for a puzzle-hash scan -- deliberately not
/// `oracle::chain_client::CoinRecordInfo` (that type answers "is *this one*
/// known coin id confirmed/spent", keyed by a coin id the caller already
/// has; this answers "which coins exist at any of *these* puzzle hashes",
/// keyed by puzzle hash, which is what deposit detection needs).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PuzzleHashCoinRecord {
    pub coin_id: Bytes32,
    pub puzzle_hash: Bytes32,
    pub amount: u64,
    pub confirmed_height: u32,
    pub spent: bool,
}

pub trait PlayChainClient: ChainClient {
    /// Every confirmed coin (spent or not -- task instruction: "only
    /// confirmed, unspent-or-spent CAT coins") at any of `puzzle_hashes`,
    /// confirmed at or after `start_height` (`None` = from genesis). Returns
    /// nothing (without a network call) for an empty `puzzle_hashes` list --
    /// `GET /v1/play/deposits` before any `POST /v1/play/deposit-address`
    /// has ever been called.
    fn coin_records_by_puzzle_hashes(
        &self,
        puzzle_hashes: Vec<Bytes32>,
        start_height: Option<u32>,
    ) -> impl Future<Output = Result<Vec<PuzzleHashCoinRecord>>> + Send;
}

impl PlayChainClient for CoinsetChainClient {
    async fn coin_records_by_puzzle_hashes(
        &self,
        puzzle_hashes: Vec<Bytes32>,
        start_height: Option<u32>,
    ) -> Result<Vec<PuzzleHashCoinRecord>> {
        if puzzle_hashes.is_empty() {
            return Ok(Vec::new());
        }
        // `include_spent_coins: Some(true)` -- task instruction: "only
        // confirmed, unspent-OR-SPENT CAT coins" (a deposit already spent by
        // the time the server polls for it must still be reported once).
        let response = self
            .client()
            .get_coin_records_by_puzzle_hashes(puzzle_hashes, start_height, None, Some(true), None)
            .await
            .context("get_coin_records_by_puzzle_hashes")?;
        if let Some(err) = &response.error {
            bail!("get_coin_records_by_puzzle_hashes failed: {err}");
        }
        Ok(response
            .coin_records
            .into_iter()
            .flatten()
            .map(|r| PuzzleHashCoinRecord {
                coin_id: r.coin.coin_id(),
                puzzle_hash: r.coin.puzzle_hash,
                amount: r.coin.amount,
                confirmed_height: r.confirmed_block_index,
                spent: r.spent,
            })
            .collect())
    }
}

/// Decodes only `peak.height` out of a raw `get_blockchain_state` response
/// body, for `ChainClient::peak_height` (`oracle/chain_client.rs`; the
/// method lived on `PlayChainClient` until the stale-index scan of
/// `docs/research/chain_service.md` §20 needed it on the oracle side too).
/// Task fix (`docs/research/chain_service.md` §13): testnet11 intermittently
/// answers `peak.height` as a JSON *float* (e.g. `0.0`) instead of an
/// integer -- `chia_sdk_coinset::get_blockchain_state()`'s strongly-typed
/// `BlockchainState` (whose `peak: chia_protocol::BlockRecord` declares
/// `height: u32`) fails to deserialize the *entire* response the moment that
/// happens, turning `GET /v1/play/deposits` into a 500. So the raw JSON body
/// is decoded by hand (same "only model the fields this service actually
/// uses" precedent `oracle::chain_client::FeeEstimateResponse` already sets
/// for `get_fee_estimate`), tolerant of `height` being a float, and of every
/// other field's shape (never even named here). Kept as a standalone,
/// synchronous function (no network) so it's directly unit-testable against
/// a recorded body, without a mock HTTP server.
pub(crate) fn parse_peak_height(body: &serde_json::Value) -> Result<u32> {
    if body.get("success").and_then(serde_json::Value::as_bool) != Some(true) {
        bail!(
            "get_blockchain_state failed: {}",
            body.get("error").and_then(serde_json::Value::as_str).unwrap_or("(no error message)")
        );
    }
    let height = body
        .pointer("/blockchain_state/peak/height")
        .context("get_blockchain_state: missing blockchain_state.peak.height")?;
    height
        .as_u64()
        .or_else(|| height.as_f64().map(|f| f as u64))
        .and_then(|h| u32::try_from(h).ok())
        .with_context(|| format!("get_blockchain_state: peak.height is not a valid non-negative height: {height}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Recorded (trimmed) shape reproducing the live testnet11 response that
    /// broke `GET /v1/play/deposits`: `peak.height` as a JSON float. Only the
    /// fields `parse_peak_height` touches are included -- it must ignore
    /// everything else regardless of shape, which this also exercises (an
    /// extra, irrelevant field with a shape `BlockchainState` wouldn't even
    /// accept, `sync_progress_height` as a string here).
    const RECORDED_FLOAT_HEIGHT_BODY: &str = r#"{
        "success": true,
        "blockchain_state": {
            "peak": { "height": 0.0, "weight": 12345 },
            "sync": { "sync_progress_height": "not-a-number" }
        }
    }"#;

    #[test]
    fn tolerates_a_float_height_from_testnet11() {
        let body: serde_json::Value = serde_json::from_str(RECORDED_FLOAT_HEIGHT_BODY).unwrap();
        assert_eq!(parse_peak_height(&body).unwrap(), 0);
    }

    #[test]
    fn still_works_for_an_ordinary_integer_height() {
        let body: serde_json::Value =
            serde_json::from_str(r#"{"success": true, "blockchain_state": {"peak": {"height": 4737632}}}"#).unwrap();
        assert_eq!(parse_peak_height(&body).unwrap(), 4_737_632);
    }

    #[test]
    fn surfaces_a_get_blockchain_state_failure() {
        let body: serde_json::Value = serde_json::from_str(r#"{"success": false, "error": "boom"}"#).unwrap();
        let err = parse_peak_height(&body).unwrap_err();
        assert!(err.to_string().contains("boom"));
    }

    #[test]
    fn rejects_a_missing_peak_height() {
        let body: serde_json::Value = serde_json::from_str(r#"{"success": true, "blockchain_state": {}}"#).unwrap();
        assert!(parse_peak_height(&body).is_err());
    }
}
