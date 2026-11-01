package crowdvsmarket.server.service;

import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Enums;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every bet of one player that has not settled yet — the ONE walk over the running series that both
 * {@code myPositions} (the player's open bets and positions, D-71) and {@code PlayerAccountDto.inPlay} (the
 * stake sitting in them) are made of, so that the second is the sum of the first by construction.
 *
 * <p>A series (market + interval) has at most two rounds with bets in the engine (docs/research/
 * markets_architecture.md T-5: one position per player per round of a market, D-19: one running and one open
 * for betting): the round {@link RoundProgress} points at is {@code RUNNING} — its bets stay in the {@code
 * BetBook} until the settlement clears them, there is no stored "running" state in the engine — and the one
 * after it is {@code BETTING}. A series that is not seeded (a closed market, replay mode's other markets) has
 * none: its bets were refunded when it stopped.
 *
 * <p>{@link #of(String)} and {@link #stake(String)} read the live engine, for a caller on the core writer; the reads of the
 * API use {@link #of(ReadViews.Snapshot, String)}, which walks the committed views and takes no lock.
 */
@ApplicationScoped
public class OpenBets {

    /** One pending bet: {@code window} is the round it is on, {@code phase} says whether that round is open for
     *  betting or already running. */
    public record Entry(String marketId, Interval interval, RoundWindow window, Enums.RoundPhase phase, Bet bet) {
    }

    private final EngineHub hub;
    private final RoundProgress roundProgress;

    @Inject
    public OpenBets(EngineHub hub, RoundProgress roundProgress) {
        this.hub = hub;
        this.roundProgress = roundProgress;
    }

    /** The player's pending bets across every series, in no particular order. */
    public List<Entry> of(String playerId) {
        List<Entry> out = new ArrayList<>();
        for (RoundProgress.SeriesKey series : roundProgress.seededSeries()) {
            Optional<EngineHub.MarketEngines> market = hub.findMarket(series.marketId());
            Optional<RoundWindow> running = roundProgress.findNext(series.marketId(), series.interval());
            if (market.isEmpty() || running.isEmpty()) {
                continue; // closed or re-seeded since seededSeries() looked
            }
            GameEngine engine = market.get().engine(series.interval());
            betOf(engine, running.get().roundId(), playerId).ifPresent(bet -> out.add(new Entry(series.marketId(),
                    series.interval(), running.get(), Enums.RoundPhase.RUNNING, bet)));
            RoundWindow betting = RoundClock.next(running.get());
            betOf(engine, betting.roundId(), playerId).ifPresent(bet -> out.add(new Entry(series.marketId(),
                    series.interval(), betting, Enums.RoundPhase.BETTING, bet)));
        }
        return out;
    }

    /** The sum of the stakes of {@link #of}: money already debited from the balance, not yet won or lost. */
    public PlayAmount stake(String playerId) {
        PlayAmount total = PlayAmount.ZERO;
        for (Entry entry : of(playerId)) {
            total = total.plus(entry.bet().stake());
        }
        return total;
    }

    /**
     * {@link #of(String)} over the committed read views (architecture plan F8): the same walk, with the cursors and the bets as of
     * {@code snapshot}, and no lock. A series' cursor and its bets come from the one snapshot, so a settlement is never seen half
     * way (the cursor moved, the stake not yet paid out).
     */
    public static List<Entry> of(ReadViews.Snapshot snapshot, String playerId) {
        List<Entry> out = new ArrayList<>();
        for (RoundProgress.SeriesKey series : snapshot.seededSeries()) {
            Optional<RoundWindow> running = snapshot.cursor(series.marketId(), series.interval());
            if (running.isEmpty() || !snapshot.hasMarket(series.marketId())) {
                continue;
            }
            snapshot.find(running.get().roundId(), playerId).ifPresent(bet -> out.add(new Entry(series.marketId(),
                    series.interval(), running.get(), Enums.RoundPhase.RUNNING, bet)));
            RoundWindow betting = RoundClock.next(running.get());
            snapshot.find(betting.roundId(), playerId).ifPresent(bet -> out.add(new Entry(series.marketId(),
                    series.interval(), betting, Enums.RoundPhase.BETTING, bet)));
        }
        return out;
    }

    /** {@link #stake(String)} over the committed read views. */
    public static PlayAmount stake(ReadViews.Snapshot snapshot, String playerId) {
        PlayAmount total = PlayAmount.ZERO;
        for (Entry entry : of(snapshot, playerId)) {
            total = total.plus(entry.bet().stake());
        }
        return total;
    }

    private static Optional<Bet> betOf(GameEngine engine, String roundId, String playerId) {
        return engine.betBook().find(roundId, playerId);
    }
}
