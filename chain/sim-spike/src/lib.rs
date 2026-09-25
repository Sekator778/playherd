//! sim-spike: week-1 compile spike for the Chia Rust SDK offline simulator.
//!
//! See `docs/research/week1_chain_spike.md` for the full write-up of what was
//! tried, what worked, and what is still open.
//!
//! ## Measuring CLVM cost
//!
//! `chia-sdk-test`'s [`chia_sdk_test::Simulator`] does **not** expose the CLVM
//! cost of a spend or a spend bundle: `Simulator::spend_coins` /
//! `Simulator::new_transaction` only return the resulting `CoinState`s. The
//! cost-computing code path used *inside* `chia-sdk-test` itself
//! (`chia-sdk-test`'s own `validate_clvm_and_signature` / `run_spendbundle`,
//! see `chia-sdk-test-0.36.0/src/validate_clvm_and_signature.rs`) is
//! `pub(crate)`-only inside that crate, so it is not reachable from a
//! downstream crate like this one.
//!
//! To measure cost we therefore use the same algorithm the crate above wraps,
//! but reached from its original, `pub` home: `chia-sdk-test`'s copy is
//! itself a fork of [`chia_consensus::spendbundle_conditions::run_spendbundle`]
//! (kept as a separate copy in `chia-sdk-test` only because that crate needs a
//! custom CLVM dialect for debugging Rue puzzles under WASM, per that
//! function's own doc comment there — not because the real one is
//! unreachable). `chia_consensus::spendbundle_conditions::run_spendbundle` is
//! `pub` and re-exported by the top-level `spendbundle_conditions` module, so
//! [`measure_bundle_cost`] below calls it directly instead of re-porting it:
//! - the CLVM execution cost of a single puzzle/solution pair (used
//!   separately, e.g. to report a single spend's cost in isolation) comes
//!   from [`chia_sdk_types::run_puzzle_with_cost`] (a thin wrapper around
//!   `clvmr::run_program`, which returns `Reduction(cost, output)`);
//! - the full-bundle consensus cost (byte cost + execution cost + per-condition
//!   cost, i.e. exactly what a real mempool/block validator would charge) is
//!   computed by [`measure_bundle_cost`] below, by calling
//!   `chia_consensus::spendbundle_conditions::run_spendbundle` with the same
//!   flags `chia-sdk-test`'s `Simulator::new_transaction` uses
//!   (`ENABLE_KECCAK_OPS_OUTSIDE_GUARD`, nothing else) and the same
//!   `TESTNET11_CONSTANTS`/max cost, so the numbers match what the simulator
//!   itself would have computed and charged. Unlike a line-for-line port,
//!   this also gets `run_spendbundle`'s `tree_hash`/`WrongPuzzleHash` check
//!   (coin.puzzle_hash must match the puzzle reveal's tree hash) for free.

use chia_bls::G2Element;
use chia_consensus::{allocator::make_allocator, spendbundle_conditions::run_spendbundle};
use chia_protocol::{CoinSpend, SpendBundle};
use chia_sdk_types::TESTNET11_CONSTANTS;
use clvmr::{ENABLE_KECCAK_OPS_OUTSIDE_GUARD, LIMIT_HEAP};

/// Same ceiling `chia-sdk-test::Simulator::new_transaction` uses internally
/// (`chia-sdk-test-0.36.0/src/simulator.rs`), so the numbers reported here
/// match what the simulator itself would have allowed.
pub const SIMULATOR_MAX_COST: u64 = 11_000_000_000 / 2;

/// Breakdown of a spend bundle's consensus cost.
#[derive(Debug, Clone, Copy)]
pub struct BundleCost {
    /// `byte_cost + execution_cost + condition_cost` — the number consensus
    /// actually charges (and compares against the block/mempool cost limit).
    pub total_cost: u64,
    /// Cost of the serialized generator bytes (`generator_length * cost_per_byte`).
    pub byte_cost: u64,
    /// Sum of the CLVM execution cost (`clvmr::run_program`'s `Reduction.0`)
    /// of every coin spend's puzzle run.
    pub execution_cost: u64,
    /// `total_cost - byte_cost - execution_cost`: the fixed per-condition
    /// surcharge consensus adds on top of raw CLVM execution
    /// (e.g. `CREATE_COIN`, `AGG_SIG_*`, announcements, ...).
    pub condition_cost: u64,
}

/// Computes the exact consensus cost of a spend bundle (byte + execution +
/// condition cost), using testnet11 constants, the same way
/// `chia-sdk-test`'s `Simulator` validates a bundle before accepting it.
///
/// This does not run signature validation (the returned pkm pairs from
/// `run_spendbundle` are discarded; `chia-sdk-test`'s `Simulator` validates
/// the real signature separately) and does not mutate any simulator state;
/// it only re-runs the puzzles in a scratch [`clvmr::Allocator`] to measure
/// cost. See the module docs for why this exists.
pub fn measure_bundle_cost(coin_spends: &[CoinSpend]) -> anyhow::Result<BundleCost> {
    let mut a = make_allocator(LIMIT_HEAP);
    let constants = &TESTNET11_CONSTANTS;
    let flags = ENABLE_KECCAK_OPS_OUTSIDE_GUARD;
    let max_cost = SIMULATOR_MAX_COST;

    // The aggregated signature is never read by `run_spendbundle` (it only
    // returns the (pubkey, message) pairs a caller would need to verify one
    // separately), so a placeholder is fine here.
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
