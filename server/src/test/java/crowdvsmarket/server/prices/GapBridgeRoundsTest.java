package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A redeploy must not void the running rounds (H1/H2 of the follow-up to the second review): a gap between two live
 * ticks of a liquid Hyperliquid market is bridged by its 1-minute candles — the lows, the highs and the closes of every
 * minute — when every minute has a candle with trades, each live mark lies inside the range its own minute traded
 * at, and the gap is not longer than the cap. The real {@link HyperliquidPriceSource} answers from a scripted tape of
 * candles ({@link CandleTape}), no network; the ticks are what the poller would have stored. Round [S, S + 3 min]
 * with S on a minute, the feed's limits are the defaults (stale 15 s, maximum gap 30 s).
 */
class GapBridgeRoundsTest {

    /** 2026-10-01T19:40:00Z, on a minute. */
    private static final long S = 1_790_883_600_000L;
    private static final long END = S + 180_000L;

    private final AtomicLong now = new AtomicLong(END + 10_000L);
    private final Market btc = TestMarkets.btc();

    private PricesConfig config = PricesConfig.defaults();
    private TickStore store;
    private FakeApis.Hyperliquid api;
    private CandleTape tape;
    private LiveMarketPrices prices;

    @BeforeEach
    void setUp() {
        tape = CandleTape.btc();
        wire(PricesConfig.defaults());
    }

    private void wire(PricesConfig cfg) {
        config = cfg;
        store = new TickStore(new FakeSnapshotStore(), cfg.retentionMs(), now::get);
        api = new FakeApis.Hyperliquid();
        api.info.handler = body -> tape.snapshot(body, now.get());
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, cfg, now::get);
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), cfg, now::get);
    }

    private RoundWindow window() {
        return new RoundWindow("BTC", Interval.HOURLY, S, END);
    }

    /** The marks the poller stores: one every 3 s from {@code fromMs} to {@code toMs}, both included. */
    private void ticks(long fromMs, long toMs) {
        for (long at = fromMs; at <= toMs; at += 3_000L) {
            store.append("hyperliquid", at, Map.of("BTC", tape.markPrice(at)));
        }
    }

    private BigDecimal lowestLow(RoundPriceData data) {
        return data.path().stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow();
    }

    private BigDecimal highestHigh(RoundPriceData data) {
        return data.path().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow();
    }

    private List<JsonNode> candleRequests() {
        return api.requestsOfType("candleSnapshot");
    }

    // -------------------------------------------------------------------------------- gaps that are bridged

    @Test
    void aGapOf45SecondsInsideTheRoundIsBridgedByTheMinuteItFallsInAndTheRoundSettlesWithItsExtremes() {
        // the feed stops after S + 60 s and is back at S + 105 s: 45 s, over the maximum gap of 30 s. Both ticks are
        // in the minute S + 60 s, whose trades went down to 85 900 and up to 86 100 — marks never went there
        tape.lowOf(S + 60_000L, 85_900.0).highOf(S + 60_000L, 86_100.0);
        ticks(S - 9_000L, S + 60_000L);
        ticks(S + 105_000L, END + 9_000L);

        RoundPriceData data = prices.resolve(btc, window());

        assertThat(data.open()).isEqualByComparingTo(tape.markPrice(S));
        assertThat(data.close()).isEqualByComparingTo(tape.markPrice(END));
        assertThat(lowestLow(data)).as("the minute's low is in the path").isEqualByComparingTo("85900.00");
        assertThat(highestHigh(data)).as("and its high").isEqualByComparingTo("86100.00");
        assertThat(candleRequests()).as("one request, for the minute of the live tick on the right too").hasSize(1);
        JsonNode request = candleRequests().get(0).get("req");
        assertThat(request.get("endTime").asLong()).isEqualTo(S + 105_000L);
        // what was merged: the two extremes a third and two thirds into the minute, between the two ticks
        List<PricePoint> bridge = store.series("BTC", S + 61_000L, S + 105_000L);
        assertThat(bridge).extracting(PricePoint::atMs).containsExactly(S + 80_000L, S + 100_000L);
        assertThat(bridge).extracting(p -> p.raw().intValue()).containsExactlyInAnyOrder(85_900, 86_100);
    }

    @Test
    void aGapOfTwoMinutesIsBridgedBy3CandlesTheLowsHighsAndClosesOfEachMinuteInsideIt() {
        // S + 20 s .. S + 140 s: the minutes of S, S + 60 s and S + 120 s
        tape.lowOf(S + 60_000L, 85_800.0).highOf(S + 60_000L, 86_200.0); // the minute in the middle
        ticks(S - 9_000L, S + 21_000L);
        ticks(S + 141_000L, END + 9_000L);

        RoundPriceData data = prices.resolve(btc, window());

        assertThat(lowestLow(data)).isEqualByComparingTo("85800.00");
        assertThat(highestHigh(data)).isEqualByComparingTo("86200.00");
        List<PricePoint> bridge = store.series("BTC", S + 22_000L, S + 140_001L);
        // minute S: the low and the high were at +20 s (the tick itself, not a gap) and +40 s: the +40 s one and the
        // close; minute S + 60 s: all three; minute S + 120 s: only what comes before the tick at +141 s
        assertThat(bridge).extracting(PricePoint::atMs).containsExactly(S + 40_000L, S + 59_999L, S + 80_000L,
                S + 100_000L, S + 119_999L, S + 140_000L);
        for (int i = 1; i < bridge.size(); i++) {
            assertThat(bridge.get(i).atMs() - bridge.get(i - 1).atMs()).isLessThanOrEqualTo(30_000L);
        }
        assertThat(candleRequests()).hasSize(1);
    }

    @Test
    void aGapOf20MinutesInsideAnHourlyRoundIsBridgedAndALiquidationTheCandleLowImpliesIsFound() {
        // a one-hour round; the feed is down from S + 10 min to S + 30 min, and in the minute S + 17 min the trades
        // dipped to 84 900, 1.3% under the marks around it: below the liquidation price of a 40x long at 86 000
        RoundWindow hour = new RoundWindow("BTC", Interval.DAILY, S, S + 3_600_000L);
        double dip = Math.round(tape.markAt(S) * 0.98 * 100.0) / 100.0; // 2% under the open
        tape.lowOf(S + 17 * CandleTape.MINUTE, dip);
        ticks(S - 9_000L, S + 600_000L);
        ticks(S + 1_800_000L, S + 3_609_000L);
        now.set(S + 3_620_000L);

        RoundPriceData data = prices.resolve(btc, hour);

        assertThat(lowestLow(data)).isEqualByComparingTo(BigDecimal.valueOf(dip));
        assertThat(data.path()).hasSizeLessThanOrEqualTo(30);
        Position long40 = Position.openFromMargin("u", hour.roundId(), Direction.UP, new Leverage(40),
                new BigDecimal("100.00"), data.open(), btc.spec());
        // a 40x long is liquidated 1.27% under its entry: the dip is below that, no mark was
        assertThat(long40.liquidationPrice()).isGreaterThan(BigDecimal.valueOf(dip));
        assertThat(PositionSimulator.simulate(long40, data.path())).isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
        // the same round with an ordinary hour in the gap is no liquidation: it is the candle that did it
        wire(config);
        tape = CandleTape.btc();
        ticks(S - 9_000L, S + 600_000L);
        ticks(S + 1_800_000L, S + 3_609_000L);
        RoundPriceData calm = prices.resolve(btc, hour);
        assertThat(lowestLow(calm)).isGreaterThan(new BigDecimal("85800.00"));
        assertThat(PositionSimulator.simulate(Position.openFromMargin("u", hour.roundId(), Direction.UP, new Leverage(40),
                new BigDecimal("100.00"), calm.open(), btc.spec()), calm.path()))
                .isInstanceOf(PositionSimulator.Outcome.Settled.class);
    }

    @Test
    void aBridgeCoversTheWholeGapSoARoundThatStartsInsideItFindsNoHole() {
        // the feed is down from S + 150 s to S + 210 s: across the boundary S + 180 s between the two rounds
        RoundWindow first = window();
        RoundWindow second = new RoundWindow("BTC", Interval.HOURLY, END, END + 180_000L);
        ticks(S - 9_000L, S + 150_000L);
        ticks(S + 210_000L, END + 190_000L);
        now.set(END + 200_000L);

        RoundPriceData a = prices.resolve(btc, first);
        assertThat(candleRequests()).hasSize(1);
        RoundPriceData b = prices.resolve(btc, second);

        assertThat(b.open()).isEqualByComparingTo(a.close()); // D-40
        assertThat(candleRequests()).as("the second round finds the gap filled: no request of its own").hasSize(1);
        // the price at the boundary is the close of the minute before it, one millisecond old
        assertThat(a.close()).isEqualByComparingTo(tape.markPrice(END - 1));
        assertThat(a.sourceTsMs()).isEqualTo(END - 1);
    }

    @Test
    void theSecondRoundMayBeSettledFirstAndTheBoundaryPriceIsTheSameForBoth() {
        RoundWindow first = window();
        RoundWindow second = new RoundWindow("BTC", Interval.HOURLY, END, END + 180_000L);
        ticks(S - 9_000L, S + 150_000L);
        ticks(S + 210_000L, END + 190_000L);
        now.set(END + 200_000L);

        RoundPriceData b = prices.resolve(btc, second);
        RoundPriceData a = prices.resolve(btc, first);

        assertThat(b.open()).isEqualByComparingTo(a.close());
    }

    @Test
    void aWindowThatStartsRightAfterTheFeedStoppedIsBridgedFromItsStartWhenTheTickBeforeItIsFresh() {
        // the last tick is at S − 10 s, fresh at the start (stale limit 15 s); the feed is back at S + 50 s. The
        // gap of the window is measured from its start, 50 s, and the candles of the minute before the start
        // (where the live tick was) and of the minute of the start are what bridges it
        ticks(S - 100_000L, S - 10_000L);
        ticks(S + 50_000L, END + 9_000L);

        RoundPriceData data = prices.resolve(btc, window());

        assertThat(data.open()).isEqualByComparingTo(tape.markPrice(S - 10_000L)); // the live tick is the open
        assertThat(candleRequests()).hasSize(1);
        assertThat(store.series("BTC", S + 1L, S + 50_000L)).extracting(PricePoint::atMs).containsExactly(S + 20_000L,
                S + 40_000L);
    }

    @Test
    void aRedeployInTheMiddleOfTheRoundDoesNotVoidIt() {
        // the whole chain of a redeploy: the server stops after the poll of S + 60 s and polls again from S + 165 s.
        // The ring is rebuilt from the snapshot rows (live ticks only) and the gap of 105 s is bridged by the candles
        // when the round ends; the minute S + 60 s traded down to 85 900 and the minute S + 120 s up to 86 200
        tape.lowOf(S + 60_000L, 85_900.0).highOf(S + 60_000L, 86_200.0);
        FakeSnapshotStore snapshots = new FakeSnapshotStore();
        TickStore beforeTheRedeploy = new TickStore(snapshots, config.retentionMs(), now::get);
        for (long at = S - 9_000L; at <= S + 60_000L; at += 3_000L) {
            beforeTheRedeploy.append("hyperliquid", at, Map.of("BTC", tape.markPrice(at)));
        }
        TickStore restarted = new TickStore(snapshots, config.retentionMs(), now::get);
        assertThat(restarted.restore()).isPositive();
        for (long at = S + 165_000L; at <= END + 9_000L; at += 3_000L) {
            restarted.append("hyperliquid", at, Map.of("BTC", tape.markPrice(at)));
        }
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, config, now::get);
        LiveMarketPrices afterTheRedeploy = new LiveMarketPrices(restarted, new PriceSources(List.of(source)), config,
                now::get);

        RoundPriceData data = afterTheRedeploy.resolve(btc, window());

        assertThat(data.open()).isEqualByComparingTo(tape.markPrice(S));
        assertThat(data.close()).isEqualByComparingTo(tape.markPrice(END));
        assertThat(lowestLow(data)).isEqualByComparingTo("85900.00");
        assertThat(highestHigh(data)).isEqualByComparingTo("86200.00");
        assertThat(candleRequests()).hasSize(1);
    }

    @Test
    void aRoundBoundaryInsideTheGapStillGetsTheCloseOfTheCandleBeforeIt() {
        // a window whose end is not on a minute (the previous fix's rule, kept: the price at a boundary is the close of
        // the closed candle before it, never a low or a high of the candle around it). Window [S + 17 s, S + 197 s];
        // the feed is down from S + 150 s to S + 230 s, so the end falls inside the gap, 17 s into the minute S + 180 s
        RoundWindow odd = new RoundWindow("BTC", Interval.HOURLY, S + 17_000L, S + 197_000L);
        ticks(S - 9_000L, S + 150_000L);
        ticks(S + 230_000L, S + 300_000L);
        now.set(S + 250_000L);

        RoundPriceData data = prices.resolve(btc, odd);

        assertThat(data.open()).isEqualByComparingTo(tape.markPrice(S + 15_000L)); // the tick 2 s before the start
        assertThat(data.close()).isEqualByComparingTo(tape.markPrice(S + 179_999L)); // the close of the minute S + 120 s
        assertThat(candleRequests()).hasSize(1);
        assertThat(store.series("BTC", S + 151_000L, S + 230_000L)).extracting(PricePoint::atMs).containsExactly(
                S + 160_000L, S + 179_999L, S + 197_000L, S + 200_000L, S + 220_000L);
    }

    // ------------------------------------------------------------------- gaps that stay holes (the refund)

    @Test
    void aCandleMissingInTheMiddleOfTheGapKeepsTheHole() {
        // minutes S, S + 60 s, S + 120 s: the middle one is a filler (no trades)
        tape.quiet(S + 60_000L);
        ticks(S - 9_000L, S + 21_000L);
        ticks(S + 141_000L, END + 9_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);

        assertThat(store.series("BTC", S + 22_000L, S + 140_000L)).as("nothing of a half bridge is merged").isEmpty();
    }

    @Test
    void aMinuteTheAnswerLostKeepsTheHoleToo() {
        tape.absent(S + 60_000L);
        ticks(S - 9_000L, S + 21_000L);
        ticks(S + 141_000L, END + 9_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("BTC", S + 22_000L, S + 140_000L)).isEmpty();
    }

    @Test
    void tradesFarFromTheMarksAreRefusedThePricesOfAnotherLevelNeverReplaceMarks() {
        // the trades of every minute sit 1% under the marks (a quiet asset's stale prints: 120.0 against 121.3)
        tape.shifted(-0.01);
        ticks(S - 9_000L, S + 60_000L);
        ticks(S + 105_000L, END + 9_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);

        assertThat(store.series("BTC", S + 61_000L, S + 104_000L)).isEmpty();
        assertThat(candleRequests()).hasSize(1);
    }

    @Test
    void aQuietAssetIsNotSettledOnAnOldTradePriceButCarriesItsOwnMarkWhenTheMarksAgree() {
        // xyz:NVDA outside its exchange's hours: the last trade, 27 minutes before the round, was at 120.00; every
        // minute since is a filler repeating it. The marks are flat at 121.30 on both sides of a 90 s gap
        Market nvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);
        tape = CandleTape.flat(121.30).quiet(S - 40 * CandleTape.MINUTE, S + 10 * CandleTape.MINUTE).fillerPrice(120.00);
        for (long at = S - 9_000L; at <= S + 30_000L; at += 6_000L) {
            store.append("hyperliquid:xyz", at, Map.of("xyz_NVDA", new BigDecimal("121.30")));
        }
        for (long at = S + 120_000L; at <= END + 18_000L; at += 6_000L) {
            store.append("hyperliquid:xyz", at, Map.of("xyz_NVDA", new BigDecimal("121.30")));
        }
        now.set(END + 20_000L);

        RoundPriceData data = prices.resolve(nvda, new RoundWindow("xyz_NVDA", Interval.HOURLY, S, END));

        assertThat(data.open()).isEqualByComparingTo("121300.00");
        assertThat(data.close()).isEqualByComparingTo("121300.00"); // flat: not DOWN on 120.00
        assertThat(highestHigh(data)).isEqualByComparingTo("121300.00");
        assertThat(lowestLow(data)).isEqualByComparingTo("121300.00");
        assertThat(store.series("xyz_NVDA", 0, Long.MAX_VALUE)).noneMatch(p -> p.raw().compareTo(new BigDecimal("120")) == 0);
    }

    @Test
    void aQuietAssetWhoseMarkMovedAcrossTheGapIsNotSettledAtAll() {
        Market nvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);
        tape = CandleTape.flat(121.30).quiet(S - 40 * CandleTape.MINUTE, S + 10 * CandleTape.MINUTE).fillerPrice(120.00);
        for (long at = S - 9_000L; at <= S + 30_000L; at += 6_000L) {
            store.append("hyperliquid:xyz", at, Map.of("xyz_NVDA", new BigDecimal("121.30")));
        }
        for (long at = S + 120_000L; at <= END + 18_000L; at += 6_000L) {
            store.append("hyperliquid:xyz", at, Map.of("xyz_NVDA", new BigDecimal("121.45")));
        }
        now.set(END + 20_000L);

        assertThatThrownBy(() -> prices.resolve(nvda, new RoundWindow("xyz_NVDA", Interval.HOURLY, S, END)))
                .isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("xyz_NVDA", 0, Long.MAX_VALUE)).noneMatch(p -> p.raw().compareTo(new BigDecimal("120")) == 0);
    }

    @Test
    void aGapOverTheCapIsNotBridgedAndOneOnTheCapIs() {
        RoundWindow hour = new RoundWindow("BTC", Interval.DAILY, S, S + 3_600_000L);
        // 30 minutes exactly: S + 5 min to S + 35 min
        ticks(S - 9_000L, S + 300_000L);
        ticks(S + 2_100_000L, S + 3_609_000L);
        now.set(S + 3_620_000L);
        assertThat(prices.resolve(btc, hour).path()).isNotEmpty();

        // 30 minutes and 3 s: the next tick of the grid
        wire(config);
        ticks(S - 9_000L, S + 300_000L);
        ticks(S + 2_103_000L, S + 3_609_000L);
        assertThatThrownBy(() -> prices.resolve(btc, hour)).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("BTC", S + 301_000L, S + 2_102_000L)).isEmpty();
    }

    @Test
    void bridgingCanBeSwitchedOffAndThenARedeployGapIsAHoleAgain() {
        wire(config.withHyperliquidMaxHealMs(0));
        ticks(S - 9_000L, S + 60_000L);
        ticks(S + 105_000L, END + 9_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("BTC", S + 61_000L, S + 104_000L)).isEmpty();
    }

    @Test
    void theCandleOfTheTickOnTheRightMayStillBeOpenThenTheNextTryAfterTheCooldownBridgesTheGap() {
        // the feed is back at S + 190 s, ten seconds into the minute S + 180 s whose candle is still open: not asked
        // for a price, not a closed minute. A minute later it is
        ticks(S - 9_000L, S + 150_000L);
        ticks(S + 190_000L, S + 200_000L);
        now.set(S + 200_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("BTC", S + 151_000L, S + 189_000L)).isEmpty();

        now.set(S + 262_000L);
        ticks(S + 203_000L, S + 261_000L);
        RoundPriceData data = prices.resolve(btc, window());

        assertThat(data.close()).isEqualByComparingTo(tape.markPrice(END - 1));
        assertThat(candleRequests()).hasSize(2);
    }

    @Test
    void aGapWithNoLiveTickOnTheRightYetIsNotBridgedEvenWithEveryCandleThere() {
        // the feed is still down: there is nothing to check the candles against on the right
        ticks(S - 9_000L, S + 150_000L);
        now.set(END + 130_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);
        assertThat(store.series("BTC", S + 151_000L, END + 130_000L)).isEmpty();
    }

    @Test
    void eachHoleOfAWindowIsBridgedOnItsOwnAndOneThatCannotBeKeepsTheRoundWaiting() {
        // two gaps, S + 30 s .. S + 80 s (bridged: all minutes traded) and S + 110 s .. S + 160 s (the minute S + 120 s is quiet)
        tape.quiet(S + 120_000L);
        ticks(S - 9_000L, S + 30_000L);
        ticks(S + 80_000L, S + 110_000L);
        ticks(S + 160_000L, END + 9_000L);

        assertThatThrownBy(() -> prices.resolve(btc, window())).isInstanceOf(PriceUnavailableException.class);

        assertThat(store.series("BTC", S + 31_000L, S + 79_000L)).as("the first gap is bridged").isNotEmpty();
        assertThat(store.series("BTC", S + 111_000L, S + 159_000L)).as("the second is not").isEmpty();
    }
}
