package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.markets.Category;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code herd.markets.*} settings of the catalogs (docs/research/markets_architecture.md §3.3,
 * §10) as one immutable value, built from {@code @ConfigProperty} by {@link CatalogConfigProducer};
 * every key has its default there. The environment variable of a key is its upper-cased name with
 * dots and dashes turned into underscores ({@code HERD_MARKETS_POLITICS_COUNT}).
 *
 * @param liveSources     mode=live with real sources: the Hyperliquid and Polymarket catalogs run
 * @param syntheticSource mode=live with the synthetic walk: only the synthetic catalog runs
 * @param inPlayFirst     sports: games in play are listed before anything else, and their markets are
 *                        exempt from the rules that read {@code endDate} — see {@link PolymarketCatalog}
 */
public record CatalogConfig(
        boolean liveSources,
        boolean syntheticSource,
        List<String> crypto,
        List<String> stocks,
        List<String> indices,
        List<String> commodities,
        boolean polymarketEnabled,
        int politicsCount,
        int sportsCount,
        int oddsMaxLeverage,
        boolean inPlayFirst) {

    /** A configured Hyperliquid coin name with the category its list puts it in. */
    public record PerpName(String name, String category) {
    }

    public static final String DEFAULT_CRYPTO = "BTC,ETH,SOL,XRP,DOGE,HYPE";
    public static final String DEFAULT_STOCKS = "xyz:NVDA,xyz:TSLA";
    public static final String DEFAULT_INDICES = "xyz:SP500,xyz:XYZ100";
    public static final String DEFAULT_COMMODITIES = "xyz:GOLD,xyz:BRENTOIL";
    public static final String DEFAULT_POLYMARKET_ENABLED = "true";
    public static final String DEFAULT_POLITICS_COUNT = "3";
    public static final String DEFAULT_SPORTS_COUNT = "3";
    public static final String DEFAULT_ODDS_MAX_LEVERAGE = "10";
    public static final String DEFAULT_IN_PLAY_FIRST = "true";

    /** The defaults with real sources — for tests. */
    public static CatalogConfig defaults() {
        return new CatalogConfig(true, false, parseNames(DEFAULT_CRYPTO), parseNames(DEFAULT_STOCKS),
                parseNames(DEFAULT_INDICES), parseNames(DEFAULT_COMMODITIES), true,
                Integer.parseInt(DEFAULT_POLITICS_COUNT), Integer.parseInt(DEFAULT_SPORTS_COUNT),
                Integer.parseInt(DEFAULT_ODDS_MAX_LEVERAGE), true);
    }

    /** {@code "BTC, ETH,,xyz:NVDA"} → {@code [BTC, ETH, xyz:NVDA]}: trimmed, blanks and repeats dropped, order kept. */
    public static List<String> parseNames(String csv) {
        Set<String> names = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /** Every configured Hyperliquid name once, crypto first, then stocks, indices, commodities. */
    public List<PerpName> perpNames() {
        List<PerpName> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        add(out, seen, crypto, Category.CRYPTO);
        add(out, seen, stocks, Category.STOCKS);
        add(out, seen, indices, Category.INDICES);
        add(out, seen, commodities, Category.COMMODITIES);
        return out;
    }

    private static void add(List<PerpName> out, Set<String> seen, List<String> names, String category) {
        for (String name : names) {
            if (seen.add(name)) {
                out.add(new PerpName(name, category));
            }
        }
    }

    /** How many active Polymarket markets to keep in a category (politics, sports); 0 for any other. */
    public int oddsCount(String category) {
        return switch (category) {
            case Category.POLITICS -> politicsCount;
            case Category.SPORTS -> sportsCount;
            default -> 0;
        };
    }
}
