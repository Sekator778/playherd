//! Simulator test for `oracle::spend::build_oracle_publish_batch` (task
//! instruction: "Simulator tests for the multi-record spend (a consumer
//! asserting each record's announcement)"). Follows the exact same
//! puzzle-composition pattern already proven in
//! `chain/sim-spike/tests/oracle_singleton.rs` and reused by
//! `c2_cli::builders::oracle`, just with N records batched into one spend
//! instead of one.

mod oracle_golden;

use chia_bls::SecretKey;
use chia_protocol::{Bytes, Bytes32, Coin, CoinSpend};
use chia_puzzle_types::{EveProof, Proof, singleton::SingletonArgs};
use chia_sdk_driver::{Launcher, SpendContext, StandardLayer};
use chia_sdk_test::Simulator;
use chia_sdk_types::{Conditions, announcement_id};
use clvmr::{Allocator, SExp, serde::node_from_bytes};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::cost::measure_bundle_cost;
use c2_cli::keys::{WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_svc::oracle::lineage::decode_oracle_record;
use c2_svc::oracle::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use c2_svc::oracle::types::{OracleRecord, Source};

use oracle_golden::{SIX_ELEMENT_MESSAGE_HEX, fixed_record};

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
        asset: 0,
    }
}

fn record_with_asset(seq: u64, byte: u8, asset: u32) -> OracleRecord {
    OracleRecord { asset, ..record(seq, byte, Source::Live) }
}

fn element_count(message: &[u8]) -> usize {
    let mut allocator = Allocator::new();
    let mut node = node_from_bytes(&mut allocator, message).expect("valid CLVM");
    let mut count = 0;
    while let SExp::Pair(_, rest) = allocator.sexp(node) {
        count += 1;
        node = rest;
    }
    count
}

/// A launched oracle singleton in a fresh simulator, ready for its first
/// publishing spend -- the setup the first two tests above spell out inline.
struct Launched {
    sim: Simulator,
    manager: WalletKey,
    launcher_id: Bytes32,
    eve_coin: Coin,
    eve_proof: Proof,
    singleton_puzzle_hash: Bytes32,
}

fn launch_oracle() -> anyhow::Result<Launched> {
    let mut sim = Simulator::new();
    let ctx = &mut SpendContext::new();
    let master_sk: SecretKey = master_sk_from_mnemonic(&generate_mnemonic()?);
    let manager = derive_wallet_key(&master_sk, 0);

    let funding_coin: Coin = sim.new_coin(manager.puzzle_hash, 1);
    let launcher = Launcher::new(funding_coin.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, manager.puzzle_hash, ())?;
    StandardLayer::new(manager.synthetic_pk).spend(ctx, funding_coin, create_singleton)?;
    sim.spend_coins(ctx.take(), std::slice::from_ref(&manager.synthetic_sk))?;

    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, manager.puzzle_hash.into()).into();
    let eve_proof = Proof::Eve(EveProof { parent_parent_coin_info: funding_coin.coin_id(), parent_amount: 1 });
    Ok(Launched { sim, manager, launcher_id, eve_coin, eve_proof, singleton_puzzle_hash })
}

/// A spend of a fresh consumer coin that asserts `message` was announced by
/// the singleton -- what a consumer contract would do with one record.
fn consumer_asserting(
    sim: &mut Simulator,
    singleton_puzzle_hash: Bytes32,
    message: &Bytes,
) -> anyhow::Result<(Vec<CoinSpend>, SecretKey, Coin)> {
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
    Ok((consumer_ctx.take(), consumer.synthetic_sk.clone(), consumer_coin))
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

// --- record v2 (`asset`) -----------------------------------------------------------

/// A 7-element announcement (asset 3) goes through a real singleton spend in the
/// simulator -- a consumer asserting exactly those bytes is accepted -- and the
/// chain-side decoder reads the very same message back into the very same record.
#[test]
fn seven_element_announcement_round_trips_through_the_simulator_spend_and_the_lineage_decoder() -> anyhow::Result<()> {
    let Launched { mut sim, manager, launcher_id, eve_coin, eve_proof, singleton_puzzle_hash } = launch_oracle()?;
    let records = vec![record_with_asset(1, 0xAA, 3)];

    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)?;
    let message = &result.announcement_messages[0];
    assert_eq!(element_count(message.as_ref()), 7, "a non-zero asset is announced as a 7th element");
    assert_eq!(decode_oracle_record(message.as_ref())?, records[0], "decoder must read back exactly what was announced");

    let (consumer_spends, consumer_key, consumer_coin) = consumer_asserting(&mut sim, singleton_puzzle_hash, message)?;
    let mut coin_spends = result.coin_spends;
    coin_spends.extend(consumer_spends);
    sim.spend_coins(coin_spends, &[manager.synthetic_sk.clone(), consumer_key])?;

    assert!(sim.coin_state(result.next_coin.coin_id()).is_some(), "singleton recreated");
    assert!(sim.coin_state(consumer_coin.coin_id()).unwrap().spent_height.is_some(), "consumer's assertion held");
    Ok(())
}

/// The asset is part of the announced bytes: a consumer that asserts the
/// 6-element form of a record announced WITH an asset must fail, so one market's
/// price can never be passed off as another's.
#[test]
fn a_consumer_asserting_the_six_element_form_of_an_asset_record_is_rejected() -> anyhow::Result<()> {
    let Launched { mut sim, manager, launcher_id, eve_coin, eve_proof, singleton_puzzle_hash } = launch_oracle()?;
    let announced = record_with_asset(1, 0xAA, 3);
    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &[announced])?;

    // Same record, asset dropped -> the 6-element bytes of a BTC record.
    let btc_form = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof_clone(&eve_proof), &manager, &[OracleRecord { asset: 0, ..announced }])?
        .announcement_messages[0]
        .clone();
    let (consumer_spends, consumer_key, _) = consumer_asserting(&mut sim, singleton_puzzle_hash, &btc_form)?;
    let mut coin_spends = result.coin_spends;
    coin_spends.extend(consumer_spends);

    let err = sim.spend_coins(coin_spends, &[manager.synthetic_sk.clone(), consumer_key]).unwrap_err();
    match err {
        chia_sdk_test::SimulatorError::Validation(
            chia_consensus::validation_error::ErrorCode::AssertPuzzleAnnouncementFailed,
        ) => {}
        other => panic!("expected AssertPuzzleAnnouncementFailed, got {other:?}"),
    }
    Ok(())
}

/// `Proof` is not `Copy`; the second builder call needs its own value.
fn eve_proof_clone(proof: &Proof) -> Proof {
    match proof {
        Proof::Eve(p) => Proof::Eve(*p),
        Proof::Lineage(p) => Proof::Lineage(*p),
    }
}

/// One spend mixing BTC records (asset 0, 6 elements) with asset records (7
/// elements): every announcement has its own shape, the BTC one is byte-identical
/// to the pre-v2 golden message, and every one of them is independently
/// assertable by a consumer in the simulator.
#[test]
fn a_batch_mixing_asset_zero_and_asset_three_records_announces_each_in_its_own_shape() -> anyhow::Result<()> {
    let Launched { mut sim, manager, launcher_id, eve_coin, eve_proof, singleton_puzzle_hash } = launch_oracle()?;
    let records = vec![
        fixed_record(), // asset 0 -> must equal the golden 6-element bytes
        record_with_asset(1146, 0xBB, 3),
        record_with_asset(1147, 0xCC, 0),
        record_with_asset(1148, 0xDD, 3),
        record_with_asset(1149, 0xEE, 200),
    ];

    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)?;
    assert_eq!(result.announcement_messages.len(), records.len());
    assert_eq!(hex::encode(result.announcement_messages[0].as_ref()), SIX_ELEMENT_MESSAGE_HEX);
    let shapes: Vec<usize> = result.announcement_messages.iter().map(|m| element_count(m.as_ref())).collect();
    assert_eq!(shapes, vec![6, 7, 6, 7, 7]);
    for (message, record) in result.announcement_messages.iter().zip(&records) {
        assert_eq!(&decode_oracle_record(message.as_ref())?, record);
    }

    let mut coin_spends = result.coin_spends;
    let mut keys = vec![manager.synthetic_sk.clone()];
    let mut consumer_coins = Vec::new();
    for message in &result.announcement_messages {
        let (spends, key, coin) = consumer_asserting(&mut sim, singleton_puzzle_hash, message)?;
        coin_spends.extend(spends);
        keys.push(key);
        consumer_coins.push(coin);
    }
    sim.spend_coins(coin_spends, &keys)?;
    for coin in consumer_coins {
        assert!(sim.coin_state(coin.coin_id()).unwrap().spent_height.is_some());
    }
    Ok(())
}

/// `MAX_RECORDS_PER_SPEND` (40, raised from 20 under record v2's heavier
/// markets load, `docs/research/markets_architecture.md` §7 item 8): a full
/// batch -- mixed assets, so both message shapes -- builds, runs in the
/// simulator together with the publisher's fee-only spend, every one of its 40
/// announcements is assertable, and the whole bundle stays far below the
/// 150,000,000 cost budget this test enforces (a block holds 11,000,000,000).
///
/// Measured 2026-10-01 (the test prints them, `-- --nocapture`): singleton spend
/// alone 53,382,908 (bytes 49,860,000, execution 522,908, conditions
/// 3,000,000); the whole bundle with the publisher's fee-only spend
/// 61,174,188. The one-record spend measures 21,509,048
/// (`docs/research/week1_testnet_results.md:48`), so 40 records cost about
/// 2.5x one record's singleton spend: roughly 1.3M per record against 21.5M for
/// a record published alone.
#[test]
fn a_batch_of_the_maximum_forty_records_runs_in_the_simulator_under_the_cost_budget() -> anyhow::Result<()> {
    const COST_BUDGET: u64 = 150_000_000;
    assert_eq!(MAX_RECORDS_PER_SPEND, 40);

    let Launched { mut sim, manager, launcher_id, eve_coin, eve_proof, singleton_puzzle_hash } = launch_oracle()?;
    let records: Vec<OracleRecord> = (0..40u64)
        .map(|i| record_with_asset(1_146 + i, i as u8, if i % 2 == 0 { 0 } else { (i % 7) as u32 + 1 }))
        .collect();

    let result = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)?;
    assert_eq!(result.announcement_messages.len(), 40);
    let distinct: std::collections::HashSet<&[u8]> =
        result.announcement_messages.iter().map(|m| m.as_ref()).collect();
    assert_eq!(distinct.len(), 40, "every record announces its own message");

    // The publisher attaches a fee-only spend (`attach_fee_coin`); measure what
    // is actually broadcast, singleton spend plus fee spend.
    let fee_coin = sim.new_coin(manager.puzzle_hash, 1_000_000_000);
    let fee_spend = build_fee_only(fee_coin, &manager, 6_000_000)?;
    let mut coin_spends = result.coin_spends;
    let singleton_only = measure_bundle_cost(&coin_spends)?;
    coin_spends.extend(fee_spend.coin_spends);
    let whole = measure_bundle_cost(&coin_spends)?;
    println!(
        "40-record bundle cost: singleton spend {} (bytes {}, execution {}, conditions {}); \
         with fee-only spend {}",
        singleton_only.total_cost,
        singleton_only.byte_cost,
        singleton_only.execution_cost,
        singleton_only.condition_cost,
        whole.total_cost
    );
    assert!(whole.total_cost < COST_BUDGET, "40-record bundle costs {} >= {COST_BUDGET}", whole.total_cost);
    assert!(singleton_only.total_cost < whole.total_cost);

    let mut keys = vec![manager.synthetic_sk.clone()];
    let mut consumer_coins = Vec::new();
    for message in &result.announcement_messages {
        let (spends, key, coin) = consumer_asserting(&mut sim, singleton_puzzle_hash, message)?;
        coin_spends.extend(spends);
        keys.push(key);
        consumer_coins.push(coin);
    }
    sim.spend_coins(coin_spends, &keys)?;
    assert!(sim.coin_state(result.next_coin.coin_id()).is_some(), "singleton recreated by the 40-record spend");
    for coin in consumer_coins {
        assert!(sim.coin_state(coin.coin_id()).unwrap().spent_height.is_some());
    }
    Ok(())
}

/// One record over the limit is refused by the builder itself -- the limit is
/// enforced where the spend is built, not only by the callers' batching.
#[test]
fn the_builder_refuses_more_than_the_maximum_records_per_spend() -> anyhow::Result<()> {
    let Launched { manager, launcher_id, eve_coin, eve_proof, .. } = launch_oracle()?;
    let records: Vec<OracleRecord> =
        (0..=MAX_RECORDS_PER_SPEND as u64).map(|i| record(1_146 + i, i as u8, Source::Live)).collect();
    let err = build_oracle_publish_batch(launcher_id, eve_coin, eve_proof, &manager, &records)
        .err()
        .expect("41 records must be refused");
    assert!(format!("{err:#}").contains("max 40 per spend"), "{err:#}");
    Ok(())
}
