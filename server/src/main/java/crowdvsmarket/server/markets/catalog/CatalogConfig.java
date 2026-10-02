package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.markets.Category;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * @param gameCooldownMs  {@code herd.markets.polymarket.game-cooldown}: after a market of a game (Gamma's
 *                        {@code gameId}) was closed, no market of that game is listed again for this long
 *                        (default 6 hours, 0 turns the rule off) — see {@link PolymarketCatalog}
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
        boolean inPlayFirst,
        long gameCooldownMs) {

    /** The settings before the game cooldown, with its default. */
    public CatalogConfig(boolean liveSources, boolean syntheticSource, List<String> crypto, List<String> stocks,
                         List<String> indices, List<String> commodities, boolean polymarketEnabled, int politicsCount,
                         int sportsCount, int oddsMaxLeverage, boolean inPlayFirst) {
        this(liveSources, syntheticSource, crypto, stocks, indices, commodities, polymarketEnabled, politicsCount,
                sportsCount, oddsMaxLeverage, inPlayFirst, parseDurationMs(DEFAULT_GAME_COOLDOWN));
    }

    public CatalogConfig {
        if (gameCooldownMs < 0) {
            throw new IllegalArgumentException("herd.markets.polymarket.game-cooldown must not be negative, got "
                    + gameCooldownMs + " ms");
        }
    }

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
    /** Six hours: longer than any game of the sports Polymarket lists, and the closed market's game is over (предложение). */
    public static final String DEFAULT_GAME_COOLDOWN = "6h";

    /** The defaults with real sources — for tests. */
    public static CatalogConfig defaults() {
        return new CatalogConfig(true, false, parseNames(DEFAULT_CRYPTO), parseNames(DEFAULT_STOCKS),
                parseNames(DEFAULT_INDICES), parseNames(DEFAULT_COMMODITIES), true,
                Integer.parseInt(DEFAULT_POLITICS_COUNT), Integer.parseInt(DEFAULT_SPORTS_COUNT),
                Integer.parseInt(DEFAULT_ODDS_MAX_LEVERAGE), true, parseDurationMs(DEFAULT_GAME_COOLDOWN));
    }

    /**
     * A duration as the config spells it, in milliseconds: {@code 6h}, {@code 30m}, {@code 45s}, {@code 500ms},
     * {@code 1d}, a bare number of seconds, or an ISO-8601 one ({@code PT6H}) — the spellings Quarkus durations take.
     *
     * @throws IllegalArgumentException when the text is none of them
     */
    public static long parseDurationMs(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        try {
            Matcher simple = SIMPLE_DURATION.matcher(text);
            if (simple.matches()) {
                long n = Long.parseLong(simple.group(1));
                String name = simple.group(2) == null ? "s" : simple.group(2); // no unit: seconds
                long unit = switch (name) {
                    case "ms" -> 1L;
                    case "m" -> 60_000L;
                    case "h" -> 3_600_000L;
                    case "d" -> 86_400_000L;
                    default -> 1_000L;
                };
                return Math.multiplyExact(n, unit);
            }
            return Duration.parse(text.toUpperCase(Locale.ROOT)).toMillis();
        } catch (ArithmeticException | DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("not a duration (6h, 30m, 45s, 500ms, 1d or PT6H): '" + raw + "'", e);
        }
    }

    private static final Pattern SIMPLE_DURATION = Pattern.compile("(\\d+)(ms|s|m|h|d)?");

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
