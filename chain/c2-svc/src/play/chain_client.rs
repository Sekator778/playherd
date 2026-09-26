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

    /// The chain's current peak height (`GET /v1/play/deposits`'s
    /// `tipHeight`).
    fn peak_height(&self) -> impl Future<Output = Result<u32>> + Send;
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

    async fn peak_height(&self) -> Result<u32> {
        let response = self.client().get_blockchain_state().await.context("get_blockchain_state")?;
        if !response.success {
            bail!(
                "get_blockchain_state failed: {}",
                response.error.unwrap_or_else(|| "(no error message)".to_string())
            );
        }
        let state = response.blockchain_state.context("get_blockchain_state: missing blockchain_state")?;
        Ok(state.peak.height)
    }
}
