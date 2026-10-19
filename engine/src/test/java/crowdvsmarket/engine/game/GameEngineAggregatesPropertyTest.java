package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F2 through the engine: after every place / replace / player cancel / round resolved / round cancelled
 * (refund and clear), on two markets and both round lengths sharing one ledger, the book's per-side open notional equals the
 * brute-force sum over its bets and {@link LedgerBook#playersTotal()} equals the sum of the balances.
 */
class GameEngineAggregatesPropertyTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;
    private static final long START = Instant.parse("2026-04-15T00:00:00Z").toEpochMilli();
    private static final MarketSpec ETH = MarketSpec.of("ETH", 25);
    private static final List<String> PLAYERS = List.of("a", "b", "c", "d", "e");

    private static RoundPrices prices(long start, long end, String open, String close) {
        Candle candle = new Candle(start, end - 1, new BigDecimal(open), new BigDecimal(open).max(new BigDecimal(close)),
                new BigDecimal(open).min(new BigDecimal(close)), new BigDecimal(close), BigDecimal.ZERO, 0L);
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow window) {
                return candle.open();
            }

            @Override
            public BigDecimal closePrice(RoundWindow window) {
                return candle.close();
            }

            @Override
            public List<Candle> path(RoundWindow window) {
                return List.of(candle);
            }
        };
    }

    private static PlayAmount bruteForcePlayersTotal(LedgerBook ledger) {
        PlayAmount total = PlayAmount.ZERO;
        for (String id : ledger.playerIds()) {
            total = total.plus(ledger.balance(id));
        }
        return total;
    }

    private static void check(LedgerBook ledger, List<GameEngine> engines, List<RoundWindow> windows, String context) {
        assertThat(ledger.playersTotal()).as(context + " playersTotal").isEqualTo(bruteForcePlayersTotal(ledger));
        for (GameEngine engine : engines) {
            for (RoundWindow window : windows) {
                String roundId = window.roundId();
                List<Bet> bets = List.copyOf(engine.betBook().betsFor(roundId));
                assertThat(engine.betBook().count(roundId)).isEqualTo(bets.size());
                for (Direction direction : Direction.values()) {
                    BigDecimal expected = BigDecimal.ZERO;
                    for (Bet bet : bets) {
                        if (bet.direction() == direction) {
                            expected = expected.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
                        }
                    }
                    assertThat(engine.betBook().openNotional(roundId, direction, null))
                            .as(context + " " + roundId + " " + direction).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void aggregatesEqualBruteForceAfterEveryOperation() {
        for (long seed = 1; seed <= 25; seed++) {
            Random random = new Random(seed);
            LedgerBook ledger = new LedgerBook();
            GameEngine btc = new GameEngine(ledger, new CrowdAccount());
            GameEngine eth = new GameEngine(ledger,
                    new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH));
            List<GameEngine> engines = List.of(btc, eth);
            List<RoundWindow> windows = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                windows.add(new RoundWindow(Interval.HOURLY, START + k * HOUR, START + (k + 1) * HOUR));
                windows.add(new RoundWindow("ETH", Interval.HOURLY, START + k * HOUR, START + (k + 1) * HOUR));
            }
            windows.add(new RoundWindow(Interval.DAILY, START, START + DAY));
            windows.add(new RoundWindow("ETH", Interval.DAILY, START, START + DAY));
            Set<String> closed = new HashSet<>();
            Instant now = Instant.ofEpochMilli(START - 60_000L);
            for (String player : PLAYERS) {
                ledger.ensureSeasonPack(player, now);
            }
            check(ledger, engines, windows, "seed " + seed + " start");

            for (int step = 0; step < 200; step++) {
                RoundWindow window = windows.get(random.nextInt(windows.size()));
                if (closed.contains(window.roundId())) {
                    continue;
                }
                GameEngine engine = window.market().equals("ETH") ? eth : btc;
                String player = PLAYERS.get(random.nextInt(PLAYERS.size()));
                int op = random.nextInt(10);
                String what;
                if (op < 6) {
                    Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                    Leverage leverage = new Leverage(2 + random.nextInt(9));
                    PlayAmount stake = new PlayAmount(100_000L + random.nextInt(200) * 1_000L);
                    if (random.nextBoolean()) {
                        engine.placeBet(player, window, direction, leverage, stake, now);
                    } else {
                        engine.replayBet(player, window, direction, leverage, stake,
                                new Fees(new PlayAmount(random.nextInt(500)), new PlayAmount(random.nextInt(500))), now);
                    }
                    what = "place";
                } else if (op < 8) {
                    engine.cancelBet(player, window, now);
                    what = "cancel";
                } else if (op < 9) {
                    engine.runRound(window, prices(window.startMs(), window.endMs(), "100.00",
                            random.nextBoolean() ? "103.00" : "97.00"));
                    closed.add(window.roundId());
                    what = "resolve";
                } else {
                    // a cancelled round: refund every pending bet, then clear (EventApplier.refundAndClear)
                    for (Bet bet : List.copyOf(engine.betBook().betsFor(window.roundId()))) {
                        engine.refundBet(bet, now);
                    }
                    engine.betBook().clear(window.roundId());
                    closed.add(window.roundId());
                    what = "cancel round";
                }
                check(ledger, engines, windows, "seed " + seed + " step " + step + " " + what);
            }
        }
    }
}
