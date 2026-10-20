package crowdvsmarket.server.snapshot;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test support: a seeded random game played through the real {@link EventApplier} into a hub - people and bots, nicknames with
 * tabs, newlines and unicode in them, three markets, bets of both modes with and without fees, edits and cancellations, rounds
 * resolved (some liquidating) and cancelled, withdrawals and deposits in every state, the economy cutover, retirements, sweeps. An
 * event the engine refuses (a stake above a balance, say) is skipped, as the writer would have refused the command: the state is
 * whatever the accepted ones made, which is all a snapshot has to carry.
 */
final class RandomGame {

    final EngineHub hub;
    final BetFeed feed;
    final EventApplier applier;
    final Random rnd;
    final List<String> players = new ArrayList<>();
    final List<String> markets = new ArrayList<>(List.of("BTC"));
    final List<Market> listed = new ArrayList<>();
    /** Every event the game tried to apply, in order (the refused ones too: a twin applies them and is refused alike). */
    final List<GameEvent> log = new ArrayList<>();
    final List<String> withdrawals = new ArrayList<>();
    final List<String> deposits = new ArrayList<>();
    /** The windows rounds were started on, per market and interval: {market, interval, start}. */
    private final List<Object[]> windows = new ArrayList<>();
    private long clock = 1_700_000_000_000L;
    private int serial;
    private boolean cutover;

    RandomGame(long seed, EngineHub hub, BetFeed feed) {
        this.rnd = new Random(seed);
        this.hub = hub;
        this.feed = feed;
        this.applier = EventApplier.over(hub, feed);
        for (String id : List.of("T_RA", "T_RB")) {
            GameEvent.MarketListed e = new GameEvent.MarketListed(id, "PERP", "crypto", id, "Market " + id, null, null, "synthetic",
                    id, "{}", 25, 1, 7 + markets.size(), clock, null, 600, 300, "0.1", "1.0", "0.016");
            apply(e);
            markets.add(id);
            listed.add(EventApplier.marketOf(e));
        }
    }

    Object apply(GameEvent event) {
        log.add(event);
        try {
            return applier.apply(event);
        } catch (RuntimeException e) {
            return null; // refused, as the writer would have refused the command
        }
    }

    void play(int steps) {
        for (int i = 0; i < steps; i++) {
            step();
        }
    }

    private String pick(List<String> from) {
        return from.get(rnd.nextInt(from.size()));
    }

    private void step() {
        clock += 1 + rnd.nextInt(5_000);
        int r = rnd.nextInt(100);
        if (players.size() < 3 || r < 6) {
            register();
        } else if (r < 46) {
            bet();
        } else if (r < 52) {
            cancelBet();
        } else if (r < 70) {
            resolve();
        } else if (r < 74) {
            cancelRound();
        } else if (r < 80) {
            apply(new GameEvent.NicknameChanged(pick(players), nickname(), clock));
        } else if (r < 84) {
            apply(new GameEvent.BotStrategyAssigned(pick(players), "strategy-" + rnd.nextInt(5), clock));
        } else if (r < 90) {
            cashFlow();
        } else if (r < 93 && !cutover) {
            cutover = true;
            apply(new GameEvent.EconomyCutover(5_000_000_000L + rnd.nextInt(1000), clock));
        } else if (r < 95) {
            apply(new GameEvent.BotRetired(pick(players), clock));
        } else if (r < 97) {
            apply(new GameEvent.HerdSweptToTreasury(1 + rnd.nextInt(2_000), clock));
        } else if (r < 98) {
            apply(new GameEvent.PlayerRegistered(pick(players), clock)); // a returning player (a new season's pack, maybe)
        } else {
            clock += 40L * 86_400_000L; // a month on: the next activity of everyone is a new season
        }
    }

    private String nickname() {
        String[] names = {"Alice", "Bob\tTabbed", "Line\nBreak", "back\\slash", "λ-Лиса-狐", "plain", "\\N", "CR\rLF", null, ""};
        return names[rnd.nextInt(names.length)];
    }

    private void register() {
        String id = "p-" + (++serial);
        String kind = rnd.nextInt(5) == 0 ? "HOUSE_BOT" : rnd.nextInt(8) == 0 ? "API_BOT" : "HUMAN";
        apply(GameEvent.PlayerRegistered.registration(id, clock, "xch1wallet" + serial, serial, nickname(), kind,
                kind.equals("HUMAN") ? null : "trend", kind.equals("API_BOT") && !players.isEmpty() ? pick(players) : null, null));
        players.add(id);
    }

    private long windowStart(int interval) {
        long length = interval == 0 ? 180_000L : 900_000L;
        return (clock / length + rnd.nextInt(3)) * length;
    }

    private void bet() {
        String market = pick(markets);
        Interval interval = rnd.nextBoolean() ? Interval.HOURLY : Interval.DAILY;
        long length = interval == Interval.HOURLY ? 180_000L : 900_000L;
        long start = windowStart(interval == Interval.HOURLY ? 0 : 1);
        if (windows.stream().noneMatch(w -> w[0].equals(market) && w[1] == interval && (long) w[2] == start)) {
            windows.add(new Object[] {market, interval, start});
        }
        String player = pick(players);
        Direction direction = rnd.nextBoolean() ? Direction.UP : Direction.DOWN;
        long stake = 100_000L + 1_000L * rnd.nextInt(5_000);
        int kind = rnd.nextInt(4);
        GameEvent.BetPlaced event = switch (kind) {
            case 0 -> new GameEvent.BetPlaced(player, interval, start, start + length, direction, 2 + rnd.nextInt(24), stake,
                    clock, market);
            case 1 -> new GameEvent.BetPlaced(player, interval, start, start + length, direction, 2 + rnd.nextInt(24), stake,
                    clock, market, (long) rnd.nextInt(500), (long) rnd.nextInt(50));
            case 2 -> new GameEvent.BetPlaced(player, interval, start, start + length, direction, 2 + rnd.nextInt(24), stake,
                    clock, market, (long) rnd.nextInt(500), (long) rnd.nextInt(50), 300 + rnd.nextInt(900), "0.1");
            default -> GameEvent.BetPlaced.fixed(player, interval, start, start + length, direction, stake, clock, market,
                    (long) rnd.nextInt(500), (long) rnd.nextInt(50));
        };
        apply(event);
    }

    private Object[] someWindow() {
        return windows.isEmpty() ? null : windows.get(rnd.nextInt(windows.size()));
    }

    private void cancelBet() {
        Object[] w = someWindow();
        if (w != null) {
            long length = w[1] == Interval.HOURLY ? 180_000L : 900_000L;
            apply(new GameEvent.BetCancelled(pick(players), (Interval) w[1], (long) w[2], (long) w[2] + length, clock,
                    (String) w[0]));
        }
    }

    private void resolve() {
        Object[] w = someWindow();
        if (w == null) {
            return;
        }
        long length = w[1] == Interval.HOURLY ? 180_000L : 900_000L;
        long start = (long) w[2];
        BigDecimal open = BigDecimal.valueOf(1_000 + rnd.nextInt(90_000), 2);
        List<GameEvent.CandleData> path = new ArrayList<>();
        BigDecimal price = open;
        for (int i = 0; i < 6; i++) {
            BigDecimal swing = open.multiply(BigDecimal.valueOf(rnd.nextInt(400) - 200, 4)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = price.add(swing).max(BigDecimal.ONE);
            BigDecimal high = price.max(close).add(BigDecimal.valueOf(rnd.nextInt(300), 2));
            BigDecimal low = price.min(close).subtract(BigDecimal.valueOf(rnd.nextInt(300), 2)).max(BigDecimal.ONE);
            path.add(new GameEvent.CandleData(start + i * (length / 6), start + (i + 1) * (length / 6) - 1, price.toPlainString(),
                    high.toPlainString(), low.toPlainString(), close.toPlainString(), "1.5", 10 + i));
            price = close;
        }
        apply(new GameEvent.RoundResolved((Interval) w[1], start, start + length, open.toPlainString(), price.toPlainString(),
                path, clock, (String) w[0], null, 1L + serial * 10L + rnd.nextInt(5)));
        windows.remove(w);
    }

    private void cancelRound() {
        Object[] w = someWindow();
        if (w != null) {
            long length = w[1] == Interval.HOURLY ? 180_000L : 900_000L;
            apply(new GameEvent.RoundCancelled((Interval) w[1], (long) w[2], (long) w[2] + length, "test cancel", clock,
                    (String) w[0]));
            windows.remove(w);
        }
    }

    private void cashFlow() {
        String player = pick(players);
        long amount = 100_000L + rnd.nextInt(2_000_000);
        switch (rnd.nextInt(8)) {
            case 0 -> {
                Object id = apply(new GameEvent.WithdrawalRequested(player, amount, clock));
                if (id != null) {
                    withdrawals.add((String) id);
                }
            }
            case 1 -> {
                Object id = apply(new GameEvent.DepositRequested(player, amount, clock));
                if (id != null) {
                    deposits.add((String) id);
                }
            }
            case 2 -> {
                if (!withdrawals.isEmpty()) {
                    apply(new GameEvent.WithdrawalConfirmed(pick(withdrawals), clock));
                }
            }
            case 3 -> {
                if (!withdrawals.isEmpty()) {
                    apply(new GameEvent.WithdrawalFailed(pick(withdrawals), clock));
                }
            }
            case 4 -> {
                if (!deposits.isEmpty()) {
                    apply(new GameEvent.DepositConfirmed(pick(deposits), clock));
                }
            }
            case 5 -> {
                if (!deposits.isEmpty()) {
                    apply(new GameEvent.DepositFailed(pick(deposits), clock));
                }
            }
            case 6 -> apply(new GameEvent.DepositConfirmedOnChain(player, 1, amount, "coin-" + (++serial), 100L + serial, clock));
            default -> apply(new GameEvent.WithdrawalReversed(player, amount, "2026-10", "operator \"fix\"\twith tab",
                    withdrawals.isEmpty() ? "w-none" : pick(withdrawals), clock));
        }
    }
}
