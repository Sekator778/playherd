package crowdvsmarket.bots.port;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BetResult;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * An in-memory game for the tests: three markets (BTC and ETH open, SOL closed), a random walk of 120 one-minute candles
 * each, and bot accounts that obey the game's betting rules (minimum stake, one bet per round of a market, stake tiers,
 * balance). Implements all three ports, so it is the reference for {@link PortsContractTest}.
 */
public final class InMemoryExchange implements MarketDataPort, BettingPort, BotStatePort {

    /** A fixed "now": the start of a 15-minute-aligned window. */
    public static final long WINDOW_START_MS = 1_790_000_100_000L / 900_000L * 900_000L;

    private final List<MarketSnapshot> markets = new ArrayList<>();
    private final Map<String, List<BarDto>> bars = new HashMap<>();
    private final Map<String, Direction> majorities = new HashMap<>();
    private final Map<String, PlayAmount> balances = new HashMap<>();
    private final Map<String, Set<String>> betRounds = new HashMap<>();

    public InMemoryExchange() {
        MarketSpec btc = MarketSpec.BTC;
        MarketSpec eth = MarketSpec.of("ETH", 1000).withEconomy(1000, 400, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));
        MarketSpec sol = MarketSpec.of("SOL", 600).withEconomy(600, 300, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));
        add(btc, Interval.HOURLY, 180_000, true, 60_000);
        add(eth, Interval.DAILY, 900_000, true, 3_000);
        add(sol, Interval.DAILY, 900_000, false, 150);
        majorities.put("BTC", Direction.UP);
    }

    private void add(MarketSpec spec, Interval interval, long lengthMs, boolean open, double startPrice) {
        String round = spec.id() + "-" + interval.code() + "-" + WINDOW_START_MS;
        markets.add(new MarketSnapshot(spec, interval, round, WINDOW_START_MS, WINDOW_START_MS + lengthMs, open,
                LedgerBook.MIN_STAKE));
        SplittableRandom rng = new SplittableRandom(spec.id().hashCode());
        List<BarDto> list = new ArrayList<>();
        double price = startPrice;
        for (int i = 120; i >= 1; i--) {
            double close = price * (1 + rng.nextDouble(-0.0005, 0.0005));
            list.add(new BarDto(WINDOW_START_MS - i * BarDto.MINUTE_MS, price, Math.max(price, close) * 1.0001,
                    Math.min(price, close) * 0.9999, close));
            price = close;
        }
        bars.put(spec.id(), list);
    }

    /** Opens an account. */
    public InMemoryExchange withBot(String botId, long balancePlay) {
        balances.put(botId, PlayAmount.ofWholePlay(balancePlay));
        betRounds.put(botId, new HashSet<>());
        return this;
    }

    @Override
    public List<MarketSnapshot> markets() {
        return List.copyOf(markets);
    }

    @Override
    public List<BarDto> bars(String marketId, int minutes) {
        List<BarDto> all = bars.getOrDefault(marketId, List.of());
        if (minutes <= 0) {
            return List.of();
        }
        return List.copyOf(all.subList(Math.max(0, all.size() - minutes), all.size()));
    }

    @Override
    public Optional<Direction> previousMajority(String marketId) {
        return Optional.ofNullable(majorities.get(marketId));
    }

    @Override
    public Optional<BotAccount> account(String botId) {
        PlayAmount balance = balances.get(botId);
        if (balance == null) {
            return Optional.empty();
        }
        return Optional.of(new BotAccount(botId, balance, LastOutcome.NONE, 0, betRounds.get(botId)));
    }

    @Override
    public BetResult place(BetRequest r) {
        PlayAmount balance = balances.get(r.botId());
        if (balance == null) {
            return BetResult.refused("unknown bot");
        }
        MarketSnapshot m = markets.stream().filter(x -> x.marketId().equals(r.marketId())).findFirst().orElse(null);
        if (m == null) {
            return BetResult.refused("unknown market");
        }
        if (!m.roundId().equals(r.roundId())) {
            return BetResult.refused("not the current round");
        }
        if (!m.open()) {
            return BetResult.refused("betting closed");
        }
        if (betRounds.get(r.botId()).contains(r.roundId())) {
            return BetResult.refused("already bet in this round");
        }
        if (r.stake().compareTo(m.minStake()) < 0) {
            return BetResult.refused("stake below minimum");
        }
        if (r.leverage() < Leverage.MIN || r.leverage() > m.maxLeverage(r.stake())) {
            return BetResult.refused("leverage out of range");
        }
        Fees fees = m.fees(r.leverage(), r.stake());
        PlayAmount total = r.stake().plus(fees.total());
        if (total.compareTo(balance) > 0) {
            return BetResult.refused("insufficient balance");
        }
        balances.put(r.botId(), balance.minus(total));
        betRounds.get(r.botId()).add(r.roundId());
        return BetResult.accepted(fees.total());
    }
}
