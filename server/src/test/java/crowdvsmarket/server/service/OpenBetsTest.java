package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The one walk over the running series that {@code myPositions} and {@code inPlay} are made of: a player's
 * bets on the round a series' cursor points at (RUNNING) and on the one after it (BETTING), across markets and
 * both series — nothing else, nothing twice.
 */
class OpenBetsTest {

    private static final long HOUR = 60_000L;
    private static final long DAY = 120_000L;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private EngineHub hub;
    private RoundProgress progress;
    private OpenBets openBets;
    private Market eth;
    private Market doge;

    private static Market market(String id, int lotExp) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, 20, lotExp, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    private static RoundWindow window(Market market, Interval interval, long startMs) {
        long length = interval == Interval.HOURLY ? HOUR : DAY;
        return new RoundWindow(market.id(), interval, startMs, startMs + length);
    }

    private void bet(String player, Market market, RoundWindow bettingWindow, Direction direction, int leverage,
                     long wholePlay) {
        hub.engine(market.id(), bettingWindow.interval()).placeBet(player, bettingWindow, direction,
                new Leverage(leverage), PlayAmount.ofWholePlay(wholePlay), NOW);
    }

    @BeforeEach
    void twoRunningMarkets() {
        hub = new EngineHub();
        progress = new RoundProgress();
        openBets = new OpenBets(hub, progress);
        eth = market("ETH", 0);
        doge = market("DOGE", 5);
        for (Market market : List.of(eth, doge)) {
            hub.ensureMarket(market);
            for (Interval interval : Interval.values()) {
                progress.seed(market.id(), interval, window(market, interval, 1_200_000L));
            }
        }
    }

    @Test
    void aPlayerWithoutBetsHasNothingInPlay() {
        assertThat(openBets.of("nobody")).isEmpty();
        assertThat(openBets.stake("nobody")).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void theRoundTheCursorPointsAtIsRunningAndTheNextOneIsBetting() {
        RoundWindow running = progress.next("ETH", Interval.HOURLY);
        RoundWindow betting = RoundClock.next(running);
        bet("p1", eth, running, Direction.UP, 5, 200);  // placed while it was open for betting, now it runs
        bet("p1", eth, betting, Direction.DOWN, 3, 300);

        List<OpenBets.Entry> entries = openBets.of("p1");

        assertThat(entries).hasSize(2);
        OpenBets.Entry runningEntry = entries.stream().filter(e -> e.phase() == Enums.RoundPhase.RUNNING)
                .findFirst().orElseThrow();
        OpenBets.Entry bettingEntry = entries.stream().filter(e -> e.phase() == Enums.RoundPhase.BETTING)
                .findFirst().orElseThrow();
        assertThat(runningEntry.window()).isEqualTo(running);
        assertThat(runningEntry.marketId()).isEqualTo("ETH");
        assertThat(runningEntry.interval()).isEqualTo(Interval.HOURLY);
        assertThat(runningEntry.bet().stake()).isEqualTo(PlayAmount.ofWholePlay(200));
        assertThat(bettingEntry.window()).isEqualTo(betting);
        assertThat(bettingEntry.bet().direction()).isEqualTo(Direction.DOWN);
        assertThat(openBets.stake("p1")).isEqualTo(PlayAmount.ofWholePlay(500));
    }

    @Test
    void betsOfEveryMarketAndBothSeriesAreCounted() {
        bet("p1", eth, RoundClock.next(progress.next("ETH", Interval.HOURLY)), Direction.UP, 2, 100);
        bet("p1", eth, RoundClock.next(progress.next("ETH", Interval.DAILY)), Direction.UP, 2, 110);
        bet("p1", doge, RoundClock.next(progress.next("DOGE", Interval.HOURLY)), Direction.DOWN, 2, 120);
        bet("p1", doge, RoundClock.next(progress.next("DOGE", Interval.DAILY)), Direction.DOWN, 2, 130);

        assertThat(openBets.of("p1")).extracting(e -> e.marketId() + "/" + e.interval())
                .containsExactlyInAnyOrder("ETH/HOURLY", "ETH/DAILY", "DOGE/HOURLY", "DOGE/DAILY");
        assertThat(openBets.stake("p1")).isEqualTo(PlayAmount.ofWholePlay(460));
    }

    @Test
    void otherPlayersBetsAreNotTheirs() {
        RoundWindow betting = RoundClock.next(progress.next("ETH", Interval.HOURLY));
        bet("p1", eth, betting, Direction.UP, 2, 100);
        bet("p2", eth, betting, Direction.DOWN, 2, 700);

        assertThat(openBets.stake("p1")).isEqualTo(PlayAmount.ofWholePlay(100));
        assertThat(openBets.stake("p2")).isEqualTo(PlayAmount.ofWholePlay(700));
    }

    @Test
    void aBetOnARoundTheCursorHasLeftBehindIsNotPending() {
        // a settled round leaves the BetBook (the engine clears it) — here: a bet on a window that is neither the
        // cursor's nor the one after it
        RoundWindow old = window(eth, Interval.HOURLY, 1_200_000L - 2 * HOUR);
        bet("p1", eth, old, Direction.UP, 2, 100);

        assertThat(openBets.of("p1")).isEmpty();
    }

    @Test
    void aSeriesThatIsNotSeededHasNoOpenBets() {
        RoundWindow betting = RoundClock.next(progress.next("DOGE", Interval.DAILY));
        bet("p1", doge, betting, Direction.UP, 2, 100);

        progress.remove("DOGE", Interval.DAILY); // a closed market's series stops: its bets were refunded

        assertThat(openBets.of("p1")).isEmpty();
    }

    @Test
    void aBetOnTheSameRoundIsReplacedNotAdded() {
        RoundWindow betting = RoundClock.next(progress.next("ETH", Interval.HOURLY));
        bet("p1", eth, betting, Direction.UP, 2, 100);
        bet("p1", eth, betting, Direction.DOWN, 4, 250); // Р-13: a second bet on the round replaces the first

        assertThat(openBets.of("p1")).hasSize(1);
        assertThat(openBets.stake("p1")).isEqualTo(PlayAmount.ofWholePlay(250));
    }
}
