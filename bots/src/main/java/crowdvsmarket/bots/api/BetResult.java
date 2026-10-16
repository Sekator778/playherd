package crowdvsmarket.bots.api;

import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.Objects;

/**
 * The answer to a {@link BetRequest}. A refusal is a normal result, not an exception: a bot must never fall over
 * because the game said no (design principle 6, soft degradation).
 *
 * @param accepted    the bet was placed
 * @param reason      why it was refused (empty when accepted)
 * @param feesCharged fees taken on top of the stake (zero when refused)
 */
public record BetResult(boolean accepted, String reason, PlayAmount feesCharged) {

    public BetResult {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(feesCharged, "feesCharged");
    }

    public static BetResult accepted(PlayAmount feesCharged) {
        return new BetResult(true, "", feesCharged);
    }

    public static BetResult refused(String reason) {
        return new BetResult(false, reason, PlayAmount.ZERO);
    }
}
