package crowdvsmarket.engine.round;

/**
 * How a bet is paid (Р-106, docs/product/fixed_payout_rules.md). {@link #PERCENTAGE} is the original mode: the payout follows
 * stake x leverage x the price move, with liquidation. {@link #FIXED} has no leverage and no liquidation: a correct direction pays
 * twice the stake, a wrong one pays nothing, a tie (close equal to open) gives the stake back.
 */
public enum BetMode {
    PERCENTAGE,
    FIXED;

    /** The mode of a journal or wire name; {@code null} (an absent field) is {@link #PERCENTAGE}. */
    public static BetMode fromWire(String name) {
        if (name == null) {
            return PERCENTAGE;
        }
        if (FIXED.name().equals(name)) {
            return FIXED;
        }
        throw new IllegalArgumentException("unknown bet mode: " + name);
    }

    /** The name written in the journal: {@code "FIXED"}, or {@code null} for the percentage mode (the field is left out). */
    public String wireName() {
        return this == FIXED ? name() : null;
    }
}
