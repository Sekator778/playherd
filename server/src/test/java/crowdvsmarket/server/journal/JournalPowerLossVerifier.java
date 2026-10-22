package crowdvsmarket.server.journal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifier of the power-loss harness (tools/journal-powerloss). Args: journal dir, last ACKed seq, last ACKed batch. Opens the journal the way the
 * server does and checks what {@link JournalKillTest} checks: consecutive seqs from 1, everything acknowledged present, the last ACKed batch is
 * where it should be, batches whole, the journal takes a new append. Prints one {@code RESULT ...} line; exit 0 = pass, 1 = fail.
 */
public final class JournalPowerLossVerifier {

    private JournalPowerLossVerifier() {
    }

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        long ackedSeq = Long.parseLong(args[1]);
        int ackedBatch = Integer.parseInt(args[2]);
        List<String> problems = new ArrayList<>();
        int found = -1;
        try (ChronicleJournalStore store = ChronicleJournalStore.open(dir)) {
            List<JournalRecord> all = new ArrayList<>();
            store.replay(0, Long.MAX_VALUE, all::add);
            found = all.size();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).seq() != i + 1) {
                    problems.add("seq at position " + i + " is " + all.get(i).seq());
                    break;
                }
            }
            if (found < ackedSeq) {
                problems.add("LOST " + (ackedSeq - found) + " acknowledged events (acked up to seq " + ackedSeq + ", journal has " + found + ")");
            } else if (!all.get((int) ackedSeq - 1).payload().contains("\"batch\":" + ackedBatch + ",")) {
                problems.add("event at the last acked seq is not of batch " + ackedBatch);
            }
            int i = 0;
            while (i < all.size() && problems.isEmpty()) {
                String p = all.get(i).payload();
                int at = p.indexOf("\"batch\":") + 8;
                int b = Integer.parseInt(p.substring(at, p.indexOf(',', at)));
                int n = 1 + b % 5;
                if (i + n > all.size()) {
                    problems.add("batch " + b + " is not whole");
                    break;
                }
                i += n;
            }
            long next = store.append(List.of(new JournalRecord(0, "BetPlaced", 2, 0, null, null, "{\"after\":\"powerloss\"}")));
            if (next != found + 1) {
                problems.add("new append got seq " + next + ", expected " + (found + 1));
            }
        } catch (Throwable t) {
            problems.add("OPEN/READ FAILED: " + t);
        }
        System.out.println("RESULT " + (problems.isEmpty() ? "PASS" : "FAIL") + " acked=" + ackedSeq + " found=" + found + " " + problems);
        System.exit(problems.isEmpty() ? 0 : 1);
    }
}
