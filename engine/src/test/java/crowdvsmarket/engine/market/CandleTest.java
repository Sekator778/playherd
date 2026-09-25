package crowdvsmarket.engine.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit-level tests for {@link Candle}'s compact constructor invariants: {@code low <= open <=
 * high}, {@code low <= close <= high}, and positive prices. {@link CandleSeriesTest} checks these
 * hold for every real fixture candle; this class checks the constructor itself rejects the
 * invalid shapes those fixtures never happen to contain.
 */
class CandleTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static Candle validCandle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return new Candle(0L, 3_600_000L, open, high, low, close, BigDecimal.ZERO, 0L);
    }

    @Test
    void acceptsAWellFormedCandle() {
        Candle candle = validCandle(d("100.00"), d("110.00"), d("90.00"), d("105.00"));
        assertThat(candle.open()).isEqualByComparingTo("100.00");
        assertThat(candle.high()).isEqualByComparingTo("110.00");
        assertThat(candle.low()).isEqualByComparingTo("90.00");
        assertThat(candle.close()).isEqualByComparingTo("105.00");
    }

    @Test
    void rejectsHighBelowLow() {
        assertThatThrownBy(() -> validCandle(d("100.00"), d("90.00"), d("95.00"), d("92.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("high < low");
    }

    @Test
    void rejectsOpenBelowLow() {
        assertThatThrownBy(() -> validCandle(d("89.00"), d("110.00"), d("90.00"), d("100.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open outside [low, high]");
    }

    @Test
    void rejectsOpenAboveHigh() {
        assertThatThrownBy(() -> validCandle(d("111.00"), d("110.00"), d("90.00"), d("100.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open outside [low, high]");
    }

    @Test
    void rejectsCloseBelowLow() {
        assertThatThrownBy(() -> validCandle(d("100.00"), d("110.00"), d("90.00"), d("89.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("close outside [low, high]");
    }

    @Test
    void rejectsCloseAboveHigh() {
        assertThatThrownBy(() -> validCandle(d("100.00"), d("110.00"), d("90.00"), d("111.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("close outside [low, high]");
    }

    @Test
    void rejectsNonPositiveLow() {
        assertThatThrownBy(() -> validCandle(d("0.00"), d("1.00"), d("0.00"), d("0.50")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low must be positive");

        assertThatThrownBy(() -> validCandle(d("-1.00"), d("1.00"), d("-2.00"), d("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("low must be positive");
    }

    @Test
    void rejectsCloseTimeBeforeOpenTime() {
        assertThatThrownBy(() -> new Candle(
                1_000L, 500L, d("100.00"), d("110.00"), d("90.00"), d("100.00"), BigDecimal.ZERO, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closeTimeMs before openTimeMs");
    }
}
