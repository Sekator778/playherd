package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.price.PriceService;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.testsupport.PlainMarketRegistry;
import crowdvsmarket.server.testsupport.ScriptedMarketPrices;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read model of the markets with plain objects for the application — a real engine hub, round progress
 * and bet books, an in-memory registry, scripted prices, a real {@code GameService} only for its read lock
 * and its price accessors: what {@code markets}, {@code myPositions} and {@code crowdOverview} say about
 * markets of every kind (a PERP with lot exponent 0 and one with lot exponent 4, an ODDS market), in both
 * modes, with the answer cached for about a second and no answer at all while the engine is not ready.
 */
class MarketViewServiceTest {

    private static final long HOUR_MS = 60_000L;
    private static final long DAY_MS = 120_000L;
    /** The running window of every series starts here; betting is open for the one after it. */
    private static final long T0 = 1_790_000_040_000L;
    private static final long NOW = T0 + 10_000L;
    private static final Instant BET_TIME = Instant.ofEpochMilli(T0 - 5_000L);

    private EngineHub hub;
    private RoundProgress progress;
    private PlainMarketRegistry registry;
    private ScriptedMarketPrices prices;
    private GameConfig config;
    private EngineRebuildService rebuild;
    private PriceService replay;
    private GameService gameService;
    private MarketViewService view;

    private Market btc;
    private Market lot4;
    private Market odds;

    @BeforeEach
    void anApplicationOfPlainObjects() {
        hub = new EngineHub();
        progress = new RoundProgress();
        registry = new PlainMarketRegistry();
        prices = new ScriptedMarketPrices();
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        when(config.roundDurations()).thenReturn(new RoundDurations(HOUR_MS, DAY_MS));
        rebuild = mock(EngineRebuildService.class);
        when(rebuild.isReady()).thenReturn(true);
        replay = mock(PriceService.class);

        gameService = new GameService();
        gameService.config = config;
        gameService.hub = hub;
        gameService.roundProgress = progress;
        gameService.rebuildService = rebuild;
        gameService.marketPrices = prices;
        gameService.priceService = replay;
        gameService.marketRegistry = registry;
        gameService.boundaries = new BoundaryPrices(); // nothing settled before: the source's prices as they are
        view = new MarketViewService(gameService, config, registry, hub, progress,
                new OpenBets(hub, progress));

        btc = registry.require("BTC");
        lot4 = list("PERP4", MarketKind.PERP, Category.CRYPTO, "P4", 12, 4, null, null);
        odds = list("PMODDS", MarketKind.ODDS, Category.POLITICS, "ODD", 10, Market.ODDS_LOT_EXP,
                "{\"gameStartTime\":\"2026-10-01 18:45:00+00\"}", 1_790_100_000_000L);
        for (Market market : List.of(btc, lot4, odds)) {
            run(market);
        }
    }

    private Market list(String id, MarketKind kind, String category, String symbol, int maxLeverage, int lotExp,
                        String sourceMeta, Long endsAtMs) {
        return registry.list(new NewMarket(id, kind, category, symbol, symbol + " title",
                kind == MarketKind.ODDS ? "Yes · " + symbol : null, kind == MarketKind.ODDS ? "https://img/" + id : null,
                "synthetic", id, sourceMeta, maxLeverage, lotExp, endsAtMs), 1_000L);
    }

    /** Engines and a series per interval at the common running window. */
    private void run(Market market) {
        hub.ensureMarket(market);
        for (Interval interval : Interval.values()) {
            progress.seed(market.id(), interval, running(market, interval));
        }
    }

    private static RoundWindow running(Market market, Interval interval) {
        long length = interval == Interval.HOURLY ? HOUR_MS : DAY_MS;
        return new RoundWindow(market.id(), interval, T0, T0 + length);
    }

    private static RoundWindow betting(Market market, Interval interval) {
        return RoundClock.next(running(market, interval));
    }

    private void bet(String player, Market market, Interval interval, RoundWindow window, Direction direction,
                     int leverage, long wholePlay) {
        hub.engine(market.id(), interval).placeBet(player, window, direction, new Leverage(leverage),
                PlayAmount.ofWholePlay(wholePlay), BET_TIME);
    }

    private Markets.MarketDto dto(Markets.MarketsDto answer, String id) {
        return answer.markets().stream().filter(m -> m.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + id + " in " + answer.markets()));
    }

    // ---------------------------------------------------------------------------------------
    // markets
    // ---------------------------------------------------------------------------------------

    @Test
    void everyFieldOfAMarketComesFromItsRecordItsQuoteItsSeriesAndItsCrowd() {
        prices.setQuote(odds, "0.64", NOW - 1_200L, 0.0156, false);
        bet("p1", odds, Interval.HOURLY, betting(odds, Interval.HOURLY), Direction.UP, 5, 300);
        bet("p2", odds, Interval.HOURLY, betting(odds, Interval.HOURLY), Direction.DOWN, 2, 100);

        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(answer.serverTimeMs()).isEqualTo(NOW);
        assertThat(answer.markets()).extracting(Markets.MarketDto::id).containsExactly("BTC", "PERP4", "PMODDS");
        Markets.MarketDto market = dto(answer, "PMODDS");
        assertThat(market.kind()).isEqualTo(Enums.MarketKind.ODDS);
        assertThat(market.category()).isEqualTo("politics");
        assertThat(market.symbol()).isEqualTo("ODD");
        assertThat(market.title()).isEqualTo("ODD title");
        assertThat(market.subtitle()).isEqualTo("Yes · ODD");
        assertThat(market.iconUrl()).isEqualTo("https://img/PMODDS");
        assertThat(market.maxLeverage()).isEqualTo(10);
        assertThat(market.priceDecimals()).isEqualTo(6);
        assertThat(market.status()).isEqualTo(Enums.MarketStatus.ACTIVE);
        assertThat(market.endsAtMs()).isEqualTo(1_790_100_000_000L);
        assertThat(market.eventStartsAtMs()).isEqualTo(Instant.parse("2026-10-01T18:45:00Z").toEpochMilli());
        assertThat(market.closedAtMs()).isNull();
        assertThat(market.closeReason()).isNull();
        assertThat(market.oracleIndex()).isEqualTo(odds.oracleIndex());
        assertThat(market.source()).isEqualTo("synthetic");
        assertThat(market.price()).isEqualTo(new Markets.MarketPriceDto("0.640000", NOW - 1_200L, 0.0156, false));

        assertThat(market.series()).hasSize(2);
        Markets.MarketSeriesDto hourly = market.series().get(0);
        assertThat(hourly.interval()).isEqualTo(Enums.Interval.HOURLY);
        assertThat(hourly.durationSec()).isEqualTo(60);
        assertThat(hourly.runningRoundId()).isEqualTo(running(odds, Interval.HOURLY).roundId());
        assertThat(hourly.runningEndsAtMs()).isEqualTo(T0 + HOUR_MS);
        assertThat(hourly.bettingRoundId()).isEqualTo(betting(odds, Interval.HOURLY).roundId());
        assertThat(hourly.bettingClosesAtMs()).isEqualTo(T0 + HOUR_MS);
        assertThat(hourly.participantCount()).isEqualTo(2);
        assertThat(hourly.upStake()).isEqualTo("300.000");
        assertThat(hourly.downStake()).isEqualTo("100.000");
        assertThat(hourly.majorityDirection()).isEqualTo(Enums.Direction.UP);
        Markets.MarketSeriesDto daily = market.series().get(1);
        assertThat(daily.interval()).isEqualTo(Enums.Interval.DAILY);
        assertThat(daily.durationSec()).isEqualTo(120);
        assertThat(daily.bettingClosesAtMs()).isEqualTo(T0 + DAY_MS);
        assertThat(daily.participantCount()).isZero();
        assertThat(daily.upStake()).isEqualTo("0.000");
        assertThat(daily.downStake()).isEqualTo("0.000");
        assertThat(daily.majorityDirection()).as("0 vs 0 is a tie").isNull();

        assertThat(market.crowd()).isEqualTo(new Markets.MarketCrowdBriefDto("100000.00", 0.0, 0, 0, null));
    }

    @Test
    void pricesAreRawStringsWithTheMarketsOwnDecimals() {
        prices.setQuote(btc, "84012.3", NOW - 500L, null, false);       // a PERP with lot exponent 0
        prices.setQuote(lot4, "0.4511", NOW - 400L, -0.002, false);      // a PERP with lot exponent 4
        prices.setQuote(odds, "0.5230", NOW - 300L, 0.01, true);         // an ODDS market (lot exponent 4)

        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(dto(answer, "BTC").priceDecimals()).isEqualTo(2);
        assertThat(dto(answer, "BTC").price().price()).isEqualTo("84012.30");
        assertThat(dto(answer, "BTC").price().change24h()).as("the source gave none").isNull();
        assertThat(dto(answer, "PERP4").priceDecimals()).isEqualTo(6);
        assertThat(dto(answer, "PERP4").price().price()).isEqualTo("0.451100");
        assertThat(dto(answer, "PERP4").price().change24h()).isEqualTo(-0.002);
        assertThat(dto(answer, "PMODDS").priceDecimals()).isEqualTo(6);
        assertThat(dto(answer, "PMODDS").price().price()).isEqualTo("0.523000");
        assertThat(dto(answer, "PMODDS").price().stale()).isTrue();
    }

    @Test
    void aMarketWithNoTickYetHasNoPrice() {
        prices.setQuote(btc, "84012.30", NOW, null, false);

        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(dto(answer, "PERP4").price()).isNull();
        assertThat(dto(answer, "BTC").price()).isNotNull();
    }

    @Test
    void theCategoriesAreTheNonEmptyOnesInDisplayOrderCountedByActiveMarkets() {
        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(answer.categories()).extracting(Markets.CategoryDto::id).containsExactly("crypto", "politics");
        assertThat(answer.categories()).extracting(Markets.CategoryDto::title).containsExactly("Crypto", "Politics");
        assertThat(answer.categories()).extracting(Markets.CategoryDto::marketCount).containsExactly(2, 1);

        Market sports = list("PMSPORT", MarketKind.ODDS, Category.SPORTS, "GER", 10, Market.ODDS_LOT_EXP, null, null);
        run(sports);
        Market stock = list("xyz_NVDA", MarketKind.PERP, Category.STOCKS, "NVDA", 20, 1, null, null);
        run(stock);
        view.invalidate();

        Markets.MarketsDto more = view.markets(NOW);
        assertThat(more.categories()).extracting(Markets.CategoryDto::id)
                .containsExactly("crypto", "politics", "sports", "stocks");
        assertThat(more.categories()).extracting(Markets.CategoryDto::marketCount).containsExactly(2, 1, 1, 1);
    }

    @Test
    void aClosedMarketStaysForTenMinutesWithItsReasonAndNoSeriesAndIsNotCounted() {
        registry.close("PMODDS", "The source market ended", NOW);
        progress.remove("PMODDS", Interval.HOURLY); // what GameService#onClosed does: the series stops
        progress.remove("PMODDS", Interval.DAILY);

        Markets.MarketsDto justClosed = view.markets(NOW + 1_000L);

        Markets.MarketDto closed = dto(justClosed, "PMODDS");
        assertThat(closed.status()).isEqualTo(Enums.MarketStatus.CLOSED);
        assertThat(closed.closedAtMs()).isEqualTo(NOW);
        assertThat(closed.closeReason()).isEqualTo("The source market ended");
        assertThat(closed.series()).isEmpty();
        assertThat(justClosed.categories()).extracting(Markets.CategoryDto::id)
                .as("a category with only a closed market is not listed").containsExactly("crypto");

        view.invalidate();
        assertThat(view.markets(NOW + 600_000L).markets()).extracting(Markets.MarketDto::id)
                .as("closed exactly ten minutes ago: still there").contains("PMODDS");
        view.invalidate();
        assertThat(view.markets(NOW + 600_001L).markets()).extracting(Markets.MarketDto::id)
                .as("a moment later it has left").containsExactly("BTC", "PERP4");
    }

    @Test
    void aMarketWhoseRoundsAreNotRunningHasAnEmptySeries() {
        progress.remove("PERP4", Interval.HOURLY);
        progress.remove("PERP4", Interval.DAILY);

        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(dto(answer, "PERP4").series()).isEmpty();
        assertThat(dto(answer, "BTC").series()).hasSize(2);
    }

    @Test
    void theSeriesAreListedShortestFirstWhateverTheirNamesAre() {
        // the names are historical: here the "hourly" series is the LONGER one
        when(config.roundDurations()).thenReturn(new RoundDurations(DAY_MS, HOUR_MS));
        for (Market market : List.of(btc, lot4, odds)) {
            for (Interval interval : Interval.values()) {
                long length = interval == Interval.HOURLY ? DAY_MS : HOUR_MS;
                progress.seed(market.id(), interval, new RoundWindow(market.id(), interval, T0, T0 + length));
            }
        }

        Markets.MarketDto market = dto(view.markets(NOW), "BTC");

        assertThat(market.series()).extracting(Markets.MarketSeriesDto::interval)
                .containsExactly(Enums.Interval.DAILY, Enums.Interval.HOURLY);
        assertThat(market.series()).extracting(Markets.MarketSeriesDto::durationSec).containsExactly(60L, 120L);
    }

    @Test
    void theCrowdBriefCountsTradedAndWonRoundsAndSaysHowTheLastEnded() {
        // BTC: two players bet on the same round, the crowd trades the majority side (UP, 700 vs 100)
        RoundWindow window = running(btc, Interval.HOURLY);
        bet("p1", btc, Interval.HOURLY, window, Direction.UP, 3, 700);
        bet("p2", btc, Interval.HOURLY, window, Direction.DOWN, 3, 100);
        hub.engine("BTC", Interval.HOURLY).runRound(window, flatPrices("84000.00", "84900.00", "84000.00", "84900.00"));

        Markets.MarketCrowdBriefDto brief = dto(view.markets(NOW), "BTC").crowd();

        assertThat(brief.roundsTraded()).isEqualTo(1);
        assertThat(brief.roundsWon()).isEqualTo(1);
        assertThat(brief.lastOutcome()).isEqualTo(Enums.CrowdOutcome.WIN);
        assertThat(new BigDecimal(brief.capital())).isGreaterThan(new BigDecimal("100000.00"));
        assertThat(brief.returnPct()).isPositive();
        assertThat(brief.returnPct()).isEqualTo(new BigDecimal(brief.capital())
                .divide(new BigDecimal("100000.00")).subtract(BigDecimal.ONE).doubleValue());
        // the other markets' crowds are their own
        assertThat(dto(view.markets(NOW), "PERP4").crowd().roundsTraded()).isZero();
    }

    @Test
    void aTradedRoundThatClosedWhereItOpenedIsFlatNeitherWonNorLostAndTheNextOneTakesTheLastOutcomeOver() {
        // the crowd trades UP (500 vs 100) at 3x and the price ends exactly where it began, after a dip and a rally
        RoundWindow flat = running(btc, Interval.HOURLY);
        bet("p1", btc, Interval.HOURLY, flat, Direction.UP, 3, 500);
        bet("p2", btc, Interval.HOURLY, flat, Direction.DOWN, 3, 100);
        hub.engine("BTC", Interval.HOURLY).runRound(flat, flatPrices("84000.00", "84500.00", "83500.00", "84000.00"));

        Markets.MarketCrowdBriefDto brief = dto(view.markets(NOW), "BTC").crowd();

        assertThat(brief.roundsTraded()).isEqualTo(1);
        assertThat(brief.roundsWon()).as("level is not won").isZero();
        assertThat(brief.lastOutcome()).as("level is not lost either (contract.ts FLAT)")
                .isEqualTo(Enums.CrowdOutcome.FLAT);
        assertThat(brief.capital()).isEqualTo("100000.00");
        assertThat(brief.returnPct()).isZero();

        // a lost round after it is a loss, and a flat one never counted as won
        RoundWindow next = RoundClock.next(flat);
        bet("p1", btc, Interval.HOURLY, next, Direction.UP, 3, 500);
        hub.engine("BTC", Interval.HOURLY).runRound(next, flatPrices("84000.00", "84000.00", "83000.00", "83000.00"));
        view.invalidate(); // the lobby is kept for a second
        Markets.MarketCrowdBriefDto after = dto(view.markets(NOW), "BTC").crowd();
        assertThat(after.roundsTraded()).isEqualTo(2);
        assertThat(after.roundsWon()).isZero();
        assertThat(after.lastOutcome()).isEqualTo(Enums.CrowdOutcome.LOSS);
    }

    @Test
    void theLobbyIsKeptForAboutASecondAndRebuiltAfterIt() {
        prices.setQuote(btc, "84000.00", NOW, null, false);
        Markets.MarketsDto first = view.markets(NOW);
        assertThat(dto(first, "BTC").series().get(0).participantCount()).isZero();

        bet("p1", btc, Interval.HOURLY, betting(btc, Interval.HOURLY), Direction.UP, 2, 100);
        prices.setQuote(btc, "85000.00", NOW + 100, null, false);

        Markets.MarketsDto cached = view.markets(NOW + 500L);
        assertThat(cached.serverTimeMs()).as("the caller's own time, whatever the age of the rest").isEqualTo(NOW + 500L);
        assertThat(dto(cached, "BTC").series().get(0).participantCount()).as("still the old answer").isZero();
        assertThat(dto(cached, "BTC").price().price()).isEqualTo("84000.00");
        assertThat(cached.markets()).as("the very same lists").isSameAs(first.markets());

        Markets.MarketsDto rebuilt = view.markets(NOW + 1_000L);
        assertThat(dto(rebuilt, "BTC").series().get(0).participantCount()).isEqualTo(1);
        assertThat(dto(rebuilt, "BTC").price().price()).isEqualTo("85000.00");
        assertThat(rebuilt.serverTimeMs()).isEqualTo(NOW + 1_000L);
    }

    @Test
    void invalidateMakesTheNextCallAssembleAgain() {
        Markets.MarketsDto first = view.markets(NOW);
        bet("p1", btc, Interval.HOURLY, betting(btc, Interval.HOURLY), Direction.UP, 2, 100);

        view.invalidate();

        assertThat(dto(view.markets(NOW), "BTC").series().get(0).participantCount()).isEqualTo(1);
        assertThat(dto(first, "BTC").series().get(0).participantCount()).isZero();
    }

    @Test
    void aCachedAnswerIsNotGivenWhileTheEngineIsNotReady() {
        view.markets(NOW); // cached
        when(rebuild.isReady()).thenReturn(false);

        assertThatThrownBy(() -> view.markets(NOW + 10L)).isInstanceOfSatisfying(ApiException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                    assertThat(e.httpStatus()).isEqualTo(503);
                });
        assertThatThrownBy(() -> view.myPositions("p1", NOW)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY));
        assertThatThrownBy(() -> view.crowdOverview()).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY));

        when(rebuild.isReady()).thenReturn(true);
        assertThat(view.markets(NOW + 20L).markets()).isNotEmpty();
    }

    @Test
    void replayModePlaysBtcAlone() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        when(replay.replayReference(NOW)).thenReturn(new PriceService.Reference(new BigDecimal("83451.00"), NOW));
        prices.setQuote(lot4, "0.4511", NOW, null, false); // a leftover of an earlier live run: not shown

        Markets.MarketsDto answer = view.markets(NOW);

        assertThat(answer.markets()).extracting(Markets.MarketDto::id).containsExactly("BTC");
        assertThat(dto(answer, "BTC").price()).isEqualTo(new Markets.MarketPriceDto("83451.00", NOW, null, false));
        assertThat(answer.categories()).extracting(Markets.CategoryDto::id).containsExactly("crypto");
        assertThat(answer.categories()).extracting(Markets.CategoryDto::marketCount).containsExactly(1);
    }

    // ---------------------------------------------------------------------------------------
    // myPositions
    // ---------------------------------------------------------------------------------------

    @Test
    void aPlayerWithoutBetsHasNoPositions() {
        assertThat(view.myPositions("nobody", NOW)).isEmpty();
    }

    @Test
    void aPendingBetIsBettingWithNoEntryPriceAndAnOpenPositionIsRunningWithOne() {
        bet("p1", btc, Interval.HOURLY, betting(btc, Interval.HOURLY), Direction.DOWN, 5, 250);
        bet("p1", odds, Interval.DAILY, running(odds, Interval.DAILY), Direction.UP, 4, 400);
        prices.setSoFar(odds, "0.5000", "0.5100"); // opened at 0.5, now 0.51: UP at 4x is well clear of liquidation

        List<Markets.MyPositionDto> positions = view.myPositions("p1", NOW);

        assertThat(positions).hasSize(2);
        Markets.MyPositionDto betting = positions.stream().filter(p -> p.marketId().equals("BTC")).findFirst().orElseThrow();
        assertThat(betting.phase()).isEqualTo(Enums.RoundPhase.BETTING);
        assertThat(betting.symbol()).isEqualTo("BTC");
        assertThat(betting.roundId()).isEqualTo(betting(btc, Interval.HOURLY).roundId());
        assertThat(betting.interval()).isEqualTo(Enums.Interval.HOURLY);
        assertThat(betting.durationSec()).isEqualTo(60);
        assertThat(betting.direction()).isEqualTo(Enums.Direction.DOWN);
        assertThat(betting.leverage()).isEqualTo(5);
        assertThat(betting.stake()).isEqualTo("250.000");
        assertThat(betting.entryPrice()).isNull();
        assertThat(betting.liquidationPrice()).isNull();
        assertThat(betting.size()).isNull();
        assertThat(betting.liquidated()).isFalse();
        assertThat(betting.untilMs()).as("BETTING: when the round starts").isEqualTo(T0 + HOUR_MS);

        Markets.MyPositionDto running = positions.stream().filter(p -> p.marketId().equals("PMODDS")).findFirst().orElseThrow();
        assertThat(running.phase()).isEqualTo(Enums.RoundPhase.RUNNING);
        assertThat(running.roundId()).isEqualTo(running(odds, Interval.DAILY).roundId());
        assertThat(running.interval()).isEqualTo(Enums.Interval.DAILY);
        assertThat(running.durationSec()).isEqualTo(120);
        assertThat(running.stake()).isEqualTo("400.000");
        assertThat(running.entryPrice()).as("raw, six decimals").isEqualTo("0.500000");
        // UP at 4x with l = 1/20: liquidation = entry · (1 − 1/4) / (1 − 0.05) = 0.394737
        assertThat(running.liquidationPrice()).isEqualTo("0.394737");
        assertThat(running.size()).matches("\\d+\\.\\d{1,8}");
        assertThat(new BigDecimal(running.size())).isPositive();
        assertThat(running.liquidated()).isFalse();
        assertThat(running.untilMs()).as("RUNNING: when the round ends").isEqualTo(T0 + DAY_MS);
    }

    @Test
    void aRunningPositionThePathHasAlreadyLiquidatedSaysSo() {
        bet("p1", lot4, Interval.HOURLY, running(lot4, Interval.HOURLY), Direction.UP, 12, 300);
        prices.setSoFar(lot4, "0.4500", "0.4000"); // a candle down to 0.40: far past the 12x liquidation level

        Markets.MyPositionDto position = view.myPositions("p1", NOW).get(0);

        assertThat(position.phase()).isEqualTo(Enums.RoundPhase.RUNNING);
        assertThat(position.liquidated()).isTrue();
        assertThat(position.entryPrice()).isEqualTo("0.450000");
    }

    @Test
    void aRunningRoundWhoseOpeningPriceIsNotKnownYetHasNoEntryNoLiquidationPriceNoSize() {
        bet("p1", btc, Interval.DAILY, running(btc, Interval.DAILY), Direction.UP, 10, 100);
        // ScriptedMarketPrices.soFar is empty until scripted: the price source does not know the open yet

        Markets.MyPositionDto position = view.myPositions("p1", NOW).get(0);

        assertThat(position.phase()).isEqualTo(Enums.RoundPhase.RUNNING);
        assertThat(position.entryPrice()).isNull();
        assertThat(position.liquidationPrice()).isNull();
        assertThat(position.size()).isNull();
        assertThat(position.liquidated()).isFalse();
        assertThat(position.stake()).isEqualTo("100.000");
    }

    @Test
    void inReplayModeARunningBtcPositionIsPricedFromTheReplaySeries() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        when(replay.replayPrices()).thenReturn(flatPrices("50000.00", "50100.00", "49900.00", "50050.00"));
        bet("p1", btc, Interval.HOURLY, running(btc, Interval.HOURLY), Direction.UP, 10, 500);

        List<Markets.MyPositionDto> positions = view.myPositions("p1", NOW);

        assertThat(positions).hasSize(1);
        Markets.MyPositionDto position = positions.get(0);
        assertThat(position.phase()).isEqualTo(Enums.RoundPhase.RUNNING);
        assertThat(position.entryPrice()).as("the replay's open of the window, two decimals").isEqualTo("50000.00");
        assertThat(position.liquidationPrice()).as("UP at 10x with l = 1/80").isEqualTo("45569.62");
        assertThat(new BigDecimal(position.size())).isEqualByComparingTo("0.1");
        assertThat(position.liquidated()).isFalse();
        assertThat(position.untilMs()).isEqualTo(T0 + HOUR_MS);
    }

    @Test
    void positionsComeSoonestEndingFirstAndARunningRoundBeforeTheBettingOneThatStartsWhenItEnds() {
        bet("p1", btc, Interval.DAILY, betting(btc, Interval.DAILY), Direction.UP, 2, 100);   // starts T0+120 s
        bet("p1", btc, Interval.HOURLY, betting(btc, Interval.HOURLY), Direction.UP, 2, 110); // starts T0+60 s
        bet("p1", btc, Interval.HOURLY, running(btc, Interval.HOURLY), Direction.UP, 2, 120); // ends   T0+60 s
        bet("p1", lot4, Interval.DAILY, running(lot4, Interval.DAILY), Direction.UP, 2, 130); // ends   T0+120 s

        List<Markets.MyPositionDto> positions = view.myPositions("p1", NOW);

        // at one instant: by market, and the running round (which ends then) before the betting one (which starts then)
        assertThat(positions).extracting(p -> p.untilMs() + "/" + p.marketId() + "/" + p.phase()).containsExactly(
                (T0 + HOUR_MS) + "/BTC/RUNNING", (T0 + HOUR_MS) + "/BTC/BETTING",
                (T0 + DAY_MS) + "/BTC/BETTING", (T0 + DAY_MS) + "/PERP4/RUNNING");
    }

    @Test
    void theStakesListedAreExactlyWhatIsInPlay() {
        bet("p1", btc, Interval.HOURLY, betting(btc, Interval.HOURLY), Direction.UP, 2, 100);
        bet("p1", lot4, Interval.DAILY, running(lot4, Interval.DAILY), Direction.DOWN, 2, 230);
        bet("p1", odds, Interval.HOURLY, betting(odds, Interval.HOURLY), Direction.UP, 2, 345);
        bet("p2", odds, Interval.HOURLY, betting(odds, Interval.HOURLY), Direction.UP, 2, 999);

        BigDecimal listed = view.myPositions("p1", NOW).stream().map(p -> new BigDecimal(p.stake()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(listed).isEqualByComparingTo("675.000");
        assertThat(new OpenBets(hub, progress).stake("p1").toMoney()).isEqualByComparingTo(listed);
    }

    // ---------------------------------------------------------------------------------------
    // crowdOverview
    // ---------------------------------------------------------------------------------------

    @Test
    void theOverviewHasARowPerMarketAndTotalsOverThem() {
        RoundWindow window = running(btc, Interval.HOURLY);
        bet("p1", btc, Interval.HOURLY, window, Direction.UP, 3, 500);
        hub.engine("BTC", Interval.HOURLY).runRound(window, flatPrices("84000.00", "84900.00", "84000.00", "84900.00"));

        Markets.CrowdOverviewDto overview = view.crowdOverview();

        assertThat(overview.markets()).extracting(Markets.CrowdOverviewRowDto::marketId)
                .containsExactly("BTC", "PERP4", "PMODDS");
        Markets.CrowdOverviewRowDto row = overview.markets().get(0);
        assertThat(row.symbol()).isEqualTo("BTC");
        assertThat(row.title()).isEqualTo("Bitcoin");
        assertThat(row.status()).isEqualTo(Enums.MarketStatus.ACTIVE);
        assertThat(row.category()).isEqualTo("crypto");
        assertThat(row.roundsTraded()).isEqualTo(1);
        assertThat(row.roundsWon()).isEqualTo(1);
        BigDecimal btcCapital = new BigDecimal(row.capital());
        assertThat(btcCapital).isGreaterThan(new BigDecimal("100000.00"));
        assertThat(row.returnPct()).isPositive();
        assertThat(overview.markets().get(1).capital()).isEqualTo("100000.00");
        assertThat(overview.markets().get(1).returnPct()).isZero();
        assertThat(overview.totalStartingCapital()).isEqualTo("300000.00");
        assertThat(new BigDecimal(overview.totalCapital())).isEqualByComparingTo(btcCapital.add(new BigDecimal("200000.00")));
        assertThat(overview.returnPct()).isEqualTo(new BigDecimal(overview.totalCapital())
                .divide(new BigDecimal("300000.00"), java.math.MathContext.DECIMAL128).subtract(BigDecimal.ONE).doubleValue());
    }

    @Test
    void aClosedMarketKeepsItsRowWithItsStatus() {
        registry.close("PMODDS", "The source market ended", NOW);

        Markets.CrowdOverviewDto overview = view.crowdOverview();

        Markets.CrowdOverviewRowDto row = overview.markets().stream().filter(r -> r.marketId().equals("PMODDS"))
                .findFirst().orElseThrow();
        assertThat(row.status()).isEqualTo(Enums.MarketStatus.CLOSED);
        assertThat(row.title()).isEqualTo("ODD title");
        assertThat(row.category()).isEqualTo("politics");
        assertThat(overview.totalStartingCapital()).as("it still counts in the totals").isEqualTo("300000.00");
    }

    @Test
    void inReplayModeOnlyBtcHasARowAndTheTotalsAreItsOwn() {
        when(config.mode()).thenReturn(GameMode.REPLAY);

        Markets.CrowdOverviewDto overview = view.crowdOverview();

        assertThat(overview.markets()).extracting(Markets.CrowdOverviewRowDto::marketId).containsExactly("BTC");
        assertThat(overview.totalCapital()).isEqualTo("100000.00");
        assertThat(overview.totalStartingCapital()).isEqualTo("100000.00");
        assertThat(overview.returnPct()).isZero();
    }

    // ---------------------------------------------------------------------------------------

    /** A round whose one candle runs open → high → low → close, as engine (lot) prices. */
    private static RoundPrices flatPrices(String open, String high, String low, String close) {
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow window) {
                return new BigDecimal(open);
            }

            @Override
            public BigDecimal closePrice(RoundWindow window) {
                return new BigDecimal(close);
            }

            @Override
            public List<Candle> path(RoundWindow window) {
                return List.of(new Candle(window.startMs(), window.endMs() - 1, new BigDecimal(open),
                        new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), BigDecimal.ZERO, 0));
            }
        };
    }
}
