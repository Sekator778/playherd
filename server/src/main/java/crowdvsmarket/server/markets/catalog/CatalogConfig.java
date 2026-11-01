package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.markets.Category;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code herd.markets.*} settings of the catalogs (docs/research/markets_architecture.md §3.3,
 * §10) as one immutable value, built from {@code @ConfigProperty} by {@link CatalogConfigProducer};
 * every key has its default there. The environment variable of a key is its upper-cased name with
 * dots and dashes turned into underscores ({@code HERD_MARKETS_POLITICS_COUNT}).
 *
 * @param liveSources     mode=live with real sources: the Hyperliquid, spot and Polymarket catalogs run
 * @param syntheticSource mode=live with the synthetic walk: only the synthetic catalog runs
 * @param inPlayFirst     sports: games in play are listed before anything else, and their markets are
 *                        exempt from the rules that read {@code endDate} — see {@link PolymarketCatalog}
 * @param crypto          {@code herd.markets.crypto}: crypto <i>perpetuals</i> of Hyperliquid — empty by default since
 *                        the crypto markets moved to their own venues (the next three)
 * @param gameCooldownMs  {@code herd.markets.polymarket.game-cooldown}: after a market of a game (Gamma's
 *                        {@code gameId}) was closed, no market of that game is listed again for this long
 *                        (default 6 hours, 0 turns the rule off) — see {@link PolymarketCatalog}
 * @param coinbase        {@code herd.markets.coinbase}: the Coinbase Exchange USD products to list ({@code BTC-USD}),
 *                        one market each — see {@link SpotCatalog}
 * @param hyperliquidSpot {@code herd.markets.hyperliquid-spot}: the tokens to list from Hyperliquid's spot market,
 *                        quoted in USDC ({@code HYPE} is HYPE/USDC), one market each
 * @param spotMaxLeverage {@code herd.markets.spot.max-leverage}: the leverage cap per symbol of the two lists above
 *                        ({@code BTC:40,ETH:25,…}); neither venue has one of its own, and nothing is above 40
 * @param spotBackup      {@code herd.markets.spot.backup}: the backup exchange per symbol of the two lists above
 *                        ({@code BTC:kraken,…,HYPE:coinbase}; {@code none} for a symbol that has no backup) — recorded
 *                        in the market's {@code sourceMeta} when it is listed and fixed from then on (Т-10), like
 *                        everything else about a listed market; a symbol that is not in it has none
 * @param disabledVenues  {@code herd.prices.disabled-venues}: venues that give no price at all, the catalog's requests
 *                        to them included
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
        long gameCooldownMs,
        List<String> coinbase,
        List<String> hyperliquidSpot,
        Map<String, Integer> spotMaxLeverage,
        Map<String, String> spotBackup,
        Set<String> disabledVenues) {

    /** The settings before the backup exchanges, with their defaults (the six default markets have one) and no venue off. */
    public CatalogConfig(boolean liveSources, boolean syntheticSource, List<String> crypto, List<String> stocks,
                         List<String> indices, List<String> commodities, boolean polymarketEnabled, int politicsCount,
                         int sportsCount, int oddsMaxLeverage, boolean inPlayFirst, long gameCooldownMs,
                         List<String> coinbase, List<String> hyperliquidSpot, Map<String, Integer> spotMaxLeverage) {
        this(liveSources, syntheticSource, crypto, stocks, indices, commodities, polymarketEnabled, politicsCount,
                sportsCount, oddsMaxLeverage, inPlayFirst, gameCooldownMs, coinbase, hyperliquidSpot, spotMaxLeverage,
                parseBackups(DEFAULT_SPOT_BACKUP), Set.of());
    }

    /** The settings before the spot markets, with their defaults. */
    public CatalogConfig(boolean liveSources, boolean syntheticSource, List<String> crypto, List<String> stocks,
                         List<String> indices, List<String> commodities, boolean polymarketEnabled, int politicsCount,
                         int sportsCount, int oddsMaxLeverage, boolean inPlayFirst, long gameCooldownMs) {
        this(liveSources, syntheticSource, crypto, stocks, indices, commodities, polymarketEnabled, politicsCount,
                sportsCount, oddsMaxLeverage, inPlayFirst, gameCooldownMs, parseNames(DEFAULT_COINBASE),
                parseNames(DEFAULT_HYPERLIQUID_SPOT), parseLeverages(DEFAULT_SPOT_MAX_LEVERAGE));
    }

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
        spotMaxLeverage.forEach((symbol, leverage) -> {
            if (leverage < 2) {
                throw new IllegalArgumentException("herd.markets.spot.max-leverage: " + symbol + " has " + leverage
                        + "x, a market needs at least 2x");
            }
        });
        spotMaxLeverage = Map.copyOf(spotMaxLeverage);
        spotBackup = Map.copyOf(spotBackup);
        disabledVenues = Set.copyOf(disabledVenues);
    }

    /** The same settings with these venues switched off ({@code herd.prices.disabled-venues}). */
    public CatalogConfig withDisabledVenues(Set<String> venues) {
        return new CatalogConfig(liveSources, syntheticSource, crypto, stocks, indices, commodities, polymarketEnabled,
                politicsCount, sportsCount, oddsMaxLeverage, inPlayFirst, gameCooldownMs, coinbase, hyperliquidSpot,
                spotMaxLeverage, spotBackup, venues);
    }

    /** The venues a backup may be: where a round can run besides the primary (D-76). */
    public static final Set<String> BACKUP_VENUES = Set.of("coinbase", "kraken");
    /** What {@code herd.markets.spot.backup} says for a symbol that has no backup. */
    public static final String NO_BACKUP = "none";

    /** A configured Hyperliquid coin name with the category its list puts it in. */
    public record PerpName(String name, String category) {
    }

    /**
     * No Hyperliquid perpetual by default: the six crypto markets are priced by their own venues ({@link #DEFAULT_COINBASE},
     * {@link #DEFAULT_HYPERLIQUID_SPOT}). Name coins here ({@code ETH,SOL}) to list perpetuals again.
     */
    public static final String DEFAULT_CRYPTO = "";
    /** What the crypto default was while Hyperliquid's perpetuals priced them: the markets of the offline walk ({@link #syntheticPerpNames}). */
    public static final String LEGACY_CRYPTO = "BTC,ETH,SOL,XRP,DOGE,HYPE";
    public static final String DEFAULT_COINBASE = "BTC-USD,ETH-USD,SOL-USD,XRP-USD,DOGE-USD";
    public static final String DEFAULT_HYPERLIQUID_SPOT = "HYPE";
    /** The caps the six markets had on Hyperliquid's perpetuals (hl_multi_asset_live.md §2.1: BTC 40x, ETH 25x, SOL 20x, XRP 20x, DOGE 10x, HYPE 10x). */
    public static final String DEFAULT_SPOT_MAX_LEVERAGE = "BTC:40,ETH:25,SOL:20,XRP:20,DOGE:10,HYPE:10";
    /**
     * Backup exchange per symbol (D-76): Kraken's spot market behind Coinbase's for the five coins Coinbase prices, Coinbase's
     * HYPE-USD behind Hyperliquid's own HYPE/USDC. Where Kraken's pair is named {@code <SYMBOL>/USD} and Coinbase's
     * {@code <SYMBOL>-USD} — the names of both venues' streams ({@code SpotCatalog#backupRef}).
     */
    public static final String DEFAULT_SPOT_BACKUP = "BTC:kraken,ETH:kraken,SOL:kraken,XRP:kraken,DOGE:kraken,HYPE:coinbase";
    /** A spot symbol that is in no table gets this cap. */
    public static final int FALLBACK_SPOT_LEVERAGE = 10;
    /** Т-4: no market lets a player take more than 40x. */
    public static final int LEVERAGE_CAP = 40;
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

    /**
     * {@code "BTC:40, ETH:25"} → {@code {BTC=40, ETH=25}}: symbol and leverage, order kept.
     *
     * @throws IllegalArgumentException when an entry is not {@code SYMBOL:number}
     */
    public static Map<String, Integer> parseLeverages(String csv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String part : csv.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            try {
                String symbol = colon < 1 ? "" : entry.substring(0, colon).trim();
                if (symbol.isEmpty()) {
                    throw new NumberFormatException(entry);
                }
                out.put(symbol, Integer.parseInt(entry.substring(colon + 1).trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("herd.markets.spot.max-leverage wants SYMBOL:leverage pairs "
                        + "(BTC:40,ETH:25), got '" + entry + "'", e);
            }
        }
        return out;
    }

    /**
     * {@code "BTC:kraken, HYPE:coinbase, DOGE:none"} → {@code {BTC=kraken, HYPE=coinbase, DOGE=none}}: symbol and the
     * venue of its backup, lower case, order kept.
     *
     * @throws IllegalArgumentException when an entry is not {@code SYMBOL:venue} or the venue is none of
     *         {@link #BACKUP_VENUES} and {@value #NO_BACKUP}
     */
    public static Map<String, String> parseBackups(String csv) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : csv.split(",")) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            String symbol = colon < 1 ? "" : entry.substring(0, colon).trim();
            String venue = colon < 0 ? "" : entry.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (symbol.isEmpty() || !(BACKUP_VENUES.contains(venue) || NO_BACKUP.equals(venue))) {
                throw new IllegalArgumentException("herd.markets.spot.backup wants SYMBOL:venue pairs (BTC:kraken), the "
                        + "venue one of " + new java.util.TreeSet<>(BACKUP_VENUES) + " or " + NO_BACKUP + ", got '"
                        + entry + "'");
            }
            out.put(symbol, venue);
        }
        return out;
    }

    /** The backup exchange of a spot symbol ({@code kraken}, {@code coinbase}); empty for a symbol with none. */
    public java.util.Optional<String> spotBackupVenue(String symbol) {
        String venue = spotBackup.get(symbol);
        return venue == null || NO_BACKUP.equals(venue) ? java.util.Optional.empty() : java.util.Optional.of(venue);
    }

    /** Whether the venue is in {@code herd.prices.disabled-venues}: it gives no price, over no channel, the catalog's request included. */
    public boolean venueDisabled(String venue) {
        return disabledVenues.contains(venue);
    }

    /** The leverage cap of a spot symbol: the configured one, {@link #FALLBACK_SPOT_LEVERAGE} for a symbol not listed, at most {@link #LEVERAGE_CAP}. */
    public int spotLeverage(String symbol) {
        return Math.min(LEVERAGE_CAP, spotMaxLeverage.getOrDefault(symbol, FALLBACK_SPOT_LEVERAGE));
    }

    /**
     * What the offline walk lists as perpetuals: {@link #perpNames} — but with no crypto configured, the six crypto
     * markets the list used to have before they moved to their venues, under their own ids ({@code ETH}, not
     * {@code spot_ETH}), so that CI and the e2e run on the same markets as ever.
     */
    public List<PerpName> syntheticPerpNames() {
        if (!crypto.isEmpty()) {
            return perpNames();
        }
        List<PerpName> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        add(out, seen, parseNames(LEGACY_CRYPTO), Category.CRYPTO);
        add(out, seen, stocks, Category.STOCKS);
        add(out, seen, indices, Category.INDICES);
        add(out, seen, commodities, Category.COMMODITIES);
        return out;
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
