package crowdvsmarket.server.markets;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * The stored economy parameters of a market (economy stage A, Р-92, docs/product/economy_implementation_plan.md A1-A4; the
 * columns {@code cap_hourly}, {@code cap_daily}, {@code kappa}, {@code herd_fee_bp}, {@code stress_move} of {@code markets},
 * V11):
 *
 * <ul>
 *   <li>{@code capHourly} / {@code capDaily}: the leverage cap of the two round lengths. The names are the engine's
 *       {@code Interval}s: {@code HOURLY} is the short round (3 minutes in this server's configuration,
 *       {@code herd.round-durations.hourly-sec}), {@code DAILY} the long one (15 minutes, {@code daily-sec}).
 *   <li>{@code kappa}: the maintenance constant, {@code l = κ / cap}; 0.1 for a price market, 0.5 for an odds market (which
 *       keeps {@code l = 1/(2 cap)} as before the change).
 *   <li>{@code herdFeeBp}: {@code r_m}, the herd-fee rate in basis points of notional per unit of leverage.
 *   <li>{@code stressMove}: the 99.9th-percentile move of a round, a fraction of price; for the treasury limits of stage B.
 *       {@code null} until the weekly recalculation (stage D) fills it.
 * </ul>
 */
public record MarketEconomy(int capHourly, int capDaily, BigDecimal kappa, BigDecimal herdFeeBp, BigDecimal stressMove) {

    public static final BigDecimal PRICE_KAPPA = new BigDecimal("0.1");
    public static final BigDecimal ODDS_KAPPA = new BigDecimal("0.5");
    /** {@code r_m} of every market the plan has no figure for, and of the odds markets (plan A4). */
    public static final BigDecimal DEFAULT_FEE_BP = new BigDecimal("1.0");

    /** Plan A2/A4 starting values by symbol: short-round cap, long-round cap, {@code r_m} in bp. Kept equal to V11. */
    private record Row(int capHourly, int capDaily, String feeBp) {
    }

    private static final Map<String, Row> PRICE_MARKETS = Map.ofEntries(
            Map.entry("BTC", new Row(1200, 500, "0.92")),
            Map.entry("ETH", new Row(1000, 400, "1.0")),
            Map.entry("SOL", new Row(600, 300, "1.0")),
            Map.entry("DOGE", new Row(600, 300, "1.0")),
            Map.entry("XRP", new Row(600, 300, "1.0")),
            Map.entry("HYPE", new Row(600, 250, "1.0")),
            Map.entry("NVDA", new Row(1500, 800, "0.75")),
            Map.entry("TSLA", new Row(1500, 1000, "0.70")),
            Map.entry("BRENTOIL", new Row(1000, 500, "0.83")),
            Map.entry("GOLD", new Row(2000, 1200, "0.48")),
            Map.entry("XYZ100", new Row(2000, 1500, "0.45")),
            Map.entry("SP500", new Row(4000, 2000, "0.24")));

    public MarketEconomy {
        // 0.1000 from the database is the same number as 0.1 in the code: one spelling, so records and specs compare equal
        kappa = Objects.requireNonNull(kappa, "kappa").stripTrailingZeros();
        herdFeeBp = Objects.requireNonNull(herdFeeBp, "herdFeeBp").stripTrailingZeros();
    }

    /**
     * What a market gets when it is listed: the plan's starting values for the twelve price markets (by symbol), and for
     * everything else — odds markets and price markets the plan does not name — the market's own single cap for both
     * round lengths, {@code r_m = 1.0 bp}, and {@code κ} 0.5 (odds) or 0.1 (price).
     */
    public static MarketEconomy defaultsFor(MarketKind kind, String symbol, int maxLeverage) {
        if (kind == MarketKind.ODDS) {
            return new MarketEconomy(maxLeverage, maxLeverage, ODDS_KAPPA, DEFAULT_FEE_BP, null);
        }
        Row row = PRICE_MARKETS.get(symbol);
        if (row == null) {
            return new MarketEconomy(maxLeverage, maxLeverage, PRICE_KAPPA, DEFAULT_FEE_BP, null);
        }
        return new MarketEconomy(row.capHourly(), row.capDaily(), PRICE_KAPPA, new BigDecimal(row.feeBp()), null);
    }

    /** The leverage cap of one round length. */
    public int capFor(crowdvsmarket.engine.market.Interval interval) {
        return interval == crowdvsmarket.engine.market.Interval.HOURLY ? capHourly : capDaily;
    }

    /** {@code r_m} as a fraction of notional: 1.0 bp is 0.0001. */
    public BigDecimal herdFeeRate() {
        return herdFeeBp.movePointLeft(4);
    }
}
