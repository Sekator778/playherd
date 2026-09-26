//! One-off operational tool (live-verification reconciliation, 2026-09-26): re-derives the
//! operator's current on-chain PLAY treasury coin (via the same gap-scan + lineage
//! reconstruction `send-play`/`play-balance` already use) and refreshes
//! `~/.c2/testnet11/operator.play.json` to match — read-only against chain, no spend, no key
//! material printed. Needed because `operator.play.json` was stale (last written by
//! `issue-play`'s genesis mint) after a later `c2 send-play --name operator` funded the
//! counterparty wallet, which never updates that cache (see `cmd_send_play` — it has no
//! `save_play_coin` call, unlike `cmd_issue_play`). `chain/c2-svc`'s withdrawal publisher trusts
//! this file as-is and has no chain-lineage self-heal for it (unlike the oracle publisher's
//! §11/§12 fixes), so a stale cache here permanently stalls every withdrawal batch.
use anyhow::{Context, Result};
use bip39::Mnemonic;
use c2_cli::{cat_chain, chain, keys, keystore, state};

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let name = args.get(1).cloned().unwrap_or_else(|| "operator".to_string());
    let asset_id_hex = args.get(2).cloned().context("usage: reconcile_play <wallet_name> <asset_id_hex>")?;
    // parse_asset_id is private to main.rs; reimplement the same trivial hex decode here.
    let asset_id: chia_protocol::Bytes32 = {
        let bytes = hex::decode(asset_id_hex.trim_start_matches("0x")).context("asset id hex")?;
        chia_protocol::Bytes32::try_from(bytes.as_slice()).context("asset id must be 32 bytes")?
    };
    let key_file = keystore::load(&name)?;
    let mnemonic = Mnemonic::parse(&key_file.mnemonic).context("parsing stored mnemonic")?;
    let master_sk = keys::master_sk_from_mnemonic(&mnemonic);
    let client = chain::client();
    let (_wallet_key, cat) = cat_chain::find_cat_coin(&client, &master_sk, asset_id, 0, chain::ADDRESS_GAP)
        .await
        .context("find_cat_coin")?;
    let lineage_proof = cat.lineage_proof.context("reconstructed CAT coin has no lineage proof")?;
    let play_state = state::PlayCoinState::new(cat.info.asset_id, cat.coin, lineage_proof);
    let path = state::save_play_coin(&name, &play_state)?;
    println!("reconciled {} -> coin_id={} amount={} path={}", name, cat.coin.coin_id(), cat.coin.amount, path.display());
    Ok(())
}
