package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.OracleStatusCache;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.testsupport.PlainMarketRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code serverInfo()} with plain objects: where prices come from ({@code priceSource}), how many markets are
 * active, the PLAY asset id and the test-wallet flag from the configuration, the oracle's first {@code seq} from
 * the database, and the older fields as before.
 */
class ServerInfoServiceTest {

    private static final String ASSET = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";

    private GameConfig config;
    private PlainMarketRegistry registry;
    private Db db;
    private OracleStatusCache statusCache;
    private OracleRecordRepository oracleRecords;
    private PricesConfig prices;
    private EngineRebuildService rebuildService;
    private ServerInfoService service;

    @BeforeEach
    void anApplicationOfPlainObjects() {
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        when(config.roundDurations()).thenReturn(new RoundDurations(180_000L, 900_000L));
        when(config.botsCount()).thenReturn(20);
        when(config.oracleNetwork()).thenReturn("testnet11");
        when(config.oracleLauncherId()).thenReturn("launcher");
        when(config.playAssetId()).thenReturn(GameConfig.TESTNET11_PLAY_ASSET_ID);
        when(config.testWalletEnabled()).thenReturn(true);
        registry = new PlainMarketRegistry();
        db = mock(Db.class);
        statusCache = mock(OracleStatusCache.class);
        oracleRecords = mock(OracleRecordRepository.class);
        rebuildService = mock(EngineRebuildService.class);
        when(rebuildService.isReady()).thenReturn(true);
        prices = PricesConfig.defaults(); // mode live, source live

        GameService gameService = new GameService();
        gameService.config = config;
        service = new ServerInfoService();
        service.config = config;
        service.prices = prices;
        service.marketRegistry = registry;
        service.gameService = gameService;
        service.oracleStatusCache = statusCache;
        service.oracleRecords = oracleRecords;
        service.db = db;
        service.rebuildService = rebuildService;
    }

    private void list(String id, String category) {
        registry.list(new NewMarket(id, MarketKind.PERP, category, id, id, null, null, "synthetic", id, null, 10, 0,
                null), 1L);
    }

    @Test
    void theDefaultIsTheGameAsBefore() {
        when(oracleRecords.pendingCount()).thenReturn(3);

        ServerInfo.ServerInfoDto info = service.serverInfo();

        assertThat(info.mode()).isEqualTo("live");
        assertThat(info.replay()).isNull();
        assertThat(info.roundDurationsSec()).isEqualTo(new ServerInfo.RoundDurationsDto(180, 900));
        assertThat(info.bots()).isEqualTo(20);
        assertThat(info.oracle().network()).isEqualTo("testnet11");
        assertThat(info.oracle().launcherId()).isEqualTo("launcher");
        assertThat(info.oracle().pendingRecords()).as("no status from chainsvc yet: the database's count").isEqualTo(3);
        assertThat(info.oracle().lastConfirmedSeq()).isNull();
        assertThat(info.oracle().operatorBalanceMojos()).isNull();
        assertThat(info.serverTimeMs()).isPositive();
    }

    @Test
    void theOracleBlockComesFromTheStatusChainsvcGave() {
        ChainSvcClient.OracleStatus status = mock(ChainSvcClient.OracleStatus.class);
        when(status.pendingRecords()).thenReturn(4);
        when(status.lastConfirmedSeq()).thenReturn(41L);
        when(status.lastConfirmedHeight()).thenReturn(4_800_000L);
        when(status.operatorBalanceMojos()).thenReturn("123456");
        when(statusCache.get()).thenReturn(status);

        ServerInfo.OracleInfoDto oracle = service.serverInfo().oracle();

        assertThat(oracle.pendingRecords()).isEqualTo(4);
        assertThat(oracle.lastConfirmedSeq()).isEqualTo(41L);
        assertThat(oracle.lastConfirmedHeight()).isEqualTo(4_800_000L);
        assertThat(oracle.operatorBalanceMojos()).isEqualTo("123456");
    }

    @Test
    void priceSourceIsReplayInReplayModeOtherwiseTheConfiguredSource() {
        assertThat(service.serverInfo().priceSource()).isEqualTo("live");

        service.prices = prices.withMode(true, PricesConfig.SourceKind.SYNTHETIC);
        assertThat(service.serverInfo().priceSource()).isEqualTo("synthetic");

        when(config.mode()).thenReturn(GameMode.REPLAY);
        assertThat(service.serverInfo().priceSource()).as("replay mode, whatever herd.prices.source says")
                .isEqualTo("replay");
        service.prices = prices.withMode(false, PricesConfig.SourceKind.LIVE);
        assertThat(service.serverInfo().priceSource()).isEqualTo("replay");
    }

    @Test
    void marketsIsTheNumberOfActiveMarketsPlayedHere() {
        list("ETH", Category.CRYPTO);
        list("PM1", Category.POLITICS);
        list("PM2", Category.SPORTS);
        registry.close("PM2", "ended", 5L);

        assertThat(service.serverInfo().markets()).as("BTC, ETH and PM1 — PM2 is closed").isEqualTo(3);

        when(config.mode()).thenReturn(GameMode.REPLAY);
        assertThat(service.serverInfo().markets()).as("a replay server plays BTC alone").isEqualTo(1);
    }

    @Test
    void theFirstSeqIsWhatTheDatabaseSaysAndNullWhenThereIsNone() {
        when(db.read(any())).thenReturn(null);
        assertThat(service.serverInfo().oracle().firstSeq()).isNull();

        when(db.read(any())).thenReturn(17L);
        assertThat(service.serverInfo().oracle().firstSeq()).isEqualTo(17L);
    }

    @Test
    void thePlayAssetIdIsTheConfiguredOneNormalized() {
        assertThat(service.serverInfo().playAssetId()).isEqualTo(ASSET);

        when(config.playAssetId()).thenReturn("  0x" + ASSET.substring(2).toUpperCase() + "  ");
        assertThat(service.serverInfo().playAssetId()).as("lower case, trimmed").isEqualTo(ASSET);

        when(config.playAssetId()).thenReturn(ASSET.substring(2)); // without the 0x
        assertThat(service.serverInfo().playAssetId()).isEqualTo(ASSET);

        when(config.playAssetId()).thenReturn("0x1234");
        assertThat(service.serverInfo().playAssetId()).as("not an asset id: left out, never a wrong token").isNull();
        when(config.playAssetId()).thenReturn("not hex at all, not hex at all, not hex at all, not hex at al");
        assertThat(service.serverInfo().playAssetId()).isNull();
    }

    @Test
    void theDefaultPlayAssetIdIsTheTestnet11Issuance() {
        assertThat(GameConfig.TESTNET11_PLAY_ASSET_ID).isEqualTo(ASSET);
    }

    @Test
    void theTestWalletFlagFollowsTheConfiguration() {
        assertThat(service.serverInfo().testWallet()).isTrue();

        when(config.testWalletEnabled()).thenReturn(false);
        assertThat(service.serverInfo().testWallet()).isFalse();
    }

    @Test
    void inReplayModeTheReplayRangeIsGiven() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        when(config.replaySpeedup()).thenReturn(20.0);

        ServerInfo.ReplayInfoDto replay = service.serverInfo().replay();

        assertThat(replay).isNotNull();
        assertThat(replay.speedup()).isEqualTo(20.0);
        assertThat(replay.sourceFromIso()).isNotBlank();
        assertThat(replay.sourceToIso()).isNotBlank();
        assertThat(service.serverInfo().mode()).isEqualTo("replay");
    }

    @Test
    void marketKindIsNotNeededToCountMarkets() {
        // a market of any kind counts once, ODDS included
        registry.list(new NewMarket("PMODDS", MarketKind.ODDS, Category.POLITICS, "O", "t", null, null, "synthetic",
                "PMODDS", null, 10, Market.ODDS_LOT_EXP, null), 1L);

        assertThat(service.serverInfo().markets()).isEqualTo(2);
    }

    @Test
    void engineReadyIsWhatTheRebuildServiceSays() {
        assertThat(service.serverInfo().engineReady()).isTrue();

        when(rebuildService.isReady()).thenReturn(false);
        assertThat(service.serverInfo().engineReady()).as("a recovery that has not succeeded yet").isFalse();
    }

    @Test
    void aDatabaseThatDoesNotAnswerLeavesTheLastFirstSeqNotAFailure() {
        when(db.read(any())).thenReturn(17L);
        assertThat(service.serverInfo().oracle().firstSeq()).isEqualTo(17L);

        when(db.read(any())).thenThrow(new RuntimeException("db connection failure"));
        ServerInfo.ServerInfoDto info = service.serverInfo();

        assertThat(info.oracle().firstSeq()).as("the value it last read").isEqualTo(17L);
        assertThat(info.engineReady()).isTrue();
    }

    @Test
    void aDatabaseThatNeverAnsweredGivesNullAndZeroNotAFailure() {
        when(db.read(any())).thenThrow(new RuntimeException("db connection failure"));
        when(oracleRecords.pendingCount()).thenThrow(new RuntimeException("db connection failure"));

        ServerInfo.ServerInfoDto info = service.serverInfo();

        assertThat(info.oracle().firstSeq()).isNull();
        assertThat(info.oracle().pendingRecords()).isZero();
        assertThat(info.mode()).isEqualTo("live");
    }

    @Test
    void thePendingCountOfTheLastGoodReadAnswersWhenTheDatabaseDoesNot() {
        when(oracleRecords.pendingCount()).thenReturn(5);
        assertThat(service.serverInfo().oracle().pendingRecords()).isEqualTo(5);

        when(oracleRecords.pendingCount()).thenThrow(new RuntimeException("db connection failure"));

        assertThat(service.serverInfo().oracle().pendingRecords()).isEqualTo(5);
    }
}
