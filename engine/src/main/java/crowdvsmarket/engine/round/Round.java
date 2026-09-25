package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A "BTC up/down" round, matching the {@code Round} data format (PLAN.md section 3):
 *
 * <pre>
 * Round { round_id, asset="BTC", timeframe: daily|hourly, start_ts, end_ts,
 *         open_price, close_price, resolution_source="hyperliquid_oracle" }
 * </pre>
 *
 * <p>{@code closePrice} is {@code null} until the round resolves. In this engine's historical
 * replay, {@code openPrice}/{@code closePrice} are a candle's {@code open}/{@code close} (doc
 * section 2.4); {@code resolutionSource} records which price feed produced them (e.g.
 * {@code "candle_snapshot"} for historical replay, {@code "hyperliquid_oracle"} for the live
 * oracle-publish path described in PLAN.md section 3, out of scope for this engine module).
 */
public record Round(
        String roundId,
        String asset,
        Interval timeframe,
        long startTs,
        long endTs,
        BigDecimal openPrice,
        BigDecimal closePrice,
        String resolutionSource) {

    public Round {
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(openPrice, "openPrice");
        Objects.requireNonNull(resolutionSource, "resolutionSource");
        if (endTs < startTs) {
            throw new IllegalArgumentException("endTs before startTs");
        }
    }

    public boolean isResolved() {
        return closePrice != null;
    }

    public Round resolvedWith(BigDecimal closePrice) {
        return new Round(roundId, asset, timeframe, startTs, endTs, openPrice, closePrice, resolutionSource);
    }
}
