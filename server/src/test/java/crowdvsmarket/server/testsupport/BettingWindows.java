package crowdvsmarket.server.testsupport;

import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import java.time.Duration;

/**
 * Betting windows for tests that run the real scheduler with short rounds (8–10 s profiles).
 * Two ways a test used to get a window it could no longer bet into (ROUND_NOT_OPEN_FOR_BETTING on
 * CI, run 36244309916 and PR #3's first run):
 * <ul>
 *   <li>{@code RoundCalendar}'s grid is fixed wall-clock time, so the window open right now can be
 *       milliseconds from closing when the test reads it;</li>
 *   <li>after {@code EngineRebuildService.rebuild()} {@link RoundProgress} is seeded from the last
 *       settled round, i.e. behind wall-clock time, and the next scheduler tick advances it
 *       through every overdue round — a poll during that catch-up sees windows already in the
 *       past.</li>
 * </ul>
 * Both helpers therefore wait for a window that still has real time ahead of it. A series is a market
 * plus an interval (docs/research/markets_architecture.md §4 item 1); the overloads without a market are
 * BTC's.
 */
public final class BettingWindows {

    private BettingWindows() {
    }

    /** A BTC window with at least min(half its length, 5 s) still ahead — the current one if it
     *  qualifies, so 180 s default rounds rarely wait. */
    public static RoundWindow fresh(RoundProgress roundProgress, Interval interval) {
        return fresh(roundProgress, MarketSpec.BTC_ID, interval);
    }

    /** {@link #fresh(RoundProgress, Interval)} for the series of {@code marketId}. */
    public static RoundWindow fresh(RoundProgress roundProgress, String marketId, Interval interval) {
        return await().atMost(maxWait(roundProgress, marketId, interval)).pollInterval(Duration.ofMillis(50))
                .until(() -> roundProgress.bettingWindow(marketId, interval), BettingWindows::hasMargin);
    }

    /** Like {@link #fresh} but never the BTC window open at call time — for tests that need a round
     *  whose betting opened after something they just did (e.g. bots re-created by a rebuild). */
    public static RoundWindow next(RoundProgress roundProgress, Interval interval) {
        return next(roundProgress, MarketSpec.BTC_ID, interval);
    }

    /** {@link #next(RoundProgress, Interval)} for the series of {@code marketId}. */
    public static RoundWindow next(RoundProgress roundProgress, String marketId, Interval interval) {
        String currentRoundId = roundProgress.bettingWindow(marketId, interval).roundId();
        return await().atMost(maxWait(roundProgress, marketId, interval)).pollInterval(Duration.ofMillis(50))
                .until(() -> roundProgress.bettingWindow(marketId, interval),
                        window -> !window.roundId().equals(currentRoundId) && hasMargin(window));
    }

    private static boolean hasMargin(RoundWindow window) {
        long duration = window.endMs() - window.startMs();
        return window.startMs() - System.currentTimeMillis() >= Math.min(duration / 2, 5_000);
    }

    private static Duration maxWait(RoundProgress roundProgress, String marketId, Interval interval) {
        RoundWindow window = roundProgress.bettingWindow(marketId, interval);
        return Duration.ofMillis(2 * (window.endMs() - window.startMs()) + 5_000);
    }
}
