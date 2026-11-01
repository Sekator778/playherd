//! (c) Managed-resolver oracle pattern (day-1 spike for the project plan §3's
//! "managed-resolver-singleton" / the `RewardDistributorType::Managed`
//! pattern from CHIP-51, minimal form: no action layer yet, just a plain
//! singleton whose inner puzzle is a standard p2 controlled by one
//! "manager" key).
//!
//! Launches a singleton, then spends it 3 times. Each spend:
//! - recreates the singleton (same inner puzzle hash / manager key, so it
//!   can be spent again next round);
//! - emits a `CREATE_PUZZLE_ANNOUNCEMENT` whose message is the CLVM
//!   serialization of the list `(price timestamp sequence)`.
//!
//! In the *same* spend bundle, a second, independent coin (a "consumer")
//! spends itself and asserts that exact puzzle announcement
//! (`ASSERT_PUZZLE_ANNOUNCEMENT`), proving a consumer contract could rely on
//! the oracle's output without needing any relationship to the oracle coin
//! other than reading its announcement.
//!
//! Puzzle-composition pattern (`SingletonLayer<I>` + `Launcher`) taken from
//! `chia-sdk-driver-0.36.0/src/layers/p2_singleton_layer.rs`
//! (`mod tests::test_p2_singleton_layer`).
//!
//! Cost measurement: see `sim_spike::measure_bundle_cost` doc comment for
//! why the simulator itself can't be asked for cost, and what public API
//! this uses instead.

use chia_consensus::validation_error::ErrorCode;
use chia_protocol::{Bytes, Bytes32, Coin};
use chia_puzzle_types::{
    EveProof, Memos, Proof,
    singleton::{SingletonArgs, SingletonSolution},
};
use chia_sdk_driver::{Layer, Launcher, SingletonLayer, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_test::{Simulator, SimulatorError};
use chia_sdk_types::{Conditions, announcement_id, run_puzzle_with_cost};
use clvm_utils::ToTreeHash;

use sim_spike::{SIMULATOR_MAX_COST, measure_bundle_cost};

#[test]
fn oracle_singleton_three_price_updates() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    // --- Launch the oracle singleton, inner puzzle = standard layer controlled
    // --- by the "manager" key (the minimal stand-in for a managed resolver).
    let manager = sim.bls(1);
    let manager_p2 = StandardLayer::new(manager.pk);
    let inner_puzzle_hash: Bytes32 = manager_p2.tree_hash().into();

    let launcher = Launcher::new(manager.coin.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, inner_puzzle_hash, ())?;
    manager_p2.spend(ctx, manager.coin, create_singleton)?;
    sim.spend_coins(ctx.take(), &[manager.sk.clone()])?;

    // The singleton's full puzzle hash never changes across these 3 spends,
    // since the manager key (inner puzzle) is the same every time.
    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, inner_puzzle_hash.into()).into();
    assert_eq!(eve_coin.puzzle_hash, singleton_puzzle_hash);

    let mut current_coin = eve_coin;
    let mut current_proof = Proof::Eve(EveProof {
        parent_parent_coin_info: manager.coin.coin_id(),
        parent_amount: 1,
    });

    let mut singleton_execution_costs = Vec::new();
    let mut bundle_costs = Vec::new();

    for sequence in 1u64..=3 {
        // Arbitrary but distinct fake price/timestamp per round.
        let price_usd_cents: i64 = 65_432_10 + i64::try_from(sequence)?;
        let timestamp: i64 = 1_780_000_000 + i64::try_from(sequence)?;

        // Encode (price, timestamp, sequence) as a CLVM list and use its raw
        // serialization as the announcement message.
        let message_ptr = ctx.alloc(&vec![price_usd_cents, timestamp, i64::try_from(sequence)?])?;
        let message: Bytes = ctx.serialize(&message_ptr)?.into_inner();

        let inner_solution = manager_p2
            .spend_with_conditions(
                ctx,
                Conditions::new()
                    .create_coin(inner_puzzle_hash, 1, Memos::None)
                    .create_puzzle_announcement(message.clone()),
            )?
            .solution;

        let inner_puzzle_ptr = manager_p2.construct_puzzle(ctx)?;
        let singleton_spend = SingletonLayer::new(launcher_id, inner_puzzle_ptr).construct_spend(
            ctx,
            SingletonSolution {
                lineage_proof: current_proof,
                amount: current_coin.amount,
                inner_solution,
            },
        )?;

        // Measure the CLVM execution cost of *just* the singleton spend
        // (puzzle run in isolation, same as clvmr::run_program would report).
        let reduction =
            run_puzzle_with_cost(ctx, singleton_spend.puzzle, singleton_spend.solution, SIMULATOR_MAX_COST, false)?;
        singleton_execution_costs.push(reduction.0);

        let puzzle_reveal = ctx.serialize(&singleton_spend.puzzle)?;
        let solution = ctx.serialize(&singleton_spend.solution)?;
        ctx.insert(chia_protocol::CoinSpend::new(
            current_coin,
            puzzle_reveal,
            solution,
        ));

        // Independent consumer coin: spends itself and asserts the oracle's
        // puzzle announcement from *this same bundle*.
        let consumer = sim.bls(1);
        let consumer_p2 = StandardLayer::new(consumer.pk);
        consumer_p2.spend(
            ctx,
            consumer.coin,
            Conditions::new()
                .create_coin(consumer.puzzle_hash, 1, Memos::None)
                .assert_puzzle_announcement(announcement_id(singleton_puzzle_hash, &message)),
        )?;

        let coin_spends = ctx.take();
        let bundle_cost = measure_bundle_cost(&coin_spends)?;
        bundle_costs.push(bundle_cost);

        sim.spend_coins(coin_spends, &[manager.sk.clone(), consumer.sk])?;

        // Advance to the singleton's child for the next round.
        let next_coin = Coin::new(current_coin.coin_id(), singleton_puzzle_hash, 1);
        assert!(sim.coin_state(next_coin.coin_id()).is_some());
        assert!(
            sim.coin_state(current_coin.coin_id())
                .unwrap()
                .spent_height
                .is_some()
        );

        current_proof = Proof::Lineage(
            SingletonLayer::new(launcher_id, manager_p2).lineage_proof(current_coin),
        );
        current_coin = next_coin;
    }

    assert_eq!(singleton_execution_costs.len(), 3);
    assert_eq!(bundle_costs.len(), 3);

    println!("singleton spend execution cost (CLVM only), per round: {singleton_execution_costs:?}");
    for (i, cost) in bundle_costs.iter().enumerate() {
        println!(
            "round {}: bundle total_cost={} (byte={} execution={} condition={})",
            i + 1,
            cost.total_cost,
            cost.byte_cost,
            cost.execution_cost,
            cost.condition_cost
        );
    }

    Ok(())
}

/// Negative counterpart to [`oracle_singleton_three_price_updates`]: a
/// consumer that asserts a puzzle announcement message *different* from the
/// one the oracle singleton actually emits in the same bundle must fail
/// Simulator validation (`ASSERT_PUZZLE_ANNOUNCEMENT` looks up the exact
/// announcement id — `sha256(puzzle_hash || message)` — among the
/// announcements every other spend in the bundle actually made; a mismatched
/// message means no such id was announced this round, so validation must
/// reject it with `ErrorCode::AssertPuzzleAnnouncementFailed`, the same as
/// it would if the singleton spend that makes the real announcement were
/// removed from the bundle entirely).
#[test]
fn oracle_singleton_consumer_rejects_wrong_announcement() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    let manager = sim.bls(1);
    let manager_p2 = StandardLayer::new(manager.pk);
    let inner_puzzle_hash: Bytes32 = manager_p2.tree_hash().into();

    let launcher = Launcher::new(manager.coin.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, inner_puzzle_hash, ())?;
    manager_p2.spend(ctx, manager.coin, create_singleton)?;
    sim.spend_coins(ctx.take(), &[manager.sk.clone()])?;

    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, inner_puzzle_hash.into()).into();
    assert_eq!(eve_coin.puzzle_hash, singleton_puzzle_hash);

    // The message the oracle actually announces this round.
    let real_message_ptr = ctx.alloc(&vec![65_432_11_i64, 1_780_000_001_i64, 1_i64])?;
    let real_message: Bytes = ctx.serialize(&real_message_ptr)?.into_inner();

    let inner_solution = manager_p2
        .spend_with_conditions(
            ctx,
            Conditions::new()
                .create_coin(inner_puzzle_hash, 1, Memos::None)
                .create_puzzle_announcement(real_message.clone()),
        )?
        .solution;

    let inner_puzzle_ptr = manager_p2.construct_puzzle(ctx)?;
    let singleton_spend = SingletonLayer::new(launcher_id, inner_puzzle_ptr).construct_spend(
        ctx,
        SingletonSolution {
            lineage_proof: Proof::Eve(EveProof {
                parent_parent_coin_info: manager.coin.coin_id(),
                parent_amount: 1,
            }),
            amount: eve_coin.amount,
            inner_solution,
        },
    )?;

    let puzzle_reveal = ctx.serialize(&singleton_spend.puzzle)?;
    let solution = ctx.serialize(&singleton_spend.solution)?;
    ctx.insert(chia_protocol::CoinSpend::new(eve_coin, puzzle_reveal, solution));

    // A different message than `real_message` above (distinct sequence/price/
    // timestamp), so its announcement id will not match anything the bundle
    // actually announces.
    let wrong_message_ptr = ctx.alloc(&vec![99_999_99_i64, 1_780_099_999_i64, 9_i64])?;
    let wrong_message: Bytes = ctx.serialize(&wrong_message_ptr)?.into_inner();
    assert_ne!(wrong_message, real_message);

    let consumer = sim.bls(1);
    let consumer_p2 = StandardLayer::new(consumer.pk);
    consumer_p2.spend(
        ctx,
        consumer.coin,
        Conditions::new()
            .create_coin(consumer.puzzle_hash, 1, Memos::None)
            .assert_puzzle_announcement(announcement_id(singleton_puzzle_hash, &wrong_message)),
    )?;

    let coin_spends = ctx.take();
    let result = sim.spend_coins(coin_spends, &[manager.sk.clone(), consumer.sk]);

    match result {
        Err(SimulatorError::Validation(ErrorCode::AssertPuzzleAnnouncementFailed)) => {}
        other => panic!(
            "expected Err(SimulatorError::Validation(AssertPuzzleAnnouncementFailed)), got {other:?}"
        ),
    }

    Ok(())
}
