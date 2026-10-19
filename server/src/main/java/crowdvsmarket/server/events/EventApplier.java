package crowdvsmarket.server.events;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.ledger.DepositRequest;
import crowdvsmarket.engine.ledger.WithdrawalRequest;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single code path that mutates {@link EngineHub} (its ledger, and per market the crowd account,
 * engines and comparison lines) for a {@link GameEvent} — used identically for live traffic (right
 * after the event is persisted, see {@code GameService}) and for the full replay at startup
 * ({@code EngineRebuildService}). Keeping exactly one such path is what makes "rebuilt from events
 * on startup" trustworthy: there is no second, hand-written reconstruction that could drift from
 * what live traffic actually does.
 *
 * <p>The four round events are routed by their {@code market}; an event that names a market the hub
 * has no engines for is an {@link IllegalStateException} naming the event — a rebuild must never
 * silently skip a bet or a settlement (money), it must stop and say why. The same events keep the hub's
 * {@link EngineHub#betWindows() index of the windows that hold bets}.
 */
@ApplicationScoped
public final class EventApplier {

    @Inject
    EngineHub hub;

    @Inject
    BetFeed betFeed;

    @Inject
    PlayerDirectory players;

    /**
     * An applier over a state other than the live one: what a rebuild replays the event log into
     * (and then cancels the rounds of closed markets in) while the live state keeps serving, before
     * the new state is swapped in. The bean itself always works on the live hub and feed.
     */
    public static EventApplier over(EngineHub hub, BetFeed betFeed, PlayerDirectory players) {
        EventApplier applier = new EventApplier();
        applier.hub = hub;
        applier.betFeed = betFeed;
        applier.players = players;
        return applier;
    }

    /** @return the event-specific result a live caller needs right away — a {@link RoundReport}
     *          for {@code RoundResolved}, the assigned id for {@code WithdrawalRequested}/
     *          {@code DepositRequested} (both mirror {@code LedgerBook}'s own generated
     *          {@code "w-N"}/{@code "d-N"} ids, see those two cases below), a
     *          {@code crowdvsmarket.engine.ledger.LedgerCorrection} for {@code WithdrawalReversed}/
     *          {@code DepositReversed}, {@code null} otherwise. Untyped ({@link Object}) since the cases return unrelated
     *          types; callers know which case they triggered and cast accordingly. */
    public Object apply(GameEvent event) {
        return switch (event) {
            case GameEvent.PlayerRegistered e -> {
                hub.ledger().ensureSeasonPack(e.playerId(), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.BetPlaced e -> {
                EngineHub.MarketEngines market = marketOf(e, e.market());
                RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                if (e.hasFees()) {
                    market.engine(e.interval()).replayBet(e.playerId(), window, e.direction(),
                            new Leverage(e.leverage()), new PlayAmount(e.stakeMojos()),
                            new Fees(new PlayAmount(e.feeHerdMojos()), new PlayAmount(e.feeNetMojos())),
                            e.liquidation(), Instant.ofEpochMilli(e.atMs()));
                } else { // written before the economy change: a legacy bet, no fees, the original rules
                    market.engine(e.interval()).placeBet(e.playerId(), window, e.direction(),
                            new Leverage(e.leverage()), new PlayAmount(e.stakeMojos()), Instant.ofEpochMilli(e.atMs()));
                }
                recordBetFeedEntry(window.roundId(), e);
                hub.betWindows().placed(window);
                yield null;
            }
            case GameEvent.BetCancelled e -> {
                EngineHub.MarketEngines market = marketOf(e, e.market());
                RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                market.engine(e.interval()).cancelBet(e.playerId(), window, Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.RoundResolved e -> {
                EngineHub.MarketEngines market = marketOf(e, e.market());
                RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                List<Candle> path = toCandles(e.path());
                RoundPrices prices = new StoredRoundPrices(new BigDecimal(e.openPrice()), new BigDecimal(e.closePrice()), path);
                RoundReport report = market.engine(e.interval()).runRound(window, prices);
                market.comparison().onRoundResolved(report, path);
                hub.betWindows().closed(window);
                hub.closedRounds().closed(window);
                yield report;
            }
            case GameEvent.RoundCancelled e -> {
                EngineHub.MarketEngines market = marketOf(e, e.market());
                RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                refundAndClear(market.engine(e.interval()), window, Instant.ofEpochMilli(e.atMs()));
                hub.betWindows().closed(window);
                hub.closedRounds().closed(window);
                yield null;
            }
            case GameEvent.WithdrawalRequested e -> {
                WithdrawalRequest wr = hub.ledger().requestWithdrawal(e.playerId(), new PlayAmount(e.amountMojos()),
                        Instant.ofEpochMilli(e.atMs()));
                yield wr.requestId();
            }
            case GameEvent.WithdrawalConfirmed e -> {
                hub.ledger().confirmWithdrawal(e.requestId());
                yield null;
            }
            case GameEvent.WithdrawalFailed e -> {
                hub.ledger().failWithdrawal(e.requestId(), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.DepositRequested e -> {
                DepositRequest dr = hub.ledger().requestDeposit(e.playerId(), new PlayAmount(e.amountMojos()),
                        Instant.ofEpochMilli(e.atMs()));
                yield dr.requestId();
            }
            case GameEvent.DepositConfirmed e -> {
                hub.ledger().confirmDeposit(e.requestId(), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.DepositFailed e -> {
                hub.ledger().failDeposit(e.requestId());
                yield null;
            }
            case GameEvent.WithdrawalReversed e -> {
                crowdvsmarket.engine.season.Season season = crowdvsmarket.engine.season.Season.parse(e.originalSeasonId());
                yield hub.ledger().reverseWithdrawalEffect(e.playerId(), new PlayAmount(e.originalAmountMojos()),
                        season, e.reason(), e.relatedWithdrawalRequestId(), Instant.ofEpochMilli(e.atMs()));
            }
            case GameEvent.DepositReversed e -> {
                crowdvsmarket.engine.season.Season season = crowdvsmarket.engine.season.Season.parse(e.originalSeasonId());
                yield hub.ledger().reverseDepositEffect(e.playerId(), new PlayAmount(e.originalAmountMojos()),
                        season, e.reason(), e.relatedDepositRequestId(), Instant.ofEpochMilli(e.atMs()));
            }
            case GameEvent.DepositConfirmedOnChain e -> {
                hub.ledger().creditOnChainDeposit(e.playerId(), new PlayAmount(e.amountMojos()),
                        Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.WithdrawalConfirmedOnChain e -> {
                hub.ledger().confirmWithdrawal(e.requestId());
                yield null;
            }
            case GameEvent.EconomyCutover e -> {
                hub.ledger().activateEconomy(new PlayAmount(e.openingTotalMojos()), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.BotRetired e -> {
                hub.ledger().retire(e.playerId(), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
            case GameEvent.HerdSweptToTreasury e -> {
                hub.ledger().sweepHerdToTreasury(new PlayAmount(e.amountMojos()), Instant.ofEpochMilli(e.atMs()));
                yield null;
            }
        };
    }

    /** The engines of the market {@code event} names — {@link IllegalStateException} naming the
     *  event when there are none (see the class javadoc). */
    private EngineHub.MarketEngines marketOf(GameEvent event, String marketId) {
        return hub.findMarket(marketId).orElseThrow(() -> new IllegalStateException(
                event.getClass().getSimpleName() + " refers to market '" + marketId + "' that has no engines "
                        + "(is it in the markets table?) — refusing to skip a money event: " + event));
    }

    /** Refunds every bet still pending on {@code window} and clears it — the operator/automatic
     *  cancellation operation (Р-23, Р-32); {@code GameEngine} has no such method itself, this is
     *  built from its public {@code ledger()}/{@code betBook()} accessors, same primitives
     *  {@code GameEngine.refundBet} (stake and fees) already used for a plain bet edit/cancel (Р-13). */
    public static int refundAndClear(GameEngine engine, RoundWindow window, Instant now) {
        String roundId = window.roundId();
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(roundId));
        for (Bet bet : bets) {
            engine.refundBet(bet, now); // the stake, and the fees of a bet placed under the economy rules
        }
        engine.betBook().clear(roundId);
        return bets.size();
    }

    /** Р-62: appends one {@link BetFeed} row for this placement/edit — live or replayed, since this
     *  runs from the same {@code BetPlaced} case either way. Falls back to the raw {@code playerId}/
     *  {@code isBot=false} if the directory has no row for it yet (defensive only: every
     *  {@code BetPlaced} in practice is preceded by that player's own {@code PlayerRegistered},
     *  same "should not happen" situation as {@code GameService.storeSettledResult}'s identical
     *  fallback). */
    private void recordBetFeedEntry(String roundId, GameEvent.BetPlaced e) {
        PlayerDirectory.Row row = players.byId(e.playerId()).orElse(null);
        String displayName = row == null ? e.playerId() : row.identity().displayName();
        boolean isBot = row != null && row.displaysAsBot();
        String stake = new PlayAmount(e.stakeMojos()).toMoney().toPlainString();
        betFeed.record(roundId, e.playerId(), displayName, isBot, Enums.Direction.valueOf(e.direction().name()),
                stake, e.leverage(), e.atMs());
    }

    public static List<Candle> toCandles(List<GameEvent.CandleData> raw) {
        List<Candle> out = new ArrayList<>(raw.size());
        for (GameEvent.CandleData c : raw) {
            out.add(new Candle(c.openTimeMs(), c.closeTimeMs(), new BigDecimal(c.open()), new BigDecimal(c.high()),
                    new BigDecimal(c.low()), new BigDecimal(c.close()), new BigDecimal(c.volume()), c.trades()));
        }
        return out;
    }

    public static List<GameEvent.CandleData> fromCandles(List<Candle> candles) {
        List<GameEvent.CandleData> out = new ArrayList<>(candles.size());
        for (Candle c : candles) {
            out.add(new GameEvent.CandleData(c.openTimeMs(), c.closeTimeMs(), c.open().toPlainString(),
                    c.high().toPlainString(), c.low().toPlainString(), c.close().toPlainString(),
                    c.volume().toPlainString(), c.trades()));
        }
        return out;
    }
}
