package crowdvsmarket.server.bots;

import crowdvsmarket.bots.brain.BotBrain;
import crowdvsmarket.bots.brain.BotProfile;
import crowdvsmarket.bots.brain.BotRunner;
import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
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
 * D-46: 20 server-side bot players, betting at a random moment of each betting window
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
 *
 * <p><b>Two brains (docs/research/bots_module_design.md section 7).</b> {@code herd.bots.engine=catalog} (the default): the bots of the
 * {@code bots} module's catalog - 21 of them, each a rule on the candles read-model and a persona - decide with
 * {@link BotBrain} through the ports of {@link ServerBotPorts}; each bot gets one shot per betting window of a series, at a random
 * moment of it, and its persona picks the markets, the sides, the stakes and the leverage. {@code legacy}: the five
 * {@link BotStrategy} kinds, as before. The choice is per bot as well: a bot whose {@code players.bot_strategy} is not a catalog id
 * (a bot of the old kind, before it is migrated or when the operator rolled back) plays the legacy way.
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
    private static final Catalog CATALOG = Catalog.load();
    private static final BotBrain BRAIN = new BotBrain();
    /** The profile of each catalog bot ({@code botId|catalogId}); a profile is stateless, so it is built once. */
    private final Map<String, BotProfile> profiles = new HashMap<>();

    /** One bet a bot has decided to place: on which market's round, and when. For a catalog bot {@code marketId} and
     *  {@code roundId} are {@code null}: its shot is one pass of the brain over the whole window group
     *  ({@code bettingClosesAtMs} is the start of that group's betting round). */
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

    /**
     * Keeps {@code herd.bots.count} LIVE bots (economy stage B, plan B4): a bot that retired is not live. A retired bot is replaced
     * {@code herd.bots.respawn-delay-ms} (1 hour) after it retired, by a bot of the same strategy under a new generated name; a missing
     * bot that no retirement explains (a first start, a raised count) is created at once. Idempotent.
     *
     * <p>With the catalog engine "the same strategy" is the same catalog slot, and the number of live bots is at most the catalog's
     * 21; bots of the old kinds are first given a free slot ({@link #migrateToCatalog}), keeping their players and names.
     */
    public synchronized void ensureBotsExist(long nowMs) {
        if (config.botsCatalogEngine()) {
            ensureCatalogBots(nowMs);
        } else {
            ensureLegacyBots(nowMs);
        }
    }

    private void ensureLegacyBots(long nowMs) {
        List<PlayerDirectory.Row> all = players.bots();
        int live = (int) all.stream().filter(b -> !b.isRetired()).count();
        int missing = config.botsCount() - live;
        if (missing <= 0) {
            return;
        }
        List<PlayerDirectory.Row> pending = all.stream().filter(b -> b.isRetired() && !b.replaced())
                .sorted(java.util.Comparator.comparingLong(PlayerDirectory.Row::retiredAtMs)).toList();
        int serve = Math.min(pending.size(), missing);
        int immediate = missing - serve;
        for (int i = 0; i < immediate; i++) {
            BotStrategy strategy = STRATEGIES[(live + i) % STRATEGIES.length];
            String nickname = "Bot " + (all.size() + i + 1) + " (" + strategy.label() + ")";
            gameService.createBotPlayer(nickname, strategy.name(), nowMs);
        }
        java.util.Set<String> taken = null;
        for (int i = 0; i < serve; i++) {
            PlayerDirectory.Row retired = pending.get(i);
            if (retired.retiredAtMs() + config.botsRespawnDelayMs() > nowMs) {
                break; // the oldest is not an hour old yet, none after it is
            }
            BotStrategy strategy = legacyStrategyOf(retired.botStrategy());
            if (taken == null) {
                taken = new java.util.HashSet<>(gameService.nicknamesInUse());
            }
            String nickname = BotNames.next(random, taken, strategy.label());
            taken.add(nickname);
            gameService.createBotPlayer(nickname, strategy.name(), nowMs, retired.playerId());
        }
    }

    private void ensureCatalogBots(long nowMs) {
        List<CatalogEntry> slots = CATALOG.bots();
        int target = Math.min(config.botsCount(), slots.size());
        if (target <= 0) {
            return;
        }
        migrateToCatalog();
        relabelCatalogBots();
        List<PlayerDirectory.Row> all = players.bots();
        int live = (int) all.stream().filter(b -> !b.isRetired()).count();
        int missing = target - live;
        if (missing <= 0) {
            return;
        }
        List<PlayerDirectory.Row> pending = all.stream().filter(b -> b.isRetired() && !b.replaced())
                .sorted(java.util.Comparator.comparingLong(PlayerDirectory.Row::retiredAtMs)).toList();
        int serve = Math.min(pending.size(), missing);
        int immediate = missing - serve;
        // the slots in use: held by a live bot, or kept for a retired bot that is waiting to be replaced
        Set<String> held = catalogSlotsHeld(all, true);
        Set<String> names = new HashSet<>(gameService.nicknamesInUse());
        for (int i = 0; i < immediate; i++) {
            Optional<CatalogEntry> slot = freeSlot(held, null);
            if (slot.isEmpty()) {
                break;
            }
            held.add(slot.get().catalogId());
            createCatalogBot(slot.get(), names, nowMs, null);
        }
        Set<String> liveIds = catalogSlotsHeld(all, false);
        for (int i = 0; i < serve; i++) {
            PlayerDirectory.Row retired = pending.get(i);
            if (retired.retiredAtMs() + config.botsRespawnDelayMs() > nowMs) {
                break; // the oldest is not an hour old yet, none after it is
            }
            // the replacement takes the slot of the bot it replaces; when that slot is gone (an old kind of bot, a slot taken since), a free one
            Optional<CatalogEntry> slot = CATALOG.byId(retired.botStrategy()).filter(e -> !liveIds.contains(e.catalogId()));
            if (slot.isEmpty()) {
                slot = freeSlot(held, legacyOrNull(retired.botStrategy()));
            }
            if (slot.isEmpty()) {
                break;
            }
            held.add(slot.get().catalogId());
            liveIds.add(slot.get().catalogId());
            createCatalogBot(slot.get(), names, nowMs, retired.playerId());
        }
    }

    private void createCatalogBot(CatalogEntry slot, Set<String> names, long nowMs, String replacesPlayerId) {
        String nickname = BotNames.next(random, names, slot.name());
        names.add(nickname);
        if (replacesPlayerId == null) {
            gameService.createBotPlayer(nickname, slot.catalogId(), nowMs);
        } else {
            gameService.createBotPlayer(nickname, slot.catalogId(), nowMs, replacesPlayerId);
        }
    }

    /**
     * Gives every live bot of an old kind (a {@link BotStrategy} name in {@code players.bot_strategy}) a free catalog slot, keeping the
     * player, the name, the balance and the history; the slot of the same family when there is one (a contrarian takes a contrarian
     * slot), else any free one. Bots in excess of the 21 slots stay as they are and play the legacy way.
     */
    private void migrateToCatalog() {
        List<PlayerDirectory.Row> all = players.bots();
        List<PlayerDirectory.Row> old = all.stream().filter(b -> !b.isRetired() && CATALOG.byId(b.botStrategy()).isEmpty())
                .sorted(java.util.Comparator.comparingInt(PlayerDirectory.Row::playerIndex)).toList();
        if (old.isEmpty()) {
            return;
        }
        Set<String> held = catalogSlotsHeld(all, true);
        for (PlayerDirectory.Row bot : old) {
            Optional<CatalogEntry> slot = freeSlot(held, legacyOrNull(bot.botStrategy()));
            if (slot.isEmpty()) {
                return;
            }
            held.add(slot.get().catalogId());
            gameService.assignBotStrategy(bot.playerId(), slot.get().catalogId());
            LOG.infof("bot %s (%s) now plays as %s", bot.playerId(), bot.nickname(), slot.get().catalogId());
        }
    }

    /**
     * Puts the name of its catalog slot into the label of every live catalog bot: a bot moved from an old kind keeps its number or its
     * generated name, and its {@code "(Trend Follower)"} becomes the name of what it now plays. Renames only what differs, so it is
     * idempotent; retired bots keep the names they played under.
     */
    private void relabelCatalogBots() {
        for (PlayerDirectory.Row bot : players.bots()) {
            Optional<CatalogEntry> slot = bot.isRetired() || bot.nickname() == null ? Optional.empty() : CATALOG.byId(bot.botStrategy());
            if (slot.isEmpty()) {
                continue;
            }
            String name = BotNames.relabel(bot.nickname(), slot.get().name());
            if (!name.equals(bot.nickname())) {
                gameService.renameBot(bot.playerId(), name);
                LOG.infof("bot %s (%s) is now called %s", bot.playerId(), bot.nickname(), name);
            }
        }
    }

    /** The catalog ids held by live bots, and - with {@code includeWaiting} - by retired bots not yet replaced. */
    private static Set<String> catalogSlotsHeld(List<PlayerDirectory.Row> bots, boolean includeWaiting) {
        Set<String> held = new HashSet<>();
        for (PlayerDirectory.Row bot : bots) {
            boolean counts = !bot.isRetired() || (includeWaiting && !bot.replaced());
            if (counts && CATALOG.byId(bot.botStrategy()).isPresent()) {
                held.add(bot.botStrategy());
            }
        }
        return held;
    }

    /** The first catalog slot not in {@code held}; one of {@code family} (see {@link #familyMatches}) first when there is such. */
    private static Optional<CatalogEntry> freeSlot(Set<String> held, BotStrategy family) {
        CatalogEntry any = null;
        for (CatalogEntry entry : CATALOG.bots()) {
            if (held.contains(entry.catalogId())) {
                continue;
            }
            if (family != null && familyMatches(family, entry)) {
                return Optional.of(entry);
            }
            if (any == null) {
                any = entry;
            }
        }
        return Optional.ofNullable(any);
    }

    /** Whether a catalog bot is of the kind of an old one: the maximalists, the contrarians, the followers, the coin flippers, the cautious. */
    private static boolean familyMatches(BotStrategy legacy, CatalogEntry entry) {
        return switch (legacy) {
            case ALWAYS_MAX_LEVERAGE -> entry.persona().startsWith("maximalist");
            case CONTRARIAN -> entry.strategy().equals("crowd-contrarian");
            case FOLLOW_MAJORITY -> entry.strategy().equals("crowd-follow");
            case RANDOM -> entry.strategy().equals("coin");
            case CAUTIOUS_LOW_LEVERAGE -> entry.persona().startsWith("cautious");
        };
    }

    private static BotStrategy legacyOrNull(String botStrategy) {
        try {
            return botStrategy == null ? null : BotStrategy.valueOf(botStrategy);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The old kind a bot plays as when the legacy engine is on: its own, or - for a bot already moved to the catalog and rolled back -
     * the nearest: the maximalists, the contrarian and the follower, the cautious; the rest are wildcards.
     */
    static BotStrategy legacyStrategyOf(String botStrategy) {
        BotStrategy own = legacyOrNull(botStrategy);
        if (own != null) {
            return own;
        }
        CatalogEntry entry = botStrategy == null ? null : CATALOG.byId(botStrategy).orElse(null);
        if (entry == null) {
            return BotStrategy.RANDOM;
        }
        if (entry.persona().startsWith("maximalist")) {
            return BotStrategy.ALWAYS_MAX_LEVERAGE;
        }
        return switch (entry.strategy()) {
            case "crowd-contrarian" -> BotStrategy.CONTRARIAN;
            case "crowd-follow" -> BotStrategy.FOLLOW_MAJORITY;
            default -> entry.persona().startsWith("cautious") ? BotStrategy.CAUTIOUS_LOW_LEVERAGE : BotStrategy.RANDOM;
        };
    }

    /** How often the bots' economy housekeeping (retire, replace, sweep) looks, in ms: it is cheap but takes the engine's write lock. */
    private static final long HOUSEKEEPING_EVERY_MS = 5_000L;
    private static final long RECONCILE_EVERY_MS = 3_600_000L;
    private long lastHousekeepingMs = Long.MIN_VALUE / 2;
    private long lastReconcileMs = Long.MIN_VALUE / 2;

    /**
     * Economy stage B (plan B4): retires the bots that ran out of PLAY (below {@code herd.bots.retire-below-play}, nothing said in public),
     * creates the replacements that are due, moves the day's bot fees from HERD to TREASURY, and once an hour checks the books against the
     * chain (a mismatch is a WARN in the log). Failures are logged and retried at the next look.
     */
    void housekeeping(long nowMs) {
        if (nowMs - lastHousekeepingMs < HOUSEKEEPING_EVERY_MS) {
            return;
        }
        lastHousekeepingMs = nowMs;
        try {
            gameService.retireBotsBelowThreshold(nowMs);
            ensureBotsExist(nowMs);
            gameService.sweepBotFeesIfDue(nowMs);
            if (nowMs - lastReconcileMs >= RECONCILE_EVERY_MS) {
                lastReconcileMs = nowMs;
                gameService.reconciliation(nowMs);
            }
        } catch (ApiException e) {
            LOG.debugf("bot housekeeping skipped: %s", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnf(e, "bot housekeeping failed (will look again)");
        }
    }

    public synchronized void tick(long nowMs) {
        housekeeping(nowMs);
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
            if (bot.isRetired()) {
                continue; // retired: out of the game, in no list (economy stage B)
            }
            if (playsFromCatalog(bot)) {
                // one pass of the brain per betting window of the series, at a random moment of it (the legacy bots get one
                // shot per market): the persona decides how many markets, which, how much
                RoundWindow first = windows.get(0);
                long duration = first.endMs() - first.startMs();
                long bettingOpensAt = first.startMs() - duration;
                long offset = (long) (random.nextDouble() * duration * 0.9); // leave margin before close
                shots.add(new Shot(bot.playerId(), null, interval, null, first.startMs(), bettingOpensAt + offset));
                continue;
            }
            List<RoundWindow> picks = new ArrayList<>(windows);
            Collections.shuffle(picks, random);
            for (RoundWindow window : picks.subList(0, perBot)) {
                long duration = window.endMs() - window.startMs();
                // Bug fix (bots stopped betting): betting for `window` is open for the whole PREVIOUS
                // round's duration (D-19) — i.e. from window.startMs() - duration (when the currently
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
                if (shot.marketId() == null) {
                    placeCatalogBets(shot, nowMs);
                } else {
                    placeBotBet(shot, nowMs);
                }
            }
        }
    }

    /** A bot plays from the catalog when the catalog engine is on and its {@code bot_strategy} is a catalog id. */
    private boolean playsFromCatalog(PlayerDirectory.Row bot) {
        return config.botsCatalogEngine() && bot.botStrategy() != null && CATALOG.byId(bot.botStrategy()).isPresent();
    }

    /**
     * One pass of the bots module's brain for a catalog bot over the markets whose betting round of the shot's series closes at the
     * shot's window start: the ports read the engine, the candles and the history, the brain returns the intents, the runner places
     * them through {@link GameService#placeBet} (a refusal is a result, logged at debug level, never thrown).
     */
    private void placeCatalogBets(Shot shot, long nowMs) {
        try {
            PlayerDirectory.Row bot = players.byId(shot.botId()).orElse(null);
            if (bot == null || bot.isRetired() || !playsFromCatalog(bot)) {
                return; // reset since planning, retired, or rolled back to the legacy bots
            }
            BotProfile profile = profiles.computeIfAbsent(bot.playerId() + "|" + bot.botStrategy(),
                    k -> CATALOG.byId(bot.botStrategy()).orElseThrow().profile(bot.playerId()));
            ServerBotPorts ports = new ServerBotPorts(gameService, players, roundProgress, roundResults, marketRegistry,
                    marketPrices, config, shot.interval(), shot.bettingClosesAtMs(), nowMs, previousMajority);
            for (BotRunner.Placed placed : new BotRunner(ports, ports, ports, BRAIN).runOnce(profile)) {
                if (!placed.result().accepted()) {
                    LOG.debugf("bot %s could not bet on %s: %s", shot.botId(), placed.intent().roundId(), placed.result().reason());
                }
            }
        } catch (ApiException e) {
            LOG.debugf("bot %s skipped a pass: %s", shot.botId(), e.getMessage());
        } catch (Exception e) {
            LOG.warnf(e, "bot %s failed a pass of the brain (skipped this window)", shot.botId());
        }
    }

    private void placeBotBet(Shot shot, long nowMs) {
        try {
            PlayerDirectory.Row bot = players.byId(shot.botId()).orElse(null);
            Market market = marketRegistry.byId(shot.marketId()).orElse(null);
            if (bot == null || bot.isRetired() || market == null || !market.isActive() || !hasFreshPrice(market)) {
                return; // reset since planning, closed, or its price went away
            }
            BotStrategy strategy = legacyStrategyOf(bot.botStrategy());
            Optional<Direction> previous = previousMajority.computeIfAbsent(market.id() + "/" + shot.interval(),
                    k -> roundResults.lastMajorityDirection(market.id(), shot.interval())
                            .map(d -> Direction.valueOf(d.name())));
            Direction direction = strategy.chooseDirection(random, previous);
            // bots follow the same rules as any player (D-92): the stake is chosen first, because the leverage allowed
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
        // the primary exchange's price while it is fresh, else the backup's (D-76): a market whose primary is down still bets
        return marketPrices.latest(market).map(quote -> !quote.stale()).orElse(false);
    }
}
