package crowdvsmarket.server.bots;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.service.GameService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.jboss.logging.Logger;

/**
 * Р-46: 20 server-side bot players, betting at a random moment of each betting window
 * ({@code RoundSchedulerService} calls {@link #tick} every scheduler tick). Bots are ordinary
 * players from the engine's point of view — they go through the exact same {@code GameService}
 * validated path (min stake, one bet per round, leverage bounds) as a human player, just without
 * ever signing a wallet challenge (see {@code GameService#createBotPlayer}).
 */
@ApplicationScoped
public class BotService {

    private static final Logger LOG = Logger.getLogger(BotService.class);
    private static final BotStrategy[] STRATEGIES = BotStrategy.values();

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

    private final Random random = new Random();
    private final Map<Interval, String> currentBettingRoundId = new EnumMap<>(Interval.class);
    private final Map<Interval, Map<String, Long>> fireSchedule = new EnumMap<>(Interval.class);

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
        for (Interval interval : Interval.values()) {
            RoundWindow betting = roundProgress.bettingWindow(interval);
            String roundId = betting.roundId();
            if (!roundId.equals(currentBettingRoundId.get(interval))) {
                currentBettingRoundId.put(interval, roundId);
                scheduleBots(interval, betting);
            }
            fireDueBots(interval, betting, nowMs);
        }
    }

    private void scheduleBots(Interval interval, RoundWindow window) {
        Map<String, Long> schedule = new HashMap<>();
        long duration = window.endMs() - window.startMs();
        // Bug fix (bots stopped betting): betting for `window` is open for the whole PREVIOUS
        // round's duration (Р-19) — i.e. from window.startMs() - duration (when the currently
        // running round started) up to window.startMs() (when betting closes and `window` itself
        // starts running). The old code added the offset onto window.startMs() instead of onto
        // that betting-open instant, so every computed fire time landed AFTER betting for
        // `window` had already closed; roundProgress had by then advanced past `window`, so
        // GameService#placeBet always rejected it with "round not open for betting" by the time
        // the (already too late) fire time arrived. In practice this made a bot's odds of ever
        // firing inside the ~1s gap before the schedule got overwritten by the next round's
        // roughly (1s / (0.9*duration)) per bot per window — bots appeared to have "stopped
        // betting" almost entirely, restart or not.
        long bettingOpensAt = window.startMs() - duration;
        for (PlayerDirectory.Row bot : players.bots()) {
            long offset = (long) (random.nextDouble() * duration * 0.9); // leave margin before close
            schedule.put(bot.playerId(), bettingOpensAt + offset);
        }
        fireSchedule.put(interval, schedule);
    }

    private void fireDueBots(Interval interval, RoundWindow window, long nowMs) {
        Map<String, Long> schedule = fireSchedule.get(interval);
        if (schedule == null) {
            return;
        }
        for (PlayerDirectory.Row bot : players.bots()) {
            Long fireAt = schedule.get(bot.playerId());
            if (fireAt == null || fireAt > nowMs) {
                continue;
            }
            schedule.remove(bot.playerId()); // fire exactly once per round
            placeBotBet(bot, interval, window, nowMs);
        }
    }

    private void placeBotBet(PlayerDirectory.Row bot, Interval interval, RoundWindow window, long nowMs) {
        try {
            BotStrategy strategy = BotStrategy.valueOf(bot.botStrategy());
            Optional<Enums.Direction> previous = roundResults.lastMajorityDirection(interval);
            Optional<Direction> previousEngine = previous.map(d -> Direction.valueOf(d.name()));
            Direction direction = strategy.chooseDirection(random, previousEngine);
            int leverage = strategy.chooseLeverage(random);
            String stake = strategy.chooseStake(random).toPlainString();
            Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(window.roundId(),
                    Enums.Direction.valueOf(direction.name()), leverage, stake);
            gameService.placeBet(bot.playerId(), req, nowMs);
        } catch (Exception e) {
            LOG.warnf(e, "bot %s failed to place a bet on %s (skipped this round)", bot.playerId(), window.roundId());
        }
    }
}
