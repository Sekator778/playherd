package crowdvsmarket.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The journal store on its own (no Quarkus): numbering, durable position, readers, reopen. */
class ChronicleJournalStoreTest {

    @TempDir
    Path dir;

    static JournalRecord ev(int i) {
        return new JournalRecord(0, "BetPlaced", 2, 1_000L + i, i % 2 == 0 ? "BTC" : null, "p" + i, "{\"n\":" + i + ",\"t\":\"é\\u0001\"}");
    }

    static List<JournalRecord> batch(int from, int n) {
        List<JournalRecord> l = new ArrayList<>();
        for (int i = from; i < from + n; i++) {
            l.add(ev(i));
        }
        return l;
    }

    @Test
    void appendNumbersContinuouslyAndReadsBack() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            assertThat(store.durableSeq()).isZero();
            assertThat(store.append(batch(0, 3))).isEqualTo(3);
            assertThat(store.append(batch(3, 1))).isEqualTo(4);
            assertThat(store.durableSeq()).isEqualTo(4);
            List<JournalRecord> all = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, all::add);
            assertThat(all).hasSize(4);
            for (int i = 0; i < 4; i++) {
                assertThat(all.get(i)).isEqualTo(new JournalRecord(i + 1, ev(i).type(), 2, 1_000L + i, ev(i).market(), "p" + i, ev(i).payload()));
            }
            List<JournalRecord> tail = new ArrayList<>();
            assertThat(store.replay(2, Long.MAX_VALUE, tail::add)).isEqualTo(4);
            assertThat(tail).extracting(JournalRecord::seq).containsExactly(3L, 4L);
        }
    }

    @Test
    void reopenContinuesTheNumbers() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.append(batch(0, 5));
        }
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            assertThat(store.durableSeq()).isEqualTo(5);
            assertThat(store.nextSeq()).isEqualTo(6);
            assertThat(store.append(batch(5, 2))).isEqualTo(7);
            List<Long> seqs = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        }
    }

    @Test
    void readerStartsNearTheWantedEventThroughTheMarks() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            for (int i = 0; i < 1000; i++) {
                store.append(batch(i * 3, 3));
            }
            List<Long> seqs = new ArrayList<>();
            long last = store.replay(2_500, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(last).isEqualTo(3_000);
            assertThat(seqs).hasSize(500);
            assertThat(seqs.get(0)).isEqualTo(2_501);
            seqs.clear();
            store.replay(2_999, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(3_000L);
        }
    }

    @Test
    void numberedAppendKeepsGapsAndRefusesGoingBack() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.appendNumbered(List.of(new JournalRecord(5, "A", 1, 1, null, null, "{}"), new JournalRecord(9, "B", 1, 2, null, null, "{}")));
            assertThat(store.durableSeq()).isEqualTo(9);
            assertThat(store.nextSeq()).isEqualTo(10);
            assertThatThrownBy(() -> store.appendNumbered(List.of(new JournalRecord(9, "C", 1, 3, null, null, "{}"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.append(List.of(ev(0)))).isEqualTo(10);
        }
    }

    @Test
    void aReaderNeverGetsAnEventThatIsNotSyncedYetAndAFailedBatchIsVoid() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.append(batch(0, 2));
            store.hook(p -> {
                if (p == ChronicleJournalStore.Point.AFTER_WRITE) {
                    throw new IllegalStateException("disk failure before the sync");
                }
            });
            assertThatThrownBy(() -> store.append(batch(2, 2))).hasMessageContaining("disk failure");
            assertThat(store.durableSeq()).isEqualTo(2);
            List<Long> seen = new ArrayList<>();
            try (ChronicleJournalStore.Cursor c = store.cursor(0)) {
                c.next(100).forEach(r -> seen.add(r.seq()));
                assertThat(seen).containsExactly(1L, 2L);
                store.hook(null);
                assertThat(c.next(100)).isEmpty(); // the failed excerpt is in the file, not durable
                store.append(batch(4, 1)); // numbers 3 and 4 are spent; this one is 5, after the VOID excerpt for the failed batch
                c.next(100).forEach(r -> seen.add(r.seq()));
                assertThat(seen).as("the failed batch (3, 4) never turns up: its callers were told it failed").containsExactly(1L, 2L, 5L);
            }
        }
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            assertThat(store.nextSeq()).as("a restart goes on above the numbers the failed batch spent").isEqualTo(6);
            List<Long> seqs = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(1L, 2L, 5L);
        }
    }

    @Test
    void aFailureAfterTheSyncIsVoidToo() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.hook(p -> {
                if (p == ChronicleJournalStore.Point.AFTER_SYNC) {
                    throw new IllegalStateException("died after the sync");
                }
            });
            assertThatThrownBy(() -> store.append(batch(0, 2))).hasMessageContaining("died after the sync");
            store.hook(null);
            store.append(batch(2, 1));
            List<Long> seqs = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(3L);
        }
    }

    @Test
    void aBatchThatFailedAndWasNotVoidedBeforeTheProcessDiedIsThereAfterTheRestart() {
        // the process dies between the failure and the next append: nothing marks the excerpt void, and a restart reads what is in the file
        ChronicleJournalStore store = ChronicleJournalStore.open(dir);
        store.append(batch(0, 1));
        store.hook(p -> {
            if (p == ChronicleJournalStore.Point.AFTER_WRITE) {
                throw new IllegalStateException("boom");
            }
        });
        assertThatThrownBy(() -> store.append(batch(1, 1))).hasMessageContaining("boom");
        store.close();
        try (ChronicleJournalStore again = ChronicleJournalStore.open(dir)) {
            List<Long> seqs = new ArrayList<>();
            again.replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(1L, 2L);
        }
    }

    @Test
    void theVoidMarkerIsRetriedUntilItIsWritten() {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.hook(p -> {
                if (p == ChronicleJournalStore.Point.AFTER_WRITE) {
                    throw new IllegalStateException("boom");
                }
            });
            assertThatThrownBy(() -> store.append(batch(0, 1))).hasMessageContaining("boom");
            store.hook(null);
            assertThat(store.append(batch(1, 1))).isEqualTo(2);
            assertThat(store.durableSeq()).isEqualTo(2);
        }
    }

    @Test
    void theDailyFileRollsOverAndReadersAndRestartsFollowAcrossTheFiles() throws Exception {
        long day = 24L * 3_600_000L;
        long start = 20_000L * day;
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(start + 3_600_000L);
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir, clock::get)) {
            store.append(batch(0, 3));
            store.append(batch(3, 2));
            clock.set(start + day + 5_000L);
            store.append(batch(5, 4));
            clock.set(start + 3 * day + 5_000L);
            store.append(batch(9, 1));
            store.append(batch(10, 6));
            assertThat(store.durableSeq()).isEqualTo(16);
            List<Long> seqs = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).isEqualTo(java.util.stream.LongStream.rangeClosed(1, 16).boxed().toList());
            seqs.clear();
            store.replay(7, 12, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(8L, 9L, 10L, 11L, 12L);
        }
        try (var files = Files.list(dir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".cq4")).count()).as("one file for each day with an append").isEqualTo(3);
        }
        clock.set(start + 4 * day);
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir, clock::get)) {
            assertThat(store.durableSeq()).isEqualTo(16);
            assertThat(store.append(batch(16, 1))).isEqualTo(17);
            List<Long> seqs = new ArrayList<>();
            store.replay(14, Long.MAX_VALUE, r -> seqs.add(r.seq()));
            assertThat(seqs).containsExactly(15L, 16L, 17L);
        }
    }

    @Test
    void aDamagedExcerptIsRefusedOnOpen() throws Exception {
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            store.append(batch(0, 1));
        }
        Path file;
        try (var files = Files.list(dir)) {
            file = files.filter(p -> p.toString().endsWith(".cq4")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(file);
        byte[] needle = "BetPlaced".getBytes();
        int at = -1;
        for (int i = 0; i + needle.length < bytes.length && at < 0; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                at = i;
            }
        }
        assertThat(at).isPositive();
        bytes[at] = 'X';
        Files.write(file, bytes);
        assertThatThrownBy(() -> ChronicleJournalStore.open(dir)).hasMessageContaining("checksum");
    }
}
