package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** The per-market tick buffer: ordering, windows, history inserts, trimming — against a TreeMap model. */
class TickRingTest {

    @Test
    void appendsInOrderAndFindsTheBoundaryTick() {
        TickRing ring = new TickRing();
        ring.append(1_000, 10);
        ring.append(4_000, 40);
        ring.append(7_000, 70);

        assertThat(ring.size()).isEqualTo(3);
        assertThat(ring.last()).containsExactly(7_000, 70);
        assertThat(ring.floor(4_000)).containsExactly(4_000, 40); // at <= t includes t itself
        assertThat(ring.floor(6_999)).containsExactly(4_000, 40);
        assertThat(ring.floor(999)).isNull();
    }

    @Test
    void windowIsOpenAtTheStartAndClosedAtTheEndAndCarriesTheFloorTick() {
        TickRing ring = new TickRing();
        for (int i = 1; i <= 5; i++) {
            ring.append(i * 1_000L, i);
        }

        TickRing.Window w = ring.window(2_000, 4_000); // ticks in (2000, 4000], floor = tick at 2000

        assertThat(w.hasFloor()).isTrue();
        assertThat(w.floorAtMs()).isEqualTo(2_000);
        assertThat(w.floorPx()).isEqualTo(2);
        assertThat(w.atMs()).containsExactly(3_000, 4_000);
        assertThat(w.px()).containsExactly(3, 4);

        TickRing.Window beforeAll = ring.window(500, 1_000);
        assertThat(beforeAll.hasFloor()).isFalse();
        assertThat(beforeAll.atMs()).containsExactly(1_000);
    }

    @Test
    void rangeIsClosedAtTheStartAndOpenAtTheEnd() {
        TickRing ring = new TickRing();
        for (int i = 1; i <= 5; i++) {
            ring.append(i * 1_000L, i);
        }

        assertThat(ring.range(2_000, 4_000).atMs()).containsExactly(2_000, 3_000);
        assertThat(ring.range(6_000, 9_000).atMs()).isEmpty();
    }

    @Test
    void aTickAtTheSameTimeReplacesAndHistoryNeverOverwrites() {
        TickRing ring = new TickRing();
        ring.append(1_000, 10);
        ring.append(1_000, 11);
        assertThat(ring.size()).isEqualTo(1);
        assertThat(ring.last()).containsExactly(1_000, 11);

        assertThat(ring.insertIfAbsent(1_000, 99)).isFalse();
        assertThat(ring.last()).containsExactly(1_000, 11);
        assertThat(ring.insertIfAbsent(500, 5)).isTrue();
        assertThat(ring.window(0, 2_000).atMs()).containsExactly(500, 1_000);
    }

    @Test
    void anOlderTickIsInsertedAtItsPlace() {
        TickRing ring = new TickRing();
        ring.append(1_000, 1);
        ring.append(3_000, 3);
        ring.append(2_000, 2);

        assertThat(ring.window(0, 10_000).atMs()).containsExactly(1_000, 2_000, 3_000);
        assertThat(ring.window(0, 10_000).px()).containsExactly(1, 2, 3);
    }

    @Test
    void trimDropsOldTicksAndTheRingKeepsWorkingAfterIt() {
        TickRing ring = new TickRing();
        for (int i = 0; i < 100; i++) {
            ring.append(i * 1_000L, i);
        }

        assertThat(ring.trimBefore(60_000)).isEqualTo(60);
        assertThat(ring.size()).isEqualTo(40);
        assertThat(ring.floor(59_999)).isNull();
        assertThat(ring.floor(60_000)).containsExactly(60_000, 60);

        assertThat(ring.trimBefore(1_000_000)).isEqualTo(40);
        assertThat(ring.size()).isZero();
        ring.append(5, 5);
        assertThat(ring.last()).containsExactly(5, 5);
    }

    /** The insert after a slide-down used to use an index from before the slide. */
    @Test
    void insertingIntoTheMiddleAfterTheFrontWasTrimmedKeepsTheOrder() {
        TickRing ring = new TickRing();
        for (int i = 0; i < 64; i++) { // exactly the initial capacity
            ring.append(i * 10L, i);
        }
        ring.trimBefore(300); // front room: 30 of 64
        ring.insertIfAbsent(555, 999); // the ring is full, so this slides down first

        TickRing.Window w = ring.window(0, 10_000);
        long previous = Long.MIN_VALUE;
        for (long at : w.atMs()) {
            assertThat(at).isGreaterThan(previous);
            previous = at;
        }
        assertThat(w.atMs()).contains(555L);
        assertThat(w.atMs()).hasSize(35);
    }

    @Test
    void behavesLikeASortedMapUnderRandomOperations() {
        Random random = new Random(42);
        TickRing ring = new TickRing();
        TreeMap<Long, Long> model = new TreeMap<>();
        long clock = 0;
        for (int step = 0; step < 20_000; step++) {
            int op = random.nextInt(100);
            if (op < 70) { // a poll: newest tick
                clock += 1 + random.nextInt(5_000);
                long px = 1 + random.nextInt(1_000_000);
                ring.append(clock, px);
                model.put(clock, px);
            } else if (op < 85) { // history somewhere in the past, never over a live tick
                long at = Math.max(1, clock - random.nextInt(600_000));
                long px = 1 + random.nextInt(1_000_000);
                boolean added = ring.insertIfAbsent(at, px);
                assertThat(added).isEqualTo(!model.containsKey(at));
                model.putIfAbsent(at, px);
            } else if (op < 92) { // a tick of an arbitrary time: an insert, or an overwrite of the one there
                long at = 1 + random.nextInt((int) Math.max(1, clock));
                long px = 1 + random.nextInt(1_000_000);
                ring.append(at, px);
                model.put(at, px);
            } else { // retention
                long cutoff = clock - 300_000;
                ring.trimBefore(cutoff);
                model.headMap(cutoff, false).clear();
            }
            if (step % 500 == 0) {
                assertSameAsModel(ring, model, clock, random);
            }
        }
        assertSameAsModel(ring, model, clock, random);
    }

    private static void assertSameAsModel(TickRing ring, TreeMap<Long, Long> model, long clock, Random random) {
        assertThat(ring.size()).isEqualTo(model.size());
        TickRing.Window all = ring.window(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2);
        assertThat(all.atMs()).hasSize(model.size());
        int i = 0;
        for (Map.Entry<Long, Long> e : model.entrySet()) {
            assertThat(all.atMs()[i]).isEqualTo(e.getKey());
            assertThat(all.px()[i]).isEqualTo(e.getValue());
            i++;
        }
        for (int probe = 0; probe < 20; probe++) {
            long t = random.nextInt((int) Math.max(1, clock));
            Map.Entry<Long, Long> expected = model.floorEntry(t);
            long[] actual = ring.floor(t);
            if (expected == null) {
                assertThat(actual).isNull();
            } else {
                assertThat(actual).containsExactly(expected.getKey(), expected.getValue());
            }
            long from = random.nextInt((int) Math.max(1, clock));
            long to = from + random.nextInt(100_000);
            assertThat(ring.window(from, to).atMs())
                    .containsExactly(model.subMap(from, false, to, true).keySet().stream().mapToLong(Long::longValue)
                            .toArray());
        }
    }
}
