//! The fee-coin reserve (`docs/research/chain_service.md` §16, open item "запас
//! комиссионных монет", `the internal notes` §6): keep the operator wallet holding
//! at least `C2_FEE_COINS_MIN` (default 4) free standard coins of at least
//! `C2_FEE_COIN_MOJOS` (default 200,000,000 = 0.0002 TXCH) each, so the oracle
//! publisher and the PLAY withdrawal publisher -- every in-flight bundle
//! consumes one whole coin, `c2_cli::builders::fee::build_fee_only` -- never
//! have to wait for each other's coin.
//!
//! **Why**: with one coin in the wallet, whichever publisher has a bundle in
//! flight holds it, and the other one's next batch fails "no unspent,
//! unreserved coin" (a tick failure; three in a row degrade `/health`). The
//! fix is not a smarter allocator -- there is nothing to allocate -- but more
//! coins: this module splits a big coin into several fee-sized ones.
//!
//! **What one maintenance pass does** (`FeeCoinAllocator::maintain`, called from
//! the oracle publisher's tick, which runs every `publisher_tick`):
//!
//! 1. A split this process pushed earlier is polled until it settles: input
//!    coin spent -> confirmed; input unspent and bundle gone from the mempool
//!    after the retry backoff -> dropped, try again later. No second split is
//!    started while one is in flight ("wait for confirmation before the next
//!    maintenance attempt").
//! 2. Otherwise, only when the oracle has nothing in flight **and the allocator
//!    holds no reservation at all** (no in-flight bundle of either publisher holds
//!    a fee coin): if the wallet has fewer than `min_free` free coins of at least
//!    `coin_mojos`, take the largest standard coin and spend it into `min_free`
//!    coins of `coin_mojos` each plus change, paying the split's own fee out of the
//!    same coin (estimated like every other fee here, capped at `C2_MAX_FEE_MOJOS`).
//!    Pending records do not stop it: the one thing a split can delay is a batch
//!    that wants the wallet's only usable coin, by the few blocks the split needs,
//!    once -- and the publishers *wait* for it (`FeeCoinsBusy`), they do not fail.
//! 3. The coin being split is reserved in the allocator for as long as the split
//!    is in flight, so neither publisher can pick it as its fee coin meanwhile.
//!
//! **Never**: split in dry-run (`C2_PUSH=0`); touch a reserved coin; spend the
//! oracle singleton or the PLAY treasury (the wallet scan is by the operator's
//! *standard* puzzle hashes -- a singleton or a CAT coin has a different one --
//! and `build_fee_coin_split` refuses any coin whose puzzle hash is not its
//! owner's); start a split from a coin the node already shows being spent in its
//! mempool.
//!
//! **Restart safety without a file**: nothing here is persisted. A restarted
//! process finds the wallet below the minimum, picks the same largest coin, asks
//! the node whether anything in its mempool spends it (`coin_in_mempool`) and, if
//! the split from before the restart is still pending there, waits instead of
//! building a second one. Even if it did build one, both splits spend the same
//! input coin, so at most one can confirm -- the chain, not this process,
//! guarantees no coin is spent twice. A fee coin the oracle or withdrawal
//! publisher had picked while the split was pending is the §15 case (spent
//! elsewhere), already recovered by a rebuild with a fresh coin.

use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use chia_bls::{SecretKey, Signature};
use chia_protocol::{Bytes32, Coin, CoinSpend, SpendBundle};
use chia_puzzle_types::Memos;
use chia_sdk_driver::{SpendContext, StandardLayer};
use chia_sdk_types::Conditions;

use c2_cli::cost::measure_bundle_cost;
use c2_cli::keys::WalletKey;
use c2_cli::sign::sign_transaction;

use crate::fee_allocator::FeeCoinAllocator;
use crate::oracle::chain_client::ChainClient;

/// How many free fee coins to keep and how big each is (`C2_FEE_COINS_MIN`,
/// `C2_FEE_COIN_MOJOS`). **предложение**: 4 coins -- the oracle and the
/// withdrawal publisher each hold at most one at a time, a rebuild after a
/// double-spend needs a fresh one, and one spare -- of 200,000,000 mojos each:
/// four times `C2_MAX_FEE_MOJOS`'s default of 50,000,000, so even a fee that
/// doubled up to the cap leaves the coin able to pay it again.
/// `min_free == 0` turns the reserve off.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FeeCoinPolicy {
    pub min_free: usize,
    pub coin_mojos: u64,
}

impl Default for FeeCoinPolicy {
    fn default() -> Self {
        Self { min_free: 4, coin_mojos: 200_000_000 }
    }
}

/// What the last wallet scan saw: how many coins, and how many of them are free
/// fee coins (unreserved, standard, at least `coin_mojos`) worth how much in
/// total -- `GET /v1/oracle/status`'s `feeCoinsFree` / `feeCoinsFreeMojos`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FeeCoinView {
    pub total_coins: usize,
    pub free_coins: usize,
    pub free_mojos: u64,
}

impl FeeCoinView {
    pub(crate) fn of(coins: &[(Bytes32, u64)], reserved: &std::collections::HashSet<Bytes32>, policy: &FeeCoinPolicy) -> Self {
        let free = coins.iter().filter(|(id, amount)| *amount >= policy.coin_mojos && !reserved.contains(id));
        let (free_coins, free_mojos) = free.fold((0usize, 0u64), |(n, sum), (_, amount)| (n + 1, sum.saturating_add(*amount)));
        Self { total_coins: coins.len(), free_coins, free_mojos }
    }
}

/// A split this process has pushed and is waiting on. In memory only (module doc,
/// "Restart safety without a file").
#[derive(Debug, Clone)]
pub(crate) struct SplitInFlight {
    pub input: Coin,
    /// `SpendBundle::name()` -- what the node calls it (`ChainClient::in_mempool`).
    pub bundle_name: Bytes32,
    pub pushed_at: Instant,
}

/// Everything the reserve keeps besides the reservation set itself. One short
/// `std::sync::Mutex`, never held across an `.await`, so `status()` can read the
/// view while a tick is deep in a chain call.
#[derive(Debug, Default)]
pub(crate) struct ReserveState {
    pub split: Option<SplitInFlight>,
    pub retry_after: Option<Instant>,
    pub view: Option<FeeCoinView>,
}

/// What a maintenance pass needs from the publisher that runs it.
#[derive(Debug, Clone, Copy)]
pub struct MaintenanceParams {
    /// `false` = dry run: the reserve never pushes anything.
    pub push_enabled: bool,
    /// The split's fee is capped here, like every other fee (`C2_MAX_FEE_MOJOS`).
    pub max_fee_mojos: u64,
    pub fee_target_time_secs: u32,
    /// Grace before a pushed split that is not in the mempool counts as dropped,
    /// and the pause before trying again after a failed or dropped one.
    pub retry_backoff: Duration,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MaintenanceOutcome {
    /// The reserve is off (`C2_FEE_COINS_MIN=0`) or this is a dry run.
    Disabled,
    /// Nothing to do this tick (not idle, something reserved, backing off, ...).
    Skipped(&'static str),
    /// The wallet holds enough free fee coins.
    Healthy { free: usize },
    /// A split was built and pushed; its input coin is reserved until it settles.
    SplitPushed { input: Bytes32, outputs: usize, fee_mojos: u64 },
    /// A split is in flight and still pending.
    SplitPending,
    /// The in-flight split's input coin is spent: it landed.
    SplitConfirmed,
    /// The in-flight split is neither confirmed nor in the mempool any more.
    SplitDropped,
    /// The node refused the split.
    SplitRejected { error: String },
    /// The wallet is below the minimum but no coin is big enough to split.
    NoCoinLargeEnough { largest: u64, needed: u64 },
}

/// Returned (as the root cause of an `anyhow::Error`) by `FeeCoinAllocator::
/// reserve` when no coin is free **only because** the fee-coin split is in flight
/// and holds the one big coin. Publishers treat it as "wait for the next tick",
/// not as a tick failure: it resolves itself within a few blocks, and counting
/// it would degrade `/health` for no reason.
#[derive(Debug)]
pub struct FeeCoinsBusy;

impl std::fmt::Display for FeeCoinsBusy {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "no unreserved fee coin is free right now: the wallet's fee-coin split is waiting for confirmation")
    }
}

impl std::error::Error for FeeCoinsBusy {}

/// How long to leave the wallet alone after finding no coin big enough to split
/// -- nothing changes until the operator funds it, so scanning every tick would
/// only cost coinset calls.
const NO_COIN_RETRY: Duration = Duration::from_secs(300);

/// Total of the split's `outputs` coins: `base`, `base + 1`, ... (see
/// `build_fee_coin_split` for why they differ).
pub fn split_outputs_total(outputs: usize, base: u64) -> u64 {
    let n = outputs as u64;
    n.saturating_mul(base).saturating_add(n.saturating_mul(n.saturating_sub(1)) / 2)
}

pub struct FeeCoinSplit {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    /// The `outputs` fee coins, in order.
    pub outputs: Vec<Coin>,
    /// What goes back to the owner as change (0 = no change coin).
    pub change_amount: u64,
    /// The fee the bundle reserves -- `fee` as asked, plus the odd mojo moved
    /// out of the change to keep every output coin distinct.
    pub fee: u64,
}

/// Builds (and signs) the spend that turns `input` (a plain coin of `owner`, the
/// key that signs) into `outputs` coins of about `output_mojos` each plus change,
/// reserving `fee` mojos -- the split pays its own fee out of the input, no second
/// coin needed.
///
/// The outputs are `output_mojos`, `output_mojos + 1`, ... and NOT all equal:
/// Chia consensus rejects a bundle that creates the same coin twice (the coin id is
/// the hash of parent, puzzle hash and amount -- identical outputs of one parent to
/// one puzzle hash collide, `DUPLICATE_OUTPUT`; `tests/fee_reserve.rs` pins that
/// against the simulator). One mojo of difference is nothing to a fee coin. For the
/// same reason the change must not equal an output amount: if it would, a mojo moves
/// from change to fee until it does not.
pub fn build_fee_coin_split(
    input: Coin,
    owner: &WalletKey,
    outputs: usize,
    output_mojos: u64,
    fee: u64,
) -> Result<FeeCoinSplit> {
    if input.puzzle_hash != owner.puzzle_hash {
        // The guarantee behind "never spend the singleton or the treasury": only a plain
        // coin of this wallet's own standard puzzle hash is ever split.
        bail!("input coin does not belong to this wallet (puzzle hash mismatch)");
    }
    if outputs == 0 || output_mojos == 0 {
        bail!("a fee-coin split needs at least one output of at least one mojo");
    }
    let total = split_outputs_total(outputs, output_mojos);
    let Some(mut change) = input.amount.checked_sub(total).and_then(|rest| rest.checked_sub(fee)) else {
        bail!(
            "input coin has {} mojos, need at least {total} (outputs) + {fee} (fee)",
            input.amount
        );
    };
    let amounts: Vec<u64> = (0..outputs as u64).map(|i| output_mojos + i).collect();
    let mut fee = fee;
    while change > 0 && amounts.contains(&change) {
        fee += 1;
        change -= 1;
    }

    let mut ctx = SpendContext::new();
    let mut conditions = Conditions::new();
    for amount in &amounts {
        conditions = conditions.create_coin(owner.puzzle_hash, *amount, Memos::None);
    }
    if change > 0 {
        conditions = conditions.create_coin(owner.puzzle_hash, change, Memos::None);
    }
    if fee > 0 {
        conditions = conditions.reserve_fee(fee);
    }
    StandardLayer::new(owner.synthetic_pk).spend(&mut ctx, input, conditions)?;
    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, std::slice::from_ref(&owner.synthetic_sk))?;

    let parent = input.coin_id();
    Ok(FeeCoinSplit {
        coin_spends,
        signature,
        outputs: amounts.iter().map(|amount| Coin::new(parent, owner.puzzle_hash, *amount)).collect(),
        change_amount: change,
        fee,
    })
}

impl FeeCoinAllocator {
    pub(crate) fn state(&self) -> std::sync::MutexGuard<'_, ReserveState> {
        self.reserve_state.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// The policy this allocator keeps the wallet to.
    pub fn policy(&self) -> FeeCoinPolicy {
        self.policy
    }

    /// The last wallet scan's view (`None` before the first one, or after one failed).
    /// A plain in-memory read: `GET /v1/oracle/status` must never wait on the chain.
    pub fn view(&self) -> Option<FeeCoinView> {
        self.state().view
    }

    /// Forgets the view -- the last scan failed, so the status shows "unknown", not a stale number.
    pub fn clear_view(&self) {
        self.state().view = None;
    }

    /// Records a fresh wallet scan as the view. Waits for the reservation set (a `reserve` in
    /// progress holds it across its own scan), which is why only background loops call this,
    /// never a request handler.
    pub async fn record_scan(&self, coins: &[(WalletKey, Coin)]) {
        let snapshot = scan_snapshot(coins);
        let reserved = self.reserved.lock().await;
        self.state().view = Some(FeeCoinView::of(&snapshot, &reserved, &self.policy));
    }

    /// Whether a fee-coin split is in flight (its input coin is held in the reservation set).
    pub fn split_in_flight(&self) -> bool {
        self.state().split.is_some()
    }

    /// One pass of the fee-coin reserve (module doc). `may_start` says whether the caller is idle
    /// enough for a new split to begin; polling a split already in flight happens regardless.
    /// Errors are for the caller to log and move on from -- a failed pass leaves no reservation
    /// behind and never fails a publisher's tick.
    pub async fn maintain<C: ChainClient>(
        &self,
        chain: &C,
        master_sk: &SecretKey,
        params: &MaintenanceParams,
        may_start: bool,
    ) -> Result<MaintenanceOutcome> {
        if self.policy.min_free == 0 || !params.push_enabled {
            return Ok(MaintenanceOutcome::Disabled);
        }

        let in_flight = self.state().split.clone();
        if let Some(split) = in_flight {
            return self.poll_split(chain, split, params).await;
        }
        if !may_start {
            return Ok(MaintenanceOutcome::Skipped("the publisher is busy"));
        }
        if self.state().retry_after.is_some_and(|until| Instant::now() < until) {
            return Ok(MaintenanceOutcome::Skipped("backing off after a failed, dropped or impossible split"));
        }

        // From here until the input coin is reserved the reservation set stays locked: the
        // scan is the same atomic section `reserve` uses, so nothing can take the coin between
        // choosing it and holding it.
        let mut reserved = self.reserved.lock().await;
        if !reserved.is_empty() {
            return Ok(MaintenanceOutcome::Skipped("a fee coin is reserved by an in-flight spend"));
        }
        // The background scan keeps the view fresh; if it says the reserve is healthy there is
        // nothing to look at and no reason to spend 20 coinset calls on it.
        if let Some(view) = self.state().view
            && view.free_coins >= self.policy.min_free
        {
            return Ok(MaintenanceOutcome::Healthy { free: view.free_coins });
        }
        let mut coins = chain
            .unspent_coins(master_sk)
            .await
            .context("scanning the operator wallet for the fee-coin reserve")?;
        let view = FeeCoinView::of(&scan_snapshot(&coins), &reserved, &self.policy);
        self.state().view = Some(view);
        if view.free_coins >= self.policy.min_free {
            return Ok(MaintenanceOutcome::Healthy { free: view.free_coins });
        }

        // Below the minimum: the largest standard coin is the one to split (the first of equals).
        let Some(largest) = (0..coins.len()).max_by_key(|&i| (coins[i].1.amount, std::cmp::Reverse(i))) else {
            self.state().retry_after = Some(Instant::now() + NO_COIN_RETRY);
            return Ok(MaintenanceOutcome::NoCoinLargeEnough {
                largest: 0,
                needed: split_outputs_total(self.policy.min_free, self.policy.coin_mojos),
            });
        };
        let needed = split_outputs_total(self.policy.min_free, self.policy.coin_mojos);
        if coins[largest].1.amount < needed {
            self.state().retry_after = Some(Instant::now() + NO_COIN_RETRY);
            return Ok(MaintenanceOutcome::NoCoinLargeEnough { largest: coins[largest].1.amount, needed });
        }
        let (owner, input) = coins.swap_remove(largest);
        reserved.insert(input.coin_id());
        drop(reserved);

        match self.attempt_split(chain, params, &owner, input).await {
            Ok(pushed @ MaintenanceOutcome::SplitPushed { .. }) => Ok(pushed),
            Ok(other) => {
                self.release(input.coin_id()).await;
                self.state().retry_after = Some(Instant::now() + backoff_for(&other, params));
                Ok(other)
            }
            Err(e) => {
                self.release(input.coin_id()).await;
                self.state().retry_after = Some(Instant::now() + params.retry_backoff);
                Err(e)
            }
        }
    }

    /// Builds, prices and pushes the split of `input` (already reserved by the caller, which also
    /// releases it unless this returns `SplitPushed`).
    async fn attempt_split<C: ChainClient>(
        &self,
        chain: &C,
        params: &MaintenanceParams,
        owner: &WalletKey,
        input: Coin,
    ) -> Result<MaintenanceOutcome> {
        if chain
            .coin_in_mempool(input.coin_id())
            .await
            .context("asking the node whether the coin to split is already being spent")?
        {
            return Ok(MaintenanceOutcome::Skipped("the largest coin is already being spent in the node's mempool"));
        }
        let outputs = self.policy.min_free;
        let base = self.policy.coin_mojos;

        // Priced like every other bundle: estimate the fee for the cost of a draft without one --
        // and, like every other bundle, never for nothing because the estimate failed or answered 0
        // (`fee_estimate.rs`, `docs/research/chain_service.md` §17): the fallback rate, capped at
        // `C2_MAX_FEE_MOJOS`.
        let draft = build_fee_coin_split(input, owner, outputs, base, 0)?;
        let cost = measure_bundle_cost(&draft.coin_spends).context("measuring the fee-coin split's cost")?;
        let fee = self
            .fees
            .estimate(chain, cost.total_cost, params.fee_target_time_secs, params.max_fee_mojos)
            .await
            .fee_mojos;
        let needed = split_outputs_total(outputs, base).saturating_add(fee);
        if input.amount < needed {
            return Ok(MaintenanceOutcome::NoCoinLargeEnough { largest: input.amount, needed });
        }
        let split = if fee == 0 { draft } else { build_fee_coin_split(input, owner, outputs, base, fee)? };

        let bundle = SpendBundle::new(split.coin_spends, split.signature);
        let bundle_name = bundle.name();
        let pushed = chain.push_tx(bundle).await.context("push_tx for the fee-coin split")?;
        if !pushed.accepted {
            return Ok(MaintenanceOutcome::SplitRejected {
                error: pushed.error.unwrap_or_else(|| "(no error message)".to_string()),
            });
        }
        self.state().split = Some(SplitInFlight { input, bundle_name, pushed_at: Instant::now() });
        Ok(MaintenanceOutcome::SplitPushed { input: input.coin_id(), outputs, fee_mojos: split.fee })
    }

    async fn poll_split<C: ChainClient>(
        &self,
        chain: &C,
        split: SplitInFlight,
        params: &MaintenanceParams,
    ) -> Result<MaintenanceOutcome> {
        let record = chain
            .coin_record(split.input.coin_id())
            .await
            .context("checking whether the fee-coin split confirmed")?;
        if record.spent {
            self.finish_split(&split).await;
            return Ok(MaintenanceOutcome::SplitConfirmed);
        }
        let in_mempool = chain
            .in_mempool(split.bundle_name)
            .await
            .context("checking the mempool for the fee-coin split")?;
        if in_mempool || split.pushed_at.elapsed() < params.retry_backoff {
            return Ok(MaintenanceOutcome::SplitPending);
        }
        // Not confirmed, not in the mempool, past the grace period: the node dropped it. Nothing
        // was spent; free the coin and try again after the backoff.
        self.finish_split(&split).await;
        self.state().retry_after = Some(Instant::now() + params.retry_backoff);
        Ok(MaintenanceOutcome::SplitDropped)
    }

    async fn finish_split(&self, split: &SplitInFlight) {
        self.release(split.input.coin_id()).await;
        self.state().split = None;
    }
}

fn scan_snapshot(coins: &[(WalletKey, Coin)]) -> Vec<(Bytes32, u64)> {
    coins.iter().map(|(_, coin)| (coin.coin_id(), coin.amount)).collect()
}

/// Pause before the next attempt after a pass that ended without a split in flight.
fn backoff_for(outcome: &MaintenanceOutcome, params: &MaintenanceParams) -> Duration {
    match outcome {
        MaintenanceOutcome::NoCoinLargeEnough { .. } => NO_COIN_RETRY,
        _ => params.retry_backoff,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use chia_sdk_types::{Condition, run_puzzle_with_cost};
    use clvm_traits::FromClvm;
    use clvmr::{Allocator, NodePtr, serde::node_from_bytes};

    use c2_cli::keys::{derive_wallet_key, generate_mnemonic, master_sk_from_mnemonic};

    fn wallet() -> WalletKey {
        derive_wallet_key(&master_sk_from_mnemonic(&generate_mnemonic().unwrap()), 0)
    }

    /// What the split spend does, read back out of its own puzzle: (created amounts in order, reserved fee).
    fn effects(split: &FeeCoinSplit) -> (Vec<u64>, u64) {
        assert_eq!(split.coin_spends.len(), 1);
        let spend = &split.coin_spends[0];
        let mut allocator = Allocator::new();
        let puzzle = node_from_bytes(&mut allocator, spend.puzzle_reveal.as_ref()).unwrap();
        let solution = node_from_bytes(&mut allocator, spend.solution.as_ref()).unwrap();
        let reduction = run_puzzle_with_cost(&mut allocator, puzzle, solution, 11_000_000_000, false).unwrap();
        let conditions = Vec::<Condition<NodePtr>>::from_clvm(&allocator, reduction.1).unwrap();
        let mut created = Vec::new();
        let mut fee = 0;
        for condition in conditions {
            match condition {
                Condition::CreateCoin(create) => created.push(create.amount),
                Condition::ReserveFee(reserve) => fee += reserve.amount,
                _ => {}
            }
        }
        (created, fee)
    }

    #[test]
    fn the_outputs_are_one_mojo_apart_because_identical_coins_cannot_be_created_twice() {
        assert_eq!(split_outputs_total(0, 200_000_000), 0);
        assert_eq!(split_outputs_total(1, 200_000_000), 200_000_000);
        assert_eq!(split_outputs_total(4, 200_000_000), 800_000_006); // 200,000,000 + ..001 + ..002 + ..003
    }

    #[test]
    fn a_split_conserves_value_and_returns_everything_to_the_owner() {
        let owner = wallet();
        let input = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 1_000_000_000_000);
        let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 3_000_000).unwrap();

        let (created, fee) = effects(&split);
        assert_eq!(created, vec![200_000_000, 200_000_001, 200_000_002, 200_000_003, 1_000_000_000_000 - 800_000_006 - 3_000_000]);
        assert_eq!(fee, 3_000_000);
        assert_eq!(created.iter().sum::<u64>() + fee, input.amount, "inputs == outputs + fee: nothing burns");
        assert_eq!(split.fee, 3_000_000);
        assert_eq!(split.change_amount, 1_000_000_000_000 - 800_000_006 - 3_000_000);
        // The coins it reports are exactly the coins consensus will see created.
        assert_eq!(split.outputs.len(), 4);
        assert_eq!(split.outputs.iter().map(|c| c.amount).collect::<Vec<_>>(), created[..4].to_vec());
        assert!(split.outputs.iter().all(|c| c.parent_coin_info == input.coin_id() && c.puzzle_hash == owner.puzzle_hash));
    }

    #[test]
    fn an_input_that_is_exactly_enough_leaves_no_change_coin() {
        let owner = wallet();
        let input = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 800_000_006 + 500);
        let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 500).unwrap();
        let (created, fee) = effects(&split);
        assert_eq!(created.len(), 4, "no change coin of 0 mojos");
        assert_eq!((split.change_amount, fee), (0, 500));
    }

    #[test]
    fn a_zero_fee_split_reserves_nothing() {
        let owner = wallet();
        let input = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 10_000_000_000);
        let (created, fee) = effects(&build_fee_coin_split(input, &owner, 4, 200_000_000, 0).unwrap());
        assert_eq!(fee, 0);
        assert_eq!(created.iter().sum::<u64>(), 10_000_000_000);
    }

    /// A change that equals an output amount would create the same coin twice (consensus:
    /// DUPLICATE_OUTPUT). The change gives up a mojo at a time -- to the fee -- until it is distinct.
    #[test]
    fn a_change_that_would_equal_an_output_gives_mojos_to_the_fee_until_it_is_distinct() {
        let owner = wallet();
        // change would be 200,000,002 = the third output; then ..001, then ..000 collide too.
        let input = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 800_000_006 + 1_000 + 200_000_002);
        let split = build_fee_coin_split(input, &owner, 4, 200_000_000, 1_000).unwrap();
        let (created, fee) = effects(&split);
        let mut distinct = created.clone();
        distinct.sort_unstable();
        distinct.dedup();
        assert_eq!(distinct.len(), created.len(), "no two outputs may be the same coin: {created:?}");
        assert_eq!(fee, 1_000 + 3);
        assert_eq!(*created.last().unwrap(), 199_999_999);
        assert_eq!(created.iter().sum::<u64>() + fee, input.amount);
    }

    #[test]
    fn a_coin_that_is_not_the_owners_plain_coin_is_refused() {
        // The guarantee behind "never spend the oracle singleton or the PLAY treasury": their puzzle
        // hashes are not the operator's standard one, so they can never be the input of a split.
        let owner = wallet();
        let foreign = Coin::new(Bytes32::from([1u8; 32]), Bytes32::from([0xc3; 32]), 10_000_000_000);
        let err = build_fee_coin_split(foreign, &owner, 4, 200_000_000, 1_000).err().expect("must refuse");
        assert!(err.to_string().contains("does not belong to this wallet"), "{err}");
    }

    #[test]
    fn an_underfunded_input_and_degenerate_requests_are_refused() {
        let owner = wallet();
        let small = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 800_000_005);
        let err = build_fee_coin_split(small, &owner, 4, 200_000_000, 0).err().expect("must refuse");
        assert!(err.to_string().contains("need at least"), "{err}");
        let enough_for_outputs_only = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 800_000_006);
        assert!(build_fee_coin_split(enough_for_outputs_only, &owner, 4, 200_000_000, 1).is_err());
        let big = Coin::new(Bytes32::from([1u8; 32]), owner.puzzle_hash, 10_000_000_000);
        assert!(build_fee_coin_split(big, &owner, 0, 200_000_000, 0).is_err());
        assert!(build_fee_coin_split(big, &owner, 4, 0, 0).is_err());
    }

    #[test]
    fn the_default_policy_is_four_coins_of_two_hundred_million_mojos() {
        assert_eq!(FeeCoinPolicy::default(), FeeCoinPolicy { min_free: 4, coin_mojos: 200_000_000 });
    }
}
