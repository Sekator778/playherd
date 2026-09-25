//! CLVM/consensus cost measurement.
//!
//! Ported from `chain/sim-spike/src/lib.rs::measure_bundle_cost` (see that
//! file's module doc comment for the full explanation of why the simulator
//! itself doesn't expose this and what public API this uses instead). Kept
//! as its own copy here instead of a dependency on `sim-spike` because that
//! crate is a throwaway "week-1 compile spike", not a shared library --
//! `c2-cli` is meant to stand on its own.

use chia_bls::G2Element;
use chia_consensus::{allocator::make_allocator, spendbundle_conditions::run_spendbundle};
use chia_protocol::{CoinSpend, SpendBundle};
use chia_sdk_types::TESTNET11_CONSTANTS;
use clvmr::{ENABLE_KECCAK_OPS_OUTSIDE_GUARD, LIMIT_HEAP};

/// Same ceiling `chia-sdk-test::Simulator::new_transaction` uses internally
/// (`chia-sdk-test-0.36.0/src/simulator.rs`).
pub const SIMULATOR_MAX_COST: u64 = 11_000_000_000 / 2;

/// Breakdown of a spend bundle's consensus cost.
#[derive(Debug, Clone, Copy, serde::Serialize)]
pub struct BundleCost {
    /// `byte_cost + execution_cost + condition_cost` -- the number consensus
    /// actually charges (and compares against the block/mempool cost limit).
    pub total_cost: u64,
    pub byte_cost: u64,
    pub execution_cost: u64,
    pub condition_cost: u64,
}

/// Computes the exact consensus cost of a spend bundle (byte + execution +
/// condition cost), using testnet11 constants, the same way
/// `chia-sdk-test`'s `Simulator` validates a bundle before accepting it.
///
/// Does not run signature validation and does not require the real
/// aggregated signature (a placeholder is used) -- this only re-runs the
/// puzzles in a scratch allocator to measure cost, it never mutates any
/// state.
pub fn measure_bundle_cost(coin_spends: &[CoinSpend]) -> anyhow::Result<BundleCost> {
    let mut a = make_allocator(LIMIT_HEAP);
    let constants = &TESTNET11_CONSTANTS;
    let flags = ENABLE_KECCAK_OPS_OUTSIDE_GUARD;
    let max_cost = SIMULATOR_MAX_COST;

    let spend_bundle = SpendBundle::new(coin_spends.to_vec(), G2Element::default());

    let (conditions, _pkm_pairs) =
        run_spendbundle(&mut a, &spend_bundle, max_cost, flags, constants)?;

    let total_cost = conditions.cost;
    let execution_cost = conditions.execution_cost;
    let condition_cost = conditions.condition_cost;
    let byte_cost = total_cost - execution_cost - condition_cost;

    Ok(BundleCost {
        total_cost,
        byte_cost,
        execution_cost,
        condition_cost,
    })
}
