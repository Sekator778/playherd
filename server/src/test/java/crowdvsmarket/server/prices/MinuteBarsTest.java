package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The candles read-model: ticks folded into 1-minute bars in a fixed ring, only finished minutes handed out, holes kept
 * as holes, a late tick not moving the close, a minute that falls out of the ring gone - and the same through
 * {@link TickStore}, which feeds it from {@code append} and {@code restore} and keeps each key (primary, shadow) apart.
 */
class MinuteBarsTest {

    private static final long M = 60_000L;
    private static final long T0 = 1_790_880_000_000L / M * M; // a minute boundary

    private static long px(String raw) {
        return Px.encode(new BigDecimal(raw));
    }

    @Test
    void ticksOfOneMinuteFoldIntoOpenHighLowCloseAndTheMinuteInProgressIsNotHandedOut() {
        MinuteBars bars = new MinuteBars();
        bars.add(T0 + 1_000, px("100"));
        bars.add(T0 + 10_000, px("103"));
        bars.add(T0 + 20_000, px("98"));
        bars.add(T0 + 59_000, px("101"));
        bars.add(T0 + M + 1_000, px("102")); // the next minute, in progress

        assertThat(bars.finished(T0 + M + 30_000, 10)).containsExactly(
                new MinuteBars.Bar(T0, px("100"), px("103"), px("98"), px("101")));
        assertThat(bars.finished(T0 + 30_000, 10)).as("while the minute is running it is not finished").isEmpty();
        assertThat(bars.finished(T0 + 2 * M, 10)).hasSize(2);
    }

    @Test
    void aLateTickWithAnEarlierTimeMovesTheOpenButNotTheClose() {
        MinuteBars bars = new MinuteBars();
        bars.add(T0 + 30_000, px("100"));
        bars.add(T0 + 50_000, px("101"));
        bars.add(T0 + 10_000, px("99")); // arrives late

        MinuteBars.Bar bar = bars.finished(T0 + M, 1).get(0);
        assertThat(bar.open()).isEqualTo(px("99"));
        assertThat(bar.close()).isEqualTo(px("101"));
        assertThat(bar.low()).isEqualTo(px("99"));
        assertThat(bar.high()).isEqualTo(px("101"));
    }

    @Test
    void aMinuteWithoutTicksHasNoBarAndTheOthersKeepTheirPlace() {
        MinuteBars bars = new MinuteBars();
        bars.add(T0 + 1_000, px("100"));
        bars.add(T0 + 3 * M + 1_000, px("104"));

        List<MinuteBars.Bar> out = bars.finished(T0 + 4 * M, 10);
        assertThat(out).extracting(MinuteBars.Bar::openTimeMs).containsExactly(T0, T0 + 3 * M);
    }

    @Test
    void asksForTheLastNMinutesOnlyOldestFirst() {
        MinuteBars bars = new MinuteBars();
        for (int i = 0; i < 100; i++) {
            bars.add(T0 + i * M + 5_000, px("100") + i);
        }
        List<MinuteBars.Bar> out = bars.finished(T0 + 100 * M, 30);
        assertThat(out).hasSize(30);
        assertThat(out.get(0).openTimeMs()).isEqualTo(T0 + 70 * M);
        assertThat(out.get(29).openTimeMs()).isEqualTo(T0 + 99 * M);
        assertThat(out).extracting(MinuteBars.Bar::openTimeMs).isSorted();
    }

    @Test
    void theRingHoldsAtLeastTwoHoursAndForgetsWhatFallsOutOfItsWindow() {
        MinuteBars bars = new MinuteBars();
        int minutes = MinuteBars.CAPACITY + 50;
        for (int i = 0; i < minutes; i++) {
            bars.add(T0 + i * M + 5_000, px("100"));
        }
        long now = T0 + minutes * M;
        List<MinuteBars.Bar> all = bars.finished(now, 10_000);
        assertThat(all).hasSize(MinuteBars.CAPACITY - 1);
        assertThat(MinuteBars.CAPACITY - 1).isGreaterThanOrEqualTo(120);
        assertThat(all.get(all.size() - 1).openTimeMs()).isEqualTo(T0 + (minutes - 1) * M);
        assertThat(all.get(0).openTimeMs()).isEqualTo(T0 + (minutes - MinuteBars.CAPACITY + 1) * M);
        // a tick from before the window is dropped and does not disturb the newer bar sharing its slot
        bars.add(T0 + 5_000, px("1"));
        assertThat(bars.finished(now, 10_000)).isEqualTo(all);
    }

    @Test
    void manyTicksStayInTheSameFixedMemory() {
        MinuteBars bars = new MinuteBars();
        for (int i = 0; i < 100_000; i++) {
            bars.add(T0 + i * 100L, px("100") + (i % 17));
        }
        assertThat(bars.finished(T0 + 100_000 * 100L + M, 10_000).size()).isLessThanOrEqualTo(MinuteBars.CAPACITY - 1);
    }

    // --- through TickStore ---

    private static Map<String, BigDecimal> prices(Object... idAndPrice) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndPrice.length; i += 2) {
            m.put((String) idAndPrice[i], new BigDecimal(idAndPrice[i + 1].toString()));
        }
        return m;
    }

    @Test
    void theStoreBuildsBarsFromPollsKeepsKeysApartAndRestoresThemAfterARestart() {
        AtomicLong now = new AtomicLong(T0 + 5 * M);
        FakeSnapshotStore snapshots = new FakeSnapshotStore();
        TickStore store = new TickStore(snapshots, 3 * 3_600_000L, now::get);
        store.append("coinbase", T0 + 1_000, prices("BTC", "84000.5", "spot_BTC~kraken", "83990"));
        store.append("coinbase", T0 + 30_000, prices("BTC", "84010"));
        store.append("coinbase", T0 + M + 2_000, prices("BTC", "84005"));
        store.append("coinbase", T0 + 2 * M + 2_000, prices("BTC", "0")); // refused: no price, no tick, no bar

        List<PriceBar> btc = store.bars("BTC", 10, T0 + 3 * M);
        assertThat(btc).hasSize(2);
        assertThat(btc.get(0).openTimeMs()).isEqualTo(T0);
        assertThat(btc.get(0).open()).isEqualByComparingTo("84000.5");
        assertThat(btc.get(0).high()).isEqualByComparingTo("84010");
        assertThat(btc.get(0).low()).isEqualByComparingTo("84000.5");
        assertThat(btc.get(0).close()).isEqualByComparingTo("84010");
        assertThat(store.bars("spot_BTC~kraken", 10, T0 + 3 * M)).as("the backup's own key, not mixed into the primary's").hasSize(1);
        assertThat(store.bars("NOPE", 10, T0 + 3 * M)).isEmpty();

        TickStore restarted = new TickStore(snapshots, 3 * 3_600_000L, now::get);
        restarted.restore();
        assertThat(restarted.bars("BTC", 10, T0 + 3 * M)).isEqualTo(btc);
    }
}
