//! (b) Issue the PLAY CAT with the genesis-by-coin-id (single-issuance) TAIL
//! that `chia-sdk-driver` provides, then transfer part of it. Prints the
//! asset id (the TAIL's curried tree hash), which is what identifies the CAT
//! on-chain (the "PLAY CAT" from the project plan).
//!
//! Pattern taken from `chia-sdk-driver-0.36.0/src/primitives/cat.rs`
//! (`mod tests::test_single_issuance_cat` and `test_cat_spends`).

use chia_puzzle_types::cat::GenesisByCoinIdTailArgs;
use chia_sdk_driver::{Cat, CatSpend, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_test::Simulator;
use chia_sdk_types::Conditions;

#[test]
fn issue_play_cat_and_transfer_part() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    const PLAY_SUPPLY: u64 = 1_000_000; // whole PLAY genesis supply, in mojo-equivalent units

    let alice = sim.bls(PLAY_SUPPLY);
    let alice_p2 = StandardLayer::new(alice.pk);

    // Single-issuance TAIL: `genesis_by_coin_id(parent_coin_id)`. Only the coin
    // that funds the issuance spend can ever run this TAIL, so the supply is
    // fixed forever after this one spend (no further minting is possible).
    let memos = ctx.hint(alice.puzzle_hash)?;
    let (issue_cat, cats) = Cat::single_issuance(
        ctx,
        alice.coin.coin_id(),
        None, // no hidden/revocation puzzle
        PLAY_SUPPLY,
        Conditions::new().create_coin(alice.puzzle_hash, PLAY_SUPPLY, memos),
    )?;
    alice_p2.spend(ctx, alice.coin, issue_cat)?;
    sim.spend_coins(ctx.take(), &[alice.sk.clone()])?;

    let play = cats[0];
    let expected_asset_id: chia_protocol::Bytes32 =
        GenesisByCoinIdTailArgs::curry_tree_hash(alice.coin.coin_id()).into();
    assert_eq!(play.info.asset_id, expected_asset_id);
    assert_eq!(play.coin.amount, PLAY_SUPPLY);
    assert!(sim.coin_state(play.coin.coin_id()).is_some());

    println!("PLAY asset id: {}", play.info.asset_id);
    println!(
        "PLAY genesis coin id (funding coin): {}",
        alice.coin.coin_id()
    );

    // Transfer part of it: bob gets 250_000, alice keeps the 750_000 change.
    let bob = sim.bls(0);

    const TO_BOB: u64 = 250_000;
    let alice_change = PLAY_SUPPLY - TO_BOB;

    let bob_memos = ctx.hint(bob.puzzle_hash)?;
    let alice_memos = ctx.hint(alice.puzzle_hash)?;

    let transfer_spend = CatSpend::new(
        play,
        alice_p2.spend_with_conditions(
            ctx,
            Conditions::new()
                .create_coin(bob.puzzle_hash, TO_BOB, bob_memos)
                .create_coin(alice.puzzle_hash, alice_change, alice_memos),
        )?,
    );
    let children = Cat::spend_all(ctx, &[transfer_spend])?;
    sim.spend_coins(ctx.take(), &[alice.sk])?;

    assert_eq!(children.len(), 2);
    let bob_cat = children
        .iter()
        .find(|c| c.coin.amount == TO_BOB)
        .expect("bob's child coin");
    let alice_cat = children
        .iter()
        .find(|c| c.coin.amount == alice_change)
        .expect("alice's change coin");

    assert_eq!(bob_cat.info.p2_puzzle_hash, bob.puzzle_hash);
    assert_eq!(bob_cat.info.asset_id, play.info.asset_id);
    assert_eq!(alice_cat.info.p2_puzzle_hash, alice.puzzle_hash);
    assert_eq!(alice_cat.info.asset_id, play.info.asset_id);

    assert!(sim.coin_state(bob_cat.coin.coin_id()).is_some());
    assert!(sim.coin_state(alice_cat.coin.coin_id()).is_some());

    Ok(())
}
