package crowdvsmarket.bots.signal;

import static crowdvsmarket.bots.TestBars.concat;
import static crowdvsmarket.bots.TestBars.flat;
import static crowdvsmarket.bots.TestBars.ramp;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.TestBars;
import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.engine.round.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Each strategy on crafted series: rising, falling, flat, a gap, a history that is too short. */
class SignalStrategiesTest {

    private static MarketView view(List<BarDto> bars) {
        return MarketView.of("BTC", bars, Optional.empty(), new SplittableRandom(1));
    }

    private static Optional<Signal> run(SignalStrategy s, double... closes) {
        return s.evaluate(view(TestBars.of(closes)));
    }

    private static void assertDirection(Optional<Signal> s, Direction d) {
        assertThat(s).isPresent();
        assertThat(s.get().direction()).isEqualTo(d);
    }

    // --- view ---

    @Test
    void viewKeepsOnlyTheContiguousTail() {
        List<BarDto> bars = new ArrayList<>(TestBars.of(1, 2, 3, 4, 5));
        // drop the middle minute: 0, 1, [gap], 3, 4 -> the tail is 3, 4
        bars.remove(2);
        assertThat(view(bars).size()).isEqualTo(2);
        assertThat(view(TestBars.of(1, 2, 3)).size()).isEqualTo(3);
        assertThat(view(List.of()).size()).isZero();
    }

    // --- momentum ---

    @Test
    void momentumFollowsTheMove() {
        Momentum m = new Momentum(3);
        assertDirection(run(m, ramp(10, 100, 1)), Direction.UP);
        assertDirection(run(m, ramp(10, 100, -1)), Direction.DOWN);
    }

    @Test
    void momentumIsSilentOnFlatAndShortHistory() {
        Momentum m = new Momentum(3);
        assertThat(run(m, flat(10, 100))).isEmpty();
        assertThat(run(m, 100, 101, 102)).isEmpty(); // 3 candles, needs 4
        assertThat(run(m, 100, 101, 102, 103)).isPresent();
    }

    @Test
    void momentumSeesAPriceGap() {
        assertDirection(run(new Momentum(5), concat(flat(10, 100), flat(2, 130))), Direction.UP);
        assertDirection(run(new Momentum(5), concat(flat(10, 100), flat(2, 70))), Direction.DOWN);
    }

    @Test
    void aGapInTimeLeavesTooLittleHistory() {
        List<BarDto> bars = new ArrayList<>(TestBars.of(ramp(10, 100, 1)));
        bars.remove(7); // the tail after the hole is 2 candles
        assertThat(new Momentum(3).evaluate(view(bars))).isEmpty();
    }

    // --- RSI reversion ---

    @Test
    void rsiBetsAgainstExtremes() {
        RsiReversion r = new RsiReversion(2, 10, 90);
        assertDirection(run(r, ramp(12, 100, 1)), Direction.DOWN); // RSI 100: overbought
        assertDirection(run(r, ramp(12, 100, -1)), Direction.UP); // RSI 0: oversold
    }

    @Test
    void rsiIsSilentInTheMiddleOnFlatAndShort() {
        RsiReversion r = new RsiReversion(2, 10, 90);
        assertThat(run(r, flat(12, 100))).isEmpty(); // RSI 50
        assertThat(run(r, ramp(5, 100, 1))).isEmpty(); // warm-up is 6
        assertThat(run(new RsiReversion(14, 30, 70), 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101,
                100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101, 100, 101,
                100, 101, 100, 101, 100, 101)).isEmpty(); // choppy, RSI near 50
    }

    // --- Bollinger bounce ---

    @Test
    void bollingerFadesTheBreakOutOfTheBands() {
        BollingerBounce b = new BollingerBounce(10, 1.5);
        // nine closes at 100 and a last one at 110: mean 101, sigma 3, upper 105.5 -> 110 is above
        assertDirection(run(b, concat(flat(9, 100), new double[] {110})), Direction.DOWN);
        // nine at 100 and a last at 90: mean 99, sigma 3, lower 94.5 -> 90 is below
        assertDirection(run(b, concat(flat(9, 100), new double[] {90})), Direction.UP);
    }

    @Test
    void bollingerIsSilentInsideFlatAndShort() {
        BollingerBounce b = new BollingerBounce(10, 1.5);
        assertThat(run(b, flat(10, 100))).isEmpty(); // bands collapse onto the price
        // a steady ramp: last close 109, mean 104.5, sigma 2.872 -> upper band at 2 sigma is 110.24, so 109 is inside
        assertThat(run(new BollingerBounce(10, 2.0), ramp(10, 100, 1))).isEmpty();
        assertThat(run(b, flat(9, 100))).isEmpty();
    }

    // --- Donchian breakout ---

    @Test
    void donchianFollowsABreakout() {
        DonchianBreakout d = new DonchianBreakout(5);
        assertDirection(run(d, ramp(10, 100, 1)), Direction.UP); // each close tops the previous highs
        assertDirection(run(d, ramp(10, 100, -1)), Direction.DOWN);
    }

    @Test
    void donchianIsSilentInsideTheChannelFlatAndShort() {
        DonchianBreakout d = new DonchianBreakout(5);
        assertThat(run(d, concat(ramp(6, 100, 1), new double[] {103}))).isEmpty(); // back inside 99..106
        assertThat(run(d, flat(10, 100))).isEmpty();
        assertThat(run(d, ramp(5, 100, 1))).isEmpty(); // needs 6
    }

    @Test
    void donchianCountsTheLastCandleOutsideItsOwnChannel() {
        // five candles 100..104 (highs up to 104), then 105: breaks the 5-candle channel
        assertDirection(run(new DonchianBreakout(5), ramp(6, 100, 1)), Direction.UP);
    }

    // --- EMA cross ---

    @Test
    void emaCrossFollowsTheFasterAverage() {
        EmaCross e = new EmaCross(5, 13);
        assertDirection(run(e, ramp(40, 100, 1)), Direction.UP);
        assertDirection(run(e, ramp(40, 100, -1)), Direction.DOWN);
    }

    @Test
    void emaCrossIsSilentOnFlatAndShort() {
        EmaCross e = new EmaCross(5, 13);
        assertThat(run(e, flat(40, 100))).isEmpty();
        assertThat(run(e, ramp(25, 100, 1))).isEmpty(); // warm-up is 26
        assertThat(run(e, ramp(26, 100, 1))).isPresent();
    }

    // --- crowd and coin ---

    @Test
    void crowdStrategiesUseThePreviousMajority() {
        MarketView up = MarketView.of("BTC", List.of(), Optional.of(Direction.UP), new SplittableRandom(1));
        MarketView none = MarketView.of("BTC", List.of(), Optional.empty(), new SplittableRandom(1));
        assertDirection(new CrowdFollow().evaluate(up), Direction.UP);
        assertDirection(new CrowdContrarian().evaluate(up), Direction.DOWN);
        assertThat(new CrowdFollow().evaluate(none)).isEmpty();
        assertThat(new CrowdContrarian().evaluate(none)).isEmpty();
    }

    @Test
    void coinIsAFairSeededFlip() {
        Coin coin = new Coin();
        int ups = 0;
        for (long seed = 0; seed < 2000; seed++) {
            MarketView v = MarketView.of("BTC", List.of(), Optional.empty(), new SplittableRandom(seed));
            Optional<Signal> a = coin.evaluate(v);
            Optional<Signal> b = coin.evaluate(MarketView.of("BTC", List.of(), Optional.empty(), new SplittableRandom(seed)));
            assertThat(a).isPresent().isEqualTo(b);
            if (a.get().direction() == Direction.UP) {
                ups++;
            }
        }
        assertThat(ups).isBetween(900, 1100);
    }

    @Test
    void parametersAreValidated() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Momentum(0)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RsiReversion(14, 70, 30)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EmaCross(13, 5)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BollingerBounce(1, 2)).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new DonchianBreakout(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
