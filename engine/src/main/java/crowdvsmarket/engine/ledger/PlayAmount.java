package crowdvsmarket.engine.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An exact integer amount of PLAY, the game's off-chain toy currency (Р-2,
 * docs/product/decisions.md), stored the way a CAT2 balance is stored on-chain: as an integer
 * count of the smallest unit ("mojos"), never as a floating-point number.
 *
 * <p><b>Unit scale (предложение — see docs/research/round_engine_design.md "Units"):</b> 1 PLAY =
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

    @Override
    public int compareTo(PlayAmount other) {
        return Long.compare(mojos, other.mojos);
    }

    @Override
    public String toString() {
        return toMoney().toPlainString() + " PLAY";
    }
}
