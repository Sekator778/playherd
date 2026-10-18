package herdbench;

import org.HdrHistogram.EncodableHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogReader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Offline report of one Gatling run of the bench: per ladder step, the planned and the actual arrival rate (design R-7), the accepted
 *  responses per second, the rejects by code, and p50/p99/p99.9/max of the response times from the HdrHistogram log, split into
 *  "mid-round" and "boundary" seconds (the seconds around a round boundary, where the settlement runs under the engine lock).
 *  java -cp lib/*:classes herdbench.Report <prefix> <ladder "rate:sec,..."> [skipSec=5] [beforeSec=5] [afterSec=10] [cls=ok]
 *  reads <prefix>.hdrlog and <prefix>.counts.tsv, prints a markdown table and writes <prefix>.steps.csv. */
public final class Report {
    record Step(double rate, int sec, int from) {}

    public static void main(String[] a) throws Exception {
        String prefix = a[0];
        String spec = a[1];
        int skip = a.length > 2 ? Integer.parseInt(a[2]) : 5;
        int before = a.length > 3 ? Integer.parseInt(a[3]) : 5;
        int after = a.length > 4 ? Integer.parseInt(a[4]) : 10;
        String cls = a.length > 5 ? a[5] : "ok";

        long t0 = 0, originClose = 0, originDur = 0;
        Map<Long, Map<String, Long>> counts = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of(prefix + ".counts.tsv"))) {
            String[] f = line.split("\t");
            if (line.startsWith("# t0Ms")) { t0 = Long.parseLong(f[1]); originClose = Long.parseLong(f[3]); originDur = Long.parseLong(f[5]); }
            else if (!line.startsWith("#") && f.length == 3) counts.computeIfAbsent(Long.parseLong(f[0]), k -> new HashMap<>()).merge(f[1], Long.parseLong(f[2]), Long::sum);
        }
        List<Step> steps = new ArrayList<>();
        int off = 3; // Common.ladder starts with nothingFor(3)
        for (String part : spec.split(",")) {
            String[] rs = part.trim().split(":");
            int secs = Integer.parseInt(rs[1]);
            double rate = Double.parseDouble(rs[0]);
            if (rate > 0) steps.add(new Step(rate, secs, off));
            off += secs;
        }
        // histograms per (second, class)
        Map<Long, Map<String, Histogram>> hist = new TreeMap<>();
        HistogramLogReader reader = new HistogramLogReader(new File(prefix + ".hdrlog"));
        EncodableHistogram eh;
        while ((eh = reader.nextIntervalHistogram()) != null) {
            long sec = Math.round((eh.getStartTimeStamp() - t0) / 1000.0);
            hist.computeIfAbsent(sec, k -> new HashMap<>()).put(eh.getTag(), (Histogram) eh);
        }
        final long fo = originClose, fd = originDur * 1000, ft0 = t0;
        java.util.function.LongPredicate nearBoundary = sec -> {
            if (fd <= 0) return false;
            long rel = Math.floorMod(ft0 + sec * 1000 - fo, fd); // 0 = just after a boundary
            return rel < after * 1000L || rel >= fd - before * 1000L;
        };

        StringBuilder md = new StringBuilder();
        StringBuilder csv = new StringBuilder("step,planned_per_s,arrived_per_s,responses_per_s,ok_per_s,rejected_per_s,rejects,"
                + "p50_ms,p99_ms,p99.9_ms,max_ms,mid_p50_ms,mid_p99_ms,mid_p99.9_ms,mid_max_ms,bnd_p50_ms,bnd_p99_ms,bnd_p99.9_ms,bnd_max_ms,mid_n,bnd_n\n");
        md.append("| ступень | план/с | пришло/с | ответов/с | принято/с | отклонено (код: число) | p50 | p99 | p99,9 | max | mid p99,9 | граница p99,9 (n) |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        int idx = 0;
        for (Step st : steps) {
            idx++;
            long lo = st.from + skip, hi = st.from + st.sec; // [lo, hi)
            long arrived = 0, ok = 0, rej = 0, all = 0;
            Map<String, Long> rejects = new TreeMap<>();
            Histogram hAll = new Histogram(3_600_000, 3), hMid = new Histogram(3_600_000, 3), hBnd = new Histogram(3_600_000, 3);
            for (long s = lo; s < hi; s++) {
                Map<String, Long> c = counts.getOrDefault(s, Map.of());
                arrived += c.getOrDefault("_arrived", 0L);
                for (var e : c.entrySet()) {
                    if (e.getKey().equals("_arrived") || e.getKey().equals("probe") || e.getKey().equals("skipped_no_row")) continue;
                    all += e.getValue();
                    if (e.getKey().equals("ok") || e.getKey().startsWith("ok_")) ok += e.getValue();
                    else { rej += e.getValue(); rejects.merge(e.getKey(), e.getValue(), Long::sum); }
                }
                for (var e : hist.getOrDefault(s, Map.of()).entrySet()) {
                    if (!(e.getKey().equals(cls) || (cls.equals("ok") && e.getKey().startsWith("ok_")))) continue;
                    hAll.add(e.getValue());
                    (nearBoundary.test(s) ? hBnd : hMid).add(e.getValue());
                }
            }
            double len = hi - lo;
            md.append(String.format(Locale.ROOT, "| %d | %.0f | %.1f | %.1f | %.1f | %s | %s | %s | %s | %s | %s | %s (%d) |\n", idx, st.rate, arrived / len, all / len, ok / len,
                    rejects.isEmpty() ? "-" : rejects.toString(), pct(hAll, 50), pct(hAll, 99), pct(hAll, 99.9), mx(hAll), pct(hMid, 99.9), pct(hBnd, 99.9), hBnd.getTotalCount()));
            csv.append(String.format(Locale.ROOT, "%d,%.0f,%.2f,%.2f,%.2f,%.2f,\"%s\",%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d\n", idx, st.rate, arrived / len, all / len, ok / len, rej / len, rejects,
                    pct(hAll, 50), pct(hAll, 99), pct(hAll, 99.9), mx(hAll), pct(hMid, 50), pct(hMid, 99), pct(hMid, 99.9), mx(hMid), pct(hBnd, 50), pct(hBnd, 99), pct(hBnd, 99.9), mx(hBnd), hMid.getTotalCount(), hBnd.getTotalCount()));
        }
        Files.writeString(Path.of(prefix + ".steps.csv"), csv.toString());
        System.out.print(md);
    }

    private static String pct(Histogram h, double p) { return h.getTotalCount() == 0 ? "-" : String.valueOf(h.getValueAtPercentile(p)); }
    private static String mx(Histogram h) { return h.getTotalCount() == 0 ? "-" : String.valueOf(h.getMaxValue()); }
}
