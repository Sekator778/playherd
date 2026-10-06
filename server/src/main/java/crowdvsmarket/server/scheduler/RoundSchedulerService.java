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
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.util.FailureLog;
import crowdvsmarket.server.util.LogThrottle;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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
 * <p><b>Opening the rounds (Р-76).</b> Every tick, before anything is settled, each running round of a market that has a
 * backup exchange which has started and has no exchange yet is given one ({@link GameService#openRounds}): the
 * primary if it has a live price at the round's start, else the backup, else the round waits. The settlement
 * of a round then asks that exchange alone for its prices — and if it cannot be priced the round waits and is
 * refunded an hour after its end as any round without a price (below), never moved to the other exchange — unless it
 * is let go sooner (next paragraph).
 *
 * <p><b>Letting a round go (Р-77).</b> The rounds of a series are settled in order, so a round that waits for its
 * exchange holds the whole series, however well the market's other exchange is doing. A round of a market that has a
 * backup exchange, with an exchange decided for it, that cannot be settled for want of a price ({@link
 * PriceUnavailableException}) is therefore cancelled with a refund — reason {@link #VENUE_RELEASE_REASON}, its exchange
 * kept in the result — as soon as {@code herd.prices.venue-release-after-ms} (two minutes; 0 switches the rule off)
 * have passed since its end and the market's <i>other</i> exchange has a live tick fresh now ({@link
 * GameService#otherVenueLive}); the series goes on, and the next round opens on whichever exchange has a live tick at
 * its start. A round whose exchange comes back first is settled as always; with the other exchange silent too nothing
 * changes — the round waits, is healed from the candles for up to 30 minutes and is refunded an hour after its end
 * (Р-23) — and a market without a backup is not touched. The backoff of the failed attempts is cut at the release
 * instant ({@link SettlementBackoff}), so a round is let go when its two minutes are over, not up to a minute later.
 *
 * <p><b>Letting a round go after its minutes, on every market (Р-79).</b> A round that cannot be priced holds its series whatever the
 * market: one without a backup exchange has no other exchange to wait for, and with both exchanges silent Р-77 does not apply. So a
 * round that cannot be settled for want of a price ({@link PriceUnavailableException}) is cancelled with a refund — reason {@link
 * #SOURCE_RELEASE_REASON} — as soon as the minutes of its source ({@link PricesConfig.ReleaseAfter}: five for an exchange and for the
 * synthetic walk, fifteen for Polymarket; 0 switches a source off) have passed since the end of the round <i>or since the start of this
 * server, whichever is later</i>: a restart gives every round that was in flight its minutes to heal from the candles before it is let
 * go. The source is the one of the exchange the round was decided on, else the market's own ({@link GameService#instrumentOf}). Р-77 comes
 * first — a market whose other exchange is alive lets the round go after two minutes — and the hour of Р-23 stays the upper bound: no
 * round waits longer than an hour after its end, and every failure that is not "no price" (the database, the oracle's gate) keeps the
 * hour. The backoff is cut at the earliest of the release instants that are still to come, as for Р-77.
 *
 * <p><b>A round nobody bet on</b> that cannot be priced is not worth a minute of its series' time (Р-79): it is stepped over at once, like
 * an overdue empty window — no result, no event, no oracle record, one log line. The bet of a bot is a bet: only a round with none at all
 * is skipped, and an empty round that can be priced is settled as ever.
 *
 * <p><b>Gates before anything runs.</b> While the engine is not ready ({@code
 * EngineRebuildService#isReady}: the first rebuild has not finished, or a failed settlement's
 * recovery has not succeeded) the tick only tries to bring it back — the rebuild is retried here,
 * once per tick, until it works — and does nothing else: nothing is resolved, no bot bets, no
 * series is seeded on a state that cannot be trusted.
 *
 * <p><b>The oracle's sequence floor (§7 п.4) is asked around the settlement itself, and nowhere else.</b> A
 * settlement reserves a {@code seq}, and a {@code seq} handed out below what is already on chain is a record
 * rejected for good, so while {@link OracleSeqGate#open()} is false no round is settled. Everything else
 * goes on: the catch-up jump over empty windows (no event, no record), the seeding of missing series, the
 * bots, the publishing of what is queued — and the refund of a round that is still unsettled an hour after its
 * end, which writes a result row and an event but no oracle record. (The whole tick used to wait for the gate:
 * after a restart with some downtime no series had a round open for betting until the chain service answered.)
 * The closed gate is said once a minute.
 *
 * <p><b>Catch-up (§4 п.5):</b> a window with bets is always resolved or cancelled, however late; an
 * EMPTY window that is overdue by more than one step is not resolved at all — the series jumps to the
 * window that contains "now", with no event, no result and no oracle record (one log line per jump).
 * <b>No price (Р-23):</b> a failed attempt to settle — {@link PriceUnavailableException}, any other
 * failure of the price source, a price that cannot be settled on, a settlement transaction that
 * failed — means "try again": one hour after the window's end the round is cancelled with a
 * refund (sooner when it is let go, Р-77 and Р-79, above). <b>The retry backs off</b> ({@link
 * SettlementBackoff}): after the k-th failed attempt of a window the next one is made {@code min(60 s, 2^k s)} later,
 * the refund an hour after the end is not delayed by it, and the failure is logged with its stack trace once per
 * window, then one line a minute.
 */
@ApplicationScoped
public class RoundSchedulerService {

    private static final Logger LOG = Logger.getLogger(RoundSchedulerService.class);
    /** Р-23: "если за 1 час цену получить не удалось — раунд отменяется целиком". The upper bound of every wait: Р-77 and Р-79 let a round go sooner. */
    private static final long PRICE_GRACE_MS = 3_600_000L;
    private static final String NO_PRICE_REASON = "no oracle price available within 1 hour of round close";
    private static final String SETTLEMENT_FAILED_REASON = "settlement failed within 1 hour of round close";
    /** Р-77: the reason of a round let go from an exchange that stopped sending prices; players read it as it is. */
    static final String VENUE_RELEASE_REASON = "The exchange of this round stopped sending prices; stakes returned";
    /** Р-79: the reason of a round let go after the minutes of its source, with no other exchange to blame; players read it as it is. */
    static final String SOURCE_RELEASE_REASON = "No price from the source; stakes returned";

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
    @Inject
    PricesConfig pricesConfig;

    /** One line a minute per condition: a closed oracle gate. */
    private final LogThrottle logThrottle = new LogThrottle(60_000L);
    /** What failed windows have in common: how often they were tried and when they may be tried again. */
    private final SettlementBackoff backoff = new SettlementBackoff();
    /** A window that cannot be settled: its stack trace once, then a line a minute. */
    private final FailureLog settlementLog = new FailureLog(60_000L);
    /** A series whose pass fails with something other than a failed attempt (the database does not answer). */
    private final FailureLog passLog = new FailureLog(60_000L);
    /** One line a minute per series: the question whether the other exchange is alive could not be answered. */
    private final LogThrottle venueLog = new LogThrottle(60_000L);
    /**
     * When this server started — what a round that cannot be priced is given its minutes from, if that is later than its end (Р-79).
     * 0, the beginning of time, until it is known: the minutes then count from the end of the round alone.
     */
    volatile long startedAtMs;

    /** The server has started (a restart included): what waits for a price is given its minutes from now at the earliest (Р-79). */
    void onStart(@Observes StartupEvent event) {
        startedAtMs = System.currentTimeMillis();
    }

    /**
     * For tests that drive their own clock: pretends that the server started at {@code atMs}, as after a restart — the rounds that cannot be
     * priced are given their minutes (Р-79) from then at the earliest.
     */
    public void startedAt(long atMs) {
        startedAtMs = atMs;
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        tick(System.currentTimeMillis());
    }

    /** One scheduler pass as of {@code now} — the {@link #tick()} body, with the clock as an
     *  argument so tests can drive time. */
    public void tick(long now) {
        if (!rebuildService.isReady() && !rebuildService.retryPendingRebuild(now)) {
            // Startup race (bug fix, see RoundProgress#isSeeded): this @Scheduled tick can be armed
            // and fire on its own executor thread before EngineRebuildService's StartupEvent-observer
            // rebuild() (main thread) has finished — and after a failed recovery the memory may be
            // ahead of the database. Skip the tick entirely rather than let bots/round-resolution/
            // oracle-publish below work on an empty or untrustworthy engine — the very next tick (1s
            // later) finds it ready and resumes normally, every subsystem included.
            return;
        }
        if (!oracleSeqGate.open() && logThrottle.allow("oracle-seq-gate", now)) {
            LOG.warn("the oracle's sequence floor has not been obtained yet — no round is settled until it has "
                    + "(herd.oracle.require-seq-floor); a round still unsettled an hour after its end is refunded "
                    + "all the same");
        }
        List<Market> markets = gameService.runningMarkets();
        for (Market market : markets) {
            seedMissingSeries(market, now);
        }
        gameService.openRounds(now); // the exchange each round that has started runs on (Р-76); never throws
        try {
            botService.tick(now);
        } catch (Exception e) {
            LOG.error("bot tick failed", e);
        }
        for (Market market : markets) {
            for (Interval interval : Interval.values()) {
                String passKey = market.id() + "/" + interval;
                try {
                    resolveOverdueRounds(market, interval, now);
                    passLog.clear(passKey);
                } catch (ApiException e) {
                    if (e.code() != Enums.ApiErrorCode.ENGINE_NOT_READY) {
                        logFailedPass(passKey, e, now);
                    }
                    // else: the engine failed under this very tick; the top of the next one deals with it
                } catch (Exception e) {
                    logFailedPass(passKey, e, now);
                }
            }
        }
        try {
            oraclePublisher.publishPending();
        } catch (Exception e) {
            LOG.error("oracle publish tick failed", e);
        }
    }

    /**
     * Forgets the failed attempts it remembers ({@link SettlementBackoff}): every overdue window is tried again at
     * the next tick. For tests that drive their own clock — they share one scheduler, and an attempt that failed at
     * the instant of one test must not hold a window back in another.
     */
    public void forgetFailedAttempts() {
        backoff.clear();
    }

    private void logFailedPass(String series, Exception e, long now) {
        switch (passLog.next(series, now)) {
            case STACK_TRACE -> LOG.errorf(e, "round resolution failed for %s — will retry next tick", series);
            case LINE -> LOG.errorf("round resolution still fails for %s — will retry next tick: %s", series, e);
            case NOTHING -> {
                // said this minute
            }
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
        RoundProgress.SeriesKey series = new RoundProgress.SeriesKey(marketId, interval);
        long stepMs = config.roundDurations().forInterval(interval);
        while (true) {
            Optional<RoundWindow> cursor = roundProgress.findNext(marketId, interval);
            if (cursor.isEmpty()) {
                backoff.forget(series);
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
            boolean refundDue = now >= next.endMs() + PRICE_GRACE_MS;
            if (backoff.waiting(series, next, now, refundDue)) {
                return; // an attempt failed lately: not yet (SettlementBackoff)
            }
            if (!oracleSeqGate.open()) {
                // The gate is asked here and only here: a settlement reserves a seq, a refund does not.
                if (!refundDue || !refund(series, next, SETTLEMENT_FAILED_REASON, now, true)) {
                    return;
                }
                roundProgress.advance(marketId, interval, next);
                continue;
            }
            try {
                gameService.resolveRound(next, now);
                backoff.forget(series);
                settlementLog.clear(next.roundId());
            } catch (ApiException e) {
                if (e.code() == Enums.ApiErrorCode.ENGINE_NOT_READY) {
                    throw e; // not a failed attempt of this round: the engine is being recovered
                }
                if (!failedAttempt(series, market, next, e, now)) {
                    return;
                }
            } catch (RuntimeException e) {
                if (!failedAttempt(series, market, next, e, now)) {
                    return;
                }
            }
            roundProgress.advance(marketId, interval, next);
        }
    }

    /**
     * One failed attempt to settle {@code window} — no price, a price the source gave that cannot be
     * settled on, any other failure of the source, a settlement transaction that failed (and had the
     * engine rebuilt behind it, when it had touched it). The round is tried again later ({@link
     * SettlementBackoff}), until an hour after its end (Р-23): then it is cancelled, with a refund. A round that has no
     * price is let go sooner (the class comment), and only a failure to find a price counts for that, not any other failure:
     * <ul>
     *   <li>a round nobody bet on is skipped at once (Р-79);
     *   <li>a round of a market with a backup exchange is cancelled with a refund as soon as {@code
     *       herd.prices.venue-release-after-ms} have passed since its end and the market's other exchange is alive (Р-77);
     *   <li>any other round, as soon as the minutes of its source have passed since its end or since the start of the server
     *       (Р-79).
     * </ul>
     *
     * @return {@code true} when the round has been cancelled or skipped (the series moves on), {@code false}
     *         when it will be tried again (stop catching up on this series this tick)
     */
    private boolean failedAttempt(RoundProgress.SeriesKey series, Market market, RoundWindow window,
                                  RuntimeException failure, long now) {
        boolean noPrice = failure instanceof PriceUnavailableException;
        if (noPrice && !gameService.hasPendingBets(window)) {
            return skipEmpty(series, window, failure);
        }
        if (now >= window.endMs() + PRICE_GRACE_MS) {
            LOG.warnf("round %s could not be settled within 1 hour of its end (%s) — cancelling it with a refund",
                    window.roundId(), failure.toString());
            return refund(series, window, noPrice ? NO_PRICE_REASON : SETTLEMENT_FAILED_REASON, now, true);
        }
        long venueReleaseAtMs = noPrice ? venueReleaseAt(market, window) : Long.MAX_VALUE;
        long sourceReleaseAtMs = noPrice ? sourceReleaseAt(market, window) : Long.MAX_VALUE;
        if (now >= venueReleaseAtMs && otherVenueLive(market, window, now)) {
            LOG.warnf("round %s has no price from its exchange %d s after its end (%s) and the other exchange of %s is "
                    + "alive — cancelling it with a refund so that the series goes on", window.roundId(),
                    (now - window.endMs()) / 1000L, failure.getMessage(), market.id());
            return refund(series, window, VENUE_RELEASE_REASON, now, false);
        }
        if (now >= sourceReleaseAtMs) {
            LOG.warnf("round %s has no price from its source %d s after its end (%s) — cancelling it with a refund so "
                    + "that the series of %s goes on", window.roundId(), (now - window.endMs()) / 1000L,
                    failure.getMessage(), market.id());
            return refund(series, window, SOURCE_RELEASE_REASON, now, false);
        }
        // the next attempt is made at the earliest release instant still to come at the latest, so that the round is let go when its time is up
        SettlementBackoff.State state = backoff.failed(series, window, now, false,
                firstAfter(now, venueReleaseAtMs, sourceReleaseAtMs));
        long delaySec = (state.nextAttemptAtMs() - now) / 1000L;
        if (noPrice) {
            LOG.debugf("round %s has no price yet (%s) — attempt %d failed, retrying in %d s", window.roundId(),
                    failure.getMessage(), state.failures(), delaySec);
            return false;
        }
        switch (settlementLog.next(window.roundId(), now)) {
            case STACK_TRACE -> LOG.warnf(failure, "round %s could not be settled (attempt %d) — retrying in %d s, "
                    + "it is cancelled with a refund an hour after its end", window.roundId(), state.failures(),
                    delaySec);
            case LINE -> LOG.warnf("round %s still could not be settled (attempt %d, %s) — retrying in %d s",
                    window.roundId(), state.failures(), failure.toString(), delaySec);
            case NOTHING -> {
                // said this minute
            }
        }
        return false;
    }

    /**
     * A round nobody bet on whose price cannot be had (Р-79): not worth a minute of its series' time, it is stepped over like an
     * overdue empty window ({@code GameService#firstWindowToResolve}) — no result, no event, no oracle record — and the caller
     * moves the cursor on.
     */
    private boolean skipEmpty(RoundProgress.SeriesKey series, RoundWindow window, RuntimeException failure) {
        LOG.infof("market %s %s: skipped the empty window %s, which has no price (%s), without a result",
                series.marketId(), series.interval(), window.roundId(), failure.getMessage());
        backoff.forget(series);
        settlementLog.clear(window.roundId());
        return true;
    }

    /**
     * The instant from which the round of {@code market} that cannot be priced is let go, if the market's other exchange
     * is alive: its end plus {@code herd.prices.venue-release-after-ms}. {@link Long#MAX_VALUE} — never — for a market
     * without a backup exchange and while the rule is switched off (0).
     */
    private long venueReleaseAt(Market market, RoundWindow window) {
        long afterMs = pricesConfig.venueReleaseAfterMs();
        if (afterMs <= 0 || !Instruments.hasBackup(market)) {
            return Long.MAX_VALUE;
        }
        return plus(window.endMs(), afterMs);
    }

    /**
     * The instant from which the round of {@code market} that cannot be priced is let go whatever the other exchange does (Р-79):
     * the minutes of the source that prices it ({@link PricesConfig#releaseAfterMs}), counted from the later of the end of the
     * round and the start of the server. {@link Long#MAX_VALUE} — never — while the rule is switched off (0) for that source.
     */
    private long sourceReleaseAt(Market market, RoundWindow window) {
        long afterMs = pricesConfig.releaseAfterMs(gameService.instrumentOf(market, window));
        if (afterMs <= 0) {
            return Long.MAX_VALUE;
        }
        return plus(Math.max(window.endMs(), startedAtMs), afterMs);
    }

    /** {@code atMs + afterMs}; {@link Long#MAX_VALUE} — never — when that does not fit. */
    private static long plus(long atMs, long afterMs) {
        return afterMs > Long.MAX_VALUE - atMs ? Long.MAX_VALUE : atMs + afterMs;
    }

    /** The earliest of {@code instants} that is after {@code now}; {@link Long#MAX_VALUE} when every one of them has come. */
    private static long firstAfter(long now, long... instants) {
        long first = Long.MAX_VALUE;
        for (long at : instants) {
            if (at > now && at < first) {
                first = at;
            }
        }
        return first;
    }

    /**
     * {@link GameService#otherVenueLive} for a pass that must not fail on it: the table of the round's exchange not
     * answering, say, is "not now" — the next attempt asks again, and the hour of Р-23 does not depend on any of it.
     */
    private boolean otherVenueLive(Market market, RoundWindow window, long now) {
        try {
            return gameService.otherVenueLive(market, window, now);
        } catch (RuntimeException e) {
            if (venueLog.allow(window.market() + "/" + window.interval(), now)) {
                LOG.warnf("could not tell whether the other exchange of %s is alive for %s — the round is not let go "
                        + "now: %s", market.id(), window.roundId(), e.toString());
            }
            return false;
        }
    }

    /**
     * Cancels {@code window} with a refund: an hour after its end (Р-23), or earlier, when the round is let go from an
     * exchange that stopped sending prices (Р-77) or from a source that gives no price (Р-79). A refund that fails is an
     * attempt that failed like any other (backed off, logged once per window with its trace, then a line a minute), not an
     * exception out of the pass.
     *
     * @param hourRefund whether this is the refund of the hour: only that one, when it fails, counts as "the refund has
     *                   been tried" ({@link SettlementBackoff}) — the backoff never delays its first attempt, whatever a
     *                   release that failed before it did
     * @return whether the round was cancelled
     */
    private boolean refund(RoundProgress.SeriesKey series, RoundWindow window, String reason, long now,
                           boolean hourRefund) {
        try {
            gameService.cancelRound(window, reason, now);
            backoff.forget(series);
            settlementLog.clear(window.roundId());
            return true;
        } catch (ApiException e) {
            if (e.code() == Enums.ApiErrorCode.ENGINE_NOT_READY) {
                throw e;
            }
            refundFailed(series, window, e, now, hourRefund);
            return false;
        } catch (RuntimeException e) {
            refundFailed(series, window, e, now, hourRefund);
            return false;
        }
    }

    private void refundFailed(RoundProgress.SeriesKey series, RoundWindow window, RuntimeException failure, long now,
                              boolean hourRefund) {
        SettlementBackoff.State state = backoff.failed(series, window, now, hourRefund);
        long delaySec = (state.nextAttemptAtMs() - now) / 1000L;
        switch (settlementLog.next(window.roundId(), now)) {
            case STACK_TRACE -> LOG.errorf(failure, "round %s could not be cancelled and refunded (attempt %d) — "
                    + "retrying in %d s", window.roundId(), state.failures(), delaySec);
            case LINE -> LOG.errorf("round %s still could not be cancelled and refunded (attempt %d, %s) — retrying "
                    + "in %d s", window.roundId(), state.failures(), failure.toString(), delaySec);
            case NOTHING -> {
                // said this minute
            }
        }
    }
}
