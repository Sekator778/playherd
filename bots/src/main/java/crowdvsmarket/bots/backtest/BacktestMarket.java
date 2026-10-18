package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A market of the backtest: its id, the terms the game gives it, and its 1-minute candles (oldest first).
 *
 * <p>The terms are the starting values of {@code server/src/main/resources/db/migration/V11__economy_stage_a.sql} (also
 * {@code CURRENT_DEFAULTS} of {@code tools/economy/recompute_caps.py}): the leverage cap of the 3-minute round
 * ({@code cap_hourly}) and of the 15-minute round ({@code cap_daily}) and the herd-fee rate in basis points of notional.
 * The weekly recomputation (economy stage D) may have moved the live values since; a backtest uses these.
 */
public record BacktestMarket(String id, MarketSpec spec, List<BarDto> bars) {

    /** id to {cap of the 3-minute round, cap of the 15-minute round, herd fee in bp}. */
    private static final Map<String, double[]> TERMS = Map.of(
            "BTC", new double[] {1200, 500, 0.92},
            "ETH", new double[] {1000, 400, 1.05},
            "SOL", new double[] {600, 300, 1.25},
            "NVDA", new double[] {1500, 800, 0.75},
            "GOLD", new double[] {2000, 1200, 0.48});

    /** The V11 terms with the Р-102 fee rates (V13) of {@code id}. @throws IllegalArgumentException for a market without known terms */
    public static MarketSpec specOf(String id) {
        double[] t = TERMS.get(id);
        if (t == null) {
            throw new IllegalArgumentException("no known terms for market " + id + " (known: " + TERMS.keySet().stream().sorted().toList() + ")");
        }
        int hourly = (int) t[0];
        int daily = (int) t[1];
        return MarketSpec.of(id, Math.max(hourly, daily))
                .withEconomy(hourly, daily, MarketSpec.DEFAULT_KAPPA, BigDecimal.valueOf(t[2]).movePointLeft(4));
    }

    public static BacktestMarket of(String id, List<BarDto> bars) {
        return new BacktestMarket(id, specOf(id), bars);
    }
}
