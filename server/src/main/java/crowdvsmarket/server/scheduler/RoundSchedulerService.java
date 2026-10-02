package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.util.LogThrottle;
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
 * <p><b>Gates before anything runs.</b> While the engine is not ready ({@code
 * EngineRebuildService#isReady}: the first rebuild has not finished, or a failed settlement's
 * recovery has not succeeded) the tick only tries to bring it back — the rebuild is retried here,
 * once per tick, until it works — and does nothing else: nothing is resolved, no bot bets, no
 * series is seeded on a state that cannot be trusted. While the oracle's sequence floor has not been
 * obtained ({@link OracleSeqGate#open()} is false) it does nothing at all, and says so once a
 * minute: a settlement or a cancellation reserves a {@code seq}, and a {@code seq} handed out below
 * what is already on chain is a record rejected for good.
 *
 * <p><b>Catch-up (§4 п.5):</b> a window with bets is always resolved or cancelled, however late; an
 * EMPTY window that is overdue by more than one step is not resolved at all — the series jumps to the
 * window that contains "now", with no event, no result and no oracle record (one log line per jump).
 * <b>No price (Р-23):</b> a failed attempt to settle — {@link PriceUnavailableException}, any other
 * failure of the price source, a price that cannot be settled on, a settlement transaction that
 * failed — means "try again next tick"; one hour after the window's end the round is cancelled with a
 * refund. (Only {@link PriceUnavailableException} used to count: any other exception was retried
 * every second for ever, and the bets of that window were never refunded.)
 */
@ApplicationScoped
public class RoundSchedulerService {

    private static final Logger LOG = Logger.getLogger(RoundSchedulerService.class);
    /** Р-23: "если за 1 час цену получить не удалось — раунд отменяется целиком". */
    private static final long PRICE_GRACE_MS = 3_600_000L;
    private static final String NO_PRICE_REASON = "no oracle price available within 1 hour of round close";
    private static final String SETTLEMENT_FAILED_REASON = "settlement failed within 1 hour of round close";

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
    OracleSeqGate oracleSeqGate;
    @Inject
    GameConfig config;

    /** One line a minute per condition: a closed gate, a series whose settlements keep failing. */
    private final LogThrottle logThrottle = new LogThrottle(60_000L);

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        tick(System.currentTimeMillis());
    }

    /** One scheduler pass as of {@code now} — the {@link #tick()} body, with the clock as an
     *  argument so tests can drive time. */
    public void tick(long now) {
        if (!rebuildService.isReady() && !rebuildService.retryPendingRebuild()) {
            // Startup race (bug fix, see RoundProgress#isSeeded): this @Scheduled tick can be armed
            // and fire on its own executor thread before EngineRebuildService's StartupEvent-observer
            // rebuild() (main thread) has finished — and after a failed recovery the memory may be
            // ahead of the database. Skip the tick entirely rather than let bots/round-resolution/
            // oracle-publish below work on an empty or untrustworthy engine — the very next tick (1s
            // later) finds it ready and resumes normally, every subsystem included.
            return;
        }
        if (!oracleSeqGate.open()) {
            if (logThrottle.allow("oracle-seq-gate", now)) {
                LOG.warn("the oracle's sequence floor has not been obtained yet — no round is settled or "
                        + "cancelled until it has (herd.oracle.require-seq-floor)");
            }
            return;
        }
        List<Market> markets = gameService.runningMarkets();
        for (Market market : markets) {
            seedMissingSeries(market, now);
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
                } catch (ApiException e) {
                    if (e.code() != Enums.ApiErrorCode.ENGINE_NOT_READY) {
                        LOG.errorf(e, "round resolution failed for %s/%s — will retry next tick", market.id(), interval);
                    }
                    // else: the engine failed under this very tick; the top of the next one deals with it
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
    private void seedMissingSeries(Market market, long now) {
        boolean missing = false;
        for (Interval interval : Interval.values()) {
            missing |= !roundProgress.isSeeded(market.id(), interval);
        }
        if (!missing) {
            return;
        }
        try {
            gameService.ensureSeries(market, now);
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
            } catch (ApiException e) {
                if (e.code() == Enums.ApiErrorCode.ENGINE_NOT_READY) {
                    throw e; // not a failed attempt of this round: the engine is being recovered
                }
                if (!failedAttempt(next, e, now)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (!failedAttempt(next, e, now)) {
                    return;
                }
            }
            roundProgress.advance(marketId, interval, next);
        }
    }

    /**
     * One failed attempt to settle {@code window} — no price, a price the source gave that cannot be
     * settled on, any other failure of the source, a settlement transaction that failed (and had the
     * engine rebuilt behind it). The round is retried on the next tick, until an hour after its end
     * (Р-23): then it is cancelled, with a refund.
     *
     * @return {@code true} when the round has been cancelled (the series moves on), {@code false}
     *         when it will be tried again (stop catching up on this series this tick)
     */
    private boolean failedAttempt(RoundWindow window, RuntimeException failure, long now) {
        boolean noPrice = failure instanceof PriceUnavailableException;
        if (now >= window.endMs() + PRICE_GRACE_MS) {
            LOG.warnf("round %s could not be settled within 1 hour of its end (%s) — cancelling it with a refund",
                    window.roundId(), failure.toString());
            gameService.cancelRound(window, noPrice ? NO_PRICE_REASON : SETTLEMENT_FAILED_REASON, now);
            return true;
        }
        if (noPrice) {
            LOG.debugf("round %s has no price yet (%s) — retrying next tick", window.roundId(), failure.getMessage());
        } else if (logThrottle.allow("settlement-" + window.roundId(), now)) {
            LOG.warnf(failure, "round %s could not be settled — retrying next tick, it is cancelled with a "
                    + "refund an hour after its end", window.roundId());
        }
        return false;
    }
}
