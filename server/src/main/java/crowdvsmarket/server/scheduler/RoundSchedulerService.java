package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Drives the whole game clock: for every running market and every series of it, resolves every round
 * whose window has elapsed (catching up in a tight loop after any gap, e.g. a restart — see
 * {@link RoundProgress}'s javadoc), runs bot betting, and drains the oracle publish queue.
 *
 * <p>The tick is {@code concurrentExecution = SKIP}: Quarkus's default is {@code PROCEED}, i.e. a
 * tick that is still busy when the next second arrives does NOT delay it — two ticks would then
 * resolve the same window side by side (docs/research/markets_architecture.md §4 п.4). With SKIP the
 * scheduler never overlaps itself; {@code GameService}'s mutating methods all take its write lock
 * and re-check {@code round_results} under it anyway, so even an operator action racing the tick
 * cannot settle a round twice.
 *
 * <p>A failure in one series (a price that cannot be read, an exception) never stops the others: each
 * (market, interval) is its own try block.
 *
 * <p><b>Catch-up (§4 п.5):</b> a window with bets is always resolved or cancelled, however late; an
 * EMPTY window that is overdue by more than one step is not resolved at all — the series jumps to the
 * window that contains "now", with no event, no result and no oracle record (one log line per jump).
 * <b>No price (Р-23):</b> {@link PriceUnavailableException} means "try again next tick"; one hour after
 * the window's end the round is cancelled with a refund.
 */
@ApplicationScoped
public class RoundSchedulerService {

    private static final Logger LOG = Logger.getLogger(RoundSchedulerService.class);
    /** Р-23: "если за 1 час цену получить не удалось — раунд отменяется целиком". */
    private static final long PRICE_GRACE_MS = 3_600_000L;

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
    @Inject
    EngineRebuildService rebuildService;
    @Inject
    GameConfig config;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        tick(System.currentTimeMillis());
    }

    /** One scheduler pass as of {@code now} — the {@link #tick()} body, with the clock as an
     *  argument so tests can drive time. */
    public void tick(long now) {
        if (!rebuildService.isReady()) {
            // Startup race (bug fix, see RoundProgress#isSeeded): this @Scheduled tick can be armed
            // and fire on its own executor thread before EngineRebuildService's StartupEvent-observer
            // rebuild() (main thread) has finished. Skip the tick entirely rather than let
            // bots/round-resolution/oracle-publish below work on an empty engine — the very next tick
            // (1s later) finds it ready and resumes normally, every subsystem included.
            return;
        }
        List<Market> markets = gameService.runningMarkets();
        for (Market market : markets) {
            seedMissingSeries(market);
        }
        try {
            botService.tick(now);
        } catch (Exception e) {
            LOG.error("bot tick failed", e);
        }
        for (Market market : markets) {
            for (Interval interval : Interval.values()) {
                try {
                    resolveOverdueRounds(market, interval, now);
                } catch (Exception e) {
                    LOG.errorf(e, "round resolution failed for %s/%s — will retry next tick", market.id(), interval);
                }
            }
        }
        try {
            oraclePublisher.publishPending();
        } catch (Exception e) {
            LOG.error("oracle publish tick failed", e);
        }
    }

    /** A running market without a series — listed while no listener was registered, or its
     *  notification was lost — gets one at the window containing "now" (§4 п.2). */
    private void seedMissingSeries(Market market) {
        boolean missing = false;
        for (Interval interval : Interval.values()) {
            missing |= !roundProgress.isSeeded(market.id(), interval);
        }
        if (!missing) {
            return;
        }
        try {
            gameService.ensureSeries(market);
        } catch (Exception e) {
            LOG.errorf(e, "could not seed the series of market %s — will retry next tick", market.id());
        }
    }

    void resolveOverdueRounds(Market market, Interval interval, long now) {
        String marketId = market.id();
        long stepMs = config.roundDurations().forInterval(interval);
        while (true) {
            Optional<RoundWindow> cursor = roundProgress.findNext(marketId, interval);
            if (cursor.isEmpty()) {
                return; // not seeded (yet) or stopped: the market closed, or a rebuild is under way
            }
            RoundWindow next = cursor.get();
            if (now < next.endMs()) {
                return; // nothing overdue for this series right now
            }
            RoundWindow target = gameService.firstWindowToResolve(next, now);
            if (!target.equals(next)) {
                if (roundProgress.jumpTo(marketId, interval, next, target)) {
                    LOG.infof("market %s %s: skipped %d empty window(s) overdue by more than one step, without a "
                                    + "result: %s up to %s (exclusive)", marketId, interval,
                            (target.startMs() - next.startMs()) / stepMs, next.roundId(), target.roundId());
                }
                continue;
            }
            if (roundResults.exists(next.roundId())) {
                // Already resolved — e.g. an operator cancelled this exact round ahead of time
                // (GameService#cancelRound never touches RoundProgress itself, see its javadoc).
                roundProgress.advance(marketId, interval, next);
                continue;
            }
            try {
                gameService.resolveRound(next, now);
            } catch (PriceUnavailableException e) {
                // Р-23 fallback: no price at a boundary even after the source's history was asked.
                if (now >= next.endMs() + PRICE_GRACE_MS) {
                    gameService.cancelRound(next, "no oracle price available within 1 hour of round close", now);
                } else {
                    LOG.debugf("round %s has no price yet (%s) — retrying next tick", next.roundId(), e.getMessage());
                    return; // stop catching up on this series this tick; try again next tick
                }
            }
            roundProgress.advance(marketId, interval, next);
        }
    }
}
