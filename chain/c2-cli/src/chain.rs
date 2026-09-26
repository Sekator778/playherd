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
/// fund an issuance/offer/oracle-launch with. A thin wrapper over
/// [`find_funding_coin_excluding`] with an empty exclude set -- kept as its
/// own function so every existing caller (`c2-cli` itself, and `c2-svc`
/// before the incident below) is unaffected.
pub async fn find_funding_coin(
    client: &CoinsetClient,
    master_sk: &SecretKey,
    min_amount: u64,
    gap: u32,
) -> Result<(WalletKey, Coin)> {
    find_funding_coin_excluding(client, master_sk, min_amount, gap, &std::collections::HashSet::new()).await
}

/// Same as [`find_funding_coin`], but skips any candidate coin whose id is
/// in `exclude` -- task fix (`docs/research/chain_service.md` §15): `c2-svc`
/// runs more than one independent publisher (the oracle singleton publisher
/// and the PLAY withdrawal publisher) off the *same* operator wallet, and a
/// plain gap-scan for "the first unspent coin big enough" is deterministic,
/// so two publishers that each need a fee coin at the same time reliably
/// picked the *same* one -- whichever spend confirmed first left the other
/// permanently double-spending a now-spent input. `exclude` lets a caller
/// (`c2_svc::fee_allocator::FeeCoinAllocator`) keep every coin a
/// not-yet-confirmed bundle already depends on out of this scan, so a
/// second concurrent caller is offered a genuinely different coin (or a
/// clear "none available" error) instead of silently colliding.
pub async fn find_funding_coin_excluding(
    client: &CoinsetClient,
    master_sk: &SecretKey,
    min_amount: u64,
    gap: u32,
    exclude: &std::collections::HashSet<chia_protocol::Bytes32>,
) -> Result<(WalletKey, Coin)> {
    let coins = scan_unspent(client, master_sk, gap).await?;
    coins
        .into_iter()
        .find(|(_, r)| r.coin.amount >= min_amount && !exclude.contains(&r.coin.coin_id()))
        .map(|(wk, r)| (wk, r.coin))
        .ok_or_else(|| {
            anyhow::anyhow!(
                "no unspent, unreserved coin with amount >= {min_amount} mojos found in addresses \
                 0..{gap} (excluding {} coin(s) already reserved by an in-flight spend elsewhere in \
                 this process); fund the wallet from https://testnet11-faucet.chia.net/ first, or \
                 wait for the in-flight spend(s) to confirm",
                exclude.len()
            )
        })
}
