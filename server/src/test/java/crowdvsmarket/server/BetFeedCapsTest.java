package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Р-62's live bet feed ({@code betFeed} RPC, backed by {@link BetFeed}): ordering, {@code sinceSeq}
 * pagination, and both caps ({@link BetFeed#PAGE_SIZE} per call, {@link BetFeed#MAX_ENTRIES_PER_ROUND}
 * retained per round). Drives {@link GameService} directly with a controlled {@code nowMs}, same
 * pattern as {@code PlaceBetWindowBoundaryTest} — no HTTP, no waiting on the real scheduler.
 *
 * <p>{@code herd.bots.count=0}: these tests count exact feed entries, so bot activity (which would
 * add unpredictable extra rows to the SAME current betting round) is turned off entirely, unlike
 * {@code GameFlowIntegrationTest}'s lower-bound-only assertions.
 */
@QuarkusTest
@TestProfile(BetFeedCapsTest.NoBotsProfile.class)
class BetFeedCapsTest {

    public static class NoBotsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.bots.count", "0");
        }
    }

    @Inject
    GameService gameService;
    @Inject
    RoundProgress roundProgress;

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1feed" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    /** Places (or edits, for a repeat player) one bet at a fixed {@code nowMs} strictly before the
     *  window opens — every call in these tests reuses the SAME window/nowMs, since only the
     *  ordering/count of the resulting feed entries matters, not real wall-clock timing. */
    private void bet(String playerId, RoundWindow window, long nowMs, Enums.Direction direction, int leverage) {
        Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(window.roundId(), direction, leverage, "100.000");
        gameService.placeBet(playerId, req, nowMs);
    }

    @Test
    void feedOrdersEntriesBySeqAndSupportsSinceSeqPagination() {
        RoundWindow window = roundProgress.bettingWindow(Interval.HOURLY);
        long nowMs = window.startMs() - 1;
        String p1 = signUpPlayer("ord1");
        String p2 = signUpPlayer("ord2");
        String p3 = signUpPlayer("ord3");

        bet(p1, window, nowMs, Enums.Direction.UP, 5);
        bet(p2, window, nowMs, Enums.Direction.DOWN, 10);
        bet(p3, window, nowMs, Enums.Direction.UP, 2);

        Rounds.BetFeedDto page = gameService.betFeed(window.roundId(), null);
        assertThat(page.roundId()).isEqualTo(window.roundId());
        assertThat(page.asset()).isEqualTo("BTC");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.entries()).hasSize(3);
        assertThat(page.entries()).extracting(Rounds.BetFeedEntryDto::seq)
                .isSorted(); // strictly increasing seq, oldest first
        assertThat(page.entries().get(0).direction()).isEqualTo(Enums.Direction.UP);
        assertThat(page.entries().get(0).stake()).isEqualTo("100.000");
        assertThat(page.entries().get(0).leverage()).isEqualTo(5);
        assertThat(page.entries().get(0).isBot()).isFalse();
        assertThat(page.entries().get(1).direction()).isEqualTo(Enums.Direction.DOWN);
        assertThat(page.entries().get(2).leverage()).isEqualTo(2);

        // sinceSeq: only entries after the first one
        long firstSeq = page.entries().get(0).seq();
        Rounds.BetFeedDto tail = gameService.betFeed(window.roundId(), firstSeq);
        assertThat(tail.entries()).hasSize(2);
        assertThat(tail.entries()).allMatch(e -> e.seq() > firstSeq);

        // an edit by an existing player (Р-13) appends a NEW row, does not rewrite the old one
        bet(p1, window, nowMs, Enums.Direction.DOWN, 20);
        Rounds.BetFeedDto afterEdit = gameService.betFeed(window.roundId(), null);
        assertThat(afterEdit.entries()).hasSize(4);
        Rounds.BetFeedEntryDto lastEntry = afterEdit.entries().get(3);
        assertThat(lastEntry.direction()).isEqualTo(Enums.Direction.DOWN);
        assertThat(lastEntry.leverage()).isEqualTo(20);
    }

    @Test
    void feedCapsOnePageAtPageSizeAndTotalRetentionAtMaxEntriesPerRound() {
        RoundWindow window = roundProgress.bettingWindow(Interval.DAILY);
        long nowMs = window.startMs() - 1;
        String soleBettor = signUpPlayer("cap");

        // One player editing their bet repeatedly generates one feed row per placeBet call,
        // independent of Р-13's "one position per player per round" (that rule only bounds the
        // BetBook's CURRENT state, not this append-only activity log).
        int totalPlacements = BetFeed.MAX_ENTRIES_PER_ROUND + 5;
        for (int i = 0; i < totalPlacements; i++) {
            bet(soleBettor, window, nowMs, i % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN, 2 + (i % 39));
        }

        // Page size cap: one call never returns more than PAGE_SIZE, and says so via hasMore.
        Rounds.BetFeedDto firstPage = gameService.betFeed(window.roundId(), null);
        assertThat(firstPage.entries()).hasSize(BetFeed.PAGE_SIZE);
        assertThat(firstPage.hasMore()).isTrue();

        // Sweep every page via sinceSeq pagination and count the total retained.
        List<Rounds.BetFeedEntryDto> all = new ArrayList<>(firstPage.entries());
        long sinceSeq = firstPage.entries().get(firstPage.entries().size() - 1).seq();
        Rounds.BetFeedDto page = firstPage;
        while (page.hasMore()) {
            page = gameService.betFeed(window.roundId(), sinceSeq);
            assertThat(page.entries().size()).isLessThanOrEqualTo(BetFeed.PAGE_SIZE);
            all.addAll(page.entries());
            if (!page.entries().isEmpty()) {
                sinceSeq = page.entries().get(page.entries().size() - 1).seq();
            }
        }

        // Retention cap: the round accumulated MAX_ENTRIES_PER_ROUND + 5 placements, but only the
        // newest MAX_ENTRIES_PER_ROUND survive — the oldest 5 were evicted, never returned by any
        // page, regardless of sinceSeq=0.
        assertThat(all).hasSize(BetFeed.MAX_ENTRIES_PER_ROUND);
        assertThat(all).extracting(Rounds.BetFeedEntryDto::seq).isSorted();
        // The very last placement (index totalPlacements-1, odd -> DOWN, leverage 2+((total-1)%39))
        // must be present — proof the newest entries, not the oldest, are what got kept.
        int lastIndex = totalPlacements - 1;
        Rounds.BetFeedEntryDto newest = all.get(all.size() - 1);
        assertThat(newest.direction()).isEqualTo(lastIndex % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN);
        assertThat(newest.leverage()).isEqualTo(2 + (lastIndex % 39));
    }
}
