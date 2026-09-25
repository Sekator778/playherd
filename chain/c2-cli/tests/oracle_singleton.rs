//! Simulator proof of `oracle-launch` + `oracle-publish`'s builders
//! (`c2_cli::builders::oracle`), across three rounds, with an independent
//! consumer coin asserting the announcement -- the same shape as
//! `chain/sim-spike/tests/oracle_singleton.rs`, but through the CLI's own
//! builder functions and its `Proof`/state handoff between commands
//! (`oracle-launch` -> `oracle-publish` -> `oracle-publish` -> ...).

use c2_cli::builders::oracle::{build_oracle_launch, build_oracle_publish};
use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use chia_consensus::validation_error::ErrorCode;
use chia_protocol::CoinSpend;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_test::{Simulator, SimulatorError};
use chia_sdk_types::{Conditions, announcement_id};

#[test]
fn oracle_singleton_three_rounds_via_cli_builders() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);

    let funding_coin = sim.new_coin(manager.puzzle_hash, 1);
    let launch = build_oracle_launch(funding_coin, &manager, 0)?;
    sim.spend_coins(launch.coin_spends, &[manager.synthetic_sk.clone()])?;
    assert!(sim.coin_state(launch.eve_coin.coin_id()).is_some());

    let mut current_coin = launch.eve_coin;
    let mut current_proof = launch.eve_proof;

    for sequence in 1u64..=3 {
        let price = 65_432_10_i64 + i64::try_from(sequence)?;
        let ts = 1_780_000_000_i64 + i64::try_from(sequence)?;

        let published = build_oracle_publish(
            launch.launcher_id,
            current_coin,
            current_proof,
            &manager,
            price,
            ts,
            sequence,
        )?;

        // Independent consumer coin, in the *same* bundle, asserting the
        // oracle's own puzzle announcement -- proves a consumer contract
        // could rely on the oracle's output.
        let mut ctx = SpendContext::new();
        let consumer_master_sk = master_sk_from_mnemonic(&generate_mnemonic()?);
        let consumer = derive_wallet_key(&consumer_master_sk, 0);
        let consumer_coin = sim.new_coin(consumer.puzzle_hash, 1);
        let consumer_p2 = StandardLayer::new(consumer.synthetic_pk);
        consumer_p2.spend(
            &mut ctx,
            consumer_coin,
            Conditions::new()
                .create_coin(consumer.puzzle_hash, 1, chia_puzzle_types::Memos::None)
                .assert_puzzle_announcement(announcement_id(
                    launch.singleton_puzzle_hash,
                    &published.announcement_message,
                )),
        )?;

        let mut coin_spends: Vec<CoinSpend> = published.coin_spends.clone();
        coin_spends.extend(ctx.take());

        sim.spend_coins(coin_spends, &[manager.synthetic_sk.clone(), consumer.synthetic_sk])?;

        assert!(sim.coin_state(published.next_coin.coin_id()).is_some());
        assert!(
            sim.coin_state(current_coin.coin_id())
                .unwrap()
                .spent_height
                .is_some()
        );

        current_coin = published.next_coin;
        current_proof = published.next_proof;
    }

    Ok(())
}

/// Regression test for a real testnet11 incident (week 1, day 3, see
/// `docs/research/week1_testnet_results.md`): `find_funding_coin` picks a
/// coin by "amount at least this much", almost never exactly
/// `SINGLETON_AMOUNT + fee`, so `build_oracle_launch` must return the
/// leftover as change -- not let it silently become an oversized implicit
/// fee (Chia consensus treats spent-but-not-recreated value as fee, with no
/// error, so this bug produced a real, unintended ~0.8 TXCH fee before it
/// was fixed).
#[test]
fn oracle_launch_returns_change_for_excess_funding() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);

    const FUNDING_AMOUNT: u64 = 800_177_754_150; // matches the real incident's coin size
    const FEE: u64 = 1_200_000;

    let funding_coin = sim.new_coin(manager.puzzle_hash, FUNDING_AMOUNT);
    let launch = build_oracle_launch(funding_coin, &manager, FEE)?;
    assert_eq!(
        launch.change_amount,
        FUNDING_AMOUNT - c2_cli::builders::oracle::SINGLETON_AMOUNT - FEE
    );

    sim.spend_coins(launch.coin_spends, &[manager.synthetic_sk.clone()])?;

    assert!(sim.coin_state(launch.eve_coin.coin_id()).is_some());
    let change_coins = sim.unspent_coins(manager.puzzle_hash, false);
    assert!(change_coins.iter().any(|c| c.amount == launch.change_amount));

    Ok(())
}

/// A consumer asserting a *different* announcement than the one the oracle
/// actually emits this round must fail Simulator validation -- same
/// guarantee `sim-spike`'s negative test proves for the plain (non-CLI)
/// pattern.
#[test]
fn oracle_singleton_consumer_rejects_wrong_announcement() -> anyhow::Result<()> {
    let mut sim = Simulator::new();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);

    let funding_coin = sim.new_coin(manager.puzzle_hash, 1);
    let launch = build_oracle_launch(funding_coin, &manager, 0)?;
    sim.spend_coins(launch.coin_spends, &[manager.synthetic_sk.clone()])?;

    let published = build_oracle_publish(
        launch.launcher_id,
        launch.eve_coin,
        launch.eve_proof,
        &manager,
        1,
        2,
        1,
    )?;

    let wrong_message: chia_protocol::Bytes = vec![9, 9, 9].into();
    assert_ne!(wrong_message, published.announcement_message);

    let mut ctx = SpendContext::new();
    let consumer_master_sk = master_sk_from_mnemonic(&generate_mnemonic()?);
    let consumer = derive_wallet_key(&consumer_master_sk, 0);
    let consumer_coin = sim.new_coin(consumer.puzzle_hash, 1);
    let consumer_p2 = StandardLayer::new(consumer.synthetic_pk);
    consumer_p2.spend(
        &mut ctx,
        consumer_coin,
        Conditions::new()
            .create_coin(consumer.puzzle_hash, 1, chia_puzzle_types::Memos::None)
            .assert_puzzle_announcement(announcement_id(
                launch.singleton_puzzle_hash,
                &wrong_message,
            )),
    )?;

    let mut coin_spends: Vec<CoinSpend> = published.coin_spends;
    coin_spends.extend(ctx.take());

    let result = sim.spend_coins(coin_spends, &[manager.synthetic_sk, consumer.synthetic_sk]);
    match result {
        Err(SimulatorError::Validation(ErrorCode::AssertPuzzleAnnouncementFailed)) => {}
        other => panic!(
            "expected Err(SimulatorError::Validation(AssertPuzzleAnnouncementFailed)), got {other:?}"
        ),
    }

    Ok(())
}

