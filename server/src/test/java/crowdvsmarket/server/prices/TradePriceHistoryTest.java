package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F1 (second adversarial review): Hyperliquid's history is trade prices and its ticks are marks, so a candle's
 * price must never be put into a hole next to live ticks, and a quiet asset's hole is bridged only by the last
 * <i>live</i> tick under the quiet rule. Every scenario here settled on a price the market did not have during
 * the round: a flat mark that settled as DOWN on a 27-minute-old trade close, a path with a dip that the
 * liquidation check used. The scripted source answers what the old Hyperliquid source answered (the old trade
 * close carried across the hole) — whatever a source says, a trade price does not land between live ticks.
 */
class TradePriceHistoryTest {

    /** 2026-10-01T19:40:00Z, on a minute: the round [START, END] is three minutes long. */
    private static final long START = 1_790_883_600_000L;
    private static final long END = START + 180_000L;

    private final PricesConfig config = PricesConfig.defaults();
    private final AtomicLong now = new AtomicLong(END + 1_000);
    private final Market nvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);

    private TickStore store;
    private ScriptedTradeSource source;
    private LiveMarketPrices prices;

    /** An xyz-shaped source whose history is trade prices and whatever the test scripts. */
    private static final class ScriptedTradeSource implements PriceSource {

        final List<long[]> requests = new ArrayList<>();
        List<PricePoint> points = List.of();
        Optional<PricePoint> lastTrade = Optional.empty();
        HistoryKind kind = HistoryKind.TRADE_PRICES;
        IOException failure;

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid:xyz", 20_000, 30_000, m -> m.sourceRef().startsWith("xyz:")));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            requests.add(new long[] {fromMs, toMs});
            if (failure != null) {
                throw failure;
            }
            return points;
        }

        @Override
        public HistoryKind historyKind() {
            return kind;
        }

        @Override
        public History historyOf(Market market, long fromMs, long toMs, long... boundariesMs) throws IOException {
            return new History(history(market, fromMs, toMs), lastTrade);
        }
    }

    @BeforeEach
    void setUp() {
        store = new TickStore(new FakeSnapshotStore(), config.retentionMs(), now::get);
        source = new ScriptedTradeSource();
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), config, now::get);
    }

    private RoundWindow window() {
        return new RoundWindow(nvda.id(), Interval.HOURLY, START, END);
    }

    private void marks(long from, long to, long stepMs, String price) {
        for (long at = from; at <= to; at += stepMs) {
            store.append("hyperliquid:xyz", at, Map.of(nvda.id(), new BigDecimal(price)));
        }
    }

    private static PricePoint pt(long at, String price) {
        return new PricePoint(at, new BigDecimal(price));
    }

    /** What the old source answered for a quiet asset: its last close, 27 minutes old, carried every 10 s across the range. */
    private static List<PricePoint> theOldSourcesCarriedClose(long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        for (long at = fromMs; at <= toMs; at += 10_000L) {
            out.add(pt(at, "120.00"));
        }
        return out;
    }

    private boolean ringHolds(String price) {
        return store.series(nvda.id(), 0, Long.MAX_VALUE).stream().anyMatch(p -> p.raw().compareTo(new BigDecimal(price)) == 0);
    }

    // ---------------------------------------------------------------------- the quiet asset's flat mark

    @Test
    void aFlatMarkIsNotSettledAsDownOnAnOldTradeCloseTheFeedBeingDownAtTheEnd() {
        // xyz:NVDA, quiet: the marks are 121.30 and flat; the last trade, 27 minutes ago, closed at 120.00. The feed
        // dies after S + 54 s (a redeploy is enough) and the round is resolved 130 s after its end.
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        source.points = theOldSourcesCarriedClose(START + 64_000, END);
        source.lastTrade = Optional.of(pt(START - 27 * 60_000L, "120.00"));
        now.set(END + 130_000L);

        // it used to settle: open 121.30, close 120.00 — a flat mark as DOWN, with 120.00 in the path
        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);

        assertThat(ringHolds("120.00")).as("a trade price never lands between the marks").isFalse();
        assertThat(source.requests).hasSize(1);
    }

    @Test
    void aGapBetweenTwoDifferentMarksIsNotFilledWithAnOldTradeCloseAndTheRoundStaysUnavailable() {
        // ticks 121.30, a gap, ticks 121.45: R5 refused for two minutes and then the source's carry became "allowed"
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        marks(START + 120_000, END + 60_000, 6_000, "121.45");
        source.points = theOldSourcesCarriedClose(START + 64_000, START + 110_000);
        source.lastTrade = Optional.of(pt(START - 27 * 60_000L, "120.00"));

        for (long after : new long[] {1_000L, 51_000L, 61_000L, 125_000L, 3_000_000L}) {
            now.set(END + after);
            assertThatThrownBy(() -> prices.resolve(nvda, window())).as("at end + " + after + " ms")
                    .isInstanceOf(PriceUnavailableException.class);
        }

        assertThat(ringHolds("120.00")).as("no dip for the liquidation check to use").isFalse();
    }

    @Test
    void aQuietAssetWhoseMarkDidNotMoveAcrossTheHoleIsCarriedAtTheMarkNotAtTheTradeClose() {
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        marks(START + 120_000, END + 60_000, 6_000, "121.30"); // back after the outage at the very same mark
        source.points = List.of(); // nothing traded in the hole
        source.lastTrade = Optional.of(pt(START - 27 * 60_000L, "120.00")); // the old trade is not what is carried
        now.set(END + 61_000L);

        RoundPriceData data = prices.resolve(nvda, window());

        assertThat(data.open()).isEqualByComparingTo("121300.00"); // lot exponent 3
        assertThat(data.close()).isEqualByComparingTo("121300.00"); // flat: not DOWN
        assertThat(data.path()).allSatisfy(c -> {
            assertThat(c.low()).isEqualByComparingTo("121300.00");
            assertThat(c.high()).isEqualByComparingTo("121300.00");
        });
        assertThat(ringHolds("120.00")).isFalse();
        // the carried points repeat the mark, 10 s apart, and nothing else
        List<PricePoint> carried = store.series(nvda.id(), START + 55_000L, START + 119_999L);
        assertThat(carried).isNotEmpty().allSatisfy(p -> assertThat(p.raw()).isEqualByComparingTo("121.30"));
    }

    @Test
    void aTradeInsideTheHoleMeansTheAssetWasNotQuietSoNothingIsCarriedEvenWhenTheMarksAgree() {
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        marks(START + 120_000, END + 60_000, 6_000, "121.30");
        source.points = List.of(pt(START + 70_000, "121.10"), pt(START + 80_000, "121.90")); // a liquid minute inside
        now.set(END + 61_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);

        assertThat(ringHolds("121.10")).as("trade prices are evidence, not points").isFalse();
        assertThat(ringHolds("121.90")).isFalse();
        assertThat(store.series(nvda.id(), START + 55_000L, START + 119_999L)).isEmpty(); // and no carry either
    }

    // ------------------------------------------------------------------- the tail, no right neighbour yet

    @Test
    void aTailWithNoTickAfterItIsCarriedOnlyWhenTheLastTradeIsThePriceOfTheLastTick() {
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        now.set(END + 130_000L); // the feed is still down: no right neighbour

        source.lastTrade = Optional.of(pt(START - 60_000L, "121.31")); // a trade one tick away: the mark may have moved
        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series(nvda.id(), START + 55_000L, END + 1)).isEmpty();

        now.addAndGet(61_000L); // the cooldown has passed; the last trade now says 121.30 — the mark's own price
        source.lastTrade = Optional.of(pt(START - 60_000L, "121.30"));
        RoundPriceData data = prices.resolve(nvda, window());

        assertThat(data.close()).isEqualByComparingTo("121300.00");
        assertThat(data.open()).isEqualByComparingTo(data.close());
        assertThat(data.sourceTsMs()).isEqualTo(START + 52_000L); // the last tick that really came, not a carried copy
        assertThat(ringHolds("120.00")).isFalse();
    }

    @Test
    void aTailWithNoLastTradeAtAllIsNotCarried() {
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        source.lastTrade = Optional.empty();
        now.set(END + 130_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void aSourceWhoseHistoryIsTheSameNumberAsItsTicksStillNeedsTheTickAfterTheWindowForATail() {
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        source.kind = PriceSource.HistoryKind.SAME_AS_LIVE;
        source.lastTrade = Optional.of(pt(START - 60_000L, "121.30")); // means nothing to a midpoint source
        now.set(END + 130_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);
    }

    // --------------------------------------------------------------------------------- the carry's cap

    @Test
    void aHoleLongerThanTheCapIsNoFlatLineAndOneThatIsNotLongerIs() {
        // ticks at START + 6 s (121.30) and START + 66 s (121.30): a hole of exactly 60 s inside the round
        PricesConfig capped = config.withQuietCarryMaxMs(60_000L);
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), capped, now::get);
        marks(START - 2_000, START + 6_000, 4_000, "121.30");
        marks(START + 66_000, END + 20_000, 6_000, "121.30");
        now.set(END + 30_000L);

        RoundPriceData ok = prices.resolve(nvda, window()); // 60 s: carried

        assertThat(ok.close()).isEqualByComparingTo(ok.open());

        // the same with one millisecond more: refused
        setUp();
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), capped, now::get);
        marks(START - 2_000, START + 6_000, 4_000, "121.30");
        marks(START + 66_001, END + 20_000, 6_000, "121.30");
        now.set(END + 30_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void theDefaultCapIsTenMinutesLongEnoughForARestartAndTooShortForAnHourOfSilence() {
        // a one-hour round with an outage of 20 minutes in the middle at the same mark: not a quiet minute, not carried
        RoundWindow hour = new RoundWindow(nvda.id(), Interval.DAILY, START, START + 3_600_000L);
        marks(START - 2_000, START + 600_000L, 6_000, "121.30");
        marks(START + 1_800_000L, START + 3_700_000L, 6_000, "121.30");
        now.set(START + 3_700_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, hour)).isInstanceOf(PriceUnavailableException.class);
        assertThat(config.quietCarryMaxMs()).isEqualTo(600_000L);
    }

    // ----------------------------------------------- the carried price is a mark: the left tick must be live

    @Test
    void aHoleBehindAHistoryPointIsNotCarriedForATradeSourceBecauseThePriceIsATradePrice() {
        // the seeded past (history points) up to S + 10 s, then the first poll only at S + 100 s at the same price
        store.mergeHistory(nvda.id(), List.of(pt(START - 30_000, "121.30"), pt(START - 15_000, "121.30"),
                pt(START, "121.30"), pt(START + 10_000, "121.30")));
        marks(START + 100_000, END + 20_000, 6_000, "121.30");
        now.set(END + 30_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, window())).isInstanceOf(PriceUnavailableException.class);

        // the same ring under a source whose history is the same number as its ticks: a midpoint is a midpoint
        source.kind = PriceSource.HistoryKind.SAME_AS_LIVE;
        now.addAndGet(61_000L);
        RoundPriceData data = prices.resolve(nvda, window());
        assertThat(data.close()).isEqualByComparingTo("121300.00");
    }

    @Test
    void aPinThatStandsForALiveTickIsStillALiveLeftNeighbourForTheNextWindowThatStartsInTheSameSilence() {
        // the ticks stop at S + 52 s and resume at S + 330 s, the same mark. Round 1 [S, E] closes in the silence
        // (the carried points end at S + 172 s, the pin is at E); round 2 [E, E + 180 s] starts on that pin and
        // its head is the rest of the silence: the pin's price is a mark that was seen live, so it is carried too
        marks(START - 2_000, START + 54_000, 6_000, "121.30");
        marks(START + 330_000, START + 420_000, 6_000, "121.30");
        source.lastTrade = Optional.empty();
        now.set(END + 21_000L);
        RoundPriceData first = prices.resolve(nvda, window());
        assertThat(first.close()).isEqualByComparingTo("121300.00");
        assertThat(first.sourceTsMs()).isEqualTo(START + 52_000L);

        now.set(END + 180_000L + 61_000L);
        RoundPriceData second = prices.resolve(nvda, new RoundWindow(nvda.id(), Interval.HOURLY, END, END + 180_000L));

        assertThat(second.open()).isEqualByComparingTo(first.close()); // D-40
        assertThat(second.close()).isEqualByComparingTo("121300.00");
        assertThat(second.sourceTsMs()).isEqualTo(START + 360_000L); // a real tick again
        assertThat(source.requests).hasSize(2);
    }

    // --------------------------------------------- history is the past of the first poll, nothing else

    @Test
    void aMarketsFirstRoundTakesItsOpenFromHistoryBeforeItsFirstPollAndNeverFromAfterIt() {
        // listed mid-round: the first poll arrives at S + 100 s. The candles' points before it are the past; the
        // ones after it (the minute the poll is in) must not touch the live ticks' stretch.
        marks(START + 100_000, END + 3_000, 6_000, "121.50");
        List<PricePoint> past = new ArrayList<>();
        for (long at = START - 60_000; at <= START - 10_000; at += 10_000) {
            past.add(pt(at, "121.00"));
        }
        past.add(pt(START - 1, "121.10"));
        for (long at = START + 5_000; at <= START + 95_000; at += 10_000) {
            past.add(pt(at, "121.20"));
        }
        past.add(pt(START + 110_000, "999")); // after the first poll: never merged
        past.add(pt(START + 150_000, "999"));
        source.points = past;

        RoundPriceData data = prices.resolve(nvda, window());

        assertThat(data.open()).isEqualByComparingTo("121100.00"); // the trade price at START - 1 ms: the history's price at S
        assertThat(data.close()).isEqualByComparingTo("121500.00"); // a live mark
        assertThat(ringHolds("999")).isFalse();
        assertThat(store.oldestLiveAt(nvda.id())).hasValue(START + 100_000);
        assertThat(store.series(nvda.id(), START + 100_001, END + 1)).allSatisfy(
                p -> assertThat(p.raw()).isEqualByComparingTo("121.50"));
    }

    @Test
    void theSameHoleOfASourceWhoseHistoryIsTheSameNumberIsFilledFromHistory() {
        source.kind = PriceSource.HistoryKind.SAME_AS_LIVE;
        marks(START - 2_000, START + 54_000, 6_000, "0.50");
        marks(START + 130_000, END + 30_000, 6_000, "0.52");
        List<PricePoint> midpoints = new ArrayList<>();
        for (long at = START + 60_000; at < START + 130_000; at += 10_000) {
            midpoints.add(pt(at, "0.51"));
        }
        source.points = midpoints;
        now.set(END + 31_000L);

        RoundPriceData data = prices.resolve(nvda, window());

        assertThat(data.path()).hasSize(30);
        assertThat(data.path().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("520.00");
        assertThat(ringHolds("0.51")).isTrue(); // midpoints are the same kind of number as the ticks around them
    }
}
