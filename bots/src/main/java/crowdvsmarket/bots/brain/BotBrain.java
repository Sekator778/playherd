package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.persona.MarketCandidate;
import crowdvsmarket.bots.persona.NoSignalPolicy;
import crowdvsmarket.bots.persona.Persona;
import crowdvsmarket.bots.persona.Sizing;
import crowdvsmarket.bots.signal.MarketView;
import crowdvsmarket.bots.signal.Signal;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.random.RandomGenerator;

/**
 * The bot brain, a pure function: snapshots of all markets in, bet intents out. No clock, no network, no field that
 * changes; the randomness of every decision is seeded from {@code hash(botId, marketId, roundId)} ({@link Seeds}), so
 * the same inputs always give the same intents, in any process, in any number of instances.
 *
 * <p>Order of a decision (docs/research/bots_module_design.md section 3): the persona picks the markets it plays this
 * round; for each, the strategy gives a signal; the persona sizes stake and leverage within the market's terms
 * ({@code EconomyRules} tiers, minimum stake, balance). No signal: the persona skips the market or bets on a fallback
 * (the previous majority, else a coin flip).
 */
public final class BotBrain {

    /** Candles the brain looks at (the server's read-model gives at most this many). */
    public static final int HISTORY_BARS = 100;
    /** Candles over which "the market moved sharply" is measured when a persona picks markets. */
    static final int MOVE_BARS = 5;

    /**
     * @param markets     the current round of every market
     * @param bars        candles by market id, oldest first (a missing entry means no history)
     * @param majorities  the previous round's majority by market id (a missing entry means unknown)
     */
    public List<BetIntent> decide(BotProfile profile, BotAccount account, List<MarketSnapshot> markets,
                                  Map<String, List<BarDto>> bars, Map<String, Optional<Direction>> majorities) {
        List<MarketCandidate> candidates = markets.stream()
                .filter(m -> m.open() && !account.betRoundIds().contains(m.roundId()))
                .sorted(Comparator.comparing(MarketSnapshot::marketId))
                .map(m -> new MarketCandidate(m, recentMove(bars.getOrDefault(m.marketId(), List.of()))))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        String rounds = String.join(",", candidates.stream().map(c -> c.market().marketId() + ":" + c.market().roundId()).toList());
        RandomGenerator pickRng = Seeds.rng(profile.botId(), "*", rounds);
        List<MarketCandidate> picked = profile.persona().pickMarkets(candidates, account, pickRng);

        List<BetIntent> intents = new ArrayList<>();
        BotAccount current = account;
        for (MarketCandidate c : picked) {
            MarketSnapshot m = c.market();
            Optional<BetIntent> intent = decideMarket(profile, current, m,
                    bars.getOrDefault(m.marketId(), List.of()),
                    majorities.getOrDefault(m.marketId(), Optional.empty()));
            if (intent.isPresent()) {
                BetIntent i = intent.get();
                intents.add(i);
                Fees fees = i.mode() == BetMode.FIXED ? EconomyRules.fixedFees(i.stake()) : m.fees(i.leverage(), i.stake());
                PlayAmount spent = i.stake().plus(fees.total());
                current = current.withBalance(current.balance().minus(spent));
            }
        }
        return intents;
    }

    /** The decision for one chosen market: signal (or fallback), then sizing. Empty when the bot skips it. */
    public Optional<BetIntent> decideMarket(BotProfile profile, BotAccount account, MarketSnapshot market,
                                            List<BarDto> bars, Optional<Direction> majority) {
        RandomGenerator rng = Seeds.rng(profile.botId(), market.marketId(), market.roundId());
        MarketView view = MarketView.of(market.marketId(), bars, majority, rng);
        Optional<Signal> signal = profile.strategy().evaluate(view);
        boolean fallback = false;
        if (signal.isEmpty()) {
            if (profile.persona().noSignalPolicy() == NoSignalPolicy.SKIP) {
                return Optional.empty();
            }
            fallback = true;
            signal = Optional.of(majority.map(d -> new Signal(d, 0.0))
                    .orElseGet(() -> new Signal(rng.nextBoolean() ? Direction.UP : Direction.DOWN, 0.0)));
        }
        Persona persona = profile.persona();
        Optional<Sizing> sizing = persona.size(market, account, rng);
        if (sizing.isEmpty()) {
            return Optional.empty();
        }
        Signal s = signal.get();
        Sizing z = sizing.get();
        // D-106: the bot's preferred mode, except where the market refuses it (odds markets play the percentage mode only)
        BetMode mode = profile.preferredMode() == BetMode.FIXED && !market.odds() ? BetMode.FIXED : BetMode.PERCENTAGE;
        return Optional.of(new BetIntent(profile.botId(), market.marketId(), market.roundId(), s.direction(), z.stake(),
                z.leverage(), s.confidence(), fallback, mode));
    }

    /** |close[last] / close[last - 5] - 1|, 0 when the history is shorter. */
    static double recentMove(List<BarDto> bars) {
        int n = bars.size();
        if (n <= MOVE_BARS) {
            return 0.0;
        }
        double now = bars.get(n - 1).close();
        double then = bars.get(n - 1 - MOVE_BARS).close();
        return Math.abs(now / then - 1.0);
    }
}
