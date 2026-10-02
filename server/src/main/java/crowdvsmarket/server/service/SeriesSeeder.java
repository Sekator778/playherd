package crowdvsmarket.server.service;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where a round series starts, and which windows still hold bets nobody has settled — the one place
 * that decides it, for a rebuild ({@code EngineStateBuilder}, over the hub it has just replayed the
 * event log into) and for a market that gets its series while the game runs ({@code
 * GameService#ensureSeries}).
 *
 * <p><b>Rule (docs/research/markets_architecture.md §4 п.2, п.5, п.12; the money rule behind it: a stake is
 * never left behind a cursor).</b> A series is seeded where its last result row says the next window to
 * resolve starts — but never later than the window that contains "now": a cursor ahead of the clock would
 * show a running round that has not started and open betting two round lengths early (an operator cancels
 * the betting round, the process restarts inside the running window, and the last row is the cancelled
 * window after it). A market without a row starts at the window that contains "now". And then never later
 * than the earliest window that still holds a pending bet and has no result row. The scheduler walks
 * forward from the cursor, so a window behind it would be neither resolved nor refunded, ever: the stake
 * stays debited, is not even counted as "in play", and every later restart seeds the same way. After a stop
 * across the end of a betting window (a market listed, a bet on its second window, the process down until
 * the window is over) "the window containing now" is behind exactly such a bet.
 *
 * <p>"Holds a pending bet" is read off the engine's {@code BetBook} — the truth the replay rebuilt. The book
 * can only be asked per round id, so the windows to ask about come from the hub's index of the windows that
 * ever got a bet and were not resolved or cancelled since ({@link EngineHub#betWindows()}, kept by {@code
 * EventApplier} — in a replay as well as live).
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
     * The window {@code market}'s {@code interval} series starts at as of {@code nowMs}, given the bets
     * {@code hub} holds — see the class javadoc.
     */
    public RoundWindow seedFor(EngineHub hub, Market market, Interval interval, long nowMs) {
        RoundWindow base = calendar.windowContaining(market.id(), interval, nowMs);
        Optional<Long> latestEnd = roundResults.latestWindowEnd(market.id(), interval);
        if (latestEnd.isPresent() && latestEnd.get() < base.startMs()) {
            long stepMs = config.roundDurations().forInterval(interval);
            base = new RoundWindow(market.id(), interval, latestEnd.get(), latestEnd.get() + stepMs);
        }
        List<RoundWindow> pending = pending(hub, market, interval);
        return !pending.isEmpty() && pending.get(0).startMs() < base.startMs() ? pending.get(0) : base;
    }

    /** The windows of {@code hub}'s series that hold a bet in its engine and have no result row, earliest
     *  first — what is still waiting to be resolved or refunded. A window of the index that holds no bet any more
     *  (its only bet was cancelled by the player, and the empty round was stepped over without an event) is dropped
     *  from the index here: it would otherwise stay in it for as long as the process lives. The caller holds the
     *  engine's write lock, or owns the hub (a state built aside). */
    public List<RoundWindow> pending(EngineHub hub, Market market, Interval interval) {
        Optional<EngineHub.MarketEngines> engines = hub.findMarket(market.id());
        if (engines.isEmpty()) {
            return List.of();
        }
        BetBook book = engines.get().engine(interval).betBook();
        List<RoundWindow> pending = new ArrayList<>();
        for (RoundWindow window : hub.betWindows().of(market.id(), interval)) {
            if (book.betsFor(window.roundId()).isEmpty()) {
                hub.betWindows().closed(window);
            } else if (!roundResults.exists(window.roundId())) {
                pending.add(window);
            }
        }
        return pending;
    }
}
