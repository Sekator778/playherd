package crowdvsmarket.server.journal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A command-line measurement of the journal alone (not a test): how long {@code append} takes, msync included, for a batch of the size the
 * writer builds at 64 clients (62 bet events of about 350 bytes). Run it with the journal directory on the disk of interest:
 * {@code java <add-opens flags> -cp <test classpath> crowdvsmarket.server.journal.JournalSyncBench DIR [batches] [events-per-batch]}.
 * It deletes nothing and writes into DIR (an empty one).
 */
public final class JournalSyncBench {

    private JournalSyncBench() {
    }

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        int batches = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        int perBatch = args.length > 2 ? Integer.parseInt(args[2]) : 62;
        String pad = "x".repeat(170);
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            List<JournalRecord> batch = new ArrayList<>();
            for (int i = 0; i < perBatch; i++) {
                batch.add(new JournalRecord(0, "BetPlaced", 2, 1_700_000_000_000L + i, "BTC", "player-" + i,
                        "{\"playerId\":\"player-" + i + "\",\"interval\":\"HOURLY\",\"windowStart\":1700000000000,\"windowEnd\":1700003600000,"
                                + "\"direction\":\"UP\",\"leverage\":5,\"stakeMojos\":100000,\"atMs\":1700000000500,\"market\":\"BTC\",\"pad\":\""
                                + pad + "\"}"));
            }
            int warm = Math.min(300, batches / 5);
            long[] ns = new long[batches];
            for (int i = 0; i < warm; i++) {
                store.append(batch);
            }
            for (int i = 0; i < batches; i++) {
                long t0 = System.nanoTime();
                store.append(batch);
                ns[i] = System.nanoTime() - t0;
            }
            Arrays.sort(ns);
            long sum = 0;
            for (long v : ns) {
                sum += v;
            }
            System.out.printf("JOURNAL-SYNC-BENCH dir=%s os=%s batches=%d events/batch=%d bytes/event~%d mean_us=%.0f p50_us=%.0f p99_us=%.0f max_us=%.0f "
                    + "events_per_s_single_writer=%.0f%n", dir, System.getProperty("os.name"), batches, perBatch, 350, sum / 1000.0 / batches,
                    ns[batches / 2] / 1000.0, ns[(int) (batches * 0.99) - 1] / 1000.0, ns[batches - 1] / 1000.0,
                    perBatch * 1e9 / (sum / (double) batches));
        }
    }
}
