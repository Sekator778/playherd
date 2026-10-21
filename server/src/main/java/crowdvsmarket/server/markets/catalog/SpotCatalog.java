package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.CoinbaseExchangeApi;
import crowdvsmarket.server.prices.CoinbasePriceSource;
import crowdvsmarket.server.prices.Dec;
import crowdvsmarket.server.prices.HistorySeeder;
import crowdvsmarket.server.prices.HyperliquidInfoApi;
import crowdvsmarket.server.prices.HyperliquidPriceSource;
import crowdvsmarket.server.prices.HyperliquidSpotPriceSource;
import crowdvsmarket.server.prices.KrakenApi;
import crowdvsmarket.server.prices.KrakenPriceSource;
import crowdvsmarket.server.prices.PriceHttpClients;
import io.quarkus.arc.Unremovable;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Lists the crypto markets that are priced by the exchange of the asset itself — one market per Coinbase Exchange USD
 * product of {@code herd.markets.coinbase} ({@code BTC-USD} → {@code spot_BTC}) and per token of
 * {@code herd.markets.hyperliquid-spot} ({@code HYPE} → {@code spot_HYPE}, the pair HYPE/USDC of Hyperliquid's spot
 * market) — and <b>retires the market each of them replaces</b>
 * (docs/research/primary_sources.md §4; iteration 1 of the move from Hyperliquid's perpetuals to the venues' own
 * prices). A source change is a new market (Т-10): the new one has its own id, source, lot exponent and oracle index.
 *
 * <p><b>The market.</b> Kind {@code PERP} — which now means "the USD price of an asset", whatever the venue — category
 * {@code crypto}, the symbol the coin's own ({@code BTC}), the title from {@link MarketNames}. The id is
 * {@code spot_<SYMBOL>} whichever venue prices the market (a market may be priced by its backup exchange, so the id says
 * nothing about a venue). Priced by Coinbase: source {@code coinbase}, {@code sourceRef} the product id; priced by Hyperliquid's
 * spot market: source {@code hyperliquid}, {@code sourceRef} the
 * pair's name in Hyperliquid's spot meta ({@code "@107"}, found by the token names HYPE and USDC — never hard-coded),
 * {@code sourceMeta} {@code {"spot":true,"pair":"HYPE/USDC"}}. Leverage is {@code herd.markets.spot.max-leverage} of the
 * symbol (the caps the six markets had as perpetuals), at most 40; the lot exponent is {@link Market#lotExpFor} of the
 * first price read, fixed from then on (Т-3).
 *
 * <p><b>The backup exchange</b> (Р-76: a round runs entirely on one exchange, chosen when it opens): the listing records
 * in {@code sourceMeta} the exchange a round moves to when the primary cannot price its start, from
 * {@code herd.markets.spot.backup} — Kraken ({@code "backup":{"source":"kraken","ref":"BTC/USD"}}) behind Coinbase for BTC, ETH,
 * SOL, XRP and DOGE, Coinbase ({@code {"source":"coinbase","ref":"HYPE-USD"}}) behind Hyperliquid's spot market for HYPE. Like
 * the rest of a listing it is fixed from then on (Т-10): a change of the setting is for the markets listed after it. A Kraken pair
 * is looked up in {@code /0/public/AssetPairs} first and a market whose pair Kraken says it does not know is listed without a
 * backup ({@link #recordBackup}); a market is not kept from the lobby because Kraken cannot be asked right now, or because the
 * backup's pair does not answer right now — and Coinbase, the backup of HYPE, is not asked at all.
 *
 * <p><b>Replacement, in this order, and only then.</b> For a market that is not in the registry yet: (1) the source is
 * read — the product's ticker, or the spot meta with the pair's {@code midPx} — and a source that cannot be reached
 * leaves everything as it is, to be tried again; (2) every <i>active</i> market of the same category and symbol that is
 * not this one — BTC, ETH … on Hyperliquid's perpetuals — is closed through
 * {@link MarketRegistry#closeReplaced} with the reason {@value #REASON_COINBASE} or {@value #REASON_HYPERLIQUID_SPOT},
 * which the players read as it stands and which cancels its open rounds with a refund; (3) only when each of them is
 * seen to be closed, (4) the new market is listed and handed to {@link HistorySeeder}. So two active markets never
 * share a symbol, and a close that failed lists nothing. BTC is the one market the registry otherwise never closes; its
 * row stays, closed. A crash between (2) and (4) leaves the old market closed and the new one for the next run.
 *
 * <p><b>Idempotent.</b> A market in the registry — active or closed, a closed id is never listed again — is skipped
 * without a request, so a restart replaces nothing twice, and once every configured market is listed a run costs
 * nothing. A name that cannot be a market (not a USD product, not a legal id, a product Coinbase answers 404 for, a
 * token with no unique pair against USDC) is warned about once and not asked for again until restart; a source that is
 * unreachable, or answers without a usable price, is tried again at the next run, every minute, like
 * {@link HyperliquidCatalog}.
 *
 * <p><b>Not here (UNVERIFIED, a later iteration):</b> a market whose product Coinbase delists simply goes stale — its
 * rounds are refunded an hour after their end (Р-23) — there is no watcher like {@link HyperliquidCatalog#watch}.
 *
 * <p><b>Locks.</b> Neither {@link #sync} nor anything it calls holds a lock or monitor while it calls
 * {@code registry.closeReplaced} / {@code registry.list}: the registry's listener (the game service) takes the engine's
 * WRITE lock on the calling thread (see {@link HyperliquidCatalog}). Runs only with {@code herd.mode=live} and
 * {@code herd.prices.source=live}, on its own {@code @Scheduled(concurrentExecution = SKIP)}.
 */
@ApplicationScoped
@Unremovable
public class SpotCatalog {

    private static final Logger LOG = Logger.getLogger(SpotCatalog.class);

    static final String EVERY = "${herd.markets.spot.catalog-every:1m}";
    static final String JOB = "catalog-spot";
    /** Shown to the players as it stands. */
    static final String REASON_COINBASE = "Moved to a new price source: Coinbase";
    static final String REASON_HYPERLIQUID_SPOT = "Moved to a new price source: Hyperliquid spot";
    /** The id of every market of this catalog: {@code spot_BTC}, whichever venue prices it (a market can move to its backup). */
    static final String ID_PREFIX = "spot_";
    /** What a spot pair of Hyperliquid is quoted in. */
    static final String HYPERLIQUID_QUOTE = "USDC";

    /** {@code BTC-USD}: the asset, and USD — a market's price is the USD price of an asset. */
    private static final Pattern COINBASE_PRODUCT = Pattern.compile("([A-Z0-9]{1,10})-USD");
    private static final Pattern HYPERLIQUID_TOKEN = Pattern.compile("[A-Za-z0-9]{1,10}");
    private static final Pattern LEGAL_ID = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private final MarketRegistry registry;
    private final CatalogConfig config;
    private final Supplier<CoinbaseExchangeApi> coinbase;
    private final Supplier<HyperliquidInfoApi> hyperliquid;
    private final Supplier<KrakenApi> kraken;
    private final HistorySeeder seeder;
    private final LongSupplier clock;
    private final Set<String> knownBad = ConcurrentHashMap.newKeySet();
    private final Set<String> sameVenueWarned = ConcurrentHashMap.newKeySet();
    /** Kraken's answer for a backup pair, by its name ({@code BTC/USD}) — a definite one only, kept so it is asked once. */
    private final ConcurrentHashMap<String, Boolean> krakenPairs = new ConcurrentHashMap<>();
    private final Set<String> unknownBackupWarned = ConcurrentHashMap.newKeySet();

    @Inject
    public SpotCatalog(MarketRegistry registry, CatalogConfig config, PriceHttpClients clients, HistorySeeder seeder) {
        this(registry, config, clients.coinbase(), clients.hyperliquid(), clients.kraken(), seeder,
                System::currentTimeMillis);
    }

    SpotCatalog(MarketRegistry registry, CatalogConfig config, Supplier<CoinbaseExchangeApi> coinbase,
                Supplier<HyperliquidInfoApi> hyperliquid, Supplier<KrakenApi> kraken, LongSupplier clock) {
        this(registry, config, coinbase, hyperliquid, kraken, HistorySeeder.disabled(), clock);
    }

    SpotCatalog(MarketRegistry registry, CatalogConfig config, Supplier<CoinbaseExchangeApi> coinbase,
                Supplier<HyperliquidInfoApi> hyperliquid, Supplier<KrakenApi> kraken, HistorySeeder seeder,
                LongSupplier clock) {
        this.registry = registry;
        this.config = config;
        this.coinbase = coinbase;
        this.hyperliquid = hyperliquid;
        this.kraken = kraken;
        this.seeder = seeder;
        this.clock = clock;
    }

    @Scheduled(every = EVERY, delayed = "3s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = JOB)
    void run() {
        WhenReady.run(JOB, this::runNow);
    }

    private void runNow() {
        if (config.liveSources()) {
            sync(clock.getAsLong());
        }
    }

    /** One pass over the configured names; returns how many are still waiting for a later pass. */
    int sync(long nowMs) {
        return syncCoinbase(nowMs) + syncHyperliquidSpot(nowMs);
    }

    // ------------------------------------------------------------------------------------------
    // Coinbase
    // ------------------------------------------------------------------------------------------

    private int syncCoinbase(long nowMs) {
        int pending = 0;
        for (String product : config.coinbase()) {
            if (knownBad.contains(product)) {
                continue;
            }
            Matcher m = COINBASE_PRODUCT.matcher(product);
            if (!m.matches()) {
                markBad(product, "not a USD product of the form ASSET-USD: a market's price is the USD price of an asset");
                continue;
            }
            String symbol = m.group(1);
            String id = ID_PREFIX + symbol;
            if (!LEGAL_ID.matcher(id).matches()) {
                markBad(product, "'" + id + "' is not a legal market id");
                continue;
            }
            if (registry.byId(id).isPresent()) {
                continue; // listed by this or an earlier run, active or closed: never again
            }
            if (config.venueDisabled(CoinbasePriceSource.NAME)) {
                pending++; // herd.prices.disabled-venues: Coinbase gives no price, not even to a catalog; not listed, not asked
                continue;
            }
            BigDecimal mid;
            try {
                mid = CoinbasePriceSource.midOf(coinbase.get().ticker(product));
            } catch (WebApplicationException e) {
                if (e.getResponse().getStatus() == 404) {
                    markBad(product, "Coinbase Exchange does not list it");
                } else {
                    LOG.warnf("Coinbase ticker of %s unavailable (HTTP %d), %s waits for the next run", product,
                            e.getResponse().getStatus(), id);
                    pending++;
                }
                continue;
            } catch (RuntimeException e) {
                LOG.warnf("Coinbase ticker of %s unavailable, %s waits for the next run: %s", product, id, e.toString());
                pending++;
                continue;
            }
            if (mid == null) {
                LOG.warnf("%s has no usable best bid and ask yet, will try again", product);
                pending++;
                continue;
            }
            ObjectNode meta = JsonNodeFactory.instance.objectNode().put("product", product).put("quote", "USD");
            recordBackup(meta, symbol, CoinbasePriceSource.NAME);
            NewMarket market = new NewMarket(id, MarketKind.PERP, Category.CRYPTO, symbol, MarketNames.title(symbol),
                    null, null, CoinbasePriceSource.NAME, product, meta.toString(), config.spotLeverage(symbol),
                    Market.lotExpFor(mid), null);
            pending += replaceAndList(market, REASON_COINBASE, mid, nowMs);
        }
        return pending;
    }

    // ------------------------------------------------------------------------------------------
    // Hyperliquid spot
    // ------------------------------------------------------------------------------------------

    private int syncHyperliquidSpot(long nowMs) {
        int pending = 0;
        JsonNode answer = null; // one read of Hyperliquid's spot meta serves every token of this pass
        Map<String, JsonNode> contexts = null;
        boolean unreachable = false;
        for (String symbol : config.hyperliquidSpot()) {
            if (knownBad.contains(symbol)) {
                continue;
            }
            String id = ID_PREFIX + symbol;
            if (!HYPERLIQUID_TOKEN.matcher(symbol).matches() || !LEGAL_ID.matcher(id).matches()) {
                markBad(symbol, "not a token name a market can be made of");
                continue;
            }
            if (registry.byId(id).isPresent()) {
                continue;
            }
            if (unreachable || config.venueDisabled(HyperliquidPriceSource.NAME)) {
                pending++; // the meta could not be read a moment ago (or Hyperliquid is switched off): no request for this token
                continue;
            }
            if (answer == null) {
                try {
                    answer = hyperliquid.get().info(HyperliquidSpotPriceSource.spotMetaAndAssetCtxsRequest());
                    contexts = HyperliquidSpotPriceSource.contexts(answer); // refuses what is not [meta, ctxs]
                } catch (RuntimeException e) { // unreachable, or an error body that is no meta: not a pair that is missing
                    LOG.warnf("Hyperliquid spot meta unavailable, the spot market(s) wait for the next run: %s",
                            e.toString());
                    answer = null;
                    unreachable = true;
                    pending++;
                    continue;
                }
            }
            Optional<String> pair = HyperliquidSpotPriceSource.pairName(answer, symbol, HYPERLIQUID_QUOTE);
            if (pair.isEmpty()) {
                markBad(symbol, "Hyperliquid's spot meta has no single pair " + symbol + "/" + HYPERLIQUID_QUOTE);
                continue;
            }
            JsonNode ctx = contexts.get(pair.get());
            BigDecimal mid = ctx == null ? null : Dec.of(ctx.path("midPx"));
            if (mid == null || mid.signum() <= 0) {
                LOG.warnf("%s (%s) has no midPx yet, will try again", pair.get(), symbol);
                pending++;
                continue;
            }
            ObjectNode meta = JsonNodeFactory.instance.objectNode().put("spot", true).put("pair",
                    symbol + "/" + HYPERLIQUID_QUOTE);
            recordBackup(meta, symbol, HyperliquidPriceSource.NAME);
            NewMarket market = new NewMarket(id, MarketKind.PERP, Category.CRYPTO, symbol, MarketNames.title(symbol),
                    null, null, HyperliquidPriceSource.NAME, pair.get(), meta.toString(), config.spotLeverage(symbol),
                    Market.lotExpFor(mid), null);
            pending += replaceAndList(market, REASON_HYPERLIQUID_SPOT, mid, nowMs);
        }
        return pending;
    }

    // ------------------------------------------------------------------------------------------
    // Replacement
    // ------------------------------------------------------------------------------------------

    /**
     * Retires the active markets this one takes over, then lists it; returns 1 when it has to be tried again. No lock
     * is held: the registry's listener takes the engine's write lock (class comment).
     */
    private int replaceAndList(NewMarket market, String reason, BigDecimal firstPrice, long nowMs) {
        List<Market> replaced = registry.active().stream()
                .filter(m -> m.category().equals(market.category()) && m.symbol().equals(market.symbol())
                        && !m.id().equals(market.id()))
                .toList();
        for (Market old : replaced) {
            LOG.infof("closing %s (%s %s) for %s: %s", old.id(), old.source(), old.sourceRef(), market.id(), reason);
            try {
                registry.closeReplaced(old.id(), reason, nowMs);
            } catch (RuntimeException e) {
                LOG.warnf("could not close %s for %s, will try again: %s", old.id(), market.id(), e.toString());
                return 1;
            }
            if (registry.byId(old.id()).map(Market::isActive).orElse(false)) {
                LOG.warnf("%s is still active after it was closed for %s: not listing it, will try again", old.id(),
                        market.id());
                return 1;
            }
        }
        Market listed;
        try {
            listed = registry.list(market, nowMs);
        } catch (RuntimeException e) {
            LOG.warnf("could not list %s, will try again: %s", market.id(), e.toString());
            return 1;
        }
        LOG.infof("listed %s (%s %s, %dx, lot exponent %d) at %s%s", listed.id(), listed.source(), listed.sourceRef(),
                listed.maxLeverage(), listed.lotExp(), firstPrice.toPlainString(),
                replaced.isEmpty() ? "" : ", replacing " + replaced.stream().map(Market::id).toList());
        seeder.seed(listed); // its last minutes of history, fetched in the background
        return 0;
    }

    /**
     * Writes the symbol's backup exchange into the market's {@code sourceMeta}
     * ({@code "backup":{"source":"kraken","ref":"BTC/USD"}}) — what {@code Instruments} reads, and fixed from here on like
     * everything else a listing records (Т-10). A symbol with none in {@code herd.markets.spot.backup}, or one named
     * {@code none}, has no backup; a backup on the very venue that prices the market is no backup, said once and left out.
     *
     * <p><b>A Kraken backup is checked against {@code /0/public/AssetPairs}</b> ({@link #krakenKnows}): a pair that Kraken
     * says it does not know ({@code EQuery:Unknown asset pair}) would be a shadow that never gets a price — and once polled
     * a source that has to retry per pair — so the market is listed without a backup, said once. Only that definite answer
     * keeps a backup out: a Kraken that cannot be asked (unreachable, an error, a venue switched off) does not keep a market
     * from the lobby, and its backup is recorded as before — the pair may well answer later, and the source copes with a
     * pair that never does.
     */
    private void recordBackup(ObjectNode meta, String symbol, String primaryVenue) {
        Optional<String> venue = config.spotBackupVenue(symbol);
        if (venue.isEmpty()) {
            return;
        }
        if (venue.get().equals(primaryVenue)) {
            if (sameVenueWarned.add(symbol)) {
                LOG.warnf("%s is priced by %s and its configured backup is the same venue: it has no backup", symbol,
                        primaryVenue);
            }
            return;
        }
        String ref = backupRef(venue.get(), symbol);
        if (KrakenPriceSource.NAME.equals(venue.get()) && !krakenKnows(ref, symbol)) {
            return;
        }
        meta.putObject("backup").put("source", venue.get()).put("ref", ref);
    }

    /**
     * Whether Kraken has the pair {@code ref} ({@code BTC/USD}), by one {@code AssetPairs} request per pair, a definite answer
     * kept. {@code false} only when Kraken says the pair is unknown; {@code true} otherwise, an unanswered question included
     * (and without asking at all while Kraken is in {@code herd.prices.disabled-venues}).
     */
    private boolean krakenKnows(String ref, String symbol) {
        if (config.venueDisabled(KrakenPriceSource.NAME)) {
            return true; // switched off: nobody asks it anything, the backup is recorded and waits for the venue
        }
        Boolean known = krakenPairs.get(ref);
        if (known != null) {
            return known;
        }
        Optional<Boolean> says;
        try {
            says = KrakenPriceSource.knowsPair(kraken.get().assetPairs(ref));
        } catch (RuntimeException e) { // unreachable, or an HTTP error: no word about the pair
            LOG.warnf("could not ask Kraken whether it has %s, %s keeps its backup: %s", ref, symbol, e.toString());
            return true;
        }
        if (says.isEmpty()) {
            LOG.warnf("Kraken's AssetPairs said nothing about %s, %s keeps its backup", ref, symbol);
            return true;
        }
        krakenPairs.put(ref, says.get());
        if (!says.get() && unknownBackupWarned.add(ref)) {
            LOG.warnf("Kraken does not know the pair %s: %s is listed without a backup exchange", ref, symbol);
        }
        return says.get();
    }

    /** What a backup venue calls the USD pair of a symbol: Kraken's WebSocket v2 name {@code BTC/USD}, Coinbase's product {@code BTC-USD}. */
    static String backupRef(String venue, String symbol) {
        return KrakenPriceSource.NAME.equals(venue) ? symbol + "/USD" : symbol + "-USD";
    }

    private void markBad(String name, String why) {
        if (knownBad.add(name)) {
            LOG.warnf("not listing spot market %s: %s", name, why);
        }
    }

    /** The names a pass has given up on (they are not asked for again until restart) — for tests. */
    Set<String> knownBad() {
        return Set.copyOf(knownBad);
    }
}
