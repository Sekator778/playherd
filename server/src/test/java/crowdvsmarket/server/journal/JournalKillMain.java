package crowdvsmarket.server.journal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The child process of {@link JournalKillTest}: appends batches to the journal in {@code args[0]} for ever, and says {@code ACK <last seq> <batch>}
 * after each append has returned (that is, after its sync). The test kills it with SIGKILL at a moment it chooses.
 */
public final class JournalKillMain {

    private JournalKillMain() {
    }

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        int start = Integer.parseInt(args[1]);
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            for (int i = start;; i++) {
                int n = 1 + i % 5;
                List<JournalRecord> batch = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    batch.add(new JournalRecord(0, "BetPlaced", 2, i, "BTC", "p" + k, "{\"batch\":" + i + ",\"k\":" + k + ",\"pad\":\"" + "x".repeat(200) + "\"}"));
                }
                long last = store.append(batch);
                System.out.println("ACK " + last + " " + i + " " + n);
                System.out.flush();
            }
        }
    }
}
