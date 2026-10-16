package crowdvsmarket.bots.signal;

import crowdvsmarket.engine.round.Direction;
import java.util.Objects;

/**
 * A strategy's answer: which way, and how sure (0..1; a proposal-level scale, only ordering matters). The strategy says
 * "where" only; stake and leverage are the persona's business.
 */
public record Signal(Direction direction, double confidence) {

    public Signal {
        Objects.requireNonNull(direction, "direction");
        if (!(confidence >= 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence must be in [0, 1], got " + confidence);
        }
    }
}
