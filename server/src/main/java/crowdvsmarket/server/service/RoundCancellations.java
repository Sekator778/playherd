package crowdvsmarket.server.service;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.RoundVenues;
import crowdvsmarket.server.util.ResultsHasher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cancels one round with a refund of every pending bet, in ONE database transaction like the
 * settlement (docs/research/markets_architecture.md §4 п.3): the {@code RoundCancelled} event, its
 * application to the engine (every stake refunded, the round's bet book entry cleared) and the
 * cancelled result row. {@link #cancel} never touches {@code RoundProgress} — the scheduler steps over
 * the round when it sees the row; {@link #closeSeries} stops a closed market's series.
 *
 * <p>It works on the state it is given, not on the live one: {@code GameService} cancels a round of
 * the live engine, a rebuild cancels the rounds of closed markets in the state it is still building
 * aside. It does not recover from a failure either — the transaction rolls back, but the event has
 * been applied to whatever state it was given by then — which is for the caller to deal with: the live
 * engine is rebuilt from the log, a state built aside is simply thrown away.
 */
@ApplicationScoped
public class RoundCancellations {

    @Inject
    Db db;
    @Inject
    EventStore eventStore;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    RoundProgress roundProgress;
    @Inject
    SeriesSeeder seeder;
    @Inject
    RoundVenues venues;

    /**
     * A market closed: cancels, with a refund, the running round and the round open for betting of each
     * of its series that have no result yet — even an empty one, so that a round a player still has open
     * in the app resolves to a cancelled round, not to "unknown" — and EVERY other window of the series that
     * holds a bet and has no result (the hub's index of the windows with bets, the same list a rebuild
     * refunds a market closed in the database by: the cursor and the window after it are where bets are in a
     * game that runs forward, but a stake must never depend on that — a bet left in a window the cursor
     * does not point at is debited, never settled, never refunded), earliest first. Then stops the series.
     * The caller holds the engine lock and recovers from a failure ({@link #cancel}).
     */
    public void closeSeries(EventApplier applier, EngineHub hub, Market market, String reason, long nowMs) {
        for (Interval interval : Interval.values()) {
            TreeMap<Long, RoundWindow> windows = new TreeMap<>();
            Optional<RoundWindow> running = roundProgress.findNext(market.id(), interval);
            if (running.isPresent()) {
                for (RoundWindow window : List.of(running.get(), RoundClock.next(running.get()))) {
                    windows.put(window.startMs(), window);
                }
            }
            for (RoundWindow window : seeder.pending(hub, market, interval)) {
                windows.put(window.startMs(), window);
            }
            for (RoundWindow window : windows.values()) {
                if (!hub.closedRounds().isClosed(window)) {
                    cancel(applier, hub, market, window, reason, nowMs);
                }
            }
            roundProgress.remove(market.id(), interval);
        }
    }

    /** {@link #cancel(EventApplier, EngineHub, Market, RoundWindow, String, long, AtomicBoolean)} for a caller
     *  that recovers from any failure the same way. */
    public Rounds.CancellationDto cancel(EventApplier applier, EngineHub hub, Market market, RoundWindow window,
                                         String reason, long nowMs) {
        return cancel(applier, hub, market, window, reason, nowMs, new AtomicBoolean());
    }

    /**
     * @param applier      the applier of the state the cancellation is for ({@code EventApplier.over} for
     *                     one built aside)
     * @param hub          that state's hub, to count the bets refunded
     * @param touchedState set to {@code true} the moment the event has been written and is about to be applied
     *                     to the state: a failure that comes while it is still {@code false} cannot have
     *                     changed that state (the event could not be written, no connection could be had),
     *                     so there is nothing to rebuild; one that comes after it may have
     */
    public Rounds.CancellationDto cancel(EventApplier applier, EngineHub hub, Market market, RoundWindow window,
                                         String reason, long nowMs, AtomicBoolean touchedState) {
        int refundedBetCount = hub.engine(market.id(), window.interval()).betBook().count(window.roundId());
        GameEvent.RoundCancelled event = new GameEvent.RoundCancelled(window.interval(), window.startMs(),
                window.endMs(), reason, nowMs, market.id());
        // the exchange the round had been decided to run on, if it had (Р-76): the cancelled round says so too
        String venue = Instruments.hasBackup(market) ? venues.venueOfRound(window.roundId()).orElse(null) : null;
        var canonical = ResultsHasher.build(market.id(), window.roundId(), window.interval().name(), "CANCELLED",
                null, null, List.of(), null, venue);
        db.tx(c -> {
            eventStore.append(c, event, nowMs);
            touchedState.set(true);
            applier.apply(event); // refunds every pending bet and clears the round's BetBook entry
            roundResults.insertCancelled(c, market.id(), window.roundId(), window.interval(), window.startMs(),
                    window.endMs(), reason, nowMs, refundedBetCount, canonical.hash(), canonical.json(), venue);
            return null;
        });
        return new Rounds.CancellationDto(window.roundId(), market.id(), market.symbol(), reason, nowMs,
                refundedBetCount);
    }
}
