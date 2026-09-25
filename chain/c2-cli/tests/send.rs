//! Simulator proof of `send`'s builder (`c2_cli::builders::send`), using a
//! key derived exactly the way `c2 keygen` derives one, so the code path
//! exercised here is the one `c2 send` runs against testnet11.

use c2_cli::builders::send::build_send;
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use chia_sdk_test::Simulator;

#[test]
fn send_transfers_amount_and_returns_change() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let sender_mnemonic = generate_mnemonic()?;
    let sender_master_sk = master_sk_from_mnemonic(&sender_mnemonic);
    let sender = derive_wallet_key(&sender_master_sk, 0);

    let recipient_mnemonic = generate_mnemonic()?;
    let recipient_master_sk = master_sk_from_mnemonic(&recipient_mnemonic);
    let recipient = derive_wallet_key(&recipient_master_sk, 0);

    const FUNDING_AMOUNT: u64 = 1_000_000;
    const SEND_AMOUNT: u64 = 100_000;
    const FEE: u64 = 1_000;

    let funding_coin = sim.new_coin(sender.puzzle_hash, FUNDING_AMOUNT);

    let result = build_send(funding_coin, &sender, recipient.puzzle_hash, SEND_AMOUNT, FEE)?;
    assert_eq!(result.change_amount, FUNDING_AMOUNT - SEND_AMOUNT - FEE);

    let updates = sim.spend_coins(result.coin_spends, &[sender.synthetic_sk.clone()])?;
    assert!(!updates.is_empty());

    assert!(
        sim.coin_state(funding_coin.coin_id())
            .unwrap()
            .spent_height
            .is_some()
    );

    let recipient_coins = sim.unspent_coins(recipient.puzzle_hash, false);
    assert_eq!(recipient_coins.len(), 1);
    assert_eq!(recipient_coins[0].amount, SEND_AMOUNT);

    let sender_change_coins = sim.unspent_coins(sender.puzzle_hash, false);
    assert!(sender_change_coins.iter().any(|c| c.amount == result.change_amount));

    Ok(())
}

#[test]
fn send_with_no_change_creates_only_the_recipient_coin() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let sender_mnemonic = generate_mnemonic()?;
    let sender_master_sk = master_sk_from_mnemonic(&sender_mnemonic);
    let sender = derive_wallet_key(&sender_master_sk, 0);

    let recipient_mnemonic = generate_mnemonic()?;
    let recipient_master_sk = master_sk_from_mnemonic(&recipient_mnemonic);
    let recipient = derive_wallet_key(&recipient_master_sk, 0);

    const FUNDING_AMOUNT: u64 = 500_000;

    let funding_coin = sim.new_coin(sender.puzzle_hash, FUNDING_AMOUNT);
    let result = build_send(funding_coin, &sender, recipient.puzzle_hash, FUNDING_AMOUNT, 0)?;
    assert_eq!(result.change_amount, 0);

    sim.spend_coins(result.coin_spends, &[sender.synthetic_sk.clone()])?;

    let recipient_coins = sim.unspent_coins(recipient.puzzle_hash, false);
    assert_eq!(recipient_coins.len(), 1);
    assert_eq!(recipient_coins[0].amount, FUNDING_AMOUNT);
    assert!(sim.unspent_coins(sender.puzzle_hash, false).is_empty());

    Ok(())
}
