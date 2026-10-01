package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * Tolerant decimal reading of source JSON. Hyperliquid and the Polymarket CLOB send prices as
 * strings ({@code "84629.0"}, {@code "0.145"}), Gamma and the data API as numbers; either may be
 * missing, {@code null} or garbage, and one bad value must cost one market its tick, never the
 * whole poll.
 */
public final class Dec {

    private Dec() {
    }

    /** The node as a decimal, or {@code null} when it is absent, not a number, or not finite. */
    public static BigDecimal of(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                String text = node.asText().trim();
                return text.isEmpty() ? null : new BigDecimal(text);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
