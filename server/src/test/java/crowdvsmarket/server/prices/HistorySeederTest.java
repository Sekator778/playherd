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
        return PricesConfig.defaults().withDailyRoundMs(seconds * 1000L);
    }

    // ------------------------------------------------------------------------- what the ring gets

    @Test
    void theHistoryLandsInTheRingAndTheRoomHasAChartAtOnceButNoQuoteBeforeTheFirstPoll() {
        source.script = (m, from, to) -> every(10_000, from, to, 84_000);

        seeder.seed(btc);

        assertThat(store.size("BTC")).isEqualTo(91); // 15 minutes every 10 s, both ends
        LiveMarketPrices prices = PipelineTestSupport.livePrices(store, List.of(source), now::get);
        assertThat(store.series("BTC", NOW - 900_000L, NOW + 1)).hasSize(91);
        assertThat(store.feedOf("BTC")).contains("hyperliquid"); // the staleness limit of the quote is its feed's own
        // a seeded point is the past, not what the source says now: it makes no quote, however fresh its time looks
        assertThat(prices.latest("BTC")).isEmpty();
        store.append("hyperliquid", NOW + 1_000L, Map.of("BTC", new BigDecimal("84010")));
        PriceQuote quote = prices.latest("BTC").orElseThrow();
        assertThat(quote.raw()).isEqualByComparingTo("84010");
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
        assertThat(replay.seedMissing(List.of(btc))).isZero();
        HistorySeeder.disabled().seed(btc);
        assertThat(HistorySeeder.disabled().seedMissing(List.of(btc))).isZero();

        assertThat(source.requests).isEmpty();
    }

    @Test
    void thePollSeedsOnlyTheMarketsWithAnEmptyRing() {
        Market eth = TestMarkets.hl("ETH", "ETH", 0);
        store.append("hyperliquid", NOW - 5_000L, Map.of("BTC", new BigDecimal("84000"))); // restored from the snapshots
        source.script = (m, from, to) -> every(10_000, from, to, 2_700);

        int queued = seeder.seedMissing(List.of(btc, eth));

        assertThat(queued).isEqualTo(1);
        assertThat(source.markets).containsExactly("ETH");
        assertThat(store.size("ETH")).isEqualTo(91);
        assertThat(store.size("BTC")).isEqualTo(1);
        assertThat(seeder.seedMissing(List.of(btc, eth))).isZero(); // nothing is empty any more
        assertThat(source.requests).hasSize(1);
    }

    @Test
    void aMarketWhoseRingStaysEmptyIsTriedAgainAMinuteAfterTheLastAttemptThenAfterTwoThenFour() {
        source.script = (m, from, to) -> List.of(); // the source has nothing for it (yet)

        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);
        assertThat(seeder.seedMissing(List.of(btc))).isZero(); // the poll comes every 3 s: not every 3 s
        now.addAndGet(59_999L);
        assertThat(seeder.seedMissing(List.of(btc))).isZero();
        now.addAndGet(1L);
        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);
        assertThat(source.requests).hasSize(2);

        now.addAndGet(119_999L); // the second miss doubles the wait
        assertThat(seeder.seedMissing(List.of(btc))).isZero();
        now.addAndGet(1L);
        source.script = (m, from, to) -> every(10_000, from, to, 84_000); // it has something now
        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);
        now.addAndGet(600_000L);
        assertThat(seeder.seedMissing(List.of(btc))).isZero(); // seeded: the ring is not empty, no more requests
        assertThat(source.requests).hasSize(3);
    }

    @Test
    void aListingAndTheFirstPollDoNotAskTwice() {
        source.script = (m, from, to) -> List.of(); // still empty when the poll comes, a few seconds after the listing

        seeder.seed(btc); // the catalog, right after it listed the market
        now.addAndGet(3_000L);
        seeder.seedMissing(List.of(btc)); // the first poll of its feed

        assertThat(source.requests).hasSize(1);
    }

    // ----------------------------------------------------------------------------------- failures

    @Test
    void aFailingSourceIsAThrottledWarningNotAnErrorAndNotAnException() {
        RecordingLogger log = new RecordingLogger();
        HistorySeeder quiet = new HistorySeeder(new PriceSources(List.of(source)), store, PricesConfig.defaults(),
                now::get, (task, delay) -> task.run(), t -> new long[0], new ThrottledLog(log, now::get, 60_000L));
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
        // closed candles only: the minute in progress (19:42) is not in the answer, so nothing reaches NOW, and a
        // seeded point is no live tick — the lobby shows no quote until the first poll
        List<PricePoint> seeded = store.series("BTC", 0, Long.MAX_VALUE);
        assertThat(seeded.get(seeded.size() - 1).atMs()).isLessThan(NOW);
        assertThat(store.last("BTC")).isEmpty();
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

    // ------------------------------------------- F6: a market with no price at a running window's start

    /** The windows running at NOW on the stand: the DAILY one began at 19:30:00, the HOURLY one at 19:39:00. */
    private static final long DAILY_START = WINDOW_START - 540_000L;
    private static final long[] STARTS = {DAILY_START, WINDOW_START};

    private HistorySeeder withCalendar(PriceSource... sources) {
        return new HistorySeeder(new PriceSources(List.of(sources)), store, PricesConfig.defaults(), now::get,
                (task, delay) -> task.run(), t -> STARTS);
    }

    /** What a source whose history is the same kind of number as its ticks says for any range: 0.50, every 10 s. */
    private static List<PricePoint> midpoints(long fromMs, long toMs) {
        return every(10_000, fromMs, toMs, 0.5);
    }

    @Test
    void aMarketWhoseRingHoldsTicksButNoneFreshAtARunningStartIsSeededToo() {
        // a restart that straddled the HOURLY window's start: the ring has the ticks from before the restart (stale at
        // 19:39:00) and the polls since it (from 19:40:40) — and no tick fresh at the start, so no opening price
        store.append("hyperliquid", WINDOW_START - 100_000L, Map.of("BTC", new BigDecimal("0.7")));
        store.append("hyperliquid", WINDOW_START - 90_000L, Map.of("BTC", new BigDecimal("0.7")));
        store.append("hyperliquid", WINDOW_START + 100_000L, Map.of("BTC", new BigDecimal("0.7")));
        source.script = (m, from, to) -> midpoints(from, to);
        HistorySeeder seeder = withCalendar(source);

        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);

        assertThat(source.requests).hasSize(1);
        // the history landed in the hole around the start, and the ticks that were there are untouched
        assertThat(store.hasTickFreshAt("BTC", WINDOW_START, 15_000)).isTrue();
        assertThat(store.series("BTC", WINDOW_START - 90_001L, WINDOW_START - 89_999L).get(0).raw())
                .isEqualByComparingTo("0.7"); // the live tick that was there, not the history point of the same time
        assertThat(seeder.seedMissing(List.of(btc))).isZero(); // fresh now: nothing more to ask
        assertThat(source.requests).hasSize(1);
    }

    @Test
    void aMarketWithAFreshTickAtEveryRunningStartIsNeverSeeded() {
        store.append("hyperliquid", DAILY_START - 3_000L, Map.of("BTC", new BigDecimal("84000")));
        for (long at = DAILY_START; at <= NOW; at += 3_000L) {
            store.append("hyperliquid", at, Map.of("BTC", new BigDecimal("84000")));
        }

        assertThat(withCalendar(source).seedMissing(List.of(btc))).isZero();

        assertThat(source.requests).isEmpty();
    }

    @Test
    void aStartThatOnlyAPinKnowsIsPricedAndNotSeeded() {
        store.pinBoundary("BTC", WINDOW_START, Px.encode(new BigDecimal("84000")));
        store.pinBoundary("BTC", DAILY_START, Px.encode(new BigDecimal("84000")));

        assertThat(withCalendar(source).seedMissing(List.of(btc))).isZero();
    }

    /** Hyperliquid-shaped: its history is trade prices, so it is only ever the past of the first live tick. */
    private static final class TradeSource implements PriceSource {

        final List<long[]> requests = new ArrayList<>();

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
        public List<PricePoint> history(Market market, long fromMs, long toMs) {
            requests.add(new long[] {fromMs, toMs});
            return every(10_000, fromMs, toMs, 84_000);
        }

        @Override
        public HistoryKind historyKind() {
            return HistoryKind.TRADE_PRICES;
        }
    }

    @Test
    void aTradePriceSourceIsNotAskedWhenALiveTickLiesBeforeTheStartBecauseItsHistoryCouldNotGoThere() {
        TradeSource trades = new TradeSource();
        store.append("hyperliquid", DAILY_START - 100_000L, Map.of("BTC", new BigDecimal("84010"))); // live, stale at both starts
        store.append("hyperliquid", WINDOW_START + 100_000L, Map.of("BTC", new BigDecimal("84020")));

        int queued = withCalendar(trades).seedMissing(List.of(btc));

        assertThat(queued).isZero(); // the hole between two polls is not the past of the first poll: a request would be waste
        assertThat(trades.requests).isEmpty();
    }

    @Test
    void aTradePriceSourceIsAskedWhenNoLiveTickLiesAtOrBeforeTheStartAndFillsOnlyTheTimeBeforeTheFirstOne() {
        TradeSource trades = new TradeSource();
        store.append("hyperliquid", WINDOW_START + 100_000L, Map.of("BTC", new BigDecimal("84020"))); // the first poll

        int queued = withCalendar(trades).seedMissing(List.of(btc));

        assertThat(queued).isEqualTo(1);
        assertThat(trades.requests).hasSize(1);
        assertThat(store.hasTickFreshAt("BTC", WINDOW_START, 15_000)).isTrue();
        assertThat(store.series("BTC", 0, WINDOW_START + 100_000L)).isNotEmpty().allSatisfy(
                p -> assertThat(p.atMs()).isLessThan(WINDOW_START + 100_000L));
        assertThat(store.series("BTC", WINDOW_START + 100_000L, Long.MAX_VALUE)).extracting(PricePoint::atMs)
                .containsExactly(WINDOW_START + 100_000L); // nothing was put over or after the live tick
    }

    @Test
    void eachRunningStartIsTriedOnceAndANewWindowIsATryOfItsOwn() {
        source.script = (m, from, to) -> midpoints(from, to);
        long[][] starts = {STARTS};
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(source)), store, PricesConfig.defaults(),
                now::get, (task, delay) -> task.run(), t -> starts[0]);

        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1); // an empty ring
        assertThat(seeder.seedMissing(List.of(btc))).isZero(); // seeded: fresh at both starts
        // the next HOURLY window began 3 minutes later and the feed has been down since: nothing is fresh at its start
        now.addAndGet(200_000L);
        starts[0] = new long[] {DAILY_START, WINDOW_START + 240_000L};
        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);
        assertThat(source.requests).hasSize(2);
    }

    @Test
    void aSeedStillWaitingInLineIsNeverDoubledByThePoll() {
        List<Runnable> queue = new ArrayList<>();
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(source)), store, PricesConfig.defaults(),
                now::get, (task, delay) -> queue.add(task), t -> STARTS);

        assertThat(seeder.seedMissing(List.of(btc))).isEqualTo(1);
        now.addAndGet(120_000L); // the polls go on, the seed has not run
        assertThat(seeder.seedMissing(List.of(btc))).isZero();
        assertThat(seeder.seedMissing(List.of(btc))).isZero();

        assertThat(queue).hasSize(1);
    }

    // ------------------------------------------ L3: the seeds of a start-up are staggered (the budget)

    private List<long[]> scheduleTwelveHyperliquidSeeds(PricesConfig config) {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid(); // never asked: the seeds are only scheduled
        HyperliquidPriceSource hl = PipelineTestSupport.hyperliquid(api, now::get);
        List<long[]> scheduled = new ArrayList<>(); // {delay, weight}
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(hl)), store, config, now::get,
                (task, delay) -> scheduled.add(new long[] {delay, hl.historyWeight(seederSpan(config))}), t -> STARTS);
        for (int i = 0; i < 12; i++) {
            seeder.seed(TestMarkets.hl("M" + i, "COIN" + i, 0));
        }
        return scheduled;
    }

    private static long seederSpan(PricesConfig config) {
        return Math.min(HistorySeeder.MAX_SEED_MS, Math.max(HistorySeeder.MIN_SEED_MS, config.dailyRoundMs()));
    }

    @Test
    void twelveSeedsOnTheStandAreFiveSecondsApartAndTheirFirstMinuteStaysUnderTheBudget() {
        PricesConfig stand = PricesConfig.defaults(); // rounds of 3 and 15 minutes: a seed is 45 rows, weight 21

        List<long[]> scheduled = scheduleTwelveHyperliquidSeeds(stand);

        assertThat(scheduled).extracting(s -> s[0]).containsExactly(0L, 5_000L, 10_000L, 15_000L, 20_000L, 25_000L,
                30_000L, 35_000L, 40_000L, 45_000L, 50_000L, 55_000L);
        long inTheFirstMinute = scheduled.stream().filter(s -> s[0] < 60_000L).mapToLong(s -> s[1]).sum();
        assertThat(inTheFirstMinute).isEqualTo(12 * 21).isLessThanOrEqualTo(stand.hyperliquidSeedBudgetPerMinute());
        assertThat(inTheFirstMinute + 600 + 40).as("polling 600, catalog 40: 892 of the 1200").isEqualTo(892);
    }

    @Test
    void twelveSeedsWithTheProductionDurationsAreTwelveSecondsApartAndFiveFitInTheFirstMinute() {
        PricesConfig production = PricesConfig.defaults().withDailyRoundMs(86_400_000L); // a seed is a day: weight 45

        List<long[]> scheduled = scheduleTwelveHyperliquidSeeds(production);

        assertThat(scheduled).extracting(s -> s[0]).startsWith(0L, 12_000L, 24_000L, 36_000L, 48_000L, 60_000L)
                .endsWith(132_000L);
        long inTheFirstMinute = scheduled.stream().filter(s -> s[0] < 60_000L).mapToLong(s -> s[1]).sum();
        assertThat(inTheFirstMinute).isEqualTo(5 * 45); // unstaggered: 12 x 45 = 540 and 1180 of 1200 with the rest
        assertThat(inTheFirstMinute + 600 + 40).as("865 of the 1200").isEqualTo(865).isLessThan(900);
        assertThat(12 * 45 + 600 + 40).as("what it was").isEqualTo(1180);
    }

    @Test
    void theSpacingFollowsTheBudgetSettingAndTheWeightOfTheSeed() {
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(source), store, now::get);

        assertThat(seeder.spacingMs(21)).isEqualTo(5_000L); // floor(260 / 21) = 12 a minute
        assertThat(seeder.spacingMs(45)).isEqualTo(12_000L); // floor(260 / 45) = 5 a minute
        assertThat(seeder.spacingMs(300)).as("one a minute at least").isEqualTo(60_000L);
        assertThat(seeder.spacingMs(0)).as("a source with no weight").isEqualTo(HistorySeeder.OTHER_SPACING_MS);
        HistorySeeder tight = new HistorySeeder(new PriceSources(List.of(source)), store,
                PricesConfig.defaults().withHyperliquidSeedBudget(105), now::get, Runnable::run);
        assertThat(tight.spacingMs(21)).isEqualTo(12_000L); // floor(105 / 21) = 5 a minute
    }

    @Test
    void theQueuesOfTwoSourcesDoNotDelayEachOther() {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        HyperliquidPriceSource hl = PipelineTestSupport.hyperliquid(api, now::get);
        FakeApis.Data data = new FakeApis.Data();
        PolymarketPriceSource pm = PipelineTestSupport.polymarket(new FakeApis.Clob(), data, new PolymarketLiveState(),
                now::get);
        List<long[]> scheduled = new ArrayList<>(); // {delay, market index}
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(hl, pm)), store, PricesConfig.defaults(),
                now::get, (task, delay) -> scheduled.add(new long[] {delay}), t -> STARTS);

        seeder.seed(TestMarkets.hl("A", "A", 0));
        seeder.seed(TestMarkets.hl("B", "B", 0));
        seeder.seed(TestMarkets.odds("PM1", "tok1"));
        seeder.seed(TestMarkets.odds("PM2", "tok2"));

        assertThat(scheduled).extracting(s -> s[0]).containsExactly(0L, 5_000L, 0L, 1_000L);
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
