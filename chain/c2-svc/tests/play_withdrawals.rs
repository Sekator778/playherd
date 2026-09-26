//! Queueing-logic unit tests for the PLAY withdrawal publisher (Р-6,
//! decision Р-53 2026-09-26) -- no network, no simulator, just
//! `WithdrawalPublisher::{enqueue, tick}` against `FakePlayChainClient`
//! (`tests/play_common/mod.rs`) and a temp data dir. Mirrors
//! `tests/oracle_queue.rs`'s own approach for the oracle publisher. Exercises
//! the task's explicit requirements: "batched withdrawal with change back to
//! treasury", "idempotency", "restart mid-flight".

mod play_common;

use anyhow::Result;
use chia_protocol::{Bytes32, Coin};

use c2_cli::builders::issue::build_issue_play;
use c2_cli::keys::{TXCH_PREFIX, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::PlayCoinState;
use c2_svc::play::types::WithdrawalRequest;
use c2_svc::play::withdrawal_publisher::{WithdrawalPublisher, WithdrawalTickOutcome};
use c2_svc::play::withdrawal_queue::WithdrawalQueueFile;
use c2_svc::play_state_file::load_play_state;
use chia_sdk_utils::Address;

use play_common::FakePlayChainClient;

const PLAY_SUPPLY: u64 = 1_000_000;

struct Fixture {
    _tmp: tempfile::TempDir,
    publisher: WithdrawalPublisher<FakePlayChainClient>,
    data_dir: std::path::PathBuf,
}

fn set_up() -> Result<Fixture> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();

    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let owner = derive_wallet_key(&master_sk, 0);

    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-09-26T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // Pure local CLVM construction (no chain/simulator), same reasoning as
    // `tests/oracle_queue.rs::set_up`'s `build_oracle_launch` call.
    let funding_coin = Coin::new(Bytes32::from([9u8; 32]), owner.puzzle_hash, PLAY_SUPPLY);
    let issued = build_issue_play(funding_coin, &owner, PLAY_SUPPLY, 0)?;
    let lineage_proof = issued.cat.lineage_proof.expect("issue-play always sets a lineage proof");
    let play_state = PlayCoinState::new(issued.cat.info.asset_id, issued.cat.coin, lineage_proof);
    c2_svc::play_state_file::save_play_state_atomic(&data_dir, "operator", &play_state)?;

    let publisher = WithdrawalPublisher {
        data_dir: data_dir.clone(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(300),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        queue_lock: tokio::sync::Mutex::new(()),
    };

    Ok(Fixture { _tmp: tmp, publisher, data_dir })
}

fn withdrawal_request(request_id: &str, to_puzzle_hash: Bytes32, amount_mojos: u64) -> WithdrawalRequest {
    WithdrawalRequest {
        request_id: request_id.to_string(),
        to_address: Address::new(to_puzzle_hash, TXCH_PREFIX.to_string()).encode().unwrap(),
        to_puzzle_hash,
        amount_mojos,
    }
}

#[tokio::test]
async fn batch_withdrawal_pays_out_and_returns_change_to_treasury() -> Result<()> {
    let fx = set_up()?;

    let alice = Bytes32::from([1u8; 32]);
    let bob = Bytes32::from([2u8; 32]);
    fx.publisher.enqueue(withdrawal_request("w1", alice, 100_000)).await?;
    fx.publisher.enqueue(withdrawal_request("w2", bob, 50_000)).await?;

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Broadcast { requests: 2, accepted: true });
    assert_eq!(fx.publisher.status("w1")?.state, "in_flight");
    assert_eq!(fx.publisher.status("w2")?.state, "in_flight");

    let queue = WithdrawalQueueFile::load(&fx.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("batch should be in flight");
    let next_coin = in_flight.next_coin;
    assert_eq!(next_coin.amount, PLAY_SUPPLY - 150_000, "change must return the untouched remainder to the treasury");

    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 5_000_000);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Confirmed { requests: 2 });

    let status_w1 = fx.publisher.status("w1")?;
    assert_eq!(status_w1.state, "confirmed");
    assert_eq!(status_w1.height, Some(5_000_000));
    assert_eq!(status_w1.coin_id.as_deref(), Some(format!("0x{}", next_coin.coin_id()).as_str()));
    assert_eq!(fx.publisher.status("w2")?.state, "confirmed");

    // The treasury's tracked coin advanced to the change coin -- the next
    // batch will spend from here.
    let play_state = load_play_state(&fx.data_dir, "operator")?;
    assert_eq!(play_state.coin, next_coin);

    Ok(())
}

#[tokio::test]
async fn enqueue_is_idempotent_by_request_id() -> Result<()> {
    let fx = set_up()?;
    let alice = Bytes32::from([1u8; 32]);

    let first = fx.publisher.enqueue(withdrawal_request("dup", alice, 1_000)).await?;
    let second = fx.publisher.enqueue(withdrawal_request("dup", alice, 1_000)).await?;
    assert!(first, "first submission of a fresh requestId must be accepted");
    assert!(!second, "resubmitting a known requestId must be a no-op, not a duplicate");

    let queue = WithdrawalQueueFile::load(&fx.data_dir, "operator")?;
    assert_eq!(queue.pending_count(), 1, "the duplicate must not be queued a second time");

    // Still idempotent once the request has moved past `pending`.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    assert!(!fx.publisher.enqueue(withdrawal_request("dup", alice, 1_000)).await?);

    Ok(())
}

#[tokio::test]
async fn restart_resumes_and_confirms_a_pending_in_flight_batch() -> Result<()> {
    let fx = set_up()?;
    let alice = Bytes32::from([1u8; 32]);
    fx.publisher.enqueue(withdrawal_request("w1", alice, 100_000)).await?;

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });

    let queue = WithdrawalQueueFile::load(&fx.data_dir, "operator")?;
    let next_coin = queue.in_flight.unwrap().next_coin;

    // A brand-new `WithdrawalPublisher` (fresh `FakePlayChainClient`, no
    // shared in-memory state) pointed at the *same* data dir -- stands in
    // for a process restart (same convention as `tests/oracle_queue.rs::
    // restart_resumes_a_pending_in_flight_spend`).
    let restarted = WithdrawalPublisher {
        data_dir: fx.data_dir.clone(),
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain: FakePlayChainClient::new(),
        retry_backoff: std::time::Duration::from_secs(300),
        max_retry_attempts: 3,
        max_fee_mojos: 50_000_000,
        fee_target_time_secs: 60,
        queue_lock: tokio::sync::Mutex::new(()),
    };
    restarted.chain.set_confirmed(next_coin.coin_id(), 5_000_001);

    let outcome = restarted.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Confirmed { requests: 1 }, "restart must resume and confirm the persisted batch");
    assert_eq!(restarted.status("w1")?.state, "confirmed");

    Ok(())
}
