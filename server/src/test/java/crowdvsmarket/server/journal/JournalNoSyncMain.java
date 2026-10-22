package crowdvsmarket.server.journal;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

/**
 * NEGATIVE CONTROL of the power-loss test (tools/journal-powerloss): the same as {@link JournalKillMain} (same records, same excerpt format,
 * same ACK line) but the queue has the default sync mode and {@code appender.sync()} is never called. Its ACK therefore promises nothing
 * after a power cut; if the power-loss harness does not lose these, it does not see mmap writes and its positive result means nothing.
 */
public final class JournalNoSyncMain {

    private JournalNoSyncMain() {
    }

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        int start = Integer.parseInt(args[1]);
        long seq = args.length > 2 ? Long.parseLong(args[2]) : 0;
        ChronicleQueue queue = SingleChronicleQueueBuilder.single(new File(dir.toString())).rollCycle(RollCycles.FAST_DAILY).build();
        ExcerptAppender appender = queue.createAppender();
        for (int i = start;; i++) {
            int n = 1 + i % 5;
            List<JournalRecord> batch = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                batch.add(new JournalRecord(++seq, "BetPlaced", 2, i, "BTC", "p" + k,
                        "{\"batch\":" + i + ",\"k\":" + k + ",\"pad\":\"" + "x".repeat(200) + "\"}"));
            }
            byte[] body = ChronicleJournalStore.encode(batch);
            appender.writeBytes(b -> b.write(body));
            System.out.println("ACK " + seq + " " + i + " " + n);
            System.out.flush();
        }
    }
}
