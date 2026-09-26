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

mod play_common;

use anyhow::Result;
use chia_protocol::{Bytes32, Coin};

use c2_cli::builders::issue::build_issue_play;
use c2_cli::builders::oracle::build_oracle_launch;
use c2_cli::keys::{TXCH_PREFIX, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::{OracleState, PlayCoinState};
use c2_svc::fee_allocator::{FeeCoinAllocator, fee_coin_of};
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

    Ok(Fixture { _tmp: tmp, oracle, withdrawal, manager_puzzle_hash: manager.puzzle_hash })
}

fn record(seq: u64) -> OracleRecord {
    OracleRecord {
        seq,
        ts: 1_790_000_000 + seq as i64,
        price_cents: 65_432_100,
        results_hash: Bytes32::from([seq as u8; 32]),
        source_flag: Source::Live.flag(),
        source_ts: 1_789_999_000 + seq as i64,
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
