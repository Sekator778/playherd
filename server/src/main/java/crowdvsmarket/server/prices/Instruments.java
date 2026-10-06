package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.server.markets.Market;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the price pipeline prices for a market: the <b>instruments</b> of Р-76 ("a round runs entirely on one exchange"). A
 * market that has a backup exchange — its {@code sourceMeta} says {@code {"backup":{"source":"kraken","ref":"BTC/USD"}}}
 * — has two: itself, priced by its own {@code source} and {@code sourceRef} (the primary venue), and a <b>shadow</b>, a copy
 * of the market with {@code id = "<marketId>~<backupSource>"} ({@code spot_BTC~kraken}), {@code source} the backup venue and
 * {@code sourceRef} the backup's name of the pair. Everything of the pipeline that is per market — the poll of a feed, the
 * tick ring and its snapshot rows, the seeding of history, pins, healing, the quiet-carry rule, staleness — works on
 * instruments and so holds per venue unchanged: the backup's ticks live in {@link TickStore} under the shadow's id, restored
 * at start like any other tick.
 *
 * <p>A shadow is only an address for the pipeline. It never enters the market registry, the engine, the API's lists or the
 * oracle registry — the id of a shadow is never a market's: {@code '~'} is not legal in a market id
 * ({@code [A-Za-z0-9_]{1,32}}), so there is no collision. The shadow has the primary's lot exponent and decimals (one market,
 * one price scale whichever venue prices a round), and no {@code sourceMeta}, so it has no backup of its own.
 */
public final class Instruments {

    /** The character that tells a shadow's id from a market's. */
    public static final char SHADOW_SEPARATOR = '~';

    /** The backup exchange of a market: the venue's name ({@code kraken}) and what that venue calls the pair ({@code BTC/USD}). */
    public record Backup(String source, String ref) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** The parsed backups by {@code sourceMeta} text: only metas that mention a backup are kept, so as many entries as there are such markets. */
    private static final ConcurrentHashMap<String, Optional<Backup>> PARSED = new ConcurrentHashMap<>();

    private Instruments() {
    }

    /** The backup exchange the market was listed with; empty for a market that has none (and for a shadow). */
    public static Optional<Backup> backupOf(Market market) {
        String meta = market.sourceMeta();
        if (meta == null || !meta.contains("\"backup\"")) {
            return Optional.empty();
        }
        return PARSED.computeIfAbsent(meta, Instruments::parse);
    }

    public static boolean hasBackup(Market market) {
        return backupOf(market).isPresent();
    }

    private static Optional<Backup> parse(String meta) {
        try {
            JsonNode backup = JSON.readTree(meta).path("backup");
            String source = backup.path("source").asText("");
            String ref = backup.path("ref").asText("");
            return source.isEmpty() || ref.isEmpty() ? Optional.empty() : Optional.of(new Backup(source, ref));
        } catch (Exception e) {
            return Optional.empty(); // a note that is no JSON says nothing; the market is simply priced by its own venue
        }
    }

    /** The key of the backup venue's ticks of a market: {@code spot_BTC~kraken}. */
    public static String shadowId(String marketId, String backupSource) {
        return marketId + SHADOW_SEPARATOR + backupSource;
    }

    public static boolean isShadowId(String id) {
        return id.indexOf(SHADOW_SEPARATOR) >= 0;
    }

    /** The market a ring key belongs to: {@code spot_BTC} for {@code spot_BTC~kraken}, and any other id as it is. */
    public static String marketIdOf(String ringKey) {
        int at = ringKey.indexOf(SHADOW_SEPARATOR);
        return at < 0 ? ringKey : ringKey.substring(0, at);
    }

    /** The shadow instrument of the market's backup exchange; empty when it has none. */
    public static Optional<Market> shadowOf(Market market) {
        return backupOf(market).map(backup -> new Market(shadowId(market.id(), backup.source()), market.kind(),
                market.category(), market.symbol(), market.title(), market.subtitle(), market.iconUrl(),
                backup.source(), backup.ref(), null, market.maxLeverage(), market.lotExp(), market.oracleIndex(),
                market.status(), market.listedAtMs(), market.closedAtMs(), market.closeReason(), market.endsAtMs()));
    }

    /** The market itself and, when it has a backup, its shadow — what the pipeline polls, seeds and keeps rings for. */
    public static List<Market> of(Market market) {
        Optional<Market> shadow = shadowOf(market);
        return shadow.isPresent() ? List.of(market, shadow.get()) : List.of(market);
    }

    /**
     * The instrument a round that was decided on {@code venue} runs on (Р-76): the shadow when that is the market's backup
     * exchange, the market itself for its own exchange, for a round with no decision and for a market without a backup.
     */
    public static Market runningOn(Market market, Optional<String> venue) {
        return shadowOf(market).filter(shadow -> venue.filter(shadow.source()::equals).isPresent()).orElse(market);
    }

    /**
     * The feed that prices an instrument — a market, or the shadow of one — by what its {@code source} and {@code sourceRef} say,
     * whether or not that source is switched on: the ids the sources give their feeds ({@link PriceSource.Feed#id}), by the rules of
     * their own {@link PriceSource.Feed#handles}. Hyperliquid has three — a spot pair ({@code @107}) is {@code hyperliquid:spot}, a
     * perpetual of the dex {@code xyz} is {@code hyperliquid:xyz}, any other perpetual is {@code hyperliquid} — and every other
     * source has one. Empty for a source that has no feed, and for a Hyperliquid coin of a dex that nothing polls. With
     * {@code herd.prices.source=synthetic} the walk prices every market whatever its source says; that is for the caller to know
     * ({@link PricesConfig#releaseAfterMs}).
     */
    public static Optional<String> feedOf(Market instrument) {
        return switch (instrument.source()) {
            case HyperliquidPriceSource.NAME -> hyperliquidFeedOf(instrument.sourceRef());
            case CoinbasePriceSource.NAME -> Optional.of(CoinbasePriceSource.FEED);
            case KrakenPriceSource.NAME -> Optional.of(KrakenPriceSource.FEED);
            case PolymarketPriceSource.NAME -> Optional.of(PolymarketPriceSource.FEED);
            case SyntheticPriceSource.NAME -> Optional.of(SyntheticPriceSource.FEED);
            default -> Optional.empty();
        };
    }

    private static Optional<String> hyperliquidFeedOf(String sourceRef) {
        if (HyperliquidPriceSource.isSpotRef(sourceRef)) {
            return Optional.of(HyperliquidSpotPriceSource.FEED);
        }
        String dex = HyperliquidPriceSource.dexOf(sourceRef);
        if (!HyperliquidPriceSource.DEXES.contains(dex)) {
            return Optional.empty();
        }
        return Optional.of(dex.isEmpty() ? HyperliquidPriceSource.FEED_MAIN : HyperliquidPriceSource.FEED_XYZ);
    }
}
