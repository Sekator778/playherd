//! Read-only chain access via coinset.org, and funding-coin lookup for the
//! spending builders.
//!
//! Route: `chia_sdk_coinset::CoinsetClient::testnet11()` (public HTTPS API,
//! no certificates, no local node) -- the route recommended in
//! `docs/research/week1_chain_access.md` §0/§3 (`CoinsetClient::testnet11()`,
//! `chia-sdk-coinset-0.36.0/src/coinset_client.rs:20-22`).

use anyhow::{Context, Result, bail};
use chia_bls::SecretKey;
use chia_protocol::Coin;
use chia_sdk_coinset::{ChiaRpcClient, CoinRecord, CoinsetClient};

use crate::keys::{WalletKey, derive_wallet_key};

/// How many address indices to scan for coins. New wallets in this CLI only
/// ever use index 0 today (see `docs/research/week1_testnet_cli.md`), but a
/// small gap makes `balance`/funding lookups correct even after a few
/// addresses have been used, mirroring (at a much smaller scale) the "gap
/// limit" every HD Chia wallet scans past the last used index.
pub const ADDRESS_GAP: u32 = 20;

pub fn client() -> CoinsetClient {
    CoinsetClient::testnet11()
}

/// Unspent coin records for address indices `0..gap`, each paired with the
/// [`WalletKey`] that owns it.
pub async fn scan_unspent(
    client: &CoinsetClient,
    master_sk: &SecretKey,
    gap: u32,
) -> Result<Vec<(WalletKey, CoinRecord)>> {
    let mut found = Vec::new();
    for index in 0..gap {
        let wallet_key = derive_wallet_key(master_sk, index);
        let response = client
            .get_coin_records_by_puzzle_hash(wallet_key.puzzle_hash, None, None, Some(false), None)
            .await
            .with_context(|| format!("get_coin_records_by_puzzle_hash (index {index})"))?;
        if let Some(err) = &response.error {
            bail!("coinset error for index {index}: {err}");
        }
        for record in response.coin_records.into_iter().flatten() {
            if !record.spent {
                found.push((
                    derive_wallet_key(master_sk, index), // fresh copy, WalletKey is not Clone
                    record,
                ));
            }
        }
    }
    Ok(found)
}

pub async fn balance(client: &CoinsetClient, master_sk: &SecretKey, gap: u32) -> Result<u64> {
    let coins = scan_unspent(client, master_sk, gap).await?;
    Ok(coins.iter().map(|(_, r)| r.coin.amount).sum())
}

/// Finds one unspent coin whose amount is at least `min_amount`, scanning
/// address indices `0..gap`. Used by the spending builders to pick a coin to
/// fund an issuance/offer/oracle-launch with.
pub async fn find_funding_coin(
    client: &CoinsetClient,
    master_sk: &SecretKey,
    min_amount: u64,
    gap: u32,
) -> Result<(WalletKey, Coin)> {
    let coins = scan_unspent(client, master_sk, gap).await?;
    coins
        .into_iter()
        .find(|(_, r)| r.coin.amount >= min_amount)
        .map(|(wk, r)| (wk, r.coin))
        .ok_or_else(|| {
            anyhow::anyhow!(
                "no unspent coin with amount >= {min_amount} mojos found in addresses 0..{gap}; \
                 fund the wallet from https://testnet11-faucet.chia.net/ first"
            )
        })
}
