package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.brain.BotBrain;
import crowdvsmarket.bots.brain.BotProfile;
import crowdvsmarket.bots.brain.Seeds;
import crowdvsmarket.bots.catalog.CatalogEntry;
import crowdvsmarket.bots.persona.Persona;
import crowdvsmarket.bots.persona.Personas;
import crowdvsmarket.bots.signal.MarketView;
import crowdvsmarket.bots.signal.Signal;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/**
 * Replays the catalog's bots over historical 1-minute candles, round by round, the way the game would settle them.
 *
 * <p><b>Rounds</b> are aligned like the game's: a window of {@code H} minutes starts at a multiple of {@code H} minutes
 * of the epoch (H = 3 is the short round, {@link Interval#HOURLY}, H = 15 the long one, {@link Interval#DAILY}). The
 * bet is decided from the (up to {@value BotBrain#HISTORY_BARS}) candles <i>before</i> the window; the entry price is the
 * open of the window's first minute, the result is the close of its last minute against that open, and liquidation is
 * checked on the whole window's high and low (one aggregate candle).
 *
 * <p><b>Money</b> goes through the engine, as in {@code GameEngine#resolveRound}: {@code Position.openFromMargin} on the
 * market's per-round-length spec, {@code PositionSimulator.simulate}, the economy-stage-A remainder at the candle's
 * extreme on liquidation ({@code LiquidationCalculator.payoutAtCandleExtreme}), the payout cap
 * ({@code EconomyRules.maxPayout}), fees from {@code EconomyRules.fees}. Net result of a bet = payout - stake - fees.
 *
 * <p><b>Fixed stake, persona leverage.</b> Every bet is {@code stake} PLAY (default 100, inside the full-leverage tier);
 * the leverage is what the bot's persona would pick for that stake, with the persona's state (last result and streak)
 * fed from this very replay. How often the persona would play, and on how many markets, is deliberately not simulated:
 * the backtest measures the rule and the leverage, not the participation. A window where the rule is silent is a
 * "no signal" window and no bet is made (the persona's fallback is not used). Crowd strategies need the previous round's
 * majority, which candles do not contain, so they are not replayed.
 */
public final class Backtester {

    /** Default fixed stake in whole PLAY. */
    public static final long DEFAULT_STAKE_PLAY = 100;
    /** The horizons of the backtest in minutes. */
    public static final int[] HORIZONS = {3, 15};

    private static final PlayAmount BANKROLL = PlayAmount.ofWholePlay(1_000_000_000L);

    private final PlayAmount stake;
    private final String personaOverride;
    private final int fixedLeverage;
    private final Consumer<Bet> betSink;

    public Backtester() {
        this(DEFAULT_STAKE_PLAY);
    }

    public Backtester(long stakePlay) {
        this(stakePlay, null, 0, null);
    }

    /**
     * A backtest that compares strategies on equal terms.
     *
     * @param personaOverride  persona id every bot plays with instead of its own catalog persona, or {@code null} for the
     *                         catalog's (its leverage rule and win/loss state then apply to every strategy alike)
     * @param fixedLeverage    leverage of every bet (clamped to {@code [2, cap of the stake's tier]}), or {@code 0} to let
     *                         the persona choose
     * @param betSink          receives every settled bet (for statistics outside this class), or {@code null}
     */
    public Backtester(long stakePlay, String personaOverride, int fixedLeverage, Consumer<Bet> betSink) {
        this.stake = PlayAmount.ofWholePlay(stakePlay);
        if (stake.compareTo(LedgerBook.MIN_STAKE) < 0) {
            throw new IllegalArgumentException("stake below the game's minimum: " + stakePlay);
        }
        if (fixedLeverage != 0 && fixedLeverage < Leverage.MIN) {
            throw new IllegalArgumentException("fixed leverage below the game's minimum: " + fixedLeverage);
        }
        if (personaOverride != null) {
            Personas.byId(personaOverride); // fail early on an unknown id
        }
        this.personaOverride = personaOverride;
        this.fixedLeverage = fixedLeverage;
        this.betSink = betSink;
    }

    /**
     * One settled bet.
     *
     * @param direction +1 for UP, -1 for DOWN
     * @param moveBp    the window's close against its open, in basis points (signed, independent of the bet's direction)
     * @param net       payout - stake - fees, PLAY
     */
    public record Bet(String catalogId, String marketId, int horizonMinutes, long startMs, int direction, double moveBp,
                      int leverage, boolean liquidated, BigDecimal net, BigDecimal fees) {
    }

    /**
     * The outcome of one bot on one market and horizon.
     *
     * @param windows      complete windows seen
     * @param noSignal     windows where the rule was silent (including too short a history)
     * @param bets         windows bet on
     * @param ties         bets whose window closed exactly at its open (neither right nor wrong, as {@code GameEngine})
     * @param hits         bets whose direction matched the window's move
     * @param liquidations bets liquidated inside the window
     * @param pnl          net PLAY over all bets: payout - stake - fees
     * @param fees         fees paid over all bets, PLAY
     * @param leverageSum  sum of the leverages used (divide by {@code bets} for the mean)
     */
    public record Result(String catalogId, String strategyId, String persona, String marketId, int horizonMinutes, int windows,
                         int noSignal, int bets, int ties, int hits, int liquidations, BigDecimal pnl, BigDecimal fees,
                         long leverageSum) {

        /** Bets that had a right or wrong answer. */
        public int decided() {
            return bets - ties;
        }

        /** Share of decided bets that went the right way; NaN without any. */
        public double hitRate() {
            return decided() == 0 ? Double.NaN : (double) hits / decided();
        }

        /** Share of windows where the rule was silent. */
        public double noSignalShare() {
            return windows == 0 ? Double.NaN : (double) noSignal / windows;
        }

        public double meanLeverage() {
            return bets == 0 ? Double.NaN : (double) leverageSum / bets;
        }
    }

    /** How the windows of a market moved: the base rate any "always up" or "always down" rule would see. */
    public record Baseline(String marketId, int horizonMinutes, int windows, int up, int down) {

        public double upShare() {
            return up + down == 0 ? Double.NaN : (double) up / (up + down);
        }
    }

    /** Whether the catalog entry can be replayed from candles alone. */
    public static boolean replayable(CatalogEntry entry) {
        return !entry.strategy().startsWith("crowd-");
    }

    /** Every replayable entry on every market at every horizon. */
    public List<Result> run(List<CatalogEntry> entries, List<BacktestMarket> markets) {
        List<Result> out = new java.util.ArrayList<>();
        for (int h : HORIZONS) {
            for (BacktestMarket m : markets) {
                for (CatalogEntry e : entries) {
                    if (replayable(e)) {
                        out.add(run(e, m, h));
                    }
                }
            }
        }
        return out;
    }

    /** Up/down counts of the windows of every market at every horizon. */
    public List<Baseline> baselines(List<BacktestMarket> markets) {
        List<Baseline> out = new java.util.ArrayList<>();
        for (int h : HORIZONS) {
            for (BacktestMarket m : markets) {
                out.add(baseline(m, h));
            }
        }
        return out;
    }

    public Baseline baseline(BacktestMarket market, int horizonMinutes) {
        int windows = 0;
        int up = 0;
        int down = 0;
        for (Window w : windows(market.bars(), horizonMinutes)) {
            windows++;
            Candle c = w.candle();
            int cmp = c.close().compareTo(c.open());
            if (cmp > 0) {
                up++;
            } else if (cmp < 0) {
                down++;
            }
        }
        return new Baseline(market.id(), horizonMinutes, windows, up, down);
    }

    public Result run(CatalogEntry entry, BacktestMarket market, int horizonMinutes) {
        Interval interval = horizonMinutes == 3 ? Interval.HOURLY : Interval.DAILY;
        BotProfile profile = entry.profile(entry.catalogId());
        Persona persona = personaOverride == null ? profile.persona() : Personas.byId(personaOverride);
        MarketSpec spec = market.spec();
        MarketSpec roundSpec = spec.forInterval(interval);
        List<BarDto> bars = market.bars();

        int windows = 0;
        int noSignal = 0;
        int bets = 0;
        int ties = 0;
        int hits = 0;
        int liquidations = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal feesPaid = BigDecimal.ZERO;
        long leverageSum = 0;
        BotAccount account = BotAccount.fresh(entry.catalogId(), BANKROLL);

        for (Window w : windows(bars, horizonMinutes)) {
            windows++;
            String roundId = market.id() + "-" + horizonMinutes + "m-" + w.startMs();
            SplittableRandom rng = Seeds.rng(entry.catalogId(), market.id(), roundId);
            int from = Math.max(0, w.firstIndex() - BotBrain.HISTORY_BARS);
            MarketView view = MarketView.of(market.id(), bars.subList(from, w.firstIndex()), Optional.empty(), rng);
            Optional<Signal> signal = profile.strategy().evaluate(view);
            if (signal.isEmpty()) {
                noSignal++;
                continue;
            }
            MarketSnapshot snapshot = new MarketSnapshot(spec, interval, roundId, w.startMs(),
                    w.startMs() + horizonMinutes * BarDto.MINUTE_MS, true, LedgerBook.MIN_STAKE);
            int wanted = fixedLeverage != 0 ? fixedLeverage : persona.leverage(snapshot, stake, account, rng);
            int leverage = Math.max(Leverage.MIN, Math.min(snapshot.maxLeverage(stake), wanted));
            Direction direction = signal.get().direction();

            Candle c = w.candle();
            Settled settled = settle(entry.catalogId(), roundId, direction, leverage, stake, c, spec, roundSpec, interval);
            if (settled.liquidated()) {
                liquidations++;
            }
            BigDecimal fees = settled.fees();
            BigDecimal net = settled.net();

            bets++;
            pnl = pnl.add(net);
            feesPaid = feesPaid.add(fees);
            leverageSum += leverage;
            int cmp = c.close().compareTo(c.open());
            if (cmp == 0) {
                ties++;
            } else if ((direction == Direction.UP) == (cmp > 0)) {
                hits++;
            }
            if (betSink != null) {
                double moveBp = c.close().subtract(c.open()).divide(c.open(), java.math.MathContext.DECIMAL64).doubleValue() * 1e4;
                betSink.accept(new Bet(entry.catalogId(), market.id(), horizonMinutes, w.startMs(),
                        direction == Direction.UP ? 1 : -1, moveBp, leverage,
                        settled.liquidated(), net, fees));
            }
            boolean won = net.signum() > 0;
            account = nextAccount(account, won);
        }
        return new Result(entry.catalogId(), profile.strategy().id(), entry.persona(), market.id(), horizonMinutes, windows,
                noSignal, bets, ties, hits, liquidations, pnl, feesPaid, leverageSum);
    }

    /** What one bet on one window paid: all amounts in PLAY, {@code net = payout - stake - fees}. */
    record Settled(BigDecimal payout, BigDecimal fees, boolean liquidated, BigDecimal net) {
    }

    /**
     * Settles a bet on the aggregate candle of its window the way {@code GameEngine#resolveRound} does: the position
     * from the stake as margin at the candle's open, liquidation on the candle's high/low with the remainder at the
     * extreme, the payout cap, the fees of {@link EconomyRules#fees}.
     */
    static Settled settle(String playerId, String roundId, Direction direction, int leverage, PlayAmount stake, Candle c,
                          MarketSpec spec, MarketSpec roundSpec, Interval interval) {
        Position position = Position.openFromMargin(playerId, roundId, direction, new Leverage(leverage), stake.toMoney(),
                c.open(), roundSpec);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, List.of(c));
        BigDecimal payout;
        boolean liquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;
        if (outcome instanceof PositionSimulator.Outcome.Liquidated liq) {
            payout = LiquidationCalculator.payoutAtCandleExtreme(position.size(), position.entryPrice(),
                    position.leverage(), direction.toSide(), liq.extremePrice());
        } else {
            BigDecimal margin = LiquidationCalculator.initialMargin(position.size(), position.entryPrice(), position.leverage());
            payout = margin.add(((PositionSimulator.Outcome.Settled) outcome).pnl()).max(BigDecimal.ZERO);
        }
        BigDecimal cap = EconomyRules.maxPayout(stake).toMoney().setScale(2, RoundingMode.DOWN);
        payout = payout.min(cap);
        BigDecimal fees = EconomyRules.fees(spec, interval, leverage, stake).total().toMoney();
        return new Settled(payout, fees, liquidated, payout.subtract(stake.toMoney()).subtract(fees));
    }

    private static BotAccount nextAccount(BotAccount a, boolean won) {
        LastOutcome now = won ? LastOutcome.WIN : LastOutcome.LOSS;
        int streak = a.lastOutcome() == now ? a.streak() + 1 : 1;
        return new BotAccount(a.botId(), a.balance(), now, streak, a.betRoundIds());
    }

    /** One complete round window: where it starts, the index of its first candle, and its aggregate candle. */
    record Window(long startMs, int firstIndex, Candle candle) {
    }

    /** The complete windows (every minute present) aligned to multiples of {@code horizonMinutes}. */
    static List<Window> windows(List<BarDto> bars, int horizonMinutes) {
        long span = horizonMinutes * BarDto.MINUTE_MS;
        Map<Long, Integer> index = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            index.put(bars.get(i).openTimeMs(), i);
        }
        List<Window> out = new java.util.ArrayList<>();
        if (bars.isEmpty()) {
            return out;
        }
        long first = Math.ceilDiv(bars.get(0).openTimeMs(), span) * span;
        long last = bars.get(bars.size() - 1).openTimeMs();
        for (long s = first; s + span - BarDto.MINUTE_MS <= last; s += span) {
            Integer i0 = index.get(s);
            if (i0 == null || i0 + horizonMinutes > bars.size()) {
                continue;
            }
            boolean complete = true;
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (int k = 0; k < horizonMinutes; k++) {
                BarDto b = bars.get(i0 + k);
                if (b.openTimeMs() != s + k * BarDto.MINUTE_MS) {
                    complete = false;
                    break;
                }
                high = Math.max(high, b.high());
                low = Math.min(low, b.low());
            }
            if (!complete) {
                continue;
            }
            BarDto firstBar = bars.get(i0);
            BarDto lastBar = bars.get(i0 + horizonMinutes - 1);
            Candle candle = new Candle(s, s + span - 1, BigDecimal.valueOf(firstBar.open()), BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low), BigDecimal.valueOf(lastBar.close()), BigDecimal.ZERO, 0);
            out.add(new Window(s, i0, candle));
        }
        return out;
    }
}
