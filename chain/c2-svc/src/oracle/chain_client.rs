//! Abstracts every chain read/write the oracle publisher needs, so
//! `publisher.rs`'s queueing/retry state machine can be unit tested against
//! a fully in-memory fake instead of real coinset.org network calls (task
//! instruction: "queueing logic (unit with a fake chain client)"). The real
//! implementation (`CoinsetChainClient`) wraps `c2_cli::chain` (funding-coin
//! lookup, balance) plus `chia_sdk_coinset::CoinsetClient` directly (coin
//! records, `push_tx`, `get_fee_estimate`).
//!
//! `get_fee_estimate`'s request/response shape is **не из `chia-sdk-coinset`
//! (крейт версии 0.36.0 его не оборачивает)** -- verified directly against
//! the real testnet11 endpoint today, see `docs/research/chain_service.md`
//! §4 for the exact `curl` output this struct matches.

use std::future::Future;

use anyhow::{Context, Result};
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, SpendBundle};
use chia_sdk_coinset::{ChiaRpcClient, CoinsetClient};
use serde::Deserialize;

use c2_cli::chain::ADDRESS_GAP;
use c2_cli::keys::WalletKey;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CoinRecordInfo {
    pub exists: bool,
    pub confirmed_height: Option<u32>,
    pub spent: bool,
    pub spent_height: Option<u32>,
}

#[derive(Debug, Clone)]
pub struct PushOutcome {
    pub accepted: bool,
    /// coinset's `status` field on success (e.g. `"SUCCESS"`/`"PENDING"`).
    pub status: Option<String>,
    pub error: Option<String>,
}

/// Everything `publisher.rs` needs from the chain. Uses return-position
/// `impl Future` (RPITIT, stable since Rust 1.75) rather than `async-trait`
/// -- the same style `chia_sdk_coinset::ChiaRpcClient` itself already uses
/// (`chia-sdk-coinset-0.36.0/src/chia_rpc_client.rs`), so no new pattern is
/// introduced into the dependency stack. Not object-safe by design: the
/// publisher is generic over `C: ChainClient`, never `dyn ChainClient`.
pub trait ChainClient: Send + Sync {
    fn coin_record(&self, coin_id: Bytes32) -> impl Future<Output = Result<CoinRecordInfo>> + Send;

    /// Total fee, in mojos, coinset currently estimates for a spend of this
    /// CLVM cost to confirm within `target_time_secs`.
    fn fee_estimate_mojos(&self, cost: u64, target_time_secs: u32) -> impl Future<Output = Result<u64>> + Send;

    /// Finds one unspent coin of `master_sk`'s wallet with amount >=
    /// `min_amount` (used both to fund the fee-only coin, and for
    /// `GET /v1/oracle/status`'s `operatorBalanceMojos` -- see `balance`).
    fn find_funding_coin(
        &self,
        master_sk: &SecretKey,
        min_amount: u64,
    ) -> impl Future<Output = Result<(WalletKey, chia_protocol::Coin)>> + Send;

    fn balance(&self, master_sk: &SecretKey) -> impl Future<Output = Result<u64>> + Send;

    fn push_tx(&self, bundle: SpendBundle) -> impl Future<Output = Result<PushOutcome>> + Send;
}

#[derive(Debug, Clone)]
pub struct CoinsetChainClient {
    client: CoinsetClient,
}

impl CoinsetChainClient {
    pub fn new(base_url: String) -> Self {
        Self {
            client: CoinsetClient::new(base_url),
        }
    }
}

/// `get_fee_estimate`'s response, verified live against
/// `https://testnet11.api.coinset.org/get_fee_estimate` 2026-09-25 (see
/// `docs/research/chain_service.md` §4). Only the fields this service uses
/// are modeled; the rest of the real response is ignored by serde's default
/// "unknown fields are dropped" behavior.
#[derive(Debug, Deserialize)]
struct FeeEstimateResponse {
    success: bool,
    #[serde(default)]
    error: Option<String>,
    /// One total-mojos estimate per requested `target_times` entry, same
    /// order. This service always requests exactly one target time, so
    /// `estimates[0]` is used.
    #[serde(default)]
    estimates: Vec<u64>,
}

impl ChainClient for CoinsetChainClient {
    async fn coin_record(&self, coin_id: Bytes32) -> Result<CoinRecordInfo> {
        let response = self
            .client
            .get_coin_record_by_name(coin_id)
            .await
            .context("get_coin_record_by_name")?;
        // coinset answers a coin id it has never seen with `coin_record:
        // null` (no hard error) -- that's the normal "not broadcast/not
        // confirmed yet" case this polling loop expects to see repeatedly,
        // so it maps directly to `exists: false` regardless of `error`/
        // `success`, rather than treating it as a failure.
        Ok(match response.coin_record {
            Some(record) => CoinRecordInfo {
                exists: true,
                confirmed_height: Some(record.confirmed_block_index),
                spent: record.spent,
                spent_height: if record.spent {
                    Some(record.spent_block_index)
                } else {
                    None
                },
            },
            None => CoinRecordInfo {
                exists: false,
                confirmed_height: None,
                spent: false,
                spent_height: None,
            },
        })
    }

    async fn fee_estimate_mojos(&self, cost: u64, target_time_secs: u32) -> Result<u64> {
        let response: FeeEstimateResponse = self
            .client
            .make_post_request(
                "get_fee_estimate",
                serde_json::json!({ "cost": cost, "target_times": [target_time_secs] }),
            )
            .await
            .context("get_fee_estimate")?;
        if !response.success {
            anyhow::bail!(
                "get_fee_estimate failed: {}",
                response.error.unwrap_or_else(|| "(no error message)".to_string())
            );
        }
        response
            .estimates
            .first()
            .copied()
            .context("get_fee_estimate: empty estimates array")
    }

    async fn find_funding_coin(
        &self,
        master_sk: &SecretKey,
        min_amount: u64,
    ) -> Result<(WalletKey, chia_protocol::Coin)> {
        c2_cli::chain::find_funding_coin(&self.client, master_sk, min_amount, ADDRESS_GAP).await
    }

    async fn balance(&self, master_sk: &SecretKey) -> Result<u64> {
        c2_cli::chain::balance(&self.client, master_sk, ADDRESS_GAP).await
    }

    async fn push_tx(&self, bundle: SpendBundle) -> Result<PushOutcome> {
        let response = self.client.push_tx(bundle).await.context("push_tx")?;
        Ok(PushOutcome {
            accepted: response.success,
            status: response.status,
            error: response.error,
        })
    }
}
