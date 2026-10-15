package crowdvsmarket.server.bots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.service.GameService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Т-8 (docs/research/markets_architecture.md §2): each bot, in each round of a series, bets on
 * {@code herd.bots.markets-per-round} distinct random active markets, once in each, within the market's
 * leverage cap, looking at that market's own previous majority — and in live mode never on a market
 * without a fresh price. Every collaborator is a fake; time is the argument of {@code tick}.
 */
class BotServiceTest {

    private static final long STEP = 60_000L;
    /** Running window [10 steps, 11 steps), so betting is open from 10 steps and closes at 11 steps. */
    private static final long RUNNING_START = 10 * STEP;
    private static final long BETTING_CLOSES = 11 * STEP;
    private static final long JUST_BEFORE_CLOSE = BETTING_CLOSES - 1;

    private GameConfig config;
    private PlayerDirectory players;
    private GameService gameService;
    private RoundProgress progress;
    private RoundResultRepository roundResults;
    private MarketRegistry registry;
    private MarketPrices prices;
    private BotService bots;
    private final Map<String, Market> markets = new HashMap<>();
    private List<PlayerDirectory.Row> botRows;

    private static Market market(String id, int maxLeverage) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, maxLeverage, 0, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    @BeforeEach
    void wire() {
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.REPLAY);
        when(config.botsMarketsPerRound()).thenReturn(3);
        players = mock(PlayerDirectory.class);
        gameService = mock(GameService.class);
        progress = new RoundProgress();
        roundResults = mock(RoundResultRepository.class);
        when(roundResults.lastMajorityDirection(anyString(), any())).thenReturn(Optional.empty());
        registry = mock(MarketRegistry.class);
        prices = mock(MarketPrices.class);
        when(prices.latest(anyString())).thenReturn(Optional.empty());
        // the bots ask for a market's quote, the primary exchange's or the backup's (Р-76); this mock answers by the market's id
        when(prices.latest(any(Market.class))).thenAnswer(inv -> prices.latest(inv.<Market>getArgument(0).id()));

        bots = new BotService();
        bots.config = config;
        bots.players = players;
        bots.gameService = gameService;
        bots.roundProgress = progress;
        bots.roundResults = roundResults;
        bots.marketRegistry = registry;
        bots.marketPrices = prices;
        setBots(BotStrategy.values());
    }

    private void setBots(BotStrategy... strategies) {
        botRows = new ArrayList<>();
        for (int i = 0; i < strategies.length; i++) {
            PlayerDirectory.Row row = new PlayerDirectory.Row("bot-" + i, "txbot" + i, "Bot " + i, true,
                    strategies[i].name(), i, 0L);
            botRows.add(row);
            when(players.byId(row.playerId())).thenReturn(Optional.of(row));
        }
        when(players.bots()).thenReturn(botRows);
    }

    private void run(Market... runnable) {
        markets.clear();
        for (Market m : runnable) {
            markets.put(m.id(), m);
            for (Interval interval : Interval.values()) {
                progress.seed(m.id(), interval, new RoundWindow(m.id(), interval, RUNNING_START, RUNNING_START + STEP));
            }
            when(registry.byId(m.id())).thenReturn(Optional.of(m));
        }
        when(gameService.runningMarkets()).thenReturn(List.of(runnable));
    }

    private record Bet(String playerId, String marketId, Interval interval, Enums.Direction direction, int leverage) {
    }

    /** Plans at the start of the betting window, then lets time reach just before it closes: every
     *  planned bet is due by then. */
    private List<Bet> playOneRound() {
        bots.tick(RUNNING_START);
        bots.tick(JUST_BEFORE_CLOSE);
        ArgumentCaptor<String> player = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, org.mockito.Mockito.atLeast(0)).placeBet(player.capture(), request.capture(), anyLong());
        List<Bet> out = new ArrayList<>();
        for (int i = 0; i < request.getAllValues().size(); i++) {
            Betting.PlaceBetRequest r = request.getAllValues().get(i);
            RoundWindow.RoundRef ref = RoundWindow.parseRoundId(r.roundId());
            out.add(new Bet(player.getAllValues().get(i), ref.market(), ref.interval(), r.direction(), r.leverage()));
        }
        return out;
    }

    @Test
    void eachBotBetsOnThreeDistinctMarketsPerSeriesOnce() {
        run(market("BTC", 40), market("ETH", 25), market("SOL", 20), market("DOGE", 10), market("HYPE", 5));

        List<Bet> placed = playOneRound();

        for (PlayerDirectory.Row bot : botRows) {
            for (Interval interval : Interval.values()) {
                List<String> marketsOfBot = placed.stream()
                        .filter(b -> b.playerId().equals(bot.playerId()) && b.interval() == interval)
                        .map(Bet::marketId).toList();
                assertThat(marketsOfBot).as("%s %s", bot.playerId(), interval).hasSize(3)
                        .doesNotHaveDuplicates().isSubsetOf(markets.keySet());
            }
        }
        assertThat(placed).hasSize(botRows.size() * 2 * 3);
    }

    @Test
    void theChoiceIsRandomAcrossBotsAndRounds() {
        run(market("BTC", 40), market("ETH", 25), market("SOL", 20), market("DOGE", 10), market("HYPE", 5));
        setBots(BotStrategy.RANDOM, BotStrategy.RANDOM, BotStrategy.RANDOM, BotStrategy.RANDOM, BotStrategy.RANDOM,
                BotStrategy.RANDOM, BotStrategy.RANDOM, BotStrategy.RANDOM);

        List<Bet> placed = playOneRound();

        Set<Set<String>> distinctPicks = new HashSet<>();
        for (PlayerDirectory.Row bot : botRows) {
            distinctPicks.add(placed.stream()
                    .filter(b -> b.playerId().equals(bot.playerId()) && b.interval() == Interval.HOURLY)
                    .map(Bet::marketId).collect(Collectors.toSet()));
        }
        // 8 bots drawing 3 of 5 markets: all picking the same triple has probability (1/10)^7
        assertThat(distinctPicks.size()).isGreaterThan(1);
    }

    @Test
    void fewerMarketsThanTheConfiguredCountMeansOneBetInEach() {
        run(market("BTC", 40), market("ETH", 25));

        List<Bet> placed = playOneRound();

        for (PlayerDirectory.Row bot : botRows) {
            assertThat(placed.stream().filter(b -> b.playerId().equals(bot.playerId())
                    && b.interval() == Interval.DAILY).map(Bet::marketId)).containsExactlyInAnyOrder("BTC", "ETH");
        }
    }

    @Test
    void marketsPerRoundIsConfigurable() {
        when(config.botsMarketsPerRound()).thenReturn(1);
        run(market("BTC", 40), market("ETH", 25), market("SOL", 20));

        List<Bet> placed = playOneRound();

        assertThat(placed).hasSize(botRows.size() * 2);
    }

    @Test
    void leverageNeverExceedsTheMarketsCap() {
        run(market("BTC", 40), market("ETH", 25), market("DOGE", 10), market("HYPE", 5));
        when(config.botsMarketsPerRound()).thenReturn(4);

        for (int round = 0; round < 3; round++) {
            // a fresh betting window each round, so it is planned again
            long shift = round * STEP;
            for (Market m : markets.values()) {
                for (Interval interval : Interval.values()) {
                    progress.seed(m.id(), interval, new RoundWindow(m.id(), interval, RUNNING_START + shift,
                            RUNNING_START + shift + STEP));
                }
            }
            bots.tick(RUNNING_START + shift);
            bots.tick(JUST_BEFORE_CLOSE + shift);
        }

        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, atLeastOnce()).placeBet(anyString(), request.capture(), anyLong());
        assertThat(request.getAllValues()).hasSize(botRows.size() * 2 * 4 * 3);
        boolean sawAStakeAboveTheFirstTier = false;
        for (Betting.PlaceBetRequest r : request.getAllValues()) {
            RoundWindow.RoundRef ref = RoundWindow.parseRoundId(r.roundId());
            PlayAmount stake = PlayAmount.fromMoney(new BigDecimal(r.stake()));
            // economy stage A (Р-92): the market's cap for the round length, lowered by the tier of the stake
            int allowed = EconomyRules.maxLeverage(markets.get(ref.market()).spec(), ref.interval(), stake);
            assertThat(r.leverage()).as("%s stake %s", r.roundId(), r.stake()).isBetween(2, allowed);
            sawAStakeAboveTheFirstTier |= stake.compareTo(PlayAmount.ofWholePlay(250)) > 0;
        }
        assertThat(sawAStakeAboveTheFirstTier).as("the tiers were exercised").isTrue();
    }

    @Test
    void aBotsBetPassesTheSameLeverageValidationAsAPlayers() {
        // cap for the round length, lowered by the stake tier: what GameService.placeBet enforces
        run(market("BTC", 40), market("SP500", 40), market("ETH", 25), market("HYPE", 5));
        when(config.botsMarketsPerRound()).thenReturn(4);

        playOneRound();

        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, atLeastOnce()).placeBet(anyString(), request.capture(), anyLong());
        for (Betting.PlaceBetRequest r : request.getAllValues()) {
            RoundWindow.RoundRef ref = RoundWindow.parseRoundId(r.roundId());
            MarketSpec spec = markets.get(ref.market()).spec();
            assertThatCode(() -> EconomyRules.requireLeverageAllowed(spec, ref.interval(), r.leverage(),
                    PlayAmount.fromMoney(new BigDecimal(r.stake())))).as(r.roundId()).doesNotThrowAnyException();
        }
    }

    @Test
    void thePreviousMajorityIsPerMarket() {
        run(market("ETH", 25), market("SOL", 20));
        when(config.botsMarketsPerRound()).thenReturn(2);
        when(roundResults.lastMajorityDirection("ETH", Interval.HOURLY)).thenReturn(Optional.of(Enums.Direction.UP));
        when(roundResults.lastMajorityDirection("SOL", Interval.HOURLY)).thenReturn(Optional.of(Enums.Direction.DOWN));
        when(roundResults.lastMajorityDirection("ETH", Interval.DAILY)).thenReturn(Optional.of(Enums.Direction.UP));
        when(roundResults.lastMajorityDirection("SOL", Interval.DAILY)).thenReturn(Optional.of(Enums.Direction.DOWN));
        setBots(BotStrategy.FOLLOW_MAJORITY, BotStrategy.FOLLOW_MAJORITY, BotStrategy.CONTRARIAN,
                BotStrategy.CONTRARIAN);

        List<Bet> placed = playOneRound();

        for (Bet bet : placed) {
            boolean follower = bet.playerId().equals("bot-0") || bet.playerId().equals("bot-1");
            Enums.Direction previous = bet.marketId().equals("ETH") ? Enums.Direction.UP : Enums.Direction.DOWN;
            Enums.Direction expected = follower ? previous
                    : previous == Enums.Direction.UP ? Enums.Direction.DOWN : Enums.Direction.UP;
            assertThat(bet.direction()).as("%s on %s", bet.playerId(), bet.marketId()).isEqualTo(expected);
        }
        assertThat(placed).hasSize(4 * 2 * 2);
    }

    @Test
    void aWindowIsPlannedOnlyOnce() {
        run(market("BTC", 40), market("ETH", 25));

        playOneRound();
        long firstRound = placeBetCalls();
        bots.tick(JUST_BEFORE_CLOSE); // same window again, nothing new may be planned or fired
        bots.tick(JUST_BEFORE_CLOSE);

        assertThat(firstRound).isEqualTo(botRows.size() * 2 * 2);
        assertThat(placeBetCalls()).isEqualTo(firstRound);
    }

    private long placeBetCalls() {
        return org.mockito.Mockito.mockingDetails(gameService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("placeBet")).count();
    }

    @Test
    void aBetAfterTheWindowClosedIsDropped() {
        run(market("BTC", 40));
        bots.tick(RUNNING_START); // planned, nothing due yet at the very start unless the offset is 0

        bots.tick(BETTING_CLOSES + 5_000); // too late for everything still unfired

        // whatever fired fired before the close; nothing can be placed after it
        ArgumentCaptor<Long> at = ArgumentCaptor.forClass(Long.class);
        verify(gameService, org.mockito.Mockito.atLeast(0)).placeBet(anyString(), any(), at.capture());
        assertThat(at.getAllValues()).allSatisfy(t -> assertThat(t).isLessThan(BETTING_CLOSES));
    }

    // ---- mode=live: only markets with a fresh price ----

    private static PriceQuote quote(boolean stale) {
        return new PriceQuote(new BigDecimal("1.00"), 1L, null, stale);
    }

    @Test
    void liveModeBotsSkipMarketsWithoutAFreshPrice() {
        when(config.mode()).thenReturn(GameMode.LIVE);
        run(market("BTC", 40), market("ETH", 25), market("STALE", 25), market("NOQUOTE", 25));
        when(config.botsMarketsPerRound()).thenReturn(4);
        when(prices.latest("BTC")).thenReturn(Optional.of(quote(false)));
        when(prices.latest("ETH")).thenReturn(Optional.of(quote(false)));
        when(prices.latest("STALE")).thenReturn(Optional.of(quote(true)));
        when(prices.latest("NOQUOTE")).thenReturn(Optional.empty());

        List<Bet> placed = playOneRound();

        assertThat(placed).isNotEmpty();
        assertThat(placed).extracting(Bet::marketId).containsOnly("BTC", "ETH").doesNotContain("STALE", "NOQUOTE");
        assertThat(placed).hasSize(botRows.size() * 2 * 2);
    }

    @Test
    void liveModeChecksAgainAtTheMomentOfTheBet() {
        when(config.mode()).thenReturn(GameMode.LIVE);
        run(market("BTC", 40), market("ETH", 25));
        when(prices.latest(anyString())).thenReturn(Optional.of(quote(false)));
        bots.tick(RUNNING_START); // planned with both markets fresh

        when(prices.latest("ETH")).thenReturn(Optional.of(quote(true))); // ETH goes stale before the bots fire
        bots.tick(JUST_BEFORE_CLOSE);

        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, atLeastOnce()).placeBet(anyString(), request.capture(), anyLong());
        assertThat(request.getAllValues()).extracting(r -> RoundWindow.parseRoundId(r.roundId()).market())
                .containsOnly("BTC");
    }

    @Test
    void replayModeNeedsNoQuotesAtAll() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        run(market("BTC", 40));
        when(prices.latest(anyString())).thenReturn(Optional.empty());

        List<Bet> placed = playOneRound();

        assertThat(placed).hasSize(botRows.size() * 2);
        verify(prices, never()).latest(anyString());
    }

    @Test
    void aClosedMarketIsNotBetOnEvenIfAShotWasPlanned() {
        run(market("BTC", 40), market("ETH", 25));
        bots.tick(RUNNING_START);
        Market closed = new Market("ETH", MarketKind.PERP, "crypto", "ETH", "ETH", null, null, "synthetic", "ETH", null,
                25, 0, 1, MarketStatus.CLOSED, 0L, 1L, "gone", null);
        when(registry.byId("ETH")).thenReturn(Optional.of(closed));

        bots.tick(JUST_BEFORE_CLOSE);

        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, atLeastOnce()).placeBet(anyString(), request.capture(), anyLong());
        assertThat(request.getAllValues()).extracting(r -> RoundWindow.parseRoundId(r.roundId()).market())
                .containsOnly("BTC");
    }

    @Test
    void aBotThatNoLongerExistsAfterARebuildIsSkippedQuietly() {
        run(market("BTC", 40));
        bots.tick(RUNNING_START);
        for (PlayerDirectory.Row row : botRows) {
            when(players.byId(row.playerId())).thenReturn(Optional.empty()); // the directory was reset
        }

        bots.tick(JUST_BEFORE_CLOSE);

        verify(gameService, never()).placeBet(anyString(), any(), anyLong());
    }
}
