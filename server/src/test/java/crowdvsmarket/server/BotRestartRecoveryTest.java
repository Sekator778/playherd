package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Regression test for two bugs that made "bots stopped betting after a restart" real on the
 * local stand:
 *
 * <ol>
 *   <li>{@code BotService#scheduleBots} computed a bot's fire time as
 *       {@code window.startMs() + offset} instead of relative to when betting for that window
 *       actually opens ({@code window.startMs() - duration}) — every fire time landed after
 *       betting had already closed, so almost no bot bet ever actually got accepted, restart or
 *       not (see its updated javadoc for the full mechanism).</li>
 *   <li>{@code RoundSchedulerService}'s {@code @Scheduled} tick could fire on its own executor
 *       thread before {@link EngineRebuildService}'s {@code StartupEvent} observer (main thread)
 *       finished seeding {@link RoundProgress}, throwing {@code IllegalStateException:
 *       RoundProgress not seeded for DAILY/HOURLY} — observed for real on the local stand's
 *       server logs right after a container restart.</li>
 * </ol>
 *
 * <p>This test drives {@link EngineRebuildService#rebuild()} directly — the exact same code path
 * a real process restart uses (see {@code GameFlowIntegrationTest}'s existing restart-recovery
 * assertions) — then waits for the very next betting window of BOTH series to settle and checks
 * at least one bot placed a bet in each. Before the fix above, this failed essentially always
 * (a bot's odds of firing inside its ever-shrinking, already-too-late window were on the order of
 * 1% per bot per round).
 */
@QuarkusTest
@TestProfile(BotRestartRecoveryTest.FastBothSeriesProfile.class)
class BotRestartRecoveryTest {

    public static class FastBothSeriesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // CI regression fix (docs/research/server_design.md "Shared Postgres across
            // @QuarkusTest classes"): 3s/4s left no real margin once this class runs as part of
            // the full suite (Quarkus restart + game_events truncation/rebuild between classes,
            // see CleanDatabasePerTestClass) rather than standalone — the round window is a fixed
            // wall-clock grid (RoundCalendar's origin epoch is NOT reset per test), and
            // herd.scheduler.tick-ms defaults to 1000ms, so a 3s window only ever gets 2-3 ticks;
            // any startup overhead eating into it could leave zero ticks before betting closed,
            // failing this test's own assertion (not the bot-count/rebuild-replay correctness this
            // class exists to check). 8s/10s keeps this comfortably "fast" while giving several
            // real ticks of margin regardless of what ran immediately before this class.
            return Map.of(
                    "herd.round-durations.hourly-sec", "8",
                    "herd.round-durations.daily-sec", "10",
                    "herd.bots.count", "6");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    EngineRebuildService engineRebuildService;
    @Inject
    RoundProgress roundProgress;
    @Inject
    PlayerDirectory players;
    @Inject
    RoundResultRepository roundResults;

    @Test
    void botsResumeBettingInBothSeriesAfterRestart() {
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
                    List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
                    return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
                });
        org.mockito.Mockito.when(chainSvcClient.oracleStatus()).thenReturn(null);
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);

        // --- simulate a process restart: same code path EngineRebuildService's own
        // StartupEvent observer uses, exercising RoundProgress re-seeding for both series and
        // BotService.ensureBotsExist — right at the start of this test, before any round has ever
        // settled in this fresh test app context. ---
        engineRebuildService.rebuild();

        Set<String> botIds = players.bots().stream().map(PlayerDirectory.Row::playerId).collect(Collectors.toSet());
        assertThat(botIds).hasSize(6);

        // --- the betting round each series is open for RIGHT NOW, right after the rebuild —
        // this is "the next betting window" bots must resume betting into. ---
        String hourlyBettingRoundId = ensureComfortableMargin(Interval.HOURLY).roundId();
        String dailyBettingRoundId = ensureComfortableMargin(Interval.DAILY).roundId();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> roundResults.exists(hourlyBettingRoundId));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .until(() -> roundResults.exists(dailyBettingRoundId));

        assertThat(botPlayerIdsIn(hourlyBettingRoundId, botIds))
                .as("HOURLY round %s should have at least one bot bet after the restart", hourlyBettingRoundId)
                .isNotEmpty();
        assertThat(botPlayerIdsIn(dailyBettingRoundId, botIds))
                .as("DAILY round %s should have at least one bot bet after the restart", dailyBettingRoundId)
                .isNotEmpty();
    }

    /**
     * {@code RoundCalendar}'s origin epoch is a fixed wall-clock instant (the whole suite run's
     * first-ever boot), never reset per test/class, so "the round open for betting right now" can
     * land anywhere in its window purely as a function of how much real time this class happened
     * to start at relative to that fixed grid — including, unluckily, right before it closes,
     * leaving the scheduler (1s ticks) no real chance to fire a bot bet. Rather than race that,
     * wait out a too-close-to-closing window and use the next (fresh, full-margin) one instead —
     * still the exact same "bots resume betting after a restart" behavior this class regression-
     * tests, just not tied to catching one specific window by chance.
     */
    private RoundWindow ensureComfortableMargin(Interval interval) {
        RoundWindow window = roundProgress.bettingWindow(interval);
        long duration = window.endMs() - window.startMs();
        long remainingMs = window.startMs() - System.currentTimeMillis();
        if (remainingMs >= duration / 2) {
            return window;
        }
        String staleRoundId = window.roundId();
        await().atMost(Duration.ofMillis(duration + 5_000)).pollInterval(Duration.ofMillis(100))
                .until(() -> !roundProgress.bettingWindow(interval).roundId().equals(staleRoundId));
        return roundProgress.bettingWindow(interval);
    }

    private Set<String> botPlayerIdsIn(String roundId, Set<String> botIds) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as("round %s", roundId).isInstanceOf(Rounds.RoundSettled.class);
        List<Rounds.PublicPositionDto> positions = ((Rounds.RoundSettled) dto).positions();
        return positions.stream().map(Rounds.PublicPositionDto::playerId)
                .filter(botIds::contains)
                .collect(Collectors.toSet());
    }
}
