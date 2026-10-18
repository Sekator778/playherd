package herdbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramLogWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/** Shared pieces of the bench scenarios (design §3): parameters, the seeded sessions, the current betting rounds,
 *  the per-second result counters and the open-model ladder. All parameters are system properties {@code bench.*}
 *  (or the environment variable BENCH_X for {@code bench.x}); defaults are for a smoke run. */
public final class Common {
    private Common() {}

    public static String prop(String key, String def) {
        String v = System.getProperty("bench." + key);
        if (v == null) v = System.getenv("BENCH_" + key.toUpperCase().replace('.', '_'));
        return v == null || v.isBlank() ? def : v;
    }

    public static final String BASE_URL = prop("url", "http://server:8081");
    public static final ObjectMapper MAPPER = new ObjectMapper();

    public static HttpProtocolBuilder protocol() {
        // shareConnections: one pool for all virtual users, like wrk2's connections (a pool per user would measure TCP connects);
        // the cookie is sent as a plain header: the seeded session id is the whole credential (SessionService.playerIdFor)
        return http.baseUrl(BASE_URL)
                .shareConnections()
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .disableCaching()
                .maxConnectionsPerHost(Integer.parseInt(prop("maxconn", "2000")));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Seeded sessions: the file written by seed.sh (--cookies-out), lines "cookie<TAB>playerId"
    // ---------------------------------------------------------------------------------------------------------------
    public static String[] loadCookies(int limit) {
        Path file = Path.of(prop("cookies", "/results/cookies.tsv"));
        List<String> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null && out.size() < limit) {
                int tab = line.indexOf('\t');
                String c = tab < 0 ? line.trim() : line.substring(0, tab).trim();
                if (!c.isEmpty() && !c.startsWith("#")) out.add(c);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the sessions file " + file + ": " + e, e);
        }
        if (out.isEmpty()) throw new IllegalStateException("no sessions in " + file);
        return out.toArray(new String[0]);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rounds: the probe polls `markets` once a second and keeps, per row (market x series), the id of the round open for bets
    // ---------------------------------------------------------------------------------------------------------------
    public record Row(String marketId, String interval, long durationSec) {
        public String key() { return marketId + ":" + interval; }
    }

    public static final class Rounds {
        public static volatile Row[] rows = new Row[0];
        public static volatile String[] roundIds = new String[0]; // parallel to rows
        public static volatile long[] closesMs = new long[0];     // bettingClosesAtMs, parallel to rows
        public static volatile long[] durMs = new long[0];

        /** The round open for bets on row i at time nowMs: the id the probe saw, moved on by whole round lengths once its betting
         *  window has closed (the id is "<market>-<code>-<startMs>" and the next betting round starts when this one closes), so the
         *  second between a boundary and the next probe does not send bets to a closed round. */
        public static String idAt(int i, long nowMs) {
            String id = roundIds[i];
            long c = closesMs[i], d = durMs[i];
            if (d <= 0 || nowMs < c) return id;
            long k = (nowMs - c) / d + 1;
            return id.substring(0, id.lastIndexOf('-') + 1) + (c + k * d);
        }
        public static volatile long originCloseMs = 0;            // bettingClosesAtMs of the first HOURLY row seen (phase reference)
        public static volatile long originDurSec = 0;
        public static final AtomicLong probes = new AtomicLong();

        static void update(String body) {
            try {
                JsonNode root = MAPPER.readTree(body);
                List<Row> rs = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                List<long[]> times = new ArrayList<>();
                long originClose = 0, originDur = 0;
                for (JsonNode m : root.path("markets")) {
                    if (!"ACTIVE".equals(m.path("status").asText())) continue;
                    for (JsonNode s : m.path("series")) {
                        String id = s.path("bettingRoundId").asText("");
                        if (id.isEmpty()) continue;
                        rs.add(new Row(m.path("id").asText(), s.path("interval").asText(), s.path("durationSec").asLong()));
                        ids.add(id);
                        times.add(new long[] {s.path("bettingClosesAtMs").asLong(), s.path("durationSec").asLong() * 1000});
                        if (originClose == 0) { originClose = s.path("bettingClosesAtMs").asLong(); originDur = s.path("durationSec").asLong(); }
                    }
                }
                if (!rs.isEmpty()) {
                    closesMs = times.stream().mapToLong(t -> t[0]).toArray();
                    durMs = times.stream().mapToLong(t -> t[1]).toArray();
                    roundIds = ids.toArray(new String[0]);
                    rows = rs.toArray(new Row[0]);
                    if (originCloseMs == 0) { originCloseMs = originClose; originDurSec = originDur; }
                    probes.incrementAndGet();
                }
            } catch (Exception ignore) { /* keep the previous rounds; counted as a stale probe by the report */ }
        }
    }

    /** The probe: GET-like `markets` call once a second; its latency is logged under the name "probe_markets". */
    public static ChainBuilder probeChain() {
        return exec(http("probe_markets").post("/api/rpc/markets").body(StringBody("{\"args\":[]}"))
                .check(responseTimeInMillis().saveAs("prt"), bodyString().saveAs("pm"), status().is(200)))
                .exec(session -> { Rounds.update(session.getString("pm")); Stats.record("probe", session.getInt("prt")); return session; });
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Counters: results by (second since start, class); written at the end of the run by the simulation's after-hook
    // ---------------------------------------------------------------------------------------------------------------
    public static final class Stats {
        /** per second since t0, per class: how many and the response times (ms) as an HdrHistogram (3 significant digits) */
        record Cell(LongAdder n, ConcurrentHistogram h) {}
        public static final ConcurrentHashMap<String, LongAdder> byClass = new ConcurrentHashMap<>();
        public static final ConcurrentHashMap<Long, ConcurrentHashMap<String, Cell>> bySecond = new ConcurrentHashMap<>();
        public static volatile long t0Ms = System.currentTimeMillis();

        /** a virtual user arrived (its start, for the planned-versus-actual rate check, design R-7) */
        public static void arrived() { cell("_arrived", sec()).n.increment(); }

        private static long sec() { return (System.currentTimeMillis() - t0Ms) / 1000; }

        private static Cell cell(String cls, long sec) {
            return bySecond.computeIfAbsent(sec, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(cls, k -> new Cell(new LongAdder(), new ConcurrentHistogram(3)));
        }

        /** one finished call: class "ok" / error code / "http<status>", and Gatling's response time in ms */
        public static void record(String cls, int rtMs) {
            byClass.computeIfAbsent(cls, k -> new LongAdder()).increment();
            Cell c = cell(cls, sec());
            c.n.increment();
            c.h.recordValue(Math.max(0, Math.min(rtMs, 3_600_000)));
        }

        /** "ok" for 200, the server's error code for a rejected call, "http<status>" otherwise. */
        public static String classify(int status, String body) {
            if (status == 200) return "ok";
            if (body != null) {
                int i = body.indexOf("\"code\":\"");
                if (i >= 0) {
                    int j = body.indexOf('"', i + 8);
                    if (j > i) return body.substring(i + 8, j);
                }
            }
            return "http" + status;
        }

        public static void write(String dir, String name) {
            try {
                Files.createDirectories(Path.of(dir));
                try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(dir, name + ".counts.tsv")))) {
                    w.println("# t0Ms\t" + t0Ms + "\toriginCloseMs\t" + Rounds.originCloseMs + "\toriginDurSec\t" + Rounds.originDurSec
                            + "\tprobes\t" + Rounds.probes.get());
                    w.println("# totals");
                    for (Map.Entry<String, LongAdder> e : new TreeMap<>(byClass).entrySet()) w.println(e.getKey() + "\t" + e.getValue().sum());
                    w.println("# second\tclass\tcount");
                    for (Map.Entry<Long, ConcurrentHashMap<String, Cell>> s : new TreeMap<>(bySecond).entrySet())
                        for (Map.Entry<String, Cell> e : new TreeMap<>(s.getValue()).entrySet())
                            w.println(s.getKey() + "\t" + e.getKey() + "\t" + e.getValue().n.sum());
                }
                // the histograms, one per (second, class), in the standard HdrHistogram log format (tag = class)
                try (java.io.PrintStream ps = new java.io.PrintStream(Files.newOutputStream(Path.of(dir, name + ".hdrlog")))) {
                    HistogramLogWriter lw = new HistogramLogWriter(ps);
                    lw.setBaseTime(t0Ms);
                    lw.outputLogFormatVersion();
                    lw.outputStartTime(t0Ms);
                    lw.outputLegend();
                    for (Map.Entry<Long, ConcurrentHashMap<String, Cell>> s : new TreeMap<>(bySecond).entrySet())
                        for (Map.Entry<String, Cell> e : s.getValue().entrySet()) {
                            if (e.getKey().equals("_arrived")) continue;
                            Histogram h = e.getValue().h.copy();
                            h.setTag(e.getKey());
                            h.setStartTimeStamp(t0Ms + s.getKey() * 1000);
                            h.setEndTimeStamp(t0Ms + s.getKey() * 1000 + 1000);
                            lw.outputIntervalHistogram(h);
                        }
                }
            } catch (IOException e) { System.err.println("cannot write results: " + e); }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ladder: "rate:seconds,rate:seconds,..." -> consecutive open-model steps (Poisson arrivals); rate 0 = pause
    // ---------------------------------------------------------------------------------------------------------------
    public static OpenInjectionStep[] ladder(String spec) {
        List<OpenInjectionStep> steps = new ArrayList<>();
        steps.add(nothingFor(3)); // the probe learns the round ids first
        for (String part : spec.split(",")) {
            String[] rs = part.trim().split(":");
            double rate = Double.parseDouble(rs[0]);
            int secs = Integer.parseInt(rs[1]);
            if (rate <= 0) steps.add(nothingFor(secs));
            else steps.add(constantUsersPerSec(rate).during(secs).randomized());
        }
        return steps.toArray(new OpenInjectionStep[0]);
    }

    public static int ladderSeconds(String spec) {
        int t = 0;
        for (String part : spec.split(",")) t += Integer.parseInt(part.trim().split(":")[1]);
        return t;
    }
}
