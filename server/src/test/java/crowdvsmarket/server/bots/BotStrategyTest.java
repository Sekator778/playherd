package crowdvsmarket.server.bots;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** A bot never asks for more leverage than the market allows (Т-4, Т-8). */
class BotStrategyTest {

    @Test
    void everyStrategyStaysWithinTheMarketCap() {
        Random random = new Random(7);
        for (BotStrategy strategy : BotStrategy.values()) {
            for (int cap : new int[] {2, 3, 5, 10, 25, 40}) {
                for (int i = 0; i < 500; i++) {
                    int leverage = strategy.chooseLeverage(random, cap);
                    assertThat(leverage).as("%s cap %d", strategy, cap).isBetween(2, cap);
                }
            }
        }
    }

    @Test
    void theMaximalistBetsTheCapItself() {
        Random random = new Random(1);

        assertThat(BotStrategy.ALWAYS_MAX_LEVERAGE.chooseLeverage(random, 25)).isEqualTo(25);
        assertThat(BotStrategy.ALWAYS_MAX_LEVERAGE.chooseLeverage(random, 10)).isEqualTo(10);
        assertThat(BotStrategy.ALWAYS_MAX_LEVERAGE.chooseLeverage(random)).isEqualTo(40);
    }

    @Test
    void theCautiousBotKeepsToTwoToFiveOrTheCapIfLower() {
        Random random = new Random(3);
        for (int i = 0; i < 500; i++) {
            assertThat(BotStrategy.CAUTIOUS_LOW_LEVERAGE.chooseLeverage(random, 40)).isBetween(2, 5);
            assertThat(BotStrategy.CAUTIOUS_LOW_LEVERAGE.chooseLeverage(random, 3)).isBetween(2, 3);
        }
    }

    @Test
    void everyStrategyStaysWithinTheLeverageTheStakeAllowsOnEveryMarketAndRoundLength() {
        Random random = new Random(11);
        MarketSpec[] specs = {MarketSpec.BTC, MarketSpec.of("ODDS", 5)};
        for (BotStrategy strategy : BotStrategy.values()) {
            for (MarketSpec spec : specs) {
                for (Interval interval : Interval.values()) {
                    for (int i = 0; i < 300; i++) {
                        BigDecimal stake = strategy.chooseStake(random);
                        int allowed = EconomyRules.maxLeverage(spec, interval, PlayAmount.fromMoney(stake));
                        int leverage = strategy.chooseLeverage(random, allowed);
                        assertThat(leverage).as("%s %s %s stake %s", strategy, spec.id(), interval, stake)
                                .isBetween(2, allowed);
                    }
                }
            }
        }
    }

    @Test
    void theOneArgumentFormIsBtcsRange() {
        Random random = new Random(5);
        boolean sawAbove25 = false;
        for (int i = 0; i < 2000; i++) {
            int leverage = BotStrategy.RANDOM.chooseLeverage(random);
            assertThat(leverage).isBetween(2, 40);
            sawAbove25 |= leverage > 25;
        }
        assertThat(sawAbove25).isTrue();
    }

    @Test
    void theMixPutsMostBetsAtALowLeverageAndAFewAtTheTop() {
        // plan B4: 70% of the draws are bounded by cap/10, 25% by cap/4, 5% by the allowed maximum (the stage-A draw was uniform:
        // 90% of the bets above cap/10)
        Random random = new Random(42);
        int allowed = 600;
        int n = 20_000;
        int upToTenth = 0;
        int upToQuarter = 0;
        int above = 0;
        for (int i = 0; i < n; i++) {
            int leverage = BotStrategy.RANDOM.chooseLeverage(random, allowed);
            assertThat(leverage).isBetween(2, allowed);
            if (leverage <= allowed / 10) {
                upToTenth++;
            } else if (leverage <= allowed / 4) {
                upToQuarter++;
            } else {
                above++;
            }
        }
        // each draw is uniform inside its band, so the bands overlap: the expected shares of the final leverage are about 80% / 16% / 3.8%
        assertThat(upToTenth / (double) n).as("at most cap/10").isBetween(0.78, 0.83);
        assertThat(upToQuarter / (double) n).as("between cap/10 and cap/4").isBetween(0.14, 0.18);
        assertThat(above / (double) n).as("above cap/4").isBetween(0.03, 0.045);
        for (BotStrategy strategy : new BotStrategy[] {BotStrategy.CONTRARIAN, BotStrategy.FOLLOW_MAJORITY}) {
            int low = 0;
            for (int i = 0; i < n; i++) {
                low += strategy.chooseLeverage(random, allowed) <= allowed / 10 ? 1 : 0;
            }
            assertThat(low / (double) n).as(strategy.name()).isGreaterThan(0.68);
        }
    }

    @Test
    void theCautiousAndTheMaximalistKeepTheirNatures() {
        Random random = new Random(9);
        for (int i = 0; i < 500; i++) {
            assertThat(BotStrategy.CAUTIOUS_LOW_LEVERAGE.chooseLeverage(random, 1200)).isBetween(2, 5);
        }
        assertThat(BotStrategy.ALWAYS_MAX_LEVERAGE.chooseLeverage(random, 1200)).isEqualTo(1200);
    }
}
