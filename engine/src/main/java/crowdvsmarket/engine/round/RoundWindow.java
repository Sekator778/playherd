package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.util.Objects;

/**
 * One round's fixed time window {@code [startMs, endMs)} of one market, UTC-aligned to
 * {@code interval} (Р-19). Carries no price data — {@link RoundPrices} resolves open/close/path for
 * a window once it is known. Construct via {@link RoundClock}, which guarantees UTC alignment.
 *
 * <p>{@code market} is a {@link MarketSpec} id (Р-69, a round belongs to one pair). The
 * three-argument constructor builds a {@link MarketSpec#BTC_ID BTC} window, so every pre-markets
 * call site keeps compiling and behaving exactly as before.
 */
public record RoundWindow(String market, Interval interval, long startMs, long endMs) {

    /** A BTC window — the engine's original single-market shape. */
    public RoundWindow(Interval interval, long startMs, long endMs) {
        this(MarketSpec.BTC_ID, interval, startMs, endMs);
    }

    public RoundWindow {
        MarketSpec.requireValidId(market);
        if (endMs <= startMs) {
            throw new IllegalArgumentException("endMs must be after startMs");
        }
    }

    /**
     * Deterministic id: interval code + UTC-aligned start — stable across re-runs on the same
     * fixtures. A BTC window keeps the legacy {@code "<code>-<startMs>"} (published history, DB keys
     * and result hashes depend on it); any other market is {@code "<market>-<code>-<startMs>"}.
     */
    public String roundId() {
        return formatRoundId(market, interval, startMs);
    }

    /** A round id taken apart: which market, which series, which UTC-aligned start — see {@link RoundWindow#parseRoundId}. */
    public record RoundRef(String market, Interval interval, long startMs) {

        public RoundRef {
            MarketSpec.requireValidId(market);
            Objects.requireNonNull(interval, "interval");
        }

        /** The canonical id of this reference, same format as {@link RoundWindow#roundId()} — for BTC the
         *  legacy two-part form, also when the reference was parsed from {@code "BTC-<code>-<start>"}. */
        public String roundId() {
            return formatRoundId(market, interval, startMs);
        }
    }

    /**
     * Inverse of {@link #roundId()}: two {@code '-'}-separated parts are the legacy BTC form
     * {@code "<code>-<startMs>"}, three are {@code "<market>-<code>-<startMs>"}. Accepts every
     * string {@link #roundId()} produces, plus one alias: the explicit three-part spelling of a BTC
     * round, {@code "BTC-1h-5"} = {@code "1h-5"}, so an id built generically as market + code +
     * start parses whatever the market is. Use {@link RoundRef#roundId()} as the key — it is always
     * the form {@link #roundId()} gives. The start must be spelled the way {@link #roundId()} prints
     * it: {@code "1h-05"}, {@code "1h-+5"} and non-ASCII digits are rejected like any other
     * malformed id, so a round has no other spelling than that one alias. Market ids never contain
     * {@code '-'}, which is what makes the split unambiguous (and a negative start, whose id would
     * contain a second one, is not parseable: real starts are epoch milliseconds).
     *
     * @throws IllegalArgumentException {@code "malformed roundId: …"} for anything else, an unknown
     *                                  interval code or a non-numeric start included
     */
    public static RoundRef parseRoundId(String roundId) {
        String[] parts = roundId == null ? new String[0] : roundId.split("-", -1);
        try {
            RoundRef ref = switch (parts.length) {
                case 2 -> new RoundRef(MarketSpec.BTC_ID, Interval.fromCode(parts[0]), Long.parseLong(parts[1]));
                case 3 -> new RoundRef(parts[0], Interval.fromCode(parts[1]), Long.parseLong(parts[2]));
                default -> throw new IllegalArgumentException("expected 2 or 3 '-'-separated parts");
            };
            String spelling = parts.length == 3
                    ? ref.market() + "-" + ref.interval().code() + "-" + ref.startMs() // BTC's alias included
                    : ref.roundId();
            if (!spelling.equals(roundId)) {
                throw new IllegalArgumentException("not the canonical spelling " + spelling);
            }
            return ref;
        } catch (IllegalArgumentException e) { // NumberFormatException is one too
            throw new IllegalArgumentException("malformed roundId: " + roundId, e);
        }
    }

    private static String formatRoundId(String market, Interval interval, long startMs) {
        String legacy = interval.code() + "-" + startMs;
        return MarketSpec.BTC_ID.equals(market) ? legacy : market + "-" + legacy;
    }
}
