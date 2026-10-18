package herdbench;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import org.HdrHistogram.Histogram;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/** Where the time goes (design §5): a summary of the Herd events of a JFR recording inside a time window.
 *  java -cp lib/*:classes herdbench.JfrSummary <file.jfr> <fromEpochMs> <toEpochMs>
 *  per window: herd.PlaceBet (whole call and phases), engine lock hold (by caller) and its utilisation, lock wait + queue length,
 *  transaction/commit, Rpc by method, Session, Settle, GC pauses, CPU load. Durations are microseconds (HdrHistogram, 3 digits). */
public final class JfrSummary {
    static final class H {
        final Histogram h = new Histogram(3_600_000_000L, 3); // us
        long sum;
        void add(long ns) { long us = Math.max(0, ns / 1000); h.recordValue(Math.min(us, 3_600_000_000L)); sum += ns; }
        String line() { return h.getTotalCount() == 0 ? "n=0" : String.format(Locale.ROOT, "n=%d p50=%d p99=%d p99.9=%d max=%d us, sum=%.2f s", h.getTotalCount(), h.getValueAtPercentile(50),
                h.getValueAtPercentile(99), h.getValueAtPercentile(99.9), h.getMaxValue(), sum / 1e9); }
    }

    public static void main(String[] a) throws Exception {
        Path file = Path.of(a[0]);
        long from = Long.parseLong(a[1]), to = Long.parseLong(a[2]);
        Map<String, H> hs = new TreeMap<>();
        Map<String, long[]> holdByCaller = new HashMap<>(); // caller -> {count, ns}
        Map<String, long[]> waitByCaller = new HashMap<>();
        long maxQueue = 0, queueSum = 0, queueN = 0, maxRoundBets = 0;
        Map<String, Long> outcomes = new TreeMap<>();
        long gcN = 0, gcSumNs = 0, gcMaxNs = 0; double cpuJvm = 0, cpuMach = 0; long cpuN = 0;
        List<String> settles = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                long startMs = e.getStartTime().toEpochMilli();
                if (startMs < from || startMs >= to) continue;
                String n = e.getEventType().getName();
                long durNs = e.getDuration().toNanos();
                switch (n) {
                    case "herd.PlaceBet" -> {
                        hs.computeIfAbsent("PlaceBet whole call", k -> new H()).add(durNs);
                        hs.computeIfAbsent("PlaceBet lock wait", k -> new H()).add(e.getLong("waitNs"));
                        hs.computeIfAbsent("PlaceBet round_results.exists", k -> new H()).add(e.getLong("existsNs"));
                        hs.computeIfAbsent("PlaceBet requireExposureRoom", k -> new H()).add(e.getLong("exposureNs"));
                        hs.computeIfAbsent("PlaceBet persist (tx)", k -> new H()).add(e.getLong("persistNs"));
                        hs.computeIfAbsent("PlaceBet eventApplier.apply", k -> new H()).add(e.getLong("applyNs"));
                        maxRoundBets = Math.max(maxRoundBets, e.getInt("roundBets"));
                        outcomes.merge(e.getString("outcome"), 1L, Long::sum);
                    }
                    case "herd.LockHold" -> {
                        hs.computeIfAbsent("LockHold " + e.getString("mode"), k -> new H()).add(durNs);
                        long[] x = holdByCaller.computeIfAbsent(e.getString("mode") + " " + caller(e.getStackTrace()), k -> new long[2]);
                        x[0]++; x[1] += durNs;
                    }
                    case "herd.LockWait" -> {
                        hs.computeIfAbsent("LockWait " + e.getString("mode"), k -> new H()).add(durNs);
                        int q = e.getInt("queueLength"); maxQueue = Math.max(maxQueue, q); queueSum += q; queueN++;
                        long[] x = waitByCaller.computeIfAbsent(e.getString("mode") + " " + caller(e.getStackTrace()), k -> new long[2]);
                        x[0]++; x[1] += durNs;
                    }
                    case "herd.TxCommit" -> {
                        hs.computeIfAbsent("Tx whole (Db.tx)", k -> new H()).add(durNs);
                        hs.computeIfAbsent("Tx commit()", k -> new H()).add(e.getLong("commitNs"));
                    }
                    case "herd.Rpc" -> hs.computeIfAbsent("Rpc " + e.getString("method"), k -> new H()).add(durNs);
                    case "herd.Session" -> hs.computeIfAbsent("Session SELECT", k -> new H()).add(durNs);
                    case "herd.Settle" -> settles.add(String.format(Locale.ROOT, "bets=%d lockedMs=%.1f applyMs=%.1f storeMs=%.1f appendMs=%.1f outcome=%s at=%s", e.getInt("bets"), e.getLong("lockedNs") / 1e6,
                            e.getLong("applyNs") / 1e6, e.getLong("storeNs") / 1e6, e.getLong("appendNs") / 1e6, e.getString("outcome"), e.getStartTime()));
                    case "jdk.GarbageCollection" -> { gcN++; long d = e.getDuration("sumOfPauses").toNanos(); gcSumNs += d; gcMaxNs = Math.max(gcMaxNs, e.getDuration("longestPause").toNanos()); }
                    case "jdk.CPULoad" -> { cpuJvm += e.getFloat("jvmUser") + e.getFloat("jvmSystem"); cpuMach += e.getFloat("machineTotal"); cpuN++; }
                    default -> { }
                }
            }
        }
        double wall = (to - from) / 1000.0;
        System.out.printf(Locale.ROOT, "window %s .. %s (%.0f s)%n", Instant.ofEpochMilli(from), Instant.ofEpochMilli(to), wall);
        hs.forEach((k, v) -> System.out.println(k + ": " + v.line()));
        long holdSum = hs.entrySet().stream().filter(x -> x.getKey().startsWith("LockHold")).mapToLong(x -> x.getValue().sum).sum();
        System.out.printf(Locale.ROOT, "engine lock utilisation (sum of outermost holds / wall) = %.1f %%%n", 100.0 * holdSum / 1e9 / wall);
        System.out.printf(Locale.ROOT, "lock wait queue length at arrival: mean %.2f, max %d (n=%d); max bets in a round of the bet's market: %d%n", queueN == 0 ? 0 : (double) queueSum / queueN, maxQueue, queueN, maxRoundBets);
        System.out.println("placeBet outcomes: " + outcomes);
        System.out.println("hold time by caller (top 8 by total):");
        holdByCaller.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(8)
                .forEach(x -> System.out.printf(Locale.ROOT, "  %-70s n=%d total=%.2f s mean=%.0f us%n", x.getKey(), x.getValue()[0], x.getValue()[1] / 1e9, x.getValue()[1] / 1e3 / x.getValue()[0]));
        System.out.println("wait time by caller (top 5 by total):");
        waitByCaller.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1])).limit(5)
                .forEach(x -> System.out.printf(Locale.ROOT, "  %-70s n=%d total=%.2f s mean=%.0f us%n", x.getKey(), x.getValue()[0], x.getValue()[1] / 1e9, x.getValue()[1] / 1e3 / x.getValue()[0]));
        System.out.printf(Locale.ROOT, "GC: %d collections, pauses sum %.1f ms, longest %.1f ms; JVM CPU mean %.1f %% of machine-share (jvm user+system %.3f, machine total %.3f, n=%d)%n", gcN, gcSumNs / 1e6, gcMaxNs / 1e6,
                cpuN == 0 ? 0 : 100 * cpuJvm / cpuN, cpuN == 0 ? 0 : cpuJvm / cpuN, cpuN == 0 ? 0 : cpuMach / cpuN, cpuN);
        settles.forEach(s -> System.out.println("Settle: " + s));
    }

    /** the first frame of the stack in a Herd class other than the lock itself (the method that took / released the lock) */
    static String caller(RecordedStackTrace st) {
        if (st == null) return "?";
        for (RecordedFrame f : st.getFrames()) {
            String c = f.getMethod().getType().getName();
            if (c.startsWith("crowdvsmarket.") && !c.contains(".jfr.")) return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethod().getName() + ":" + f.getLineNumber();
        }
        return "?";
    }
}
