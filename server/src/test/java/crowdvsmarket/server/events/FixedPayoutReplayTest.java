package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
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
 * Р-106 on the replay path, the twin of {@link AggregatesReplayTest}: a random journal that mixes fixed-payout bets, percentage bets with
 * and without fees, replacements across the modes, cancels, resolved and cancelled rounds is applied once as a live run and once into a
 * fresh hub, as a rebuild does. After every event the aggregates of the book (notional of the percentage bets, stake of the fixed ones)
 * equal the brute force, and the replay ends with the same aggregates and the same balances as the live run.
 */
class FixedPayoutReplayTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long START = 1_700_000_000_000L;
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3", "p4", "p5");
    private static final List<String> MARKETS = List.of("BTC", "ETH");

    private record Win(Interval interval, long start, long end) {
    }

    private static final List<Win> WINDOWS = List.of(
            new Win(Interval.HOURLY, START, START + HOUR), new Win(Interval.HOURLY, START + HOUR, START + 2 * HOUR),
            new Win(Interval.DAILY, START, START + DAY));

    private static EventApplier newApplier(EngineHub hub) {
        hub.ensureMarket(new Market("ETH", MarketKind.PERP, "crypto", "ETH", "Ether", null, null, "synthetic", "ETH",
                null, 25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null));
        EventApplier applier = new EventApplier();
        applier.hub = hub;
        applier.betFeed = new BetFeed();
        applier.players = new PlayerDirectory();
        return applier;
    }

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
                long stake = 100_000L + random.nextInt(200) * 1_000L;
                int kind = random.nextInt(3);
                if (kind == 0) {
                    events.add(new GameEvent.BetPlaced(player, w.interval(), w.start(), w.end(), direction, 2 + random.nextInt(9),
                            stake, START - 1, market));
                } else if (kind == 1) {
                    events.add(new GameEvent.BetPlaced(player, w.interval(), w.start(), w.end(), direction, 2 + random.nextInt(9),
                            stake, START - 1, market, (long) random.nextInt(500), (long) random.nextInt(500)));
                } else {
                    var fees = EconomyRules.fixedFees(new PlayAmount(stake));
                    events.add(GameEvent.BetPlaced.fixed(player, w.interval(), w.start(), w.end(), direction, stake, START - 1,
                            market, fees.herd().mojos(), fees.network().mojos()));
                }
            } else if (op < 8) {
                events.add(new GameEvent.BetCancelled(player, w.interval(), w.start(), w.end(), START - 1, market));
            } else if (op < 9) {
                String open = "100.00";
                String close = switch (random.nextInt(3)) {
                    case 0 -> "103.00";
                    case 1 -> "97.00";
                    default -> "100.00"; // a tie
                };
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

    private static void assertAgainstBruteForce(EngineHub hub, String context) {
        for (String market : MARKETS) {
            for (Win w : WINDOWS) {
                String roundId = new RoundWindow(market, w.interval(), w.start(), w.end()).roundId();
                var book = hub.engine(market, w.interval()).betBook();
                List<Bet> bets = List.copyOf(book.betsFor(roundId));
                for (Direction direction : Direction.values()) {
                    BigDecimal notional = BigDecimal.ZERO;
                    BigDecimal fixed = BigDecimal.ZERO;
                    for (Bet bet : bets) {
                        if (bet.direction() != direction) {
                            continue;
                        }
                        if (bet.isFixed()) {
                            fixed = fixed.add(bet.stake().toMoney());
                        } else {
                            notional = notional.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
                        }
                    }
                    String at = context + " " + roundId + " " + direction;
                    assertThat(book.openNotional(roundId, direction, null)).as(at + " notional").isEqualByComparingTo(notional);
                    assertThat(book.openFixedStake(roundId, direction, null)).as(at + " fixed").isEqualByComparingTo(fixed);
                }
            }
        }
    }

    @Test
    void aReplayedMixedJournalEndsWithTheAggregatesAndTheBalancesOfTheLiveRun() {
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
            assertThat(replayed.ledger().herd()).isEqualTo(live.ledger().herd());
            for (String p : PLAYERS) {
                assertThat(replayed.ledger().balance(p)).as("seed " + seed + " " + p).isEqualTo(live.ledger().balance(p));
            }
            for (String market : MARKETS) {
                for (Win w : WINDOWS) {
                    String roundId = new RoundWindow(market, w.interval(), w.start(), w.end()).roundId();
                    for (Direction direction : Direction.values()) {
                        assertThat(replayed.engine(market, w.interval()).betBook().openFixedStake(roundId, direction, null))
                                .isEqualByComparingTo(live.engine(market, w.interval()).betBook().openFixedStake(roundId, direction, null));
                    }
                }
            }
        }
    }
}
