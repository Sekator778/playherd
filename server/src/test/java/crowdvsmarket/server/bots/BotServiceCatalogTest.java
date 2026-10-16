package crowdvsmarket.server.bots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
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
import crowdvsmarket.server.service.GameService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@code BotService} with the catalog engine, every collaborator a fake: the 20 bots of the old kinds are moved into catalog slots
 * (same players, same names) and the 21st is created, a replacement takes the slot of the bot it replaces, the roll-back to the
 * legacy engine still makes sense of catalog bots, and a catalog bot's pass of the brain places valid bets through
 * {@code GameService#placeBet}.
 */
class BotServiceCatalogTest {

    private static final Catalog CATALOG = Catalog.load();
    private static final long STEP = 60_000L;
    private static final long RUNNING_START = 10 * STEP;
    private static final long HOUR = 3_600_000L;

    private GameConfig config;
    private PlayerDirectory players;
    private GameService gameService;
    private RoundProgress progress;
    private RoundResultRepository roundResults;
    private MarketRegistry registry;
    private MarketPrices prices;
    private BotService bots;
    private List<PlayerDirectory.Row> rows;

    @BeforeEach
    void wire() {
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        when(config.botsCatalogEngine()).thenReturn(true);
        when(config.botsCount()).thenReturn(21);
        when(config.botsRespawnDelayMs()).thenReturn(HOUR);
        players = mock(PlayerDirectory.class);
        gameService = mock(GameService.class);
        progress = new RoundProgress();
        roundResults = mock(RoundResultRepository.class);
        when(roundResults.lastMajorityDirection(anyString(), any())).thenReturn(Optional.empty());
        registry = mock(MarketRegistry.class);
        prices = mock(MarketPrices.class);
        rows = new ArrayList<>();
        when(players.bots()).thenAnswer(inv -> List.copyOf(rows));
        when(players.byId(anyString())).thenAnswer(inv -> rows.stream().filter(r -> r.playerId().equals(inv.getArgument(0))).findFirst());
        when(gameService.nicknamesInUse()).thenAnswer(inv -> rows.stream().map(PlayerDirectory.Row::nickname).collect(Collectors.toSet()));
        // what the real service and the directory do: a created bot is a row, an assigned strategy changes the row
        doAnswer(inv -> {
            String id = inv.getArgument(0);
            String strategy = inv.getArgument(1);
            rows.replaceAll(r -> r.playerId().equals(id) ? new PlayerDirectory.Row(r.playerId(), r.walletAddress(), r.nickname(),
                    true, strategy, r.playerIndex(), r.createdAtMs(), r.retiredAtMs(), r.replaced()) : r);
            return null;
        }).when(gameService).assignBotStrategy(anyString(), anyString());
        when(gameService.createBotPlayer(anyString(), anyString(), anyLong())).thenAnswer(inv -> create(inv.getArgument(0), inv.getArgument(1), null));
        when(gameService.createBotPlayer(anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> create(inv.getArgument(0), inv.getArgument(1), inv.getArgument(3)));

        bots = new BotService();
        bots.config = config;
        bots.players = players;
        bots.gameService = gameService;
        bots.roundProgress = progress;
        bots.roundResults = roundResults;
        bots.marketRegistry = registry;
        bots.marketPrices = prices;
    }

    private Object create(String nickname, String strategy, String replaces) {
        rows.add(new PlayerDirectory.Row("bot-" + rows.size(), "txbot" + rows.size(), nickname, true, strategy, rows.size(), 0L));
        if (replaces != null) {
            rows.replaceAll(r -> r.playerId().equals(replaces) ? new PlayerDirectory.Row(r.playerId(), r.walletAddress(), r.nickname(),
                    true, r.botStrategy(), r.playerIndex(), r.createdAtMs(), r.retiredAtMs(), true) : r);
        }
        return null;
    }

    private void legacyBots() {
        int i = 0;
        for (BotStrategy strategy : BotStrategy.values()) {
            for (int k = 0; k < 4; k++, i++) {
                rows.add(new PlayerDirectory.Row("bot-" + i, "txbot" + i, "Bot " + (i + 1) + " (" + strategy.label() + ")", true,
                        strategy.name(), i, 0L));
            }
        }
    }

    private Set<String> liveSlots() {
        return rows.stream().filter(r -> !r.isRetired()).map(PlayerDirectory.Row::botStrategy).collect(Collectors.toSet());
    }

    @Test
    void theTwentyOldBotsMoveIntoCatalogSlotsOfTheirFamilyKeepingTheirNamesAndThe21stIsCreated() {
        legacyBots();
        List<String> names = rows.stream().map(PlayerDirectory.Row::nickname).toList();

        bots.ensureBotsExist(1_000L);

        assertThat(rows).hasSize(21);
        assertThat(liveSlots()).as("21 distinct catalog slots").hasSize(21).containsExactlyInAnyOrderElementsOf(
                CATALOG.bots().stream().map(CatalogEntry::catalogId).toList());
        assertThat(rows.subList(0, 20)).extracting(PlayerDirectory.Row::nickname).containsExactlyElementsOf(names);
        assertThat(rows.subList(0, 20)).extracting(PlayerDirectory.Row::playerId).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> "bot-" + i).toList());
        // the old kinds took the slots of their family while there were such: all 2 contrarian and 2 follower slots, all 3 maximalists
        List<CatalogEntry> taken = rows.subList(0, 20).stream().map(r -> CATALOG.byId(r.botStrategy()).orElseThrow()).toList();
        assertThat(taken.stream().filter(e -> e.strategy().equals("crowd-contrarian")).filter(e -> rows.stream().anyMatch(
                r -> r.botStrategy().equals(e.catalogId()) && r.nickname().contains("(Contrarian)")))).hasSize(2);
        assertThat(taken.stream().filter(e -> e.strategy().equals("crowd-follow")).filter(e -> rows.stream().anyMatch(
                r -> r.botStrategy().equals(e.catalogId()) && r.nickname().contains("(Trend Follower)")))).hasSize(2);
        assertThat(taken.stream().filter(e -> e.persona().startsWith("maximalist")).filter(e -> rows.stream().anyMatch(
                r -> r.botStrategy().equals(e.catalogId()) && r.nickname().contains("(Maximalist)")))).hasSize(3);
        // the new bot's name is a generated one, labelled with its catalog name
        assertThat(rows.get(20).nickname()).startsWith("Bot ").endsWith("(" + CATALOG.byId(rows.get(20).botStrategy()).orElseThrow().name() + ")");

        bots.ensureBotsExist(2_000L);
        assertThat(rows).as("idempotent").hasSize(21);
    }

    @Test
    void aCountBelowTheCatalogFillsThatManySlotsAndAnAbsurdCountIsCappedAtTheCatalog() {
        when(config.botsCount()).thenReturn(4);
        bots.ensureBotsExist(1_000L);
        assertThat(rows).hasSize(4);
        when(config.botsCount()).thenReturn(500);
        bots.ensureBotsExist(2_000L);
        assertThat(rows).hasSize(21);
        assertThat(liveSlots()).hasSize(21);
    }

    @Test
    void zeroBotsMeansNoMigrationEither() {
        legacyBots();
        when(config.botsCount()).thenReturn(0);
        bots.ensureBotsExist(1_000L);
        verify(gameService, never()).assignBotStrategy(anyString(), anyString());
        assertThat(rows).hasSize(20);
    }

    @Test
    void aReplacementTakesTheCatalogSlotOfTheBotItReplacesOnlyAfterTheDelay() {
        bots.ensureBotsExist(1_000L); // 21 fresh catalog bots
        PlayerDirectory.Row gone = rows.get(7);
        rows.set(7, new PlayerDirectory.Row(gone.playerId(), gone.walletAddress(), gone.nickname(), true, gone.botStrategy(),
                gone.playerIndex(), gone.createdAtMs(), 5_000L, false));

        bots.ensureBotsExist(5_000L + HOUR - 1);
        assertThat(rows).as("not an hour yet").hasSize(21);

        bots.ensureBotsExist(5_000L + HOUR);
        assertThat(rows).hasSize(22);
        assertThat(rows.get(21).botStrategy()).isEqualTo(gone.botStrategy());
        assertThat(rows.get(7).replaced()).isTrue();

        bots.ensureBotsExist(5_000L + 2 * HOUR);
        assertThat(rows).as("one replacement per retirement").hasSize(22);
    }

    @Test
    void whileARetiredBotWaitsItsSlotIsKeptForItsReplacement() {
        when(config.botsCount()).thenReturn(20);
        bots.ensureBotsExist(1_000L); // 20 of 21 slots
        String free = CATALOG.bots().stream().map(CatalogEntry::catalogId).filter(id -> !liveSlots().contains(id)).findFirst().orElseThrow();
        PlayerDirectory.Row gone = rows.get(3);
        rows.set(3, new PlayerDirectory.Row(gone.playerId(), gone.walletAddress(), gone.nickname(), true, gone.botStrategy(),
                gone.playerIndex(), gone.createdAtMs(), 5_000L, false));
        when(config.botsCount()).thenReturn(21); // the operator raises the count: the new bot takes the free slot, not the waiting one

        bots.ensureBotsExist(6_000L);

        assertThat(rows).hasSize(21);
        assertThat(rows.get(20).botStrategy()).isEqualTo(free).isNotEqualTo(gone.botStrategy());
    }

    @Test
    void rolledBackToLegacyACatalogBotIsReplacedByTheNearestOldKind() {
        when(config.botsCatalogEngine()).thenReturn(false);
        when(config.botsCount()).thenReturn(1);
        rows.add(new PlayerDirectory.Row("bot-0", "txbot0", "Bot 1", true, "coin-1-maximalist", 0, 0L, 1_000L, false));

        bots.ensureBotsExist(1_000L + HOUR);

        verify(gameService).createBotPlayer(anyString(), eq(BotStrategy.ALWAYS_MAX_LEVERAGE.name()), anyLong(), eq("bot-0"));
        assertThat(BotService.legacyStrategyOf("crowd-contrarian-1-cautious")).isEqualTo(BotStrategy.CONTRARIAN);
        assertThat(BotService.legacyStrategyOf("crowd-follow-2-disciplined")).isEqualTo(BotStrategy.FOLLOW_MAJORITY);
        assertThat(BotService.legacyStrategyOf("coin-2-cautious")).isEqualTo(BotStrategy.CAUTIOUS_LOW_LEVERAGE);
        assertThat(BotService.legacyStrategyOf("momentum-3-disciplined")).isEqualTo(BotStrategy.RANDOM);
        assertThat(BotService.legacyStrategyOf("CONTRARIAN")).isEqualTo(BotStrategy.CONTRARIAN);
        assertThat(BotService.legacyStrategyOf(null)).isEqualTo(BotStrategy.RANDOM);
    }

    // --- a pass of the brain ---

    private Market market(String id, int maxLeverage) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, maxLeverage, 0, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    @Test
    void catalogBotsPlaceValidBetsInARoundThroughPlaceBetAtMostOneInARoundOfAMarket() {
        List<Market> markets = List.of(market("BTC", 40), market("ETH", 25), market("SOL", 20));
        for (Market m : markets) {
            for (Interval interval : Interval.values()) {
                progress.seed(m.id(), interval, new RoundWindow(m.id(), interval, RUNNING_START, RUNNING_START + STEP));
            }
            when(registry.byId(m.id())).thenReturn(Optional.of(m));
            // a rising market: two hours of candles, so that the rules on candles have something to read
            List<PriceBar> candles = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                BigDecimal close = new BigDecimal("100").add(new BigDecimal(i).multiply(new BigDecimal("0.37")))
                        .add(new BigDecimal(i % 7 == 0 ? "-1.5" : "0.4"));
                candles.add(new PriceBar(RUNNING_START - (100 - i) * 60_000L, close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE), close));
            }
            when(prices.bars(eq(m.id()), org.mockito.ArgumentMatchers.anyInt(), anyLong())).thenReturn(candles);
        }
        when(gameService.runningMarkets()).thenReturn(markets);
        when(prices.latest(any(Market.class))).thenReturn(Optional.of(new crowdvsmarket.server.prices.PriceQuote(new BigDecimal("100"),
                RUNNING_START, null, false)));
        when(config.botsMarketsPerRound()).thenReturn(3);
        bots.ensureBotsExist(1_000L); // 21 catalog bots
        for (PlayerDirectory.Row r : rows) {
            when(gameService.balanceOf(r.playerId())).thenReturn(Optional.of(PlayAmount.ofWholePlay(10_000)));
            when(gameService.openBetRoundIds(r.playerId())).thenReturn(Set.of());
        }
        when(roundResults.recentOutcomes(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(gameService.placeBet(anyString(), any(Betting.PlaceBetRequest.class), anyLong())).thenAnswer(inv -> {
            Betting.PlaceBetRequest r = inv.getArgument(1);
            return new Rounds.BetDto(inv.getArgument(0), r.roundId(), r.direction(), r.leverage(), r.stake(), "0.010", "0.100", 40);
        });

        bots.tick(RUNNING_START);                       // betting is open: plan
        bots.tick(RUNNING_START + STEP - 1);            // everything is due before it closes

        ArgumentCaptor<String> player = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Betting.PlaceBetRequest> request = ArgumentCaptor.forClass(Betting.PlaceBetRequest.class);
        verify(gameService, org.mockito.Mockito.atLeastOnce()).placeBet(player.capture(), request.capture(), anyLong());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.getAllValues().size(); i++) {
            Betting.PlaceBetRequest r = request.getAllValues().get(i);
            RoundWindow.RoundRef ref = RoundWindow.parseRoundId(r.roundId());
            Market m = markets.stream().filter(x -> x.id().equals(ref.market())).findFirst().orElseThrow();
            PlayAmount stake = PlayAmount.fromMoney(new BigDecimal(r.stake()));
            assertThat(seen.add(player.getAllValues().get(i) + "/" + r.roundId())).as("one bet per bot per round of a market").isTrue();
            assertThat(r.leverage()).as("%s on %s", player.getAllValues().get(i), r.roundId())
                    .isBetween(2, EconomyRules.maxLeverage(m.spec(), ref.interval(), stake));
            assertThat(r.roundId()).isEqualTo(new RoundWindow(ref.market(), ref.interval(), RUNNING_START + STEP, RUNNING_START + 2 * STEP).roundId());
            assertThat(stake.compareTo(crowdvsmarket.engine.ledger.LedgerBook.MIN_STAKE)).isGreaterThanOrEqualTo(0);
        }
        assertThat(player.getAllValues().stream().distinct().count()).as("many of the 21 bots bet").isGreaterThan(10);
        assertThat(seen.size()).isGreaterThan(21);
        // both series (3 and 15 minutes) are played
        assertThat(request.getAllValues().stream().map(r -> RoundWindow.parseRoundId(r.roundId()).interval()).distinct()).hasSize(2);
        assertThat(request.getAllValues()).allMatch(r -> r.direction() == Enums.Direction.UP || r.direction() == Enums.Direction.DOWN);
    }
}
