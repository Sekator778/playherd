package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * {@link #originMs()} is the server's first-ever-boot instant (task: "boundaries aligned to the
 * server start epoch"), persisted once in {@code server_meta} so it survives restarts unchanged —
 * a restart must not shift any round boundary out from under bets already placed on it.
 * {@link #firstWindow} is the very first window of a series, exactly at the origin — the seed
 * {@link RoundProgress} starts from when no round has ever been resolved yet.
 *
 * <p>Р-19's "always one running + one open [for betting] round per series" is <b>not</b>
 * recomputed here from wall-clock {@code now} on every read (naive
 * {@code RoundClock.windowContaining(interval, now, ...)} would, right at a boundary, race
 * against the scheduler that has not resolved the elapsed round yet) — see {@link RoundProgress},
 * which tracks the actual next-window-to-resolve explicitly instead.
 */
@ApplicationScoped
public class RoundCalendar {

    @Inject
    GameConfig config;

    @Inject
    ServerMetaRepository serverMeta;

    private volatile long originMs = -1L;

    /** Call once, early at startup (before the scheduler or any price source needs it) so the
     *  origin is fixed for the rest of the process's life. */
    public synchronized long init(long nowMsIfFirstBoot) {
        if (originMs < 0) {
            originMs = serverMeta.originMsOrInit(nowMsIfFirstBoot);
        }
        return originMs;
    }

    public long originMs() {
        if (originMs < 0) {
            throw new IllegalStateException("RoundCalendar.init() was not called yet");
        }
        return originMs;
    }

    public RoundWindow firstWindow(Interval interval) {
        return RoundClock.windowContaining(interval, originMs(), config.roundDurations(), originMs());
    }
}
