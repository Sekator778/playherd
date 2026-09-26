package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Drives the whole game clock: resolves every round whose window has elapsed (catching up in a
 * tight loop after any gap, e.g. a restart — see {@link RoundProgress}'s javadoc), runs bot
 * betting, and drains the oracle publish queue. One tick, one thread — {@code GameService}'s
 * mutating methods all take its write lock anyway (see that class's javadoc), but the scheduler
 * itself never overlaps itself (Quarkus's {@code @Scheduled} does not run a new invocation while
 * the previous one is still in flight, by default).
 */
@ApplicationScoped
public class RoundSchedulerService {

    private static final Logger LOG = Logger.getLogger(RoundSchedulerService.class);
    /** Р-23: "если за 1 час цену получить не удалось — раунд отменяется целиком". */
    private static final long LIVE_PRICE_GRACE_MS = 3_600_000L;

    @Inject
    RoundProgress roundProgress;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    GameService gameService;
    @Inject
    BotService botService;
    @Inject
    OraclePublisherService oraclePublisher;

    @Scheduled(every = "1s")
    void tick() {
        long now = System.currentTimeMillis();
        if (!roundProgress.isSeeded(Interval.HOURLY) || !roundProgress.isSeeded(Interval.DAILY)) {
            // Startup race (bug fix, see RoundProgress#isSeeded): this @Scheduled tick can be
            // armed and fire on its own executor thread before EngineRebuildService's
            // StartupEvent-observer rebuild() (main thread) has seeded RoundProgress for both
            // series. Skip this tick entirely rather than let bots/round-resolution/oracle-publish
            // below throw IllegalStateException for it — the very next tick (1s later) finds both
            // series seeded and resumes normally, every subsystem included.
            return;
        }
        try {
            botService.tick(now);
        } catch (Exception e) {
            LOG.error("bot tick failed", e);
        }
        for (Interval interval : Interval.values()) {
            try {
                resolveOverdueRounds(interval, now);
            } catch (Exception e) {
                LOG.errorf(e, "round resolution failed for %s — will retry next tick", interval);
            }
        }
        try {
            oraclePublisher.publishPending();
        } catch (Exception e) {
            LOG.error("oracle publish tick failed", e);
        }
    }

    private void resolveOverdueRounds(Interval interval, long now) {
        while (true) {
            RoundWindow next = roundProgress.next(interval);
            if (now < next.endMs()) {
                return; // nothing overdue for this series right now
            }
            if (roundResults.exists(next.roundId())) {
                // Already resolved — e.g. an operator cancelled this exact round ahead of time
                // (GameService#cancelRound never touches RoundProgress itself, see its javadoc).
                roundProgress.advance(interval);
                continue;
            }
            try {
                gameService.resolveRound(next, now);
            } catch (IllegalStateException e) {
                // Р-23 fallback (mode=live only — ReplayRoundPrices never throws this).
                if (now >= next.endMs() + LIVE_PRICE_GRACE_MS) {
                    gameService.cancelRound(next, "no oracle price available within 1 hour of round close", now);
                } else {
                    LOG.debugf("round %s has no price yet (%s) — retrying next tick", next.roundId(), e.getMessage());
                    return; // stop catching up on this series this tick; try again next tick
                }
            }
            roundProgress.advance(interval);
        }
    }
}
