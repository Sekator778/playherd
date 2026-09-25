package crowdvsmarket.engine.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One OHLC candle, as fetched from Hyperliquid's {@code candleSnapshot} endpoint and stored in
 * {@code engine/src/test/resources/fixtures/*.csv} (columns:
 * {@code open_time_ms,close_time_ms,open,high,low,close,volume,trades}; see PROVENANCE.md there).
 *
 * <p>In historical replay this is the engine's stand-in for mark price (doc section 2.4): {@code
 * open} is the round's entry/close price, {@code low}/{@code high} are the liquidation triggers
 * (doc section 2.3).
 */
public record Candle(
        long openTimeMs,
        long closeTimeMs,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        long trades) {

    public Candle {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(high, "high");
        Objects.requireNonNull(low, "low");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(volume, "volume");
        // OHLC prices are USD, scale 2 (doc section 2.6).
        open = open.setScale(2, RoundingMode.HALF_UP);
        high = high.setScale(2, RoundingMode.HALF_UP);
        low = low.setScale(2, RoundingMode.HALF_UP);
        close = close.setScale(2, RoundingMode.HALF_UP);
        if (closeTimeMs < openTimeMs) {
            throw new IllegalArgumentException("closeTimeMs before openTimeMs");
        }
        if (low.signum() <= 0) {
            throw new IllegalArgumentException("low must be positive: " + low);
        }
        if (high.compareTo(low) < 0) {
            throw new IllegalArgumentException("high < low");
        }
        if (open.compareTo(low) < 0 || open.compareTo(high) > 0) {
            throw new IllegalArgumentException(
                    "open outside [low, high]: open=" + open + " low=" + low + " high=" + high);
        }
        if (close.compareTo(low) < 0 || close.compareTo(high) > 0) {
            throw new IllegalArgumentException(
                    "close outside [low, high]: close=" + close + " low=" + low + " high=" + high);
        }
    }
}
