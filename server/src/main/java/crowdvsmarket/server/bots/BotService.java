package crowdvsmarket.server.bots;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.service.GameService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.jboss.logging.Logger;

/**
 * Р-46: 20 server-side bot players, betting at a random moment of each betting window
 * ({@code RoundSchedulerService} calls {@link #tick} every scheduler tick). Bots are ordinary
 * players from the engine's point of view — they go through the exact same {@code GameService}
 * validated path (min stake, one bet per round, leverage bounds) as a human player, just without
 * ever signing a wallet challenge (see {@code GameService#createBotPlayer}).
 *
 * <p><b>Markets (Т-8, docs/research/markets_architecture.md §2):</b> in every round of a series
 * ({@code Interval}) each bot bets on {@code herd.bots.markets-per-round} (default 3) distinct
 * random active markets — fewer when there are fewer markets — placing one bet in each, at a random
 * moment of that market's betting window. Leverage stays within the market's cap, and the "previous
 * majority" the contrarian/follower strategies look at is the previous round's majority OF THAT
 * MARKET. In {@code mode=live} a bot never bets on a market without a fresh price (none at all, or
 * a stale one): such a market is left out of the pick, and checked again at the moment of the bet.
 */
@ApplicationScoped
public class BotService {

    private static final Logger LOG = Logger.getLogger(BotService.class);
    private static final BotStrategy[] STRATEGIES = BotStrategy.values();
    /** A plan (the bets of one betting window) is remembered this long past the window's start, so a
     *  window is never planned twice; older ones are dropped. */
    private static final long PLAN_RETENTION_MS = 3_600_000L;

    @Inject
    GameConfig config;
    @Inject
    PlayerDirectory players;
    @Inject
    GameService gameService;
    @Inject
    RoundProgress roundProgress;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    MarketPrices marketPrices;

    private final Random random = new Random();

    /** One bet a bot has decided to place: on which market's round, and when. */
    private record Shot(String botId, String marketId, Interval interval, String roundId, long bettingClosesAtMs,
                        long fireAtMs) {
    }

    /** One betting window of one interval: the unit that is planned exactly once. */
    private record PlanKey(Interval interval, long bettingStartMs) {
    }

    private final List<Shot> shots = new ArrayList<>();
    private final Set<PlanKey> planned = new HashSet<>();
    /** The previous round's crowd majority per (market, interval) — one database read per plan, not
     *  one per bot. */
    private final Map<String, Optional<Direction>> previousMajority = new HashMap<>();

    public synchronized void ensureBotsExist(long nowMs) {
        int existing = players.bots().size();
        int target = config.botsCount();
        for (int i = existing; i < target; i++) {
            BotStrategy strategy = STRATEGIES[i % STRATEGIES.length];
            String nickname = "Bot " + (i + 1) + " (" + strategy.label() + ")";
            gameService.createBotPlayer(nickname, strategy.name(), nowMs);
        }
    }

    public synchronized void tick(long nowMs) {
        List<Market> running = gameService.runningMarkets();
        for (Interval interval : Interval.values()) {
            planBettingWindows(interval, running, nowMs);
        }
        fireDueBets(nowMs);
    }

    /** Plans every betting window of {@code interval} that is open now and has not been planned yet:
     *  markets whose open betting round closes at the same instant form one window, and each bot
     *  picks its markets among those. */
    private void planBettingWindows(Interval interval, List<Market> running, long nowMs) {
        Map<Long, List<RoundWindow>> windowsByStart = new TreeMap<>();
        for (Market market : running) {
            Optional<RoundWindow> cursor = roundProgress.findNext(market.id(), interval);
            if (cursor.isEmpty() || !hasFreshPrice(market)) {
                continue;
            }
            RoundWindow betting = RoundClock.next(cursor.get());
            if (betting.startMs() <= nowMs) {
                continue; // the scheduler has not caught up yet; betting on this window is over
            }
            windowsByStart.computeIfAbsent(betting.startMs(), k -> new ArrayList<>()).add(betting);
        }
        for (Map.Entry<Long, List<RoundWindow>> entry : windowsByStart.entrySet()) {
            if (planned.add(new PlanKey(interval, entry.getKey()))) {
                previousMajority.clear();
                scheduleBots(interval, entry.getValue());
            }
        }
        planned.removeIf(key -> key.bettingStartMs() < nowMs - PLAN_RETENTION_MS);
    }

    private void scheduleBots(Interval interval, List<RoundWindow> windows) {
        int perBot = Math.min(Math.max(0, config.botsMarketsPerRound()), windows.size());
        for (PlayerDirectory.Row bot : players.bots()) {
            List<RoundWindow> picks = new ArrayList<>(windows);
            Collections.shuffle(picks, random);
            for (RoundWindow window : picks.subList(0, perBot)) {
                long duration = window.endMs() - window.startMs();
                // Bug fix (bots stopped betting): betting for `window` is open for the whole PREVIOUS
                // round's duration (Р-19) — i.e. from window.startMs() - duration (when the currently
                // running round started) up to window.startMs() (when betting closes and `window`
                // itself starts running). A fire time computed as window.startMs() + offset would land
                // AFTER betting had closed and GameService#placeBet would always reject it.
                long bettingOpensAt = window.startMs() - duration;
                long offset = (long) (random.nextDouble() * duration * 0.9); // leave margin before close
                shots.add(new Shot(bot.playerId(), window.market(), interval, window.roundId(), window.startMs(),
                        bettingOpensAt + offset));
            }
        }
    }

    private void fireDueBets(long nowMs) {
        Iterator<Shot> iterator = shots.iterator();
        while (iterator.hasNext()) {
            Shot shot = iterator.next();
            if (nowMs >= shot.bettingClosesAtMs()) {
                iterator.remove(); // betting closed before it fired
            } else if (shot.fireAtMs() <= nowMs) {
                iterator.remove(); // fire exactly once per round and market
                placeBotBet(shot, nowMs);
            }
        }
    }

    private void placeBotBet(Shot shot, long nowMs) {
        try {
            PlayerDirectory.Row bot = players.byId(shot.botId()).orElse(null);
            Market market = marketRegistry.byId(shot.marketId()).orElse(null);
            if (bot == null || market == null || !market.isActive() || !hasFreshPrice(market)) {
                return; // reset since planning, closed, or its price went away
            }
            BotStrategy strategy = BotStrategy.valueOf(bot.botStrategy());
            Optional<Direction> previous = previousMajority.computeIfAbsent(market.id() + "/" + shot.interval(),
                    k -> roundResults.lastMajorityDirection(market.id(), shot.interval())
                            .map(d -> Direction.valueOf(d.name())));
            Direction direction = strategy.chooseDirection(random, previous);
            // bots follow the same rules as any player (Р-92): the stake is chosen first, because the leverage allowed
            // depends on it (the market's cap for this round length, lowered by the stake tier)
            BigDecimal stakeAmount = strategy.chooseStake(random);
            int allowed = EconomyRules.maxLeverage(market.spec(), shot.interval(), PlayAmount.fromMoney(stakeAmount));
            int leverage = strategy.chooseLeverage(random, allowed);
            String stake = stakeAmount.toPlainString();
            Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(shot.roundId(),
                    Enums.Direction.valueOf(direction.name()), leverage, stake);
            gameService.placeBet(bot.playerId(), req, nowMs);
        } catch (ApiException e) {
            // Expected now and then (insufficient balance, a round that has just closed): not worth a stack.
            LOG.debugf("bot %s could not bet on %s: %s", shot.botId(), shot.roundId(), e.getMessage());
        } catch (Exception e) {
            LOG.warnf(e, "bot %s failed to place a bet on %s (skipped this round)", shot.botId(), shot.roundId());
        }
    }

    /** In {@code mode=live}: a price exists and is not stale (docs/research/markets_architecture.md
     *  §3.2 п.3). In {@code mode=replay} there is no price feed to ask — BTC always has one. */
    private boolean hasFreshPrice(Market market) {
        if (config.mode() != GameMode.LIVE) {
            return true;
        }
        // the primary exchange's price while it is fresh, else the backup's (Р-76): a market whose primary is down still bets
        return marketPrices.latest(market).map(quote -> !quote.stale()).orElse(false);
    }
}
