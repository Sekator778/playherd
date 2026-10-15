package crowdvsmarket.engine.economy;

import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.Objects;

/**
 * The two fees charged on top of a stake when a bet is placed (plan A4): the herd fee (the game's, scales with
 * the leverage) and the network fee (a flat amount per bet). Both are stored on the bet so that a cancellation
 * or a cancelled round refunds exactly what was charged, whatever the rules are by then. Never negative.
 */
public record Fees(PlayAmount herd, PlayAmount network) {

    public static final Fees ZERO = new Fees(PlayAmount.ZERO, PlayAmount.ZERO);

    public Fees {
        Objects.requireNonNull(herd, "herd");
        Objects.requireNonNull(network, "network");
        if (herd.isNegative() || network.isNegative()) {
            throw new IllegalArgumentException("fees must not be negative: " + herd + ", " + network);
        }
    }

    public PlayAmount total() {
        return herd.plus(network);
    }
}
