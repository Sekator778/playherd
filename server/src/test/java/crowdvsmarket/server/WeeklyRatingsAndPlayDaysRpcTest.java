package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.domain.PlayDays;
import crowdvsmarket.server.domain.WeeklyBoards;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The "This week" boards ({@code weeklyRatings}) and "played N days in a row" ({@code PublicProfileDto.playDays}) over the real database:
 * history rows are written the way a settlement writes them, at chosen instants, so the edges of the window and of the UTC day are exact.
 * The rules themselves are in {@code WeeklyBoardsTest} / {@code PlayDaysTest}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class WeeklyRatingsAndPlayDaysRpcTest extends LiveRpcTestBase {

    private static final long MONDAY = Instant.parse("2026-10-19T00:00:00Z").toEpochMilli();
    private static final long NOW = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli();

    private void row(String playerId, long settledAtMs, long stake, long payout, Boolean correct) {
        db.tx(c -> db.update(c, """
                INSERT INTO player_round_history (round_id, player_id, interval, settled_at_ms, direction,
                    leverage, stake_mojos, liquidated, direction_correct, payout_mojos, market)
                VALUES (?, ?, 'HOURLY', ?, 'UP', 2, ?, FALSE, ?, ?, 'BTC')
                """, "wk-" + UUID.randomUUID(), playerId, settledAtMs, stake, correct, payout));
    }

    private void rows(String playerId, int count, long settledAtMs, long stake, long payout, Boolean correct) {
        for (int i = 0; i < count; i++) {
            row(playerId, settledAtMs + i, stake, payout, correct);
        }
    }

    @Test
    void theWindowIsMondayToNowTheMinimumIsTenRoundsAndTheRichListHoldsWinnersOnly() throws Exception {
        Session winner = signIn("wk-winner");
        Session edge = signIn("wk-edge");
        Session nine = signIn("wk-nine");
        Session loser = signIn("wk-loser");
        // 10 winning rounds this week: +0.800 each
        rows(winner.playerId(), 10, MONDAY + 5_000, 100_000, 100_800, true);
        // 6 rounds in the last milliseconds of the previous week do not count; 1 at Monday 00:00:00.000 and 3 just after do: 4 < 10
        rows(edge.playerId(), 6, MONDAY - 10, 100_000, 100_800, true);
        rows(edge.playerId(), 1, MONDAY, 100_000, 100_800, true);
        rows(edge.playerId(), 3, MONDAY + 1, 100_000, 100_800, true);
        // one round short of the minimum
        rows(nine.playerId(), 9, MONDAY + 10_000, 100_000, 150_000, true);
        // enough rounds, a net loss
        rows(loser.playerId(), 12, MONDAY + 20_000, 100_000, 90_000, false);
        // rounds after "now" are not in the window
        rows(loser.playerId(), 3, NOW + 1, 100_000, 300_000, true);

        var weekly = gameService.weeklyRatings(NOW);

        assertThat(weekly.weekStartMs()).isEqualTo(MONDAY);
        assertThat(weekly.roundsThreshold()).isEqualTo(10);
        assertThat(weekly.rich()).extracting(e -> e.player().playerId()).contains(winner.playerId())
                .doesNotContain(edge.playerId(), nine.playerId(), loser.playerId());
        var mine = weekly.rich().stream().filter(e -> e.player().playerId().equals(winner.playerId())).findFirst().orElseThrow();
        assertThat(mine.growth()).isEqualTo("8.000");
        assertThat(mine.roundsPlayed()).isEqualTo(10);
        assertThat(weekly.rich()).as("ranks run 1..n").extracting(e -> e.rank())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, weekly.rich().size()).boxed().toList());
        // the prophets: the loser had 12 rounds, none right, and is listed with 0 (an accuracy, not a loss figure)
        assertThat(weekly.prophets()).extracting(e -> e.player().playerId()).contains(winner.playerId(), loser.playerId())
                .doesNotContain(edge.playerId(), nine.playerId());

        // the window just before Monday is the week before, which holds none of these rounds
        var previous = gameService.weeklyRatings(MONDAY - 1);
        assertThat(previous.weekStartMs()).isEqualTo(MONDAY - 7 * PlayDays.DAY_MS);
        assertThat(previous.rich()).extracting(e -> e.player().playerId()).doesNotContain(winner.playerId());
        assertThat(previous.prophets()).extracting(e -> e.player().playerId()).doesNotContain(edge.playerId());
    }

    @Test
    void theWeeklyCallIsPublicAndAnswersTheDocumentedKeys() throws Exception {
        JsonNode answer = ok(null, "weeklyRatings");
        Set<String> keys = new TreeSet<>();
        answer.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isEqualTo(new TreeSet<>(Set.of("weekStartMs", "roundsThreshold", "rich", "prophets")));
        assertThat(answer.path("weekStartMs").asLong()).isEqualTo(WeeklyBoards.weekStartMs(System.currentTimeMillis()));
        assertThat(ok(null, "botWeeklyRatings").path("rich").isArray()).isTrue();
    }

    @Test
    void daysInARowAreCountedByUtcDayAndAGapEndsTheRunButNotTheBest() throws Exception {
        long today = PlayDays.dayOf(System.currentTimeMillis());
        long dayMs = PlayDays.DAY_MS;
        Session me = signIn("wk-days");
        // today, yesterday, the day before: three; then a gap; then four days
        for (long d : new long[] {0, 1, 2, 4, 5, 6, 7}) {
            row(me.playerId(), (today - d) * dayMs + 1_000L, 100_000, 90_000, false);
        }
        // a second round on the same day changes nothing
        row(me.playerId(), (today - 1) * dayMs + 2_000L, 100_000, 100_000, null);

        JsonNode profile = ok(me, "myProfile");

        assertThat(profile.path("playDays").path("current").asInt()).isEqualTo(3);
        assertThat(profile.path("playDays").path("best").asInt()).isEqualTo(4);
        JsonNode seenByAnother = ok(signIn("wk-days-viewer"), "publicProfile", me.playerId());
        assertThat(seenByAnother.path("playDays").path("current").asInt()).isEqualTo(3);
        assertThat(seenByAnother.path("playDays").path("best").asInt()).isEqualTo(4);
    }

    @Test
    void aRunThatEndedYesterdayIsStillShownAndOlderOnesAreAZeroNotAnError() throws Exception {
        long today = PlayDays.dayOf(System.currentTimeMillis());
        long dayMs = PlayDays.DAY_MS;
        Session yesterday = signIn("wk-yesterday");
        row(yesterday.playerId(), (today - 1) * dayMs + 1_000L, 100_000, 90_000, false);
        row(yesterday.playerId(), (today - 2) * dayMs + 1_000L, 100_000, 90_000, false);
        assertThat(ok(yesterday, "myProfile").path("playDays").path("current").asInt()).isEqualTo(2);

        Session old = signIn("wk-old");
        row(old.playerId(), (today - 5) * dayMs + 1_000L, 100_000, 90_000, false);
        JsonNode playDays = ok(old, "myProfile").path("playDays");
        assertThat(playDays.path("current").asInt()).isZero();
        assertThat(playDays.path("best").asInt()).isEqualTo(1);

        JsonNode fresh = ok(signIn("wk-fresh"), "myProfile").path("playDays");
        assertThat(fresh.path("current").asInt()).isZero();
        assertThat(fresh.path("best").asInt()).isZero();
    }
}
