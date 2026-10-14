//! A `ChainClient` over the real consensus simulator (`chia-sdk-test`'s `Simulator`) with a model
//! of a node's mempool in front of it -- the chain the replacement tests (`tests/oracle_replace.rs`)
//! run the publisher against (`docs/research/chain_service.md` §17.7).
//!
//! What is real: every bundle that is *confirmed* here goes through the simulator's consensus
//! validation -- puzzles run, conditions checked, the aggregated signature verified (the
//! simulator and `c2_cli::sign` both use the testnet11 constants), value conserved, a coin spent
//! once -- so "the publisher's bundle is a valid bundle" and "of two bundles for one coin at most
//! one confirms" are consensus facts, not assumptions. Coin records, spends (`puzzle_and_solution`)
//! and heights come from the simulator's own state.
//!
//! What is modelled: the mempool. `push_tx` accepts a bundle that is valid against the current
//! chain, and when it spends a coin that a mempool item already spends it applies the node's
//! replacement rule exactly as `chia-blockchain` 2.7.4 `can_replace()` states it (superset of the
//! conflicting items' coins, strictly higher fee per cost, fee increase of at least 10,000,000
//! mojos; `chia/full_node/mempool_manager.py`, `docs/research/chain_service.md` §17.3) -- written
//! out here from the Python source, *not* by calling `c2_svc::mempool_rule`, so the code under test
//! cannot satisfy a rule it also defined. A refused replacement is parked in a conflict cache and
//! answered `status: "PENDING"` with success, like the real `push_tx` RPC; the cache is retried when a
//! block is applied, like `new_peak()`. A block evicts every mempool item that spends a coin the block
//! spent.

#![allow(dead_code)]

use std::collections::BTreeSet;
use std::sync::Mutex;

use anyhow::{Result, bail};
use chia_bls::SecretKey;
use chia_consensus::{allocator::make_allocator, spendbundle_conditions::run_spendbundle};
use chia_protocol::{Bytes32, Coin, CoinSpend, SpendBundle};
use chia_sdk_test::{Simulator, SimulatorError};
use chia_sdk_types::TESTNET11_CONSTANTS;
use clvmr::{ENABLE_KECCAK_OPS_OUTSIDE_GUARD, LIMIT_HEAP};

use c2_cli::keys::{WalletKey, derive_wallet_key};
use c2_svc::oracle::chain_client::{ChainClient, CoinRecordInfo, PushOutcome};
use c2_svc::play::chain_client::{PlayChainClient, PuzzleHashCoinRecord};

/// `MEMPOOL_MIN_FEE_INCREASE` of `mempool_manager.py` (2.7.4, line 51) -- restated here, see the
/// module doc.
const NODE_MIN_FEE_INCREASE: u64 = 10_000_000;

/// One bundle in the model mempool.
#[derive(Debug, Clone)]
pub struct PoolItem {
    pub bundle: SpendBundle,
    pub name: Bytes32,
    pub fee: u64,
    pub cost: u64,
    pub removals: BTreeSet<Bytes32>,
}

/// What `push_tx` was called with and what the model node answered.
#[derive(Debug, Clone)]
pub struct PushLog {
    pub bundle: SpendBundle,
    pub name: Bytes32,
    pub outcome: Option<PushOutcome>,
}

struct Inner {
    sim: Simulator,
    mempool: Vec<PoolItem>,
    parked: Vec<SpendBundle>,
    pushes: Vec<PushLog>,
    /// `Some(fee)` = `get_fee_estimate` answers `fee`; `None` = it fails ("Upstream request timed out").
    estimate: Option<u64>,
    transport_error_next_push: bool,
    refuse_next_push: Option<String>,
    park_next_push: bool,
    /// Check every pushed bundle against the simulator's full consensus validation (signature
    /// included) on a copy of its state. Off for the randomised test, which would otherwise pay
    /// for a pairing check per push; every bundle that is *confirmed* is validated either way.
    validate_on_push: bool,
}

pub struct SimChain(Mutex<Inner>);

/// Cost and fee of a bundle exactly as the node computes them: `cost` of the conditions run, `fee`
/// = coins spent minus coins created.
pub fn cost_and_fee(bundle: &SpendBundle) -> Result<(u64, u64)> {
    let mut allocator = make_allocator(LIMIT_HEAP);
    let (conditions, _) = run_spendbundle(
        &mut allocator,
        bundle,
        11_000_000_000 / 2,
        ENABLE_KECCAK_OPS_OUTSIDE_GUARD,
        &TESTNET11_CONSTANTS,
    )
    .map_err(|e| anyhow::anyhow!("{e:?}"))?;
    let fee = conditions
        .removal_amount
        .checked_sub(conditions.addition_amount)
        .ok_or_else(|| anyhow::anyhow!("bundle creates more than it spends"))?;
    Ok((conditions.cost, u64::try_from(fee)?))
}

/// `can_replace()` of `chia-blockchain` 2.7.4 `chia/full_node/mempool_manager.py`, lines 1138-1231,
/// for bundles with no time locks and no fast-forward or dedup eligibility (ours have none).
pub fn node_can_replace(conflicting: &[&PoolItem], new: &PoolItem) -> bool {
    let mut conflicting_fees = 0u64;
    let mut conflicting_cost = 0u64;
    for item in conflicting {
        conflicting_fees += item.fee;
        conflicting_cost += item.cost;
        // "All coins spent in all conflicting items must also be spent in the new item."
        if !item.removals.is_subset(&new.removals) {
            return false;
        }
    }
    // "New item must have higher fee per cost" (Python float division, like here).
    let conflicting_fee_per_cost = conflicting_fees as f64 / conflicting_cost as f64;
    let new_fee_per_cost = new.fee as f64 / new.cost as f64;
    if new_fee_per_cost <= conflicting_fee_per_cost {
        return false;
    }
    // "New item must increase the total fee at least by a certain amount".
    match new.fee.checked_sub(conflicting_fees) {
        Some(increase) => increase >= NODE_MIN_FEE_INCREASE,
        None => false,
    }
}

impl SimChain {
    pub fn new(sim: Simulator) -> Self {
        Self(Mutex::new(Inner {
            sim,
            mempool: Vec::new(),
            parked: Vec::new(),
            pushes: Vec::new(),
            estimate: Some(1_000),
            transport_error_next_push: false,
            refuse_next_push: None,
            park_next_push: false,
            validate_on_push: true,
        }))
    }

    pub fn set_validate_on_push(&self, validate: bool) {
        self.0.lock().unwrap().validate_on_push = validate;
    }

    /// `Some(fee)`: `get_fee_estimate` answers `fee`. `None`: it fails.
    pub fn set_estimate(&self, estimate: Option<u64>) {
        self.0.lock().unwrap().estimate = estimate;
    }

    /// The next `push_tx` fails at the transport level (the connection drops): the publisher gets an
    /// error, and the bundle never reaches the node.
    pub fn fail_next_push(&self) {
        self.0.lock().unwrap().transport_error_next_push = true;
    }

    /// The next `push_tx` is refused by the node: `FAILED`, i.e. the RPC raises
    /// `Failed to include transaction ..., error <error>`.
    pub fn refuse_next_push(&self, error: &str) {
        self.0.lock().unwrap().refuse_next_push = Some(error.to_string());
    }

    /// The next `push_tx` is parked in the conflict cache and answered `status: "PENDING"`, as the
    /// node does with a replacement whose conflict it will not resolve -- whatever the bundle.
    pub fn park_next_push(&self) {
        self.0.lock().unwrap().park_next_push = true;
    }

    pub fn pushes(&self) -> Vec<PushLog> {
        self.0.lock().unwrap().pushes.clone()
    }

    pub fn mempool(&self) -> Vec<PoolItem> {
        self.0.lock().unwrap().mempool.clone()
    }

    pub fn mempool_holds(&self, name: Bytes32) -> bool {
        self.0.lock().unwrap().mempool.iter().any(|item| item.name == name)
    }

    pub fn parked(&self) -> Vec<SpendBundle> {
        self.0.lock().unwrap().parked.clone()
    }

    pub fn height(&self) -> u32 {
        self.0.lock().unwrap().sim.height()
    }

    pub fn with_sim<R>(&self, f: impl FnOnce(&mut Simulator) -> R) -> R {
        f(&mut self.0.lock().unwrap().sim)
    }

    /// A block that includes the mempool item `name`: the simulator validates it for real, the
    /// item leaves the mempool, and so does every other item spending a coin it spent.
    pub fn confirm(&self, name: Bytes32) -> Result<(), SimulatorError> {
        let bundle = {
            let inner = self.0.lock().unwrap();
            inner
                .mempool
                .iter()
                .find(|item| item.name == name)
                .unwrap_or_else(|| panic!("{name} is not in the model mempool"))
                .bundle
                .clone()
        };
        self.apply_to_chain(bundle)
    }

    /// A block that includes `bundle` although this node's mempool may not hold it -- some other
    /// node's mempool did, e.g. an earlier version of a bundle this node already replaced.
    pub fn apply_to_chain(&self, bundle: SpendBundle) -> Result<(), SimulatorError> {
        let mut inner = self.0.lock().unwrap();
        inner.sim.new_transaction(bundle.clone())?;
        let spent: BTreeSet<Bytes32> = bundle.coin_spends.iter().map(|spend| spend.coin.coin_id()).collect();
        inner.mempool.retain(|item| item.removals.is_disjoint(&spent));
        // `new_peak()`: the parked bundles are tried again against the new state.
        let parked = std::mem::take(&mut inner.parked);
        for parked_bundle in parked {
            inner.admit(parked_bundle, false).ok();
        }
        Ok(())
    }
}

impl Inner {
    /// The node's answer to a bundle (`add_spend_bundle`): valid on its own, then conflict handling.
    /// `record` = this is a `push_tx` (not a retry of the conflict cache) and is logged.
    fn admit(&mut self, bundle: SpendBundle, record: bool) -> Result<PushOutcome> {
        let name = bundle.name();
        let outcome = self.decide(bundle.clone(), name);
        if record {
            self.pushes.push(PushLog { bundle, name, outcome: outcome.as_ref().ok().cloned() });
        }
        outcome
    }

    fn decide(&mut self, bundle: SpendBundle, name: Bytes32) -> Result<PushOutcome> {
        if self.transport_error_next_push {
            self.transport_error_next_push = false;
            bail!("error sending request: connection reset by peer");
        }
        let failed = |error: &str| PushOutcome {
            accepted: false,
            status: None,
            error: Some(format!("Failed to include transaction {name}, error {error}")),
        };
        if let Some(error) = self.refuse_next_push.take() {
            return Ok(failed(&error));
        }
        if self.mempool.iter().any(|item| item.name == name) {
            return Ok(PushOutcome { accepted: true, status: Some("SUCCESS".to_string()), error: None });
        }
        let Ok((cost, fee)) = cost_and_fee(&bundle) else {
            return Ok(failed("INVALID_SPEND_BUNDLE"));
        };
        // Valid against the chain as it is now: every coin exists and is unspent; with
        // `validate_on_push` the simulator's complete validation (signature, conditions, value).
        if self.validate_on_push {
            let mut probe = self.sim.clone();
            if let Err(e) = probe.new_transaction(bundle.clone()) {
                let error = match e {
                    SimulatorError::Validation(code) => format!("{code:?}").to_ascii_uppercase(),
                    other => format!("{other}"),
                };
                return Ok(failed(&error));
            }
        } else {
            for spend in &bundle.coin_spends {
                match self.sim.coin_state(spend.coin.coin_id()) {
                    None => return Ok(failed("UNKNOWN_UNSPENT")),
                    Some(state) if state.spent_height.is_some() => return Ok(failed("DOUBLE_SPEND")),
                    Some(_) => {}
                }
            }
        }
        let removals: BTreeSet<Bytes32> = bundle.coin_spends.iter().map(|spend| spend.coin.coin_id()).collect();
        let item = PoolItem { bundle: bundle.clone(), name, fee, cost, removals };

        let conflicting: Vec<usize> = self
            .mempool
            .iter()
            .enumerate()
            .filter(|(_, existing)| !existing.removals.is_disjoint(&item.removals))
            .map(|(index, _)| index)
            .collect();
        let replaces = if conflicting.is_empty() {
            true
        } else {
            let existing: Vec<&PoolItem> = conflicting.iter().map(|&index| &self.mempool[index]).collect();
            node_can_replace(&existing, &item)
        };
        if self.park_next_push {
            self.park_next_push = false;
            self.parked.push(bundle);
            return Ok(PushOutcome { accepted: true, status: Some("PENDING".to_string()), error: None });
        }
        if replaces {
            for &index in conflicting.iter().rev() {
                self.mempool.remove(index);
            }
            self.mempool.push(item);
            Ok(PushOutcome { accepted: true, status: Some("SUCCESS".to_string()), error: None })
        } else {
            // `Err.MEMPOOL_CONFLICT` with an item: parked, answered PENDING -- a success to the RPC.
            self.parked.push(bundle);
            Ok(PushOutcome { accepted: true, status: Some("PENDING".to_string()), error: None })
        }
    }
}

impl ChainClient for SimChain {
    async fn coin_record(&self, coin_id: Bytes32) -> Result<CoinRecordInfo> {
        let inner = self.0.lock().unwrap();
        Ok(match inner.sim.coin_state(coin_id) {
            Some(state) => CoinRecordInfo {
                exists: true,
                confirmed_height: state.created_height,
                spent: state.spent_height.is_some(),
                spent_height: state.spent_height,
                coin: Some(state.coin),
            },
            None => CoinRecordInfo { exists: false, confirmed_height: None, spent: false, spent_height: None, coin: None },
        })
    }

    async fn fee_estimate_mojos(&self, _cost: u64, _target_time_secs: u32) -> Result<u64> {
        match self.0.lock().unwrap().estimate {
            Some(fee) => Ok(fee),
            None => bail!("Upstream request timed out"),
        }
    }

    async fn unspent_coins(&self, master_sk: &SecretKey) -> Result<Vec<(WalletKey, Coin)>> {
        let key = derive_wallet_key(master_sk, 0);
        let coins = self.0.lock().unwrap().sim.unspent_coins(key.puzzle_hash, false);
        Ok(coins.into_iter().map(|coin| (derive_wallet_key(master_sk, 0), coin)).collect())
    }

    async fn coin_in_mempool(&self, coin_id: Bytes32) -> Result<bool> {
        Ok(self.0.lock().unwrap().mempool.iter().any(|item| item.removals.contains(&coin_id)))
    }

    async fn push_tx(&self, bundle: SpendBundle) -> Result<PushOutcome> {
        self.0.lock().unwrap().admit(bundle, true)
    }

    async fn in_mempool(&self, tx_id: Bytes32) -> Result<bool> {
        Ok(self.0.lock().unwrap().mempool.iter().any(|item| item.name == tx_id))
    }

    async fn puzzle_and_solution(&self, coin_id: Bytes32, _height: Option<u32>) -> Result<Option<CoinSpend>> {
        Ok(self.0.lock().unwrap().sim.coin_spend(coin_id))
    }

    async fn peak_height(&self) -> Result<u32> {
        Ok(self.0.lock().unwrap().sim.height())
    }
}

/// The two extra reads the PLAY side needs. Only unspent coins are listed -- the simulator's own
/// per-puzzle-hash scan wants an `indexmap::IndexSet` -- which is what treasury self-heal and the
/// tests ask for; a coin paid out to a player is never spent again in them.
impl PlayChainClient for SimChain {
    async fn coin_records_by_puzzle_hashes(
        &self,
        puzzle_hashes: Vec<Bytes32>,
        start_height: Option<u32>,
    ) -> Result<Vec<PuzzleHashCoinRecord>> {
        let inner = self.0.lock().unwrap();
        let mut records = Vec::new();
        for puzzle_hash in puzzle_hashes {
            for coin in inner.sim.unspent_coins(puzzle_hash, false) {
                let confirmed_height = inner.sim.coin_state(coin.coin_id()).and_then(|state| state.created_height).unwrap_or(0);
                if start_height.is_none_or(|height| confirmed_height >= height) {
                    records.push(PuzzleHashCoinRecord {
                        coin_id: coin.coin_id(),
                        puzzle_hash: coin.puzzle_hash,
                        amount: coin.amount,
                        confirmed_height,
                        spent: false,
                    });
                }
            }
        }
        Ok(records)
    }
}
