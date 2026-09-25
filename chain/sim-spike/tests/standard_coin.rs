//! (a) Fund a standard p2 coin in the offline simulator and spend it.
//!
//! Pattern taken from `chia-sdk-driver-0.36.0/src/layers/standard_layer.rs`
//! (`mod tests::test_flash_loan`), adapted to a plain "fund then spend".

use chia_puzzle_types::Memos;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_test::Simulator;
use chia_sdk_types::Conditions;

#[test]
fn fund_and_spend_standard_coin() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    // Fund: the simulator mints a coin directly to alice's standard puzzle hash.
    let alice = sim.bls(1_000_000_000_000);
    let alice_p2 = StandardLayer::new(alice.pk);

    assert!(sim.coin_state(alice.coin.coin_id()).is_some());
    assert_eq!(
        sim.coin_state(alice.coin.coin_id()).unwrap().spent_height,
        None
    );

    // Spend: alice sends the whole coin back to herself (a no-op transfer,
    // but it exercises the full StandardLayer puzzle + BLS-signed spend path).
    alice_p2.spend(
        ctx,
        alice.coin,
        Conditions::new().create_coin(alice.puzzle_hash, alice.coin.amount, Memos::None),
    )?;

    let updates = sim.spend_coins(ctx.take(), &[alice.sk])?;
    assert_eq!(updates.len(), 2); // the spent coin + its one child

    let spent = sim.coin_state(alice.coin.coin_id()).unwrap();
    assert!(spent.spent_height.is_some());

    let child = sim.unspent_coins(alice.puzzle_hash, false);
    assert_eq!(child.len(), 1);
    assert_eq!(child[0].amount, alice.coin.amount);

    Ok(())
}
