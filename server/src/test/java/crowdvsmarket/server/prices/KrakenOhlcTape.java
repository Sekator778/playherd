package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongToDoubleFunction;

/**
 * Kraken's {@code /0/public/OHLC?interval=1} as the live API serves it — measured 2026-10-03, 17:0x UTC, from Germany:
 * with {@code since} a day back the answer held 721 rows, the first of them 12.0 h old (the latest 720 committed minutes
 * and the one in progress), and {@code since = 1} gave the same; with {@code since} three hours back, 180 rows. The rows
 * are those after {@code since}, one for every minute of the pair (a minute without trades is a row with
 * {@code count = 0}, open = high = low = close = the previous close), the row of the minute in progress is always among
 * them whatever {@code since} is, and {@code result.last} is the start of the last committed minute. There is no older page
 * to ask for.
 */
final class KrakenOhlcTape {

    /** The committed minutes the API keeps for a call. */
    static final int KEPT = 720;
    static final long MINUTE = 60_000L;

    private final String pair;
    private final LongToDoubleFunction price;
    private final Set<Long> quietMinutes = new HashSet<>();

    KrakenOhlcTape(String pair, LongToDoubleFunction priceAtMinuteMs) {
        this.pair = pair;
        this.price = priceAtMinuteMs;
    }

    /** A pair that trades at one price all the time. */
    static KrakenOhlcTape flat(String pair, double price) {
        return new KrakenOhlcTape(pair, t -> price);
    }

    /** The minute (its start, in ms) has a row with {@code count = 0}. */
    KrakenOhlcTape quiet(long minuteStartMs) {
        quietMinutes.add(minuteStartMs);
        return this;
    }

    /** What a call with {@code sinceSeconds} answers when the clock says {@code nowMs}. */
    JsonNode answer(long sinceSeconds, long nowMs) {
        long inProgress = Math.floorDiv(nowMs, MINUTE) * MINUTE;
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (long minute = inProgress - KEPT * MINUTE; minute <= inProgress; minute += MINUTE) {
            if (minute / 1000L > sinceSeconds || minute == inProgress) { // the minute in progress: always there
                rows.add(row(minute));
            }
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set(pair, rows);
        result.put("last", (inProgress - MINUTE) / 1000L);
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.set("error", JsonNodeFactory.instance.arrayNode());
        answer.set("result", result);
        return answer;
    }

    private ArrayNode row(long minute) {
        String p = String.valueOf(price.applyAsDouble(minute));
        boolean quiet = quietMinutes.contains(minute);
        ArrayNode row = JsonNodeFactory.instance.arrayNode();
        row.add(minute / 1000L).add(p).add(p).add(p).add(p).add(p).add(quiet ? "0.0" : "1.0").add(quiet ? 0 : 5);
        return row;
    }
}
