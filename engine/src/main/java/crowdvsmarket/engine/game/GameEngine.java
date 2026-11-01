package crowdvsmarket.engine.game;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.LiquidationParams;
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
 * Orchestrates one round series (HOURLY or DAILY, D-19 — construct one {@code GameEngine} per
 * series) end to end: accepting/modifying/cancelling bets before a round starts (D-13), then, once
 * its window has elapsed, opening every bet as a position at the round's open price, monitoring
 * liquidation across the round's price path (D-7, D-8, via {@code PositionSimulator}), settling
 * every position (D-3), and updating the crowd account (D-5) — all in one call to
 * {@link #runRound}, since a historical/simulated round's whole price path is known up front.
 *
 * <p>This is the API the future Quarkus service (D-20) wraps; this module owns none of the
 * clock/scheduling side (when to call {@link #runRound} for which {@link RoundWindow}) — that is
 * {@code RoundClock}'s job, driven by whatever polls real time or replays a fixture.
 *
 * <p><b>One engine per market and series (D-69):</b> the market is the one of the
 * {@link CrowdAccount} the engine is built with ({@link #spec()}). It accepts bets and resolves
 * rounds only for windows of that market, and rejects a bet above that market's leverage cap;
 * several engines — one per market — can share a single {@link LedgerBook}, so one player's PLAY
 * balance backs bets on every market.
 */
public final class GameEngine {

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final LedgerBook ledger;
    private final CrowdAccount crowdAccount;
    private final MarketSpec spec;
    private final BetBook betBook = new BetBook();

    public GameEngine() {
        this(new LedgerBook(), new CrowdAccount());
    }

    public GameEngine(LedgerBook ledger, CrowdAccount crowdAccount) {
        this.ledger = ledger;
        this.crowdAccount = crowdAccount;
        this.spec = crowdAccount.spec();
    }

    /** The market this engine runs — its crowd account's. */
    public MarketSpec spec() {
        return spec;
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
     * Places (or replaces) {@code playerId}'s bet on {@code bettingWindow} (D-13). Any earlier bet
     * by the same player on the same round is refunded first — refunding before reserving the new
     * stake, so growing a bet up to the full pre-modification balance never spuriously fails.
     *
     * @throws IllegalArgumentException if the window belongs to another market, or {@code leverage}
     *                                  is above this market's cap — checked before anything is
     *                                  refunded or reserved, so a rejected bet changes nothing
     */
    public Bet placeBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake, Instant now) {
        requireOwnMarket(bettingWindow);
        if (leverage.value() > spec.maxLeverage()) {
            throw new IllegalArgumentException("leverage must be in [" + Leverage.MIN + ", " + spec.maxLeverage()
                    + "] for " + spec.id() + ", got " + leverage.value());
        }
        String roundId = bettingWindow.roundId();
        refundPrevious(playerId, roundId, now);
        ledger.reserveStake(playerId, roundId, stake, now);
        Bet bet = new Bet(playerId, roundId, direction, leverage, stake);
        betBook.place(bet);
        return bet;
    }

    /**
     * Economy stage A (D-92, plan A2-A4): places (or replaces) {@code playerId}'s bet under the new rules. The leverage must
     * be within the market's cap for this round's length <b>and</b> the tier of the stake
     * ({@link EconomyRules#requireLeverageAllowed}); the balance is debited {@code stake + fees.herd + fees.network}, and
     * a replaced bet is refunded with its fees. The bet is settled by the new rules ({@link #runRound}). {@code fees} is
     * what {@link EconomyRules#fees} computed (the server stores it in the event, so a replay charges the same amounts
     * whatever the formula is later).
     *
     * @throws IllegalArgumentException as {@link #placeBet(String, RoundWindow, Direction, Leverage, PlayAmount, Instant)},
     *                                  naming the allowed maximum for the stake when the leverage is too high
     */
    public Bet placeBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake,
            Fees fees, Instant now) {
        requireOwnMarket(bettingWindow);
        EconomyRules.requireLeverageAllowed(spec, bettingWindow.interval(), leverage.value(), stake);
        return replayBet(playerId, bettingWindow, direction, leverage, stake, fees,
                LiquidationParams.of(spec, bettingWindow.interval()), now);
    }

    /**
     * {@link #placeBet(String, RoundWindow, Direction, Leverage, PlayAmount, Fees, Instant)} without the cap and stake-tier
     * check, for restoring a bet from the event log: the event is the record of a bet that passed the check when it was
     * placed, and a later change of a market's cap (the weekly recalculation lowers caps) must not make the log unreadable.
     * The ledger rules (minimum stake, balance covering stake and fees) still apply.
     */
    public Bet replayBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake,
            Fees fees, Instant now) {
        return replayBet(playerId, bettingWindow, direction, leverage, stake, fees, null, now);
    }

    /**
     * {@link #replayBet(String, RoundWindow, Direction, Leverage, PlayAmount, Fees, Instant)} with the liquidation parameters the bet
     * was placed with (economy stage B): the bet is settled with them whatever the market's cap and kappa are by then. {@code null}
     * (a log written before they were stored) falls back to the market's current ones.
     */
    public Bet replayBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake,
            Fees fees, LiquidationParams liquidation, Instant now) {
        requireOwnMarket(bettingWindow);
        String roundId = bettingWindow.roundId();
        refundPrevious(playerId, roundId, now);
        ledger.reserveStake(playerId, roundId, stake, fees, now);
        Bet bet = new Bet(playerId, roundId, direction, leverage, stake, fees, liquidation);
        betBook.place(bet);
        return bet;
    }

    /**
     * D-106: places (or replaces) {@code playerId}'s fixed-payout bet (docs/product/fixed_payout_rules.md): no leverage, no liquidation;
     * the balance is debited {@code stake + fees.herd + fees.network}, a replaced bet (of either mode) is refunded with its fees; the bet
     * pays 2 x stake, nothing or the stake back ({@link #runRound}). {@code fees} is what {@link EconomyRules#fixedFees} computed.
     */
    public Bet placeFixedBet(
            String playerId, RoundWindow bettingWindow, Direction direction, PlayAmount stake, Fees fees, Instant now) {
        return replayBet(playerId, bettingWindow, direction, Bet.FIXED_LEVERAGE, stake, fees, null, BetMode.FIXED, now);
    }

    /**
     * {@link #replayBet(String, RoundWindow, Direction, Leverage, PlayAmount, Fees, LiquidationParams, Instant)} with the mode of the
     * bet from the event log ({@link BetMode#FIXED}: {@code leverage} is {@link Bet#FIXED_LEVERAGE} and {@code liquidation} {@code null}).
     */
    public Bet replayBet(
            String playerId, RoundWindow bettingWindow, Direction direction, Leverage leverage, PlayAmount stake,
            Fees fees, LiquidationParams liquidation, BetMode mode, Instant now) {
        requireOwnMarket(bettingWindow);
        String roundId = bettingWindow.roundId();
        Bet bet = new Bet(playerId, roundId, direction, leverage, stake, fees, liquidation, mode);
        refundPrevious(playerId, roundId, now);
        ledger.reserveStake(playerId, roundId, stake, fees, now);
        betBook.place(bet);
        return bet;
    }

    /** Cancels {@code playerId}'s bet on {@code bettingWindow}, if any, refunding its stake (D-13) and its fees (plan A4). */
    public void cancelBet(String playerId, RoundWindow bettingWindow, Instant now) {
        refundPrevious(playerId, bettingWindow.roundId(), now);
    }

    /** Takes {@code playerId}'s bet on {@code roundId} out of the book, if there is one, and refunds its stake and fees. */
    private void refundPrevious(String playerId, String roundId, Instant now) {
        betBook.cancel(roundId, playerId).ifPresent(previous -> refundBet(previous, now));
    }

    /**
     * Returns a bet's stake and, for a bet placed under the new rules, its fees (plan A4: a cancellation and a cancelled round
     * refund both; a tie does not, it settles). Does not touch the bet book.
     */
    public void refundBet(Bet bet, Instant now) {
        ledger.refundStake(bet.playerId(), bet.roundId(), bet.stake(), now);
        if (!bet.isLegacy()) {
            ledger.refundFees(bet.playerId(), bet.roundId(), bet.fees(), now);
        }
    }

    /**
     * Resolves {@code window}: opens every pending bet as a position at the round's open price,
     * replays each through {@code prices.path(window)} for liquidation (D-7, D-8), settles every
     * player via the ledger (D-3), updates the crowd account (D-5), and clears the round's bets.
     * Settlement is recorded at {@code window.endMs() - 1}, the round's own last millisecond —
     * {@code window.endMs()} itself is the half-open boundary shared with the next window (and,
     * for the last round of a month, is the next month's very first instant), so using it directly
     * would misfile the settlement into the wrong {@code Season} once every month.
     *
     * @throws IllegalArgumentException if the window belongs to another market
     */
    public RoundReport runRound(RoundWindow window, RoundPrices prices) {
        requireOwnMarket(window);
        Instant settledAt = Instant.ofEpochMilli(window.endMs() - 1);
        String roundId = window.roundId();
        Collection<Bet> bets = betBook.betsFor(roundId);
        BigDecimal openPrice = prices.openPrice(window);
        BigDecimal closePrice = prices.closePrice(window);
        List<Candle> path = prices.path(window);

        List<PlayerRoundOutcome> outcomes = new ArrayList<>();
        for (Bet bet : bets) {
            if (bet.isFixed()) {
                // D-106: no position, no liquidation - 2 x stake / 0 / stake back by the close against the open; the fees stay with the game
                int closeVsOpen = closePrice.compareTo(openPrice);
                PlayAmount fixedPayout = EconomyRules.fixedPayout(bet.stake(), bet.direction() == Direction.UP, closeVsOpen);
                ledger.settleRound(bet.playerId(), roundId, bet.stake(), fixedPayout,
                        directionCorrect(bet.direction(), openPrice, closePrice), settledAt);
                outcomes.add(new PlayerRoundOutcome(bet.playerId(), bet.direction(), bet.leverage().value(), bet.stake(), false,
                        fixedPayout.toMoney(), bet.fees(), null, BetMode.FIXED));
                continue;
            }
            boolean legacy = bet.isLegacy();
            // legacy bets keep the original single cap and l = 1/(2 cap); new ones use the round length's cap and l = kappa / cap
            MarketSpec positionSpec = bet.specFor(spec, window.interval());
            Position position = Position.openFromMargin(
                    bet.playerId(), roundId, bet.direction(), bet.leverage(), bet.stake().toMoney(), openPrice, positionSpec);
            PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);
            boolean liquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;

            BigDecimal payout;
            if (liquidated && legacy) {
                payout = ZERO_MONEY; // D-7: full stake lost
            } else if (liquidated) {
                // plan A1: the remainder at the extreme of the candle that triggered the liquidation, 0 on a gap through the margin
                BigDecimal extreme = ((PositionSimulator.Outcome.Liquidated) outcome).extremePrice();
                payout = LiquidationCalculator.payoutAtCandleExtreme(
                        position.size(), position.entryPrice(), position.leverage(), position.direction().toSide(), extreme);
            } else {
                BigDecimal initialMargin = LiquidationCalculator.initialMargin(position.size(), position.entryPrice(), position.leverage());
                BigDecimal pnl = ((PositionSimulator.Outcome.Settled) outcome).pnl();
                payout = initialMargin.add(pnl).max(ZERO_MONEY);
            }
            if (!legacy) {
                // plan A5: min(20 x stake, 0.1% of the treasury); the stake is mojo-exact, the payout in cents, so round down
                BigDecimal cap = EconomyRules.maxPayout(bet.stake(), ledger.payoutCapTreasury()).toMoney().setScale(2, RoundingMode.DOWN);
                payout = payout.min(cap);
            }

            Optional<Boolean> directionCorrect = directionCorrect(bet.direction(), openPrice, closePrice);
            ledger.settleRound(bet.playerId(), roundId, bet.stake(), PlayAmount.fromMoney(payout), directionCorrect, settledAt);
            outcomes.add(new PlayerRoundOutcome(bet.playerId(), bet.direction(), bet.leverage().value(), bet.stake(), liquidated, payout, bet.fees(), bet.liquidation()));
        }
        betBook.clear(roundId);

        boolean anyNewRules = bets.stream().anyMatch(b -> !b.isLegacy());
        CrowdAccount.RoundResult crowdResult = anyNewRules
                ? crowdAccount.processRound(roundId, bets, openPrice, path, window.interval())
                : crowdAccount.processRound(roundId, bets, openPrice, path);

        return new RoundReport(window, openPrice, closePrice, outcomes, crowdResult, anyNewRules);
    }

    private void requireOwnMarket(RoundWindow window) {
        if (!spec.id().equals(window.market())) {
            throw new IllegalArgumentException("round " + window.roundId() + " belongs to market " + window.market()
                    + ", this engine runs " + spec.id());
        }
    }

    /** {@code Optional.empty()} for a flat round (close == open, excluded from the prophets ratio, D-18). */
    private static Optional<Boolean> directionCorrect(Direction direction, BigDecimal openPrice, BigDecimal closePrice) {
        int cmp = closePrice.compareTo(openPrice);
        if (cmp == 0) {
            return Optional.empty();
        }
        boolean actualUp = cmp > 0;
        return Optional.of((direction == Direction.UP) == actualUp);
    }
}
