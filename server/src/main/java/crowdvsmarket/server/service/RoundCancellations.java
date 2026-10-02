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
import crowdvsmarket.server.util.ResultsHasher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

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

    /**
     * A market closed: cancels, with a refund, the running round and the round open for betting of each
     * of its series that have no result yet — even an empty one, so that a round a player still has open
     * in the app resolves to a cancelled round, not to "unknown" — and stops the series. The caller
     * holds the engine lock and recovers from a failure ({@link #cancel}).
     */
    public void closeSeries(EventApplier applier, EngineHub hub, Market market, String reason, long nowMs) {
        for (Interval interval : Interval.values()) {
            Optional<RoundWindow> running = roundProgress.findNext(market.id(), interval);
            if (running.isPresent()) {
                for (RoundWindow window : List.of(running.get(), RoundClock.next(running.get()))) {
                    if (!roundResults.exists(window.roundId())) {
                        cancel(applier, hub, market, window, reason, nowMs);
                    }
                }
            }
            roundProgress.remove(market.id(), interval);
        }
    }

    /**
     * @param applier the applier of the state the cancellation is for ({@code EventApplier.over} for
     *                one built aside)
     * @param hub     that state's hub, to count the bets refunded
     */
    public Rounds.CancellationDto cancel(EventApplier applier, EngineHub hub, Market market, RoundWindow window,
                                         String reason, long nowMs) {
        int refundedBetCount = hub.engine(market.id(), window.interval()).betBook().betsFor(window.roundId()).size();
        GameEvent.RoundCancelled event = new GameEvent.RoundCancelled(window.interval(), window.startMs(),
                window.endMs(), reason, nowMs, market.id());
        var canonical = ResultsHasher.build(market.id(), window.roundId(), window.interval().name(), "CANCELLED",
                null, null, List.of(), null);
        db.tx(c -> {
            eventStore.append(c, event, nowMs);
            applier.apply(event); // refunds every pending bet and clears the round's BetBook entry
            roundResults.insertCancelled(c, market.id(), window.roundId(), window.interval(), window.startMs(),
                    window.endMs(), reason, nowMs, refundedBetCount, canonical.hash(), canonical.json());
            return null;
        });
        return new Rounds.CancellationDto(window.roundId(), reason, nowMs, refundedBetCount);
    }
}
