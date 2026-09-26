package crowdvsmarket.server.testsupport;

import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import java.time.Duration;

/**
 * Betting windows for tests that run the real scheduler with short rounds (8–10 s profiles).
 * {@code RoundCalendar}'s grid is fixed wall-clock time, so "the window open right now" can be a
 * few milliseconds from closing when a test reads it; the scheduler then advances
 * {@link RoundProgress} before the test's own {@code placeBet} and the bet is rejected with
 * ROUND_NOT_OPEN_FOR_BETTING (seen on CI, PR #2's run 36244309916).
 */
public final class BettingWindows {

    private BettingWindows() {
    }

    /** The current window if more than half of it is still ahead, otherwise the next one. */
    public static RoundWindow fresh(RoundProgress roundProgress, Interval interval) {
        RoundWindow window = roundProgress.bettingWindow(interval);
        long duration = window.endMs() - window.startMs();
        if (window.startMs() - System.currentTimeMillis() >= duration / 2) {
            return window;
        }
        return next(roundProgress, interval);
    }

    /** Waits for the scheduler to open the window after the current one and returns it. */
    public static RoundWindow next(RoundProgress roundProgress, Interval interval) {
        RoundWindow current = roundProgress.bettingWindow(interval);
        long duration = current.endMs() - current.startMs();
        String currentRoundId = current.roundId();
        return await().atMost(Duration.ofMillis(duration + 5_000)).pollInterval(Duration.ofMillis(50))
                .until(() -> roundProgress.bettingWindow(interval),
                        window -> !window.roundId().equals(currentRoundId));
    }
}
