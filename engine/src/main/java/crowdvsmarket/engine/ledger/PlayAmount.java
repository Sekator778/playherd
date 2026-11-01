package crowdvsmarket.engine.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An exact integer amount of PLAY, the game's off-chain currency (D-2,
 * the decision log), stored the way a CAT2 balance is stored on-chain: as an integer
 * count of the smallest unit ("mojos"), never as a floating-point number.
 *
 * <p><b>Unit scale (proposal — see docs/research/round_engine_design.md "Units"):</b> 1 PLAY =
 * 1000 mojos, not Chia's native 10^12-mojos-per-XCH scale. The game never needs sub-milli-PLAY
 * precision (every USD-facing amount in {@code crowdvsmarket.engine.margin}/{@code round} settles
 * at 2dp, i.e. multiples of 10 mojos here), and 1000 keeps whole-PLAY amounts (the 10 000-PLAY
 * starter pack, the 100-PLAY minimum stake) exact. When PLAY is actually minted as a CAT2 on
 * testnet11, the real on-chain mojos-per-CAT constant is a separate, out-of-scope chain-layer
 * concern (see the design doc's open questions) — this type only models the in-game ledger.
 *
 * <p>1 PLAY is pegged 1:1 to the USD position math in {@code crowdvsmarket.engine.margin}: a
 * stake of {@code n} PLAY is numerically the same as an initial margin of {@code n} USD.
 */
public record PlayAmount(long mojos) implements Comparable<PlayAmount> {

    public static final long MOJOS_PER_PLAY = 1000L;
    public static final PlayAmount ZERO = new PlayAmount(0L);

    public static PlayAmount ofWholePlay(long wholePlay) {
        return new PlayAmount(Math.multiplyExact(wholePlay, MOJOS_PER_PLAY));
    }

    /**
     * From a money-scale (2dp or coarser, up to 3dp) PLAY/USD amount, as produced by
     * {@code LiquidationCalculator}'s formulas. Throws if {@code playAmount} carries precision
     * finer than 0.001 PLAY (not representable in mojos at this scale).
     */
    public static PlayAmount fromMoney(BigDecimal playAmount) {
        try {
            BigDecimal mojosDecimal = playAmount.setScale(3, RoundingMode.UNNECESSARY).movePointRight(3);
            return new PlayAmount(mojosDecimal.longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "not representable in PLAY mojos (finer than 0.001 PLAY): " + playAmount, e);
        }
    }

    /** Back to a {@link BigDecimal} PLAY amount, scale 3 — exact, mojos never loses precision. */
    public BigDecimal toMoney() {
        return BigDecimal.valueOf(mojos, 3);
    }

    public PlayAmount plus(PlayAmount other) {
        return new PlayAmount(Math.addExact(mojos, other.mojos));
    }

    public PlayAmount minus(PlayAmount other) {
        return new PlayAmount(Math.subtractExact(mojos, other.mojos));
    }

    public PlayAmount negate() {
        return new PlayAmount(Math.negateExact(mojos));
    }

    public boolean isNegative() {
        return mojos < 0;
    }

    public boolean isPositive() {
        return mojos > 0;
    }

    /**
     * Guards every money-moving entry point (stake, withdrawal, deposit — D-6, D-8): throws unless
     * this amount is strictly positive. Wave-2 security fix: a zero or negative amount here is not
     * merely "nothing happens" — {@link LedgerBook}'s debit/credit primitives negate a debit amount
     * before applying it, so a negative "withdrawal" of {@code -5000} silently becomes a {@code
     * +5000} credit (the exploit that minted PLAY via {@code requestWithdrawal("-5000.000")}).
     * Callers deciding a brand-new command must call this before ever constructing/persisting the
     * corresponding event ({@code GameService.requestWithdrawal}/{@code requestDeposit} do; {@link
     * LedgerBook#reserveStake} calls it directly, since no historical bet ever violated it). {@code
     * LedgerBook.requestWithdrawal}/{@code requestDeposit} themselves deliberately do
     * <b>not</b> call this — those two methods are also how a past, already-persisted event replays
     * on every restart, and this task's own rule ("do not alter or delete existing events") means a
     * historical amount that predates this guard must keep replaying exactly as it always did; see
     * those methods' own comments and {@link LedgerBook#reverseWithdrawalEffect} for how the one
     * already-corrupted balance actually gets fixed instead (a new, explicit, public event).
     *
     * @param what names the amount in the exception message (e.g. {@code "withdrawal amount"})
     * @return {@code this}, for fluent use at a call site
     */
    public PlayAmount requirePositive(String what) {
        if (!isPositive()) {
            throw new IllegalArgumentException(what + " must be positive: " + this);
        }
        return this;
    }

    @Override
    public int compareTo(PlayAmount other) {
        return Long.compare(mojos, other.mojos);
    }

    @Override
    public String toString() {
        return toMoney().toPlainString() + " PLAY";
    }
}
