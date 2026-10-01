//! Cross-publisher fee-coin allocation tests (task fix, `docs/research/
//! chain_service.md` §15): the oracle singleton publisher and the PLAY
//! withdrawal publisher fund spends from the *same* operator wallet, so
//! this file drives one of each -- sharing one `FeeCoinAllocator`, exactly
//! as `main.rs` does -- against independent `FakePlayChainClient`s
//! constructed from the same `master_sk` (so each reports the identical
//! default fee-coin candidate, the way two components sharing one real
//! wallet would see the same on-chain UTXOs, without needing to share a
//! single fake chain instance). Exercises the task's explicit requirement:
//! "concurrent oracle + withdrawal batches never share a fee coin".
//!
//! Also home of the fee-coin reserve's tests (`fee_reserve.rs`, `docs/research/
//! chain_service.md` §16): the reserve is the same allocator's other job, and its
//! cases are cross-publisher too -- "split skipped while the *withdrawal*
//! publisher holds a coin", "the *withdrawal* publisher waits while the oracle's
//! idle tick has split its only coin".

mod play_common;

use std::time::Duration;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin, CoinSpend};
use chia_sdk_types::{Condition, run_puzzle_with_cost};
use clvm_traits::FromClvm;
use clvmr::{Allocator, NodePtr, serde::node_from_bytes};

use c2_cli::builders::issue::build_issue_play;
use c2_cli::builders::oracle::build_oracle_launch;
use c2_cli::keys::{TXCH_PREFIX, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::{OracleState, PlayCoinState};
use c2_svc::fee_allocator::{FeeCoinAllocator, fee_coin_of};
use c2_svc::fee_reserve::{FeeCoinPolicy, FeeCoinsBusy, MaintenanceOutcome};
use c2_svc::oracle::chain_client::PushOutcome;
use c2_svc::oracle::publisher::{Publisher, TickFailureState, TickOutcome};
use c2_svc::oracle::queue::QueueFile;
use c2_svc::oracle::types::{OracleRecord, Source};
use c2_svc::play::types::WithdrawalRequest;
use c2_svc::play::withdrawal_publisher::{WithdrawalPublisher, WithdrawalTickOutcome};
use c2_svc::play::withdrawal_queue::WithdrawalQueueFile;
use c2_svc::state_file::save_oracle_state_atomic;
use chia_sdk_utils::Address;

use play_common::FakePlayChainClient;

const PLAY_SUPPLY: u64 = 1_000_000;

struct Fixture {
    _tmp: tempfile::TempDir,
    oracle: Publisher<FakePlayChainClient>,
    withdrawal: WithdrawalPublisher<FakePlayChainClient>,
    manager_puzzle_hash: Bytes32,
    master_sk: SecretKey,
}

fn set_up() -> Result<Fixture> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);

    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-09-26T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // Oracle singleton fixture -- same recipe as `tests/oracle_queue.rs::set_up`.
    let oracle_funding_coin = Coin::new(Bytes32::from([9u8; 32]), manager.puzzle_hash, 1);
    let launch = build_oracle_launch(oracle_funding_coin, &manager, 0)?;
    let oracle_state = OracleState {
        launcher_id: launch.launcher_id,
        manager_synthetic_pk: manager.synthetic_pk,
        current_coin: launch.eve_coin,
        current_proof_dto: launch.eve_proof.into(),
        next_sequence: 1,
    };
    save_oracle_state_atomic(&data_dir, "operator", &oracle_state)?;

    // PLAY treasury fixture -- same recipe as `tests/play_withdrawals.rs::set_up`.
    let play_funding_coin = Coin::new(Bytes32::from([10u8; 32]), manager.puzzle_hash, PLAY_SUPPLY);
    let issued = build_issue_play(play_funding_coin, &manager, PLAY_SUPPLY, 0)?;
    let lineage_proof = issued.cat.lineage_proof.expect("issue-play always sets a lineage proof");
    let play_state = PlayCoinState::new(issued.cat.info.asset_id, issued.cat.coin, lineage_proof);
    c2_svc::play_state_file::save_play_state_atomic(&data_dir, "operator", &play_state)?;

    // The one thing genuinely shared between the two publishers, exactly as
    // `main.rs` constructs it -- see `fee_allocator`'s module doc.
    let fee_allocator = std::sync::Arc::new(FeeCoinAllocator::new());

    let oracle = Publisher {
        data_dir: data_dir.clone(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(0),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: std::sync::Arc::clone(&fee_allocator),
    };

    let withdrawal = WithdrawalPublisher {
        data_dir: data_dir.clone(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(0),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        queue_lock: tokio::sync::Mutex::new(()),
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator,
    };

    Ok(Fixture { _tmp: tmp, oracle, withdrawal, manager_puzzle_hash: manager.puzzle_hash, master_sk })
}

fn record(seq: u64) -> OracleRecord {
    OracleRecord {
        seq,
        ts: 1_790_000_000 + seq as i64,
        price_cents: 65_432_100,
        results_hash: Bytes32::from([seq as u8; 32]),
        source_flag: Source::Live.flag(),
        source_ts: 1_789_999_000 + seq as i64,
        asset: 0,
    }
}

fn withdrawal_request(request_id: &str, to_puzzle_hash: Bytes32, amount_mojos: u64) -> WithdrawalRequest {
    WithdrawalRequest {
        request_id: request_id.to_string(),
        to_address: Address::new(to_puzzle_hash, TXCH_PREFIX.to_string()).encode().unwrap(),
        to_puzzle_hash,
        amount_mojos,
    }
}

/// Task's explicit requirement: "concurrent oracle + withdrawal batches
/// never share a fee coin". Both publishers see the same default fee-coin
/// candidate (same operator wallet) plus one extra distinct one (the
/// wallet's second UTXO) -- without the shared `FeeCoinAllocator`, both
/// would deterministically pick the *same* default coin (a plain gap-scan
/// has no way to know the other publisher already claimed it, the root
/// cause behind `docs/research/chain_service.md` §15's incident); with it,
/// the second publisher to build sees the first coin already reserved and
/// picks the other one instead.
#[tokio::test]
async fn concurrent_oracle_and_withdrawal_batches_never_share_a_fee_coin() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(1_000);
    fx.withdrawal.chain.set_fee_estimate(1_000);

    let second_coin = Coin::new(Bytes32::from([77u8; 32]), fx.manager_puzzle_hash, 2_000_000);
    fx.oracle.chain.add_funding_coin(second_coin);
    fx.withdrawal.chain.add_funding_coin(second_coin);

    fx.oracle.enqueue(vec![record(1)]).await?;
    fx.withdrawal.enqueue(withdrawal_request("w1", Bytes32::from([1u8; 32]), 1_000)).await?;

    let oracle_outcome = fx.oracle.tick().await?;
    assert_eq!(oracle_outcome, TickOutcome::Broadcast { records: 1, accepted: true });

    let withdrawal_outcome = fx.withdrawal.tick().await?;
    assert_eq!(withdrawal_outcome, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });

    let oracle_queue = QueueFile::load(&fx.oracle.data_dir, "operator")?;
    let oracle_in_flight = oracle_queue.in_flight.expect("oracle batch should be in flight");
    let oracle_fee_coin = fee_coin_of(&oracle_in_flight.bundle, oracle_in_flight.spent_coin)
        .expect("oracle bundle must have a fee coin");

    let withdrawal_queue = WithdrawalQueueFile::load(&fx.withdrawal.data_dir, "operator")?;
    let withdrawal_in_flight = withdrawal_queue.in_flight.expect("withdrawal batch should be in flight");
    let withdrawal_fee_coin = fee_coin_of(&withdrawal_in_flight.bundle, withdrawal_in_flight.spent_coin)
        .expect("withdrawal bundle must have a fee coin");

    assert_ne!(
        oracle_fee_coin, withdrawal_fee_coin,
        "the two concurrent in-flight bundles must never reserve the same fee coin"
    );
    assert!(fx.oracle.fee_allocator.is_reserved(oracle_fee_coin.coin_id()).await);
    assert!(fx.oracle.fee_allocator.is_reserved(withdrawal_fee_coin.coin_id()).await);

    Ok(())
}

/// Without a second coin available, a second concurrent caller must fail
/// cleanly once the first has reserved the only one -- never silently reuse
/// a coin an in-flight bundle already depends on (the exact failure mode
/// the shared allocator exists to prevent, see the incident write-up).
#[tokio::test]
async fn second_caller_with_no_other_coin_available_fails_cleanly_instead_of_colliding() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(1_000);
    fx.withdrawal.chain.set_fee_estimate(1_000);
    // No second coin registered this time -- only the one default coin
    // exists in the (simulated, shared) operator wallet.

    fx.oracle.enqueue(vec![record(1)]).await?;
    let oracle_outcome = fx.oracle.tick().await?;
    assert_eq!(oracle_outcome, TickOutcome::Broadcast { records: 1, accepted: true });

    fx.withdrawal.enqueue(withdrawal_request("w1", Bytes32::from([1u8; 32]), 1_000)).await?;
    let withdrawal_outcome = fx.withdrawal.tick().await;
    let err = withdrawal_outcome.expect_err("must not silently reuse the oracle's already-reserved fee coin");
    assert!(
        format!("{err:#}").to_ascii_lowercase().contains("funding coin"),
        "error should explain there is no funding coin left, got: {err:#}"
    );

    Ok(())
}

// --- fee-coin reserve (`fee_reserve.rs`) -------------------------------------------------------------

/// The same wallet on both publishers' fake chains (each fake is its own instance, standing in for one
/// shared on-chain wallet).
impl Fixture {
    fn set_wallet(&self, default_coin_mojos: u64) {
        self.oracle.chain.set_funding_amount(default_coin_mojos);
        self.withdrawal.chain.set_funding_amount(default_coin_mojos);
    }

    fn add_wallet_coin(&self, coin: Coin) {
        self.oracle.chain.add_funding_coin(coin);
        self.withdrawal.chain.add_funding_coin(coin);
    }

    /// The fake wallet's default coin: parent `[7; 32]`, the operator's own puzzle hash.
    fn default_coin(&self, mojos: u64) -> Coin {
        Coin::new(Bytes32::from([7u8; 32]), self.manager_puzzle_hash, mojos)
    }

    /// What confirmation of a split looks like on chain: the input coin is spent and the coins it
    /// created are in the wallet.
    fn land_split(&self, split_spend: &CoinSpend) {
        self.oracle.chain.set_spent(split_spend.coin.coin_id(), 4_800_000, 4_800_001);
        self.withdrawal.chain.set_spent(split_spend.coin.coin_id(), 4_800_000, 4_800_001);
        for coin in created_coins(split_spend) {
            self.add_wallet_coin(coin);
        }
    }
}

/// What a spend's puzzle outputs when run against its solution -- the conditions consensus would see.
fn conditions_of(spend: &CoinSpend) -> Vec<Condition<NodePtr>> {
    let mut allocator = Allocator::new();
    let puzzle = node_from_bytes(&mut allocator, spend.puzzle_reveal.as_ref()).expect("puzzle");
    let solution = node_from_bytes(&mut allocator, spend.solution.as_ref()).expect("solution");
    let reduction = run_puzzle_with_cost(&mut allocator, puzzle, solution, 11_000_000_000, false).expect("run puzzle");
    Vec::<Condition<NodePtr>>::from_clvm(&allocator, reduction.1).expect("conditions")
}

fn created_coins(spend: &CoinSpend) -> Vec<Coin> {
    let parent = spend.coin.coin_id();
    conditions_of(spend)
        .into_iter()
        .filter_map(|c| match c {
            Condition::CreateCoin(create) => Some(Coin::new(parent, create.puzzle_hash, create.amount)),
            _ => None,
        })
        .collect()
}

fn reserved_fee(spend: &CoinSpend) -> u64 {
    conditions_of(spend)
        .into_iter()
        .filter_map(|c| match c {
            Condition::ReserveFee(fee) => Some(fee.amount),
            _ => None,
        })
        .sum()
}

fn oracle_with(
    data_dir: &std::path::Path,
    chain: FakePlayChainClient,
    allocator: std::sync::Arc<FeeCoinAllocator>,
) -> Publisher<FakePlayChainClient> {
    Publisher {
        data_dir: data_dir.to_path_buf(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain,
        retry_backoff: Duration::from_secs(0),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: allocator,
    }
}

const BIG_COIN: u64 = 10_000_000_000;
const SPLIT_FEE: u64 = 1_000;

/// The headline case: one big coin in the wallet, below the minimum of 4 free fee coins -- the oracle's
/// idle tick splits it, ONCE. The split is a single spend of that coin into 4 fee-sized coins plus change
/// that pays its own fee; its input stays reserved (nobody may pick it as a fee coin) until it lands, and
/// after it lands the wallet is healthy and nothing is split again.
#[tokio::test]
async fn a_wallet_below_the_minimum_is_split_once_and_then_left_alone() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    let input = fx.default_coin(BIG_COIN);

    // Nothing queued, nothing in flight: the idle tick pushes the split.
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 1);
    let bundle = fx.oracle.chain.pushed_bundles().remove(0);
    assert_eq!(bundle.coin_spends.len(), 1, "the split pays its own fee: one spend, no second fee coin");
    let spend = &bundle.coin_spends[0];
    assert_eq!(spend.coin, input, "the largest (here the only) wallet coin is the one split");

    let mut amounts: Vec<u64> = created_coins(spend).iter().map(|c| c.amount).collect();
    amounts.sort_unstable();
    // 4 fee coins of 200,000,000 -- one mojo apart, identical coins cannot be created twice -- plus change.
    assert_eq!(amounts, vec![200_000_000, 200_000_001, 200_000_002, 200_000_003, BIG_COIN - 800_000_006 - SPLIT_FEE]);
    assert_eq!(reserved_fee(spend), SPLIT_FEE);
    assert!(
        created_coins(spend).iter().all(|c| c.puzzle_hash == fx.manager_puzzle_hash),
        "every output returns to the operator's own address"
    );
    assert!(fx.oracle.fee_allocator.is_reserved(input.coin_id()).await, "the coin being split is held back");
    assert!(fx.oracle.fee_allocator.split_in_flight());

    // Still waiting in the mempool: no second split, whatever the tick count.
    fx.oracle.chain.set_in_mempool(bundle.name(), true);
    for _ in 0..3 {
        assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    }
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "one split in flight at a time");

    // It lands: the input is spent, the new coins exist.
    fx.land_split(spend);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert!(!fx.oracle.fee_allocator.split_in_flight());
    assert!(!fx.oracle.fee_allocator.is_reserved(input.coin_id()).await);

    // The wallet now holds 5 free fee coins (4 + the change): healthy, nothing more to do.
    for _ in 0..3 {
        assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    }
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "the split happened exactly once");
    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Healthy { free: 5 })), "{outcome:?}");
    Ok(())
}

/// Enough free fee coins -> no split.
#[tokio::test]
async fn no_split_when_enough_fee_coins_exist() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(250_000_000);
    for (parent, amount) in [(21u8, 200_000_000u64), (22, 400_000_000), (23, 5_000_000_000)] {
        fx.oracle.chain.add_funding_coin(wallet_coin(parent, fx.manager_puzzle_hash, amount));
    }

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 0);
    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Healthy { free: 4 })), "{outcome:?}");
    Ok(())
}

fn wallet_coin(parent: u8, puzzle_hash: Bytes32, amount: u64) -> Coin {
    Coin::new(Bytes32::from([parent; 32]), puzzle_hash, amount)
}

/// Only coins of the full fee-coin size count towards the minimum: three coins one mojo short of
/// 200,000,000 do not make the reserve healthy.
#[tokio::test]
async fn only_coins_of_the_full_fee_coin_size_count() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    for parent in [21u8, 22, 23] {
        fx.oracle.chain.add_funding_coin(wallet_coin(parent, fx.manager_puzzle_hash, 199_999_999));
    }

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "1 coin of the full size < 4: split");
    Ok(())
}

/// "Split skipped while a coin is reserved": a fee coin held by anyone's in-flight spend (here: reserved
/// directly, as the withdrawal publisher would) blocks the split; releasing it lets it run.
#[tokio::test]
async fn no_split_while_a_fee_coin_is_reserved_by_an_in_flight_spend() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    let held = Bytes32::from([99u8; 32]);
    fx.oracle.fee_allocator.reserve_specific(held).await;

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Skipped(reason)) if reason.contains("reserved")), "{outcome:?}");
    assert_eq!(fx.oracle.chain.push_call_count(), 0);

    fx.oracle.fee_allocator.release(held).await;
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "the split runs once nothing is held");
    Ok(())
}

/// Same, with the real thing: the WITHDRAWAL publisher has a bundle in flight (its fee coin reserved),
/// the oracle is idle -- the oracle's reserve pass must stay out of the wallet.
#[tokio::test]
async fn no_split_while_the_withdrawal_publisher_has_a_batch_in_flight() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.withdrawal.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    fx.withdrawal.enqueue(withdrawal_request("w1", Bytes32::from([1u8; 32]), 1_000)).await?;
    assert_eq!(fx.withdrawal.tick().await?, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 0, "a coin is reserved: the oracle must not split");
    Ok(())
}

/// ...and while the ORACLE itself has a batch in flight, the reserve never starts -- however low the
/// wallet has meanwhile fallen. (The batch is published against a healthy wallet, then three of its four
/// fee coins are spent elsewhere.)
#[tokio::test]
async fn no_split_while_the_oracle_has_a_batch_in_flight() -> Result<()> {
    let fx = set_up()?;
    // A zero fee attaches no fee coin, so nothing is reserved: the only thing that can keep the
    // reserve from splitting below is that the oracle has a batch in flight.
    fx.oracle.chain.set_fee_estimate(0);
    fx.set_wallet(250_000_000);
    let others = [(21u8, 200_000_000u64), (22, 400_000_000), (23, 5_000_000_000)];
    for (parent, amount) in others {
        fx.oracle.chain.add_funding_coin(wallet_coin(parent, fx.manager_puzzle_hash, amount));
    }
    fx.oracle.enqueue(vec![record(1)]).await?;
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    // The batch sits in the node's mempool (otherwise this fixture's zero backoff would re-push it).
    let in_flight = QueueFile::load(&fx.oracle.data_dir, "operator")?.in_flight.expect("in flight");
    fx.oracle.chain.set_in_mempool(in_flight.bundle.name(), true);
    fx.oracle.enqueue(vec![record(2)]).await?;

    // Now the wallet falls below the minimum: three of the four coins are spent elsewhere.
    for (parent, amount) in others {
        fx.oracle.chain.set_spent(wallet_coin(parent, fx.manager_puzzle_hash, amount).coin_id(), 1, 2);
    }
    for _ in 0..3 {
        assert_eq!(fx.oracle.tick().await?, TickOutcome::WaitingForConfirmation);
    }
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "only the oracle's own batch was ever pushed");
    let outcome = fx.oracle.maintain_fee_coins(false).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Skipped(_))), "{outcome:?}");

    // The moment nothing is in flight any more (the batch confirms), the reserve may act.
    fx.oracle.chain.set_confirmed(in_flight.next_coin.coin_id(), 4_800_000);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Confirmed { records: 1 });
    Ok(())
}

/// A dry run (`C2_PUSH=0`) never splits -- and never even looks at the wallet for it.
#[tokio::test]
async fn a_dry_run_never_splits() -> Result<()> {
    let mut fx = set_up()?;
    fx.oracle.push_enabled = false;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert!(fx.oracle.maintain_fee_coins(true).await.is_none());
    assert_eq!(fx.oracle.chain.push_call_count(), 0);
    assert!(!fx.oracle.fee_allocator.split_in_flight());
    Ok(())
}

/// The reserve can be switched off (`C2_FEE_COINS_MIN=0`).
#[tokio::test]
async fn a_minimum_of_zero_switches_the_reserve_off() -> Result<()> {
    let fx = set_up()?;
    let off = oracle_with(
        &fx.oracle.data_dir,
        FakePlayChainClient::new(),
        std::sync::Arc::new(FeeCoinAllocator::with_policy(FeeCoinPolicy { min_free: 0, coin_mojos: 200_000_000 })),
    );
    off.chain.set_funding_amount(BIG_COIN);
    off.chain.set_fee_estimate(SPLIT_FEE);
    assert_eq!(off.tick().await?, TickOutcome::Idle);
    assert_eq!(off.chain.push_call_count(), 0);
    Ok(())
}

/// A wallet with no coin big enough to cut the reserve from is left alone, loudly (a warning, the
/// outcome) and without rescanning every tick.
#[tokio::test]
async fn no_split_when_no_coin_is_large_enough() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(700_000_000); // 4 x 200,000,000 + 6 + fee does not fit

    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert_eq!(
        outcome,
        Some(MaintenanceOutcome::NoCoinLargeEnough { largest: 700_000_000, needed: 800_000_006 }),
    );
    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Skipped(_))), "no rescan right away: {outcome:?}");
    assert_eq!(fx.oracle.chain.push_call_count(), 0);

    // A coin that covers the outputs but not the outputs plus the fee is just as unsplittable.
    let tight = set_up()?;
    tight.oracle.chain.set_fee_estimate(5_000);
    tight.set_wallet(800_000_006 + 4_999);
    let outcome = tight.oracle.maintain_fee_coins(true).await;
    assert_eq!(
        outcome,
        Some(MaintenanceOutcome::NoCoinLargeEnough { largest: 800_000_006 + 4_999, needed: 800_000_006 + 5_000 }),
    );
    assert_eq!(tight.oracle.chain.push_call_count(), 0);
    assert!(!tight.oracle.fee_allocator.is_reserved(tight.default_coin(800_000_006 + 4_999).coin_id()).await);
    Ok(())
}

/// The split's fee is estimated like every other fee and capped at `C2_MAX_FEE_MOJOS`.
#[tokio::test]
async fn the_splits_own_fee_is_capped_at_the_maximum_fee() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(9_000_000_000); // far above the 50,000,000 cap
    fx.set_wallet(BIG_COIN);

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    let bundle = fx.oracle.chain.pushed_bundles().remove(0);
    assert_eq!(reserved_fee(&bundle.coin_spends[0]), 50_000_000);
    Ok(())
}

/// The case the wait exists for: the oracle's idle tick has split the wallet's ONLY coin, and a record
/// and a withdrawal arrive while that split is still pending. Neither publisher may fail its tick
/// ("no funding coin" would count towards degrading `/health`) -- both wait, their work stays queued --
/// and once the split lands each takes its own fee coin, the smallest ones that fit.
#[tokio::test]
async fn publishers_wait_instead_of_failing_while_the_split_holds_the_only_fee_coin() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.withdrawal.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle); // pushes the split
    let split_bundle = fx.oracle.chain.pushed_bundles().remove(0);
    fx.oracle.chain.set_in_mempool(split_bundle.name(), true);

    fx.oracle.enqueue(vec![record(1)]).await?;
    fx.withdrawal.enqueue(withdrawal_request("w1", Bytes32::from([1u8; 32]), 1_000)).await?;
    for _ in 0..4 {
        // More than the failure threshold (3): had these been errors, /health would be degraded by now.
        assert_eq!(fx.oracle.tick().await?, TickOutcome::WaitingForConfirmation);
        assert_eq!(fx.withdrawal.tick().await?, WithdrawalTickOutcome::WaitingForConfirmation);
    }
    assert!(fx.oracle.health_check().await?.is_none(), "waiting for the split is not a tick failure");
    assert!(fx.withdrawal.health_check().await?.is_none());
    assert_eq!(fx.oracle.record_status(1)?.state, "queued", "the record never left the queue");
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "nothing but the split was pushed");
    assert_eq!(fx.withdrawal.chain.push_call_count(), 0);

    fx.land_split(&split_bundle.coin_spends[0]);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    assert_eq!(fx.withdrawal.tick().await?, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });

    let oracle_queue = QueueFile::load(&fx.oracle.data_dir, "operator")?;
    let oracle_in_flight = oracle_queue.in_flight.expect("oracle batch in flight");
    let oracle_fee_coin = fee_coin_of(&oracle_in_flight.bundle, oracle_in_flight.spent_coin).expect("fee coin");
    let withdrawal_queue = WithdrawalQueueFile::load(&fx.withdrawal.data_dir, "operator")?;
    let withdrawal_in_flight = withdrawal_queue.in_flight.expect("withdrawal batch in flight");
    let withdrawal_fee_coin =
        fee_coin_of(&withdrawal_in_flight.bundle, withdrawal_in_flight.spent_coin).expect("fee coin");
    assert_eq!(oracle_fee_coin.amount, 200_000_000, "best fit: the smallest coin that covers the fee");
    assert_eq!(withdrawal_fee_coin.amount, 200_000_001, "the next smallest -- never the oracle's coin");
    Ok(())
}

/// Pending records do not stop the reserve: with a single coin in the wallet and a record already
/// waiting, the same tick that finds nothing in flight pushes the split FIRST, and the batch that
/// wanted that coin waits (its record stays queued, no failure) until the split lands -- then goes out
/// with one of the new coins. The alternative -- publishing with the only coin and splitting "some other
/// time" -- would never end the single-coin trap while records keep arriving.
#[tokio::test]
async fn pending_records_do_not_stop_the_split_and_wait_for_it_without_failing() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    fx.oracle.enqueue(vec![record(1)]).await?;

    assert_eq!(fx.oracle.tick().await?, TickOutcome::WaitingForConfirmation);
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "the only thing pushed is the split");
    let split_bundle = fx.oracle.chain.pushed_bundles().remove(0);
    assert_eq!(split_bundle.coin_spends[0].coin, fx.default_coin(BIG_COIN));
    assert_eq!(fx.oracle.record_status(1)?.state, "queued", "the record waits in the queue");
    fx.oracle.chain.set_in_mempool(split_bundle.name(), true);
    for _ in 0..3 {
        assert_eq!(fx.oracle.tick().await?, TickOutcome::WaitingForConfirmation);
    }
    assert!(fx.oracle.health_check().await?.is_none(), "waiting is not a failure");

    fx.land_split(&split_bundle.coin_spends[0]);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    let in_flight = QueueFile::load(&fx.oracle.data_dir, "operator")?.in_flight.expect("in flight");
    assert_eq!(fee_coin_of(&in_flight.bundle, in_flight.spent_coin).expect("fee coin").amount, 200_000_000);
    Ok(())
}

/// Restart safety without a file: a process restarted while its split is still pending in the node's
/// mempool finds the wallet below the minimum, picks the same coin, asks the node -- and waits instead
/// of building a second split.
#[tokio::test]
async fn a_restart_while_the_split_is_in_the_mempool_does_not_split_again() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    let input = fx.default_coin(BIG_COIN);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    let split_bundle = fx.oracle.chain.pushed_bundles().remove(0);

    // The process restarts: new allocator (no memory of the split), new chain connection -- and the
    // node still has the split in its mempool.
    let restarted = oracle_with(
        &fx.oracle.data_dir,
        FakePlayChainClient::new(),
        std::sync::Arc::new(FeeCoinAllocator::new()),
    );
    restarted.chain.set_funding_amount(BIG_COIN);
    restarted.chain.set_fee_estimate(SPLIT_FEE);
    restarted.chain.set_coin_in_mempool(input.coin_id(), true);

    assert_eq!(restarted.tick().await?, TickOutcome::Idle);
    let outcome = restarted.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::Skipped(reason)) if reason.contains("mempool")), "{outcome:?}");
    assert_eq!(restarted.chain.push_call_count(), 0, "the split from before the restart is still pending");
    assert!(!restarted.fee_allocator.is_reserved(input.coin_id()).await, "the failed attempt left no reservation");

    // The split lands (seen by the restarted process's own chain connection): healthy, still no push.
    restarted.chain.set_coin_in_mempool(input.coin_id(), false);
    restarted.chain.set_spent(input.coin_id(), 4_800_000, 4_800_001);
    for coin in created_coins(&split_bundle.coin_spends[0]) {
        restarted.chain.add_funding_coin(coin);
    }
    assert_eq!(restarted.tick().await?, TickOutcome::Idle);
    assert_eq!(restarted.chain.push_call_count(), 0);
    Ok(())
}

/// A split the node refuses leaves nothing reserved behind, and is not retried before the backoff.
#[tokio::test]
async fn a_rejected_split_releases_its_coin_and_backs_off() -> Result<()> {
    let mut fx = set_up()?;
    fx.oracle.retry_backoff = Duration::from_secs(300);
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    let input = fx.default_coin(BIG_COIN);
    fx.oracle.chain.set_push_outcome(PushOutcome {
        accepted: false,
        status: None,
        error: Some("Failed to include transaction, error INVALID_FEE_TOO_CLOSE_TO_ZERO".to_string()),
    });

    let outcome = fx.oracle.maintain_fee_coins(true).await;
    assert!(matches!(outcome, Some(MaintenanceOutcome::SplitRejected { .. })), "{outcome:?}");
    assert_eq!(fx.oracle.chain.push_call_count(), 1);
    assert!(!fx.oracle.fee_allocator.is_reserved(input.coin_id()).await, "a rejected split holds nothing");
    assert!(!fx.oracle.fee_allocator.split_in_flight());

    for _ in 0..3 {
        assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    }
    assert_eq!(fx.oracle.chain.push_call_count(), 1, "backing off: no immediate retry");
    Ok(())
}

/// With no backoff to wait out, a rejected split is simply tried again on the next idle tick.
#[tokio::test]
async fn a_rejected_split_is_tried_again_once_the_backoff_has_passed() -> Result<()> {
    let fx = set_up()?; // retry_backoff = 0
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    fx.oracle.chain.set_push_outcome(PushOutcome { accepted: false, status: None, error: Some("mempool full".into()) });
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 1);

    fx.oracle.chain.set_push_outcome(PushOutcome { accepted: true, status: Some("SUCCESS".into()), error: None });
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 2);
    assert!(fx.oracle.fee_allocator.split_in_flight());
    Ok(())
}

/// A pushed split that neither confirms nor stays in the mempool is declared dropped (its coin freed),
/// and only THEN is another one built -- never two in flight.
#[tokio::test]
async fn a_split_that_vanished_from_the_mempool_is_replaced_not_duplicated() -> Result<()> {
    let fx = set_up()?; // retry_backoff = 0: no grace period to wait out
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    let input = fx.default_coin(BIG_COIN);

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle); // push 1, accepted, in flight
    assert_eq!(fx.oracle.chain.push_call_count(), 1);

    // The node never shows it in the mempool, and the coin is unspent: the next tick finds it dropped
    // (and starts nothing else in the same pass).
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle);
    assert_eq!(fx.oracle.chain.push_call_count(), 1);
    assert!(!fx.oracle.fee_allocator.split_in_flight());
    assert!(!fx.oracle.fee_allocator.is_reserved(input.coin_id()).await);

    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle); // push 2
    assert_eq!(fx.oracle.chain.push_call_count(), 2);
    assert!(fx.oracle.fee_allocator.split_in_flight());
    Ok(())
}

/// `GET /v1/oracle/status`: `feeCoinsFree` / `feeCoinsFreeMojos` come from the last wallet scan
/// (None before the first), count only unreserved coins of the full fee-coin size, and are refreshed
/// by the same background scan as the balance.
#[tokio::test]
async fn status_shows_the_free_fee_coins_from_the_last_wallet_scan() -> Result<()> {
    let fx = set_up()?;
    fx.set_wallet(300_000_000);
    fx.oracle.chain.add_funding_coin(wallet_coin(21, fx.manager_puzzle_hash, 5_000_000_000));
    fx.oracle.chain.add_funding_coin(wallet_coin(22, fx.manager_puzzle_hash, 150_000_000)); // too small to count

    let before = fx.oracle.status().await?;
    assert_eq!((before.fee_coins_free, before.fee_coins_free_mojos), (None, None), "unknown before the first scan");

    fx.oracle.refresh_balance_snapshot().await;
    let status = fx.oracle.status().await?;
    assert_eq!(status.fee_coins_free, Some(2));
    assert_eq!(status.fee_coins_free_mojos.as_deref(), Some("5300000000"));
    assert_eq!(status.operator_balance_mojos.as_deref(), Some("5450000000"), "balance = the same scan, summed");

    // A reserved coin is not free.
    fx.oracle.fee_allocator.reserve_specific(fx.default_coin(300_000_000).coin_id()).await;
    fx.oracle.refresh_balance_snapshot().await;
    let status = fx.oracle.status().await?;
    assert_eq!(status.fee_coins_free, Some(1));
    assert_eq!(status.fee_coins_free_mojos.as_deref(), Some("5000000000"));
    Ok(())
}

/// The oracle pays its fee with the smallest coin that covers it (best fit), end to end through a tick.
#[tokio::test]
async fn the_oracle_pays_its_fee_with_the_smallest_coin_that_covers_it() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(2_500_000);
    fx.oracle.chain.set_funding_amount(900_000_000);
    for (parent, amount) in [(21u8, 5_000_000u64), (22, 3_000_000), (23, 2_000_000)] {
        fx.oracle.chain.add_funding_coin(wallet_coin(parent, fx.manager_puzzle_hash, amount));
    }
    fx.oracle.enqueue(vec![record(1)]).await?;
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });

    let queue = QueueFile::load(&fx.oracle.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("in flight");
    let fee_coin = fee_coin_of(&in_flight.bundle, in_flight.spent_coin).expect("fee coin");
    assert_eq!(fee_coin.amount, 3_000_000, "the 2M coin is too small, the 3M one is the smallest that fits");
    Ok(())
}

/// Allocator level: the order coins are handed out in for one fee size -- smallest sufficient first,
/// a reserved coin never again, and an error (not a collision) once none is left. And the error is a
/// plain "no coin" unless a split is the reason.
#[tokio::test]
async fn reserve_hands_out_coins_smallest_first_and_never_a_reserved_one() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_funding_amount(900_000_000);
    for (parent, amount) in [(21u8, 5_000_000u64), (22, 3_000_000), (23, 2_000_000)] {
        fx.oracle.chain.add_funding_coin(wallet_coin(parent, fx.manager_puzzle_hash, amount));
    }
    let allocator = FeeCoinAllocator::new();

    let mut picked = Vec::new();
    for _ in 0..3 {
        picked.push(allocator.reserve(&fx.oracle.chain, &fx.master_sk, 2_500_000).await?.1.amount);
    }
    assert_eq!(picked, vec![3_000_000, 5_000_000, 900_000_000]);

    let err = allocator.reserve(&fx.oracle.chain, &fx.master_sk, 2_500_000).await.err().expect("no coin is left");
    assert!(err.downcast_ref::<FeeCoinsBusy>().is_none(), "no split in flight: a plain 'no coin' error");
    assert!(format!("{err:#}").contains("no unspent, unreserved coin"), "{err:#}");
    Ok(())
}

/// While a split is in flight and holds the only big coin, `reserve` fails with the typed
/// `FeeCoinsBusy` -- recognisable even through the publishers' `.context(..)` wrapping.
#[tokio::test]
async fn reserve_reports_busy_only_when_the_split_is_the_reason_nothing_is_free() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(SPLIT_FEE);
    fx.set_wallet(BIG_COIN);
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Idle); // split in flight, the only coin reserved
    assert!(fx.oracle.fee_allocator.split_in_flight());

    let err = fx
        .oracle
        .fee_allocator
        .reserve(&fx.oracle.chain, &fx.master_sk, 1_000)
        .await
        .err()
        .expect("the only big coin is held by the split")
        .context("no funding coin to pay the oracle-publish batch fee with");
    assert!(err.downcast_ref::<FeeCoinsBusy>().is_some(), "{err:#}");
    Ok(())
}

/// The FIRST fee attempt is capped at `C2_MAX_FEE_MOJOS` like every rebuild -- for both publishers.
#[tokio::test]
async fn the_first_fee_attempt_is_capped_at_the_maximum_fee() -> Result<()> {
    let fx = set_up()?;
    fx.oracle.chain.set_fee_estimate(1_000_000_000);
    fx.withdrawal.chain.set_fee_estimate(1_000_000_000);
    fx.set_wallet(BIG_COIN);
    for parent in [21u8, 22, 23] {
        fx.add_wallet_coin(wallet_coin(parent, fx.manager_puzzle_hash, BIG_COIN)); // 4 free coins: healthy, no split
    }

    fx.oracle.enqueue(vec![record(1)]).await?;
    assert_eq!(fx.oracle.tick().await?, TickOutcome::Broadcast { records: 1, accepted: true });
    assert_eq!(QueueFile::load(&fx.oracle.data_dir, "operator")?.in_flight.unwrap().fee_mojos, 50_000_000);

    fx.withdrawal.enqueue(withdrawal_request("w1", Bytes32::from([1u8; 32]), 1_000)).await?;
    assert_eq!(fx.withdrawal.tick().await?, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    assert_eq!(WithdrawalQueueFile::load(&fx.withdrawal.data_dir, "operator")?.in_flight.unwrap().fee_mojos, 50_000_000);
    Ok(())
}
