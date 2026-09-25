package crowdvsmarket.engine.game;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates one round series (HOURLY or DAILY, Р-19 — construct one {@code GameEngine} per
 * series) end to end: accepting/modifying/cancelling bets before a round starts (Р-13), then, once
 * its window has elapsed, opening every bet as a position at the round's open price, monitoring
 * liquidation across the round's price path (Р-7, Р-8, via {@code PositionSimulator}), settling
 * every position (Р-3), and updating the crowd account (Р-5) — all in one call to
 * {@link #runRound}, since a historical/simulated round's whole price path is known up front.
 *
 * <p>This is the API the future Quarkus service (Р-20) wraps; this module owns none of the
 * clock/scheduling side (when to call {@link #runRound} for which {@link RoundWindow}) — that is
 * {@code RoundClock}'s job, driven by whatever polls real time or replays a fixture.
 */
public final class GameEngine {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final LedgerBook ledger;
    private final CrowdAccount crowdAccount;
    private final BetBook betBook = new BetBook();

    public GameEngine() {
        this(new LedgerBook(), new CrowdAccount());
    }

    public GameEngine(LedgerBook ledger, CrowdAccount crowdAccount) {
        this.ledger = ledger;
        this.crowdAccount = crowdAccount;
    }

    public LedgerBook ledger() {
        return ledger;
    }

    public CrowdAccount crowdAccount() {
        return crowdAccount;
    }

    public BetBook betBook() {
        return betBook;
    }

    /**
     * Places (or replaces) {@code playerId}'s bet on {@code bettingWindow} (Р-13). Any earlier bet
     * by the same player on the same round is refunded first — refunding before reserving the new
     * stake, so growing a bet up to the full pre-modification balance never spuriously fails.
     */
    public Bet placeBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake, Instant now) {
        String roundId = bettingWindow.roundId();
        betBook.cancel(roundId, playerId)
                .ifPresent(previous -> ledger.refundStake(playerId, roundId, previous.stake(), now));
        ledger.reserveStake(playerId, roundId, stake, now);
        Bet bet = new Bet(playerId, roundId, direction, leverage, stake);
        betBook.place(bet);
        return bet;
    }

    /** Cancels {@code playerId}'s bet on {@code bettingWindow}, if any, refunding its stake (Р-13). */
    public void cancelBet(String playerId, RoundWindow bettingWindow, Instant now) {
        String roundId = bettingWindow.roundId();
        betBook.cancel(roundId, playerId)
                .ifPresent(previous -> ledger.refundStake(playerId, roundId, previous.stake(), now));
    }

    /**
     * Resolves {@code window}: opens every pending bet as a position at the round's open price,
     * replays each through {@code prices.path(window)} for liquidation (Р-7, Р-8), settles every
     * player via the ledger (Р-3), updates the crowd account (Р-5), and clears the round's bets.
     * Settlement is recorded at {@code window.endMs() - 1}, the round's own last millisecond —
     * {@code window.endMs()} itself is the half-open boundary shared with the next window (and,
     * for the last round of a month, is the next month's very first instant), so using it directly
     * would misfile the settlement into the wrong {@code Season} once every month.
     */
    public RoundReport runRound(RoundWindow window, RoundPrices prices) {
        Instant settledAt = Instant.ofEpochMilli(window.endMs() - 1);
        String roundId = window.roundId();
        Collection<Bet> bets = betBook.betsFor(roundId);
        BigDecimal openPrice = prices.openPrice(window);
        BigDecimal closePrice = prices.closePrice(window);
        List<Candle> path = prices.path(window);

        List<PlayerRoundOutcome> outcomes = new ArrayList<>();
        for (Bet bet : bets) {
            Position position = Position.openFromMargin(
                    bet.playerId(), roundId, bet.direction(), bet.leverage(), bet.stake().toMoney(), openPrice);
            PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);
            boolean liquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;

            BigDecimal payout;
            if (liquidated) {
                payout = ZERO_MONEY; // Р-7: full stake lost
            } else {
                BigDecimal initialMargin = LiquidationCalculator.initialMargin(position.size(), position.entryPrice(), position.leverage());
                BigDecimal pnl = ((PositionSimulator.Outcome.Settled) outcome).pnl();
                payout = initialMargin.add(pnl).max(ZERO_MONEY);
            }

            Optional<Boolean> directionCorrect = directionCorrect(bet.direction(), openPrice, closePrice);
            ledger.settleRound(bet.playerId(), roundId, PlayAmount.fromMoney(payout), directionCorrect, settledAt);
            outcomes.add(new PlayerRoundOutcome(bet.playerId(), bet.direction(), bet.leverage().value(), bet.stake(), liquidated, payout));
        }
        betBook.clear(roundId);

        CrowdAccount.RoundResult crowdResult = crowdAccount.processRound(roundId, bets, openPrice, path);

        return new RoundReport(window, openPrice, closePrice, outcomes, crowdResult);
    }

    /** {@code Optional.empty()} for a flat round (close == open, excluded from the "пророки" ratio, Р-18). */
    private static Optional<Boolean> directionCorrect(Direction direction, BigDecimal openPrice, BigDecimal closePrice) {
        int cmp = closePrice.compareTo(openPrice);
        if (cmp == 0) {
            return Optional.empty();
        }
        boolean actualUp = cmp > 0;
        return Optional.of((direction == Direction.UP) == actualUp);
    }
}
