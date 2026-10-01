package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.DbMarketRegistry;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.scheduler.RoundSchedulerService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code mode=replay} plays BTC only, "whatever the registry holds" (docs/research/
 * markets_architecture.md Т-12, §3.2 п.7): another market in the registry gets its engines (a rebuild
 * must know it) but no series, so nothing is bet on, resolved or priced for it, while BTC runs exactly as
 * before from the accelerated history, its prices still the 2-decimal strings they always were.
 */
@QuarkusTest
@TestProfile(ReplayModeMarketsTest.ReplayNoBotsProfile.class)
class ReplayModeMarketsTest {

    public static class ReplayNoBotsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.bots.count", "0");
        }
    }

    @InjectMock
    crowdvsmarket.server.chain.ChainSvcClient chainSvcClient;

    @Inject
    GameService gameService;
    @Inject
    DbMarketRegistry registry;
    @Inject
    RoundProgress roundProgress;
    @Inject
    EngineHub hub;
    @Inject
    RoundSchedulerService scheduler;
    @Inject
    Db db;

    @Test
    void anotherMarketHasEnginesButNoSeriesAndBtcRunsAsBefore() {
        Market market = registry.list(new NewMarket("T_RPL", MarketKind.PERP, Category.CRYPTO, "RPL", "Replay test",
                null, null, "synthetic", "T_RPL", null, 25, 1, null), System.currentTimeMillis());

        assertThat(hub.findMarket(market.id())).isPresent();
        assertThat(roundProgress.isSeeded(market.id(), Interval.HOURLY)).isFalse();
        assertThat(roundProgress.isSeeded(market.id(), Interval.DAILY)).isFalse();
        assertThat(gameService.runningMarkets()).extracting(Market::id).containsExactly("BTC");

        // nothing to read, bet on, price or settle for it
        assertThatThrownBy(() -> gameService.roundsPair(Enums.Interval.HOURLY, market.id(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.NOT_FOUND);
                    assertThat(e.getMessage()).contains("T_RPL");
                });
        RoundWindow window = new RoundWindow(market.id(), Interval.HOURLY, 60_000L, 120_000L);
        String playerId = gameService.registerOrTouchPlayer("txch1replay" + UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis()).player().playerId();
        assertThatThrownBy(() -> gameService.placeBet(playerId, new Betting.PlaceBetRequest(window.roundId(),
                Enums.Direction.UP, 2, "100.000"), 1L))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING));
        assertThatThrownBy(() -> gameService.referencePrice(market.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.PRICE_UNAVAILABLE));
        long eventsBefore = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events "
                + "WHERE payload->>'market' = 'T_RPL'", rs -> rs.getLong("n")));
        scheduler.tick(System.currentTimeMillis());
        long eventsAfter = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events "
                + "WHERE payload->>'market' = 'T_RPL'", rs -> rs.getLong("n")));
        assertThat(eventsAfter).isEqualTo(eventsBefore);

        // BTC: the same pair of rounds with the same 2-decimal prices, from the replay series
        Rounds.RoundsPairDto pair = gameService.roundsPair(Enums.Interval.HOURLY, null, null);
        assertThat(pair.betting()).isInstanceOfSatisfying(Rounds.RoundBetting.class, r -> {
            assertThat(r.marketId()).isEqualTo("BTC");
            assertThat(r.asset()).isEqualTo("BTC");
            assertThat(r.roundId()).startsWith("1h-");
        });
        // (in the instant between the scheduler settling a round and moving the cursor, "running" is the
        // settled round already — its prices have the same shape)
        String openPrice = pair.running() instanceof Rounds.RoundRunning r ? r.openPrice()
                : ((Rounds.RoundSettled) pair.running()).openPrice();
        assertThat(openPrice).matches("\\d+\\.\\d{2}");
        assertThat(gameService.btcReferencePrice().price()).matches("\\d+\\.\\d{2}");
        assertThat(gameService.referencePrice("BTC").price()).matches("\\d+\\.\\d{2}");
        Betting.LiquidationPreviewDto preview = gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(
                null, Enums.Direction.UP, 10, "100.000", "83451.00"));
        assertThat(preview.entryPriceUsed()).isEqualTo("83451.00");
        assertThat(preview.liquidationPrice()).matches("\\d+\\.\\d{2}");
        List<String> ids = gameService.crowdAccount(null).history().stream().map(h -> h.roundId()).toList();
        assertThat(ids).allSatisfy(id -> assertThat(id).matches("1[hd]-\\d+"));
    }
}
