//! The rule by which a node's mempool accepts a spend bundle in place of one it already holds
//! (`docs/research/chain_service.md` §17.3), written down once so the publisher builds a
//! replacement that satisfies it instead of one the node will refuse.
//!
//! **Source**: `chia-blockchain`, file `chia/full_node/mempool_manager.py`, tag `2.7.4` (the
//! latest release on 2026-10-02, published 2026-09-10; commit
//! `98aba3d176b24b5814e84a33f9d1c11af67a9cdc`): `can_replace()` (lines 1138-1231),
//! `MEMPOOL_MIN_FEE_INCREASE` (line 51), the conflict detection in `check_removals()` (lines
//! 228-306), the order of checks in `validate_spend_bundle()` (lines 780-880), the handling of the
//! result in `add_spend_bundle()` (lines 569-640) and the retry of parked bundles in
//! `new_peak()` (lines 915-1115); the `push_tx` RPC is `chia/full_node/full_node_rpc_api.py`,
//! lines 827-855, same tag. Which version the testnet11 node behind coinset runs is
//! **UNVERIFIED**: only 2.7.4 was read for this module.
//!
//! A new bundle `N` that spends a coin some mempool item already spends is a *conflict*; the node
//! replaces the conflicting items (`C`, all of them together) with `N` only if **all** of these
//! hold. Otherwise it keeps `C` and parks `N` in a conflict cache -- `N` is *not* in the mempool
//! -- and `push_tx` still answers with success: `{"status": "PENDING"}`, not an error (the RPC
//! raises only for `FAILED`). So an accepted push is not proof of a replacement: the status must
//! be `SUCCESS`. The cache is retried at every new transaction block (`new_peak()`), where `N`
//! either replaces `C` after all, stays parked, or is dropped once a coin it spends is spent:
//!
//! 1. **Superset**: every coin spent by every item of `C` is also spent by `N`. A replacement may
//!    add coins, never drop one -- so a bundle that had no fee coin may gain one, and a bundle that
//!    had a fee coin must keep spending that very coin.
//! 2. **Fee per cost strictly higher**: `N.fee / N.cost > C.fee / C.cost` (sums over `C`).
//! 3. **Fee increase of at least `MEMPOOL_MIN_FEE_INCREASE`**: `N.fee - C.fee >= 10,000,000`
//!    mojos (0.00001 XCH) -- three to nine times what the batches of 2026-10-02 paid (1.1 to 3.4
//!    million mojos).
//! 4. **Same time locks**: `N`'s effective `ASSERT_HEIGHT`, `ASSERT_BEFORE_HEIGHT` and
//!    `ASSERT_BEFORE_SECONDS` equal those of `C` (ours have none, on both sides).
//! 5. **Fast-forward / dedup eligibility not removed**: a spend that is eligible for fast
//!    forward or dedup may not be replaced by one that is not. Neither of our spends is eligible
//!    -- both have an `AGG_SIG_ME` (the standard puzzle's), which clears both flags
//!    (`chia-consensus-0.36.1/src/conditions.rs`, `MempoolVisitor::condition`) -- so this
//!    never applies and a plain conflict is what the node sees.
//!
//! Before any of that, `validate_spend_bundle` checks that `N` is a valid bundle by itself and,
//! when the mempool is at full capacity, that its fee per cost is at least 5 mojos per unit
//! (`nonzero_fee_minimum_fpc`, `INVALID_FEE_TOO_CLOSE_TO_ZERO`) and above the mempool's minimum
//! rate (`INVALID_FEE_LOW_FEE`): a replacement into a full mempool needs far more than rules 2-3
//! ask -- more than `C2_MAX_FEE_MOJOS` allows, in which case the node refuses and the publisher
//! keeps the old bundle and says so (`Publisher::replace_in_mempool`).

/// `MEMPOOL_MIN_FEE_INCREASE` of `mempool_manager.py` (tag 2.7.4, line 51): a replacement must pay
/// at least this many mojos more than the items it replaces.
pub const MEMPOOL_MIN_FEE_INCREASE: u64 = 10_000_000;

/// The least fee a replacement bundle of cost `new_cost` may pay to replace an item that pays
/// `old_fee` at cost `old_cost`: rules 2 and 3 above, the larger of the two. The fee-per-cost rule
/// is solved exactly in integers (`new_fee * old_cost > old_fee * new_cost`) and given one extra
/// mojo of slack, because the node compares two floating-point quotients.
pub fn required_replacement_fee(old_fee: u64, old_cost: u64, new_cost: u64) -> u64 {
    let by_increase = old_fee.saturating_add(MEMPOOL_MIN_FEE_INCREASE);
    let by_rate = u128::from(old_fee) * u128::from(new_cost) / u128::from(old_cost.max(1)) + 2;
    by_increase.max(u64::try_from(by_rate).unwrap_or(u64::MAX))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_bundle_without_a_fee_needs_exactly_the_minimum_increase() {
        // 0 mojos at any cost: the fee-per-cost rule asks for "more than 0" (2 mojos with the
        // slack), the increase rule for 10,000,000.
        assert_eq!(required_replacement_fee(0, 40_000_000, 52_000_000), 10_000_000);
    }

    #[test]
    fn the_increase_rule_dominates_while_fees_are_small() {
        assert_eq!(required_replacement_fee(3_426_863, 58_000_000, 60_000_000), 13_426_863);
    }

    #[test]
    fn the_rate_rule_dominates_when_the_replacement_is_much_bigger_than_a_pricey_original() {
        // 30,000,000 mojos at cost 30,000,000 is 1 mojo per cost; a replacement of cost 60,000,000
        // needs more than 60,000,000 mojos, far above old + 10,000,000.
        let required = required_replacement_fee(30_000_000, 30_000_000, 60_000_000);
        assert_eq!(required, 60_000_002);
        assert!(u128::from(required) * 30_000_000 > u128::from(30_000_000u64) * 60_000_000);
    }

    #[test]
    fn what_it_returns_always_satisfies_both_rules_of_the_node() {
        for (old_fee, old_cost, new_cost) in [
            (0u64, 1u64, 1u64),
            (1, 1_000_000, 1_000_000),
            (7_777_777, 41_000_000, 53_000_000),
            (49_999_999, 61_174_188, 61_174_188),
            (123_456_789, 20_000_000, 90_000_000),
        ] {
            let required = required_replacement_fee(old_fee, old_cost, new_cost);
            assert!(required - old_fee >= MEMPOOL_MIN_FEE_INCREASE, "increase for {old_fee}/{old_cost}/{new_cost}");
            assert!(
                u128::from(required) * u128::from(old_cost) > u128::from(old_fee) * u128::from(new_cost),
                "fee per cost for {old_fee}/{old_cost}/{new_cost}"
            );
            // the node divides floats: the quotients must also differ as floats
            assert!(required as f64 / new_cost as f64 > old_fee as f64 / old_cost as f64);
        }
    }

    #[test]
    fn it_saturates_instead_of_overflowing() {
        assert_eq!(required_replacement_fee(u64::MAX, 1, u64::MAX), u64::MAX);
    }
}
