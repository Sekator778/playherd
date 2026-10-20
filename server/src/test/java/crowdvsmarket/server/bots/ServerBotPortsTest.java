package crowdvsmarket.server.bots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BetResult;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PriceBar;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.service.GameService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The server's ports for the bots with every collaborator a fake: what they read, how they map it, what they refuse. */
class ServerBotPortsTest {

    private static final long STEP = 60_000L;
    private static final long RUNNING_START = 10 * STEP;
    private static final long NOW = RUNNING_START + 5_000L;

    private GameService gameService;
    private PlayerDirectory players;
    private RoundProgress progress;
    private RoundResultRepository roundResults;
    private MarketRegistry registry;
    private MarketPrices prices;
    private GameConfig config;
    private final Map<String, Optional<Direction>> memo = new HashMap<>();
    private Market btc;

    @BeforeEach
    void fakes() {
        gameService = mock(GameService.class);
        players = mock(PlayerDirectory.class);
        progress = new RoundProgress();
        roundResults = mock(RoundResultRepository.class);
        registry = mock(MarketRegistry.class);
        prices = mock(MarketPrices.class);
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        btc = new Market("BTC", MarketKind.PERP, "crypto", "BTC", "BTC", null, null, "synthetic", "BTC", null, 40, 0, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
        when(registry.byId("BTC")).thenReturn(Optional.of(btc));
        when(gameService.runningMarkets()).thenReturn(List.of(btc));
        progress.seed("BTC", Interval.HOURLY, new RoundWindow("BTC", Interval.HOURLY, RUNNING_START, RUNNING_START + STEP));
        when(prices.latest(any(Market.class))).thenReturn(Optional.of(new PriceQuote(new BigDecimal("84000"), NOW, null, false)));
    }

    private ServerBotPorts ports(Long windowStart, long now) {
        return new ServerBotPorts(gameService, players, progress, roundResults, registry, prices, config, Interval.HOURLY,
                windowStart, now, memo);
    }

    private static PlayerDirectory.Row bot(String id) {
        return new PlayerDirectory.Row(id, "txbot" + id, "Bot " + id, true, "momentum-3-disciplined", 1, 0L);
    }

    @Test
    void aSnapshotIsTheBettingRoundOfTheSeriesOpenWhileBettingLastsAndThePriceIsFresh() {
        List<MarketSnapshot> markets = ports(null, NOW).markets();

        assertThat(markets).hasSize(1);
        MarketSnapshot m = markets.get(0);
        assertThat(m.marketId()).isEqualTo("BTC");
        assertThat(m.interval()).isEqualTo(Interval.HOURLY);
        assertThat(m.roundId()).isEqualTo(new RoundWindow("BTC", Interval.HOURLY, RUNNING_START + STEP, RUNNING_START + 2 * STEP).roundId());
        assertThat(m.windowStartMs()).isEqualTo(RUNNING_START + STEP);
        assertThat(m.open()).isTrue();
        assertThat(m.minStake().isPositive()).isTrue();
        // the terms are the market's own: the same leverage the game would allow this stake
        assertThat(m.maxLeverage(m.minStake())).isGreaterThanOrEqualTo(2);
        assertThat(m.maxLeverage(PlayAmount.ofWholePlay(100_000))).isLessThanOrEqualTo(m.maxLeverage(m.minStake()));
    }

    @Test
    void aSnapshotIsNotOpenOnceBettingIsOverOrThePriceIsStaleOrMissingOrTheMarketIsClosed() {
        assertThat(ports(null, RUNNING_START + STEP).markets().get(0).open()).as("betting closes at the start of the round").isFalse();
        when(prices.latest(any(Market.class))).thenReturn(Optional.of(new PriceQuote(new BigDecimal("84000"), 0L, null, true)));
        assertThat(ports(null, NOW).markets().get(0).open()).as("stale price").isFalse();
        when(prices.latest(any(Market.class))).thenReturn(Optional.empty());
        assertThat(ports(null, NOW).markets().get(0).open()).as("no price").isFalse();
        when(config.mode()).thenReturn(GameMode.REPLAY);
        assertThat(ports(null, NOW).markets().get(0).open()).as("replay has no feed to ask").isTrue();
    }

    @Test
    void aWindowFilterKeepsOnlyTheMarketsWhoseBettingRoundStartsThen() {
        assertThat(ports(RUNNING_START + STEP, NOW).markets()).hasSize(1);
        assertThat(ports(RUNNING_START + 2 * STEP, NOW).markets()).isEmpty();
    }

    @Test
    void aSeriesNotSeededYetHasNoSnapshot() {
        Market eth = new Market("ETH", MarketKind.PERP, "crypto", "ETH", "ETH", null, null, "synthetic", "ETH", null, 25, 0, 2,
                MarketStatus.ACTIVE, 0L, null, null, null);
        when(gameService.runningMarkets()).thenReturn(List.of(btc, eth));
        assertThat(ports(null, NOW).markets()).extracting(MarketSnapshot::marketId).containsExactly("BTC");
    }

    @Test
    void candlesBecomeBarsAsksTheReadModelAtTheViewsClockAndKnowsOnlyListedMarkets() {
        when(prices.bars("BTC", 30, NOW)).thenReturn(List.of(
                new PriceBar(60_000L, new BigDecimal("84000"), new BigDecimal("84010.5"), new BigDecimal("83990"), new BigDecimal("84005")),
                new PriceBar(120_000L, new BigDecimal("84005"), new BigDecimal("80000"), new BigDecimal("83990"), new BigDecimal("84000")))); // high below low: left out

        List<BarDto> bars = ports(null, NOW).bars("BTC", 30);

        assertThat(bars).containsExactly(new BarDto(60_000L, 84000, 84010.5, 83990, 84005));
        assertThat(ports(null, NOW).bars("NOPE", 30)).isEmpty();
        assertThat(ports(null, NOW).bars("BTC", 0)).isEmpty();
        verify(prices, never()).bars(eq("NOPE"), anyInt(), anyLong());
    }

    @Test
    void thePreviousMajorityIsThatOfTheMarketAndSeriesAndIsReadOncePerMemo() {
        when(roundResults.lastMajorityDirection("BTC", Interval.HOURLY)).thenReturn(Optional.of(Enums.Direction.DOWN));

        ServerBotPorts first = ports(null, NOW);
        assertThat(first.previousMajority("BTC")).contains(Direction.DOWN);
        assertThat(ports(null, NOW).previousMajority("BTC")).contains(Direction.DOWN);
        assertThat(first.previousMajority("NOPE")).isEmpty();

        verify(roundResults, times(1)).lastMajorityDirection("BTC", Interval.HOURLY);
    }

    @Test
    void theAccountIsTheBalanceTheOpenRoundsAndTheLatestRunOfResultsFlatRoundsLeftOut() {
        when(players.byId("b1")).thenReturn(Optional.of(bot("b1")));
        when(gameService.balanceOf("b1")).thenReturn(Optional.of(PlayAmount.ofWholePlay(7_500)));
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of("BTC-1h-1"));
        // newest first: loss, loss, win
        when(roundResults.recentOutcomes("b1", ServerBotPorts.HISTORY_ROUNDS)).thenReturn(List.of(
                new long[] {100, 0}, new long[] {100, 40}, new long[] {100, 250}));

        BotAccount account = ports(null, NOW).account("b1").orElseThrow();

        assertThat(account.balance()).isEqualTo(PlayAmount.ofWholePlay(7_500));
        assertThat(account.lastOutcome()).isEqualTo(LastOutcome.LOSS);
        assertThat(account.streak()).isEqualTo(2);
        assertThat(account.betRoundIds()).containsExactly("BTC-1h-1");
    }

    @Test
    void aBotWithNoSettledRoundsHasNoOutcomeAndAnUnknownOrHumanPlayerHasNoAccount() {
        when(players.byId("b1")).thenReturn(Optional.of(bot("b1")));
        when(gameService.balanceOf("b1")).thenReturn(Optional.of(PlayAmount.ofWholePlay(10_000)));
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of());
        when(roundResults.recentOutcomes(anyString(), anyInt())).thenReturn(List.of());
        when(players.byId("human")).thenReturn(Optional.of(new PlayerDirectory.Row("human", "txch1", "Ann", false, null, 2, 0L)));
        when(players.byId("ghost")).thenReturn(Optional.empty());

        BotAccount account = ports(null, NOW).account("b1").orElseThrow();
        assertThat(account.lastOutcome()).isEqualTo(LastOutcome.NONE);
        assertThat(account.streak()).isZero();
        assertThat(ports(null, NOW).account("human")).isEmpty();
        assertThat(ports(null, NOW).account("ghost")).isEmpty();
    }

    private BetRequest request(String roundId) {
        return new BetRequest("b1", "BTC", roundId, Direction.UP, PlayAmount.ofWholePlay(100), 5);
    }

    @Test
    void aBetGoesThroughPlaceBetWithTheViewsClockAndComesBackWithItsFees() {
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of());
        when(gameService.placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong())).thenReturn(
                new Rounds.BetDto("b1", "BTC-1h-x", Enums.Direction.UP, 5, "100.000", "0.050", "0.100", 40));

        BetResult result = ports(null, NOW).place(request("BTC-1h-x"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.feesCharged()).isEqualTo(PlayAmount.fromMoney(new BigDecimal("0.150")));
        org.mockito.ArgumentCaptor<Betting.PlaceBetRequest> sent = org.mockito.ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService).placeBet(eq("b1"), sent.capture(), eq(NOW));
        assertThat(sent.getValue()).isEqualTo(new Betting.PlaceBetRequest("BTC-1h-x", Enums.Direction.UP, 5, "100.000"));
    }

    @Test
    void aWinOrLoseBetOfABotReachesTheGameInTheFixedModeAndAPriceMarketIsNotAnOddsOne() {
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of());
        when(gameService.placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong())).thenReturn(
                new Rounds.BetDto("b1", "BTC-1h-x", Enums.Direction.UP, 2, "100.000", "3.000", "0.100", 40));

        ports(null, NOW).place(new BetRequest("b1", "BTC", "BTC-1h-x", Direction.UP, PlayAmount.ofWholePlay(100), 2,
                crowdvsmarket.engine.round.BetMode.FIXED));

        org.mockito.ArgumentCaptor<Betting.PlaceBetRequest> sent = org.mockito.ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService).placeBet(eq("b1"), sent.capture(), eq(NOW));
        assertThat(sent.getValue()).isEqualTo(new Betting.PlaceBetRequest("BTC-1h-x", Enums.Direction.UP, 2, "100.000", Enums.BetMode.FIXED));
        assertThat(ports(null, NOW).markets().get(0).odds()).isFalse();
    }

    @Test
    void aRefusalOfTheGameIsAResultNotAnException() {
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of());
        when(gameService.placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong()))
                .thenThrow(ApiException.insufficientBalance("stake 100 plus fees exceeds balance 5"));

        BetResult refused = ports(null, NOW).place(request("BTC-1h-x"));

        assertThat(refused.accepted()).isFalse();
        assertThat(refused.reason()).contains("exceeds balance");
        assertThat(refused.feesCharged()).isEqualTo(PlayAmount.ZERO);

        when(gameService.placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong())).thenThrow(new IllegalStateException("boom"));
        assertThat(ports(null, NOW).place(request("BTC-1h-x")).accepted()).isFalse();
    }

    @Test
    void aSecondBetInARoundIsRefusedBeforeTheGameCanReplaceTheFirstOne() {
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of("BTC-1h-x"));

        assertThat(ports(null, NOW).place(request("BTC-1h-x")).accepted()).isFalse();

        verify(gameService, never()).placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong());
    }

    @Test
    void aMarketWithoutAFreshPriceOrAnUnknownOneIsRefusedAtTheMomentOfTheBet() {
        when(gameService.openBetRoundIds("b1")).thenReturn(Set.of());
        when(prices.latest(any(Market.class))).thenReturn(Optional.empty());
        assertThat(ports(null, NOW).place(request("BTC-1h-x")).accepted()).isFalse();
        assertThat(ports(null, NOW).place(new BetRequest("b1", "NOPE", "NOPE-1h-x", Direction.UP, PlayAmount.ofWholePlay(100), 5))
                .accepted()).isFalse();

        verify(gameService, never()).placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong());
    }
}
