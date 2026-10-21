package crowdvsmarket.server.journal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A process that is killed (SIGKILL, no shutdown hook, no close) in the middle of appending to the journal. After each kill the journal is
 * opened again in this process: every batch whose append had returned (the child printed its ACK) is there, whole, once; seqs are
 * consecutive; nothing is half a batch; at most the batch in flight is there beyond the last ACK; and the journal takes new appends.
 * This is the crash of the process, which the OS survives; a power cut or kernel crash is the one thing the test cannot do, and is what the
 * {@code msync} before the answer is for (docs/kb/topics/methods.md, "Journal storage").
 */
class JournalKillTest {

    @TempDir
    Path dir;

    private record Acked(long lastSeq, int batch) {
    }

    /** Starts a child that appends from batch {@code start}, waits for {@code acks} ACK lines, kills it, returns the last ACK. */
    private Acked runAndKill(int start, int acks) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("--add-")) {
                cmd.add(arg);
            }
        }
        cmd.add("-Dchronicle.analytics.disable=true");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(JournalKillMain.class.getName());
        cmd.add(dir.toString());
        cmd.add(Integer.toString(start));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Acked last = null;
        int seen = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            while (seen < acks && (line = r.readLine()) != null) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                if (line.startsWith("ACK ")) {
                    String[] f = line.split(" ");
                    last = new Acked(Long.parseLong(f[1]), Integer.parseInt(f[2]));
                    seen++;
                }
            }
            // SIGKILL: no shutdown hook, no close. (Process.destroyForcibly would also close the stream that is read below.)
            new ProcessBuilder("kill", "-9", Long.toString(p.pid())).start().waitFor(10, TimeUnit.SECONDS);
            p.waitFor(30, TimeUnit.SECONDS);
            // the child ran on while this thread was reading: what it had printed before it died is acknowledged too
            while ((line = r.readLine()) != null) {
                if (line.startsWith("ACK ")) {
                    String[] f = line.split(" ");
                    last = new Acked(Long.parseLong(f[1]), Integer.parseInt(f[2]));
                    seen++;
                }
            }
        } finally {
            p.destroyForcibly();
        }
        assertThat(seen).as("the child appended").isGreaterThanOrEqualTo(acks);
        return last;
    }

    @Test
    void everyAcknowledgedBatchSurvivesAKillAndTheJournalGoesOn() throws Exception {
        int next = 0;
        long minSeq = 0;
        for (int round = 0; round < 3; round++) {
            Acked acked = runAndKill(next, 150 + 40 * round);
            try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
                List<JournalRecord> all = new ArrayList<>();
                store.replay(0, Long.MAX_VALUE, all::add);
                assertThat(all).extracting(JournalRecord::seq).as("consecutive seqs, none lost, none twice")
                        .isEqualTo(java.util.stream.LongStream.rangeClosed(1, all.size()).boxed().toList());
                assertThat(all.size()).as("everything acknowledged is there").isGreaterThanOrEqualTo((int) acked.lastSeq());
                assertThat(all.size()).as("at most the batch that was in flight when the child died is there beyond it")
                        .isLessThanOrEqualTo((int) acked.lastSeq() + 5);
                assertThat(all.get((int) acked.lastSeq() - 1).payload()).contains("\"batch\":" + acked.batch() + ",");
                assertThat(store.durableSeq()).isEqualTo(all.size());
                // whole batches: the events of one batch carry the same batch number, k = 0..n-1
                int i = 0;
                while (i < all.size()) {
                    int b = batchOf(all.get(i));
                    if (b < 0) {
                        i++; // an append of this test, not of the child
                        continue;
                    }
                    int n = 1 + b % 5;
                    assertThat(i + n).as("batch %d is whole", b).isLessThanOrEqualTo(all.size());
                    for (int k = 0; k < n; k++) {
                        assertThat(batchOf(all.get(i + k))).isEqualTo(b);
                    }
                    i += n;
                    next = Math.max(next, b + 1);
                }
                minSeq = all.size();
                assertThat(store.append(List.of(new JournalRecord(0, "BetPlaced", 2, 0, null, null, "{\"after\":\"kill\"}")))).isEqualTo(minSeq + 1);
            }
        }
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            assertThat(store.durableSeq()).isGreaterThan(minSeq);
        }
    }

    private static int batchOf(JournalRecord r) {
        String p = r.payload();
        if (p.contains("\"after\"")) {
            return -1;
        }
        int at = p.indexOf("\"batch\":") + 8;
        return Integer.parseInt(p.substring(at, p.indexOf(',', at)));
    }
}
