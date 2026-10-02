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
    void afterIsTheFirstTickStrictlyLaterAndFirstIsTheOldest() {
        TickRing ring = new TickRing();
        assertThat(ring.first()).isNull();
        assertThat(ring.after(0)).isNull();

        ring.append(1_000, 10);
        ring.append(4_000, 40);
        ring.append(7_000, 70);

        assertThat(ring.first()).containsExactly(1_000, 10);
        assertThat(ring.after(0)).containsExactly(1_000, 10);
        assertThat(ring.after(1_000)).containsExactly(4_000, 40); // strictly later: not the tick at t itself
        assertThat(ring.after(6_999)).containsExactly(7_000, 70);
        assertThat(ring.after(7_000)).isNull();

        ring.trimBefore(4_000);
        assertThat(ring.first()).containsExactly(4_000, 40);
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

    // ------------------------------------------------------------------------------------ kinds (F4)

    @Test
    void everyTickKnowsWhatItIsAndALiveOneReplacesWhateverWasAtItsTime() {
        TickRing ring = new TickRing();
        ring.append(1_000, 10); // live
        ring.insertIfAbsent(2_000, 20); // history
        ring.insertIfAbsent(3_000, 30, TickRing.CARRIED);
        ring.insertIfAbsent(4_000, 40, TickRing.PIN);

        TickRing.Window all = ring.window(1_000, 10_000); // the floor is the live tick at 1 000
        assertThat(all.kind()).containsExactly(TickRing.HISTORY, TickRing.CARRIED, TickRing.PIN);
        assertThat(all.floorKind()).isEqualTo(TickRing.LIVE);

        ring.append(4_000, 41); // a poll arriving at the very millisecond of a pin: the live tick wins
        assertThat(ring.window(3_999, 4_000).floorKind()).isEqualTo(TickRing.CARRIED);
        assertThat(ring.window(3_999, 4_000).kind()).containsExactly(TickRing.LIVE);
        assertThat(ring.insertIfAbsent(4_000, 99, TickRing.HISTORY)).isFalse(); // history never replaces
    }

    @Test
    void lastLiveSkipsEverythingThatIsNotAPollAndOldestLiveSkipsTheSeededPast() {
        TickRing ring = new TickRing();
        assertThat(ring.lastLive()).isNull();
        assertThat(ring.oldestLiveAt()).isEqualTo(Long.MAX_VALUE);
        ring.insertIfAbsent(1_000, 10); // history before any poll
        ring.insertIfAbsent(2_000, 20);
        assertThat(ring.lastLive()).isNull();
        assertThat(ring.oldestLiveAt()).isEqualTo(Long.MAX_VALUE);

        ring.append(5_000, 50);
        ring.append(8_000, 80);
        ring.insertIfAbsent(9_000, 80, TickRing.PIN);
        ring.insertIfAbsent(9_500, 80, TickRing.CARRIED);

        assertThat(ring.last()).containsExactly(9_500, 80); // any kind
        assertThat(ring.lastLive()).containsExactly(8_000, 80);
        assertThat(ring.oldestLiveAt()).isEqualTo(5_000);
    }

    @Test
    void aCopyStandsForTheRealTickBeforeItAndTheWindowSaysWhichOneAndWhetherItWasLive() {
        TickRing ring = new TickRing();
        ring.insertIfAbsent(1_000, 10); // a history point
        ring.append(2_000, 20); // a poll
        ring.insertIfAbsent(3_000, 20, TickRing.CARRIED);
        ring.insertIfAbsent(4_000, 20, TickRing.CARRIED);
        ring.insertIfAbsent(5_000, 20, TickRing.PIN);

        TickRing.Window onTheCopies = ring.window(4_500, 6_000); // floor = the carried tick at 4 000
        assertThat(onTheCopies.floorKind()).isEqualTo(TickRing.CARRIED);
        assertThat(onTheCopies.floorSourceAtMs()).isEqualTo(2_000); // the poll behind the copies
        assertThat(onTheCopies.floorSourceKind()).isEqualTo(TickRing.LIVE);

        TickRing.Window onThePin = ring.window(5_000, 6_000);
        assertThat(onThePin.floorKind()).isEqualTo(TickRing.PIN);
        assertThat(onThePin.floorSourceAtMs()).isEqualTo(2_000);

        TickRing.Window onHistory = ring.window(1_500, 6_000); // floor = the history point
        assertThat(onHistory.floorSourceKind()).isEqualTo(TickRing.HISTORY);
        assertThat(onHistory.floorSourceAtMs()).isEqualTo(1_000);

        TickRing orphan = new TickRing(); // a pin with no real tick in the ring: its own kind, so nothing counts as live
        orphan.insertIfAbsent(1_000, 10, TickRing.PIN);
        assertThat(orphan.window(1_000, 2_000).floorSourceKind()).isEqualTo(TickRing.PIN);
        assertThat(orphan.window(1_000, 2_000).floorSourceAtMs()).isEqualTo(1_000);
    }

    @Test
    void rangeCanLeaveThePinsOut() {
        TickRing ring = new TickRing();
        ring.append(1_000, 10);
        ring.insertIfAbsent(2_000, 10, TickRing.PIN);
        ring.insertIfAbsent(3_000, 10, TickRing.CARRIED);
        ring.append(4_000, 11);

        assertThat(ring.range(0, 10_000).atMs()).containsExactly(1_000, 2_000, 3_000, 4_000);
        assertThat(ring.range(0, 10_000, false).atMs()).containsExactly(1_000, 3_000, 4_000);
        assertThat(ring.range(0, 10_000, false).kind()).containsExactly(TickRing.LIVE, TickRing.CARRIED, TickRing.LIVE);
        assertThat(ring.kindAfter(3_999)).isEqualTo(TickRing.LIVE);
        assertThat(ring.kindAfter(4_000)).isEqualTo(-1);
        assertThat(ring.timesBetween(1_000, 3_000)).containsExactly(1_000, 2_000, 3_000);
    }

    @Test
    void theKindsSurviveTheSlideAndTheGrowthOfTheArrays() {
        TickRing ring = new TickRing();
        for (int i = 0; i < 200; i++) {
            ring.insertIfAbsent(i * 10L, i, i % 3 == 0 ? TickRing.CARRIED : (i % 3 == 1 ? TickRing.HISTORY : TickRing.PIN));
        }
        ring.trimBefore(700);
        ring.append(5_000, 7); // forces the slide-down
        ring.insertIfAbsent(1_005, 1, TickRing.CARRIED); // an insert after the slide

        TickRing.Window w = ring.window(0, 10_000);
        for (int i = 0; i < w.atMs().length; i++) {
            long at = w.atMs()[i];
            if (at == 5_000) {
                assertThat(w.kind()[i]).isEqualTo(TickRing.LIVE);
            } else if (at == 1_005) {
                assertThat(w.kind()[i]).isEqualTo(TickRing.CARRIED);
            } else {
                int n = (int) (at / 10);
                assertThat(w.kind()[i]).as("tick " + at).isEqualTo(n % 3 == 0 ? TickRing.CARRIED
                        : (n % 3 == 1 ? TickRing.HISTORY : TickRing.PIN));
            }
        }
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
