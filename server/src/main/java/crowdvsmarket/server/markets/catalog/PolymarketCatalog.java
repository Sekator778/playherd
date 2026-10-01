package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.Dec;
import crowdvsmarket.server.prices.PolymarketClobApi;
import crowdvsmarket.server.prices.PolymarketGammaApi;
import crowdvsmarket.server.prices.PolymarketLiveState;
import crowdvsmarket.server.prices.PolymarketPriceSource;
import crowdvsmarket.server.prices.PriceHttpClients;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.TickStore;
import io.quarkus.arc.Unremovable;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Lists and closes the Polymarket ODDS markets of the politics and sports categories
 * (docs/research/markets_architecture.md §3.3, polymarket_data_api.md §2–§5). Every 5 minutes it
 * keeps {@code herd.markets.politics.count} and {@code herd.markets.sports.count} markets ACTIVE per
 * category, topping up whatever closed; a market is the Gamma market's <b>outcome 0</b>, priced by
 * its CLOB token ({@link PolymarketPriceSource}).
 *
 * <p><b>Candidates</b> come from {@code GET /markets/keyset?tag_id=<2 politics | 1 sports>&closed=false&
 * order=volume24hr&ascending=false&limit=50}; for sports the games in play come first
 * ({@code GET /events/keyset?tag_id=1&live=true&closed=false}), then moneyline markets before the
 * rest. A candidate accepts orders, has an order book, has exactly two outcomes, is not closed, ends
 * at least 30 minutes from now, is not in the registry yet (a closed market is never re-listed) and
 * belongs to an event (a game, when there is one) that has no listed market yet — at most one market
 * per event. It is then checked against the live CLOB: the midpoint of outcome 0 in [0.10, 0.90]
 * and the spread {@code ≤ 0.05} (best ask − best bid; measured: {@code BUY} is the bid and
 * {@code SELL} the ask, the docs say the opposite). Degenerate prices (0.0005, 0.9995) and wide
 * books are therefore never listed.
 *
 * <p><b>Closing</b> ({@code registry.close(id, reason, now)}): closed at the source (Gamma
 * {@code closed}, looked up by token at every refresh); the token absent from {@code /midpoints} for
 * 60 s ({@link PolymarketLiveState}, reported by the price source); the midpoint outside
 * [0.03, 0.97]; less than 5 minutes to the end. The last three need no request, they are checked
 * every 5 s from memory by {@link #watch}; a close asks for an early top-up.
 *
 * <p><b>Deviation for games in play</b> (found in the 2026-10-01 samples, not in the brief): for
 * a game the Gamma {@code endDate} is typically the <i>kick-off</i> — {@code unl-den-prt-…} has
 * {@code endDate == gameStartTime == 18:45} and was still live at 19:42 — so "ends in 30 minutes or
 * more" would reject every game in play and "less than 5 minutes to the end" would close a listed
 * game at its kick-off. Therefore, with {@code herd.markets.polymarket.in-play-first} (default
 * {@code true}): a market of a game in play passes the 30-minute rule, and a <i>game</i> market
 * (it has a {@code gameStartTime}) is listed with {@code endsAtMs = null} — its end is unknown, so
 * the 5-minute rule never applies to it; it closes by the source, by the missing token or by its price
 * going to 0/1 as the game ends. With the flag off the rules are applied to every market as written.
 *
 * <p>Runs only with {@code herd.mode=live}, {@code herd.prices.source=live} and
 * {@code herd.markets.polymarket.enabled}.
 */
@ApplicationScoped
@Unremovable
public class PolymarketCatalog {

    private static final Logger LOG = Logger.getLogger(PolymarketCatalog.class);

    /** How often the job wakes up; a refresh happens every {@link #REFRESH_EVERY_MS} or when one is asked for. */
    static final String REFRESH_TICK = "${herd.markets.polymarket.catalog-tick:30s}";
    static final String WATCH_EVERY = "${herd.markets.polymarket.watch-every:5s}";
    static final String JOB_REFRESH = "catalog-polymarket";
    static final String JOB_WATCH = "catalog-polymarket-watch";

    static final long REFRESH_EVERY_MS = 300_000L;
    static final int MARKETS_LIMIT = 50;
    static final int LIVE_EVENTS_LIMIT = 20;
    static final int LOOKUP_BATCH = 20;
    static final int CLOB_BATCH = 200;

    static final String TAG_POLITICS = "2";
    static final String TAG_SPORTS = "1";

    static final long MIN_TIME_TO_END_MS = 30 * 60_000L;
    static final BigDecimal MID_MIN = new BigDecimal("0.10");
    static final BigDecimal MID_MAX = new BigDecimal("0.90");
    static final BigDecimal MAX_SPREAD = new BigDecimal("0.05");
    static final long TOKEN_ABSENT_CLOSE_MS = 60_000L;
    static final BigDecimal CLOSE_BELOW = new BigDecimal("0.03");
    static final BigDecimal CLOSE_ABOVE = new BigDecimal("0.97");
    static final long CLOSE_BEFORE_END_MS = 5 * 60_000L;

    static final String REASON_CLOSED = "closed at the source";
    static final String REASON_ABSENT = "token absent from midpoints for 60 s";
    static final String REASON_PRICE = "midpoint outside [0.03, 0.97]";
    static final String REASON_ENDING = "less than 5 minutes to the end";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MarketRegistry registry;
    private final CatalogConfig config;
    private final PricesConfig prices;
    private final Supplier<PolymarketGammaApi> gamma;
    private final Supplier<PolymarketClobApi> clob;
    private final PolymarketLiveState state;
    private final TickStore store;
    private final LongSupplier clock;
    private volatile long lastRefreshMs = Long.MIN_VALUE;
    private volatile boolean refreshWanted;

    @Inject
    public PolymarketCatalog(MarketRegistry registry, CatalogConfig config, PricesConfig prices,
                             PriceHttpClients clients, PolymarketLiveState state, TickStore store) {
        this(registry, config, prices, clients.gamma(), clients.clob(), state, store, System::currentTimeMillis);
    }

    PolymarketCatalog(MarketRegistry registry, CatalogConfig config, PricesConfig prices,
                      Supplier<PolymarketGammaApi> gamma, Supplier<PolymarketClobApi> clob, PolymarketLiveState state,
                      TickStore store, LongSupplier clock) {
        this.registry = registry;
        this.config = config;
        this.prices = prices;
        this.gamma = gamma;
        this.clob = clob;
        this.state = state;
        this.store = store;
        this.clock = clock;
    }

    private boolean enabled() {
        return config.liveSources() && config.polymarketEnabled();
    }

    /** Every 30 s; refreshes every 5 minutes, sooner after a failure or after a market closed. */
    @Scheduled(every = REFRESH_TICK, delayed = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_REFRESH)
    void refreshJob() {
        if (!enabled()) {
            return;
        }
        long now = clock.getAsLong();
        if (refreshWanted || lastRefreshMs == Long.MIN_VALUE || now - lastRefreshMs >= REFRESH_EVERY_MS) {
            refresh(now);
        }
    }

    /** Every 5 s: closes what the memory says must be closed. */
    @Scheduled(every = WATCH_EVERY, delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_WATCH)
    void watchJob() {
        if (enabled()) {
            watch(clock.getAsLong());
        }
    }

    // ------------------------------------------------------------------------------------ refresh

    /** One refresh: closes what the source closed, then tops each category up. Returns true when every request worked. */
    boolean refresh(long nowMs) {
        lastRefreshMs = nowMs;
        refreshWanted = false;
        boolean ok = true;
        try {
            refreshActive(nowMs);
        } catch (Exception e) {
            LOG.warnf("Polymarket lookup of the listed markets failed: %s", e.toString());
            ok = false;
        }
        for (String category : List.of(Category.POLITICS, Category.SPORTS)) {
            int need = config.oddsCount(category) - activeOf(category).size();
            if (need <= 0) {
                continue;
            }
            try {
                int listed = topUp(category, need, nowMs);
                LOG.debugf("Polymarket %s: %d market(s) wanted, %d listed", category, need, listed);
            } catch (Exception e) {
                LOG.warnf("Polymarket %s catalog could not be read: %s", category, e.toString());
                ok = false;
            }
        }
        if (!ok) {
            refreshWanted = true; // retry at the next 30 s tick; a close during this refresh keeps its own request
        }
        return ok;
    }

    /** Closes the listed markets Gamma reports as closed and refreshes their one-day change. */
    void refreshActive(long nowMs) {
        List<Market> active = activeMarkets();
        for (int from = 0; from < active.size(); from += LOOKUP_BATCH) {
            List<Market> batch = active.subList(from, Math.min(active.size(), from + LOOKUP_BATCH));
            JsonNode answer = gamma.get().marketsByTokens(batch.stream().map(Market::sourceRef).toList(), batch.size());
            for (GammaMarket g : GammaMarket.parseAll(answer == null ? null : answer.path("markets"), null)) {
                for (Market ours : batch) {
                    if (!g.tokenIds().contains(ours.sourceRef())) {
                        continue;
                    }
                    if (g.closed()) {
                        close(ours, REASON_CLOSED, nowMs);
                    } else {
                        state.setOneDayChange(ours.id(), g.oneDayChange());
                    }
                }
            }
        }
    }

    /** Lists up to {@code need} markets of the category; returns how many it listed. */
    int topUp(String category, int need, long nowMs) {
        List<GammaMarket> candidates = candidates(category, nowMs);
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<String, ClobQuote> quotes = readClob(candidates.stream().map(GammaMarket::token).distinct().toList());
        Set<String> takenEvents = takenEvents();
        int listed = 0;
        for (GammaMarket market : candidates) {
            if (listed >= need) {
                break;
            }
            ClobQuote quote = quotes.get(market.token());
            if (quote == null || !quote.acceptable() || takenEvents.contains(market.eventKey())) {
                continue;
            }
            if (list(market, category, nowMs)) {
                takenEvents.add(market.eventKey());
                listed++;
            }
        }
        return listed;
    }

    /**
     * The candidates of a category in the order they are tried: games in play first (sports), then
     * the volume listing — with moneyline markets ahead of the rest for sports. Only markets that
     * pass the rules that need no live price.
     */
    List<GammaMarket> candidates(String category, long nowMs) {
        boolean sports = Category.SPORTS.equals(category);
        List<GammaMarket> ordered = new ArrayList<>();
        if (sports && config.inPlayFirst()) {
            JsonNode live = gamma.get().events(TAG_SPORTS, true, false, "volume24hr", false, LIVE_EVENTS_LIMIT);
            JsonNode events = live == null ? null : live.path("events");
            if (events != null && events.isArray()) {
                for (JsonNode eventNode : events) {
                    GammaEvent event = GammaEvent.parse(eventNode);
                    if (event == null || !event.live() || event.ended()) {
                        continue;
                    }
                    List<GammaMarket> markets = GammaMarket.parseAll(eventNode.path("markets"), event);
                    markets.sort(Comparator.comparing((GammaMarket m) -> !m.isMoneyline())
                            .thenComparing(Comparator.comparingDouble(GammaMarket::volume24hr).reversed()));
                    ordered.addAll(markets);
                }
            }
        }
        JsonNode listing = gamma.get().markets(sports ? TAG_SPORTS : TAG_POLITICS, false, "volume24hr", false,
                MARKETS_LIMIT);
        List<GammaMarket> byVolume = GammaMarket.parseAll(listing == null ? null : listing.path("markets"), null);
        if (sports) {
            byVolume.sort(Comparator.comparing((GammaMarket m) -> !m.isMoneyline())); // stable: volume order is kept
        }
        ordered.addAll(byVolume);

        List<GammaMarket> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (GammaMarket market : ordered) {
            if (seen.add(market.id()) && registry.byId(idOf(market)).isEmpty() && passesStaticRules(market, nowMs)) {
                out.add(market);
            }
        }
        return out;
    }

    /** Accepting orders, an order book, not closed, and ends 30+ minutes from now (a game in play is exempt). */
    boolean passesStaticRules(GammaMarket market, long nowMs) {
        if (!market.acceptingOrders() || !market.enableOrderBook() || market.closed()) {
            return false;
        }
        if (config.inPlayFirst() && market.inPlay()) {
            return true;
        }
        return market.endMs() == null || market.endMs() - nowMs >= MIN_TIME_TO_END_MS;
    }

    /** What the live CLOB says about a token. */
    record ClobQuote(BigDecimal mid, BigDecimal bid, BigDecimal ask) {

        /** Midpoint in [0.10, 0.90] and a two-sided book no wider than 0.05. */
        boolean acceptable() {
            if (mid == null || bid == null || ask == null || ask.compareTo(bid) < 0) {
                return false;
            }
            return mid.compareTo(MID_MIN) >= 0 && mid.compareTo(MID_MAX) <= 0
                    && ask.subtract(bid).compareTo(MAX_SPREAD) <= 0;
        }
    }

    /** {@code POST /midpoints} and {@code POST /prices} for the tokens, in batches; a token the CLOB does not know gets no entry. */
    Map<String, ClobQuote> readClob(List<String> tokens) {
        Map<String, ClobQuote> out = new LinkedHashMap<>();
        for (int from = 0; from < tokens.size(); from += CLOB_BATCH) {
            List<String> batch = tokens.subList(from, Math.min(tokens.size(), from + CLOB_BATCH));
            ArrayNode mids = JsonNodeFactory.instance.arrayNode();
            ArrayNode sides = JsonNodeFactory.instance.arrayNode();
            for (String token : batch) {
                mids.addObject().put("token_id", token);
                sides.addObject().put("token_id", token).put("side", "BUY");
                sides.addObject().put("token_id", token).put("side", "SELL");
            }
            JsonNode midAnswer = clob.get().midpoints(mids);
            JsonNode priceAnswer = clob.get().prices(sides);
            for (String token : batch) {
                JsonNode book = priceAnswer == null ? null : priceAnswer.path(token);
                out.put(token, new ClobQuote(midAnswer == null ? null : Dec.of(midAnswer.path(token)),
                        book == null ? null : Dec.of(book.path("BUY")), book == null ? null : Dec.of(book.path("SELL"))));
            }
        }
        return out;
    }

    private boolean list(GammaMarket g, String category, long nowMs) {
        NewMarket market = toNewMarket(g, category);
        try {
            Market listed = registry.list(market, nowMs);
            state.setOneDayChange(listed.id(), g.oneDayChange());
            LOG.infof("listed %s (%s, %s): %s", listed.id(), category, listed.symbol(), listed.title());
            return true;
        } catch (RuntimeException e) {
            LOG.warnf("could not list Polymarket market %s: %s", g.id(), e.toString());
            return false;
        }
    }

    /** The registry entry of a Gamma market: id {@code PM<gamma id>}, outcome 0, its token as the price reference. */
    NewMarket toNewMarket(GammaMarket g, String category) {
        ObjectNode meta = JsonNodeFactory.instance.objectNode();
        put(meta, "gammaId", g.id());
        put(meta, "slug", g.slug());
        put(meta, "conditionId", g.conditionId());
        put(meta, "outcome", g.outcome());
        put(meta, "eventId", g.event() == null ? null : g.event().id());
        put(meta, "eventTitle", g.event() == null ? null : g.event().title());
        put(meta, "eventKey", g.eventKey());
        put(meta, "gameId", g.event() == null ? null : g.event().gameId());
        put(meta, "gameStartTime", g.gameStartTime());
        put(meta, "sportsMarketType", g.sportsMarketType());
        put(meta, "tickSize", g.tickSize() == null ? null : g.tickSize().toPlainString());
        String eventTitle = g.event() == null ? null : g.event().title();
        String subtitle = eventTitle == null ? g.outcome() : g.outcome() + " · " + eventTitle;
        // A game's endDate is usually its kick-off, not an end: no end is better than a wrong one.
        Long endsAt = config.inPlayFirst() && g.isGame() ? null : g.endMs();
        return new NewMarket(idOf(g), MarketKind.ODDS, category, MarketLabels.symbol(g), g.question(), subtitle,
                g.imageUrl(), PolymarketPriceSource.NAME, g.token(), meta.toString(), config.oddsMaxLeverage(),
                Market.ODDS_LOT_EXP, endsAt);
    }

    private static void put(ObjectNode node, String key, String value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    static String idOf(GammaMarket g) {
        return "PM" + g.id().replaceAll("[^A-Za-z0-9_]", "");
    }

    // -------------------------------------------------------------------------------------- watch

    /** The checks that need no request; returns how many markets it closed. */
    int watch(long nowMs) {
        int closed = 0;
        for (Market market : activeMarkets()) {
            String reason = closeReason(market, nowMs);
            if (reason != null) {
                close(market, reason, nowMs);
                closed++;
            }
        }
        return closed;
    }

    /** Why the market has to close now, or null. */
    String closeReason(Market market, long nowMs) {
        OptionalLong missing = state.missingSince(market.id());
        if (missing.isPresent() && nowMs - missing.getAsLong() >= TOKEN_ABSENT_CLOSE_MS) {
            return REASON_ABSENT;
        }
        Optional<PricePoint> last = store.last(market.id());
        if (last.isPresent() && nowMs - last.get().atMs() <= prices.polymarketStaleMs()
                && (last.get().raw().compareTo(CLOSE_BELOW) < 0 || last.get().raw().compareTo(CLOSE_ABOVE) > 0)) {
            return REASON_PRICE;
        }
        if (market.endsAtMs() != null && market.endsAtMs() - nowMs < CLOSE_BEFORE_END_MS) {
            return REASON_ENDING;
        }
        return null;
    }

    private void close(Market market, String reason, long nowMs) {
        LOG.infof("closing %s (%s): %s", market.id(), market.symbol(), reason);
        registry.close(market.id(), reason, nowMs);
        state.forget(market.id());
        refreshWanted = true; // a category is one short now: top it up at the next 30 s tick
    }

    // ------------------------------------------------------------------------------------ helpers

    private List<Market> activeMarkets() {
        return registry.active().stream().filter(m -> PolymarketPriceSource.NAME.equals(m.source())).toList();
    }

    private List<Market> activeOf(String category) {
        return activeMarkets().stream().filter(m -> category.equals(m.category())).toList();
    }

    /** The events (games) that already have an active Polymarket market, from their {@code sourceMeta}. */
    private Set<String> takenEvents() {
        Set<String> keys = new LinkedHashSet<>();
        for (Market market : activeMarkets()) {
            if (market.sourceMeta() == null) {
                continue;
            }
            try {
                String key = GammaJson.text(JSON.readTree(market.sourceMeta()), "eventKey");
                if (key != null) {
                    keys.add(key);
                }
            } catch (Exception ignored) {
                // a market listed by something else: no event to protect
            }
        }
        return keys;
    }
}
