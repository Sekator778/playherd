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
            "ETH,      1000,  400, 1.0",
            "SOL,       600,  300, 1.0",
            "DOGE,      600,  300, 1.0",
            "XRP,       600,  300, 1.0",
            "HYPE,      600,  250, 1.0",
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
        assertThat(e.herdFeeBp()).isEqualByComparingTo("1.0");
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

        // r_m = 0.24 bp: 4000x on 100 PLAY would be 9.6% of the stake, capped at 1%
        assertThat(EconomyRules.fees(spec, 4000, PlayAmount.ofWholePlay(100)).herd()).isEqualTo(PlayAmount.ofWholePlay(1));
        // 10x: 100 * 0.000024 * 10 = 0.024
        assertThat(EconomyRules.fees(spec, 10, PlayAmount.ofWholePlay(100)).herd()).isEqualTo(new PlayAmount(24));
        assertThat(EconomyRules.maxLeverage(spec, Interval.HOURLY, PlayAmount.ofWholePlay(600))).isEqualTo(800);
    }
}
