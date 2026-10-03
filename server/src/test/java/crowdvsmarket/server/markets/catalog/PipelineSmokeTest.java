package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.FakeApis;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.LiveMarketPrices;
import crowdvsmarket.server.prices.PipelineTestSupport;
import crowdvsmarket.server.prices.PolymarketLiveState;
import crowdvsmarket.server.prices.PriceFeedService;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.prices.PriceSource;
import crowdvsmarket.server.prices.RoundPriceData;
import crowdvsmarket.server.prices.TickStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The whole chain on the saved answers of 2026-10-01 and a clock that advances by the polling
 * periods, with no network: the catalogs list markets from the sources' own parameters, the feed
 * service prices exactly those markets (the catalog's {@code source}/{@code sourceRef} and the feeds'
 * predicates agree), rounds settle from the ticks the polls left, and a market that drops out of the
 * CLOB's answer is closed by the catalog's watch and no longer polled.
 */
class PipelineSmokeTest {

    private static final long T0 = Fixtures.NOW - 600_000L; // ten minutes before the fixtures' capture time

    private final AtomicLong now = new AtomicLong(T0);
    private final FakeMarketRegistry registry = new FakeMarketRegistry();
    private final FakeApis.Hyperliquid hl = new FakeApis.Hyperliquid();
    private final FakeApis.Clob clob = new FakeApis.Clob();
    private final FakeApis.Gamma gamma = new FakeApis.Gamma();
    private final FakeApis.Data data = new FakeApis.Data();
    private final PolymarketLiveState state = new PolymarketLiveState();
    private final TickStore store = Fixtures.tickStore(now::get);

    private PolymarketCatalog polymarketCatalog;
    private PriceFeedService feeds;
    private LiveMarketPrices prices;
    private ClobBook book;

    @BeforeEach
    void setUp() {
        JsonNode main = Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
        JsonNode xyz = Fixtures.json("hyperliquid/meta_and_asset_ctxs_xyz.json");
        hl.info.handler = body -> body.has("dex") ? xyz : main;

        JsonNode politics = Fixtures.json("polymarket/gamma_markets_politics.json");
        JsonNode sports = Fixtures.json("polymarket/gamma_markets_sports.json");
        JsonNode live = Fixtures.json("polymarket/gamma_events_live.json");
        gamma.markets.handler = call -> "2".equals(call.tagId()) ? politics : sports;
        gamma.events.handler = call -> live;
        gamma.byTokens.handler = call -> Fixtures.parse("{\"markets\":[]}");
        book = ClobBook.of(politics.get("markets"), sports.get("markets"), live.get("events"));
        book.install(clob);

        PriceSource hyperliquid = PipelineTestSupport.hyperliquid(hl, now::get);
        PriceSource polymarket = PipelineTestSupport.polymarket(clob, data, state, now::get);
        feeds = PipelineTestSupport.feedService(List.of(hyperliquid, polymarket), store, registry, now::get);
        prices = PipelineTestSupport.livePrices(store, List.of(hyperliquid, polymarket), now::get);
        polymarketCatalog = new PolymarketCatalog(registry, CatalogConfig.defaults(),
                crowdvsmarket.server.prices.PricesConfig.defaults(), () -> gamma, () -> clob, state, store, now::get);

        new HyperliquidCatalog(registry, TestCatalogConfigs.withCryptoPerpetuals(), () -> hl, now::get).sync(now.get());
        polymarketCatalog.refresh(now.get());
    }

    /** Polls every feed for {@code seconds} seconds of the clock: the default dex and Polymarket every 3 s, xyz every 6 s. */
    private void runFor(long seconds) {
        for (long s = 3; s <= seconds; s += 3) {
            now.addAndGet(3_000);
            PipelineTestSupport.poll(feeds, "hyperliquid");
            if (s % 6 == 0) {
                PipelineTestSupport.poll(feeds, "hyperliquid:xyz");
            }
            PipelineTestSupport.poll(feeds, "polymarket");
        }
    }

    private RoundWindow window(Market market, long startMs, long lengthMs) {
        return new RoundWindow(market.id(), Interval.HOURLY, startMs, startMs + lengthMs);
    }

    @Test
    void theCatalogsListAndThePollsPriceExactlyThoseMarkets() {
        assertThat(registry.active()).extracting(Market::id).contains("BTC", "ETH", "SOL", "DOGE", "xyz_NVDA", "xyz_SP500",
                "PM665374", "PM601826", "PM4678642");

        runFor(120);

        for (Market market : registry.active()) {
            assertThat(prices.latest(market.id())).as(market.id()).isPresent();
            PriceQuote quote = prices.latest(market.id()).orElseThrow();
            assertThat(quote.stale()).as(market.id() + " stale").isFalse();
            assertThat(quote.asOfMs()).isEqualTo(now.get());
        }
        assertThat(prices.latest("ETH").orElseThrow().raw()).isEqualByComparingTo("2697.0");
        assertThat(prices.latest("ETH").orElseThrow().change24h()).isNotNull();
        assertThat(prices.latest("xyz_NVDA").orElseThrow().raw()).isEqualByComparingTo("231.7");
        assertThat(prices.latest("PM665374").orElseThrow().raw()).isEqualByComparingTo("0.145");
        // the Hyperliquid answers were asked for once per poll of their dex, the CLOB once per poll
        assertThat(hl.requestsOfType("metaAndAssetCtxs").stream().filter(r -> !r.has("dex"))).hasSizeGreaterThan(30);
        assertThat(clob.midpoints.requests.size()).isGreaterThan(30);
    }

    @Test
    void aRoundSettlesFromThePollsInEngineLotUnits() {
        runFor(30); // some polls first: the open price is the last tick at or before the start
        long start = now.get();
        runFor(190);
        long length = 180_000L;

        RoundPriceData eth = prices.resolve(registry.require("ETH"), window(registry.require("ETH"), start, length));
        RoundPriceData nvda = prices.resolve(registry.require("xyz_NVDA"), window(registry.require("xyz_NVDA"), start, length));
        RoundPriceData iran = prices.resolve(registry.require("PM665374"), window(registry.require("PM665374"), start, length));

        assertThat(eth.open()).isEqualByComparingTo("2697.00"); // ETH: lot exponent 0
        assertThat(eth.close()).isEqualByComparingTo("2697.00");
        assertThat(nvda.open()).isEqualByComparingTo("2317.00"); // NVDA 231.7 at lot exponent 1
        assertThat(iran.open()).isEqualByComparingTo("1450.00"); // 0.145 at the ODDS lot exponent 4
        assertThat(iran.close()).isEqualByComparingTo("1450.00");
        for (RoundPriceData data : List.of(eth, nvda, iran)) {
            assertThat(data.path()).hasSize(30);
            assertThat(data.path()).allSatisfy(c -> assertThat(c.low().signum()).isPositive());
            assertThat(data.path().get(29).close()).isEqualByComparingTo(data.close());
        }
        assertThat(eth.path().stream().map(Candle::high).distinct().count()).isEqualTo(1); // a flat fixture price
    }

    @Test
    void aMarketThatLeavesTheClobsAnswerIsClosedAfterSixtySecondsAndNoLongerPolled() {
        runFor(30);
        Market iran = registry.require("PM665374");
        String token = iran.sourceRef();
        assertThat(polymarketCatalog.watch(now.get())).isZero();

        book.remove(token); // the CLOB stops answering for it, as it does for a market that resolved
        runFor(57);
        assertThat(polymarketCatalog.watch(now.get())).isZero(); // 57 s: not yet
        runFor(6);
        assertThat(polymarketCatalog.watch(now.get())).isEqualTo(1); // more than 60 s since the first poll without it

        assertThat(registry.closeReasons).containsEntry("PM665374", PolymarketCatalog.REASON_ABSENT);
        assertThat(registry.active()).extracting(Market::id).doesNotContain("PM665374").contains("PM601826");
        int requestsBefore = clob.midpoints.requests.size();
        runFor(3);
        // the next poll's body no longer carries the closed market's token
        String lastBody = clob.midpoints.requests.get(clob.midpoints.requests.size() - 1).toString();
        assertThat(clob.midpoints.requests.size()).isGreaterThan(requestsBefore);
        assertThat(lastBody).doesNotContain(token);
    }

    @Test
    void theCategoriesAreFilledAsConfiguredAndCryptoStartsWithBtc() {
        assertThat(registry.active().stream().filter(m -> m.category().equals(Category.POLITICS)).count()).isEqualTo(3);
        assertThat(registry.active().stream().filter(m -> m.category().equals(Category.SPORTS)).count()).isEqualTo(3);
        assertThat(registry.active().stream().filter(m -> m.category().equals(Category.CRYPTO)).map(Market::id).toList())
                .containsExactly("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE");
        assertThat(registry.categories()).extracting(Category::id).containsExactly("crypto", "politics", "sports",
                "stocks", "indices", "commodities");
        assertThat(registry.active().stream().map(Market::oracleIndex).distinct().count())
                .isEqualTo(registry.active().size()); // every market has its own oracle index
        assertThat(registry.require("DOGE").lotExp()).isEqualTo(5);
    }
}
