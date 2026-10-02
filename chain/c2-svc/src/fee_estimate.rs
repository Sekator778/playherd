//! The fee of a spend this service is about to broadcast, when `get_fee_estimate` cannot be
//! trusted to answer (`docs/research/chain_service.md` §17).
//!
//! **Incident** (2026-10-02, §17.1): `get_fee_estimate` answered "Upstream request timed out" and
//! the publisher fell back to `fee = 0`. A zero-fee bundle is valid while the node's mempool has
//! room, so the node accepted it -- and it then sat there for about ten minutes in a mempool that
//! was 74 % full and producing full blocks, because every competing transaction paid more.
//! Falling back to *nothing* is exactly wrong at the moment the estimate is unavailable: the
//! chain is probably busy, that is why the call is slow.
//!
//! **The rule** (one place, used by all three callers -- the oracle publisher, the withdrawal
//! publisher and the fee-coin split -- through the `FeeCoinAllocator` they already share):
//!
//! 1. `get_fee_estimate` answered a positive fee -> use it, and remember its *rate* (mojos per
//!    unit of cost) in memory.
//! 2. It failed, or answered 0 -> the fallback: the remembered rate of the last successful
//!    estimate times the cost of this spend; when this process has never had a successful
//!    estimate, the configured floor rate (`C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`) times the
//!    cost.
//! 3. Whatever came out is capped at `C2_MAX_FEE_MOJOS`, like every other fee here.
//!
//! "Answered 0" is treated like a failure on purpose: an idle mempool does answer 0, and a 0 fee is
//! valid then, but the chain can fill up between the estimate and the block, and a bundle with a
//! non-zero fee costs a few thousand mojos of testnet money while a stuck one costs minutes of
//! oracle publication. Setting the floor to 0 gives the old behaviour back for an operator who
//! wants it (and only while no estimate has succeeded yet in this process).
//!
//! The remembered rate lives in memory only. It is a hint for the next estimate failure, not
//! state: a fresh process starts from the floor, exactly like every other per-process cache here.

use std::sync::Mutex;

use anyhow::Result;

use crate::oracle::chain_client::ChainClient;

/// **предложение** -- 100 millimojos = 0.1 mojo per unit of cost. Chosen from the numbers of
/// 2026-10-02 (`docs/research/chain_service.md` §17.2): the estimate that night was 0.0377 mojo/cost
/// (1,131,704 mojos for cost 30,000,000, 60 s target); the 36-record batch that confirmed in about two
/// minutes paid 3,426,863 mojos for a draft cost of 50,601,260 (measured, `tests/oracle_replace.rs`
/// scratch run), i.e. 0.0677; the estimate of 2026-09-25 was 0.0711 (§3.5). 0.1 is a round figure
/// above all three, so a bundle priced at the floor is ahead of what was being paid: 3.6 million
/// mojos for an 18-record batch (draft cost 35,923,484), 5.4 million for the largest, 40 records
/// (53,862,988) -- about a ninth of the default `C2_MAX_FEE_MOJOS` (50,000,000), 0.0000054 TXCH.
pub const DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST: u64 = 100;

/// Where the first attempt's fee came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FeeSource {
    /// `get_fee_estimate` answered a positive fee for this cost.
    Estimate,
    /// The estimate failed or answered 0; the rate of the last successful estimate was used.
    LastEstimateRate,
    /// The estimate failed or answered 0 and none has succeeded in this process: the floor rate.
    FloorRate,
    /// The estimate failed or answered 0, nothing succeeded before and the floor is 0: no fee.
    /// Only an operator who set `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST=0` gets here.
    NoFee,
}

/// What `FeeEstimator::choose` decided.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FeeChoice {
    /// The fee to attach, capped at `C2_MAX_FEE_MOJOS`.
    pub fee_mojos: u64,
    pub source: FeeSource,
    /// The fee before the cap, i.e. what the source asked for.
    pub uncapped_mojos: u64,
}

impl FeeChoice {
    pub fn capped(&self) -> bool {
        self.fee_mojos < self.uncapped_mojos
    }
}

/// Remembers the last successful estimate's rate and applies the fallback rule (module doc).
#[derive(Debug)]
pub struct FeeEstimator {
    floor_rate_millimojos_per_cost: u64,
    last_rate_millimojos_per_cost: Mutex<Option<u64>>,
}

impl Default for FeeEstimator {
    fn default() -> Self {
        Self::new(DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST)
    }
}

/// `ceil(fee * 1000 / cost)`, the rate of an estimate in millimojos per unit of cost.
fn rate_of(fee_mojos: u64, cost: u64) -> Option<u64> {
    if fee_mojos == 0 || cost == 0 {
        return None;
    }
    let numerator = u128::from(fee_mojos) * 1000;
    let rate = numerator.div_ceil(u128::from(cost));
    Some(u64::try_from(rate).unwrap_or(u64::MAX))
}

/// `ceil(rate * cost / 1000)`, the fee of a spend of `cost` at `rate_millimojos` per unit of cost.
fn fee_at_rate(rate_millimojos: u64, cost: u64) -> u64 {
    let fee = (u128::from(rate_millimojos) * u128::from(cost)).div_ceil(1000);
    u64::try_from(fee).unwrap_or(u64::MAX)
}

impl FeeEstimator {
    pub fn new(floor_rate_millimojos_per_cost: u64) -> Self {
        Self { floor_rate_millimojos_per_cost, last_rate_millimojos_per_cost: Mutex::new(None) }
    }

    /// `C2_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST`.
    pub fn floor_rate(&self) -> u64 {
        self.floor_rate_millimojos_per_cost
    }

    /// The rate (millimojos per unit of cost) of the last successful estimate, `None` until one
    /// succeeded in this process.
    pub fn last_rate(&self) -> Option<u64> {
        *self.last_rate_millimojos_per_cost.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    /// The fee `get_fee_estimate` is replaced by when it is not usable, for a spend of `cost`, and
    /// where that number came from.
    pub fn fallback(&self, cost: u64) -> (u64, FeeSource) {
        match self.last_rate() {
            Some(rate) => (fee_at_rate(rate, cost), FeeSource::LastEstimateRate),
            None if self.floor_rate_millimojos_per_cost > 0 => {
                (fee_at_rate(self.floor_rate_millimojos_per_cost, cost), FeeSource::FloorRate)
            }
            None => (0, FeeSource::NoFee),
        }
    }

    /// Applies the whole rule to one answer of `get_fee_estimate` for a spend of `cost`: `answer`
    /// is what the call returned (`Err` = it failed). Pure apart from remembering the rate of a
    /// positive answer, so the rule is unit-tested without a chain.
    pub fn choose(&self, answer: Result<u64>, cost: u64, max_fee_mojos: u64) -> FeeChoice {
        let (uncapped, source) = match answer {
            Ok(fee) if fee > 0 => {
                if let Some(rate) = rate_of(fee, cost) {
                    *self.last_rate_millimojos_per_cost.lock().unwrap_or_else(std::sync::PoisonError::into_inner) =
                        Some(rate);
                }
                (fee, FeeSource::Estimate)
            }
            Ok(_) => {
                let (fee, source) = self.fallback(cost);
                tracing::info!(
                    cost,
                    fee_mojos = fee,
                    ?source,
                    "get_fee_estimate answered 0 -- using the fallback fee instead of broadcasting for nothing"
                );
                (fee, source)
            }
            Err(e) => {
                let (fee, source) = self.fallback(cost);
                tracing::warn!(
                    cost,
                    fee_mojos = fee,
                    ?source,
                    "get_fee_estimate failed, using the fallback fee instead of fee=0: {e:#}"
                );
                (fee, source)
            }
        };
        let fee_mojos = uncapped.min(max_fee_mojos);
        if fee_mojos < uncapped {
            tracing::warn!(
                estimate = uncapped,
                cap = max_fee_mojos,
                "the fee is above C2_MAX_FEE_MOJOS -- capping it"
            );
        }
        FeeChoice { fee_mojos, source, uncapped_mojos: uncapped }
    }

    /// Asks `chain` for the fee of a spend of `cost` that should confirm within
    /// `target_time_secs` and applies the rule (`choose`). Never fails: a failed estimate is the
    /// fallback, not an error.
    pub async fn estimate<C: ChainClient>(
        &self,
        chain: &C,
        cost: u64,
        target_time_secs: u32,
        max_fee_mojos: u64,
    ) -> FeeChoice {
        let answer = chain.fee_estimate_mojos(cost, target_time_secs).await;
        self.choose(answer, cost, max_fee_mojos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::anyhow;

    const CAP: u64 = 50_000_000;

    #[test]
    fn a_positive_estimate_is_used_as_it_is_and_its_rate_is_remembered() {
        let fees = FeeEstimator::default();
        assert_eq!(fees.last_rate(), None);
        let choice = fees.choose(Ok(1_131_704), 30_000_000, CAP);
        assert_eq!(choice, FeeChoice { fee_mojos: 1_131_704, source: FeeSource::Estimate, uncapped_mojos: 1_131_704 });
        // 1,131,704 * 1000 / 30,000,000 = 37.72 -> 38 millimojos per cost, rounded up
        assert_eq!(fees.last_rate(), Some(38));
    }

    #[test]
    fn a_failed_estimate_with_nothing_remembered_uses_the_floor_rate_times_the_cost() {
        let fees = FeeEstimator::default();
        let choice = fees.choose(Err(anyhow!("Upstream request timed out")), 30_000_000, CAP);
        // 0.1 mojo per cost * 30,000,000
        assert_eq!(choice, FeeChoice { fee_mojos: 3_000_000, source: FeeSource::FloorRate, uncapped_mojos: 3_000_000 });
        assert_eq!(fees.last_rate(), None, "a fallback is not an estimate and must not become the remembered rate");
    }

    #[test]
    fn a_failed_estimate_after_a_successful_one_uses_the_remembered_rate() {
        let fees = FeeEstimator::default();
        fees.choose(Ok(1_131_704), 30_000_000, CAP); // remembers 38 millimojos per cost
        let choice = fees.choose(Err(anyhow!("timed out")), 60_000_000, CAP);
        // ceil(38 * 60,000,000 / 1000)
        assert_eq!(choice, FeeChoice { fee_mojos: 2_280_000, source: FeeSource::LastEstimateRate, uncapped_mojos: 2_280_000 });
    }

    #[test]
    fn an_answer_of_zero_is_treated_like_a_failure_and_does_not_overwrite_the_remembered_rate() {
        let fees = FeeEstimator::default();
        assert_eq!(fees.choose(Ok(0), 30_000_000, CAP).source, FeeSource::FloorRate);
        fees.choose(Ok(1_131_704), 30_000_000, CAP);
        let choice = fees.choose(Ok(0), 30_000_000, CAP);
        assert_eq!(choice.source, FeeSource::LastEstimateRate);
        assert_eq!(fees.last_rate(), Some(38));
    }

    #[test]
    fn every_fee_is_capped_at_the_maximum_fee_whichever_source_it_came_from() {
        let fees = FeeEstimator::default();
        let from_estimate = fees.choose(Ok(9_000_000_000), 30_000_000, CAP);
        assert_eq!((from_estimate.fee_mojos, from_estimate.uncapped_mojos), (CAP, 9_000_000_000));
        assert!(from_estimate.capped());

        let from_remembered_rate = fees.choose(Err(anyhow!("down")), 30_000_000, CAP);
        assert_eq!(from_remembered_rate.source, FeeSource::LastEstimateRate);
        assert_eq!(from_remembered_rate.fee_mojos, CAP, "300 millimojos/cost * 30,000,000 = 9,000,000,000 -> capped");

        let from_floor = FeeEstimator::default().choose(Err(anyhow!("down")), 1_000_000_000, 1_234);
        assert_eq!((from_floor.source, from_floor.fee_mojos), (FeeSource::FloorRate, 1_234));
    }

    #[test]
    fn a_floor_of_zero_gives_no_fee_until_an_estimate_succeeds() {
        let fees = FeeEstimator::new(0);
        let choice = fees.choose(Err(anyhow!("down")), 30_000_000, CAP);
        assert_eq!(choice, FeeChoice { fee_mojos: 0, source: FeeSource::NoFee, uncapped_mojos: 0 });
        assert!(!choice.capped());
        fees.choose(Ok(500_000), 10_000_000, CAP);
        assert_eq!(fees.choose(Err(anyhow!("down")), 10_000_000, CAP).fee_mojos, 500_000, "the remembered rate still applies");
    }

    #[test]
    fn rates_round_up_so_a_tiny_estimate_never_becomes_a_zero_fee() {
        let fees = FeeEstimator::default();
        fees.choose(Ok(1), 1_000_000_000, CAP); // 0.000001 mojo per cost -> 1 millimojo, rounded up
        assert_eq!(fees.last_rate(), Some(1));
        let choice = fees.choose(Err(anyhow!("down")), 1_000, CAP);
        assert_eq!(choice.fee_mojos, 1, "ceil(1 * 1000 / 1000)");
        assert_eq!(rate_of(0, 1_000), None);
        assert_eq!(rate_of(5, 0), None);
    }

    #[test]
    fn an_estimate_for_a_zero_cost_is_used_but_not_remembered() {
        let fees = FeeEstimator::default();
        assert_eq!(fees.choose(Ok(7), 0, CAP).fee_mojos, 7);
        assert_eq!(fees.last_rate(), None);
    }

    #[test]
    fn the_default_floor_is_a_tenth_of_a_mojo_per_cost() {
        assert_eq!(DEFAULT_FALLBACK_FEE_RATE_MILLIMOJOS_PER_COST, 100);
        assert_eq!(FeeEstimator::default().floor_rate(), 100);
    }
}
