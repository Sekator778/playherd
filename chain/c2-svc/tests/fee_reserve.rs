//! The fee-coin split spend against real consensus rules (`chia-sdk-test`'s `Simulator`):
//! `src/fee_reserve.rs`'s unit tests read the conditions out of the spend, these run it. What the
//! fake-chain tests in `tests/fee_allocator.rs` cannot show, the simulator does: the spend is
//! signed correctly, value is conserved, the fee it reserves is the fee it pays, and the coins it
//! creates really appear.

use chia_protocol::Coin;
use chia_puzzle_types::Memos;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_test::{Simulator, SimulatorError};
use chia_sdk_types::Conditions;

use c2_cli::keys::{WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_svc::fee_reserve::{build_fee_coin_split, split_outputs_total};

fn operator() -> WalletKey {
    derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic().unwrap()), 0)
}

fn amounts_of_children(sim: &Simulator, parent: Coin) -> Vec<u64> {
    let mut amounts: Vec<u64> = sim.children(parent.coin_id()).iter().map(|s| s.coin.amount).collect();
    amounts.sort_unstable();
    amounts
}

/// The split is a valid spend: the simulator accepts it, the input is spent, and exactly the 4 fee
/// coins and the change exist afterwards.
#[test]
fn the_split_spend_is_accepted_by_consensus_and_creates_the_fee_coins_and_the_change() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let owner = operator();
    let input = sim.new_coin(owner.puzzle_hash, 10_000_000_000);

    let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 3_000_000)?;
    sim.spend_coins(split.coin_spends.clone(), std::slice::from_ref(&owner.synthetic_sk))?;

    assert!(sim.coin_state(input.coin_id()).unwrap().spent_height.is_some(), "the input coin is spent");
    let change = 10_000_000_000 - split_outputs_total(4, 200_000_000) - 3_000_000;
    assert_eq!(
        amounts_of_children(&sim, input),
        vec![200_000_000, 200_000_001, 200_000_002, 200_000_003, change]
    );
    for coin in &split.outputs {
        let state = sim.coin_state(coin.coin_id()).expect("every reported output coin exists on chain");
        assert!(state.spent_height.is_none());
    }
    assert_eq!(split.change_amount, change);
    Ok(())
}

/// A change that would collide with an output is shifted into the fee, and the result is still a
/// valid spend (it would be DUPLICATE_OUTPUT otherwise).
#[test]
fn a_colliding_change_is_nudged_and_the_spend_is_still_valid() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let owner = operator();
    let input = sim.new_coin(owner.puzzle_hash, 800_000_006 + 1_000 + 200_000_002);

    let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 1_000)?;
    sim.spend_coins(split.coin_spends, std::slice::from_ref(&owner.synthetic_sk))?;
    assert_eq!(amounts_of_children(&sim, input), vec![199_999_999, 200_000_000, 200_000_001, 200_000_002, 200_000_003]);
    Ok(())
}

/// Why the outputs differ by a mojo: four IDENTICAL outputs of one parent to one puzzle hash are the
/// same coin four times, and consensus refuses to create a coin twice. (Built by hand here, not with
/// `build_fee_coin_split`, which cannot produce it.)
#[test]
fn identical_outputs_would_be_rejected_as_a_duplicate_output() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let owner = operator();
    let input = sim.new_coin(owner.puzzle_hash, 10_000_000_000);

    let mut ctx = SpendContext::new();
    let mut conditions = Conditions::new();
    for _ in 0..4 {
        conditions = conditions.create_coin(owner.puzzle_hash, 200_000_000, Memos::None);
    }
    conditions = conditions.create_coin(owner.puzzle_hash, 10_000_000_000 - 800_000_000, Memos::None);
    StandardLayer::new(owner.synthetic_pk).spend(&mut ctx, input, conditions)?;

    let err = sim.spend_coins(ctx.take(), std::slice::from_ref(&owner.synthetic_sk)).unwrap_err();
    match err {
        SimulatorError::Validation(chia_consensus::validation_error::ErrorCode::DuplicateOutput) => {}
        other => panic!("expected DuplicateOutput, got {other:?}"),
    }
    Ok(())
}

/// A coin that is not the operator's plain coin (here: some other puzzle hash, as a singleton or CAT
/// coin would have) cannot even be built into a split.
#[test]
fn a_coin_of_another_puzzle_is_never_split() {
    let owner = operator();
    let mut sim = Simulator::new();
    let foreign = sim.new_coin([0xc3u8; 32].into(), 10_000_000_000);
    assert!(build_fee_coin_split(foreign, &owner, 4, 200_000_000, 1_000).is_err());
}

/// The split's cost is a small fraction of a block: a standard spend with five outputs.
#[test]
fn the_split_costs_a_fraction_of_a_blocks_budget() -> anyhow::Result<()> {
    let owner = operator();
    let input = Coin::new([1u8; 32].into(), owner.puzzle_hash, 10_000_000_000);
    let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 3_000_000)?;
    let cost = c2_cli::cost::measure_bundle_cost(&split.coin_spends)?;
    println!("fee-coin split cost: {} (bytes {}, execution {}, conditions {})", cost.total_cost, cost.byte_cost, cost.execution_cost, cost.condition_cost);
    assert!(cost.total_cost < 50_000_000, "split cost {} is unexpectedly large", cost.total_cost);
    Ok(())
}
