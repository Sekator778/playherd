//! The in-flight oracle bundle that sits in the node's mempool and does not confirm is replaced
//! (`docs/research/chain_service.md` §17.3-§17.4) -- against the real consensus simulator with a
//! model mempool in front of it (`tests/sim_chain/mod.rs`): every bundle that confirms is validated
//! for real (puzzles, signature, value, a coin spent once), and the node's replacement rule is
//! transcribed from chia-blockchain 2.7.4's `can_replace()`, independently of the code under test.
//!
//! What these tests are for -- the safety the replacement must have:
//!
//! * (a) the old and the new bundle spend the same singleton coin: at most one confirms;
//! * (b) the OLD bundle confirms after the replacement was pushed: no record of the replacement is
//!   lost -- records that are not on chain go back to `pending` and a later batch publishes them,
//!   records that are on chain are confirmed exactly once;
//! * (c) the replacement confirms: the same;
//! * (d) no record is ever published twice, none is lost: the announcements on chain are exactly
//!   the queued seqs, each once;
//! * (e) a fee coin reserved for a bundle that lost the race is released.
//!
//! "Time" is the persisted `last_attempt_at_unix` of the in-flight spend: the tests age it instead
//! of sleeping.

mod log_capture;
mod sim_chain;

use std::collections::BTreeSet;
use std::time::Duration;

use anyhow::Result;
use chia_bls::SecretKey;
use chia_consensus::validation_error::ErrorCode;
use chia_protocol::{Bytes32, Coin, SpendBundle};
use chia_puzzle_types::{EveProof, Proof};
use chia_sdk_driver::{Launcher, SpendContext, StandardLayer};
use chia_sdk_test::{Simulator, SimulatorError};

use c2_cli::builders::fee::build_fee_only;
use c2_cli::keys::{WalletKey, derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};
use c2_cli::keystore::KeyFile;
use c2_cli::state::OracleState;
use c2_svc::fee_allocator::{FeeCoinAllocator, fee_coin_of};
use c2_svc::fee_reserve::FeeCoinPolicy;
use c2_svc::oracle::chain_client::ChainClient;
use c2_svc::oracle::lineage::walk_lineage;
use c2_svc::oracle::publisher::{Publisher, TickFailureState, TickOutcome};
use c2_svc::oracle::queue::{InFlightSpend, QueueFile};
use c2_svc::oracle::spend::{MAX_RECORDS_PER_SPEND, build_oracle_publish_batch};
use c2_svc::oracle::types::{OracleRecord, Source};
use c2_svc::state_file::{load_oracle_state, save_oracle_state_atomic};

use sim_chain::{PoolItem, SimChain, cost_and_fee, node_can_replace};

/// What the node's rule asks the replacement to add, restated (see `sim_chain`).
const MIN_INCREASE: u64 = 10_000_000;
const BIG_WALLET_COIN: u64 = 1_000_000_000_000;

struct Options {
    /// `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`. 0 = no floor: with a failing estimate the first
    /// bundle pays nothing, which is what the stand's publisher did on 2026-10-02.
    fallback_rate: u64,
    max_fee_mojos: u64,
    wallet_coins: Vec<u64>,
    /// `get_fee_estimate`: `None` fails.
    estimate: Option<u64>,
    replace_after: Duration,
}

impl Default for Options {
    fn default() -> Self {
        Self {
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
    publisher: Publisher<SimChain>,
    master_sk: SecretKey,
    manager: WalletKey,
    launcher_id: Bytes32,
    eve_coin: Coin,
}

fn record(seq: u64) -> OracleRecord {
    OracleRecord {
        seq,
        ts: 1_790_000_000 + seq as i64,
        price_cents: 65_432_100 + seq as i64,
        results_hash: Bytes32::from([seq as u8; 32]),
        source_flag: Source::Live.flag(),
        source_ts: 1_789_999_000 + seq as i64,
        asset: if seq.is_multiple_of(3) { 3 } else { 0 },
    }
}

fn records(seqs: std::ops::RangeInclusive<u64>) -> Vec<OracleRecord> {
    seqs.map(record).collect()
}

fn world(options: Options) -> Result<World> {
    let tmp = tempfile::tempdir()?;
    let data_dir = tmp.path().to_path_buf();
    let mnemonic = generate_mnemonic()?;
    let master_sk = master_sk_from_mnemonic(&mnemonic);
    let manager = derive_wallet_key(&master_sk, 0);
    std::fs::write(
        data_dir.join("operator.json"),
        serde_json::to_string_pretty(&KeyFile {
            mnemonic: mnemonic.to_string(),
            created_utc: "2026-10-02T00:00:00Z".to_string(),
            network: "testnet11".to_string(),
        })?,
    )?;

    // The oracle singleton, launched in the simulator the way `c2 oracle-launch` does it.
    let mut sim = Simulator::new();
    let funding = sim.new_coin(manager.puzzle_hash, 1);
    let ctx = &mut SpendContext::new();
    let launcher = Launcher::new(funding.coin_id(), 1);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(ctx, manager.puzzle_hash, ())?;
    StandardLayer::new(manager.synthetic_pk).spend(ctx, funding, create_singleton)?;
    sim.spend_coins(ctx.take(), std::slice::from_ref(&manager.synthetic_sk))?;
    let eve_proof = Proof::Eve(EveProof { parent_parent_coin_info: funding.coin_id(), parent_amount: 1 });
    for amount in &options.wallet_coins {
        sim.new_coin(manager.puzzle_hash, *amount);
    }
    save_oracle_state_atomic(
        &data_dir,
        "operator",
        &OracleState {
            launcher_id,
            manager_synthetic_pk: manager.synthetic_pk,
            current_coin: eve_coin,
            current_proof_dto: eve_proof.into(),
            next_sequence: 1,
        },
    )?;

    let chain = SimChain::new(sim);
    chain.set_estimate(options.estimate);
    let publisher = Publisher {
        data_dir,
        operator_name: "operator".to_string(),
        push_enabled: true,
        chain,
        retry_backoff: Duration::from_secs(0),
        max_retry_attempts: 8,
        max_fee_mojos: options.max_fee_mojos,
        fee_target_time_secs: 60,
        mempool_replace_after: options.replace_after,
        balance_snapshot: tokio::sync::RwLock::new(None),
        queue_lock: tokio::sync::Mutex::new(()),
        tick_failures: tokio::sync::RwLock::new(TickFailureState::default()),
        tick_failure_threshold: 3,
        // The fee-coin reserve is off (`min_free: 0`): these tests are about the one bundle in
        // flight, and a background split would only add a second bundle to the mempool.
        fee_allocator: std::sync::Arc::new(
            FeeCoinAllocator::with_policy(FeeCoinPolicy { min_free: 0, coin_mojos: 200_000_000 })
                .with_fallback_fee_rate(options.fallback_rate),
        ),
        queue_cache: Default::default(),
        retain_confirmed: std::time::Duration::from_secs(86_400),
    };
    Ok(World { _tmp: tmp, publisher, master_sk, manager, launcher_id, eve_coin })
}

impl World {
    fn queue(&self) -> QueueFile {
        QueueFile::load(&self.publisher.data_dir, "operator").expect("queue file")
    }

    fn in_flight(&self) -> InFlightSpend {
        self.queue().in_flight.expect("a bundle is in flight")
    }

    async fn enqueue(&self, seqs: std::ops::RangeInclusive<u64>) -> Result<()> {
        let accepted = self.publisher.enqueue(records(seqs.clone())).await?;
        assert_eq!(accepted, seqs.collect::<Vec<_>>());
        Ok(())
    }

    /// The in-flight bundle has been waiting `secs` longer than it was.
    fn age_in_flight(&self, secs: i64) {
        let mut queue = self.queue();
        let in_flight = queue.in_flight.as_mut().expect("a bundle is in flight");
        in_flight.last_attempt_at_unix -= secs;
        in_flight.broadcast_at_unix -= secs;
        queue.save_atomic(&self.publisher.data_dir, "operator").unwrap();
    }

    /// Every seq announced on chain, one entry per announcement (duplicates kept), ascending.
    async fn announced_on_chain(&self) -> Vec<u64> {
        let walk = walk_lineage(&self.publisher.chain, self.eve_coin).await.unwrap();
        let mut seqs = Vec::new();
        for generation in &walk.generations {
            seqs.extend(generation.announced.clone().expect("every generation on this chain is readable"));
        }
        seqs.sort_unstable();
        seqs
    }

    async fn reserved(&self, coin: &Coin) -> bool {
        self.publisher.fee_allocator.is_reserved(coin.coin_id()).await
    }

    /// `tick()` until it stops finding work, returning the outcomes.
    async fn tick(&self) -> TickOutcome {
        self.publisher.tick().await.expect("tick")
    }
}

fn removal_ids(bundle: &SpendBundle) -> BTreeSet<Bytes32> {
    bundle.coin_spends.iter().map(|spend| spend.coin.coin_id()).collect()
}

// --- the model mempool is not vacuous --------------------------------------------------------------

/// The tests below only mean something if the model node refuses a replacement that breaks the
/// rule. Three bundles for the singleton coin of a bundle already in the mempool, each breaking one
/// rule, are parked ("PENDING"), not accepted; the right one is accepted.
#[tokio::test]
async fn the_model_node_enforces_the_replacement_rule() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), wallet_coins: vec![BIG_WALLET_COIN, BIG_WALLET_COIN + 1, BIG_WALLET_COIN + 2], ..Options::default() })?;
    w.enqueue(1..=4).await?;
    assert!(matches!(w.tick().await, TickOutcome::Broadcast { records: 4, accepted: true }));
    let old = w.in_flight();
    let old_fee_coin = fee_coin_of(&old.bundle, old.spent_coin).expect("the first bundle paid a fee of 1,000 mojos");

    let state = load_oracle_state(&w.publisher.data_dir, "operator")?;
    let draft = build_oracle_publish_batch(w.launcher_id, state.current_coin, state.current_proof(), &w.manager, &old.records)?;
    let chain = &w.publisher.chain;
    let wallet = chain.unspent_coins(&w.master_sk).await?;
    let spare = wallet.iter().map(|(_, coin)| *coin).find(|coin| *coin != old_fee_coin).expect("a second wallet coin");

    let with_fee_coin = |coin: Coin, fee: u64| -> Result<SpendBundle> {
        let payer = derive_wallet_key(&w.master_sk, 0);
        let fee_spend = build_fee_only(coin, &payer, fee)?;
        let mut spends = draft.coin_spends.clone();
        spends.extend(fee_spend.coin_spends);
        Ok(SpendBundle::new(spends, draft.signature.clone() + &fee_spend.signature))
    };

    // 1. a higher fee, but another fee coin: the old bundle's coin is not spent -> not a superset
    let other_coin = with_fee_coin(spare, 20_000_000)?;
    let outcome = chain.push_tx(other_coin.clone()).await?;
    assert_eq!((outcome.accepted, outcome.status.as_deref()), (true, Some("PENDING")), "not a superset: {outcome:?}");
    assert!(!chain.mempool_holds(other_coin.name()));
    assert!(chain.mempool_holds(old.bundle.name()), "the old bundle is still the one in the mempool");

    // 2. the right coin, a fee that rises by less than the node's minimum increase
    let small_bump = with_fee_coin(old_fee_coin, 1_000 + MIN_INCREASE - 1)?;
    let outcome = chain.push_tx(small_bump.clone()).await?;
    assert_eq!(outcome.status.as_deref(), Some("PENDING"), "fee increase below 10,000,000: {outcome:?}");
    assert!(chain.mempool_holds(old.bundle.name()));

    // 3. the right coin, an increase of exactly the minimum: replaces
    let good = with_fee_coin(old_fee_coin, 1_000 + MIN_INCREASE)?;
    let outcome = chain.push_tx(good.clone()).await?;
    assert_eq!(outcome.status.as_deref(), Some("SUCCESS"), "{outcome:?}");
    assert!(chain.mempool_holds(good.name()));
    assert!(!chain.mempool_holds(old.bundle.name()), "the replaced bundle is gone");
    assert_eq!(chain.mempool().len(), 1);

    // the parked ones are not in the mempool, and the node still remembers them
    assert_eq!(chain.parked().len(), 2);
    Ok(())
}

/// Rule 3 on its own, with the fee per cost rising: a replacement that pays a *lower* fee per cost
/// than the bundle it replaces is refused even when it pays 10,000,000 more (a much more expensive
/// bundle) -- the case `required_replacement_fee` has its rate term for.
#[test]
fn the_model_node_refuses_a_lower_fee_per_cost_even_with_a_big_increase() {
    let coin = |n: u8| Bytes32::from([n; 32]);
    let item = |fee: u64, cost: u64, removals: &[u8]| PoolItem {
        bundle: SpendBundle::new(vec![], Default::default()),
        name: coin(250),
        fee,
        cost,
        removals: removals.iter().map(|n| coin(*n)).collect(),
    };
    let old = item(30_000_000, 30_000_000, &[1]); // 1 mojo per cost
    assert!(!node_can_replace(&[&old], &item(45_000_000, 60_000_000, &[1])), "0.75 < 1 per cost");
    assert!(node_can_replace(&[&old], &item(61_000_000, 60_000_000, &[1])));
    assert!(!node_can_replace(&[&old], &item(61_000_000, 60_000_000, &[2])), "does not spend the old coin");
    assert!(node_can_replace(&[&old], &item(61_000_000, 60_000_000, &[1, 2])), "a superset is fine");
}

// --- (c) the replacement confirms -------------------------------------------------------------------

/// 2026-10-02: a fee-0 bundle was accepted by a node whose mempool had room, and sat there while
/// full blocks went by. Past `C2_MEMPOOL_REPLACE_AFTER_SECS` it is replaced by a bundle that the
/// node accepts: the same singleton coin, a NEW fee coin (the old bundle had none -- a superset may
/// add a coin), a fee of exactly the 10,000,000 the rule asks. Records that arrived meanwhile ride
/// along. The replacement then confirms: every record confirmed once, the fee coin released.
#[tokio::test]
async fn a_fee_zero_bundle_that_sits_in_the_mempool_is_replaced_and_the_replacement_confirms() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=18).await?;
    assert_eq!(w.tick().await, TickOutcome::Broadcast { records: 18, accepted: true });
    let old = w.in_flight();
    assert_eq!(old.fee_mojos, 0, "no floor and no estimate: the bundle of 2026-10-02");
    assert_eq!(fee_coin_of(&old.bundle, old.spent_coin), None);
    assert!(w.publisher.chain.mempool_holds(old.bundle.name()));

    w.enqueue(19..=24).await?;

    // Not yet: the bundle has waited no time at all.
    assert_eq!(w.tick().await, TickOutcome::WaitingForConfirmation);
    assert_eq!(w.publisher.chain.pushes().len(), 1, "nothing pushed while it waits");

    w.age_in_flight(200);
    assert_eq!(
        w.tick().await,
        TickOutcome::Replaced { records: 24, fee_mojos: MIN_INCREASE, replaced_fee_mojos: 0 }
    );

    let replacement = w.in_flight();
    let new_fee_coin = fee_coin_of(&replacement.bundle, replacement.spent_coin).expect("the replacement gained a fee coin");
    assert_eq!(replacement.fee_mojos, MIN_INCREASE);
    assert_eq!(replacement.records.iter().map(|r| r.seq).collect::<Vec<_>>(), (1..=24).collect::<Vec<_>>());
    assert!(w.queue().pending.is_empty(), "the records that arrived while it waited went into the replacement");
    assert_eq!(replacement.spent_coin, old.spent_coin, "the same singleton coin");
    assert!(removal_ids(&replacement.bundle).is_superset(&removal_ids(&old.bundle)), "a superset of the old bundle's coins");
    assert_eq!(replacement.superseded.len(), 1);
    assert_eq!(replacement.superseded[0].bundle_name, old.bundle.name());
    assert_eq!(replacement.superseded[0].fee_mojos, 0);
    assert_eq!(replacement.superseded[0].record_seqs, (1..=18).collect::<Vec<_>>());
    assert!(w.reserved(&new_fee_coin).await, "the replacement's fee coin is reserved while it is in flight");

    let chain = &w.publisher.chain;
    assert_eq!(chain.mempool().len(), 1, "the node holds the replacement and only it");
    assert!(chain.mempool_holds(replacement.bundle.name()));
    assert!(!chain.mempool_holds(old.bundle.name()));
    let pushes = chain.pushes();
    assert_eq!(pushes.len(), 2);
    assert_eq!(pushes[1].outcome.as_ref().unwrap().status.as_deref(), Some("SUCCESS"));
    // the fee the node saw is the fee the publisher says it paid
    assert_eq!(cost_and_fee(&replacement.bundle)?.1, MIN_INCREASE);

    // The node includes it in a block (the simulator validates the whole bundle, signatures too).
    chain.confirm(replacement.bundle.name())?;
    let height = chain.height() - 1;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 24 });

    let queue = w.queue();
    assert!(queue.in_flight.is_none() && queue.pending.is_empty() && queue.conflicts.is_empty());
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), (1..=24).collect::<Vec<_>>());
    assert!(queue.confirmed.values().all(|info| info.height == Some(height)), "{:?}", queue.confirmed);
    assert_eq!(queue.last_confirmed_seq, Some(24));
    assert_eq!(load_oracle_state(&w.publisher.data_dir, "operator")?.current_coin, replacement.next_coin);
    assert!(!w.reserved(&new_fee_coin).await, "the fee coin is released once the bundle is confirmed");
    assert_eq!(w.announced_on_chain().await, (1..=24).collect::<Vec<_>>(), "each record on chain exactly once");
    let status = w.publisher.status().await?;
    assert_eq!((status.pending_records, status.stuck_reason), (0, None));
    Ok(())
}

// --- (b) the OLD bundle confirms after the replacement was pushed ------------------------------------

/// The replacement was accepted, but the block that comes is built from another node's mempool, which
/// still holds the OLD bundle. The singleton coin is consumed by the old bundle, so the replacement is
/// dead. The records the replacement carried and the old bundle did not are NOT marked confirmed:
/// they go back to the queue and the very next batch publishes them. The fee coin reserved for the
/// replacement -- which was never spent -- is released.
#[tokio::test]
async fn when_the_old_bundle_wins_the_replacements_extra_records_are_published_by_a_later_batch() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=18).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(19..=24).await?;
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, TickOutcome::Replaced { records: 24, .. }));
    let replacement = w.in_flight();
    let new_fee_coin = fee_coin_of(&replacement.bundle, replacement.spent_coin).unwrap();
    assert!(w.reserved(&new_fee_coin).await);

    // (a) real consensus: the replacement cannot confirm once the old bundle has -- same coin.
    let chain = &w.publisher.chain;
    chain.apply_to_chain(old.bundle.clone())?;
    match chain.apply_to_chain(replacement.bundle.clone()) {
        Err(SimulatorError::Validation(ErrorCode::DoubleSpend)) => {}
        other => panic!("the replacement must be refused as a double spend of the singleton, got {other:?}"),
    }
    assert!(chain.mempool().is_empty(), "the block evicted the replacement from the mempool");

    // The next tick reads the chain: the old bundle confirmed, 18 of the replacement's 24 records are
    // on chain, the other 6 are not -- they are published again, by a new batch, in the same tick.
    let outcome = w.tick().await;
    assert_eq!(outcome, TickOutcome::Broadcast { records: 6, accepted: true });
    assert!(!w.reserved(&new_fee_coin).await, "(e) the fee coin of the bundle that lost the race is released");

    let queue = w.queue();
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), (1..=18).collect::<Vec<_>>(), "only what the chain has");
    for seq in 19..=24 {
        assert!(!queue.confirmed.contains_key(&seq), "seq {seq} was never on chain and must not be confirmed");
    }
    let next = queue.in_flight.clone().expect("the six records are in flight again");
    assert_eq!(next.records.iter().map(|r| r.seq).collect::<Vec<_>>(), (19..=24).collect::<Vec<_>>());
    assert!(next.superseded.is_empty(), "a fresh bundle, not another version of the dead one");
    assert_eq!(next.spent_coin, old.next_coin, "built on the singleton the old bundle created");
    assert_eq!(load_oracle_state(&w.publisher.data_dir, "operator")?.current_coin, old.next_coin);
    assert_eq!(queue.last_confirmed_seq, Some(18));

    chain.confirm(next.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 6 });
    let queue = w.queue();
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), (1..=24).collect::<Vec<_>>());
    assert!(queue.in_flight.is_none() && queue.pending.is_empty());
    assert_eq!(w.announced_on_chain().await, (1..=24).collect::<Vec<_>>(), "(d) every record on chain exactly once, none twice");
    Ok(())
}

/// (a) from the other side, with the whole life of a bundle: the replacement confirms first and the
/// old bundle then cannot (the simulator says DoubleSpend), and the old bundle was never in the
/// mempool again.
#[tokio::test]
async fn of_two_bundles_for_one_singleton_coin_at_most_one_confirms() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=3).await?;
    w.tick().await;
    let old = w.in_flight();
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, TickOutcome::Replaced { .. }));
    let replacement = w.in_flight();
    assert_ne!(old.bundle.name(), replacement.bundle.name());

    w.publisher.chain.confirm(replacement.bundle.name())?;
    match w.publisher.chain.apply_to_chain(old.bundle.clone()) {
        Err(SimulatorError::Validation(ErrorCode::DoubleSpend)) => {}
        other => panic!("the old bundle must be refused as a double spend, got {other:?}"),
    }
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 3 });
    assert_eq!(w.announced_on_chain().await, vec![1, 2, 3]);
    Ok(())
}

// --- the fee coin the old bundle spent is kept ---------------------------------------------------------

/// A bundle that had a fee coin must keep spending it, or the node refuses the replacement
/// (superset rule): the replacement spends the very same coin, with a higher fee, and the coin stays
/// reserved -- one reservation, whichever version is in flight. When the OLD version wins, that coin
/// is spent by it and the reservation is released.
#[tokio::test]
async fn a_bundle_that_had_a_fee_coin_is_replaced_by_one_that_spends_the_same_coin() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue(1..=5).await?;
    assert!(matches!(w.tick().await, TickOutcome::Broadcast { records: 5, accepted: true }));
    let old = w.in_flight();
    let fee_coin = fee_coin_of(&old.bundle, old.spent_coin).expect("a fee of 1,000 mojos needs a fee coin");
    assert_eq!(old.fee_mojos, 1_000);

    w.age_in_flight(200);
    match w.tick().await {
        TickOutcome::Replaced { records: 5, fee_mojos, replaced_fee_mojos: 1_000 } => {
            assert_eq!(fee_mojos, 1_000 + MIN_INCREASE, "max(2 x old, estimate, old + 10,000,000)");
        }
        other => panic!("expected a replacement, got {other:?}"),
    }
    let replacement = w.in_flight();
    assert_eq!(fee_coin_of(&replacement.bundle, replacement.spent_coin), Some(fee_coin), "the same fee coin");
    assert!(removal_ids(&replacement.bundle).is_superset(&removal_ids(&old.bundle)));
    assert!(w.reserved(&fee_coin).await);
    assert!(w.publisher.chain.mempool_holds(replacement.bundle.name()));
    assert_eq!(cost_and_fee(&replacement.bundle)?.1, 1_000 + MIN_INCREASE);

    // The old version is the one a miner takes: the coin is spent by it, nothing is left reserved.
    w.publisher.chain.apply_to_chain(old.bundle.clone())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 5 });
    assert!(!w.reserved(&fee_coin).await);
    assert_eq!(w.announced_on_chain().await, (1..=5).collect::<Vec<_>>());
    Ok(())
}

/// A bundle can be replaced again and again; each version pays at least 10,000,000 more than the
/// last until the cap is reached, and the versions stay on record. Then it stops: no valid
/// replacement fits under the cap.
#[tokio::test]
async fn a_bundle_is_replaced_again_until_the_cap_leaves_no_valid_replacement() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), ..Options::default() })?;
    w.enqueue(1..=2).await?;
    w.tick().await;

    let mut fees = vec![w.in_flight().fee_mojos];
    for _ in 0..10 {
        w.age_in_flight(200);
        match w.tick().await {
            TickOutcome::Replaced { fee_mojos, replaced_fee_mojos, .. } => {
                assert!(fee_mojos >= replaced_fee_mojos + MIN_INCREASE, "{replaced_fee_mojos} -> {fee_mojos}");
                assert!(fee_mojos <= 50_000_000, "never above C2_MAX_FEE_MOJOS");
                fees.push(fee_mojos);
            }
            TickOutcome::ReplacementBlocked { .. } => break,
            other => panic!("unexpected {other:?} after fees {fees:?}"),
        }
    }
    // 1,000 -> 10,001,000 -> 20,002,000 -> 40,004,000, and then the rule asks for 50,004,000 -- more
    // than C2_MAX_FEE_MOJOS (50,000,000): no valid replacement, so it stops.
    assert_eq!(fees, vec![1_000, 10_001_000, 20_002_000, 40_004_000], "{fees:?}");
    let in_flight = w.in_flight();
    assert_eq!(in_flight.superseded.len(), 3);
    assert_eq!(in_flight.fee_mojos, 40_004_000);
    let reason = in_flight.replace_blocked.clone().expect("the last attempt was refused");
    assert!(reason.contains("C2_MAX_FEE_MOJOS"), "{reason}");
    assert_eq!(w.publisher.chain.mempool().len(), 1, "still exactly one bundle in the node's mempool");
    Ok(())
}

// --- the cap, and saying so ---------------------------------------------------------------------------

/// When `C2_MAX_FEE_MOJOS` leaves no valid replacement -- the rule needs the old fee + 10,000,000
/// and the cap is lower -- the bundle keeps waiting, nothing is pushed, the records waiting in the
/// queue are not touched, `stuckReason` says why, and it is not retried on every tick: another full
/// wait first. Raising the cap lets the next attempt through and the reason goes away.
#[tokio::test]
async fn a_cap_that_leaves_no_valid_replacement_is_reported_and_not_retried_every_tick() -> Result<()> {
    let w = world(Options { max_fee_mojos: 5_000_000, ..Options::default() })?;
    w.enqueue(1..=3).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(4..=6).await?;
    w.age_in_flight(200);

    let outcome = w.tick().await;
    let TickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("C2_MAX_FEE_MOJOS is 5000000"), "{reason}");
    assert!(reason.contains("10000000"), "the rule's minimum is named: {reason}");

    assert_eq!(w.publisher.chain.pushes().len(), 1, "nothing was pushed");
    assert!(w.publisher.chain.mempool_holds(old.bundle.name()), "the old bundle still waits in the mempool");
    let queue = w.queue();
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![4, 5, 6], "the waiting records are untouched");
    assert_eq!(queue.in_flight.as_ref().unwrap().bundle_id, old.bundle_id);
    let status = w.publisher.status().await?;
    assert!(status.stuck_reason.as_deref().is_some_and(|r| r.contains("could not be replaced") && r.contains("C2_MAX_FEE_MOJOS")), "{:?}", status.stuck_reason);
    assert_eq!(w.publisher.health_check().await?, status.stuck_reason, "health agrees with status: one signal");

    // Not a loop: the next ticks do nothing at all until another full wait has passed.
    for _ in 0..3 {
        assert_eq!(w.tick().await, TickOutcome::WaitingForConfirmation);
    }
    assert_eq!(w.publisher.chain.pushes().len(), 1);

    // The operator raises the cap; after the wait the replacement goes through and the reason is gone.
    let mut w = w;
    w.publisher.max_fee_mojos = 50_000_000;
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, TickOutcome::Replaced { records: 6, .. }));
    assert_eq!(w.publisher.status().await?.stuck_reason, None);
    Ok(())
}

/// A fee coin that cannot pay what the rule asks of the replacement is the same kind of dead end as
/// the cap, and is reported the same way: the old bundle's coin must be kept (superset rule), so a
/// replacement that needs more than the coin holds is impossible.
#[tokio::test]
async fn a_fee_coin_too_small_for_the_replacement_is_reported() -> Result<()> {
    let w = world(Options { estimate: Some(1_000), wallet_coins: vec![5_000_000], ..Options::default() })?;
    w.enqueue(1..=2).await?;
    assert!(matches!(w.tick().await, TickOutcome::Broadcast { .. }));
    w.age_in_flight(200);
    let outcome = w.tick().await;
    let TickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("5000000 mojos") && reason.contains("10001000"), "{reason}");
    assert_eq!(w.publisher.chain.pushes().len(), 1);
    Ok(())
}

// --- the node does not take the replacement ------------------------------------------------------------

/// The node refuses the replacement outright (`FAILED`). The old bundle goes back to being the
/// in-flight spend, the records merged from the queue go back to it, the fee coin reserved for the
/// replacement is released, and `stuckReason` names the node's answer. When the old bundle then
/// confirms, everything is accounted for and the records that waited are published.
#[tokio::test]
async fn a_replacement_the_node_refuses_leaves_the_old_bundle_in_flight_and_the_queue_intact() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=18).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(19..=24).await?;
    w.age_in_flight(200);

    w.publisher.chain.refuse_next_push("INVALID_FEE_TOO_CLOSE_TO_ZERO");
    let outcome = w.tick().await;
    let TickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("INVALID_FEE_TOO_CLOSE_TO_ZERO"), "{reason}");

    let queue = w.queue();
    let in_flight = queue.in_flight.clone().unwrap();
    assert_eq!(in_flight.bundle_id, old.bundle_id, "the old bundle is the in-flight spend again");
    assert_eq!(in_flight.records.len(), 18);
    assert!(in_flight.superseded.is_empty());
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), (19..=24).collect::<Vec<_>>(), "merged records returned");
    assert!(w.publisher.chain.mempool_holds(old.bundle.name()));
    for (_, coin) in w.publisher.chain.unspent_coins(&w.master_sk).await? {
        assert!(!w.reserved(&coin).await, "the fee coin of the refused replacement is released");
    }
    let stuck = w.publisher.status().await?.stuck_reason.expect("the refusal is reported");
    assert!(stuck.contains("INVALID_FEE_TOO_CLOSE_TO_ZERO"), "{stuck}");

    // The old bundle confirms; the 6 waiting records go out in a new batch; all is accounted for.
    w.publisher.chain.confirm(old.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Broadcast { records: 6, accepted: true });
    assert_eq!(w.publisher.status().await?.stuck_reason, None);
    let next = w.in_flight();
    w.publisher.chain.confirm(next.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 6 });
    assert_eq!(w.announced_on_chain().await, (1..=24).collect::<Vec<_>>());
    assert_eq!(w.queue().confirmed.len(), 24);
    Ok(())
}

/// The node *parks* the replacement (the answer is a success with `status: "PENDING"` -- the bundle is
/// in its conflict cache, not in the mempool). That is not a replacement: treated exactly like a
/// refusal. This is the case "the push was accepted" gets wrong.
#[tokio::test]
async fn a_replacement_the_node_parks_as_pending_is_not_a_replacement() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=4).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(5..=6).await?;
    w.age_in_flight(200);

    w.publisher.chain.park_next_push();
    let outcome = w.tick().await;
    let TickOutcome::ReplacementBlocked { reason } = outcome else { panic!("expected ReplacementBlocked, got {outcome:?}") };
    assert!(reason.contains("PENDING"), "{reason}");
    assert_eq!(w.publisher.chain.parked().len(), 1, "the node keeps the bundle in its conflict cache");
    let queue = w.queue();
    assert_eq!(queue.in_flight.as_ref().unwrap().bundle_id, old.bundle_id);
    assert_eq!(queue.pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![5, 6]);
    assert!(w.publisher.chain.mempool_holds(old.bundle.name()));

    // Whichever bundle the chain takes, nothing is lost: here the old one.
    w.publisher.chain.confirm(old.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Broadcast { records: 2, accepted: true });
    w.publisher.chain.confirm(w.in_flight().bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 2 });
    assert_eq!(w.announced_on_chain().await, (1..=6).collect::<Vec<_>>());
    Ok(())
}

/// The replacement the node did not take is not gone: it sits in the node's conflict cache and a block
/// can still be built with it. The records it merged had gone back to the queue when it was refused;
/// when the chain takes THAT replacement they are on chain -- confirmed, not published a second time --
/// and since every record of the spend is one this queue holds, it is not reported as someone else's.
#[tokio::test]
async fn a_replacement_that_was_refused_and_is_then_taken_by_the_chain_publishes_its_records_only_once() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=4).await?;
    w.tick().await;
    w.enqueue(5..=6).await?;
    w.age_in_flight(200);
    w.publisher.chain.park_next_push();
    assert!(matches!(w.tick().await, TickOutcome::ReplacementBlocked { .. }));
    assert_eq!(w.queue().pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![5, 6]);
    let refused = w.publisher.chain.parked().remove(0);

    let (_guard, logs) = log_capture::capture();
    w.publisher.chain.apply_to_chain(refused)?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 4 });
    let queue = w.queue();
    assert!(queue.in_flight.is_none());
    assert!(queue.pending.is_empty(), "records 5 and 6 are on chain and must not be published again: {:?}", queue.pending);
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), (1..=6).collect::<Vec<_>>());
    assert!(logs.lines_containing("WARN").is_empty(), "the spend was this service's own bundle:\n{}", logs.text());
    assert_eq!(w.tick().await, TickOutcome::Idle);
    assert_eq!(w.announced_on_chain().await, (1..=6).collect::<Vec<_>>());
    Ok(())
}

/// A crash (or a dropped connection) between making the replacement durable and the node receiving
/// it: the replacement is the persisted in-flight spend, the node still holds the old bundle. The next
/// tick pushes the replacement again, the node replaces, and the records of both are on chain once.
#[tokio::test]
async fn a_replacement_that_never_reached_the_node_is_pushed_again_by_the_next_tick() -> Result<()> {
    let w = world(Options::default())?;
    w.enqueue(1..=10).await?;
    w.tick().await;
    let old = w.in_flight();
    w.enqueue(11..=12).await?;
    w.age_in_flight(200);

    w.publisher.chain.fail_next_push();
    assert!(w.publisher.tick().await.is_err(), "the connection dropped");
    let queue = w.queue();
    let persisted = queue.in_flight.clone().unwrap();
    assert_ne!(persisted.bundle_id, old.bundle_id, "the replacement was made durable before the push");
    assert_eq!(persisted.records.len(), 12);
    assert!(queue.pending.is_empty(), "its records are the persisted bundle's, not lost in between");
    assert!(w.publisher.chain.mempool_holds(old.bundle.name()), "the node still holds the old bundle");

    let outcome = w.tick().await;
    assert_eq!(outcome, TickOutcome::Retried { accepted: true });
    assert!(w.publisher.chain.mempool_holds(persisted.bundle.name()));
    assert!(!w.publisher.chain.mempool_holds(old.bundle.name()));

    w.publisher.chain.confirm(persisted.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 12 });
    assert_eq!(w.announced_on_chain().await, (1..=12).collect::<Vec<_>>());
    Ok(())
}

// --- limits and settings -------------------------------------------------------------------------------

/// The merge is capped like a rebuild's: a replacement never carries more than
/// `MAX_RECORDS_PER_SPEND`; what does not fit stays pending and goes out later.
#[tokio::test]
async fn the_replacement_merges_at_most_the_maximum_records_per_spend() -> Result<()> {
    let w = world(Options::default())?;
    let first = MAX_RECORDS_PER_SPEND as u64 - 2;
    w.enqueue(1..=first).await?;
    w.tick().await;
    w.enqueue(first + 1..=first + 5).await?;
    w.age_in_flight(200);
    assert!(matches!(w.tick().await, TickOutcome::Replaced { records, .. } if records == MAX_RECORDS_PER_SPEND));
    assert_eq!(w.queue().pending.iter().map(|r| r.seq).collect::<Vec<_>>(), vec![first + 3, first + 4, first + 5]);
    Ok(())
}

/// `C2_MEMPOOL_REPLACE_AFTER_SECS=0` turns the replacement off: a bundle in the mempool is left
/// alone however long it waits (the behaviour before this change).
#[tokio::test]
async fn a_replace_after_of_zero_leaves_the_bundle_alone() -> Result<()> {
    let w = world(Options { replace_after: Duration::ZERO, ..Options::default() })?;
    w.enqueue(1..=2).await?;
    w.tick().await;
    w.age_in_flight(100_000);
    for _ in 0..3 {
        assert_eq!(w.tick().await, TickOutcome::WaitingForConfirmation);
    }
    assert_eq!(w.publisher.chain.pushes().len(), 1);
    Ok(())
}

/// A failed `get_fee_estimate` does not stop the replacement and never prices it at nothing (C1): the
/// fallback rate stands in for the estimate and the rule's own minimum (old + 10,000,000) decides.
#[tokio::test]
async fn a_failing_estimate_does_not_stop_the_replacement() -> Result<()> {
    let w = world(Options { fallback_rate: 100, estimate: Some(1_000), ..Options::default() })?;
    w.enqueue(1..=6).await?;
    assert!(matches!(w.tick().await, TickOutcome::Broadcast { records: 6, accepted: true }));
    assert_eq!(w.in_flight().fee_mojos, 1_000);

    w.publisher.chain.set_estimate(None); // "Upstream request timed out"
    w.age_in_flight(200);
    assert_eq!(
        w.tick().await,
        TickOutcome::Replaced { records: 6, fee_mojos: 1_000 + MIN_INCREASE, replaced_fee_mojos: 1_000 }
    );
    Ok(())
}

// --- our own confirmation is not an incident ------------------------------------------------------------

/// The chain walk that opens the tick sees our own bundle's confirmation: INFO "oracle publish batch
/// confirmed" with the records, height and fee -- and no WARN at all. (Before: INFO "dropped
/// already-confirmed records" then WARN "oracle state's current_coin was stale ... adopted the chain
/// tip", on every healthy batch.)
#[tokio::test]
async fn our_own_confirmation_found_by_the_chain_walk_is_an_info_line_and_never_a_warning() -> Result<()> {
    let w = world(Options { estimate: Some(2_000), ..Options::default() })?;
    w.enqueue(1..=7).await?;
    w.tick().await;
    let in_flight = w.in_flight();
    let fee_coin = fee_coin_of(&in_flight.bundle, in_flight.spent_coin).expect("fee coin");

    let (_guard, logs) = log_capture::capture();
    w.publisher.chain.confirm(in_flight.bundle.name())?;
    assert_eq!(w.tick().await, TickOutcome::Confirmed { records: 7 });

    let text = logs.text();
    let confirmed = logs.lines_containing("oracle publish batch confirmed");
    assert_eq!(confirmed.len(), 1, "exactly one confirmation line:\n{text}");
    assert!(confirmed[0].contains("INFO"), "{}", confirmed[0]);
    assert!(confirmed[0].contains("records=7") && confirmed[0].contains("fee_mojos=Some(2000)"), "{}", confirmed[0]);
    assert!(confirmed[0].contains("height=Some("), "{}", confirmed[0]);
    assert!(logs.lines_containing("WARN").is_empty(), "a healthy batch must not warn:\n{text}");
    assert!(logs.lines_containing("stale").is_empty(), "{text}");
    assert!(!w.reserved(&fee_coin).await, "the fee coin is released by the same path");
    let queue = w.queue();
    assert_eq!(queue.last_confirmed_seq, Some(7));
    assert!(queue.in_flight.is_none());
    assert_eq!(load_oracle_state(&w.publisher.data_dir, "operator")?.current_coin, in_flight.next_coin);
    Ok(())
}

/// A spend that is not ours -- someone published their own records with the manager key -- is still a
/// WARN, and it must not be taken for our confirmation: the records of the in-flight bundle that the
/// spend did not announce are NOT marked confirmed, they go back to the queue.
#[tokio::test]
async fn a_spend_that_is_not_ours_warns_and_its_missing_records_are_published_later() -> Result<()> {
    let w = world(Options { estimate: Some(2_000), ..Options::default() })?;
    w.enqueue(1..=3).await?;
    w.tick().await;
    let in_flight = w.in_flight();
    let fee_coin = fee_coin_of(&in_flight.bundle, in_flight.spent_coin).unwrap();

    // Someone else spends the singleton coin, announcing one record that is not in our bundle (seq 90).
    let state = load_oracle_state(&w.publisher.data_dir, "operator")?;
    let foreign = build_oracle_publish_batch(w.launcher_id, state.current_coin, state.current_proof(), &w.manager, &[record(90)])?;
    let (_guard, logs) = log_capture::capture();
    w.publisher.chain.apply_to_chain(SpendBundle::new(foreign.coin_spends, foreign.signature))?;

    // Nothing of ours is on chain: the three records are requeued and published again at once.
    assert_eq!(w.tick().await, TickOutcome::Broadcast { records: 3, accepted: true });
    let warnings = logs.lines_containing("WARN");
    assert!(warnings.iter().any(|line| line.contains("is not this service's bundle")), "{}", logs.text());
    assert!(logs.lines_containing("oracle publish batch confirmed").is_empty(), "{}", logs.text());
    let queue = w.queue();
    assert!(queue.confirmed.is_empty(), "none of our records is on chain: {:?}", queue.confirmed);
    assert_eq!(queue.last_confirmed_seq, Some(90), "the chain's own high-water mark is still tracked");
    // The dead bundle's fee coin was never spent. It is free again, and the one coin the wallet has is
    // what the new batch pays with -- which could not have been reserved for the new batch had the dead
    // bundle still held it.
    let reissued = w.in_flight();
    assert_eq!(fee_coin_of(&reissued.bundle, reissued.spent_coin), Some(fee_coin));
    Ok(())
}

// --- random interleavings ---------------------------------------------------------------------------------

/// xorshift64*: a few lines instead of a dependency, and the same walk for the same seed.
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

/// What must hold after every single operation, whatever the order of events was:
///
/// * every queued seq is in exactly one place -- pending, in flight, confirmed or conflicting;
/// * everything the queue calls confirmed really is announced on chain, and nothing is announced twice;
/// * a bundle in flight spends the coin the oracle state is at.
async fn assert_invariants(w: &World, queued_up_to: u64, context: &str) {
    let queue = w.queue();
    let mut places: std::collections::BTreeMap<u64, Vec<&str>> = std::collections::BTreeMap::new();
    for r in &queue.pending {
        places.entry(r.seq).or_default().push("pending");
    }
    if let Some(f) = &queue.in_flight {
        for r in &f.records {
            places.entry(r.seq).or_default().push("in flight");
        }
    }
    for seq in queue.confirmed.keys() {
        places.entry(*seq).or_default().push("confirmed");
    }
    for seq in queue.conflicts.keys() {
        places.entry(*seq).or_default().push("conflict");
    }
    for seq in 1..=queued_up_to {
        let here = places.get(&seq).cloned().unwrap_or_default();
        assert_eq!(here.len(), 1, "{context}: seq {seq} is in {here:?} (must be in exactly one place)");
    }
    let on_chain = w.announced_on_chain().await;
    let distinct: BTreeSet<u64> = on_chain.iter().copied().collect();
    assert_eq!(distinct.len(), on_chain.len(), "{context}: a seq was published twice: {on_chain:?}");
    for seq in queue.confirmed.keys() {
        assert!(distinct.contains(seq), "{context}: seq {seq} is marked confirmed but is not on chain ({on_chain:?})");
    }
    if let Some(f) = &queue.in_flight {
        let state = load_oracle_state(&w.publisher.data_dir, "operator").unwrap();
        assert_eq!(f.spent_coin, state.current_coin, "{context}: the in-flight bundle spends another coin than the oracle state is at");
    }
}

/// What one random walk went through, so that the test can insist the walks are not vacuous.
#[derive(Default, Debug)]
struct Coverage {
    replaced: usize,
    blocked: usize,
    earlier_version_won: usize,
    foreign_spend_warnings: usize,
    broadcasts: usize,
    confirmed_ticks: usize,
}

async fn random_walk(seed: u64) -> Result<Coverage> {
    let (_log_guard, logs) = log_capture::capture();
    let mut coverage = Coverage::default();
    let mut rng = Rng::new(seed);
    let max_fee_mojos = if rng.below(100) < 30 { 5_000_000 } else { 50_000_000 };
    let w = world(Options {
        fallback_rate: 100,
        estimate: if rng.below(2) == 0 { None } else { Some(1_000 + rng.below(3_000_000)) },
        max_fee_mojos,
        // sometimes a small coin first (reservations that cannot be raised: "fee coin too small"),
        // sometimes only coins that can pay any fee
        wallet_coins: if rng.below(3) == 0 { vec![6_000_000, 60_000_000, BIG_WALLET_COIN] } else { vec![BIG_WALLET_COIN, BIG_WALLET_COIN + 7] },
        ..Options::default()
    })?;
    let chain = &w.publisher.chain;
    chain.set_validate_on_push(false); // every bundle that is confirmed is validated for real anyway
    let mut queued = 0u64;

    let singleton_ph = w.eve_coin.puzzle_hash;
    for step in 0..50 {
        let context = format!("seed {seed} step {step}");
        let mut tick_now = false;
        match rng.below(100) {
            0..=17 => {
                let n = 1 + rng.below(6);
                w.enqueue(queued + 1..=queued + n).await?;
                queued += n;
            }
            18..=37 => tick_now = true,
            38..=57 => {
                // the bundle has been waiting: age it, then the publisher looks at it
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
                // Some miner's mempool held one of the bundles this publisher pushed for the singleton
                // coin as it is now -- not necessarily the newest, not necessarily one this node kept.
                let mut candidates = Vec::new();
                for push in chain.pushes() {
                    let singleton = push.bundle.coin_spends.iter().find(|spend| spend.coin.puzzle_hash == singleton_ph).map(|spend| spend.coin);
                    if let Some(coin) = singleton
                        && !chain.coin_record(coin.coin_id()).await?.spent
                    {
                        candidates.push(push.bundle);
                    }
                }
                if !candidates.is_empty() {
                    let pick = rng.below(candidates.len() as u64) as usize;
                    // a bundle whose fee coin has been spent since is refused by consensus: no change
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
                    TickOutcome::Replaced { .. } => coverage.replaced += 1,
                    TickOutcome::ReplacementBlocked { .. } => coverage.blocked += 1,
                    TickOutcome::Broadcast { .. } => coverage.broadcasts += 1,
                    TickOutcome::Confirmed { .. } => coverage.confirmed_ticks += 1,
                    _ => {}
                },
                Err(e) => {
                    let text = format!("{e:#}");
                    assert!(text.contains("connection reset by peer"), "{context}: tick failed for another reason than the injected one: {text}");
                }
            }
        }
        assert_invariants(&w, queued, &context).await;
    }

    // Let the world settle: blocks keep coming, the publisher keeps ticking.
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
        assert_invariants(&w, queued, &format!("seed {seed} settle {round}")).await;
    }

    let queue = w.queue();
    assert!(queue.in_flight.is_none() && queue.pending.is_empty(), "seed {seed}: did not settle: {queue:?}");
    assert!(queue.conflicts.is_empty(), "seed {seed}: {:?}", queue.conflicts);
    assert_eq!(queue.confirmed.keys().copied().collect::<Vec<_>>(), (1..=queued).collect::<Vec<_>>(), "seed {seed}");
    assert_eq!(w.announced_on_chain().await, (1..=queued).collect::<Vec<_>>(), "seed {seed}: each record on chain exactly once");

    // (e) nothing stays reserved once nothing is in flight -- not the coin of a version that lost.
    // The coins to look at: every coin any bundle ever pushed spent besides the singleton, and every
    // coin the wallet holds now.
    let mut wallet_coins: Vec<Coin> = chain.with_sim(|sim| sim.unspent_coins(w.manager.puzzle_hash, false));
    for push in chain.pushes() {
        wallet_coins.extend(push.bundle.coin_spends.iter().map(|spend| spend.coin).filter(|coin| coin.puzzle_hash != w.eve_coin.puzzle_hash));
    }
    for coin in &wallet_coins {
        assert!(!w.reserved(coin).await, "seed {seed}: wallet coin {} is still reserved with nothing in flight", coin.coin_id());
    }
    let status = w.publisher.status().await?;
    assert_eq!(status.stuck_reason, None, "seed {seed}");
    let walk = walk_lineage(&w.publisher.chain, w.eve_coin).await?;
    assert_eq!(load_oracle_state(&w.publisher.data_dir, "operator")?.current_coin, walk.tip_coin, "seed {seed}");

    coverage.earlier_version_won = logs.lines_containing("the chain took an earlier version").len();
    coverage.foreign_spend_warnings = logs.lines_containing("is not this service's bundle").len();
    Ok(coverage)
}

/// The safety argument of the replacement, tried a few hundred times in orders nobody wrote down:
/// records arrive, ticks happen, bundles age, a block includes the node's newest bundle or ANY bundle
/// this publisher ever pushed (an old version some other mempool still held), the node refuses, parks
/// or drops a push -- and after every step every record is in one place, nothing is marked confirmed
/// that the chain does not have, nothing is on chain twice; at the end everything is on chain exactly
/// once and no fee coin is left reserved. Deterministic: the same seeds every run.
#[tokio::test]
async fn random_interleavings_never_lose_or_duplicate_a_record() -> Result<()> {
    let mut total = Coverage::default();
    for seed in 1..=24 {
        let walk = random_walk(seed).await?;
        total.replaced += walk.replaced;
        total.blocked += walk.blocked;
        total.earlier_version_won += walk.earlier_version_won;
        total.foreign_spend_warnings += walk.foreign_spend_warnings;
        total.broadcasts += walk.broadcasts;
        total.confirmed_ticks += walk.confirmed_ticks;
    }
    println!("random interleavings covered: {total:?}");
    // The walks must actually have gone through the cases the test is about.
    assert!(total.replaced >= 15, "too few replacements to mean anything: {total:?}");
    assert!(total.blocked >= 5, "{total:?}");
    assert!(total.earlier_version_won >= 3, "an old version of a bundle never won: {total:?}");
    assert!(total.broadcasts >= 24 && total.confirmed_ticks >= 10, "{total:?}");
    // Every spend on these chains was one of this service's own bundles.
    assert_eq!(total.foreign_spend_warnings, 0, "{total:?}");
    Ok(())
}
