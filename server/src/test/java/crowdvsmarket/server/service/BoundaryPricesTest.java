package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.RoundPriceData;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Р-40 in the core ({@link BoundaryPrices}), on plain objects: the rule that a round opens at the close of the
 * settled round that ended where it starts, the adjustment of the path it implies, what is read when a state is
 * built, what is added and pruned when a settlement commits, and that replay mode is left alone.
 */
class BoundaryPricesTest {

    private static final long STEP = 60_000L;
    private static final long DAY = 120_000L;
    private static final Market BTC = market("BTC");

    private GameConfig config;
    private RoundResultRepository roundResults;
    private RoundProgress progress;
    private BoundaryPrices prices;

    private static Market market(String id) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, 10, 0, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    private static RoundWindow hourly(String market, long start) {
        return new RoundWindow(market, Interval.HOURLY, start, start + STEP);
    }

    private static RoundWindow daily(String market, long start) {
        return new RoundWindow(market, Interval.DAILY, start, start + DAY);
    }

    private static Candle candle(long from, long to, String open, String high, String low, String close) {
        return new Candle(from, to, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ZERO, 0);
    }

    @BeforeEach
    void plainObjects() {
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        when(config.roundDurations()).thenReturn(new RoundDurations(STEP, DAY));
        roundResults = mock(RoundResultRepository.class);
        progress = new RoundProgress();
        prices = new BoundaryPrices();
        prices.config = config;
        prices.roundResults = roundResults;
        prices.roundProgress = progress;
    }

    private BoundaryPrices.Snapshot loadWith(Map<RoundProgress.SeriesKey, RoundWindow> cursors) {
        return prices.load(cursors);
    }

    private Map<RoundProgress.SeriesKey, RoundWindow> cursorsOf(RoundWindow... windows) {
        Map<RoundProgress.SeriesKey, RoundWindow> cursors = new LinkedHashMap<>();
        for (RoundWindow window : windows) {
            cursors.put(new RoundProgress.SeriesKey(window.market(), window.interval()), window);
        }
        return cursors;
    }

    // ---------------------------------------------------------------------------------------
    // The candle adjustments
    // ---------------------------------------------------------------------------------------

    @Test
    void theFirstCandleOpensAtTheBoundaryAndLowAndHighAreWidenedToTakeItIn() {
        List<Candle> path = List.of(candle(0, 29_999, "100.00", "105.00", "99.00", "104.00"),
                candle(30_000, 59_999, "104.00", "106.00", "103.00", "105.00"));

        List<Candle> below = BoundaryPrices.openingAt(path, new BigDecimal("97.50"));
        List<Candle> above = BoundaryPrices.openingAt(path, new BigDecimal("108.00"));
        List<Candle> inside = BoundaryPrices.openingAt(path, new BigDecimal("101.00"));

        assertThat(below.get(0).open()).isEqualByComparingTo("97.50");
        assertThat(below.get(0).low()).as("widened down").isEqualByComparingTo("97.50");
        assertThat(below.get(0).high()).isEqualByComparingTo("105.00");
        assertThat(below.get(0).close()).as("the close is the pipeline's").isEqualByComparingTo("104.00");
        assertThat(above.get(0).high()).as("widened up").isEqualByComparingTo("108.00");
        assertThat(above.get(0).low()).isEqualByComparingTo("99.00");
        assertThat(inside.get(0).open()).isEqualByComparingTo("101.00");
        assertThat(inside.get(0).low()).as("nothing to widen").isEqualByComparingTo("99.00");
        assertThat(below.get(1)).as("only the first candle changes").isEqualTo(path.get(1));
        assertThat(path.get(0).open()).as("the pipeline's list is not touched").isEqualByComparingTo("100.00");
    }

    @Test
    void aPathThatAlreadyOpensThereIsReturnedAsItIsAndAnEmptyOneStaysEmpty() {
        List<Candle> path = List.of(candle(0, 59_999, "100.00", "101.00", "99.00", "100.50"));

        assertThat(BoundaryPrices.openingAt(path, new BigDecimal("100.00"))).isSameAs(path);
        assertThat(BoundaryPrices.openingAt(List.of(), new BigDecimal("100.00"))).isEmpty();
    }

    @Test
    void aSingleCandleOpensAtTheBoundaryAndKeepsItsCloseAndItsOtherWidenedSide() {
        List<Candle> single = List.of(candle(0, 59_999, "100.00", "101.00", "99.00", "100.50"));

        List<Candle> adjusted = BoundaryPrices.openingAt(single, new BigDecimal("103.00"));

        assertThat(adjusted).hasSize(1);
        assertThat(adjusted.get(0).open()).isEqualByComparingTo("103.00");
        assertThat(adjusted.get(0).close()).isEqualByComparingTo("100.50");
        assertThat(adjusted.get(0).high()).isEqualByComparingTo("103.00");
        assertThat(adjusted.get(0).low()).isEqualByComparingTo("99.00");
    }

    // ---------------------------------------------------------------------------------------
    // forSettlement
    // ---------------------------------------------------------------------------------------

    @Test
    void withoutASettledRoundThatEndedAtTheStartTheDataIsTheSourcesAsItIs() {
        RoundPriceData data = new RoundPriceData(new BigDecimal("100.00"), new BigDecimal("101.00"),
                List.of(candle(STEP, 2 * STEP - 1, "100.00", "101.50", "99.50", "101.00")), 2 * STEP - 1);

        assertThat(prices.forSettlement(hourly("BTC", STEP), data)).isSameAs(data);
    }

    @Test
    void aRoundOpensAtTheCloseOfTheRoundThatEndedWhereItStartsWhateverTheSourceSaysNow() {
        prices.settled(hourly("BTC", 0L), 10_000_00L); // closed at 10 000.00
        RoundPriceData fromTheSource = new RoundPriceData(new BigDecimal("10010.00"), new BigDecimal("10020.00"),
                List.of(candle(STEP, 2 * STEP - 1, "10010.00", "10025.00", "10005.00", "10020.00")), 2 * STEP - 1);

        RoundPriceData data = prices.forSettlement(hourly("BTC", STEP), fromTheSource);

        assertThat(data.open()).isEqualByComparingTo("10000.00");
        assertThat(data.close()).as("only the open is the previous round's: the close is the source's")
                .isEqualByComparingTo("10020.00");
        assertThat(data.path().get(0).open()).isEqualByComparingTo("10000.00");
        assertThat(data.path().get(0).low()).as("widened to take the open in").isEqualByComparingTo("10000.00");
        assertThat(data.path().get(0).high()).isEqualByComparingTo("10025.00");
        assertThat(data.sourceTsMs()).isEqualTo(fromTheSource.sourceTsMs());
    }

    @Test
    void anHourOpensWhereTheDayThatEndedAtItsStartClosed() {
        prices.settled(daily("BTC", 0L), 20_000_00L); // a day that ended at 120 000

        RoundPriceData hour = prices.forSettlement(hourly("BTC", DAY),
                new RoundPriceData(new BigDecimal("20123.00"), new BigDecimal("20130.00"),
                        List.of(candle(DAY, DAY + STEP - 1, "20123.00", "20135.00", "20120.00", "20130.00")),
                        DAY + STEP - 1));

        assertThat(hour.open()).as("an hour of the market opens where its day closed").isEqualByComparingTo("20000.00");
        assertThat(hour.path().get(0).open()).isEqualByComparingTo("20000.00");
        assertThat(hour.path().get(0).low()).isEqualByComparingTo("20000.00");
    }

    @Test
    void aRoundIsNeverGivenAnotherCloseThanTheSourcesEvenWhenARoundOfTheOtherSeriesEndedAtItsEnd() {
        prices.settled(hourly("BTC", DAY - STEP), 21_000_00L); // an hour that ended at 120 000, settled first

        RoundPriceData day = prices.forSettlement(daily("BTC", 0L),
                new RoundPriceData(new BigDecimal("19000.00"), new BigDecimal("20999.00"),
                        List.of(candle(0, DAY - 1, "19000.00", "21050.00", "18900.00", "20999.00")), DAY - 1));

        assertThat(day.open()).as("nothing ended at the start of the day: the source's").isEqualByComparingTo("19000.00");
        assertThat(day.close()).as("the rule is about the open: the close is whatever the source says")
                .isEqualByComparingTo("20999.00");
    }

    @Test
    void anotherMarketsCloseDoesNotApply() {
        prices.settled(hourly("ETH", 0L), 3_000_00L);
        RoundPriceData data = new RoundPriceData(new BigDecimal("100.00"), new BigDecimal("101.00"), List.of(), 1L);

        assertThat(prices.forSettlement(hourly("BTC", STEP), data)).isSameAs(data);
    }

    // ---------------------------------------------------------------------------------------
    // forRunning
    // ---------------------------------------------------------------------------------------

    @Test
    void aRunningRoundShowsTheSettledCloseAsItsOpenAlsoWhereTheSourceKnowsNothing() {
        prices.settled(hourly("BTC", 0L), 10_000_00L);
        RoundWindow running = hourly("BTC", STEP);

        Optional<RoundPriceData> unknown = prices.forRunning(BTC, running, Optional.empty());
        Optional<RoundPriceData> known = prices.forRunning(BTC, running, Optional.of(new RoundPriceData(
                new BigDecimal("10010.00"), new BigDecimal("10015.00"),
                List.of(candle(STEP, STEP + 5_000, "10010.00", "10016.00", "10009.00", "10015.00")), STEP + 5_000)));

        assertThat(unknown).isPresent();
        assertThat(unknown.get().open()).isEqualByComparingTo("10000.00");
        assertThat(unknown.get().path()).as("no path: the DTO draws a flat one from the open").isEmpty();
        assertThat(known.get().open()).isEqualByComparingTo("10000.00");
        assertThat(known.get().close()).as("the latest price is the source's").isEqualByComparingTo("10015.00");
        assertThat(known.get().path().get(0).open()).isEqualByComparingTo("10000.00");
        assertThat(known.get().path().get(0).low()).isEqualByComparingTo("10000.00");
    }

    @Test
    void aRunningRoundWithoutASettledRoundBeforeItIsTheSourcesAsItIsAndNothingStaysUnknownWithoutOne() {
        RoundWindow running = hourly("BTC", STEP);
        RoundPriceData data = new RoundPriceData(new BigDecimal("1.00"), new BigDecimal("1.00"), List.of(), 1L);

        assertThat(prices.forRunning(BTC, running, Optional.empty())).isEmpty();
        assertThat(prices.forRunning(BTC, running, Optional.of(data))).containsSame(data);
    }

    // ---------------------------------------------------------------------------------------
    // load: what a state built aside needs
    // ---------------------------------------------------------------------------------------

    @Test
    void aStateBuiltAsideLoadsTheClosesOfTheRoundsItsCursorsMayOpenAt() {
        RoundWindow hourlyCursor = hourly("BTC", 5 * STEP);
        RoundWindow dailyCursor = daily("BTC", 0L);
        when(roundResults.settledCloses(eq("BTC"), eq(Interval.HOURLY), anyLong())).thenReturn(List.of(
                new RoundResultRepository.SettledClose(4 * STEP, 9_900_00L),
                new RoundResultRepository.SettledClose(5 * STEP, 10_000_00L)));
        when(roundResults.settledCloses(eq("BTC"), eq(Interval.DAILY), anyLong())).thenReturn(List.of(
                new RoundResultRepository.SettledClose(DAY, 10_050_00L)));

        BoundaryPrices.Snapshot snapshot = loadWith(cursorsOf(hourlyCursor, dailyCursor));
        prices.adopt(snapshot);

        assertThat(prices.closeAt("BTC", 5 * STEP)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("10000.00"));
        assertThat(prices.closeAt("BTC", DAY)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("10050.00"));
        assertThat(prices.closeAt("BTC", 4 * STEP)).as("ended before the earliest cursor (0): rows at or after it")
                .isPresent();
        assertThat(prices.closeAt("BTC", 9 * STEP)).isEmpty();
        assertThat(prices.closeAt("ETH", 5 * STEP)).isEmpty();
        assertThat(snapshot.of("BTC")).containsKeys(4 * STEP, 5 * STEP, DAY);
    }

    @Test
    void rowsThatEndedBeforeTheEarliestCursorOfTheMarketAreLeftOut() {
        RoundWindow hourlyCursor = hourly("BTC", 5 * STEP);
        when(roundResults.settledCloses(eq("BTC"), eq(Interval.HOURLY), anyLong())).thenReturn(List.of(
                new RoundResultRepository.SettledClose(4 * STEP, 9_900_00L),
                new RoundResultRepository.SettledClose(5 * STEP, 10_000_00L)));

        prices.adopt(loadWith(cursorsOf(hourlyCursor)));

        assertThat(prices.closeAt("BTC", 4 * STEP)).as("ends before the cursor starts: no round can open there")
                .isEmpty();
        assertThat(prices.closeAt("BTC", 5 * STEP)).isPresent();
    }

    @Test
    void whereTwoSettledRowsEndAtTheSameBoundaryWithDifferentClosesTheLongerSeriesWins() {
        when(roundResults.settledCloses(eq("BTC"), eq(Interval.HOURLY), anyLong())).thenReturn(List.of(
                new RoundResultRepository.SettledClose(DAY, 10_001_00L)));
        when(roundResults.settledCloses(eq("BTC"), eq(Interval.DAILY), anyLong())).thenReturn(List.of(
                new RoundResultRepository.SettledClose(DAY, 10_000_00L)));

        prices.adopt(loadWith(cursorsOf(hourly("BTC", DAY - STEP), daily("BTC", DAY))));

        assertThat(prices.closeAt("BTC", DAY)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("10000.00"));
    }

    @Test
    void marketsWithoutACursorAreNotLoadedAndTheDatabaseIsAskedPerSeries() {
        prices.adopt(loadWith(cursorsOf(hourly("BTC", STEP))));

        org.mockito.Mockito.verify(roundResults).settledCloses(eq("BTC"), eq(Interval.HOURLY), anyLong());
        org.mockito.Mockito.verify(roundResults).settledCloses(eq("BTC"), eq(Interval.DAILY), anyLong());
        org.mockito.Mockito.verify(roundResults, org.mockito.Mockito.never()).settledCloses(eq("ETH"), org.mockito.ArgumentMatchers.any(),
                anyLong());
    }

    // ---------------------------------------------------------------------------------------
    // settled: added after the commit, pruned behind the cursors
    // ---------------------------------------------------------------------------------------

    @Test
    void aCommittedSettlementIsRememberedAndWhatNoCursorCanNeedAnyMoreIsDropped() {
        progress.seed("BTC", Interval.HOURLY, hourly("BTC", 3 * STEP));
        progress.seed("BTC", Interval.DAILY, daily("BTC", 0L));
        prices.settled(hourly("BTC", STEP), 100_00L);
        prices.settled(hourly("BTC", 2 * STEP), 200_00L);
        prices.settled(hourly("BTC", 3 * STEP), 300_00L);

        assertThat(prices.closeAt("BTC", 2 * STEP)).as("the day's cursor is at 0: nothing is behind it yet").isPresent();
        assertThat(prices.closeAt("BTC", 4 * STEP)).isPresent();

        progress.seed("BTC", Interval.DAILY, daily("BTC", 2 * DAY)); // the day moved on, past the hour's cursor
        prices.settled(hourly("BTC", 4 * STEP), 400_00L);

        assertThat(prices.closeAt("BTC", 2 * STEP)).as("ended before every cursor: dropped").isEmpty();
        assertThat(prices.closeAt("BTC", 3 * STEP)).as("the hourly cursor still starts at 3 steps").isPresent();
        assertThat(prices.closeAt("BTC", 5 * STEP)).isPresent();
    }

    @Test
    void withoutAnyCursorNothingIsPruned() {
        prices.settled(hourly("BTC", STEP), 100_00L);
        prices.settled(hourly("BTC", 2 * STEP), 200_00L);

        assertThat(prices.closeAt("BTC", 2 * STEP)).isPresent();
        assertThat(prices.closeAt("BTC", 3 * STEP)).isPresent();
    }

    // ---------------------------------------------------------------------------------------
    // replay mode
    // ---------------------------------------------------------------------------------------

    @Test
    void inReplayModeNothingIsLoadedRememberedOrOverridden() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        RoundPriceData data = new RoundPriceData(new BigDecimal("100.00"), new BigDecimal("101.00"), List.of(), 1L);

        BoundaryPrices.Snapshot snapshot = loadWith(cursorsOf(hourly("BTC", STEP)));
        prices.adopt(snapshot);
        prices.settled(hourly("BTC", 0L), 99_00L);

        assertThat(snapshot.of("BTC")).isEmpty();
        assertThat(prices.closeAt("BTC", STEP)).isEmpty();
        assertThat(prices.forSettlement(hourly("BTC", STEP), data)).isSameAs(data);
        assertThat(prices.forRunning(BTC, hourly("BTC", STEP), Optional.empty())).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(roundResults);
    }
}
