package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kraken serves the latest 720 one-minute candles whatever {@code since} is (measured 2026-10-03: {@code since} a day back
 * answered 721 rows, the first 12.0 h old; see {@link KrakenOhlcTape}). What lies before them is <i>no data</i>: a seed that
 * asks for a day is done with the twelve hours that exist and is not repeated, a hole older than that is not bridged by
 * candles that are not there and not carried across as a quiet asset's flat price — the source's silence about it says
 * nothing — and a hole inside the twelve hours still is bridged. The real {@link KrakenPriceSource} over a tape that
 * answers as the API does; nothing touches a network.
 */
class KrakenHistoryDepthTest {

    /** 2026-10-03T12:31:00Z, on a minute. */
    private static final long T0 = 1_791_030_660_000L;
    private static final long HOUR = 3_600_000L;
    private static final long STEP = 180_000L;

    private final AtomicLong now = new AtomicLong(T0 + 13 * HOUR);
    /** A market on Kraken's own ticks and candles; a round window needs a legal market id, which a backup's shadow has not. */
    private final Market market = TestMarkets.kraken("BTC", 0, 1);
    private final FakeApis.Kraken api = new FakeApis.Kraken();
    private final KrakenOhlcTape tape = KrakenOhlcTape.flat("BTC/USD", 100.0);

    private TickStore store;
    private KrakenPriceSource kraken;

    @BeforeEach
    void setUp() {
        store = Fixtures.tickStore(now::get);
        kraken = PipelineTestSupport.kraken(api, new FakeWebSocketTransport(), now::get);
        api.ohlc.handler = call -> tape.answer(call.sinceSeconds(), now.get());
    }

    private static long minuteOf(long ms) {
        return Math.floorDiv(ms, 60_000L) * 60_000L;
    }

    // ------------------------------------------------------------------------------------------ the seed

    @Test
    void aSeedThatAsksForADayIsDoneWithTheTwelveHoursThatExistAndIsNotRepeatedEveryFewMinutes() {
        // production's DAILY round is a day: the window that began 20 h ago is running, and Kraken keeps 12 h
        long daily = minuteOf(now.get()) - 20 * HOUR;
        long hourly = minuteOf(now.get()) - 5 * 60_000L;
        long[] starts = {daily, hourly};
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(kraken)), store,
                PricesConfig.defaults().withDailyRoundMs(24 * HOUR), now::get, (task, delay) -> task.run(), t -> starts);

        assertThat(seeder.seedMissing(List.of(market))).isEqualTo(1);

        assertThat(api.ohlc.requests).as("one request for the whole seed").hasSize(1);
        assertThat(store.hasTickFreshAt(market.id(), hourly, 15_000)).as("priced where Kraken has data").isTrue();
        assertThat(store.hasTickFreshAt(market.id(), daily, 15_000)).as("20 h ago is no data at all").isFalse();
        for (int minute = 1; minute <= 40; minute++) { // the polls go on every few seconds, the minutes pass
            now.addAndGet(60_000L);
            assertThat(seeder.seedMissing(List.of(market))).as("%d min later: nothing a seed could give is missing", minute)
                    .isZero();
        }
        assertThat(api.ohlc.requests).as("never asked again for what Kraken does not have").hasSize(1);
    }

    @Test
    void aStartInsideWhatKrakenKeepsIsStillRetriedWhenTheSeedBroughtNoPriceForIt() {
        // an answer with no rows at all is no sign of a limit: Kraken has not said it keeps nothing older, so a seed that
        // left a start unpriced is retried a minute later as before
        long hourly = minuteOf(now.get()) - 5 * 60_000L;
        api.ohlc.handler = call -> Fixtures.parse("{\"error\":[],\"result\":{\"BTC/USD\":[],\"last\":1}}");
        HistorySeeder seeder = new HistorySeeder(new PriceSources(List.of(kraken)), store, PricesConfig.defaults(),
                now::get, (task, delay) -> task.run(), t -> new long[] {hourly});

        assertThat(seeder.seedMissing(List.of(market))).isEqualTo(1);
        now.addAndGet(60_000L);
        assertThat(seeder.seedMissing(List.of(market))).as("a minute later, like any seed that brought nothing").isEqualTo(1);
        assertThat(api.ohlc.requests).hasSize(2);
    }

    // ------------------------------------------------------------------------------------ the settlement

    private RoundWindow window() {
        return new RoundWindow(market.id(), Interval.HOURLY, T0, T0 + STEP);
    }

    /** The ticks of the Kraken instrument at 100.0 every 3 s from {@code from} to {@code to}. */
    private void ticks(long from, long to) {
        for (long at = from; at <= to; at += 3_000L) {
            store.append("kraken", at, Map.of(market.id(), new BigDecimal("100.0")));
        }
    }

    /** A feed that stopped after S + 60 s and came back at S + 105 s: a 45 s hole in the middle of the round. */
    private void aHoleInTheRound() {
        ticks(T0 - 9_000L, T0 + 60_000L);
        ticks(T0 + 105_000L, T0 + STEP + 9_000L);
    }

    @Test
    void aHoleOlderThanKrakenKeepsIsNoDataAndNeitherBridgedNorCarriedAcrossAsAQuietPrice() {
        aHoleInTheRound(); // the round ended 13 h ago; the tape begins 12 h ago
        LiveMarketPrices prices = PipelineTestSupport.livePrices(store, List.of(kraken), now::get);

        assertThatThrownBy(() -> prices.resolve(market, window())).isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("no tick between");

        assertThat(store.series(market.id(), T0 + 61_000L, T0 + 105_000L)).as("nothing was made up for the hole").isEmpty();
        assertThat(api.ohlc.requests).hasSize(1);
    }

    @Test
    void aHoleInsideWhatKrakenKeepsIsBridgedByItsCandlesAsAlways() {
        aHoleInTheRound();
        now.set(T0 + STEP + HOUR); // an hour after the end: well inside the twelve hours
        LiveMarketPrices prices = PipelineTestSupport.livePrices(store, List.of(kraken), now::get);

        RoundPriceData data = prices.resolve(market, window());

        assertThat(data.open()).isEqualByComparingTo("100");
        assertThat(data.close()).isEqualByComparingTo("100");
        assertThat(store.series(market.id(), T0 + 61_000L, T0 + 105_000L)).as("the minute's extremes, a third and two thirds in")
                .extracting(PricePoint::atMs).containsExactly(T0 + 80_000L, T0 + 100_000L);
        assertThat(api.ohlc.requests).hasSize(1);
    }
}
