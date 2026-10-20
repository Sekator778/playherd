package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link EventApplier} routes the four round events by their market (docs/research/
 * markets_architecture.md §6): an event without one is BTC, an event naming a market the hub has no
 * engines for stops the replay loudly — a rebuild must never silently skip money. Plain objects, no
 * database: the applier and the hub are wired by hand.
 */
class EventApplierMarketsTest {

    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 180_000L;

    private EngineHub hub;
    private EventApplier applier;

    @BeforeEach
    void wire() {
        hub = new EngineHub();
        hub.ensureMarket(new Market("ETH", MarketKind.PERP, "crypto", "ETH", "Ether", null, null, "synthetic", "ETH",
                null, 25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null));
        applier = new EventApplier();
        applier.hub = hub;
        applier.betFeed = new BetFeed();
        applier.apply(new GameEvent.PlayerRegistered("p1", START - 1_000));
    }

    private static GameEvent.CandleData candle(String open, String high, String low, String close) {
        return new GameEvent.CandleData(START, END - 1, open, high, low, close, "0", 0);
    }

    @Test
    void anApplierOverAnotherStateChangesThatStateAndNeverTheLiveOne() {
        // what a rebuild replays into while the live state keeps serving
        EngineHub aside = new EngineHub();
        aside.ensureMarket(new Market("ETH", MarketKind.PERP, "crypto", "ETH", "Ether", null, null, "synthetic", "ETH",
                null, 25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null));
        BetFeed asideFeed = new BetFeed();
        EventApplier over = EventApplier.over(aside, asideFeed);

        over.apply(new GameEvent.PlayerRegistered("p2", START - 1_000));
        over.apply(new GameEvent.BetPlaced("p2", Interval.HOURLY, START, END, Direction.UP, 5, 200_000L, START - 1, "ETH"));

        assertThat(aside.engine("ETH", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).hasSize(1);
        assertThat(aside.ledger().playerIds()).contains("p2");
        assertThat(asideFeed.latest("ETH-1h-" + START, 10)).hasSize(1);
        // the live hub and feed did not hear of any of it
        assertThat(hub.ledger().playerIds()).doesNotContain("p2");
        assertThat(hub.engine("ETH", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).isEmpty();
        assertThat(applier.betFeed.latest("ETH-1h-" + START, 10)).isEmpty();
    }

    @Test
    void anEventWithoutAMarketIsBtc() {
        // the pre-markets constructor of every round event is BTC
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 10, 100_000L, START - 1));

        assertThat(hub.engine("BTC", Interval.HOURLY).betBook().betsFor("1h-" + START)).hasSize(1);
        assertThat(hub.engine("ETH", Interval.HOURLY).betBook().betsFor("1h-" + START)).isEmpty();
        assertThat(hub.engine("ETH", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).isEmpty();

        applier.apply(new GameEvent.BetCancelled("p1", Interval.HOURLY, START, END, START));
        assertThat(hub.engine("BTC", Interval.HOURLY).betBook().betsFor("1h-" + START)).isEmpty();
    }

    @Test
    void aMarketEventLandsInThatMarketsEngineAndNowhereElse() {
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.DOWN, 20, 300_000L,
                START - 1, "ETH"));

        assertThat(hub.engine("ETH", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).hasSize(1);
        assertThat(hub.engine("BTC", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).isEmpty();
        assertThat(hub.engine("ETH", Interval.DAILY).betBook().betsFor("ETH-1h-" + START)).isEmpty();
        // the stake left the one shared balance
        assertThat(hub.ledger().balance("p1")).isEqualTo(PlayAmount.ofWholePlay(10_000).minus(new PlayAmount(300_000L)));
    }

    @Test
    void resolvingAMarketRoundSettlesItsOwnCrowdAccountAndComparisonLines() {
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.DOWN, 20, 300_000L,
                START - 1, "ETH"));

        RoundReport report = (RoundReport) applier.apply(new GameEvent.RoundResolved(Interval.HOURLY, START, END,
                "25000.00", "24000.00", List.of(candle("25000.00", "25100.00", "24000.00", "24000.00")), END, "ETH"));

        assertThat(report.window().market()).isEqualTo("ETH");
        // 300 PLAY at 20x short from 25000.00 to 24000.00: size 0.24, pnl +240 -> payout 540
        assertThat(report.playerOutcomes()).singleElement()
                .satisfies(o -> assertThat(o.payout()).isEqualByComparingTo(new BigDecimal("540.00")));
        assertThat(hub.crowdAccount("ETH").history()).hasSize(1);
        assertThat(hub.crowdAccount("BTC").history()).isEmpty();
        assertThat(hub.comparison("ETH").holdHistory()).hasSize(1);
        assertThat(hub.comparison("BTC").holdHistory()).isEmpty();
        assertThat(hub.ledger().balance("p1")).isEqualTo(PlayAmount.ofWholePlay(10_000).minus(new PlayAmount(300_000L))
                .plus(PlayAmount.ofWholePlay(540)));
    }

    @Test
    void aCancelledMarketRoundRefundsTheStake() {
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 5, 200_000L, START - 1,
                "ETH"));

        applier.apply(new GameEvent.RoundCancelled(Interval.HOURLY, START, END, "market closed: test", END, "ETH"));

        assertThat(hub.engine("ETH", Interval.HOURLY).betBook().betsFor("ETH-1h-" + START)).isEmpty();
        assertThat(hub.ledger().balance("p1")).isEqualTo(PlayAmount.ofWholePlay(10_000));
    }

    @Test
    void anUnknownMarketStopsTheReplayNamingTheEvent() {
        GameEvent.BetPlaced bet = new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 5, 200_000L,
                START - 1, "GHOST");

        assertThatThrownBy(() -> applier.apply(bet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BetPlaced")
                .hasMessageContaining("GHOST");
        // nothing was reserved for the bet that could not be placed
        assertThat(hub.ledger().balance("p1")).isEqualTo(PlayAmount.ofWholePlay(10_000));
    }

    @Test
    void everyRoundEventTypeFailsLoudlyOnAnUnknownMarket() {
        List<GameEvent> events = List.of(
                new GameEvent.BetCancelled("p1", Interval.HOURLY, START, END, START, "GHOST"),
                new GameEvent.RoundResolved(Interval.HOURLY, START, END, "1.00", "1.00",
                        List.of(candle("1.00", "1.00", "1.00", "1.00")), END, "GHOST"),
                new GameEvent.RoundCancelled(Interval.HOURLY, START, END, "x", END, "GHOST"));

        for (GameEvent event : events) {
            assertThatThrownBy(() -> applier.apply(event))
                    .as(event.getClass().getSimpleName())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(event.getClass().getSimpleName())
                    .hasMessageContaining("GHOST");
        }
    }

    @Test
    void theNormalisedMarketOfAnEventWithoutOneIsBtc() {
        assertThat(new GameEvent.BetPlaced("p", Interval.HOURLY, 1, 2, Direction.UP, 2, 1, 1).market()).isEqualTo("BTC");
        assertThat(new GameEvent.BetPlaced("p", Interval.HOURLY, 1, 2, Direction.UP, 2, 1, 1, null).market())
                .isEqualTo("BTC");
        assertThat(new GameEvent.BetCancelled("p", Interval.HOURLY, 1, 2, 1).market()).isEqualTo("BTC");
        assertThat(new GameEvent.RoundCancelled(Interval.HOURLY, 1, 2, "r", 1).market()).isEqualTo("BTC");
        assertThat(new GameEvent.RoundResolved(Interval.HOURLY, 1, 2, "1.00", "1.00", List.of(), 1).market())
                .isEqualTo("BTC");
        assertThat(new GameEvent.BetPlaced("p", Interval.HOURLY, 1, 2, Direction.UP, 2, 1, 1, "xyz_NVDA").market())
                .isEqualTo("xyz_NVDA");
    }
}
