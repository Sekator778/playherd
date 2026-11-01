//! `oracle-launch` / `oracle-publish`: the minimal oracle singleton of
//! decision D-11 ("owner key signature + recreating itself +
//! announcement (price, time, number)"), exactly the pattern proven in
//! `chain/sim-spike/tests/oracle_singleton.rs`. No action layer, no
//! `RewardDistributorType::Managed` -- a plain singleton whose inner puzzle
//! is a standard p2 controlled by the wallet's own (synthetic) key.

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Bytes, Bytes32, Coin, CoinSpend};
use chia_puzzle_types::{
    EveProof, Memos, Proof,
    singleton::{SingletonArgs, SingletonSolution},
};
use chia_sdk_driver::{Launcher, Layer, SingletonLayer, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_types::{Conditions, run_puzzle_with_cost};

use crate::cost::SIMULATOR_MAX_COST;
use crate::keys::WalletKey;
use crate::sign::sign_transaction;

/// Fixed amount every Chia singleton coin (launcher, eve, and every
/// recreated generation) carries -- singletons are identity-tracking, not
/// value-tracking, coins.
pub const SINGLETON_AMOUNT: u64 = 1;

pub struct OracleLaunchResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub launcher_id: Bytes32,
    pub singleton_puzzle_hash: Bytes32,
    pub eve_coin: Coin,
    pub eve_proof: Proof,
    pub change_amount: u64,
}

/// Launches a fresh oracle singleton, funded by `funding_coin` (>=
/// `SINGLETON_AMOUNT + fee`), with `manager`'s own synthetic key as the sole
/// authority over it. Any of `funding_coin` left over after
/// `SINGLETON_AMOUNT` and `fee` comes back to `manager` as change --
/// `funding_coin` is picked from the wallet's UTXOs by "amount at least
/// this much" (see `chain.rs::find_funding_coin`), so it is almost never
/// exactly `SINGLETON_AMOUNT + fee`; without this, every excess mojo would
/// silently become an oversized, unintended transaction fee instead (Chia
/// consensus treats any spent-but-not-recreated value as fee, with no
/// error).
pub fn build_oracle_launch(
    funding_coin: Coin,
    manager: &WalletKey,
    fee: u64,
) -> Result<OracleLaunchResult> {
    let Some(change_amount) = funding_coin
        .amount
        .checked_sub(SINGLETON_AMOUNT)
        .and_then(|v| v.checked_sub(fee))
    else {
        bail!(
            "funding coin has {} mojos, need at least {} (SINGLETON_AMOUNT) + {fee} (fee)",
            funding_coin.amount,
            SINGLETON_AMOUNT
        );
    };

    let mut ctx = SpendContext::new();
    let manager_p2 = StandardLayer::new(manager.synthetic_pk);
    let inner_puzzle_hash: Bytes32 = manager.puzzle_hash;

    let launcher = Launcher::new(funding_coin.coin_id(), SINGLETON_AMOUNT);
    let launcher_id = launcher.coin().coin_id();
    let (create_singleton, eve_coin) = launcher.spend(&mut ctx, inner_puzzle_hash, ())?;

    let mut outer_conditions = create_singleton;
    if change_amount > 0 {
        outer_conditions =
            outer_conditions.create_coin(manager.puzzle_hash, change_amount, chia_puzzle_types::Memos::None);
    }
    if fee > 0 {
        outer_conditions = outer_conditions.reserve_fee(fee);
    }
    manager_p2.spend(&mut ctx, funding_coin, outer_conditions)?;

    let singleton_puzzle_hash: Bytes32 =
        SingletonArgs::curry_tree_hash(launcher_id, inner_puzzle_hash.into()).into();

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[manager.synthetic_sk.clone()])?;

    // The eve coin's parent is the launcher coin; the launcher coin's own
    // parent is `funding_coin` and its amount is always `SINGLETON_AMOUNT`
    // (not `funding_coin.amount`) -- see
    // `chia-puzzle-types-0.36.1/src/proof.rs::EveProof`.
    let eve_proof = Proof::Eve(EveProof {
        parent_parent_coin_info: funding_coin.coin_id(),
        parent_amount: SINGLETON_AMOUNT,
    });

    Ok(OracleLaunchResult {
        coin_spends,
        signature,
        launcher_id,
        singleton_puzzle_hash,
        eve_coin,
        eve_proof,
        change_amount,
    })
}

pub struct OraclePublishResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub next_coin: Coin,
    pub next_proof: Proof,
    pub announcement_message: Bytes,
    /// CLVM execution cost of just the singleton spend (matches
    /// `sim-spike`'s `oracle_singleton.rs` per-round reporting).
    pub singleton_execution_cost: u64,
}

/// Spends the oracle singleton one round forward: recreates itself (same
/// inner puzzle / manager key) and emits a `CREATE_PUZZLE_ANNOUNCEMENT`
/// whose message is the CLVM serialization of `(price timestamp sequence)`.
pub fn build_oracle_publish(
    launcher_id: Bytes32,
    current_coin: Coin,
    current_proof: Proof,
    manager: &WalletKey,
    price: i64,
    timestamp: i64,
    sequence: u64,
) -> Result<OraclePublishResult> {
    let mut ctx = SpendContext::new();
    let manager_p2 = StandardLayer::new(manager.synthetic_pk);
    let inner_puzzle_hash: Bytes32 = manager.puzzle_hash;

    let message_ptr = ctx.alloc(&vec![price, timestamp, i64::try_from(sequence)?])?;
    let message: Bytes = ctx.serialize(&message_ptr)?.into_inner();

    let inner_solution = manager_p2
        .spend_with_conditions(
            &mut ctx,
            Conditions::new()
                .create_coin(inner_puzzle_hash, SINGLETON_AMOUNT, Memos::None)
                .create_puzzle_announcement(message.clone()),
        )?
        .solution;

    let inner_puzzle_ptr = manager_p2.construct_puzzle(&mut ctx)?;
    let singleton_spend = SingletonLayer::new(launcher_id, inner_puzzle_ptr).construct_spend(
        &mut ctx,
        SingletonSolution {
            lineage_proof: current_proof,
            amount: current_coin.amount,
            inner_solution,
        },
    )?;

    let reduction = run_puzzle_with_cost(
        &mut ctx,
        singleton_spend.puzzle,
        singleton_spend.solution,
        SIMULATOR_MAX_COST,
        false,
    )?;

    let puzzle_reveal = ctx.serialize(&singleton_spend.puzzle)?;
    let solution = ctx.serialize(&singleton_spend.solution)?;
    ctx.insert(CoinSpend::new(current_coin, puzzle_reveal, solution));

    let coin_spends = ctx.take();
    let signature = sign_transaction(&coin_spends, &[manager.synthetic_sk.clone()])?;

    // The singleton's full puzzle hash never changes across spends (same
    // manager key every round), so the next coin reuses `current_coin`'s.
    let next_coin = Coin::new(current_coin.coin_id(), current_coin.puzzle_hash, SINGLETON_AMOUNT);
    let next_proof = Proof::Lineage(SingletonLayer::new(launcher_id, manager_p2).lineage_proof(current_coin));

    Ok(OraclePublishResult {
        coin_spends,
        signature,
        next_coin,
        next_proof,
        announcement_message: message,
        singleton_execution_cost: reduction.0,
    })
}
