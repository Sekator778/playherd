package crowdvsmarket.server.testsupport;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.DbMarketRegistry;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.scheduler.RoundSchedulerService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;

/**
 * What every test of the multi-market game needs, for {@link LiveMarketsProfile}: the beans, a way to
 * list a market and sign a player up, and the one trick that makes time the test's own — the scheduler
 * is off, so {@code scheduler.tick(now)} with a chosen {@code now} settles whatever has "ended" by then.
 * Markets are never deleted at runtime and cursors only move forward, so every test lists markets of its
 * own and works relative to whatever window a series is at.
 */
public abstract class LiveGameTestBase {

    @InjectMock
    protected ChainSvcClient chainSvcClient;

    @Inject
    protected GameService gameService;
    @Inject
    protected DbMarketRegistry registry;
    @Inject
    protected RoundProgress roundProgress;
    @Inject
    protected RoundSchedulerService scheduler;
    @Inject
    protected RoundResultRepository roundResults;
    @Inject
    protected EngineRebuildService rebuild;
    @Inject
    protected RoundCalendar calendar;
    @Inject
    protected EngineHub hub;
    @Inject
    protected ScriptedMarketPrices prices;
    @Inject
    protected Db db;

    @BeforeEach
    protected void resetScriptAndMockChain() {
        prices.reset();
        scheduler.forgetFailedAttempts();   // what one test's clock left behind must not hold a window back in another
        startBtcSeriesPastWhatIsSettled();
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
                    List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
                    return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
                });
        org.mockito.Mockito.when(chainSvcClient.oracleStatus()).thenReturn(null);
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);
    }

    /**
     * BTC's series are shared by every test, and a test that ticks the scheduler with a clock of its own leaves result
     * rows ahead of the real one. A restart on the real clock ({@code rebuild.rebuild()}: a series never starts ahead
     * of it, {@code SeriesSeeder}) then puts the cursor back at "now", behind those rows, and the next test finds the
     * betting window it wants already settled. Every test starts with BTC's cursors past everything settled, where a
     * restart put them before that rule.
     */
    private void startBtcSeriesPastWhatIsSettled() {
        for (Interval interval : Interval.values()) {
            Optional<Long> latestEnd = roundResults.latestWindowEnd("BTC", interval);
            Optional<RoundWindow> cursor = roundProgress.findNext("BTC", interval);
            if (latestEnd.isPresent() && cursor.isPresent() && cursor.get().startMs() < latestEnd.get()) {
                roundProgress.seed("BTC", interval, calendar.windowContaining("BTC", interval, latestEnd.get()));
            }
        }
    }

    protected Market list(String id, MarketKind kind, String category, String symbol, int maxLeverage, int lotExp) {
        return registry.list(new NewMarket(id, kind, category, symbol, symbol + " test market", null, null, "synthetic",
                id, null, maxLeverage, lotExp, null), System.currentTimeMillis());
    }

    /**
     * A market that has a backup exchange (Р-76): its {@code sourceMeta} says so, the way the spot catalog lists it, so
     * {@code Instruments.backupOf} finds it. The primary is called {@code coinbase} (the market's {@code source}).
     */
    protected Market listWithBackup(String id, String symbol, String backupSource, String backupRef) {
        String meta = "{\"backup\":{\"source\":\"" + backupSource + "\",\"ref\":\"" + backupRef + "\"}}";
        return registry.list(new NewMarket(id, MarketKind.PERP, "crypto", symbol, symbol + " test market", null, null,
                "coinbase", symbol + "-USD", meta, 10, 0, null), System.currentTimeMillis());
    }

    protected String signUp(String label) {
        String wallet = "txch1live" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()).player().playerId();
    }

    protected static Betting.PlaceBetRequest bet(RoundWindow window, Enums.Direction direction, int leverage,
                                                 String stake) {
        return new Betting.PlaceBetRequest(window.roundId(), direction, leverage, stake);
    }

    /** Puts {@code market}'s series at the same running window as {@code like} (series of different
     *  markets seeded at different instants can otherwise sit a window apart). */
    protected void alignTo(Market market, Interval interval, RoundWindow like) {
        roundProgress.seed(market.id(), interval,
                new RoundWindow(market.id(), interval, like.startMs(), like.endMs()));
    }

    /** One scheduler pass at the real "now": every series is brought to the window running now. */
    protected void catchUpToNow() {
        scheduler.tick(System.currentTimeMillis());
    }

    protected long oracleRowCount(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?",
                rs -> rs.getLong("n"), roundId));
    }
}
