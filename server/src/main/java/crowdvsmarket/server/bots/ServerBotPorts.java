package crowdvsmarket.server.bots;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BetResult;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.port.BettingPort;
import crowdvsmarket.bots.port.BotStatePort;
import crowdvsmarket.bots.port.MarketDataPort;
import crowdvsmarket.engine.ledger.LedgerBook;
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
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PriceBar;
import crowdvsmarket.server.service.GameService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The server's side of the three ports of the {@code bots} module (docs/research/bots_module_design.md section 3): the
 * adapter over {@link GameService}, the price read-model and the stored history. One instance is a <i>view of one betting
 * window group of one interval at one instant</i> - every market whose open betting round of {@code interval} starts at
 * {@code windowStartMs} (or every market when that is {@code null}), judged at {@code nowMs}.
 *
 * <ul>
 *   <li>{@link MarketDataPort}: a snapshot per running market - the betting round of the series, its window, whether bets are
 *       taken (the market is active, betting is not over, in {@code mode=live} its price is fresh), the smallest stake; the
 *       terms (leverage caps by stake tier, fees) are the market's {@code MarketSpec}, which {@code EconomyRules} reads,
 *       so a bot cannot drift from the rules a human is held to. Candles are the server's read-model
 *       ({@link MarketPrices#bars}); the previous majority is the previous round's majority of that market and series, the same
 *       value the legacy contrarian and follower used.
 *   <li>{@link BettingPort}: {@link GameService#placeBet}, the validated path of a human (minimum stake, leverage by tier,
 *       balance, one round open for betting, circuit breaker). It <i>replaces</i> a bet the player already has in the round,
 *       which a bot must not do, so a second bet in a round is refused here. A refusal is a {@link BetResult}, never an exception.
 *   <li>{@link BotStatePort}: balance and open bets from the engine, the last result and the streak from the stored history of
 *       settled rounds (a win is {@code payout > stake}, a flat round does not count - the rule of {@code WinStreak}).
 * </ul>
 */
public final class ServerBotPorts implements MarketDataPort, BettingPort, BotStatePort {

    private static final Logger LOG = Logger.getLogger(ServerBotPorts.class);
    /** How many settled rounds are read to find the streak: more than any persona's cap. */
    static final int HISTORY_ROUNDS = 12;

    private final GameService gameService;
    private final PlayerDirectory players;
    private final RoundProgress roundProgress;
    private final RoundResultRepository roundResults;
    private final MarketRegistry marketRegistry;
    private final MarketPrices marketPrices;
    private final GameConfig config;
    private final Interval interval;
    private final Long windowStartMs;
    private final long nowMs;
    private final Map<String, Optional<Direction>> majorityMemo;

    /**
     * @param windowStartMs  start of the betting round to show, or {@code null} for the open betting round of every market
     * @param majorityMemo   previous majorities already read, by {@code market/interval}; shared by the views of one plan so that
     *                       the database is read once per market, not once per bot
     */
    public ServerBotPorts(GameService gameService, PlayerDirectory players, RoundProgress roundProgress,
                          RoundResultRepository roundResults, MarketRegistry marketRegistry, MarketPrices marketPrices,
                          GameConfig config, Interval interval, Long windowStartMs, long nowMs,
                          Map<String, Optional<Direction>> majorityMemo) {
        this.gameService = gameService;
        this.players = players;
        this.roundProgress = roundProgress;
        this.roundResults = roundResults;
        this.marketRegistry = marketRegistry;
        this.marketPrices = marketPrices;
        this.config = config;
        this.interval = interval;
        this.windowStartMs = windowStartMs;
        this.nowMs = nowMs;
        this.majorityMemo = majorityMemo;
    }

    // --- MarketDataPort ---

    @Override
    public List<MarketSnapshot> markets() {
        List<MarketSnapshot> out = new ArrayList<>();
        for (Market market : gameService.runningMarkets()) {
            Optional<RoundWindow> running = roundProgress.findNext(market.id(), interval);
            if (running.isEmpty()) {
                continue; // not seeded (yet)
            }
            RoundWindow betting = RoundClock.next(running.get());
            if (windowStartMs != null && betting.startMs() != windowStartMs) {
                continue;
            }
            boolean open = market.isActive() && nowMs < betting.startMs() && hasFreshPrice(market);
            out.add(new MarketSnapshot(market.spec(), interval, betting.roundId(), betting.startMs(), betting.endMs(), open,
                    LedgerBook.MIN_STAKE, market.kind() == crowdvsmarket.server.markets.MarketKind.ODDS));
        }
        return out;
    }

    @Override
    public List<BarDto> bars(String marketId, int minutes) {
        if (minutes <= 0 || marketRegistry.byId(marketId).isEmpty()) {
            return List.of();
        }
        List<PriceBar> bars = marketPrices.bars(marketId, minutes, nowMs);
        List<BarDto> out = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            try {
                out.add(new BarDto(bar.openTimeMs(), bar.open().doubleValue(), bar.high().doubleValue(), bar.low().doubleValue(),
                        bar.close().doubleValue()));
            } catch (IllegalArgumentException e) {
                LOG.debugf("a candle of %s at %d was left out: %s", marketId, bar.openTimeMs(), e.getMessage());
            }
        }
        return out;
    }

    @Override
    public Optional<Direction> previousMajority(String marketId) {
        if (marketRegistry.byId(marketId).isEmpty()) {
            return Optional.empty();
        }
        return majorityMemo.computeIfAbsent(marketId + "/" + interval,
                k -> roundResults.lastMajorityDirection(marketId, interval).map(d -> Direction.valueOf(d.name())));
    }

    // --- BettingPort ---

    @Override
    public BetResult place(BetRequest request) {
        try {
            Optional<Market> market = marketRegistry.byId(request.marketId());
            if (market.isEmpty() || !market.get().isActive()) {
                return BetResult.refused("market " + request.marketId() + " is not open");
            }
            if (!hasFreshPrice(market.get())) {
                return BetResult.refused("market " + request.marketId() + " has no fresh price");
            }
            if (gameService.openBetRoundIds(request.botId()).contains(request.roundId())) {
                return BetResult.refused("the bot already has a bet in " + request.roundId()); // placeBet would replace it
            }
            // Р-106: a bot that plays win-or-lose sends the mode on (absent = the percentage mode, as before)
            Betting.PlaceBetRequest req = new Betting.PlaceBetRequest(request.roundId(),
                    Enums.Direction.valueOf(request.direction().name()), request.leverage(),
                    request.stake().toMoney().toPlainString(),
                    request.mode() == crowdvsmarket.engine.round.BetMode.FIXED ? Enums.BetMode.FIXED : null);
            Rounds.BetDto bet = gameService.placeBet(request.botId(), req, nowMs);
            return BetResult.accepted(fees(bet));
        } catch (ApiException e) {
            return BetResult.refused(e.getMessage() == null ? e.code().name() : e.getMessage());
        } catch (RuntimeException e) {
            LOG.warnf(e, "bot %s: the bet on %s failed", request.botId(), request.roundId());
            return BetResult.refused("the bet could not be placed");
        }
    }

    private static PlayAmount fees(Rounds.BetDto bet) {
        PlayAmount total = PlayAmount.ZERO;
        if (bet.feeHerd() != null) {
            total = total.plus(PlayAmount.fromMoney(new BigDecimal(bet.feeHerd())));
        }
        if (bet.feeNetwork() != null) {
            total = total.plus(PlayAmount.fromMoney(new BigDecimal(bet.feeNetwork())));
        }
        return total;
    }

    // --- BotStatePort ---

    @Override
    public Optional<BotAccount> account(String botId) {
        Optional<PlayerDirectory.Row> row = players.byId(botId);
        if (row.isEmpty() || !row.get().isBot()) {
            return Optional.empty();
        }
        Optional<PlayAmount> balance = gameService.balanceOf(botId);
        if (balance.isEmpty()) {
            return Optional.empty();
        }
        // newest first, flat rounds left out; the streak is the run of results like the newest one
        LastOutcome last = LastOutcome.NONE;
        int streak = 0;
        for (long[] round : roundResults.recentOutcomes(botId, HISTORY_ROUNDS)) {
            LastOutcome outcome = round[1] > round[0] ? LastOutcome.WIN : LastOutcome.LOSS;
            if (last == LastOutcome.NONE) {
                last = outcome;
                streak = 1;
            } else if (outcome == last) {
                streak++;
            } else {
                break;
            }
        }
        return Optional.of(new BotAccount(botId, balance.get(), last, streak, gameService.openBetRoundIds(botId)));
    }

    /** In {@code mode=live}: a price exists and is not stale; in {@code mode=replay} there is no feed to ask. */
    private boolean hasFreshPrice(Market market) {
        if (config.mode() != GameMode.LIVE) {
            return true;
        }
        // the primary exchange's price while it is fresh, else the backup's (Р-76): a market whose primary is down still bets
        return marketPrices.latest(market).map(quote -> !quote.stale()).orElse(false);
    }
}
