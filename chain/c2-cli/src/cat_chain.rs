//! Finds a wallet's own CAT coin(s) of a given asset id on chain, and
//! reconstructs a spendable `Cat` (coin + lineage proof + `CatInfo`) from its
//! parent's already-confirmed spend -- the general case `state.rs::
//! PlayCoinState` doesn't cover: that cache only ever remembers the *one*
//! CAT coin this same process's own `issue-play` just minted. A coin this
//! process never minted itself (e.g. one another wallet just sent it, the
//! `send-play`/`play-balance` case) has to be discovered and its lineage
//! proof reconstructed from the chain, the same thing every real CAT wallet
//! does when it syncs.
//!
//! Lineage-proof reconstruction: `chia-sdk-driver`'s `Cat::parse_children`
//! decodes a *parent* coin's already-confirmed puzzle+solution into every CAT
//! child it created, each already carrying the correct `LineageProof`
//! (`Cat::child_lineage_proof`) -- so the only chain calls needed are the
//! parent's own coin record (to learn its `spent_block_index`, for
//! `get_puzzle_and_solution`) and that puzzle/solution itself.

use anyhow::{Context, Result, bail};
use chia_bls::SecretKey;
use chia_protocol::Bytes32;
use chia_puzzle_types::cat::CatArgs;
use chia_sdk_coinset::{ChiaRpcClient, CoinsetClient};
use chia_sdk_driver::{Cat, Puzzle};
use clvmr::{Allocator, serde::node_from_bytes};

use crate::keys::{WalletKey, derive_wallet_key};

/// The CAT-layer puzzle hash a coin must have to be a deposit of `asset_id`
/// to `inner_puzzle_hash` -- `CatArgs::curry_tree_hash(asset_id,
/// inner_puzzle_hash)`. Same formula `chain/c2-svc`'s deposit-address /
/// deposit-scan endpoints use (`docs/research/chain_service.md` §13).
pub fn cat_puzzle_hash(asset_id: Bytes32, inner_puzzle_hash: Bytes32) -> Bytes32 {
    CatArgs::curry_tree_hash(asset_id, inner_puzzle_hash.into()).into()
}

/// Reconstructs a spendable `Cat` for `coin_id` (expected to be an unspent
/// CAT coin of `asset_id`, owned by `owner_puzzle_hash`) by looking up its
/// parent's on-chain spend and parsing out the matching child.
pub async fn cat_from_coin_id(
    client: &CoinsetClient,
    asset_id: Bytes32,
    owner_puzzle_hash: Bytes32,
    coin_id: Bytes32,
) -> Result<Cat> {
    let record = client
        .get_coin_record_by_name(coin_id)
        .await
        .context("get_coin_record_by_name")?
        .coin_record
        .with_context(|| format!("coin {coin_id} has no coin record"))?;

    let parent_record = client
        .get_coin_record_by_name(record.coin.parent_coin_info)
        .await
        .context("get_coin_record_by_name (parent)")?
        .coin_record
        .with_context(|| format!("parent coin {} has no coin record", record.coin.parent_coin_info))?;
    if !parent_record.spent {
        bail!(
            "parent coin {} is not spent yet -- coin {coin_id} cannot be a real CAT coin",
            record.coin.parent_coin_info
        );
    }

    let parent_spend = client
        .get_puzzle_and_solution(record.coin.parent_coin_info, Some(parent_record.spent_block_index))
        .await
        .context("get_puzzle_and_solution (parent)")?
        .coin_solution
        .with_context(|| format!("no puzzle/solution for parent coin {}", record.coin.parent_coin_info))?;

    let mut allocator = Allocator::new();
    let puzzle_ptr =
        node_from_bytes(&mut allocator, parent_spend.puzzle_reveal.as_ref()).context("parsing parent puzzle_reveal as CLVM")?;
    let solution_ptr =
        node_from_bytes(&mut allocator, parent_spend.solution.as_ref()).context("parsing parent solution as CLVM")?;
    let puzzle = Puzzle::parse(&allocator, puzzle_ptr);

    let children = Cat::parse_children(&mut allocator, parent_spend.coin, puzzle, solution_ptr)
        .context("parsing CAT children from parent spend")?
        .with_context(|| format!("parent coin {} spend is not a CAT spend", record.coin.parent_coin_info))?;

    let cat = children
        .into_iter()
        .find(|c| c.coin.coin_id() == coin_id)
        .with_context(|| format!("coin {coin_id} not found among its parent's CAT children"))?;

    if cat.info.asset_id != asset_id {
        bail!("coin {coin_id} is a CAT of asset id {}, expected {asset_id}", cat.info.asset_id);
    }
    if cat.info.p2_puzzle_hash != owner_puzzle_hash {
        bail!("coin {coin_id}'s CAT p2 puzzle hash does not match the expected owner");
    }

    Ok(cat)
}

/// Scans address indices `0..gap` of `master_sk`'s wallet for an unspent CAT
/// coin of `asset_id` with amount >= `min_amount`, and reconstructs it as a
/// spendable `Cat` (`cat_from_coin_id`). Mirrors `chain::find_funding_coin`'s
/// gap-scan shape, just at each index's CAT puzzle hash instead of its plain
/// p2 puzzle hash.
pub async fn find_cat_coin(
    client: &CoinsetClient,
    master_sk: &SecretKey,
    asset_id: Bytes32,
    min_amount: u64,
    gap: u32,
) -> Result<(WalletKey, Cat)> {
    for index in 0..gap {
        let wallet_key = derive_wallet_key(master_sk, index);
        let puzzle_hash = cat_puzzle_hash(asset_id, wallet_key.puzzle_hash);
        let response = client
            .get_coin_records_by_puzzle_hash(puzzle_hash, None, None, Some(false), None)
            .await
            .with_context(|| format!("get_coin_records_by_puzzle_hash (CAT, index {index})"))?;
        if let Some(err) = &response.error {
            bail!("coinset error for index {index}: {err}");
        }
        for record in response.coin_records.into_iter().flatten() {
            if record.spent || record.coin.amount < min_amount {
                continue;
            }
            let cat = cat_from_coin_id(client, asset_id, wallet_key.puzzle_hash, record.coin.coin_id()).await?;
            return Ok((derive_wallet_key(master_sk, index), cat));
        }
    }
    bail!("no unspent CAT coin (asset id {asset_id}) with amount >= {min_amount} mojos found in addresses 0..{gap}")
}

/// Sums unspent CAT-coin amounts of `asset_id` across address indices
/// `0..gap` (`c2 play-balance`). Mirrors `chain::balance`'s shape.
pub async fn cat_balance(client: &CoinsetClient, master_sk: &SecretKey, asset_id: Bytes32, gap: u32) -> Result<u64> {
    let mut total = 0u64;
    for index in 0..gap {
        let wallet_key = derive_wallet_key(master_sk, index);
        let puzzle_hash = cat_puzzle_hash(asset_id, wallet_key.puzzle_hash);
        let response = client
            .get_coin_records_by_puzzle_hash(puzzle_hash, None, None, Some(false), None)
            .await
            .with_context(|| format!("get_coin_records_by_puzzle_hash (CAT, index {index})"))?;
        if let Some(err) = &response.error {
            bail!("coinset error for index {index}: {err}");
        }
        for record in response.coin_records.into_iter().flatten() {
            if !record.spent {
                total += record.coin.amount;
            }
        }
    }
    Ok(total)
}
