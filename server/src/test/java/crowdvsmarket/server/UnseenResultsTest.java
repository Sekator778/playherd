package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Р-62's "while you were away" cross-check: a player offline at settlement never otherwise sees the
 * result moment ({@code docs/product/design/result-moment.md}) — {@code myUnseenResults}/
 * {@code ackResults} (served over RPC as {@code unseenResults}/{@code acknowledgeResults}, matching
 * contract.ts's already-written {@code Api} methods) let the app catch up on return. Drives
 * {@link GameService} directly and waits for the real scheduler to settle one round, same pattern
 * as {@code BotRestartRecoveryTest}.
 */
@QuarkusTest
@TestProfile(UnseenResultsTest.FastNoBotsProfile.class)
class UnseenResultsTest {

    public static class FastNoBotsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // CI regression fix (docs/research/server_design.md "Shared Postgres across
            // @QuarkusTest classes"): same margin fix as BotRestartRecoveryTest.FastBothSeriesProfile
            // — 3s/4s left no real headroom between computing bettingWindow and this test's own
            // placeBet call once class-to-class Quarkus-restart/DB-truncation overhead (see
            // CleanDatabasePerTestClass) is in the mix, occasionally landing placeBet after the
            // real scheduler had already advanced roundProgress past this window
            // (ROUND_NOT_OPEN_FOR_BETTING) even though this test passes an explicit
            // bettingWindow.startMs() - 1 nowMs.
            return Map.of(
                    "herd.round-durations.hourly-sec", "8",
                    "herd.round-durations.daily-sec", "10",
                    "herd.bots.count", "0");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    GameService gameService;
    @Inject
    RoundProgress roundProgress;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    EngineRebuildService engineRebuildService;

    private void mockChainSvc() {
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
                    List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
                    return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
                });
        org.mockito.Mockito.when(chainSvcClient.oracleStatus())
                .thenReturn(new ChainSvcClient.OracleStatus(
                        "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20", "testnet11", 1L, 4_735_705L,
                        0, null, "10000000000", null, List.of()));
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);
    }

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1unseen" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    @Test
    void unseenResultAppearsAfterSettlementAndClearsOnAckAndSurvivesRebuild() {
        mockChainSvc();
        String playerId = signUpPlayer("a");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        gameService.placeBet(playerId, new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 5,
                "100.000"), bettingWindow.startMs() - 1);

        // "away": nobody calls myUnseenResults until well after settlement.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> roundResults.exists(bettingWindow.roundId()));

        List<Identity.ProfileRoundEntryDto> unseen = gameService.myUnseenResults(playerId);
        assertThat(unseen).extracting(Identity.ProfileRoundEntryDto::roundId).contains(bettingWindow.roundId());
        Identity.ProfileRoundEntryDto entry = unseen.stream()
                .filter(e -> e.roundId().equals(bettingWindow.roundId())).findFirst().orElseThrow();
        assertThat(entry.asset()).isEqualTo("BTC");
        assertThat(entry.direction()).isEqualTo(Enums.Direction.UP);
        assertThat(entry.leverage()).isEqualTo(5);
        assertThat(entry.stake()).isEqualTo("100.000");

        // acking makes it disappear from the unseen list…
        gameService.ackResults(playerId, List.of(bettingWindow.roundId()), System.currentTimeMillis());
        assertThat(gameService.myUnseenResults(playerId))
                .extracting(Identity.ProfileRoundEntryDto::roundId).doesNotContain(bettingWindow.roundId());

        // …re-acking the same round is a harmless no-op (idempotent)…
        gameService.ackResults(playerId, List.of(bettingWindow.roundId()), System.currentTimeMillis());
        assertThat(gameService.myUnseenResults(playerId))
                .extracting(Identity.ProfileRoundEntryDto::roundId).doesNotContain(bettingWindow.roundId());

        // …and the ack is durable (a DB column, not in-memory engine state) — survives a full
        // restart-recovery replay exactly like round_results itself (GameFlowIntegrationTest).
        engineRebuildService.rebuild();
        assertThat(gameService.myUnseenResults(playerId))
                .extracting(Identity.ProfileRoundEntryDto::roundId).doesNotContain(bettingWindow.roundId());
    }

    @Test
    void ackIsScopedToTheAckingPlayerOnly() {
        mockChainSvc();
        String playerA = signUpPlayer("b1");
        String playerB = signUpPlayer("b2");
        RoundWindow bettingWindow = roundProgress.bettingWindow(Interval.HOURLY);
        gameService.placeBet(playerA, new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.UP, 2,
                "100.000"), bettingWindow.startMs() - 1);
        gameService.placeBet(playerB, new Betting.PlaceBetRequest(bettingWindow.roundId(), Enums.Direction.DOWN, 2,
                "100.000"), bettingWindow.startMs() - 1);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> roundResults.exists(bettingWindow.roundId()));

        assertThat(gameService.myUnseenResults(playerA))
                .extracting(Identity.ProfileRoundEntryDto::roundId).contains(bettingWindow.roundId());
        assertThat(gameService.myUnseenResults(playerB))
                .extracting(Identity.ProfileRoundEntryDto::roundId).contains(bettingWindow.roundId());

        // player B acking THIS round never touches player A's own unseen row for the same round —
        // ack is scoped by (player_id, round_id), not round_id alone.
        gameService.ackResults(playerB, List.of(bettingWindow.roundId()), System.currentTimeMillis());
        assertThat(gameService.myUnseenResults(playerB))
                .extracting(Identity.ProfileRoundEntryDto::roundId).doesNotContain(bettingWindow.roundId());
        assertThat(gameService.myUnseenResults(playerA))
                .extracting(Identity.ProfileRoundEntryDto::roundId).contains(bettingWindow.roundId());

        // acking an unknown/never-had-a-position round id is a harmless no-op, not an error.
        gameService.ackResults(playerA, List.of("1h-999999999999"), System.currentTimeMillis());
    }
}
