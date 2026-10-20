package crowdvsmarket.bots.catalog;

import crowdvsmarket.bots.brain.Seeds;
import crowdvsmarket.engine.round.BetMode;

/**
 * Which bots play the win-or-lose mode (Р-106). The mode is a trait of the strategy, not a per-bet coin: a bot's preference is
 * a function of its catalog id (the identity that survives a retired player being replaced) and of one setting, the share of
 * the catalog on {@link BetMode#FIXED}. The same id and share give the same mode on every machine and after every restart; a
 * bigger share only adds bots to the fixed side (the draw of a bot does not depend on the share).
 */
public final class PayoutModes {

    /** The setting: the share of the catalog bots that play {@link BetMode#FIXED}, 0..1 (env var {@value #ENV}). */
    public static final String ENV = "HERD_BOTS_FIXED_SHARE";
    /** About half of the bots. */
    public static final double DEFAULT_FIXED_SHARE = 0.5;

    private PayoutModes() {
    }

    /** The share from {@link #ENV}, else {@link #DEFAULT_FIXED_SHARE}. A value that is not a number in 0..1 fails at once. */
    public static double configuredShare() {
        return parseShare(System.getenv(ENV));
    }

    static double parseShare(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_FIXED_SHARE;
        }
        double share;
        try {
            share = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(ENV + " must be a number in 0..1, got \"" + value + "\"");
        }
        return checked(share);
    }

    /** {@link BetMode#FIXED} for the bots whose draw falls under {@code fixedShare}, else {@link BetMode#PERCENTAGE}. */
    public static BetMode preferredMode(String catalogId, double fixedShare) {
        return draw(catalogId) < checked(fixedShare) ? BetMode.FIXED : BetMode.PERCENTAGE;
    }

    /** The bot's fixed draw in [0, 1): {@link Seeds} (FNV-1a) of the id, then a SplittableRandom to spread it. */
    static double draw(String catalogId) {
        return Seeds.rng(catalogId, "payout-mode", "").nextDouble();
    }

    private static double checked(double share) {
        if (Double.isNaN(share) || share < 0 || share > 1) {
            throw new IllegalArgumentException("the fixed-payout share must be in 0..1, got " + share);
        }
        return share;
    }
}
