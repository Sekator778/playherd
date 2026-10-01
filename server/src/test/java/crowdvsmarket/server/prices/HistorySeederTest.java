package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * G1: a market gets its recent past the moment it is listed (and at start-up, when its ring is
 * empty), so that the first running round has its opening price at once. Against a scripted source,
 * against the real Hyperliquid source over canned candle rows, and against the synthetic walk; and
 * the promise that the request waits for the source without holding anything a reader needs.
 */
class HistorySeederTest {

    /** 2026-10-01T19:42:00Z; the stand's 3-minute rounds start on :00, :03, … so a window starts at 19:39:00. */
    private static final long NOW = 1_790_883_720_000L;
    private static final long WINDOW_START = 1_790_883_540_000L; // 19:39:00
    private static final long WINDOW_END = WINDOW_START + 180_000L;

    private interface Script {
        List<PricePoint> history(Market market, long fromMs, long toMs) throws Exception;
    }

    /** A source whose history is whatever the test scripts; it records what it was asked. */
    private static final class ScriptedSource implements PriceSource {

        final List<long[]> requests = new ArrayList<>();
        final List<String> markets = new ArrayList<>();
        Script script = (m, from, to) -> List.of();

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> m.source().equals("hyperliquid")));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws Exception {
            requests.add(new long[] {fromMs, toMs});
            markets.add(market.id());
            return script.history(market, fromMs, toMs);
        }
    }

    private final AtomicLong now = new AtomicLong(NOW);
    private final Market btc = TestMarkets.btc();
    private TickStore store;
    private ScriptedSource source;
    private HistorySeeder seeder;

    @BeforeEach
    void setUp() {
        store = Fixtures.tickStore(now::get);
        source = new ScriptedSource();
        seeder = PipelineTestSupport.seeder(List.of(source), store, now::get);
    }

    private static List<PricePoint> every(long stepMs, long fromMs, long toMs, double price) {
        List<PricePoint> out = new ArrayList<>();
        for (long at = fromMs; at <= toMs; at += stepMs) {
            out.add(new PricePoint(at, BigDecimal.valueOf(price)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------- what is asked

    @Test
    void aListedMarketIsSeededWithTheLastFifteenMinutesOfItsSourcesHistory() {
        seeder.seed(btc);

        assertThat(source.requests).hasSize(1);
        assertThat(source.requests.get(0)).containsExactly(NOW - 900_000L, NOW); // the stand's DAILY round: 15 minutes
        assertThat(source.markets).containsExactly("BTC");
    }

    @Test
    void theSpanFollowsTheDailyRoundButNeverBelowFifteenMinutesNorAboveADay() {
        PricesConfig hour = PricesConfig.defaults(); // 900 s
        assertThat(new HistorySeeder(new PriceSources(List.of(source)), store, hour, now::get, Runnable::run).seedSpanMs())
                .isEqualTo(900_000L);
        PricesConfig day = withDaily(86_400L);
        assertThat(new HistorySeeder(new PriceSources(List.of(source)), store, day, now::get, Runnable::run).seedSpanMs())
                .isEqualTo(86_400_000L); // a live DAILY round: a window that started this long ago has its open in it
        assertThat(new HistorySeeder(new PriceSources(List.of(source)), store, withDaily(7 * 86_400L), now::get,
                Runnable::run).seedSpanMs()).isEqualTo(86_400_000L);
        assertThat(new HistorySeeder(new PriceSources(List.of(source)), store, withDaily(60L), now::get, Runnable::run)
                .seedSpanMs()).isEqualTo(900_000L);
    }

    private static PricesConfig withDaily(long seconds) {
        PricesConfig d = PricesConfig.defaults();
        return new PricesConfig(d.liveMode(), d.source(), seconds * 1000L, d.hyperliquidUrl(), d.polymarketClobUrl(),
                d.polymarketGammaUrl(), d.polymarketDataUrl(), d.hyperliquidStaleMs(), d.hyperliquidXyzStaleMs(),
                d.hyperliquidMaxGapMs(), d.polymarketStaleMs(), d.polymarketMaxGapMs(), d.syntheticStaleMs(),
                d.syntheticMaxGapMs(), d.backfillCooldownMs(), d.connectTimeoutMs(), d.readTimeoutMs());
    }

    // ------------------------------------------------------------------------- what the ring gets

    @Test
    void theHistoryLandsInTheRingAndTheLobbyHasAPriceAndAChartAtOnce() {
        source.script = (m, from, to) -> every(10_000, from, to, 84_000);

        seeder.seed(btc);

        assertThat(store.size("BTC")).isEqualTo(91); // 15 minutes every 10 s, both ends
        PriceQuote quote = PipelineTestSupport.livePrices(store, List.of(source), now::get).latest("BTC").orElseThrow();
        assertThat(quote.raw()).isEqualByComparingTo("84000");
        assertThat(quote.stale()).isFalse();
        assertThat(store.series("BTC", NOW - 900_000L, NOW + 1)).hasSize(91);
        assertThat(store.feedOf("BTC")).contains("hyperliquid"); // the staleness limit of the quote is its feed's own
    }

    @Test
    void theFirstRunningRoundHasItsOpeningPriceAndItsPathSoFarAtOnce() {
        source.script = (m, from, to) -> every(10_000, from, to, 84_000);
        LiveMarketPrices prices = PipelineTestSupport.livePrices(store, List.of(source), now::get);
        RoundWindow running = new RoundWindow("BTC", Interval.HOURLY, WINDOW_START, WINDOW_END);
        long midRound = WINDOW_START + 100_000L; // the market is listed 100 s into the round that started at 19:39:00
        assertThat(prices.soFar(btc, running, midRound)).isEmpty(); // before: a market listed mid-window has no open

        seeder.seed(btc);

        RoundPriceData data = prices.soFar(btc, running, midRound).orElseThrow();
        assertThat(data.open()).isEqualByComparingTo("84000.00");
        assertThat(data.path()).isNotEmpty();
        assertThat(data.sourceTsMs()).isEqualTo(midRound); // the newest point at or before "now"
    }

    @Test
    void historyIsNeverMergedAtOrAfterTheOldestLiveTick() {
        store.append("hyperliquid", NOW - 300_000L, Map.of("BTC", new BigDecimal("90000"))); // a poll got in first
        source.script = (m, from, to) -> every(10_000, from, to, 84_000);

        seeder.seed(btc);

        // 5 minutes of history before the live tick, none over it or after it: the live ticks are authoritative
        assertThat(store.series("BTC", 0, NOW - 300_000L)).hasSize(60).allSatisfy(
                p -> assertThat(p.raw()).isEqualByComparingTo("84000"));
        assertThat(store.series("BTC", NOW - 300_000L, NOW + 1)).extracting(PricePoint::atMs)
                .containsExactly(NOW - 300_000L);
        assertThat(store.last("BTC").orElseThrow().raw()).isEqualByComparingTo("90000");
    }

    @Test
    void aTickIsNeverReplacedAndPointsTheEngineCannotHoldAreDropped() {
        Market doge = TestMarkets.hl("DOGE", "DOGE", 5);
        store.append("hyperliquid", NOW - 600_000L, Map.of("DOGE", new BigDecimal("0.0947")));
        source.script = (m, from, to) -> List.of(new PricePoint(NOW - 900_000L, new BigDecimal("0.0941")),
                new PricePoint(NOW - 700_000L, new BigDecimal("0")), // not a price
                new PricePoint(NOW - 650_000L, new BigDecimal("0.00000001")), // a lot price of 0.00 at exponent 5
                new PricePoint(NOW - 600_000L, new BigDecimal("0.5")), // the live tick's own time: yields to it
                new PricePoint(NOW - 100_000L, new BigDecimal("0.5"))); // after it: not merged

        seeder.seed(doge);

        assertThat(store.series("DOGE", 0, NOW + 1)).extracting(PricePoint::atMs)
                .containsExactly(NOW - 900_000L, NOW - 600_000L);
        assertThat(store.series("DOGE", NOW - 600_000L, NOW + 1).get(0).raw()).isEqualByComparingTo("0.0947");
    }

    // --------------------------------------------------------------------------------- what is left alone

    @Test
    void aMarketTheSourcesDoNotHandleIsLeftAlone() {
        Market odds = TestMarkets.odds("PM1", "tok"); // source "polymarket": the scripted source handles hyperliquid only

        seeder.seed(odds);

        assertThat(source.requests).isEmpty();
        assertThat(store.size("PM1")).isZero();
    }

    @Test
    void outsideLiveModeNothingIsAsked() {
        HistorySeeder replay = new HistorySeeder(new PriceSources(List.of(source)), store,
                PricesConfig.defaults().withMode(false, PricesConfig.SourceKind.LIVE), now::get, Runnable::run);

        replay.seed(btc);
        assertThat(replay.seedEmpty(List.of(btc))).isZero();
        HistorySeeder.disabled().seed(btc);
        assertThat(HistorySeeder.disabled().seedEmpty(List.of(btc))).isZero();

        assertThat(source.requests).isEmpty();
    }

    @Test
    void thePollSeedsOnlyTheMarketsWithAnEmptyRing() {
        Market eth = TestMarkets.hl("ETH", "ETH", 0);
        store.append("hyperliquid", NOW - 5_000L, Map.of("BTC", new BigDecimal("84000"))); // restored from the snapshots
        source.script = (m, from, to) -> every(10_000, from, to, 2_700);

        int queued = seeder.seedEmpty(List.of(btc, eth));

        assertThat(queued).isEqualTo(1);
        assertThat(source.markets).containsExactly("ETH");
        assertThat(store.size("ETH")).isEqualTo(91);
        assertThat(store.size("BTC")).isEqualTo(1);
        assertThat(seeder.seedEmpty(List.of(btc, eth))).isZero(); // nothing is empty any more
        assertThat(source.requests).hasSize(1);
    }

    @Test
    void aMarketWhoseRingStaysEmptyIsTriedAgainOnlyAMinuteAfterTheLastAttempt() {
        source.script = (m, from, to) -> List.of(); // the source has nothing for it (yet)

        assertThat(seeder.seedEmpty(List.of(btc))).isEqualTo(1);
        assertThat(seeder.seedEmpty(List.of(btc))).isZero(); // the poll comes every 3 s: not every 3 s
        now.addAndGet(59_999L);
        assertThat(seeder.seedEmpty(List.of(btc))).isZero();
        now.addAndGet(1L);
        assertThat(seeder.seedEmpty(List.of(btc))).isEqualTo(1);
        assertThat(source.requests).hasSize(2);

        source.script = (m, from, to) -> every(10_000, from, to, 84_000); // it has something now
        now.addAndGet(60_000L);
        assertThat(seeder.seedEmpty(List.of(btc))).isEqualTo(1);
        now.addAndGet(120_000L);
        assertThat(seeder.seedEmpty(List.of(btc))).isZero(); // seeded: the ring is not empty, no more requests
        assertThat(source.requests).hasSize(3);
    }

    @Test
    void aListingAndTheFirstPollDoNotAskTwice() {
        source.script = (m, from, to) -> List.of(); // still empty when the poll comes, a few seconds after the listing

        seeder.seed(btc); // the catalog, right after it listed the market
        now.addAndGet(3_000L);
        seeder.seedEmpty(List.of(btc)); // the first poll of its feed

        assertThat(source.requests).hasSize(1);
    }

    // ----------------------------------------------------------------------------------- failures

    @Test
    void aFailingSourceIsAThrottledWarningNotAnErrorAndNotAnException() {
        RecordingLogger log = new RecordingLogger();
        HistorySeeder quiet = new HistorySeeder(new PriceSources(List.of(source)), store, PricesConfig.defaults(),
                now::get, Runnable::run, new ThrottledLog(log, now::get, 60_000L));
        source.script = (m, from, to) -> {
            throw new java.io.IOException("connection refused");
        };

        quiet.seed(btc);
        now.addAndGet(5_000L);
        quiet.seed(btc);
        quiet.seed(btc); // three failures within a minute: one line
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("BTC").contains("connection refused");
        assertThat(store.size("BTC")).isZero(); // the market starts with the live polls

        now.addAndGet(61_000L);
        quiet.seed(btc);
        assertThat(log.warnings()).hasSize(2);
        assertThat(log.warnings().get(1)).contains("2 similar");

        // the source is back: the recovery is said once
        source.script = (m, from, to) -> every(10_000, from, to, 84_000);
        quiet.seed(btc);
        assertThat(log.infos()).anyMatch(line -> line.contains("recovered") && line.contains("BTC"));
        assertThat(store.size("BTC")).isPositive();
    }

    // ---------------------------------------------------------------- the real sources, no network

    @Test
    void theRealHyperliquidSourceGivesTheFirstRoundItsOpenFromThePreviousMinutesCloseAtOnce() {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> candleRows(body.get("req").get("startTime").asLong(),
                body.get("req").get("endTime").asLong());
        HyperliquidPriceSource hl = PipelineTestSupport.hyperliquid(api, now::get);
        HistorySeeder real = PipelineTestSupport.seeder(List.of(hl), store, now::get);
        LiveMarketPrices prices = PipelineTestSupport.livePrices(store, List.of(hl), now::get);
        RoundWindow running = new RoundWindow("BTC", Interval.HOURLY, WINDOW_START, WINDOW_END);

        real.seed(btc);

        // one request: the last 15 minutes plus the source's own 30 minutes of lookback, up to now
        assertThat(api.info.requests).hasSize(1);
        assertThat(api.info.requests.get(0).get("req").get("coin").asText()).isEqualTo("BTC");
        RoundPriceData data = prices.soFar(btc, running, NOW).orElseThrow();
        // the minute 19:38 closes at 84000 + 38 + 0.5 and the minute 19:39 opens at 84000 + 39: the price at 19:39:00
        // is the close of the minute before it (the boundary convention of the Hyperliquid history)
        assertThat(data.open()).isEqualByComparingTo(
                new BigDecimal("84038.5")); // lot exponent 0 of BTC: 84038.50 rounds to 84038.50 at scale 2
        assertThat(store.last("BTC").orElseThrow().atMs()).isLessThanOrEqualTo(NOW);
    }

    /** One candle per minute from the one containing {@code startMs} to the one containing {@code endMs}, up 0.5 each. */
    private static JsonNode candleRows(long startMs, long endMs) {
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (long t = Math.floorDiv(startMs, 60_000L) * 60_000L; t <= endMs; t += 60_000L) {
            long minute = Math.floorDiv(t - WINDOW_START, 60_000L);
            double open = 84_000 + 39 + minute;
            rows.addObject().put("t", t).put("T", t + 59_999).put("s", "BTC").put("i", "1m")
                    .put("o", String.valueOf(open)).put("c", String.valueOf(open + 0.5))
                    .put("h", String.valueOf(open + 1)).put("l", String.valueOf(open - 1)).put("n", 10);
        }
        return rows;
    }

    @Test
    void theSyntheticWalkIsSeededToo() {
        SyntheticPriceSource walk = new SyntheticPriceSource(store, PricesConfig.defaults()
                .withMode(true, PricesConfig.SourceKind.SYNTHETIC), now::get);
        Market eth = TestMarkets.synthetic("ETH", MarketKind.PERP, "ETH", 0, "{\"start\":\"2700\"}");
        HistorySeeder synthetic = new HistorySeeder(new PriceSources(List.of(walk)), store,
                PricesConfig.defaults(), now::get, Runnable::run);

        synthetic.seed(eth);

        assertThat(store.size("ETH")).isEqualTo(901); // one point per second for 15 minutes, both ends
        assertThat(store.feedOf("ETH")).contains("synthetic");
        RoundWindow running = new RoundWindow("ETH", Interval.HOURLY, WINDOW_START, WINDOW_END);
        assertThat(PipelineTestSupport.livePrices(store, List.of(walk), now::get).soFar(eth, running, NOW)).isPresent();
    }

    // ----------------------------------------------------- no lock is held while the source answers

    @Test
    void whileTheSourceIsAnsweringNothingAReaderOrThePollerNeedsIsHeld() throws Exception {
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.script = (m, from, to) -> {
            asked.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return every(10_000, from, to, 84_000);
        };
        AtomicLong wall = new AtomicLong(System.currentTimeMillis());
        TickStore live = Fixtures.tickStore(wall::get);
        // the real background executor: the seed waits for the source on the "price-history-seed" thread
        HistorySeeder background = new HistorySeeder(new PriceSources(List.of(source)), live, PricesConfig.defaults(),
                wall::get, null);
        LiveMarketPrices prices = PipelineTestSupport.livePrices(live, List.of(source), wall::get);
        ExecutorService others = Executors.newSingleThreadExecutor();
        try {
            background.seed(btc);
            assertThat(asked.await(10, TimeUnit.SECONDS)).isTrue(); // the seed is now blocked in the source

            Future<?> readers = others.submit(() -> {
                // what the game service does under the engine's read lock, and what the poller does
                prices.latest("BTC");
                prices.series("BTC", 0, Long.MAX_VALUE);
                prices.soFar(btc, new RoundWindow("BTC", Interval.HOURLY, WINDOW_START, WINDOW_END), wall.get());
                live.append("hyperliquid", wall.get(), Map.of("BTC", new BigDecimal("84010")));
                return null;
            });
            readers.get(5, TimeUnit.SECONDS); // would time out if the seed held a lock they take

            release.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> live.size("BTC") > 5);
            // the live tick that arrived meanwhile stays: the history ends before it
            assertThat(live.last("BTC").orElseThrow().raw()).isEqualByComparingTo("84010");
        } finally {
            release.countDown();
            others.shutdownNow();
            background.shutdown();
        }
    }
}
