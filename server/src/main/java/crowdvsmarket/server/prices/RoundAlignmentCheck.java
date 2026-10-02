package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundCalendar;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses to start a server that would settle rounds on boundaries its price history cannot price exactly.
 *
 * <p>The history of a candle source is minute-resolution: the price at a round boundary is the close of the
 * candle before it, exact only when the boundary is on a whole minute (hl_multi_asset_live.md §4.1, §13.1; for
 * a boundary inside a minute the candle that contains it is still open and its last trade unknown). The
 * boundaries are {@code origin + k × duration}, so the calendar's origin and both round durations must be
 * multiples of 60 s. A fresh database aligns its origin by itself (the origin of a database made in
 * {@code herd.mode=live} is a multiple of the DAILY round, {@link RoundCalendar}); a database that was made in
 * replay mode keeps the instant of its first boot, to the millisecond, and so does any configuration with a
 * round of, say, 90.5 s.
 *
 * <p>The check runs at start-up, only where it matters: {@code herd.mode=live} with the real sources
 * ({@code herd.prices.source=live}) and the scheduler on — the synthetic walk has a price at every second, and
 * with the scheduler off (a test of the game core) nothing is ever asked of a source. It reads the same
 * config the scheduler uses ({@link GameConfig#roundDurations}) and the origin through
 * {@link RoundCalendar#init}, which is idempotent: whoever asks first fixes the origin, the others read it.
 * {@code herd.prices.require-minute-aligned-rounds=false} turns the refusal into an error line in the log.
 */
@ApplicationScoped
@Unremovable
public class RoundAlignmentCheck {

    private static final Logger LOG = Logger.getLogger(RoundAlignmentCheck.class);

    static final long MINUTE_MS = 60_000L;

    @Inject
    PricesConfig prices;
    @Inject
    GameConfig game;
    @Inject
    RoundCalendar calendar;
    @ConfigProperty(name = "quarkus.scheduler.enabled", defaultValue = "true")
    boolean schedulerEnabled;

    void onStart(@Observes StartupEvent event) {
        check(System.currentTimeMillis());
    }

    /** @throws IllegalStateException when a round boundary is not on a whole minute and the check is required */
    void check(long nowMs) {
        if (!prices.liveSources() || !schedulerEnabled) {
            return;
        }
        RoundDurations durations = game.roundDurations();
        long origin = calendar.init(nowMs);
        List<String> problems = problems(durations.hourlyMs(), durations.dailyMs(), origin);
        if (problems.isEmpty()) {
            return;
        }
        String message = message(problems);
        if (prices.requireMinuteAlignedRounds()) {
            throw new IllegalStateException(message);
        }
        LOG.error(message + " Starting anyway because herd.prices.require-minute-aligned-rounds=false.");
    }

    /** What is off a whole minute: each round duration and the calendar origin; empty when every boundary is on one. */
    static List<String> problems(long hourlyMs, long dailyMs, long originMs) {
        List<String> problems = new ArrayList<>();
        if (hourlyMs % MINUTE_MS != 0) {
            problems.add("the HOURLY round is " + hourlyMs + " ms (herd.round-durations.hourly-sec)");
        }
        if (dailyMs % MINUTE_MS != 0) {
            problems.add("the DAILY round is " + dailyMs + " ms (herd.round-durations.daily-sec)");
        }
        long past = Math.floorMod(originMs, MINUTE_MS);
        if (past != 0) {
            problems.add("the calendar origin " + originMs + " ms is " + past + " ms past a whole minute");
        }
        return problems;
    }

    static String message(List<String> problems) {
        return "herd.mode=live with real price sources needs every round boundary on a whole minute, because the price "
                + "history is minute-resolution and a boundary price from it is exact only there, but "
                + String.join("; ", problems) + ". Use round durations that are multiples of 60 s and a database "
                + "whose origin is a multiple of the DAILY round (a fresh database made in live mode has one; one "
                + "made in replay mode keeps the instant of its first start), or set "
                + "herd.prices.require-minute-aligned-rounds=false to start anyway.";
    }
}
