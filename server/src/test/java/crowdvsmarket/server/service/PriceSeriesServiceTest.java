package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.price.PriceService;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.testsupport.PlainMarketRegistry;
import crowdvsmarket.server.testsupport.ScriptedMarketPrices;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code priceSeries(marketId, sinceMs?)} with plain objects: the last 15 minutes or the ticks strictly after
 * {@code sinceMs}, raw strings with the market's decimals, at most 600 points thinned over the range with the
 * newest kept, a closed market anchored at its closing, replay mode's BTC sampled and everything else not found.
 */
class PriceSeriesServiceTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long MINUTE = 60_000L;

    private PlainMarketRegistry registry;
    private ScriptedMarketPrices prices;
    private GameConfig config;
    private PriceService replay;
    private PriceSeriesService service;
    private Market btc;
    private Market odds;
    private Market lot4;

    @BeforeEach
    void anApplicationOfPlainObjects() {
        registry = new PlainMarketRegistry();
        prices = new ScriptedMarketPrices();
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        replay = mock(PriceService.class);
        service = new PriceSeriesService(registry, prices, config, PricesConfig.defaults(), replay);
        btc = registry.require("BTC");
        odds = registry.list(new NewMarket("PMODDS", MarketKind.ODDS, Category.POLITICS, "ODD", "t", null, null,
                "synthetic", "PMODDS", null, 10, Market.ODDS_LOT_EXP, null), 1L);
        lot4 = registry.list(new NewMarket("PERP4", MarketKind.PERP, Category.CRYPTO, "P4", "t", null, null,
                "synthetic", "PERP4", null, 12, 4, null), 2L);
    }

    /** One tick a second from {@code fromMs} to {@code toMs} inclusive; the price climbs a cent a second. */
    private static List<PricePoint> everySecond(long fromMs, long toMs) {
        List<PricePoint> points = new ArrayList<>();
        for (long at = fromMs; at <= toMs; at += 1_000L) {
            points.add(new PricePoint(at, new BigDecimal("84000.00").add(BigDecimal.valueOf((at - fromMs) / 1_000L, 2))));
        }
        return points;
    }

    @Test
    void withoutSinceTheLastFifteenMinutesAreReturnedThinnedToSixHundredWithTheNewestKept() {
        prices.setSeries(btc, everySecond(NOW - 20 * MINUTE, NOW)); // 1 201 ticks, 901 of them in the window

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", null, NOW);

        assertThat(answer.marketId()).isEqualTo("BTC");
        assertThat(answer.priceDecimals()).isEqualTo(2);
        assertThat(answer.ticks()).hasSize(600);
        assertThat(answer.ticks().get(0).atMs()).as("the oldest tick of the window").isEqualTo(NOW - 15 * MINUTE);
        assertThat(answer.ticks().get(599).atMs()).as("the newest tick is always kept").isEqualTo(NOW);
        assertThat(answer.ticks()).extracting(Markets.PriceTickDto::atMs).isSorted().doesNotHaveDuplicates();
        assertThat(answer.ticks().get(599).price()).matches("\\d+\\.\\d{2}");
    }

    @Test
    void aWindowOfFewerThanSixHundredTicksIsReturnedWhole() {
        prices.setSeries(btc, everySecond(NOW - 20 * MINUTE, NOW));

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", NOW - 100_000L, NOW);

        assertThat(answer.ticks()).hasSize(100); // the 100 ticks strictly after NOW − 100 s: NOW − 99 s … NOW
        assertThat(answer.ticks().get(0).atMs()).isEqualTo(NOW - 99_000L);
        assertThat(answer.ticks().get(99).atMs()).isEqualTo(NOW);
        assertThat(answer.ticks()).extracting(Markets.PriceTickDto::atMs).isSorted();
    }

    @Test
    void sinceIsExclusive() {
        prices.setSeries(btc, everySecond(NOW - 10_000L, NOW));

        assertThat(service.priceSeries("BTC", NOW - 3_000L, NOW).ticks()).extracting(Markets.PriceTickDto::atMs)
                .containsExactly(NOW - 2_000L, NOW - 1_000L, NOW);
        assertThat(service.priceSeries("BTC", NOW, NOW).ticks()).as("nothing after the newest tick").isEmpty();
        assertThat(service.priceSeries("BTC", NOW + 5_000L, NOW).ticks()).as("a since in the future").isEmpty();
    }

    @Test
    void aSinceAtOrBeyondTheEndOfTheRangeIsEmptyEvenAtTheLargestNumber() {
        prices.setSeries(btc, everySecond(NOW - 10_000L, NOW));

        assertThat(service.priceSeries("BTC", Long.MAX_VALUE, NOW).ticks()).isEmpty();
        assertThat(service.priceSeries("BTC", Long.MAX_VALUE - 1, NOW).ticks()).isEmpty();
        assertThat(service.priceSeries("BTC", NOW + 1L, NOW).ticks()).isEmpty();
        assertThat(service.priceSeries("BTC", Long.MIN_VALUE, NOW).ticks()).as("the oldest since there is: all of it")
                .hasSize(11);
    }

    @Test
    void sinceIsHonouredPastFifteenMinutesAndThinnedIfItAsksForMoreThanSixHundred() {
        prices.setSeries(btc, everySecond(NOW - 40 * MINUTE, NOW)); // 2 401 ticks: the whole buffer (40 minutes)

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", NOW - 30 * MINUTE, NOW);

        assertThat(answer.ticks()).hasSize(600);
        assertThat(answer.ticks().get(0).atMs()).as("spans the whole requested range")
                .isEqualTo(NOW - 30 * MINUTE + 1_000L);
        assertThat(answer.ticks().get(599).atMs()).isEqualTo(NOW);
    }

    @Test
    void aSinceBeyondTheBufferIsCutToTheBuffersLength() {
        // PricesConfig.defaults(): the buffer keeps 2 x 15 minutes + 10 = 40 minutes
        prices.setSeries(btc, everySecond(NOW - 60 * MINUTE, NOW));

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", 0L, NOW);

        assertThat(answer.ticks().get(0).atMs()).isGreaterThanOrEqualTo(NOW - 40 * MINUTE);
        assertThat(answer.ticks()).hasSize(600);
    }

    @Test
    void thePricesAreRawStringsWithTheMarketsOwnDecimals() {
        prices.setSeries(odds, List.of(new PricePoint(NOW - 2_000L, new BigDecimal("0.5")),
                new PricePoint(NOW - 1_000L, new BigDecimal("0.5005")), new PricePoint(NOW, new BigDecimal("0.4995"))));
        prices.setSeries(lot4, List.of(new PricePoint(NOW, new BigDecimal("0.4511"))));
        prices.setSeries(btc, List.of(new PricePoint(NOW, new BigDecimal("84012.3"))));

        Markets.PriceSeriesDto oddsAnswer = service.priceSeries("PMODDS", null, NOW);
        assertThat(oddsAnswer.priceDecimals()).isEqualTo(6);
        assertThat(oddsAnswer.ticks()).extracting(Markets.PriceTickDto::price)
                .containsExactly("0.500000", "0.500500", "0.499500");
        assertThat(service.priceSeries("PERP4", null, NOW).ticks()).extracting(Markets.PriceTickDto::price)
                .containsExactly("0.451100");
        assertThat(service.priceSeries("PERP4", null, NOW).priceDecimals()).isEqualTo(6);
        assertThat(service.priceSeries("BTC", null, NOW).ticks()).extracting(Markets.PriceTickDto::price)
                .containsExactly("84012.30");
    }

    @Test
    void aMarketWithoutTicksHasAnEmptySeries() {
        Markets.PriceSeriesDto answer = service.priceSeries("PERP4", null, NOW);

        assertThat(answer.ticks()).isEmpty();
        assertThat(answer.priceDecimals()).isEqualTo(6);
    }

    @Test
    void anUnknownMarketIsMarketNotFound() {
        assertThatThrownBy(() -> service.priceSeries("NOPE", null, NOW)).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.MARKET_NOT_FOUND);
                    assertThat(e.httpStatus()).isEqualTo(404);
                });
    }

    @Test
    void aClosedMarketsLastFifteenMinutesAreTheFifteenBeforeItClosed() {
        long closedAt = NOW - 60 * MINUTE; // closed an hour ago
        prices.setSeries(odds, everySecond(closedAt - 20 * MINUTE, closedAt + 5_000L).stream()
                .map(p -> new PricePoint(p.atMs(), new BigDecimal("0.5"))).toList());
        registry.close("PMODDS", "The source market ended", closedAt);

        Markets.PriceSeriesDto answer = service.priceSeries("PMODDS", null, NOW);

        assertThat(answer.ticks()).isNotEmpty();
        assertThat(answer.ticks().get(0).atMs()).isGreaterThanOrEqualTo(closedAt - 15 * MINUTE);
        assertThat(answer.ticks().get(answer.ticks().size() - 1).atMs()).as("nothing after it closed")
                .isLessThanOrEqualTo(closedAt);
    }

    // ---------------------------------------------------------------------------------------
    // mode=replay
    // ---------------------------------------------------------------------------------------

    private void replayMode() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        when(replay.replayReference(anyLong())).thenAnswer(inv -> {
            long at = inv.getArgument(0);
            return new PriceService.Reference(new BigDecimal("83000").add(BigDecimal.valueOf(at / 3_000L % 100)), at);
        });
    }

    @Test
    void inReplayModeBtcIsTheReplaySampledOncePerWholeSecond() {
        replayMode();

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", NOW - 10_000L, NOW);

        assertThat(answer.marketId()).isEqualTo("BTC");
        assertThat(answer.priceDecimals()).isEqualTo(2);
        assertThat(answer.ticks()).extracting(Markets.PriceTickDto::atMs)
                .containsExactly(NOW - 9_000L, NOW - 8_000L, NOW - 7_000L, NOW - 6_000L, NOW - 5_000L, NOW - 4_000L,
                        NOW - 3_000L, NOW - 2_000L, NOW - 1_000L, NOW);
        // each tick is what referencePrice would have said at that second, with the market's two decimals
        for (Markets.PriceTickDto tick : answer.ticks()) {
            assertThat(tick.price()).isEqualTo(replay.replayReference(tick.atMs()).price().setScale(2).toPlainString());
        }
    }

    @Test
    void inReplayModeTheDefaultWindowIsFifteenMinutesThinnedToSixHundred() {
        replayMode();

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", null, NOW);

        assertThat(answer.ticks()).hasSize(600);
        assertThat(answer.ticks().get(0).atMs()).isEqualTo(NOW - 15 * MINUTE);
        assertThat(answer.ticks().get(599).atMs()).isEqualTo(NOW);
    }

    @Test
    void inReplayModeASinceAnHourBackIsCutToTheBufferLikeInLiveMode() {
        replayMode();

        Markets.PriceSeriesDto answer = service.priceSeries("BTC", 0L, NOW);

        assertThat(answer.ticks().get(0).atMs()).isGreaterThanOrEqualTo(NOW - 40 * MINUTE);
        assertThat(answer.ticks()).hasSize(600);
    }

    @Test
    void inReplayModeASinceBeyondTheEndIsEmptyAndNeverWalkedTowards() {
        replayMode();

        long started = System.nanoTime();
        assertThat(service.priceSeries("BTC", Long.MAX_VALUE, NOW).ticks()).isEmpty();
        assertThat(service.priceSeries("BTC", NOW, NOW).ticks()).isEmpty();
        assertThat(service.priceSeries("BTC", Long.MIN_VALUE, NOW).ticks()).as("cut to the look-back: thinned").hasSize(600);

        assertThat(System.nanoTime() - started).as("no walk over billions of seconds").isLessThan(5_000_000_000L);
    }

    @Test
    void inReplayModeEveryOtherMarketIsNotFoundEvenIfTheRegistryHoldsIt() {
        replayMode();

        assertThatThrownBy(() -> service.priceSeries("PMODDS", null, NOW)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.MARKET_NOT_FOUND));
        assertThatThrownBy(() -> service.priceSeries("PERP4", NOW - 1_000L, NOW)).isInstanceOfSatisfying(
                ApiException.class, e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.MARKET_NOT_FOUND));
    }
}
