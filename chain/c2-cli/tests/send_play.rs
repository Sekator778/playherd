//! Simulator proof of `send-play`'s builder (`c2_cli::builders::send_cat`),
//! using a key derived exactly the way `c2 keygen` derives one and a CAT
//! minted exactly the way `c2 issue-play` mints one (`build_issue_play`) --
//! the code path exercised here is the one `c2 send-play` runs against
//! testnet11, minus only the coinset lookup itself (`cat_chain::
//! find_cat_coin`), which -- like `chain::find_funding_coin`/`chain::
//! balance` -- has no dedicated offline test in this crate; it's a thin,
//! read-only coinset wrapper, proven the same way those are: live testnet11
//! use (`docs/research/chain_service.md`).

use c2_cli::builders::issue::build_issue_play;
use c2_cli::builders::send_cat::build_send_cat;
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use chia_sdk_driver::CatInfo;
use chia_sdk_test::Simulator;

#[test]
fn send_play_transfers_amount_and_returns_change() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let sender_mnemonic = generate_mnemonic()?;
    let sender_master_sk = master_sk_from_mnemonic(&sender_mnemonic);
    let sender = derive_wallet_key(&sender_master_sk, 0);

    let recipient_mnemonic = generate_mnemonic()?;
    let recipient_master_sk = master_sk_from_mnemonic(&recipient_mnemonic);
    let recipient = derive_wallet_key(&recipient_master_sk, 0);

    const PLAY_SUPPLY: u64 = 1_000_000;
    const SEND_AMOUNT: u64 = 250_000;

    // Mint the PLAY CAT the same way `c2 issue-play` does.
    let funding_coin = sim.new_coin(sender.puzzle_hash, PLAY_SUPPLY);
    let issued = build_issue_play(funding_coin, &sender, PLAY_SUPPLY, 0)?;
    sim.spend_coins(issued.coin_spends, &[sender.synthetic_sk.clone()])?;
    let asset_id = issued.cat.info.asset_id;

    // Transfer part of it, exactly like `c2 send-play` would build.
    let result = build_send_cat(issued.cat, &sender, recipient.puzzle_hash, SEND_AMOUNT)?;
    assert_eq!(result.change_amount, PLAY_SUPPLY - SEND_AMOUNT);

    sim.spend_coins(result.coin_spends, &[sender.synthetic_sk.clone()])?;

    let recipient_cat_ph: chia_protocol::Bytes32 =
        CatInfo::new(asset_id, None, recipient.puzzle_hash).puzzle_hash().into();
    let recipient_coins = sim.unspent_coins(recipient_cat_ph, false);
    assert_eq!(recipient_coins.len(), 1);
    assert_eq!(recipient_coins[0].amount, SEND_AMOUNT);

    let sender_cat_ph: chia_protocol::Bytes32 = CatInfo::new(asset_id, None, sender.puzzle_hash).puzzle_hash().into();
    let sender_coins = sim.unspent_coins(sender_cat_ph, false);
    assert_eq!(sender_coins.len(), 1);
    assert_eq!(sender_coins[0].amount, result.change_amount);

    Ok(())
}

#[test]
fn send_play_with_no_change_creates_only_the_recipient_coin() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let sender_mnemonic = generate_mnemonic()?;
    let sender_master_sk = master_sk_from_mnemonic(&sender_mnemonic);
    let sender = derive_wallet_key(&sender_master_sk, 0);

    let recipient_mnemonic = generate_mnemonic()?;
    let recipient_master_sk = master_sk_from_mnemonic(&recipient_mnemonic);
    let recipient = derive_wallet_key(&recipient_master_sk, 0);

    const PLAY_SUPPLY: u64 = 500_000;

    let funding_coin = sim.new_coin(sender.puzzle_hash, PLAY_SUPPLY);
    let issued = build_issue_play(funding_coin, &sender, PLAY_SUPPLY, 0)?;
    sim.spend_coins(issued.coin_spends, &[sender.synthetic_sk.clone()])?;
    let asset_id = issued.cat.info.asset_id;

    let result = build_send_cat(issued.cat, &sender, recipient.puzzle_hash, PLAY_SUPPLY)?;
    assert_eq!(result.change_amount, 0);

    sim.spend_coins(result.coin_spends, &[sender.synthetic_sk.clone()])?;

    let recipient_cat_ph: chia_protocol::Bytes32 =
        CatInfo::new(asset_id, None, recipient.puzzle_hash).puzzle_hash().into();
    assert_eq!(sim.unspent_coins(recipient_cat_ph, false).len(), 1);

    let sender_cat_ph: chia_protocol::Bytes32 = CatInfo::new(asset_id, None, sender.puzzle_hash).puzzle_hash().into();
    assert!(sim.unspent_coins(sender_cat_ph, false).is_empty());

    Ok(())
}
