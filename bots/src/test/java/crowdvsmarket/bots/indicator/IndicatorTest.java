package crowdvsmarket.bots.indicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Indicator values against reference series worked out by hand; the derivation of every expected value is in the test.
 */
class IndicatorTest {

    @Test
    void smaIsTheMeanOfTheLastN() {
        // (3 + 4 + 5) / 3 = 4
        assertThat(Sma.last(new double[] {1, 2, 3, 4, 5}, 3)).isEqualTo(4.0);
    }

    @Test
    void emaSeedsWithSmaThenSmooths() {
        // n = 3, alpha = 2 / (3 + 1) = 0.5. Seed = SMA(2, 4, 6) = 4. Then 0.5*8 + 0.5*4 = 6, then 0.5*10 + 0.5*6 = 8.
        assertThat(Ema.last(new double[] {2, 4, 6, 8, 10}, 3)).isCloseTo(8.0, within(1e-12));
        // exactly n values: just the seed
        assertThat(Ema.last(new double[] {2, 4, 6}, 3)).isCloseTo(4.0, within(1e-12));
    }

    @Test
    void rsiFirstValueUsesPlainAverages() {
        // n = 3 on 10, 11, 10.5, 11.5: changes +1, -0.5, +1. avg gain = 2/3, avg loss = 0.5/3 = 1/6.
        // RS = (2/3) / (1/6) = 4, RSI = 100 - 100 / (1 + 4) = 80.
        assertThat(Rsi.last(new double[] {10, 11, 10.5, 11.5}, 3)).isCloseTo(80.0, within(1e-9));
    }

    @Test
    void rsiThenSmoothsLikeWilder() {
        // Continue with 11 (change -0.5): gain = (2/3 * 2 + 0) / 3 = 4/9, loss = (1/6 * 2 + 0.5) / 3 = 5/18.
        // RS = (4/9) / (5/18) = 1.6, RSI = 100 - 100 / 2.6 = 61.53846153846154.
        assertThat(Rsi.last(new double[] {10, 11, 10.5, 11.5, 11}, 3)).isCloseTo(61.53846153846154, within(1e-9));
    }

    @Test
    void rsiEdgeCases() {
        assertThat(Rsi.last(new double[] {1, 2, 3, 4, 5}, 3)).isEqualTo(100.0); // no loss at all
        assertThat(Rsi.last(new double[] {5, 4, 3, 2, 1}, 3)).isEqualTo(0.0); // no gain at all
        assertThat(Rsi.last(new double[] {7, 7, 7, 7, 7}, 3)).isEqualTo(50.0); // flat, by convention
    }

    @Test
    void bollingerUsesPopulationDeviation() {
        // The textbook set 2, 4, 4, 4, 5, 5, 7, 9: mean 5, population variance (9+1+1+1+0+0+4+16)/8 = 4, sigma 2.
        Bollinger.Bands b = Bollinger.last(new double[] {2, 4, 4, 4, 5, 5, 7, 9}, 8, 2.0);
        assertThat(b.middle()).isCloseTo(5.0, within(1e-12));
        assertThat(b.lower()).isCloseTo(1.0, within(1e-12));
        assertThat(b.upper()).isCloseTo(9.0, within(1e-12));
    }

    @Test
    void bollingerUsesOnlyTheLastN() {
        Bollinger.Bands b = Bollinger.last(new double[] {1000, 2, 4, 4, 4, 5, 5, 7, 9}, 8, 1.0);
        assertThat(b.middle()).isCloseTo(5.0, within(1e-12));
        assertThat(b.upper()).isCloseTo(7.0, within(1e-12));
    }

    @Test
    void donchianTakesExtremesOfARange() {
        double[] highs = {1, 5, 3, 2};
        double[] lows = {4, 2, 6};
        assertThat(Donchian.highest(highs, 0, 4)).isEqualTo(5.0);
        assertThat(Donchian.highest(highs, 2, 4)).isEqualTo(3.0);
        assertThat(Donchian.lowest(lows, 0, 3)).isEqualTo(2.0);
    }

    @Test
    void tooShortInputIsRejected() {
        assertThatThrownBy(() -> Sma.last(new double[] {1, 2}, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ema.last(new double[] {1, 2}, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rsi.last(new double[] {1, 2, 3}, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Bollinger.last(new double[] {1, 2}, 3, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Donchian.highest(new double[] {1}, 0, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
