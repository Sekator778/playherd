package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongToDoubleFunction;

/**
 * A scripted market for the bridge tests: its mark at every moment, and the 1-minute candles of Hyperliquid's
 * {@code candleSnapshot} that go with it — what the mark did inside each minute (sampled every second), which minutes
 * traded and which did not, where a minute's low or high is set by hand, and how far the trades sit from the mark.
 * {@link #snapshot} answers a {@code candleSnapshot} request the way the API does (measured 2026-10-02, hl_multi_asset_live.md
 * §4.1, §13.1): the row of the minute that contains {@code startTime} first, rows by {@code t} up to {@code endTime}
 * inclusive, a row for the minute in progress (its {@code T} in the future), a filler row with {@code n = 0} and the
 * previous close for a minute without trades.
 */
final class CandleTape {

    static final long MINUTE = 60_000L;

    private final LongToDoubleFunction mark;
    private final Set<Long> quietMinutes = new HashSet<>();
    private final Set<Long> absentMinutes = new HashSet<>();
    private final Map<Long, Double> lows = new HashMap<>();
    private final Map<Long, Double> highs = new HashMap<>();
    private double shift;
    private double quietPrice = Double.NaN;

    CandleTape(LongToDoubleFunction mark) {
        this.mark = mark;
    }

    /** A BTC-like mark: 86 000 with a slow wave of up to 65 either way. */
    static CandleTape btc() {
        return new CandleTape(t -> 86_000.0 + 40.0 * Math.sin(t / 23_000.0) + 25.0 * Math.sin(t / 7_300.0));
    }

    /** A mark that never moves. */
    static CandleTape flat(double price) {
        return new CandleTape(t -> price);
    }

    double markAt(long atMs) {
        return Math.round(mark.applyAsDouble(atMs) * 100.0) / 100.0;
    }

    BigDecimal markPrice(long atMs) {
        return BigDecimal.valueOf(markAt(atMs));
    }

    /** The minute has a row with {@code n = 0}: nothing traded, the row only repeats the previous close. */
    CandleTape quiet(long minuteStartMs) {
        quietMinutes.add(minuteStartMs);
        return this;
    }

    CandleTape quiet(long fromMinuteMs, long toMinuteMs) {
        for (long m = fromMinuteMs; m <= toMinuteMs; m += MINUTE) {
            quietMinutes.add(m);
        }
        return this;
    }

    /** The minute has no row at all (the tail of a quiet asset, or a row the answer lost). */
    CandleTape absent(long minuteStartMs) {
        absentMinutes.add(minuteStartMs);
        return this;
    }

    /** The minute's low is this price, whatever the mark did (a wick of the trades). */
    CandleTape lowOf(long minuteStartMs, double low) {
        lows.put(minuteStartMs, low);
        return this;
    }

    CandleTape highOf(long minuteStartMs, double high) {
        highs.put(minuteStartMs, high);
        return this;
    }

    /** Every traded price is this much (relative) above the mark: {@code -0.01} is 1% below it. */
    CandleTape shifted(double relative) {
        this.shift = relative;
        return this;
    }

    /** The price a filler row repeats (the close of the last minute that traded, before the window). */
    CandleTape fillerPrice(double price) {
        this.quietPrice = price;
        return this;
    }

    /** The answer to a {@code candleSnapshot} request at {@code nowMs}. */
    JsonNode snapshot(JsonNode request, long nowMs) {
        JsonNode req = request.get("req");
        long start = req.get("startTime").asLong();
        long end = req.get("endTime").asLong();
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (long m = Math.floorDiv(start, MINUTE) * MINUTE; m <= end && m <= nowMs; m += MINUTE) {
            if (absentMinutes.contains(m)) {
                continue;
            }
            rows.add(row(m, req.get("coin").asText()));
        }
        return rows;
    }

    private ObjectNode row(long minute, String coin) {
        boolean quiet = quietMinutes.contains(minute);
        double open = markAt(minute);
        double close = markAt(minute + MINUTE - 1);
        double low = Math.min(open, close);
        double high = Math.max(open, close);
        for (long s = 0; s < 60; s++) {
            double p = markAt(minute + s * 1_000L);
            low = Math.min(low, p);
            high = Math.max(high, p);
        }
        if (lows.containsKey(minute)) {
            low = Math.min(low, lows.get(minute));
        }
        if (highs.containsKey(minute)) {
            high = Math.max(high, highs.get(minute));
        }
        if (quiet) {
            double price = Double.isNaN(quietPrice) ? open : quietPrice;
            open = price;
            close = price;
            low = price;
            high = price;
        } else {
            open *= 1 + shift;
            close *= 1 + shift;
            low *= 1 + shift;
            high *= 1 + shift;
            if (lows.containsKey(minute)) {
                low = lows.get(minute); // a wick set by hand is what it says
            }
            if (highs.containsKey(minute)) {
                high = highs.get(minute);
            }
        }
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        row.put("t", minute).put("T", minute + MINUTE - 1).put("s", coin).put("i", "1m");
        row.put("o", price(open)).put("h", price(high)).put("l", price(low)).put("c", price(close));
        row.put("v", quiet ? "0.0" : "12.5").put("n", quiet ? 0 : 12);
        return row;
    }

    private static String price(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
