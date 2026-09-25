//! Extends `c2_cli::builders::oracle`'s minimal oracle singleton (decision
//! Р-11: one manager-key-controlled singleton, self-recreating, no action
//! layer) so **one spend carries up to 20 records**, each as its own
//! `CREATE_PUZZLE_ANNOUNCEMENT` (task instruction). `chain/c2-cli` itself is
//! left unmodified (`docs/research/chain_service.md` §1) -- this is new
//! code, not a copy of `c2_cli::builders::oracle::build_oracle_publish`,
//! but it follows the exact same puzzle-composition pattern (proven in
//! `chain/sim-spike/tests/oracle_singleton.rs` and `c2-cli`'s own version):
//! recreate the singleton at its fixed `SINGLETON_AMOUNT`, then announce.
//!
//! Announcement message per record (task instruction, exact order):
//! `(seq ts priceCents resultsHash sourceFlag sourceTs)`, a CLVM proper list
//! (`clvm_list!`), `sourceFlag` 0=live 1=replay.

use anyhow::{Result, bail};
use chia_bls::Signature;
use chia_protocol::{Bytes, Bytes32, Coin, CoinSpend};
use chia_puzzle_types::{
    Memos, Proof,
    singleton::{SingletonArgs, SingletonSolution},
};
use chia_sdk_driver::{Layer, SingletonLayer, SpendContext, SpendWithConditions, StandardLayer};
use chia_sdk_types::{Conditions, run_puzzle_with_cost};
use clvm_traits::clvm_list;

use c2_cli::builders::oracle::SINGLETON_AMOUNT;
use c2_cli::cost::SIMULATOR_MAX_COST;
use c2_cli::keys::WalletKey;
use c2_cli::sign::sign_transaction;

use super::types::OracleRecord;

/// Task instruction: "several pending records go into ONE singleton spend
/// (max 20)".
pub const MAX_RECORDS_PER_SPEND: usize = 20;

pub struct OraclePublishBatchResult {
    pub coin_spends: Vec<CoinSpend>,
    pub signature: Signature,
    pub next_coin: Coin,
    pub next_proof: Proof,
    /// One announcement message per record, same order as `records` --
    /// tests (and a future consumer contract) can assert
    /// `announcement_id(singleton_puzzle_hash, &messages[i])` per record.
    pub announcement_messages: Vec<Bytes>,
    /// CLVM execution cost of just the singleton spend (matches
    /// `c2-cli`'s and `sim-spike`'s per-round reporting convention).
    pub singleton_execution_cost: u64,
}

/// Spends the oracle singleton one generation forward: recreates itself
/// (same inner puzzle / manager key) and emits one
/// `CREATE_PUZZLE_ANNOUNCEMENT` per record in `records` (1..=20 of them).
pub fn build_oracle_publish_batch(
    launcher_id: Bytes32,
    current_coin: Coin,
    current_proof: Proof,
    manager: &WalletKey,
    records: &[OracleRecord],
) -> Result<OraclePublishBatchResult> {
    if records.is_empty() {
        bail!("build_oracle_publish_batch requires at least one record");
    }
    if records.len() > MAX_RECORDS_PER_SPEND {
        bail!(
            "build_oracle_publish_batch got {} records, max {MAX_RECORDS_PER_SPEND} per spend",
            records.len()
        );
    }

    let mut ctx = SpendContext::new();
    let manager_p2 = StandardLayer::new(manager.synthetic_pk);
    let inner_puzzle_hash: Bytes32 = manager.puzzle_hash;

    let mut conditions =
        Conditions::new().create_coin(inner_puzzle_hash, SINGLETON_AMOUNT, Memos::None);
    let mut announcement_messages = Vec::with_capacity(records.len());
    for record in records {
        let seq = i64::try_from(record.seq)?;
        let message_ptr = ctx.alloc(&clvm_list!(
            seq,
            record.ts,
            record.price_cents,
            record.results_hash,
            record.source_flag,
            record.source_ts
        ))?;
        let message: Bytes = ctx.serialize(&message_ptr)?.into_inner();
        conditions = conditions.create_puzzle_announcement(message.clone());
        announcement_messages.push(message);
    }

    let inner_solution = manager_p2.spend_with_conditions(&mut ctx, conditions)?.solution;

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

    // Same reasoning as `c2_cli::builders::oracle::build_oracle_publish`:
    // the singleton's full puzzle hash never changes across spends (same
    // manager key every generation), so the next coin reuses
    // `current_coin`'s puzzle hash.
    let next_coin = Coin::new(current_coin.coin_id(), current_coin.puzzle_hash, SINGLETON_AMOUNT);
    let next_proof =
        Proof::Lineage(SingletonLayer::new(launcher_id, manager_p2).lineage_proof(current_coin));

    Ok(OraclePublishBatchResult {
        coin_spends,
        signature,
        next_coin,
        next_proof,
        announcement_messages,
        singleton_execution_cost: reduction.0,
    })
}

/// The singleton's full puzzle hash (needed to compute
/// `announcement_id(singleton_puzzle_hash, message)` for a consumer
/// contract, or for tests) -- same formula as
/// `c2_cli::builders::oracle::build_oracle_launch`.
pub fn singleton_puzzle_hash(launcher_id: Bytes32, manager: &WalletKey) -> Bytes32 {
    SingletonArgs::curry_tree_hash(launcher_id, manager.puzzle_hash.into()).into()
}
