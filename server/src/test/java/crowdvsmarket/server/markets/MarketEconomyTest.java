package crowdvsmarket.server.markets;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The starting economy values of the plan (A2, A4) as {@link MarketEconomy#defaultsFor} hands them out. */
class MarketEconomyTest {

    @ParameterizedTest
    @CsvSource({
            "BTC,      1200,  500, 0.92",
            "ETH,      1000,  400, 1.05",
            "SOL,       600,  300, 1.25",
            "DOGE,      600,  300, 1.25",
            "XRP,       600,  300, 1.10",
            "HYPE,      600,  250, 1.15",
            "NVDA,     1500,  800, 0.75",
            "TSLA,     1500, 1000, 0.70",
            "BRENTOIL, 1000,  500, 0.83",
            "GOLD,     2000, 1200, 0.48",
            "XYZ100,   2000, 1500, 0.45",
            "SP500,    4000, 2000, 0.24"})
    void thePlansTwelvePriceMarkets(String symbol, int capHourly, int capDaily, String feeBp) {
        MarketEconomy e = MarketEconomy.defaultsFor(MarketKind.PERP, symbol, 40);

        assertThat(e.capHourly()).as("3 min (HOURLY) cap").isEqualTo(capHourly);
        assertThat(e.capDaily()).as("15 min (DAILY) cap").isEqualTo(capDaily);
        assertThat(e.herdFeeBp()).isEqualByComparingTo(feeBp);
        assertThat(e.kappa()).isEqualByComparingTo("0.1");
    }

    @Test
    void anOddsMarketKeepsItsCapAndItsMaintenanceRate() {
        MarketEconomy e = MarketEconomy.defaultsFor(MarketKind.ODDS, "GER", 10);

        assertThat(e.capHourly()).isEqualTo(10);
        assertThat(e.capDaily()).isEqualTo(10);
        assertThat(e.herdFeeBp()).as("r_m of an odds market, D-102").isEqualByComparingTo("2.5");
        // l = kappa / cap = 0.5 / 10 = 1 / (2 * 10): unchanged from before the economy change
        MarketSpec spec = new Market("PM1", MarketKind.ODDS, "politics", "GER", "Q", null, null, "polymarket", "t", null, 10,
                4, 5, MarketStatus.ACTIVE, 0L, null, null, null).spec();
        assertThat(spec.maintenanceRateFor(Interval.HOURLY)).isEqualByComparingTo(spec.maintenanceMarginRate());
    }

    @Test
    void aPriceMarketTheTableDoesNotNameGetsItsOwnCapAndTheDefaults() {
        MarketEconomy e = MarketEconomy.defaultsFor(MarketKind.PERP, "AAPL", 20);

        assertThat(e.capHourly()).isEqualTo(20);
        assertThat(e.capDaily()).isEqualTo(20);
        assertThat(e.kappa()).isEqualByComparingTo("0.1");
        assertThat(e.herdFeeBp()).isEqualByComparingTo("1.0");
    }

    @Test
    void theMarketSpecCarriesTheEconomyAndTheOriginalCap() {
        Market btc = new Market("BTC", MarketKind.PERP, "crypto", "BTC", "Bitcoin", null, null, "hyperliquid", "BTC", null,
                40, 0, 0, MarketStatus.ACTIVE, 0L, null, null, null);
        MarketSpec spec = btc.spec();

        assertThat(spec.maxLeverage()).as("what bets placed before the change are settled with").isEqualTo(40);
        assertThat(spec.maintenanceMarginRate()).isEqualByComparingTo("0.0125");
        assertThat(spec.capFor(Interval.HOURLY)).isEqualTo(1200);
        assertThat(spec.capFor(Interval.DAILY)).isEqualTo(500);
        assertThat(spec.maintenanceRateFor(Interval.HOURLY)).isEqualByComparingTo(new BigDecimal("0.1").divide(new BigDecimal(1200), java.math.MathContext.DECIMAL128));
        assertThat(spec.herdFeeRate()).isEqualByComparingTo("0.000092");
        assertThat(btc.spec()).as("same as the engine's own BTC constant").isEqualTo(MarketSpec.BTC);
    }

    @Test
    void feesAndTiersFromAStoredMarket() {
        Market sp500 = new Market("xyz_SP500", MarketKind.PERP, "indices", "SP500", "S&P 500", null, null, "hyperliquid",
                "xyz:SP500", null, 40, 2, 9, MarketStatus.ACTIVE, 0L, null, null, null);
        MarketSpec spec = sp500.spec();

        // r_m = 0.24 bp: 4000x on 100 PLAY is 9.6% of the stake, no cap below the 20% guard (D-102)
        assertThat(EconomyRules.fees(spec, Interval.HOURLY, 4000, PlayAmount.ofWholePlay(100)).herd()).isEqualTo(new PlayAmount(9_600));
        // 10x: 100 * 0.000024 * 10 = 0.024
        assertThat(EconomyRules.fees(spec, Interval.HOURLY, 10, PlayAmount.ofWholePlay(100)).herd()).isEqualTo(new PlayAmount(24));
        // the 15-minute round: k = 2.2, 0.024 * 2.2 = 0.0528 -> rounded up to 0.053
        assertThat(EconomyRules.fees(spec, Interval.DAILY, 10, PlayAmount.ofWholePlay(100)).herd()).isEqualTo(new PlayAmount(53));
        assertThat(EconomyRules.maxLeverage(spec, Interval.HOURLY, PlayAmount.ofWholePlay(600))).isEqualTo(800);
    }

    private static PlayAmount herd(Market m, Interval interval, int leverage) {
        return EconomyRules.fees(m.spec(), interval, leverage, PlayAmount.ofWholePlay(1000)).herd();
    }

    private static Market price(String symbol) {
        return new Market(symbol, MarketKind.PERP, "crypto", symbol, symbol, null, null, "hyperliquid", symbol, null, 40, 0, 0,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    @Test
    void theHerdFeeOfAStoredMarketFollowsKAndTheGuard_R102() {
        Market btc = price("BTC");
        Market tsla = price("TSLA");
        Market eth = price("ETH");
        Market sol = price("SOL");
        Market odds = new Market("PM1", MarketKind.ODDS, "politics", "GER", "Q", null, null, "polymarket", "t", null, 10,
                4, 5, MarketStatus.ACTIVE, 0L, null, null, null);

        // stake 1000 PLAY; BTC r_m 0.92 bp
        assertThat(herd(btc, Interval.HOURLY, 240)).isEqualTo(PlayAmount.ofWholePlay(22).plus(new PlayAmount(80)));
        assertThat(herd(btc, Interval.HOURLY, 1200)).isEqualTo(PlayAmount.ofWholePlay(110).plus(new PlayAmount(400)));
        assertThat(herd(btc, Interval.HOURLY, 10)).isEqualTo(new PlayAmount(920));
        assertThat(herd(btc, Interval.DAILY, 10)).isEqualTo(new PlayAmount(2_024));
        // TSLA 15 min, L = 1000: 0.70 bp * 2.2 * 1000 = 15.4%, under the guard
        assertThat(herd(tsla, Interval.DAILY, 1000)).isEqualTo(PlayAmount.ofWholePlay(154));
        assertThat(herd(eth, Interval.HOURLY, 10)).isEqualTo(new PlayAmount(1_050));
        assertThat(herd(sol, Interval.DAILY, 100)).isEqualTo(PlayAmount.ofWholePlay(27).plus(new PlayAmount(500)));
        // an odds market: 2.5 bp, k = 3.0 in the 15-minute round
        assertThat(herd(odds, Interval.HOURLY, 10)).isEqualTo(new PlayAmount(2_500));
        assertThat(herd(odds, Interval.DAILY, 10)).isEqualTo(new PlayAmount(7_500));
        // 20% guard: BTC 15 min, L = 1200 would be 0.92 bp * 2.2 * 1200 = 24.3%
        assertThat(herd(btc, Interval.DAILY, 1200)).isEqualTo(PlayAmount.ofWholePlay(200));
    }
}
