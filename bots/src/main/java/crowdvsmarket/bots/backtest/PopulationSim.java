package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.brain.BetIntent;
import crowdvsmarket.bots.brain.BotBrain;
import crowdvsmarket.bots.brain.BotProfile;
import crowdvsmarket.bots.brain.Seeds;
import crowdvsmarket.bots.catalog.CatalogEntry;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * The bot population over historical candles with the treasury as the counterparty of every bet: what the economy of the
 * game would do to the treasury if these bots ran. Where {@link Backtester} measures one rule at a fixed stake, this runs
 * the whole {@link BotBrain} (market choice, participation, stake, leverage after wins and losses) for every bot at the
 * same time, settles through the engine ({@link Backtester#settle}) and applies the bot economy of the game:
 *
 * <ul>
 *   <li>a bet moves {@code stake + fees} out of the bot's balance at the start of its window, the payout comes back at the
 *       window's end ({@code payout - stake - fees} is the bot's net, its negative the treasury's);
 *   <li>a bot with no open bet and less than {@code retireBelow} PLAY retires (its remainder goes to the treasury) and
 *       {@code respawnDelayMs} later a new bot of the same catalog entry arrives with a starter pack of {@code pack} PLAY
 *       paid by the treasury (economy plan B4);
 *   <li>the bots' fees are swept to the treasury daily, so they count as treasury income here.
 * </ul>
 *
 * Treasury change of a run = {@code stakes + fees - payouts - packs + remainders}; by conservation it equals the negative
 * change of the bots' total balance. Crowd strategies need the previous round's majority, which candles do not contain:
 * it is drawn as a fair coin per (market, round) (an assumption, the crowd is unknown). {@code peekProbability} gives
 * every bot a sure bet on the realised direction with that probability (a stress test: a bot with an edge); 0 for none.
 */
public final class PopulationSim {

    private static final long MINUTE = BarDto.MINUTE_MS;
    private static final long DAY = 86_400_000L;

    /**
     * @param bots            the catalog entries; each is played {@code copies} times (as separate bots)
     * @param markets         markets with their candles (see {@link #loop} for a longer history)
     * @param salt            replicate id: changes every seeded decision and, with it, the random draws
     * @param peekProbability chance per bet that the direction is replaced by the realised one (0 = off)
     */
    public record Config(List<CatalogEntry> bots, int copies, List<BacktestMarket> markets, long salt, double peekProbability,
                         PlayAmount pack, PlayAmount retireBelow, long respawnDelayMs) {

        public static Config of(List<CatalogEntry> bots, List<BacktestMarket> markets, long salt) {
            return new Config(bots, 1, markets, salt, 0.0, PlayAmount.ofWholePlay(10_000), PlayAmount.ofWholePlay(100), 3_600_000L);
        }
    }

    /** Flows of one UTC-aligned day of the run (day 0 starts at the first window), PLAY. */
    public record Day(int day, long bets, double stakes, double fees, double payouts, double packs, double remainders,
                      int retirements, int liquidations) {

        /** What the treasury gained from the day's bets alone: stakes + fees - payouts. */
        public double tradingGain() {
            return stakes + fees - payouts;
        }

        /** Treasury change of the day: trading gain - packs + remainders. */
        public double treasuryChange() {
            return tradingGain() - packs + remainders;
        }
    }

    /** One bot life: from its (re)birth to its retirement ({@code endMs} -1 while it was still alive at the end). */
    public record Life(String catalogId, long startMs, long endMs, long bets, double net) {
    }

    /**
     * @param maxDrawdown the largest fall of the treasury below its running maximum during the run, PLAY
     * @param finalChange the treasury change over the whole run, PLAY
     */
    public record Result(List<Day> days, List<Life> lives, double maxDrawdown, double finalChange) {
    }

    private static final class Bot {
        final CatalogEntry entry;
        final int copy;
        int generation;
        BotProfile profile;
        BotAccount account;
        int open;
        boolean retired;
        long retiredAtMs;
        long lifeStartMs;
        long lifeBets;
        double lifeNet;

        Bot(CatalogEntry entry, int copy) {
            this.entry = entry;
            this.copy = copy;
        }
    }

    private record Pending(long endMs, int bot, double payout, double net, boolean won) {
    }

    private static final class DayAcc {
        long bets;
        double stakes;
        double fees;
        double payouts;
        double packs;
        double remainders;
        int retirements;
        int liquidations;
    }

    /**
     * The history repeated {@code times} times end to end, each copy rescaled so that its first open is the last close of
     * the one before (returns are kept, the seam has no jump). A lengthening of the history for bots whose lifetime is
     * longer than the data; the copies are not independent price paths.
     */
    public static List<BarDto> loop(List<BarDto> bars, int times) {
        List<BarDto> out = new ArrayList<>(bars);
        long span = bars.get(bars.size() - 1).openTimeMs() + MINUTE - bars.get(0).openTimeMs();
        double lastClose = bars.get(bars.size() - 1).close();
        for (int k = 1; k < times; k++) {
            double scale = lastClose / bars.get(0).open();
            for (BarDto b : bars) {
                out.add(new BarDto(b.openTimeMs() + k * span, b.open() * scale, b.high() * scale, b.low() * scale, b.close() * scale));
            }
            lastClose = bars.get(bars.size() - 1).close() * scale;
        }
        return out;
    }

    /** The same candles with every open time moved by {@code minutes} (to move the round grid against the price path). */
    public static List<BarDto> shifted(List<BarDto> bars, int minutes) {
        List<BarDto> out = new ArrayList<>(bars.size());
        for (BarDto b : bars) {
            out.add(new BarDto(b.openTimeMs() - minutes * MINUTE, b.open(), b.high(), b.low(), b.close()));
        }
        return out;
    }

    public Result run(Config cfg) {
        BotBrain brain = new BotBrain();
        PlayAmount minStake = LedgerBook.MIN_STAKE;
        int[] horizons = {3, 15};

        // windows by start time, per horizon and market
        Map<Long, Map<Integer, List<Entry>>> byTime = new TreeMap<>();
        for (BacktestMarket m : cfg.markets()) {
            for (int h : horizons) {
                for (Backtester.Window w : Backtester.windows(m.bars(), h)) {
                    byTime.computeIfAbsent(w.startMs(), k -> new HashMap<>()).computeIfAbsent(h, k -> new ArrayList<>())
                            .add(new Entry(m, w));
                }
            }
        }
        if (byTime.isEmpty()) {
            return new Result(List.of(), List.of(), 0, 0);
        }
        long t0 = byTime.keySet().iterator().next();

        List<Bot> bots = new ArrayList<>();
        for (CatalogEntry e : cfg.bots()) {
            for (int c = 0; c < cfg.copies(); c++) {
                Bot b = new Bot(e, c);
                spawn(b, cfg, t0);
                bots.add(b);
            }
        }
        Map<Integer, DayAcc> days = new TreeMap<>();
        List<Life> lives = new ArrayList<>();
        PriorityQueue<Pending> pending = new PriorityQueue<>(java.util.Comparator.comparingLong(Pending::endMs));
        double packPlay = cfg.pack().toMoney().doubleValue();
        // the starter packs of the first generation are not a treasury flow of the run: the bots start funded
        double treasury = 0;
        double peak = 0;
        double maxDrawdown = 0;
        SplittableRandom peekRng = new SplittableRandom(Seeds.of("peek", Long.toString(cfg.salt()), "x"));

        for (Map.Entry<Long, Map<Integer, List<Entry>>> step : byTime.entrySet()) {
            long t = step.getKey();
            // 1. settle what has ended
            while (!pending.isEmpty() && pending.peek().endMs() <= t) {
                Pending p = pending.poll();
                Bot b = bots.get(p.bot());
                PlayAmount payout = PlayAmount.fromMoney(BigDecimal.valueOf(p.payout()).setScale(3, java.math.RoundingMode.HALF_EVEN));
                b.account = b.account.withBalance(b.account.balance().plus(payout));
                LastOutcome now = p.won() ? LastOutcome.WIN : LastOutcome.LOSS;
                int streak = b.account.lastOutcome() == now ? b.account.streak() + 1 : 1;
                b.account = new BotAccount(b.account.botId(), b.account.balance(), now, streak, b.account.betRoundIds());
                b.open--;
                b.lifeNet += p.net();
                acc(days, t0, p.endMs()).payouts += p.payout();
                treasury -= p.payout();
            }
            // 2. retire the broke, 3. bring back those whose hour is over
            for (Bot b : bots) {
                if (!b.retired && b.open == 0 && b.account.balance().compareTo(cfg.retireBelow()) < 0) {
                    b.retired = true;
                    b.retiredAtMs = t;
                    double rem = b.account.balance().toMoney().doubleValue();
                    DayAcc d = acc(days, t0, t);
                    d.retirements++;
                    d.remainders += rem;
                    treasury += rem;
                    lives.add(new Life(b.entry.catalogId(), b.lifeStartMs, t, b.lifeBets, b.lifeNet));
                } else if (b.retired && b.retiredAtMs + cfg.respawnDelayMs() <= t) {
                    spawn(b, cfg, t);
                    acc(days, t0, t).packs += packPlay;
                    treasury -= packPlay;
                }
            }
            // 4. decisions for the windows that open now
            for (int h : horizons) {
                List<Entry> now = step.getValue().get(h);
                if (now == null) {
                    continue;
                }
                Interval interval = h == 3 ? Interval.HOURLY : Interval.DAILY;
                List<MarketSnapshot> snaps = new ArrayList<>();
                Map<String, List<BarDto>> bars = new HashMap<>();
                Map<String, Optional<Direction>> majorities = new HashMap<>();
                Map<String, Entry> byMarket = new HashMap<>();
                for (Entry e : now) {
                    String id = e.market().id();
                    String roundId = id + "-" + h + "m-" + e.window().startMs();
                    snaps.add(new MarketSnapshot(e.market().spec(), interval, roundId, e.window().startMs(),
                            e.window().startMs() + h * MINUTE, true, minStake));
                    int from = Math.max(0, e.window().firstIndex() - BotBrain.HISTORY_BARS);
                    bars.put(id, e.market().bars().subList(from, e.window().firstIndex()));
                    majorities.put(id, Optional.of(Seeds.rng("crowd" + cfg.salt(), id, roundId).nextBoolean() ? Direction.UP : Direction.DOWN));
                    byMarket.put(id, e);
                }
                for (int i = 0; i < bots.size(); i++) {
                    Bot b = bots.get(i);
                    if (b.retired) {
                        continue;
                    }
                    for (BetIntent intent : brain.decide(b.profile, b.account, snaps, bars, majorities)) {
                        Entry e = byMarket.get(intent.marketId());
                        Candle c = e.window().candle();
                        Direction dir = intent.direction();
                        int cmp = c.close().compareTo(c.open());
                        if (cfg.peekProbability() > 0 && cmp != 0 && peekRng.nextDouble() < cfg.peekProbability()) {
                            dir = cmp > 0 ? Direction.UP : Direction.DOWN;
                        }
                        MarketSpec spec = e.market().spec();
                        Backtester.Settled s = Backtester.settle(b.account.botId(), intent.roundId(), dir, intent.leverage(),
                                intent.stake(), c, spec, spec.forInterval(interval));
                        PlayAmount out = intent.stake().plus(PlayAmount.fromMoney(s.fees()));
                        b.account = b.account.withBalance(b.account.balance().minus(out));
                        b.open++;
                        b.lifeBets++;
                        DayAcc d = acc(days, t0, t);
                        d.bets++;
                        d.stakes += intent.stake().toMoney().doubleValue();
                        d.fees += s.fees().doubleValue();
                        if (s.liquidated()) {
                            d.liquidations++;
                        }
                        treasury += intent.stake().toMoney().doubleValue() + s.fees().doubleValue();
                        pending.add(new Pending(e.window().startMs() + h * MINUTE, i, s.payout().doubleValue(),
                                s.net().doubleValue(), s.net().signum() > 0));
                    }
                }
            }
            peak = Math.max(peak, treasury);
            maxDrawdown = Math.max(maxDrawdown, peak - treasury);
        }
        // the last windows' bets are settled too, so every stake has its payout in the totals
        while (!pending.isEmpty()) {
            Pending p = pending.poll();
            Bot b = bots.get(p.bot());
            b.lifeNet += p.net();
            acc(days, t0, p.endMs()).payouts += p.payout();
            treasury -= p.payout();
        }
        maxDrawdown = Math.max(maxDrawdown, peak - treasury);
        for (Bot b : bots) {
            lives.add(new Life(b.entry.catalogId(), b.lifeStartMs, b.retired ? b.retiredAtMs : -1, b.lifeBets, b.lifeNet));
        }
        List<Day> out = new ArrayList<>();
        int lastDay = days.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (int d = 0; d <= lastDay; d++) {
            DayAcc a = days.getOrDefault(d, new DayAcc());
            out.add(new Day(d, a.bets, a.stakes, a.fees, a.payouts, a.packs, a.remainders, a.retirements, a.liquidations));
        }
        return new Result(out, lives, maxDrawdown, treasury);
    }

    private record Entry(BacktestMarket market, Backtester.Window window) {
    }

    private static DayAcc acc(Map<Integer, DayAcc> days, long t0, long t) {
        return days.computeIfAbsent((int) Math.max(0, (t - t0) / DAY), k -> new DayAcc());
    }

    private static void spawn(Bot b, Config cfg, long nowMs) {
        String id = b.entry.catalogId() + "#" + b.copy + "." + b.generation++ + "~" + cfg.salt();
        b.profile = b.entry.profile(id);
        b.account = BotAccount.fresh(id, cfg.pack());
        b.open = 0;
        b.retired = false;
        b.lifeStartMs = nowMs;
        b.lifeBets = 0;
        b.lifeNet = 0;
    }
}
