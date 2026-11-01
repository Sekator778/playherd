package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F4 and F3 (second adversarial review). A boundary pin is a price the ring remembers for the next round, not a
 * tick: it must not make an old price look fresh to the bots or the bet slip, it must not show on a chart, and it
 * must not become the time a settlement says its price was seen. And what a round settled on is not rewritten
 * by a history merge made for another window — whichever order the series settle in.
 */
class PinsAreNotTicksTest {

    /** 2026-10-01T19:40:00Z, on a minute. */
    private static final long X = 1_790_883_600_000L;

    private final PricesConfig config = PricesConfig.defaults();
    private final AtomicLong now = new AtomicLong(X + 1_000);
    private final Market btc = TestMarkets.btc();

    private TickStore store;
    private ScriptedSource source;
    private LiveMarketPrices prices;

    /** A Hyperliquid-shaped source (15 s stale, 30 s gap) whose history is whatever the test scripts. */
    private static final class ScriptedSource implements PriceSource {

        final List<long[]> requests = new ArrayList<>();
        List<PricePoint> points = List.of();

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> "BTC".equals(m.sourceRef())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            requests.add(new long[] {fromMs, toMs});
            return points;
        }
    }

    @BeforeEach
    void setUp() {
        store = new TickStore(new FakeSnapshotStore(), config.retentionMs(), now::get);
        source = new ScriptedSource();
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), config, now::get);
    }

    private void ticks(long from, long to, long stepMs, String price) {
        for (long at = from; at <= to; at += stepMs) {
            store.append("hyperliquid", at, Map.of("BTC", new BigDecimal(price)));
        }
    }

    private static PricePoint pt(long at, String price) {
        return new PricePoint(at, new BigDecimal(price));
    }

    private RoundWindow hour(long start) {
        return new RoundWindow("BTC", Interval.HOURLY, start, start + 180_000L);
    }

    private RoundWindow day() {
        return new RoundWindow("BTC", Interval.DAILY, X - 900_000L, X);
    }

    // ------------------------------------------------------------------------------- F4: a pin is no tick

    @Test
    void aPinNeverMakesAnOldPriceLookFreshToABotOrTheBetSlip() {
        // the last real tick is 12 s before X (still fresh at X: the limit is 15 s); the feed is down after it
        ticks(X - 183_000, X - 12_000, 3_000, "100");
        prices.resolve(btc, hour(X - 180_000L)); // settles and pins X (the price at X is that tick's)

        now.set(X + 14_000L);
        PriceQuote quote = prices.latest("BTC").orElseThrow();

        // it used to say asOf = X and fresh until X + 16 s; the tick that really arrived is 26 s old
        assertThat(quote.asOfMs()).isEqualTo(X - 12_000L);
        assertThat(quote.stale()).isTrue();
        assertThat(store.window("BTC", X - 1, X).kind()).containsExactly(TickRing.PIN); // the pin is there ...
        assertThat(prices.series("BTC", X - 13_000L, X + 1_000L)).extracting(PricePoint::atMs)
                .containsExactly(X - 12_000L); // ... and is no chart point
    }

    @Test
    void theSettlementOfBothSeriesSaysTheTimeOfTheTickThePriceCameFromWhoeverSettlesFirst() {
        ticks(X - 903_000, X - 12_000, 3_000, "100");

        // the scheduler settles DAILY before HOURLY; both end at X, so the day pins X before the hour reads it
        RoundPriceData daily = prices.resolve(btc, day());
        RoundPriceData hourly = prices.resolve(btc, hour(X - 180_000L));

        // it used to be X for the hour: a time later than its own ts, newer than the tick the price came from
        assertThat(daily.sourceTsMs()).isEqualTo(X - 12_000L);
        assertThat(hourly.sourceTsMs()).isEqualTo(X - 12_000L);
        assertThat(hourly.close()).isEqualByComparingTo(daily.close());
    }

    @Test
    void theNextRoundThatOpensOnAPinReportsTheRealTickBehindItToo() {
        ticks(X - 183_000, X - 12_000, 3_000, "100");
        prices.resolve(btc, hour(X - 180_000L)); // pins X
        ticks(X + 60_000, X + 200_000, 3_000, "100");
        now.set(X + 200_000L);

        RoundPriceData next = prices.resolve(btc, hour(X));

        assertThat(next.open()).isEqualByComparingTo("100.00");
        assertThat(next.sourceTsMs()).isEqualTo(X + 180_000L); // its own last tick
        // and the running path reads the same way: the real tick, never the pin
        assertThat(prices.soFar(btc, hour(X), X + 30_000L)).isPresent();
        assertThat(prices.soFar(btc, hour(X), X + 30_000L).orElseThrow().sourceTsMs()).isEqualTo(X - 12_000L);
    }

    // ----------------------------------------------------------- F3: nothing merged behind a pinned boundary

    @Test
    void aHoleBehindABoundaryThatARoundSettledOnIsNotFilledFromHistoryAnyMore() {
        // a hole at X - 400 s in the live ticks; the hour [X - 180 s, X] settled on its own stretch and pinned X - 180 s
        // and X; a day that reaches back over the hole is asked for afterwards: whatever history offers for a stretch
        // before the latest pinned boundary does not go in (and the source is not even asked for it)
        ticks(X - 903_000, X - 430_000, 3_000, "100");
        ticks(X - 370_000, X + 3_000, 3_000, "100");
        prices.resolve(btc, hour(X - 180_000L)); // pins X - 180 s and X
        source.points = List.of(pt(X - 420_000, "55"), pt(X - 400_000, "55"), pt(X - 380_000, "55"));
        RoundWindow wholeDay = new RoundWindow("BTC", Interval.DAILY, X - 900_000L, X);
        now.set(X + 5_000L);

        assertThatThrownBy(() -> prices.resolve(btc, wholeDay)).isInstanceOf(PriceUnavailableException.class);

        assertThat(source.requests).as("a stretch behind the pinned boundary is not asked for").isEmpty();
        assertThat(store.series("BTC", X - 429_000L, X - 371_000L)).as("and nothing of it is in the ring").isEmpty();
        assertThat(store.latestPin("BTC")).hasValue(X);
    }

    @Test
    void theSameHoleIsFilledWhileNoRoundHasSettledBehindIt() {
        ticks(X - 903_000, X - 430_000, 3_000, "100");
        ticks(X - 370_000, X + 3_000, 3_000, "100");
        List<PricePoint> dense = new ArrayList<>(); // every 10 s: the 60 s hole is bridged inside the 30 s limit
        for (long at = X - 427_000; at < X - 373_000; at += 10_000) {
            dense.add(pt(at, "100"));
        }
        source.points = dense;
        now.set(X + 5_000L);

        RoundPriceData data = prices.resolve(btc, new RoundWindow("BTC", Interval.DAILY, X - 900_000L, X));

        assertThat(data.open()).isEqualByComparingTo("100.00");
        assertThat(source.requests).hasSize(1);
    }

    @Test
    void aSettledRoundsBoundaryPriceSurvivesAMergeMadeForALongerWindowOfTheOtherSeries() {
        // the feed dies 12 s before X and is back 60 s after it; the source remembers the minute in between
        ticks(X - 183_000, X - 12_000, 3_000, "100");
        ticks(X + 60_000, X + 190_000, 3_000, "105");
        List<PricePoint> minute = new ArrayList<>(List.of(pt(X - 1, "105"), pt(X, "105")));
        for (long at = X - 10_000; at <= X + 50_000; at += 10_000) {
            minute.add(pt(at, "105"));
        }
        source.points = minute;
        now.set(X + 200_000L);

        RoundPriceData hourA = prices.resolve(btc, hour(X - 180_000L)); // the 12 s old tick is fresh at X: closes at 100
        RoundPriceData day = prices.resolve(btc, new RoundWindow("BTC", Interval.DAILY, X - 180_000L, X + 180_000L));
        RoundPriceData hourB = prices.resolve(btc, hour(X));

        assertThat(hourA.close()).isEqualByComparingTo("100.00");
        assertThat(hourB.open()).isEqualByComparingTo(hourA.close()); // D-40
        assertThat(day.close()).isEqualByComparingTo(hourB.close());
        // the hour that closed at X before the merge still reads 100
        assertThat(prices.resolve(btc, hour(X - 180_000L)).close()).isEqualByComparingTo("100.00");
    }
}
