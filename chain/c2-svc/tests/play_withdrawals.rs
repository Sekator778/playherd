//! Queueing-logic unit tests for the PLAY withdrawal publisher (Р-6,
//! decision Р-53 2026-09-26) -- no network, no simulator, just
//! `WithdrawalPublisher::{enqueue, tick}` against `FakePlayChainClient`
//! (`tests/play_common/mod.rs`) and a temp data dir. Mirrors
//! `tests/oracle_queue.rs`'s own approach for the oracle publisher. Exercises
//! the task's explicit requirements: "batched withdrawal with change back to
//! treasury", "idempotency", "restart mid-flight".

mod play_common;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_protocol::{Bytes32, Coin};

use c2_cli::builders::issue::build_issue_play;
use c2_cli::cat_chain::cat_puzzle_hash;
use c2_cli::keys::{TXCH_PREFIX, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::PlayCoinState;
use c2_svc::oracle::chain_client::CoinRecordInfo;
use c2_svc::play::chain_client::PuzzleHashCoinRecord;
use c2_svc::play::types::WithdrawalRequest;
use c2_svc::play::withdrawal_publisher::{WithdrawalPublisher, WithdrawalTickOutcome};
use c2_svc::play::withdrawal_queue::WithdrawalQueueFile;
use c2_svc::play::withdrawal_spend::build_play_withdrawal_batch;
use c2_svc::play_state_file::load_play_state;
use chia_sdk_driver::{Cat, CatInfo};
use chia_sdk_utils::Address;

use play_common::FakePlayChainClient;

const PLAY_SUPPLY: u64 = 1_000_000;

struct Fixture {
    _tmp: tempfile::TempDir,
    publisher: WithdrawalPublisher<FakePlayChainClient>,
    data_dir: std::path::PathBuf,
    /// Exposed so self-heal tests can independently rebuild the treasury's
    /// `Cat`/owner key and construct a genuine external spend of it (see
    /// `treasury_self_heal_adopts_the_live_coin_when_the_cached_one_is_stale`).
    master_sk: SecretKey,
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
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(c2_svc::oracle::publisher::TickFailureState::default()),
        tick_failure_threshold: 3,
    };

    Ok(Fixture { _tmp: tmp, publisher, data_dir, master_sk })
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
    // Task fix (`docs/research/chain_service.md` §13): w1's own payout coin
    // id, not the treasury's change coin id (which `advance_in_flight` used
    // to report for every request in a batch -- see `tests/
    // play_withdrawals.rs::each_request_in_a_batch_gets_its_own_correct_coin_id_and_the_change_coin_is_not_reported`
    // for the fuller, 3-request version of this check).
    let w1_payout_coin_id = in_flight.payout_coin_ids[0];
    assert_ne!(w1_payout_coin_id, next_coin.coin_id(), "w1's payout coin must not be the treasury's own change coin");

    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 5_000_000);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Confirmed { requests: 2 });

    let status_w1 = fx.publisher.status("w1")?;
    assert_eq!(status_w1.state, "confirmed");
    assert_eq!(status_w1.height, Some(5_000_000));
    assert_eq!(status_w1.coin_id.as_deref(), Some(format!("0x{w1_payout_coin_id}").as_str()));
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
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(c2_svc::oracle::publisher::TickFailureState::default()),
        tick_failure_threshold: 3,
    };
    restarted.chain.set_confirmed(next_coin.coin_id(), 5_000_001);

    let outcome = restarted.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Confirmed { requests: 1 }, "restart must resume and confirm the persisted batch");
    assert_eq!(restarted.status("w1")?.state, "confirmed");

    Ok(())
}

/// Task fix (`docs/research/chain_service.md` §13): `advance_in_flight` used
/// to record every request in a batch as confirmed at the treasury's
/// *change* coin's id -- correct by coincidence for a single-request batch
/// (like every other test in this file), wrong for the rest of a bigger one.
/// A 3-request batch, checked end to end through `WithdrawalPublisher::
/// status`: each request gets its own distinct coin id, and the change coin
/// is never one of them.
#[tokio::test]
async fn each_request_in_a_batch_gets_its_own_correct_coin_id_and_the_change_coin_is_not_reported() -> Result<()> {
    let fx = set_up()?;

    let alice = Bytes32::from([1u8; 32]);
    let bob = Bytes32::from([2u8; 32]);
    let carol = Bytes32::from([3u8; 32]);
    fx.publisher.enqueue(withdrawal_request("w1", alice, 100_000)).await?;
    fx.publisher.enqueue(withdrawal_request("w2", bob, 50_000)).await?;
    fx.publisher.enqueue(withdrawal_request("w3", carol, 25_000)).await?;

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Broadcast { requests: 3, accepted: true });

    let queue = WithdrawalQueueFile::load(&fx.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("batch should be in flight");
    let next_coin = in_flight.next_coin;
    let mut distinct_payouts = in_flight.payout_coin_ids.clone();
    distinct_payouts.sort();
    distinct_payouts.dedup();
    assert_eq!(distinct_payouts.len(), 3, "each request must get its own distinct payout coin id");
    assert!(
        !in_flight.payout_coin_ids.contains(&next_coin.coin_id()),
        "the treasury's change coin must never be one of the in-flight payout coin ids"
    );

    fx.publisher.chain.set_confirmed(next_coin.coin_id(), 5_000_010);
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Confirmed { requests: 3 });

    let change_coin_id = format!("0x{}", next_coin.coin_id());
    let mut reported = Vec::new();
    for request_id in ["w1", "w2", "w3"] {
        let status = fx.publisher.status(request_id)?;
        assert_eq!(status.state, "confirmed");
        assert_eq!(status.height, Some(5_000_010));
        let coin_id = status.coin_id.expect("a confirmed request must report a coin id");
        assert_ne!(coin_id, change_coin_id, "{request_id} must not be reported at the change coin's id");
        reported.push(coin_id);
    }
    reported.sort();
    reported.dedup();
    assert_eq!(reported.len(), 3, "each request's reported coin id must be distinct from the other two");

    Ok(())
}

/// Builds and signs a genuine spend of `treasury`'s current coin (any valid
/// CAT spend works -- this test only needs a real, `Cat::parse_children`-
/// decodable parent spend, not that it happened to be a withdrawal), for
/// `treasury_self_heal_adopts_the_live_coin_when_the_cached_one_is_stale` to
/// register directly on the fake chain client as though it had already
/// confirmed out of band (e.g. a manual `c2 send-play` consolidation).
fn external_spend_of(treasury: Cat, owner: &c2_cli::keys::WalletKey) -> Result<(chia_protocol::CoinSpend, Coin)> {
    let request = withdrawal_request("external-consolidation", Bytes32::from([8u8; 32]), 40_000);
    let draft = build_play_withdrawal_batch(treasury, owner, std::slice::from_ref(&request))?;
    Ok((draft.coin_spends[0].clone(), draft.next_coin))
}

/// Task requirement (decision Р-53 follow-up, `docs/research/
/// chain_service.md` §13): "stale cached coin -> adopts the live one". The
/// cached treasury coin (A) is spent by something this publisher never
/// tracked as its own in-flight batch (simulating e.g. a manual `c2
/// send-play` consolidation) -- self-heal must find the real unspent child
/// (B) via the treasury's own CAT puzzle hash and adopt it, never bail or
/// build on the stale coin.
#[tokio::test]
async fn treasury_self_heal_adopts_the_live_coin_when_the_cached_one_is_stale() -> Result<()> {
    let fx = set_up()?;
    let owner = derive_wallet_key(&fx.master_sk, 0);

    let play_state = load_play_state(&fx.data_dir, "operator")?;
    let lineage_proof = play_state.lineage_proof()?;
    let cat_info = CatInfo::new(play_state.asset_id, None, owner.puzzle_hash);
    let treasury_a = Cat::new(play_state.coin, Some(lineage_proof), cat_info);
    let a_coin_id = treasury_a.coin.coin_id();

    let (a_spend, b_coin) = external_spend_of(treasury_a, &owner)?;
    let b_coin_id = b_coin.coin_id();

    // The chain now shows A spent, with B (a real, decodable CAT child of A)
    // as the treasury's current unspent coin.
    fx.publisher.chain.set_spent(a_coin_id, 5_000_000, 5_000_010);
    fx.publisher.chain.set_puzzle_and_solution(a_coin_id, a_spend);
    fx.publisher.chain.set_coin_record(
        b_coin_id,
        CoinRecordInfo { exists: true, confirmed_height: Some(5_000_010), spent: false, spent_height: None, coin: Some(b_coin) },
    );
    let treasury_cat_ph = cat_puzzle_hash(play_state.asset_id, owner.puzzle_hash);
    fx.publisher.chain.add_puzzle_hash_record(PuzzleHashCoinRecord {
        coin_id: b_coin_id,
        puzzle_hash: treasury_cat_ph,
        amount: b_coin.amount,
        confirmed_height: 5_000_010,
        spent: false,
    });

    // Nothing enqueued, nothing in flight -- this tick's only job is the
    // reconciliation.
    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Idle);

    let healed = load_play_state(&fx.data_dir, "operator")?;
    assert_eq!(healed.coin, b_coin, "self-heal must adopt the live unspent coin found for the treasury key");
    assert!(
        fx.publisher.health_check().await?.is_none(),
        "a successful self-heal must not leave the treasury reported stuck"
    );

    Ok(())
}

/// Task requirement: "our own in-flight batch explains the spend -> waits".
/// The treasury coin a batch of ours is currently in flight against gets
/// marked spent on the fake chain (simulating that spend having confirmed)
/// -- self-heal must recognize its own in-flight batch already explains
/// this and do nothing, leaving `advance_in_flight`'s own confirmation logic
/// to run (which, with `next_coin` not yet registered as confirmed, simply
/// waits) rather than going looking for a "replacement" coin.
#[tokio::test]
async fn treasury_self_heal_waits_when_our_own_in_flight_batch_explains_the_spent_coin() -> Result<()> {
    let fx = set_up()?;
    let alice = Bytes32::from([1u8; 32]);
    fx.publisher.enqueue(withdrawal_request("w1", alice, 100_000)).await?;

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });

    let queue = WithdrawalQueueFile::load(&fx.data_dir, "operator")?;
    let in_flight = queue.in_flight.expect("batch should be in flight");
    let spent_coin_id = in_flight.spent_coin.coin_id();

    fx.publisher.chain.set_spent(spent_coin_id, 5_000_000, 5_000_005);

    let outcome = fx.publisher.tick().await?;
    assert_eq!(outcome, WithdrawalTickOutcome::WaitingForConfirmation, "an in-flight batch explaining the spend must just wait");

    let play_state = load_play_state(&fx.data_dir, "operator")?;
    assert_eq!(
        play_state.coin.coin_id(),
        spent_coin_id,
        "self-heal must never touch the cached coin while an in-flight batch of ours explains the spend"
    );
    assert!(fx.publisher.health_check().await?.is_none());

    Ok(())
}
