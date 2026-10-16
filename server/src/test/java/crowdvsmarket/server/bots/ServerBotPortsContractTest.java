package crowdvsmarket.server.bots;

import crowdvsmarket.bots.port.BettingPort;
import crowdvsmarket.bots.port.BotStatePort;
import crowdvsmarket.bots.port.MarketDataPort;
import crowdvsmarket.bots.port.PortsContractTest;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.DbMarketRegistry;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.ScriptedMarketPrices;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;

/**
 * The server's adapter of the bots module's three ports against the module's own contract ({@code PortsContractTest}, from the
 * {@code bots} test-jar) - the same tests the in-memory fake passes - over the real application: the real engine and ledger,
 * the real {@code GameService#placeBet}, prices scripted. Fixture: one open market (fresh price, a running round with its betting
 * round ahead), one market whose price is stale (a snapshot that is not open), and a bot with the starter pack and no bet.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ServerBotPortsContractTest extends PortsContractTest {

    private static final AtomicInteger FIXTURES = new AtomicInteger();

    @InjectMock
    ChainSvcClient chainSvcClient;
    @Inject
    GameService gameService;
    @Inject
    DbMarketRegistry registry;
    @Inject
    RoundProgress roundProgress;
    @Inject
    RoundCalendar calendar;
    @Inject
    ScriptedMarketPrices prices;
    @Inject
    PlayerDirectory players;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    GameConfig config;

    private ServerBotPorts ports;
    private String botId;

    @BeforeEach
    void fixture() {
        prices.reset();
        int n = FIXTURES.incrementAndGet();
        long now = System.currentTimeMillis();
        Market open = listAndSeed("T_PORT_OPEN" + n, now);
        prices.setPrice(open, "2500.000");
        Market stale = listAndSeed("T_PORT_STALE" + n, now);
        prices.setStale(stale, "2500.000");
        botId = gameService.createBotPlayer("Bot Port Fixture " + n, "momentum-3-disciplined", now).playerId();
        // the view of one betting window group of the hourly series, "now": every market with a running round
        ports = new ServerBotPorts(gameService, players, roundProgress, roundResults, registry, prices, config, Interval.HOURLY, null,
                now, new HashMap<>());
    }

    private Market listAndSeed(String id, long now) {
        Market market = registry.list(new NewMarket(id, MarketKind.PERP, Category.CRYPTO, "PRT", "port test market", null, null,
                "synthetic", id, null, 25, 1, null), now);
        // the running round ends at least 30 s from now, so the betting round after it starts well ahead of the test
        roundProgress.seed(market.id(), Interval.HOURLY, calendar.windowContaining(market.id(), Interval.HOURLY, now + 30_000L));
        return market;
    }

    @Override
    protected MarketDataPort marketData() {
        return ports;
    }

    @Override
    protected BettingPort betting() {
        return ports;
    }

    @Override
    protected BotStatePort state() {
        return ports;
    }

    @Override
    protected String seededBotId() {
        return botId;
    }

    @Override
    protected String brokeBotId() {
        return null;
    }
}
