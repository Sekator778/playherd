package crowdvsmarket.engine.economy;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The herd fee {@code stake * min(20%, k * r_m * L)} (Р-102, docs/research/economy_variant_a_rules_2026-10-17.md rules 2a-2g). */
class EconomyRulesFeesTest {

    private static final PlayAmount STAKE = PlayAmount.ofWholePlay(1000);

    private static MarketSpec market(String id, String rateBp, String longFactor) {
        return MarketSpec.of(id, 1500).withEconomy(1500, 1500, MarketSpec.DEFAULT_KAPPA,
                new BigDecimal(rateBp).movePointLeft(4), new BigDecimal(longFactor));
    }

    private static PlayAmount herd(MarketSpec spec, Interval interval, int leverage) {
        return EconomyRules.fees(spec, interval, leverage, STAKE).herd();
    }

    private static PlayAmount play(String amount) {
        return PlayAmount.fromMoney(new BigDecimal(amount));
    }

    @Test
    void theShortRoundHasNoFactorAndNoOnePercentCap() {
        MarketSpec btc = MarketSpec.BTC;
        assertThat(herd(btc, Interval.HOURLY, 240)).isEqualTo(play("22.080"));
        assertThat(herd(btc, Interval.HOURLY, 1200)).isEqualTo(play("110.400"));
        assertThat(herd(btc, Interval.HOURLY, 10)).isEqualTo(play("0.920"));
    }

    @Test
    void theLongRoundMultipliesByK() {
        assertThat(herd(MarketSpec.BTC, Interval.DAILY, 10)).isEqualTo(play("2.024"));
        assertThat(herd(market("TSLA", "0.70", "2.2"), Interval.DAILY, 1000)).isEqualTo(play("154.000"));
        assertThat(herd(market("SOL", "1.25", "2.2"), Interval.DAILY, 100)).isEqualTo(play("27.500"));
        assertThat(herd(market("ETH", "1.05", "2.2"), Interval.HOURLY, 10)).isEqualTo(play("1.050"));
    }

    @Test
    void anOddsMarketPaysTwoAndAHalfBpAndThreeTimesThatInTheLongRound() {
        MarketSpec odds = market("GER", "2.5", "3.0");
        assertThat(herd(odds, Interval.HOURLY, 10)).isEqualTo(play("2.500"));
        assertThat(herd(odds, Interval.DAILY, 10)).isEqualTo(play("7.500"));
    }

    @Test
    void theTwentyPercentGuardClampsAndTheMinimumAndRoundingStay() {
        assertThat(herd(MarketSpec.BTC, Interval.DAILY, 1200)).as("24.3% clamped").isEqualTo(play("200.000"));
        assertThat(EconomyRules.fees(MarketSpec.BTC, Interval.HOURLY, 2, PlayAmount.fromMoney(new BigDecimal("0.001"))).herd())
                .as("minimum 0.001").isEqualTo(EconomyRules.MIN_HERD_FEE);
        assertThat(EconomyRules.fees(market("X", "0.24", "2.2"), Interval.DAILY, 10, PlayAmount.ofWholePlay(100)).herd())
                .as("0.0528 rounded up").isEqualTo(play("0.053"));
        assertThat(EconomyRules.fees(MarketSpec.BTC, Interval.DAILY, 10, STAKE).network()).isEqualTo(EconomyRules.NETWORK_FEE);
    }

    @Test
    void theStakeTiersAreReadableAndAgreeWithTheDivisorTheRulesApply() {
        var tiers = EconomyRules.stakeTiers();
        assertThat(tiers).extracting(EconomyRules.StakeTier::upToPlay).containsExactly(250L, 500L, 1200L, null);
        assertThat(tiers).extracting(EconomyRules.StakeTier::divisor).containsExactly(1, 2, 5, 10);
        for (var tier : tiers) {
            long probe = tier.upToPlay() == null ? 5000 : tier.upToPlay();
            assertThat(EconomyRules.tierDivisor(PlayAmount.ofWholePlay(probe))).isEqualTo(tier.divisor());
        }
    }
}
