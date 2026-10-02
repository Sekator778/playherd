//! The in-flight PLAY withdrawal batch that sits in the node's mempool and does not confirm is replaced
//! (`docs/research/chain_service.md` §17.8) -- against the real consensus simulator with the model
//! mempool of `tests/sim_chain/mod.rs`, the treasury a real CAT coin: every bundle that confirms is
//! validated for real (CAT lineage, signatures, value), and "a payout is made at most once" is checked
//! against the coins the chain actually holds, not against the publisher's own books.
//!
//! This is the withdrawal twin of `tests/oracle_replace.rs`, with the one question money adds: can a
//! player be paid twice, or marked paid and never be? The answers, tested below:
//!
//! * both bundles spend the SAME treasury coin, so the chain confirms at most one -- (a);
//! * which one it confirmed is read off the chain (each version's treasury change coin exists or does
//!   not), never assumed; requests the confirmed version paid are marked paid with their own payout
//!   coin, requests it did not pay go back to the queue and are paid by a later batch -- (b), (c);
//! * after every step each request is in exactly one place, every request marked paid has its payout
//!   coin on chain, and no recipient holds two payout coins -- (d); a fee coin reserved for a version
//!   that lost is released -- (e).
//!
//! The last section is about requests that are the *same coin* -- same recipient, same amount, two
//! withdrawals (`docs/research/chain_service.md` §18.1). The chain rejects a spend that creates one coin
//! twice, so one batch carries at most one request per payout and the repeat waits for a later batch: in a
//! new batch, in a rebuild, in a replacement, and through the random interleavings.

mod log_capture;
mod sim_chain;

use std::collections::BTreeMap;
use std::time::Duration;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_consensus::validation_error::ErrorCode;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use chia_sdk_driver::{Cat, CatInfo, CatSpend, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_test::{Simulator, SimulatorError};
use chia_sdk_types::Conditions;
use chia_sdk_utils::Address;

use c2_cli::builders::issue::build_issue_play;
use c2_cli::cat_chain::cat_puzzle_hash;
use c2_cli::keys::{TXCH_PREFIX, WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::sign::sign_transaction;
use c2_cli::state::PlayCoinState;
use c2_svc::fee_allocator::{FeeCoinAllocator, fee_coin_of};
use c2_svc::fee_reserve::FeeCoinPolicy;
use c2_svc::oracle::chain_client::ChainClient;
use c2_svc::oracle::publisher::TickFailureState;
use c2_svc::play::types::WithdrawalRequest;
use c2_svc::play::withdrawal_publisher::{WithdrawalPublisher, WithdrawalTickOutcome};
use c2_svc::play::withdrawal_queue::{InFlightWithdrawalBatch, WithdrawalQueueFile, is_collision_free};
use c2_svc::play_state_file::{load_play_state, save_play_state_atomic};

use sim_chain::SimChain;

const MIN_INCREASE: u64 = 10_000_000;
const SUPPLY: u64 = 1_000_000_000;
const BIG_WALLET_COIN: u64 = 1_000_000_000_000;

struct Options {
    fallback_rate: u64,
    max_fee_mojos: u64,
    wallet_coins: Vec<u64>,
    estimate: Option<u64>,
    replace_after: Duration,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            // no floor and no estimate: the fee-0 batch of 2026-10-02
            fallback_rate: 0,
            max_fee_mojos: 50_000_000,
            wallet_coins: vec![BIG_WALLET_COIN],
            estimate: None,
            replace_after: Duration::from_secs(180),
        }
    }
}

struct World {
    _tmp: tempfile::TempDir,
    publisher: WithdrawalPublisher<SimChain>,
    master_sk: SecretKey,
    owner: WalletKey,
    asset_id: Bytes32,
}

fn request(n: u64) -> WithdrawalRequest {
    let to_puzzle_hash = Bytes32::from([n as u8 + 1; 32]);
    WithdrawalRequest {
        request_id: format!("w-{n}"),
        to_address: Address::new(to_puzzle_hash, TXCH_PREFIX.to_string()).encode().unwrap(),
        to_puzzle_hash,
        // distinct amounts, so no two payouts of one batch are the same coin
        amount_mojos: 10_000 + 1_000 * n,
    }
}

/// Request `w-{id}` to the recipient `who` for `amount`. The same `who` and `amount` under two ids are two
/// withdrawals that create the same payout coin.
fn payout(id: u64, who: u8, amount: u64) -> WithdrawalRequest {
    let to_puzzle_hash = Bytes32::from([0xD0 + who; 32]);
    WithdrawalRequest {
        request_id: format!("w-{id}"),
        to_address: Address::new(to_puzzle_hash, TXCH_PREFIX.to_string()).encode().unwrap(),
        to_puzzle_hash,
        amount_mojos: amount,
    }
}

fn world(options: Options) -> Result<World> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();
    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let owner = derive_wallet_key(&master_sk, 0);
    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-10-02T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // The PLAY treasury, issued in the simulator the way `c2 issue-play` does it.
    let mut sim = Simulator::new();
    let funding = sim.new_coin(owner.puzzle_hash, SUPPLY);
    let issued = build_issue_play(funding, &owner, SUPPLY, 0)?;
    sim.new_transaction(SpendBundle::new(issued.coin_spends.clone(), issued.signature.clone()))?;
    let lineage_proof = issued.cat.lineage_proof.expect("issue-play always sets a lineage proof");
    let asset_id = issued.cat.info.asset_id;
    save_play_state_atomic(&data_dir, "operator", &PlayCoinState::new(asset_id, issued.cat.coin, lineage_proof))?;
    for amount in &options.wallet_coins {
        sim.new_coin(owner.puzzle_hash, *amount);
    }

    let chain = SimChain::new(sim);
    chain.set_estimate(options.estimate);
    let publisher = WithdrawalPublisher {
        data_dir,
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain,
        retry_backoff: Duration::from_secs(0),
        max_retry_attempts: 8,
        max_fee_mojos: options.max_fee_mojos,
        fee_target_time_secs: 60,
        mempool_replace_after: options.replace_after,
        queue_lock: tokio::sync::Mutex::new(()),
        treasury_stuck: tokio::sync::RwLock::new(None),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        fee_allocator: std::sync::Arc::new(
            FeeCoinAllocator::with_policy(FeeCoinPolicy { min_free: 0, coin_mojos: 200_000_000 })
                .with_fallback_fee_rate(options.fallback_rate),
        ),
    };
    Ok(World { _tmp: tmp, publisher, master_sk, owner, asset_id })
}

impl World {
    fn queue(&self) -> WithdrawalQueueFile {
        WithdrawalQueueFile::load(&self.publisher.data_dir, "operator").expect("queue file")
    }

    fn in_flight(&self) -> InFlightWithdrawalBatch {
        self.queue().in_flight.expect("a batch is in flight")
    }

    async fn enqueue(&self, ns: std::ops::RangeInclusive<u64>) -> Result<()> {
        for n in ns {
            assert!(self.publisher.enqueue(request(n)).await?, "w-{n} is new");
        }
        Ok(())
    }

    /// `w-{n}` for each `n`, made by `make` (`request`, or `repeating_request`).
    async fn enqueue_made(&self, ns: std::ops::RangeInclusive<u64>, make: fn(u64) -> WithdrawalRequest) -> Result<()> {
        for n in ns {
            let request = make(n);
            assert!(self.publisher.enqueue(request).await?, "w-{n} is new");
        }
        Ok(())
    }

    async fn enqueue_requests(&self, requests: &[WithdrawalRequest]) -> Result<()> {
        for request in requests {
            assert!(self.publisher.enqueue(request.clone()).await?, "{} is new", request.request_id);
        }
        Ok(())
    }

    fn pending_ids(&self) -> Vec<String> {
        self.queue().pending.iter().map(|r| r.request_id.clone()).collect()
    }

    fn in_flight_ids(&self) -> Vec<String> {
        self.in_flight().requests.iter().map(|r| r.request_id.clone()).collect()
    }

    fn age_in_flight(&self, secs: i64) {
        let mut queue = self.queue();
        let in_flight = queue.in_flight.as_mut().expect("a batch is in flight");
        in_flight.last_attempt_at_unix -= secs;
        in_flight.broadcast_at_unix -= secs;
        queue.save_atomic(&self.publisher.data_dir, "operator").unwrap();
    }

    async fn tick(&self) -> WithdrawalTickOutcome {
        self.publisher.tick().await.expect("tick")
    }

    async fn reserved(&self, coin: &Coin) -> bool {
        self.publisher.fee_allocator.is_reserved(coin.coin_id()).await
    }

    /// The payout coins of `request` that exist on chain (it is never spent again here).
    fn payouts_on_chain(&self, request: &WithdrawalRequest) -> Vec<Coin> {
        let recipient_ph = cat_puzzle_hash(self.asset_id, request.to_puzzle_hash);
        self.publisher
            .chain
            .with_sim(|sim| sim.unspent_coins(recipient_ph, false))
            .into_iter()
            .filter(|coin| coin.amount == request.amount_mojos)
            .collect()
    }

    /// The PLAY the treasury holds on chain now.
    fn treasury_on_chain(&self) -> u64 {
        let ph = cat_puzzle_hash(self.asset_id, self.owner.puzzle_hash);
        self.publisher.chain.with_sim(|sim| sim.unspent_coins(ph, false)).iter().map(|coin| coin.amount).sum()
    }
}

fn spent_coins(bundle: &SpendBundle) -> std::collections::BTreeSet<Bytes32> {
    bundle.coin_spends.iter().map(|spend| spend.coin.coin_id()).collect()
}

// --- (c) the replacement confirms --------------------------------------------------------------------

/// A fee-0 withdrawal batch sits in the mempool; past the wait it is replaced by one that pays the
/// 10,000,000 the node asks (with a NEW fee coin -- the old batch had none), carrying the requests that
/// arrived meanwhile. The replacement confirms: every request is paid once, each marked with its own
/// payout coin, and the treasury holds exactly what is left.
#[tokio::test]
async fn a_fee_zero_withdrawal_batch_in_the_mempool_is_replaced_and_the_replacement_pays_everyone_once() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=3).await?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 3, accepted: true });
    let old = w.in_flight();
    assert_eq!(old.fee_mojos, 0);
    w.enqueue(4..=5).await?;

    assert_eq!(w.tick().await, WithdrawalTickOutcome::WaitingForConfirmation, "not yet: it has waited no time");
    w.age_in_flight(200);
    assert_eq!(
        w.tick().await,
        WithdrawalTickOutcome::Replaced { requests: 5, fee_mojos: MIN_INCREASE, replaced_fee_mojos: 0 }
    );
    let replacement = w.in_flight();
    assert_eq!(replacement.spent_coin, old.spent_coin, "the same treasury coin");
    assert!(spent_coins(&replacement.bundle).is_superset(&spent_coins(&old.bundle)));
    let fee_coin = fee_coin_of(&replacement.bundle, replacement.spent_coin).expect("the replacement gained a fee coin");
    assert!(w.reserved(&fee_coin).await);
    assert_eq!(replacement.superseded.len(), 1);
    assert_eq!(
        replacement.superseded[0].requests.iter().map(|r| r.request_id.as_str()).collect::<Vec<_>>(),
        vec!["w-1", "w-2", "w-3"]
    );
    assert!(w.queue().pending.is_empty());
    let chain = &w.publisher.chain;
    assert_eq!(chain.mempool().len(), 1);
    assert!(chain.mempool_holds(replacement.bundle.name()) && !chain.mempool_holds(old.bundle.name()));

    chain.confirm(replacement.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 5 });
    let queue = w.queue();
    assert!(queue.in_flight.is_none() && queue.pending.is_empty() && queue.failed.is_empty());
    for n in 1..=5 {
        let req = request(n);
        let payouts = w.payouts_on_chain(&req);
        assert_eq!(payouts.len(), 1, "w-{n} is paid exactly once on chain");
        assert_eq!(queue.confirmed[&req.request_id].0, payouts[0].coin_id(), "w-{n} is marked with its own payout coin");
    }
    let paid: u64 = (1..=5).map(|n| request(n).amount_mojos).sum();
    assert_eq!(w.treasury_on_chain(), SUPPLY - paid, "nothing was paid beyond the five requests");
    assert_eq!(load_play_state(&w.publisher.data_dir, "operator")?.coin, replacement.next_coin);
    assert!(!w.reserved(&fee_coin).await);
    assert_eq!(w.publisher.health_check().await?, None);
    Ok(())
}

// --- (b) the OLD batch confirms after the replacement was pushed ------------------------------------------

/// The block that comes is built from another node's mempool, which holds the OLD batch. The treasury coin
/// is consumed by it; the replacement is dead. The requests the old batch paid are marked paid -- each with
/// the payout coin that really exists on chain -- and the two requests only the replacement carried are
/// NOT marked paid: they go back to the queue, the very next batch pays them, and in the end every player
/// holds exactly one payout. The fee coin reserved for the replacement is released.
#[tokio::test]
async fn when_the_old_withdrawal_batch_wins_the_requests_it_did_not_pay_are_paid_by_a_later_batch() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=3).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(4..=5).await?;
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Replaced { requests: 5, .. }));
    let replacement = w.in_flight();
    let fee_coin = fee_coin_of(&replacement.bundle, replacement.spent_coin).unwrap();

    // (a) real consensus: the replacement cannot confirm once the old batch has.
    let chain = &w.publisher.chain;
    chain.apply_to_chain(old.bundle.clone())?;
    match chain.apply_to_chain(replacement.bundle.clone()) {
        Err(SimulatorError::Validation(ErrorCode::DoubleSpend)) => {}
        other => panic!("the replacement must be refused as a double spend of the treasury coin, got {other:?}"),
    }
    // Already on chain after the first block: w-1..w-3 paid by the old batch -- and w-4, w-5 NOT paid.
    for n in 1..=3 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1);
    }
    for n in 4..=5 {
        assert!(w.payouts_on_chain(&request(n)).is_empty(), "w-{n} was only in the replacement and is unpaid");
    }

    assert_eq!(w.tick().await, WithdrawalTickOutcome::ConfirmedEarlierVersion { confirmed: 3, requeued: 2 });
    assert!(!w.reserved(&fee_coin).await, "(e) the fee coin of the batch that lost the race is released");
    let queue = w.queue();
    assert!(queue.in_flight.is_none());
    assert_eq!(queue.confirmed.keys().cloned().collect::<Vec<_>>(), vec!["w-1", "w-2", "w-3"]);
    assert_eq!(queue.pending.iter().map(|r| r.request_id.as_str()).collect::<Vec<_>>(), vec!["w-4", "w-5"]);
    for n in 1..=3 {
        let req = request(n);
        assert_eq!(queue.confirmed[&req.request_id].0, w.payouts_on_chain(&req)[0].coin_id(), "w-{n}: the marked coin is the real one");
    }
    assert_eq!(load_play_state(&w.publisher.data_dir, "operator")?.coin, old.next_coin);

    // The next tick pays the two that were not paid.
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 2, accepted: true });
    chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    for n in 1..=5 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1, "w-{n}: paid exactly once");
    }
    let paid: u64 = (1..=5).map(|n| request(n).amount_mojos).sum();
    assert_eq!(w.treasury_on_chain(), SUPPLY - paid);
    assert_eq!(w.queue().confirmed.len(), 5);
    Ok(())
}

/// The other order: the replacement confirms, and the old batch then cannot -- consensus refuses it as a
/// double spend of the treasury coin, so no player is paid by both.
#[tokio::test]
async fn of_two_withdrawal_batches_for_one_treasury_coin_at_most_one_confirms() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    let old = w.in_flight();
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Replaced { .. }));
    let replacement = w.in_flight();
    w.publisher.chain.confirm(replacement.bundle.name())?;
    match w.publisher.chain.apply_to_chain(old.bundle.clone()) {
        Err(SimulatorError::Validation(ErrorCode::DoubleSpend)) => {}
        other => panic!("the old batch must be refused as a double spend, got {other:?}"),
    }
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    for n in 1..=2 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1);
    }
    Ok(())
}

// --- the fee coin the old batch spent is kept; the node does not take the replacement ------------------------

/// A batch that had a fee coin keeps spending it; the old version winning spends that coin and the
/// reservation is released.
#[tokio::test]
async fn a_withdrawal_batch_that_had_a_fee_coin_is_replaced_by_one_that_spends_the_same_coin() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    let old = w.in_flight();
    let fee_coin = fee_coin_of(&old.bundle, old.spent_coin).expect("fee coin");
    w.age_in_flight(200);
    assert_eq!(
        w.tick().await,
        WithdrawalTickOutcome::Replaced { requests: 2, fee_mojos: 1_000 + MIN_INCREASE, replaced_fee_mojos: 1_000 }
    );
    let replacement = w.in_flight();
    assert_eq!(fee_coin_of(&replacement.bundle, replacement.spent_coin), Some(fee_coin));
    assert!(w.reserved(&fee_coin).await);

    // The old version is the one a miner takes. Both versions pay the same two requests, so they leave
    // the same treasury change coin: the chain cannot tell them apart and the books do not need it to --
    // the two payouts are the same coins either way.
    w.publisher.chain.apply_to_chain(old.bundle.clone())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    assert!(!w.reserved(&fee_coin).await, "the fee coin was spent by the old version; nothing stays reserved");
    for n in 1..=2 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1);
    }
    Ok(())
}

/// A refused replacement leaves the old batch in flight, the merged requests in the queue, the new fee
/// coin released, and `GET /health` naming the node's answer; the old batch then confirms and everything
/// is paid exactly once.
#[tokio::test]
async fn a_withdrawal_replacement_the_node_refuses_changes_nothing_and_is_reported() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(3..=4).await?;
    w.age_in_flight(200);

    w.publisher.chain.refuse_next_push("INVALID_FEE_TOO_CLOSE_TO_ZERO");
    let outcome = w.tick().await;
    let WithdrawalTickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("INVALID_FEE_TOO_CLOSE_TO_ZERO"), "{reason}");
    let queue = w.queue();
    assert_eq!(queue.in_flight.as_ref().unwrap().bundle_id, old.bundle_id);
    assert_eq!(queue.pending.iter().map(|r| r.request_id.as_str()).collect::<Vec<_>>(), vec!["w-3", "w-4"]);
    for (_, coin) in w.publisher.chain.unspent_coins(&w.master_sk).await? {
        assert!(!w.reserved(&coin).await);
    }
    let health = w.publisher.health_check().await?.expect("a blocked replacement is reported");
    assert!(health.contains("could not be replaced") && health.contains("INVALID_FEE_TOO_CLOSE_TO_ZERO"), "{health}");

    w.publisher.chain.confirm(old.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 2, accepted: true });
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    for n in 1..=4 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1);
    }
    assert_eq!(w.publisher.health_check().await?, None);
    Ok(())
}

/// The replacement the node did not take is not gone: the node parked it in its conflict cache, and a
/// block can still be built with it. The two requests it merged had gone back to the queue when the
/// replacement was refused; when the chain takes THAT replacement they are paid by it -- so they must be
/// marked paid and leave the queue, not wait there to be paid a second time by a later batch.
#[tokio::test]
async fn a_replacement_that_was_refused_and_is_then_taken_by_the_chain_pays_its_requests_only_once() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(3..=4).await?;
    w.age_in_flight(200);

    w.publisher.chain.park_next_push();
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::ReplacementBlocked { .. }));
    assert_eq!(w.queue().pending.iter().map(|r| r.request_id.as_str()).collect::<Vec<_>>(), vec!["w-3", "w-4"]);
    let refused = w.publisher.chain.parked().remove(0);
    assert_ne!(refused.name(), old.bundle.name());

    // A block built from a mempool that held the refused replacement.
    w.publisher.chain.apply_to_chain(refused)?;
    for n in 1..=4 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1, "w-{n} is paid by that block");
    }
    assert_eq!(w.tick().await, WithdrawalTickOutcome::ConfirmedEarlierVersion { confirmed: 4, requeued: 0 });
    let queue = w.queue();
    assert!(queue.in_flight.is_none());
    assert!(queue.pending.is_empty(), "nothing left to pay a second time: {:?}", queue.pending);
    assert_eq!(queue.confirmed.len(), 4);
    for n in 1..=4 {
        let req = request(n);
        assert_eq!(queue.confirmed[&req.request_id].0, w.payouts_on_chain(&req)[0].coin_id());
    }
    // And nothing else happens: no batch, no second payout.
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Idle);
    let paid: u64 = (1..=4).map(|n| request(n).amount_mojos).sum();
    assert_eq!(w.treasury_on_chain(), SUPPLY - paid);
    Ok(())
}

/// The cap leaves no valid replacement: nothing is pushed, the queue is untouched, `/health` says why, and
/// the batch is not retried before another full wait.
#[tokio::test]
async fn a_cap_that_leaves_no_valid_withdrawal_replacement_is_reported_and_not_retried_every_tick() -> Result<()> {
    let w = world(Options { max_fee_mojos: 5_000_000, ..Options::default() })?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    w.enqueue(3..=3).await?;
    w.age_in_flight(200);
    let outcome = w.tick().await;
    let WithdrawalTickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("C2_MAX_FEE_MOJOS is 5000000"), "{reason}");
    assert_eq!(w.publisher.chain.pushes().len(), 1);
    assert_eq!(w.queue().pending.len(), 1, "the waiting request is untouched");
    assert!(w.publisher.health_check().await?.is_some());
    for _ in 0..3 {
        assert_eq!(w.tick().await, WithdrawalTickOutcome::WaitingForConfirmation);
    }
    assert_eq!(w.publisher.chain.pushes().len(), 1);
    Ok(())
}

/// A rebuild after a fee rejection is a version too: if the OLD (rejected) batch is the one that some miner
/// includes, the requests only the rebuilt one carried are not marked paid. (Before this change the
/// treasury change coin of the rebuilt batch never appearing left the batch in flight forever.)
#[tokio::test]
async fn an_earlier_rebuilt_version_that_wins_is_settled_like_a_replaced_one() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue(1..=2).await?;
    w.publisher.chain.refuse_next_push("INVALID_FEE_TOO_CLOSE_TO_ZERO");
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 2, accepted: false });
    let first = w.in_flight();
    w.enqueue(3..=3).await?;
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Rebuilt { requests: 3, accepted: true, .. }));
    let rebuilt = w.in_flight();
    assert_eq!(rebuilt.superseded.len(), 1);

    // Some other node had the first batch after all.
    w.publisher.chain.apply_to_chain(first.bundle.clone())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::ConfirmedEarlierVersion { confirmed: 2, requeued: 1 });
    assert_eq!(w.queue().pending.iter().map(|r| r.request_id.as_str()).collect::<Vec<_>>(), vec!["w-3"]);
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    for n in 1..=3 {
        assert_eq!(w.payouts_on_chain(&request(n)).len(), 1);
    }
    Ok(())
}

// --- requests that are the same coin (docs/research/chain_service.md §18.1) ---------------------------------

/// The premise of the rule, on the real consensus: a spend that creates the same CAT coin twice is rejected
/// (`DUPLICATE_OUTPUT`), one that differs only in an amount is valid. Built by hand -- the publisher's own
/// builder refuses the first.
#[tokio::test]
async fn the_chain_rejects_a_spend_that_creates_the_same_payout_coin_twice() -> Result<()> {
    let w = world(Options::default())?;
    let play = load_play_state(&w.publisher.data_dir, "operator")?;
    let treasury = Cat::new(play.coin, Some(play.lineage_proof()?), CatInfo::new(play.asset_id, None, w.owner.puzzle_hash));
    let alice = Bytes32::from([0xD1; 32]);

    let bundle_paying = |first: u64, second: u64| -> Result<SpendBundle> {
        let mut ctx = SpendContext::new();
        let conditions = Conditions::new()
            .create_coin(alice, first, ctx.hint(alice)?)
            .create_coin(alice, second, ctx.hint(alice)?)
            .create_coin(w.owner.puzzle_hash, SUPPLY - first - second, ctx.hint(w.owner.puzzle_hash)?);
        let p2_spend = StandardLayer::new(w.owner.synthetic_pk).spend_with_conditions(&mut ctx, conditions)?;
        Cat::spend_all(&mut ctx, &[CatSpend::new(treasury, p2_spend)])?;
        let coin_spends = ctx.take();
        let signature = sign_transaction(&coin_spends, std::slice::from_ref(&w.owner.synthetic_sk))?;
        Ok(SpendBundle::new(coin_spends, signature))
    };
    let on_a_copy_of_the_chain = |bundle: SpendBundle| w.publisher.chain.with_sim(|sim| sim.clone().new_transaction(bundle));

    on_a_copy_of_the_chain(bundle_paying(5_000, 5_001)?).expect("two payouts to alice of different amounts are valid");
    match on_a_copy_of_the_chain(bundle_paying(5_000, 5_000)?) {
        Err(SimulatorError::Validation(ErrorCode::DuplicateOutput)) => {}
        other => panic!("two identical payouts must be rejected as DUPLICATE_OUTPUT, got {other:?}"),
    }
    Ok(())
}

/// Alice asks twice for the same amount. The first spend carries her first request (and bob's), the second
/// spend her second: both are paid, each by its own coin, in two spends, and nobody is failed or paid twice.
#[tokio::test]
async fn two_identical_withdrawals_are_both_paid_in_two_spends() -> Result<()> {
    let w = world(Options::default())?;
    let (w1, w2, w3) = (payout(1, 1, 5_000), payout(2, 1, 5_000), payout(3, 2, 7_000));
    w.enqueue_requests(&[w1.clone(), w2.clone(), w3.clone()]).await?;

    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 2, accepted: true });
    assert_eq!(w.in_flight_ids(), vec!["w-1", "w-3"], "arrival order, one request per payout");
    assert_eq!(w.pending_ids(), vec!["w-2"], "the repeat waits");
    assert_eq!(w.publisher.status("w-2")?.state, "queued");
    let first = w.in_flight();
    assert!(is_collision_free(&first.requests));
    // The real consensus takes the batch: it is valid.
    w.publisher.chain.confirm(first.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });

    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    assert_eq!(w.in_flight_ids(), vec!["w-2"]);
    let second = w.in_flight();
    assert_eq!(second.spent_coin, first.next_coin, "the second spend is made from the treasury coin the first one left");
    w.publisher.chain.confirm(second.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Idle);

    // Alice holds two coins of 5,000 -- one from each spend --, bob one of 7,000, and every request is
    // marked with the coin that is its own.
    let alice_coins = w.payouts_on_chain(&w1);
    assert_eq!(alice_coins.len(), 2);
    assert_ne!(alice_coins[0].parent_coin_info, alice_coins[1].parent_coin_info, "two spends made them");
    let queue = w.queue();
    let marked: std::collections::BTreeSet<Bytes32> = [&w1, &w2].iter().map(|r| queue.confirmed[&r.request_id].0).collect();
    assert_eq!(marked, alice_coins.iter().map(Coin::coin_id).collect(), "two requests, two different payout coins");
    assert_eq!(w.payouts_on_chain(&w3).len(), 1);
    assert_eq!(w.treasury_on_chain(), SUPPLY - 17_000, "nothing was paid beyond the three requests");
    assert!(queue.failed.is_empty() && queue.pending.is_empty() && queue.in_flight.is_none());
    assert_eq!(w.publisher.chain.pushes().len(), 2, "two spends and nothing else was pushed");
    Ok(())
}

/// Three requests for the same payout, and a fourth for someone else: three spends, the repeats one at a time
/// in arrival order, the other recipient in the first.
#[tokio::test]
async fn three_identical_withdrawals_are_paid_in_three_spends_in_arrival_order() -> Result<()> {
    let w = world(Options::default())?;
    let requests = [payout(1, 1, 5_000), payout(2, 1, 5_000), payout(3, 1, 5_000), payout(4, 2, 7_000)];
    w.enqueue_requests(&requests).await?;

    for (expected_in_flight, expected_pending) in
        [(vec!["w-1", "w-4"], vec!["w-2", "w-3"]), (vec!["w-2"], vec!["w-3"]), (vec!["w-3"], vec![])]
    {
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: expected_in_flight.len(), accepted: true });
        assert_eq!(w.in_flight_ids(), expected_in_flight);
        assert_eq!(w.pending_ids(), expected_pending);
        w.publisher.chain.confirm(w.in_flight().bundle.name())?;
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: expected_in_flight.len() });
    }
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Idle);

    let alice_coins = w.payouts_on_chain(&requests[0]);
    assert_eq!(alice_coins.len(), 3);
    let parents: std::collections::BTreeSet<Bytes32> = alice_coins.iter().map(|c| c.parent_coin_info).collect();
    assert_eq!(parents.len(), 3, "three spends made them");
    let queue = w.queue();
    for r in &requests[..3] {
        assert!(alice_coins.iter().any(|c| c.coin_id() == queue.confirmed[&r.request_id].0), "{} has its own coin", r.request_id);
    }
    assert_eq!(w.treasury_on_chain(), SUPPLY - 3 * 5_000 - 7_000);
    assert!(queue.failed.is_empty());

    // Only identical requests: one per spend, from the very first batch.
    let w = world(Options::default())?;
    w.enqueue_requests(&[payout(1, 1, 5_000), payout(2, 1, 5_000), payout(3, 1, 5_000)]).await?;
    for expected in ["w-1", "w-2", "w-3"] {
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
        assert_eq!(w.in_flight_ids(), vec![expected]);
        w.publisher.chain.confirm(w.in_flight().bundle.name())?;
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    }
    assert_eq!(w.payouts_on_chain(&payout(1, 1, 5_000)).len(), 3);
    Ok(())
}

/// A request that repeats the payout of the batch in flight arrives while the batch's first push was refused:
/// the rebuild merges the newly pending requests -- but not the repeat, which would make the rebuilt spend
/// invalid and hold up everything behind it. The next batch pays it.
#[tokio::test]
async fn an_identical_request_arriving_while_a_batch_is_in_flight_is_not_merged_into_its_rebuild() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue_requests(&[payout(1, 1, 5_000)]).await?;
    w.publisher.chain.refuse_next_push("INVALID_FEE_TOO_CLOSE_TO_ZERO");
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: false });
    w.enqueue_requests(&[payout(2, 1, 5_000), payout(3, 2, 7_000), payout(4, 1, 5_000)]).await?;

    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Rebuilt { requests: 2, accepted: true, .. }));
    assert_eq!(w.in_flight_ids(), vec!["w-1", "w-3"]);
    assert_eq!(w.pending_ids(), vec!["w-2", "w-4"], "the repeats wait, in their order");
    assert!(is_collision_free(&w.in_flight().requests));
    // the rebuilt bundle is valid: the real consensus takes it
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });

    for expected in ["w-2", "w-4"] {
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
        assert_eq!(w.in_flight_ids(), vec![expected]);
        w.publisher.chain.confirm(w.in_flight().bundle.name())?;
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    }
    assert_eq!(w.payouts_on_chain(&payout(1, 1, 5_000)).len(), 3, "w-1, w-2 and w-4 are each paid once");
    assert_eq!(w.payouts_on_chain(&payout(3, 2, 7_000)).len(), 1);
    assert_eq!(w.treasury_on_chain(), SUPPLY - 3 * 5_000 - 7_000);
    assert!(w.queue().failed.is_empty());
    Ok(())
}

/// The same while the batch waits in the node's mempool: the replacement merges what arrived meanwhile, except
/// a request that repeats a payout the batch already makes.
#[tokio::test]
async fn an_identical_request_arriving_while_a_batch_waits_in_the_mempool_is_not_merged_into_its_replacement() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue_requests(&[payout(1, 1, 5_000)]).await?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    let old = w.in_flight();
    w.enqueue_requests(&[payout(2, 1, 5_000), payout(3, 2, 7_000), payout(4, 1, 5_000), payout(5, 3, 9_000)]).await?;

    w.age_in_flight(200);
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Replaced { requests: 3, .. }));
    assert_eq!(w.in_flight_ids(), vec!["w-1", "w-3", "w-5"]);
    assert_eq!(w.pending_ids(), vec!["w-2", "w-4"]);
    let replacement = w.in_flight();
    assert_eq!(replacement.spent_coin, old.spent_coin, "the same treasury coin");
    assert!(is_collision_free(&replacement.requests));
    // The node holds the replacement and the real consensus takes it.
    assert!(w.publisher.chain.mempool_holds(replacement.bundle.name()));
    w.publisher.chain.confirm(replacement.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 3 });

    for expected in ["w-2", "w-4"] {
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
        assert_eq!(w.in_flight_ids(), vec![expected]);
        w.publisher.chain.confirm(w.in_flight().bundle.name())?;
        assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    }
    assert_eq!(w.payouts_on_chain(&payout(1, 1, 5_000)).len(), 3);
    assert_eq!(w.treasury_on_chain(), SUPPLY - 3 * 5_000 - 7_000 - 9_000);
    assert!(w.queue().failed.is_empty() && w.queue().pending.is_empty());
    Ok(())
}

/// A replacement the node refuses gives back what it merged -- and the queue is exactly what it was: the
/// repeat that was skipped is still in front of the requests that went past it.
#[tokio::test]
async fn a_refused_replacement_leaves_the_queue_exactly_as_it_was_repeats_included() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue_requests(&[payout(1, 1, 5_000)]).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue_requests(&[payout(2, 1, 5_000), payout(3, 2, 7_000), payout(4, 3, 9_000)]).await?;
    let pending_before = w.queue().pending;
    w.age_in_flight(200);

    w.publisher.chain.refuse_next_push("INVALID_FEE_TOO_CLOSE_TO_ZERO");
    assert!(matches!(w.tick().await, WithdrawalTickOutcome::ReplacementBlocked { .. }));
    let queue = w.queue();
    assert_eq!(queue.in_flight.as_ref().unwrap().bundle_id, old.bundle_id, "the old batch is still the one in flight");
    assert_eq!(queue.pending, pending_before, "the same requests in the same order, w-2 first");

    // The old batch confirms; w-2, w-3 and w-4 no longer share a batch with a repeat and go out together.
    w.publisher.chain.confirm(old.bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 3, accepted: true });
    assert_eq!(w.in_flight_ids(), vec!["w-2", "w-3", "w-4"]);
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 3 });
    assert_eq!(w.payouts_on_chain(&payout(1, 1, 5_000)).len(), 2);
    Ok(())
}

/// A batch built by a version of this service from before the rule existed, with two payouts that are the same
/// coin, is stuck: the chain rejects it however often it is pushed. It is rebuilt without the repeat, which goes
/// back to the queue and is paid by the next batch -- nobody in it is failed.
#[tokio::test]
async fn a_batch_with_two_identical_payouts_from_before_the_rule_is_rebuilt_without_the_repeat() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue_requests(&[payout(1, 1, 5_000)]).await?;
    w.publisher.chain.refuse_next_push("DUPLICATE_OUTPUT");
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: false });

    // What the old version left in its queue file: a batch that carries w-1 and w-2, a second withdrawal of the
    // same amount to the same recipient. The bundle the old version built for it was invalid and the node refused
    // it (`DUPLICATE_OUTPUT`) -- here the refusal is injected and the bundle is the valid one for w-1; what the
    // publisher looks at is the request list.
    let mut queue = w.queue();
    let in_flight = queue.in_flight.as_mut().unwrap();
    in_flight.requests.push(payout(2, 1, 5_000));
    in_flight.payout_coin_ids.push(in_flight.payout_coin_ids[0]);
    in_flight.last_push_error = Some("Failed to include transaction 0xabc, error DUPLICATE_OUTPUT".to_string());
    queue.save_atomic(&w.publisher.data_dir, "operator").unwrap();
    assert!(!is_collision_free(&w.in_flight().requests));
    w.enqueue_requests(&[payout(3, 2, 7_000)]).await?;

    assert!(matches!(w.tick().await, WithdrawalTickOutcome::Rebuilt { requests: 2, accepted: true, .. }));
    assert_eq!(w.in_flight_ids(), vec!["w-1", "w-3"]);
    assert_eq!(w.pending_ids(), vec!["w-2"], "the repeat went back to the queue");
    assert_eq!(w.in_flight().superseded.len(), 1);
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 2 });
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Broadcast { requests: 1, accepted: true });
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, WithdrawalTickOutcome::Confirmed { requests: 1 });
    assert_eq!(w.payouts_on_chain(&payout(1, 1, 5_000)).len(), 2, "w-1 and w-2 are each paid once");
    assert_eq!(w.treasury_on_chain(), SUPPLY - 2 * 5_000 - 7_000);
    assert!(w.queue().failed.is_empty());
    Ok(())
}

// --- random interleavings ---------------------------------------------------------------------------------

struct Rng(u64);

impl Rng {
    fn new(seed: u64) -> Self {
        Self(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
    }

    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

/// The walk's requests that repeat each other's payouts: three recipients and two amounts, so that among any
/// few requests most share their recipient and amount with another (`n` and `n + 6` are the same coin).
fn repeating_request(n: u64) -> WithdrawalRequest {
    payout(n, (n % 3) as u8 + 1, 5_000 + 1_000 * (n % 2))
}

/// After every step: every request is in exactly one place; every request the queue calls paid has its own
/// payout coin on chain, and none that were paid share one; a payout was never made more often than it was
/// asked for; no batch -- the one in flight and every earlier version of it -- carries two payouts that are
/// the same coin; a batch in flight spends the treasury coin the state is at.
fn assert_invariants(w: &World, requested: u64, make: fn(u64) -> WithdrawalRequest, context: &str) {
    let queue = w.queue();
    let mut places: BTreeMap<String, Vec<&str>> = BTreeMap::new();
    for r in &queue.pending {
        places.entry(r.request_id.clone()).or_default().push("pending");
    }
    if let Some(f) = &queue.in_flight {
        for r in &f.requests {
            places.entry(r.request_id.clone()).or_default().push("in flight");
        }
    }
    for id in queue.confirmed.keys() {
        places.entry(id.clone()).or_default().push("confirmed");
    }
    for id in queue.failed.keys() {
        places.entry(id.clone()).or_default().push("failed");
    }
    let mut asked_for: BTreeMap<(Bytes32, u64), usize> = BTreeMap::new();
    for n in 1..=requested {
        *asked_for.entry(make(n).payout_key()).or_default() += 1;
    }
    let mut marked: BTreeMap<(Bytes32, u64), Vec<Bytes32>> = BTreeMap::new();
    for n in 1..=requested {
        let req = make(n);
        let here = places.get(&req.request_id).cloned().unwrap_or_default();
        assert_eq!(here.len(), 1, "{context}: {} is in {here:?} (must be in exactly one place)", req.request_id);
        let on_chain = w.payouts_on_chain(&req);
        assert!(
            on_chain.len() <= asked_for[&req.payout_key()],
            "{context}: the payout of {} exists {} times on chain, it was asked for {} times",
            req.request_id,
            on_chain.len(),
            asked_for[&req.payout_key()]
        );
        if let Some((coin_id, _)) = queue.confirmed.get(&req.request_id) {
            assert!(
                on_chain.iter().any(|coin| coin.coin_id() == *coin_id),
                "{context}: {} is marked paid with a coin that is not one of its payouts on chain",
                req.request_id
            );
            marked.entry(req.payout_key()).or_default().push(*coin_id);
        }
    }
    for (key, coins) in &marked {
        let distinct: std::collections::BTreeSet<_> = coins.iter().collect();
        assert_eq!(distinct.len(), coins.len(), "{context}: two requests for {key:?} are marked with the same payout coin");
    }
    if let Some(f) = &queue.in_flight {
        assert_eq!(f.spent_coin, load_play_state(&w.publisher.data_dir, "operator").unwrap().coin, "{context}");
        assert!(is_collision_free(&f.requests), "{context}: the batch in flight carries two payouts that are the same coin");
        for version in &f.superseded {
            assert!(
                is_collision_free(&version.requests),
                "{context}: an earlier version of the batch carries two payouts that are the same coin"
            );
        }
    }
}

#[derive(Default, Debug)]
struct Coverage {
    replaced: usize,
    blocked: usize,
    earlier_version_won: usize,
    rebuilt: usize,
    broadcasts: usize,
    confirmed: usize,
    /// Steps after which a batch was in flight while a pending request repeated one of its payouts.
    repeats_waiting: usize,
}

/// `repeating`: the walk submits `repeating_request`s, most of which repeat another's payout, instead of
/// `request`s, each of which is a coin of its own.
async fn random_walk(seed: u64, repeating: bool) -> Result<Coverage> {
    let make: fn(u64) -> WithdrawalRequest = if repeating { repeating_request } else { request };
    let mut coverage = Coverage::default();
    let mut rng = Rng::new(seed);
    let w = world(Options {
        fallback_rate: 100,
        estimate: if rng.below(2) == 0 { None } else { Some(1_000 + rng.below(3_000_000)) },
        max_fee_mojos: if rng.below(100) < 30 { 5_000_000 } else { 50_000_000 },
        wallet_coins: if rng.below(3) == 0 { vec![6_000_000, 60_000_000, BIG_WALLET_COIN] } else { vec![BIG_WALLET_COIN, BIG_WALLET_COIN + 7] },
        ..Options::default()
    })?;
    let chain = &w.publisher.chain;
    // Every bundle that confirms is validated for real anyway. With repeating requests the node model validates
    // every push too (signature included), so a batch the chain would reject shows up as a refused push.
    chain.set_validate_on_push(repeating);
    let mut requested = 0u64;

    for step in 0..50 {
        let context = format!("seed {seed} step {step}");
        let mut tick_now = false;
        match rng.below(100) {
            0..=17 => {
                let n = 1 + rng.below(4);
                w.enqueue_made(requested + 1..=requested + n, make).await?;
                requested += n;
            }
            18..=37 => tick_now = true,
            38..=57 => {
                if w.queue().in_flight.is_some() {
                    w.age_in_flight(200);
                }
                tick_now = true;
            }
            58..=64 => {
                if let Some(item) = chain.mempool().last().cloned() {
                    chain.confirm(item.name)?;
                }
            }
            65..=84 => {
                // some miner's mempool held one of the bundles this publisher pushed for the treasury coin
                // as it is now -- not necessarily the newest, not necessarily one this node kept
                let treasury = load_play_state(&w.publisher.data_dir, "operator")?.coin;
                let mut candidates = Vec::new();
                for push in chain.pushes() {
                    let spends_treasury = push.bundle.coin_spends.iter().any(|spend| spend.coin == treasury);
                    if spends_treasury {
                        candidates.push(push.bundle);
                    }
                }
                if !candidates.is_empty() {
                    let pick = rng.below(candidates.len() as u64) as usize;
                    let _ = chain.apply_to_chain(candidates[pick].clone());
                }
            }
            85..=88 => chain.refuse_next_push("INVALID_FEE_LOW_FEE"),
            89..=91 => chain.park_next_push(),
            92..=93 => chain.fail_next_push(),
            _ => chain.set_estimate(if rng.below(2) == 0 { None } else { Some(1_000 + rng.below(3_000_000)) }),
        }
        if tick_now {
            match w.publisher.tick().await {
                Ok(outcome) => match outcome {
                    WithdrawalTickOutcome::Replaced { .. } => coverage.replaced += 1,
                    WithdrawalTickOutcome::ReplacementBlocked { .. } => coverage.blocked += 1,
                    WithdrawalTickOutcome::ConfirmedEarlierVersion { .. } => coverage.earlier_version_won += 1,
                    WithdrawalTickOutcome::Rebuilt { .. } => coverage.rebuilt += 1,
                    WithdrawalTickOutcome::Broadcast { .. } => coverage.broadcasts += 1,
                    WithdrawalTickOutcome::Confirmed { .. } => coverage.confirmed += 1,
                    _ => {}
                },
                Err(e) => {
                    let text = format!("{e:#}");
                    assert!(text.contains("connection reset by peer"), "{context}: tick failed for another reason than the injected one: {text}");
                }
            }
        }
        assert_invariants(&w, requested, make, &context);
        let queue = w.queue();
        if let Some(batch) = &queue.in_flight
            && queue.pending.iter().any(|p| batch.requests.iter().any(|r| r.payout_key() == p.payout_key()))
        {
            coverage.repeats_waiting += 1;
        }
    }

    // Settle: blocks keep coming, the publisher keeps ticking.
    for round in 0..80 {
        let queue = w.queue();
        if queue.in_flight.is_none() && queue.pending.is_empty() {
            break;
        }
        if let Some(item) = chain.mempool().last().cloned() {
            chain.confirm(item.name)?;
        } else if queue.in_flight.is_some() {
            w.age_in_flight(200);
        }
        match w.publisher.tick().await {
            Ok(_) => {}
            Err(e) => {
                let text = format!("{e:#}");
                assert!(text.contains("connection reset by peer"), "seed {seed} settle {round}: {text}");
            }
        }
        assert_invariants(&w, requested, make, &format!("seed {seed} settle {round}"));
    }

    let queue = w.queue();
    assert!(queue.in_flight.is_none() && queue.pending.is_empty(), "seed {seed}: did not settle: {queue:?}");
    assert!(queue.failed.is_empty(), "seed {seed}: {:?}", queue.failed);
    assert_eq!(queue.confirmed.len() as u64, requested, "seed {seed}");
    let mut paid = 0;
    let mut asked_for: BTreeMap<(Bytes32, u64), usize> = BTreeMap::new();
    for n in 1..=requested {
        let req = make(n);
        *asked_for.entry(req.payout_key()).or_default() += 1;
        paid += req.amount_mojos;
    }
    for n in 1..=requested {
        let req = make(n);
        assert_eq!(
            w.payouts_on_chain(&req).len(),
            asked_for[&req.payout_key()],
            "seed {seed}: every request for the payout of {} is paid exactly once",
            req.request_id
        );
    }
    assert_eq!(w.treasury_on_chain(), SUPPLY - paid, "seed {seed}: the treasury holds exactly what is left");
    // The model node validated every push of the repeating walk: not one was refused as a duplicate output.
    for push in chain.pushes() {
        let error = push.outcome.and_then(|outcome| outcome.error).unwrap_or_default();
        assert!(!error.to_ascii_uppercase().contains("DUPLICATE"), "seed {seed}: a pushed batch was refused: {error}");
    }

    // (e) nothing stays reserved with nothing in flight.
    let mut wallet_coins: Vec<Coin> = chain.with_sim(|sim| sim.unspent_coins(w.owner.puzzle_hash, false));
    for push in chain.pushes() {
        wallet_coins.extend(
            push.bundle.coin_spends.iter().map(|spend| spend.coin).filter(|coin| coin.puzzle_hash == w.owner.puzzle_hash),
        );
    }
    for coin in &wallet_coins {
        assert!(!w.reserved(coin).await, "seed {seed}: wallet coin {} is still reserved with nothing in flight", coin.coin_id());
    }
    assert_eq!(w.publisher.health_check().await?, None, "seed {seed}");
    assert_eq!(load_play_state(&w.publisher.data_dir, "operator")?.coin.amount, SUPPLY - paid, "seed {seed}");
    Ok(coverage)
}

/// The safety argument for payouts, tried in orders nobody wrote down: requests arrive, ticks happen,
/// batches age, a block includes the node's newest batch or ANY batch this publisher ever pushed for the
/// treasury coin (an old version another mempool still held), the node refuses, parks or drops a push --
/// and after every step every request is in one place, nothing marked paid lacks its payout coin on
/// chain, nobody holds two payouts; at the end everyone is paid exactly once and the treasury holds
/// exactly what is left. Deterministic: the same seeds every run.
#[tokio::test]
async fn random_interleavings_never_pay_twice_and_never_lose_a_payout() -> Result<()> {
    let mut total = Coverage::default();
    for seed in 1..=16 {
        let walk = random_walk(seed, false).await?;
        total.replaced += walk.replaced;
        total.blocked += walk.blocked;
        total.earlier_version_won += walk.earlier_version_won;
        total.rebuilt += walk.rebuilt;
        total.broadcasts += walk.broadcasts;
        total.confirmed += walk.confirmed;
    }
    println!("random withdrawal interleavings covered: {total:?}");
    assert!(total.replaced >= 8, "too few replacements to mean anything: {total:?}");
    assert!(total.blocked >= 3, "{total:?}");
    assert!(total.earlier_version_won >= 2, "an earlier version of a batch never won: {total:?}");
    assert!(total.broadcasts >= 20 && total.confirmed >= 10, "{total:?}");
    Ok(())
}

/// The same orders, with requests that mostly repeat each other's payout (same recipient, same amount): after
/// every step a request that repeats a payout of the batch in flight waits in the queue, no batch -- the one in
/// flight and every earlier version of it -- carries two payouts that are the same coin, nothing marked paid
/// shares its coin with another, and no payout is made more often than it was asked for; at the end every
/// request is paid exactly once, each by a coin of its own, and the treasury holds exactly what is left. The
/// model node validates every push, so a batch the chain would reject would be a refused push here.
#[tokio::test]
async fn random_interleavings_with_repeating_requests_pay_each_request_once_in_batches_the_chain_accepts() -> Result<()> {
    let mut total = Coverage::default();
    for seed in 1..=10 {
        let walk = random_walk(seed, true).await?;
        total.replaced += walk.replaced;
        total.blocked += walk.blocked;
        total.earlier_version_won += walk.earlier_version_won;
        total.rebuilt += walk.rebuilt;
        total.broadcasts += walk.broadcasts;
        total.confirmed += walk.confirmed;
        total.repeats_waiting += walk.repeats_waiting;
    }
    println!("random withdrawal interleavings with repeating requests covered: {total:?}");
    assert!(total.repeats_waiting >= 20, "the walks barely met a repeat waiting behind a batch: {total:?}");
    assert!(total.replaced >= 3, "too few replacements to mean anything: {total:?}");
    assert!(total.broadcasts >= 15 && total.confirmed >= 10, "{total:?}");
    Ok(())
}
