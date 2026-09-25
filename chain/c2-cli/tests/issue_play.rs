//! Simulator proof of `issue-play`'s builder (`c2_cli::builders::issue`),
//! using a key derived exactly the way `c2 keygen` derives one (BIP-39 ->
//! master sk -> unhardened wallet key -> synthetic key), so the code path
//! exercised here is the one `c2 issue-play` runs against testnet11.

use c2_cli::builders::issue::build_issue_play;
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use chia_sdk_test::Simulator;

#[test]
fn issue_play_mints_and_returns_change() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let owner = derive_wallet_key(&master_sk, 0);

    const FUNDING_AMOUNT: u64 = 1_000_000;
    const PLAY_AMOUNT: u64 = 900_000;
    const FEE: u64 = 1_000;

    let funding_coin = sim.new_coin(owner.puzzle_hash, FUNDING_AMOUNT);

    let result = build_issue_play(funding_coin, &owner, PLAY_AMOUNT, FEE)?;
    assert_eq!(result.change_amount, FUNDING_AMOUNT - PLAY_AMOUNT - FEE);
    assert_eq!(result.cat.coin.amount, PLAY_AMOUNT);
    assert_eq!(result.cat.info.p2_puzzle_hash, owner.puzzle_hash);

    let updates = sim.spend_coins(result.coin_spends, &[owner.synthetic_sk.clone()])?;
    assert!(!updates.is_empty());

    assert!(sim.coin_state(result.cat.coin.coin_id()).is_some());
    assert!(
        sim.coin_state(funding_coin.coin_id())
            .unwrap()
            .spent_height
            .is_some()
    );

    let change_coins = sim.unspent_coins(owner.puzzle_hash, false);
    assert!(
        change_coins
            .iter()
            .any(|c| c.amount == result.change_amount)
    );

    Ok(())
}

#[test]
fn issue_play_rejects_insufficient_funding() {
    let mnemonic = generate_mnemonic().unwrap();
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let owner = derive_wallet_key(&master_sk, 0);

    let funding_coin = chia_protocol::Coin::new(
        chia_protocol::Bytes32::from([0u8; 32]),
        owner.puzzle_hash,
        100,
    );

    let err = build_issue_play(funding_coin, &owner, 1_000, 0).unwrap_err();
    assert!(err.to_string().contains("need at least"));
}
