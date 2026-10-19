package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F2 on the replay path: a random journal (bets with and without fees, replacements, cancels, resolved and
 * cancelled rounds, two markets, both round lengths) is applied once as a live run and once again into a fresh hub, as a rebuild
 * does. After every event the live aggregates equal the brute force over the live bets, and at the end the replayed hub's
 * aggregates equal the live hub's.
 */
class AggregatesReplayTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long START = 1_700_000_000_000L;
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3", "p4", "p5");
    private static final List<String> MARKETS = List.of("BTC", "ETH");

    private static EventApplier newApplier(EngineHub hub) {
        hub.ensureMarket(new Market("ETH", MarketKind.PERP, "crypto", "ETH", "Ether", null, null, "synthetic", "ETH",
                null, 25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null));
        EventApplier applier = new EventApplier();
        applier.hub = hub;
        applier.betFeed = new BetFeed();
        applier.players = new PlayerDirectory();
        return applier;
    }

    private record Win(Interval interval, long start, long end) {
    }

    private static final List<Win> WINDOWS = List.of(
            new Win(Interval.HOURLY, START, START + HOUR), new Win(Interval.HOURLY, START + HOUR, START + 2 * HOUR),
            new Win(Interval.HOURLY, START + 2 * HOUR, START + 3 * HOUR), new Win(Interval.DAILY, START, START + DAY));

    private static List<GameEvent> randomJournal(long seed) {
        Random random = new Random(seed);
        List<GameEvent> events = new ArrayList<>();
        for (String p : PLAYERS) {
            events.add(new GameEvent.PlayerRegistered(p, START - 1_000));
        }
        Set<String> closed = new HashSet<>();
        for (int i = 0; i < 250; i++) {
            Win w = WINDOWS.get(random.nextInt(WINDOWS.size()));
            String market = MARKETS.get(random.nextInt(MARKETS.size()));
            String key = market + w.interval() + w.start();
            if (closed.contains(key)) {
                continue;
            }
            String player = PLAYERS.get(random.nextInt(PLAYERS.size()));
            int op = random.nextInt(10);
            if (op < 6) {
                Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                int leverage = 2 + random.nextInt(9);
                long stake = 100_000L + random.nextInt(200) * 1_000L;
                if (random.nextBoolean()) {
                    events.add(new GameEvent.BetPlaced(player, w.interval(), w.start(), w.end(), direction, leverage, stake,
                            START - 1, market));
                } else {
                    events.add(new GameEvent.BetPlaced(player, w.interval(), w.start(), w.end(), direction, leverage, stake,
                            START - 1, market, (long) random.nextInt(500), (long) random.nextInt(500)));
                }
            } else if (op < 8) {
                events.add(new GameEvent.BetCancelled(player, w.interval(), w.start(), w.end(), START - 1, market));
            } else if (op < 9) {
                String open = "100.00";
                String close = random.nextBoolean() ? "103.00" : "97.00";
                events.add(new GameEvent.RoundResolved(w.interval(), w.start(), w.end(), open, close,
                        List.of(new GameEvent.CandleData(w.start(), w.end() - 1, open, "103.00", "97.00", close, "0", 0)),
                        w.end(), market));
                closed.add(key);
            } else {
                events.add(new GameEvent.RoundCancelled(w.interval(), w.start(), w.end(), "test", w.end(), market));
                closed.add(key);
            }
        }
        return events;
    }

    /** The old O(bets) / O(accounts) computations. */
    private static void assertAgainstBruteForce(EngineHub hub, String context) {
        PlayAmount total = PlayAmount.ZERO;
        for (String id : hub.ledger().playerIds()) {
            total = total.plus(hub.ledger().balance(id));
        }
        assertThat(hub.ledger().playersTotal()).as(context + " playersTotal").isEqualTo(total);
        for (String market : MARKETS) {
            for (Win w : WINDOWS) {
                String roundId = new RoundWindow(market, w.interval(), w.start(), w.end()).roundId();
                var book = hub.engine(market, w.interval()).betBook();
                List<Bet> bets = List.copyOf(book.betsFor(roundId));
                assertThat(book.count(roundId)).as(context + " count").isEqualTo(bets.size());
                for (Direction direction : Direction.values()) {
                    BigDecimal expected = BigDecimal.ZERO;
                    for (Bet bet : bets) {
                        if (bet.direction() == direction) {
                            expected = expected.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
                        }
                    }
                    assertThat(book.openNotional(roundId, direction, null)).as(context + " " + roundId + " " + direction)
                            .isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void aReplayedJournalEndsWithTheAggregatesOfTheLiveRun() {
        for (long seed = 1; seed <= 15; seed++) {
            List<GameEvent> journal = randomJournal(seed);

            EngineHub live = new EngineHub();
            EventApplier liveApplier = newApplier(live);
            int n = 0;
            for (GameEvent event : journal) {
                liveApplier.apply(event);
                assertAgainstBruteForce(live, "seed " + seed + " live event " + (n++) + " " + event.getClass().getSimpleName());
            }

            EngineHub replayed = new EngineHub();
            EventApplier replayApplier = newApplier(replayed);
            journal.forEach(replayApplier::apply);
            assertAgainstBruteForce(replayed, "seed " + seed + " replay");

            assertThat(replayed.ledger().playersTotal()).isEqualTo(live.ledger().playersTotal());
            for (String market : MARKETS) {
                for (Win w : WINDOWS) {
                    String roundId = new RoundWindow(market, w.interval(), w.start(), w.end()).roundId();
                    for (Direction direction : Direction.values()) {
                        BigDecimal a = live.engine(market, w.interval()).betBook().openNotional(roundId, direction, null);
                        BigDecimal b = replayed.engine(market, w.interval()).betBook().openNotional(roundId, direction, null);
                        assertThat(b).as("seed " + seed + " " + roundId + " " + direction).isEqualTo(a);
                        assertThat(b.scale()).isEqualTo(a.scale());
                    }
                }
            }
        }
    }
}
