//! Abstracts every chain read/write the oracle publisher needs, so
//! `publisher.rs`'s queueing/retry state machine can be unit tested against
//! a fully in-memory fake instead of real coinset.org network calls (task
//! instruction: "queueing logic (unit with a fake chain client)"). The real
//! implementation (`CoinsetChainClient`) wraps `c2_cli::chain` (funding-coin
//! lookup, balance) plus `chia_sdk_coinset::CoinsetClient` directly (coin
//! records, `push_tx`, `get_fee_estimate`).
//!
//! `get_fee_estimate`'s request/response shape is **not from `chia-sdk-coinset`
//! (crate version 0.36.0 does not wrap it)** -- verified directly against
//! the real testnet11 endpoint today, see `docs/research/chain_service.md`
//! §4 for the exact `curl` output this struct matches.

use std::future::Future;

use anyhow::{Context, Result};
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, CoinSpend, SpendBundle};
use chia_sdk_coinset::{ChiaRpcClient, CoinsetClient, GetMempoolItemResponse};
use serde::Deserialize;

use c2_cli::chain::ADDRESS_GAP;
use c2_cli::keys::WalletKey;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CoinRecordInfo {
    pub exists: bool,
    pub confirmed_height: Option<u32>,
    pub spent: bool,
    pub spent_height: Option<u32>,
    /// The queried coin's own `parent_coin_info`/`puzzle_hash`/`amount` --
    /// `None` only when `exists` is `false`. Task fix (`docs/research/
    /// chain_service.md` §12, "make the chain the source of truth for the
    /// singleton position"): to build the `Proof` needed to spend some coin
    /// `X`, the singleton layer needs `X`'s *parent*'s own
    /// `parent_coin_info`/`amount` -- one `coin_record` call on
    /// `X.parent_coin_info` already has this for free, since coinset's
    /// `get_coin_record_by_name` always returns the full `coin` object
    /// alongside its confirmation/spent status (`chia-sdk-coinset`'s own
    /// `CoinRecord` struct has a `coin: Coin` field, not just the booleans
    /// this type used to expose) -- see `oracle::lineage::tip_lineage_proof`.
    /// Purely additive: every existing caller only ever asked about
    /// confirmation/spent status and still can.
    pub coin: Option<Coin>,
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

    /// Every unspent coin of `master_sk`'s wallet at address indices
    /// `0..ADDRESS_GAP`, each paired with the key that owns it -- plain
    /// standard (p2) coins only: the scan is by the wallet's own standard
    /// puzzle hashes, so the oracle singleton (singleton puzzle hash) and the
    /// PLAY treasury (CAT puzzle hash) can never appear in it.
    ///
    /// The one wallet read behind the fee-coin pool: `fee_allocator::
    /// FeeCoinAllocator::reserve` picks the smallest sufficient coin out of it
    /// (task fix `docs/research/chain_service.md` §15/§16), the fee-coin
    /// reserve counts and splits coins from it, and `Publisher::
    /// refresh_balance_snapshot` sums it into `operatorBalanceMojos`. (It
    /// replaced `find_funding_coin`, "the first coin that is big enough", and
    /// `balance`, which was this same scan summed -- 20 sequential coinset
    /// calls, `c2_cli::chain::ADDRESS_GAP`, so callers scan once and reuse.)
    fn unspent_coins(
        &self,
        master_sk: &SecretKey,
    ) -> impl Future<Output = Result<Vec<(WalletKey, Coin)>>> + Send;

    /// Whether any mempool item spends `coin_id` -- the node's own answer
    /// (`get_mempool_items_by_coin_name`). The fee-coin split uses it so that
    /// a process restarted while its split is still waiting in the mempool does
    /// not build a second split from the same coin: the pending state lives in
    /// the node, not in a file of ours.
    fn coin_in_mempool(&self, coin_id: Bytes32) -> impl Future<Output = Result<bool>> + Send;

    fn push_tx(&self, bundle: SpendBundle) -> impl Future<Output = Result<PushOutcome>> + Send;

    /// Whether `tx_id` (a `SpendBundle::name()` -- the node's own canonical
    /// hash, *not* this service's locally-displayed `bundle_id`, see
    /// `queue.rs::InFlightSpend::bundle_id`'s doc comment) is currently known
    /// to the mempool. Distinguishes "the node rejected this push outright,
    /// it never entered the mempool" (safe to rebuild with a higher fee,
    /// task instruction: "the singleton coin is still unspent") from "it's
    /// sitting in the mempool, just slow to confirm" (waited for, then
    /// replaced by a bundle the node's replacement rule accepts -- a superset
    /// of its coins at a fee at least 10,000,000 mojos higher, `mempool_rule.rs`,
    /// `publisher.rs::replace_in_mempool`, `docs/research/chain_service.md`
    /// §17.3). Only a bundle the mempool itself holds counts: one the node
    /// parked in its conflict cache (`push_tx` answers `PENDING`) is not "in
    /// the mempool".
    fn in_mempool(&self, tx_id: Bytes32) -> impl Future<Output = Result<bool>> + Send;

    /// The puzzle reveal + solution that spent `coin_id` (`None` if
    /// `coin_id` was never spent, or coinset simply has no record of it),
    /// for the startup/periodic lineage walk (`oracle/lineage.rs`, task fix
    /// `docs/research/chain_service.md` §11: "never trust the local queue
    /// file's bookkeeping alone -- reconstruct the authoritative confirmed
    /// map straight from the chain"). `height` narrows the lookup the same
    /// way `docs/research/chain_service.md` §9.2's `get_puzzle_and_solution`
    /// calls already do elsewhere in this codebase's tooling (the coin's own
    /// `spent_height`, from a prior `coin_record` call).
    fn puzzle_and_solution(
        &self,
        coin_id: Bytes32,
        height: Option<u32>,
    ) -> impl Future<Output = Result<Option<CoinSpend>>> + Send;

    /// The chain's current peak height (`get_blockchain_state`). Bounds the
    /// stale-index scan (`Publisher::scan_blocks_for_spends`, `docs/research/
    /// chain_service.md` §20) and is `GET /v1/play/deposits`'s `tipHeight`
    /// (it used to live on `play::chain_client::PlayChainClient`).
    fn peak_height(&self) -> impl Future<Output = Result<u32>> + Send;
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

    /// Exposes the underlying `chia_sdk_coinset::CoinsetClient` for
    /// extension traits in other modules (`play::chain_client::
    /// PlayChainClient`, D-6) that need RPC calls this trait doesn't wrap
    /// (batched puzzle-hash coin scans, blockchain tip height) -- purely
    /// additive, doesn't change this trait or its existing callers at all.
    pub(crate) fn client(&self) -> &CoinsetClient {
        &self.client
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
                coin: Some(record.coin),
            },
            None => CoinRecordInfo {
                exists: false,
                confirmed_height: None,
                spent: false,
                spent_height: None,
                coin: None,
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

    async fn unspent_coins(&self, master_sk: &SecretKey) -> Result<Vec<(WalletKey, Coin)>> {
        let found = c2_cli::chain::scan_unspent(&self.client, master_sk, ADDRESS_GAP).await?;
        Ok(found.into_iter().map(|(wallet_key, record)| (wallet_key, record.coin)).collect())
    }

    async fn coin_in_mempool(&self, coin_id: Bytes32) -> Result<bool> {
        // Verified live against `https://testnet11.api.coinset.org/
        // get_mempool_items_by_coin_name` (2026-10-01) for a coin id nobody
        // spends: `{"mempool_items": [], "success": true}` -- an empty list, not
        // an error, so "not in the mempool" needs no special-casing of `success`.
        let response = self
            .client
            .get_mempool_items_by_coin_name(coin_id)
            .await
            .context("get_mempool_items_by_coin_name")?;
        Ok(response.mempool_items.is_some_and(|items| !items.is_empty()))
    }

    async fn push_tx(&self, bundle: SpendBundle) -> Result<PushOutcome> {
        let response = self.client.push_tx(bundle).await.context("push_tx")?;
        Ok(PushOutcome {
            accepted: response.success,
            status: response.status,
            error: response.error,
        })
    }

    async fn in_mempool(&self, tx_id: Bytes32) -> Result<bool> {
        // Verified live against `https://testnet11.api.coinset.org/
        // get_mempool_item_by_tx_id` today (2026-09-25) for a tx_id this
        // node had genuinely never seen: `{"error":"Tx 0x... not in
        // mempool","structuredError":{"code":"TX_NOT_IN_MEMPOOL",...},
        // "success":false}` -- same "no hard error, just an absent record"
        // shape `coin_record` above already relies on, so this maps
        // `mempool_item: None` directly to `false` regardless of `success`.
        let response: GetMempoolItemResponse = self
            .client
            .get_mempool_item_by_tx_id(tx_id)
            .await
            .context("get_mempool_item_by_tx_id")?;
        Ok(response.mempool_item.is_some())
    }

    async fn puzzle_and_solution(&self, coin_id: Bytes32, height: Option<u32>) -> Result<Option<CoinSpend>> {
        let response = self
            .client
            .get_puzzle_and_solution(coin_id, height)
            .await
            .context("get_puzzle_and_solution")?;
        // Same "absent record, no hard error" shape as `coin_record`/
        // `in_mempool` above -- coinset answers a coin it has no
        // puzzle/solution for (never spent, or simply unknown to it) with
        // `coin_solution: null`, not a `success: false` error.
        Ok(response.coin_solution)
    }

    async fn peak_height(&self) -> Result<u32> {
        // Decoded by hand (`play::chain_client::parse_peak_height`): testnet11 intermittently
        // answers `peak.height` as a JSON float, which the typed `get_blockchain_state` wrapper
        // refuses whole (`docs/research/chain_service.md` §13).
        let body: serde_json::Value = self
            .client
            .make_post_request("get_blockchain_state", serde_json::json!({}))
            .await
            .context("get_blockchain_state")?;
        crate::play::chain_client::parse_peak_height(&body)
    }
}
