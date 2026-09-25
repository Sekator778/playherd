//! Simulator test for `oracle::spend::build_oracle_publish_batch` (task
//! instruction: "Simulator tests for the multi-record spend (a consumer
//! asserting each record's announcement)"). Follows the exact same
//! puzzle-composition pattern already proven in
//! `chain/sim-spike/tests/oracle_singleton.rs` and reused by
//! `c2_cli::builders::oracle`, just with N records batched into one spend
//! instead of one.

use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin};
use chia_puzzle_types::{EveProof, Proof, singleton::SingletonArgs};
use chia_sdk_driver::{Launcher, SpendContext, StandardLayer};
use chia_sdk_test::Simulator;
use chia_sdk_types::{Conditions, announcement_id};

use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_svc::oracle::spend::build_oracle_publish_batch;
use c2_svc::oracle::types::{OracleRecord, Source};

fn results_hash(byte: u8) -> Bytes32 {
    Bytes32::from([byte; 32])
}

fn record(seq: u64, byte: u8, source: Source) -> OracleRecord {
    OracleRecord {
        seq,
        ts: 1_790_000_000 + seq as i64,
        price_cents: 65_432_100 + seq as i64,
        results_hash: results_hash(byte),
        source_flag: source.flag(),
        source_ts: 1_789_999_000 + seq as i64,
    }
}

/// One spend announcing 3 records at once; a separate consumer coin per
/// record asserts exactly that record's `CREATE_PUZZLE_ANNOUNCEMENT` from
/// the *same* bundle -- proving a consumer contract can rely on any one of
/// the batch's announcements without needing to know how many other
/// records rode along in the same spend.
#[test]
fn batch_of_three_records_each_independently_assertable() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    let master_sk: SecretKey = master_sk_from_mnemonic(&generate_mnemonic()?);
    let manager = derive_wallet_key(&master_sk, 0);

    // Launch the oracle singleton, inner puzzle = the manager's standard p2
    // puzzle (decision Р-11's minimal managed-resolver pattern, same as
    // `c2_cli::builders::oracle::build_oracle_launch`).
    let funding_coin: Coin = sim.new_coin(manager.puzzle_hash, 1);
    let launcher = Launcher::new(funding_coin.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, manager.puzzle_hash, ())?;
    StandardLayer::new(manager.synthetic_pk).spend(ctx, funding_coin, create_singleton)?;
    sim.spend_coins(ctx.take(), &[manager.synthetic_sk.clone()])?;

    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, manager.puzzle_hash.into()).into();
    assert_eq!(eve_coin.puzzle_hash, singleton_puzzle_hash);

    let eve_proof = Proof::Eve(EveProof {
        parent_parent_coin_info: funding_coin.coin_id(),
        parent_amount: 1,
    });

    let records = vec![
        record(1, 0xAA, Source::Live),
        record(2, 0xBB, Source::Live),
        record(3, 0xCC, Source::Replay),
    ];

    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)?;
    assert_eq!(result.announcement_messages.len(), 3);

    let mut coin_spends = result.coin_spends;
    let mut secret_keys = vec![manager.synthetic_sk.clone()];

    let mut consumer_coin_ids = Vec::new();
    for message in &result.announcement_messages {
        let consumer_ctx = &mut SpendContext::new();
        let consumer = derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic()?), 0);
        let consumer_coin = sim.new_coin(consumer.puzzle_hash, 1);
        StandardLayer::new(consumer.synthetic_pk).spend(
            consumer_ctx,
            consumer_coin,
            Conditions::new()
                .create_coin(consumer.puzzle_hash, 1, chia_puzzle_types::Memos::None)
                .assert_puzzle_announcement(announcement_id(singleton_puzzle_hash, message)),
        )?;
        coin_spends.extend(consumer_ctx.take());
        secret_keys.push(consumer.synthetic_sk.clone());
        consumer_coin_ids.push(consumer_coin.coin_id());
    }

    sim.spend_coins(coin_spends, &secret_keys)?;

    // The singleton was recreated (next generation exists, this generation
    // is spent) and every consumer's own spend went through -- both only
    // possible if every consumer's `ASSERT_PUZZLE_ANNOUNCEMENT` matched an
    // announcement the *same* singleton spend actually made.
    assert!(sim.coin_state(result.next_coin.coin_id()).is_some());
    assert!(sim.coin_state(eve_coin.coin_id()).unwrap().spent_height.is_some());
    for consumer_coin_id in consumer_coin_ids {
        assert!(sim.coin_state(consumer_coin_id).unwrap().spent_height.is_some());
    }

    Ok(())
}

/// Negative counterpart: a consumer asserting a message that was *not*
/// announced in this batch (a record with a different `resultsHash`, not
/// actually part of `records`) must fail simulator validation -- same
/// failure mode `chain/sim-spike/tests/oracle_singleton.rs`'s
/// `oracle_singleton_consumer_rejects_wrong_announcement` pins down for the
/// single-record builder.
#[test]
fn consumer_rejects_announcement_not_in_this_batch() -> anyhow::Result<()> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();

    let master_sk: SecretKey = master_sk_from_mnemonic(&generate_mnemonic()?);
    let manager = derive_wallet_key(&master_sk, 0);

    let funding_coin: Coin = sim.new_coin(manager.puzzle_hash, 1);
    let launcher = Launcher::new(funding_coin.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, manager.puzzle_hash, ())?;
    StandardLayer::new(manager.synthetic_pk).spend(ctx, funding_coin, create_singleton)?;
    sim.spend_coins(ctx.take(), &[manager.synthetic_sk.clone()])?;

    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, manager.puzzle_hash.into()).into();

    let eve_proof = Proof::Eve(EveProof {
        parent_parent_coin_info: funding_coin.coin_id(),
        parent_amount: 1,
    });

    let records = vec![record(1, 0xAA, Source::Live)];
    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)?;

    // A record that was never included in `records` above -> its
    // announcement message was never emitted by this spend.
    let absent = record(99, 0xFF, Source::Live);
    let absent_ptr = ctx.alloc(&clvm_traits::clvm_list!(
        99i64,
        absent.ts,
        absent.price_cents,
        absent.results_hash,
        absent.source_flag,
        absent.source_ts
    ))?;
    let absent_message = ctx.serialize(&absent_ptr)?.into_inner();

    let mut coin_spends = result.coin_spends;
    let consumer_ctx = &mut SpendContext::new();
    let consumer = derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic()?), 0);
    let consumer_coin = sim.new_coin(consumer.puzzle_hash, 1);
    StandardLayer::new(consumer.synthetic_pk).spend(
        consumer_ctx,
        consumer_coin,
        Conditions::new()
            .create_coin(consumer.puzzle_hash, 1, chia_puzzle_types::Memos::None)
            .assert_puzzle_announcement(announcement_id(singleton_puzzle_hash, &absent_message)),
    )?;
    coin_spends.extend(consumer_ctx.take());

    let secret_keys = vec![manager.synthetic_sk.clone(), consumer.synthetic_sk.clone()];
    let err = sim.spend_coins(coin_spends, &secret_keys).unwrap_err();
    match err {
        chia_sdk_test::SimulatorError::Validation(
            chia_consensus::validation_error::ErrorCode::AssertPuzzleAnnouncementFailed,
        ) => {}
        other => panic!("expected AssertPuzzleAnnouncementFailed, got {other:?}"),
    }

    Ok(())
}
