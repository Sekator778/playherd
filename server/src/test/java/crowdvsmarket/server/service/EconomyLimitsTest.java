package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.markets.MarketEconomy;
import crowdvsmarket.server.markets.MarketKind;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The treasury limits of economy stage B (plan B5, B6) as pure functions, with the numbers of docs/research/game_economy_math.md section 4.2:
 * on the treasury of 99 998 100 PLAY the net notional of BTC's 3-minute round may be 30.6 million at the stress move of 81.7 bp.
 */
class EconomyLimitsTest {

    private static final PlayAmount TREASURY = PlayAmount.ofWholePlay(99_998_100);

    @Test
    void theMarketLimitIsAQuarterOfAPercentOfTheTreasuryOverTheStressMove() {
        BigDecimal btc = EconomyLimits.marketLimit(TREASURY, new BigDecimal("0.00817"));

        assertThat(btc).as("30.6 million of notional, as in game_economy_math section 4.2").isBetween(
                new BigDecimal("30500000"), new BigDecimal("30700000"));
        assertThat(EconomyLimits.marketLimit(TREASURY, new BigDecimal("0.00161"))).as("SP500: 155.6 million")
                .isBetween(new BigDecimal("155000000"), new BigDecimal("156000000"));
        assertThat(EconomyLimits.marketLimit(PlayAmount.ofWholePlay(1_000_000), new BigDecimal("0.01")))
                .isEqualByComparingTo("250000");
    }

    @Test
    void theCryptoBucketIsOnePercentOfTheTreasury() {
        assertThat(EconomyLimits.cryptoBucketLimit(PlayAmount.ofWholePlay(1_000_000))).isEqualByComparingTo("10000");
    }

    @Test
    void aBetThatFitsPassesAndOneOverTheLimitIsRefusedWithTheRoomThatIsLeft() {
        PlayAmount treasury = PlayAmount.ofWholePlay(1_000_000);
        BigDecimal stress = new BigDecimal("0.01"); // limit 250 000

        EconomyLimits.Exposure fits = EconomyLimits.checkExposure(treasury, stress, new BigDecimal("100000"), new BigDecimal("150000"), null);
        EconomyLimits.Exposure over = EconomyLimits.checkExposure(treasury, stress, new BigDecimal("100000"), new BigDecimal("150001"), null);

        assertThat(fits.ok()).isTrue();
        assertThat(over.ok()).isFalse();
        assertThat(over.roomNotional()).isEqualByComparingTo("150000");
        assertThat(over.limitNotional()).isEqualByComparingTo("250000");
        assertThat(over.bucketLimited()).isFalse();
    }

    @Test
    void noRoomIsNeverNegative() {
        EconomyLimits.Exposure e = EconomyLimits.checkExposure(PlayAmount.ofWholePlay(1_000_000), new BigDecimal("0.01"),
                new BigDecimal("900000"), new BigDecimal("1"), null);

        assertThat(e.ok()).isFalse();
        assertThat(e.roomNotional()).isEqualByComparingTo("0");
    }

    @Test
    void theBucketLimitsWhenItHasLessRoomThanTheMarket() {
        PlayAmount treasury = PlayAmount.ofWholePlay(1_000_000);
        BigDecimal stress = new BigDecimal("0.01"); // market room 250 000 notional, bucket 10 000 of risk
        // the bucket already carries 9 500 of risk: 500 / 0.01 = 50 000 of notional left
        EconomyLimits.Exposure e = EconomyLimits.checkExposure(treasury, stress, BigDecimal.ZERO, new BigDecimal("60000"), new BigDecimal("9500"));

        assertThat(e.ok()).isFalse();
        assertThat(e.bucketLimited()).isTrue();
        assertThat(e.roomNotional()).isEqualByComparingTo("50000");
        assertThat(EconomyLimits.checkExposure(treasury, stress, BigDecimal.ZERO, new BigDecimal("50000"), new BigDecimal("9500")).ok())
                .isTrue();
    }

    @Test
    void theBreakerTakesAQuarterOfTheCapButNeverLessThanTheMinimumLeverage() {
        assertThat(EconomyLimits.breakerMaxLeverage(1200)).isEqualTo(300);
        assertThat(EconomyLimits.breakerMaxLeverage(500)).isEqualTo(125);
        assertThat(EconomyLimits.breakerMaxLeverage(5)).isEqualTo(2);
    }

    @Test
    void theStressMovesAreThoseOfTheResearchAndTheLongRoundScalesThem() {
        MarketEconomy btc = MarketEconomy.defaultsFor(MarketKind.PERP, "BTC", 40);
        MarketEconomy sp500 = MarketEconomy.defaultsFor(MarketKind.PERP, "SP500", 40);
        MarketEconomy unknown = MarketEconomy.defaultsFor(MarketKind.PERP, "NEWCOIN", 40);
        MarketEconomy odds = MarketEconomy.defaultsFor(MarketKind.ODDS, "ELECTION", 5);

        assertThat(btc.stressMove()).isEqualByComparingTo("0.00817");
        assertThat(sp500.stressMove()).isEqualByComparingTo("0.00161");
        assertThat(unknown.stressMove()).as("the largest of the twelve").isEqualByComparingTo("0.016");
        assertThat(odds.stressMove()).isEqualByComparingTo("0.20");
        assertThat(btc.stressFor(Interval.HOURLY, MarketKind.PERP)).isEqualByComparingTo("0.00817");
        assertThat(btc.stressFor(Interval.DAILY, MarketKind.PERP)).as("2.2 x for the 15-minute round")
                .isEqualByComparingTo("0.017974");
        MarketEconomy unset = new MarketEconomy(10, 10, new BigDecimal("0.1"), new BigDecimal("1.0"), null);
        assertThat(unset.stressFor(Interval.HOURLY, MarketKind.PERP)).isEqualByComparingTo("0.016");
        assertThat(unset.stressFor(Interval.HOURLY, MarketKind.ODDS)).isEqualByComparingTo("0.20");
    }
}
