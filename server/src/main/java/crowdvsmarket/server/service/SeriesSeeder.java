package crowdvsmarket.server.service;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Where a round series starts, and which windows still hold bets nobody has settled — the one place
 * that decides it, for a rebuild ({@code EngineStateBuilder}, over the hub it has just replayed the
 * event log into) and for a market that gets its series while the game runs ({@code
 * GameService#ensureSeries}).
 *
 * <p><b>Rule (docs/research/markets_architecture.md §4 п.2, п.5; the money rule behind it: a stake is
 * never left behind a cursor).</b> A series is seeded where its last result row says the next window
 * to resolve starts, or — a market without a row — at the window that contains "now"; and then never
 * later than the earliest window that still holds a pending bet and has no result row. The scheduler
 * walks forward from the cursor, so a window behind it would be neither resolved nor refunded, ever:
 * the stake stays debited, is not even counted as "in play", and every later restart seeds the same
 * way. After a stop across the end of a betting window (a market listed, a bet on its second
 * window, the process down until the window is over) "the window containing now" is behind exactly
 * such a bet.
 *
 * <p>"Holds a pending bet" is read off the engine's {@code BetBook} — the truth the replay rebuilt.
 * The book can only be asked per round id, so the windows to ask about come from the caller: a
 * rebuild knows exactly which windows ever got a bet (it has just read every {@code BetPlaced}); a
 * market that is given its series at runtime has no such list, and every window from its listing (no
 * bet can be older) to the one open for betting at "now" (none can be newer — the scheduler never lets
 * a cursor pass the window containing "now") is asked about, which for a market listed just now is
 * two.
 */
@ApplicationScoped
public class SeriesSeeder {

    @Inject
    RoundCalendar calendar;
    @Inject
    GameConfig config;
    @Inject
    RoundResultRepository roundResults;

    /**
     * The window {@code market}'s {@code interval} series starts at as of {@code nowMs}, given the
     * bets {@code hub} holds — see the class javadoc. For a series that has to be given its cursor at
     * runtime: the windows to look at are those since the market was listed.
     */
    public RoundWindow seedFor(EngineHub hub, Market market, Interval interval, long nowMs) {
        return seedFor(hub, market, interval, nowMs, windowsSinceListing(market, interval, nowMs));
    }

    /**
     * {@link #seedFor(EngineHub, Market, Interval, long)} with the windows to look at given: {@code
     * betWindows} are all the windows of the series that may hold a bet — at least every window that
     * ever got one and was not resolved or cancelled since.
     */
    public RoundWindow seedFor(EngineHub hub, Market market, Interval interval, long nowMs,
                               Iterable<RoundWindow> betWindows) {
        Optional<Long> latestEnd = roundResults.latestWindowEnd(market.id(), interval);
        RoundWindow base;
        if (latestEnd.isEmpty()) {
            base = calendar.windowContaining(market.id(), interval, nowMs);
        } else {
            long stepMs = config.roundDurations().forInterval(interval);
            base = new RoundWindow(market.id(), interval, latestEnd.get(), latestEnd.get() + stepMs);
        }
        List<RoundWindow> pending = pending(hub, market, interval, betWindows);
        return !pending.isEmpty() && pending.get(0).startMs() < base.startMs() ? pending.get(0) : base;
    }

    /** Of {@code betWindows}, those that hold a bet in {@code hub}'s engine for the market and
     *  interval and have no result row, earliest first — what is still waiting to be resolved or
     *  refunded. */
    public List<RoundWindow> pending(EngineHub hub, Market market, Interval interval,
                                     Iterable<RoundWindow> betWindows) {
        Optional<EngineHub.MarketEngines> engines = hub.findMarket(market.id());
        if (engines.isEmpty()) {
            return List.of();
        }
        BetBook book = engines.get().engine(interval).betBook();
        List<RoundWindow> pending = new ArrayList<>();
        for (RoundWindow window : betWindows) {
            if (!book.betsFor(window.roundId()).isEmpty() && !roundResults.exists(window.roundId())) {
                pending.add(window);
            }
        }
        pending.sort(Comparator.comparingLong(RoundWindow::startMs));
        return pending;
    }

    /** Every window of the series from the one containing the market's listing (a bet cannot be
     *  older) up to the one open for betting at {@code nowMs}; walked lazily — a series as old as BTC's
     *  has a great many. */
    private Iterable<RoundWindow> windowsSinceListing(Market market, Interval interval, long nowMs) {
        RoundWindow first = calendar.windowContaining(market.id(), interval,
                Math.max(market.listedAtMs(), calendar.originMs()));
        long lastStartMs = RoundClock.next(calendar.windowContaining(market.id(), interval, nowMs)).startMs();
        return () -> Stream.iterate(first, window -> window.startMs() <= lastStartMs, RoundClock::next).iterator();
    }
}
