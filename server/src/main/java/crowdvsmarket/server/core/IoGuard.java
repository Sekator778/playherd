package crowdvsmarket.server.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The runtime half of the rule "no database and no network under the engine lock, none inside a turn of the core writer"
 * (architecture plan, docs/research/lmax_roadmap.md step 1; the static half is {@code EngineIoRuleTest}).
 *
 * <p>{@link crowdvsmarket.server.persistence.Db} and the HTTP clients call {@link #check} before they reach for a connection or a
 * socket. The check does nothing unless the system property {@code herd.io-guard} is {@code record} or {@code fail} (the server tests
 * run with {@code fail}, see {@code server/pom.xml}); in production it is one volatile read. When on, the call is a violation if the
 * calling thread
 * <ul>
 *   <li>holds the engine <b>write</b> lock ({@link crowdvsmarket.server.jfr.TimedEngineLock} tells {@link #writeLockAcquired} /
 *       {@link #writeLockReleasing}; the read lock is not counted), or
 *   <li>is the core writer's thread ({@link #WRITER_THREAD}) and is not in the batch commit ({@code Db.tx} called by
 *       {@code CoreWriter.runStretch}, which runs with the engine lock released).
 * </ul>
 * unless a frame of its stack is in {@link #ALLOWED}: the known exceptions, each with its reason and the step of the plan that removes
 * it. A violation is recorded (the file {@code target/io-guard/*.txt}, written when the JVM ends) and, in mode {@code fail}, thrown
 * as an {@link IllegalStateException} at the call - so a test that reaches one fails there.
 *
 * <p>Frames are matched by {@code SimpleClass.method}; the body of a lambda counts as the method it is written in.
 */
public final class IoGuard {

    /** The name of the core writer's thread. */
    public static final String WRITER_THREAD = "herd-core-writer";

    /** How the guard reacts. */
    public enum Mode {
        OFF, RECORD, FAIL;

        static Mode parse(String value) {
            if (value == null || value.isBlank()) {
                return OFF;
            }
            return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "record" -> RECORD;
                case "fail" -> FAIL;
                case "off", "false" -> OFF;
                default -> throw new IllegalArgumentException("herd.io-guard must be off, record or fail, not '" + value + "'");
            };
        }
    }

    /**
     * The known exceptions: a frame (SimpleClass.method) on the stack of the call makes it allowed. Every entry names why and the plan
     * step that takes it away (docs/research/lmax_roadmap.md; docs/research/architecture_review_independent_2026-10-16.md §F).
     * An entry is a debt: new code does not get one without the owner's word.
     */
    static final Map<String, String> ALLOWED = new TreeMap<>(Map.ofEntries(
            Map.entry("CoreWriter.runSolo", "a solo command needs the database's answer to decide and runs alone, between two batches, "
                    + "holding the engine lock across its own transactions (the CoreWriter class comment): the market list and close "
                    + "of the registry, creditOnChainDeposit. TODO plan step 3-4: give each solo command its answer before the turn or "
                    + "after the commit, so none is left"),
            Map.entry("EngineRebuildService.rebuildStateLocked", "the rebuild of the engine (startup, an operator's, after a failed "
                    + "commit through CoreWriter.recover, the scheduler's retry) reads snapshot, journal, ledger rows, players, markets "
                    + "and the oracle counter holding the engine write lock. TODO plan F5/F7: rebuild into a copy of the state off the "
                    + "lock and swap it in"),
            Map.entry("SnapshotService.take", "the snapshot reads the journal's last id inside GameService.runExclusively so that it is "
                    + "taken between two batches. TODO plan F5: take the position from the writer's own counter (EventStore.lastId "
                    + "needs no database once the writer knows the id of its last event)"),
            Map.entry("GameService.publishAllViews", "the views are published with the journal's last id as their version, read "
                    + "under the lock the rebuild and the exclusive actions hold. TODO plan F8: the writer hands the version over "
                    + "(CoreWriter.Host.committed already does after a batch)"),
            Map.entry("GameService.ensureSeriesLocked", "seeding the series of a market reads the last settled window of the "
                    + "database (SeriesSeeder.seedFor), in the turn of the command that lists a market or on the scheduler holding "
                    + "the lock. TODO plan step 1 'database query out from under the lock': read before the turn, pass in"),
            Map.entry("RoundCancellations.cancel", "the cancellation of a round looks the venue of the round up in the database "
                    + "(RoundVenues.venueOfRound) inside the writer's turn. TODO plan step 1 'database query out from under the "
                    + "lock': look it up before the turn, or keep the venues in the engine state (F4)")));

    /** Exceptions registered at run time by test fixtures ({@link #allow}); never by the application. */
    private static final Map<String, String> FIXTURES = new ConcurrentHashMap<>();

    /** A test fixture that has to do I/O under the engine lock (a database reset inside {@code runExclusively}) says so here, once;
     *  {@code frame} is {@code SimpleClass.method} as in {@link #ALLOWED}. */
    public static void allow(String frame, String reason) {
        FIXTURES.put(frame, reason);
    }

    private static volatile Mode mode = Mode.parse(System.getProperty("herd.io-guard"));

    private static final ThreadLocal<int[]> WRITE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final Map<String, Hit> VIOLATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Hit> ALLOWED_HITS = new ConcurrentHashMap<>();
    private static final AtomicInteger HOOKS = new AtomicInteger();

    private static final class Hit {
        final String detail;
        final AtomicLong count = new AtomicLong();

        Hit(String detail) {
            this.detail = detail;
        }
    }

    private IoGuard() {
    }

    public static Mode mode() {
        return mode;
    }

    /** For the guard's own test: switches the mode at runtime (no engine lock may be held by any thread at that moment). */
    public static void setMode(Mode m) {
        mode = m;
    }

    /** {@link crowdvsmarket.server.jfr.TimedEngineLock}: the calling thread took the engine write lock (outermost hold). */
    public static void writeLockAcquired() {
        if (mode != Mode.OFF) {
            WRITE_DEPTH.get()[0]++;
        }
    }

    /** {@link crowdvsmarket.server.jfr.TimedEngineLock}: the calling thread releases the outermost hold of the write lock. */
    public static void writeLockReleasing() {
        if (mode != Mode.OFF) {
            int[] depth = WRITE_DEPTH.get();
            if (depth[0] > 0) {
                depth[0]--;
            }
        }
    }

    /** Whether the calling thread holds the engine write lock (as far as the guard has seen; false while the guard is off). */
    public static boolean holdsEngineWriteLock() {
        return mode != Mode.OFF && WRITE_DEPTH.get()[0] > 0;
    }

    /**
     * Called before a database connection or a network call.
     *
     * @param kind {@code database} or {@code network}
     * @param what the call, for the message
     */
    public static void check(String kind, String what) {
        Mode m = mode;
        if (m == Mode.OFF) {
            return;
        }
        boolean locked = WRITE_DEPTH.get()[0] > 0;
        boolean writer = WRITER_THREAD.equals(Thread.currentThread().getName());
        if (!locked && !writer) {
            return;
        }
        List<StackWalker.StackFrame> frames = StackWalker.getInstance().walk(s -> s.toList());
        for (int i = 0; i + 1 < frames.size(); i++) {
            if ((is(frames.get(i), "crowdvsmarket.server.persistence.Db", "tx")
                    || is(frames.get(i), "crowdvsmarket.server.persistence.Db_ClientProxy", "tx")) // the CDI proxy in between
                    && is(frames.get(i + 1), "crowdvsmarket.server.core.CoreWriter", "runStretch")) {
                return; // the commit of a batch: the writer's own database turn, the engine lock released
            }
        }
        String chain = chain(frames);
        for (StackWalker.StackFrame f : frames) {
            String key = key(f);
            String reason = ALLOWED.getOrDefault(key, FIXTURES.get(key));
            if (reason != null) {
                note(ALLOWED_HITS, key + " <- " + kind, kind + " " + what + " allowed by " + key + " (" + reason + "): " + chain);
                return;
            }
        }
        String why = (locked ? "holds the engine write lock" : "") + (locked && writer ? " and " : "")
                + (writer ? "is the core writer's thread outside the batch commit" : "");
        String detail = kind + " " + what + " by a thread that " + why + ": " + chain;
        note(VIOLATIONS, kind + " " + chain, detail);
        if (m == Mode.FAIL) {
            throw new IllegalStateException("engine I/O rule: " + detail
                    + " - no database and no network under the engine lock or inside a writer turn (crowdvsmarket.server.core.IoGuard)");
        }
    }

    private static boolean is(StackWalker.StackFrame f, String cls, String method) {
        return f.getClassName().equals(cls) && f.getMethodName().equals(method);
    }

    /** {@code SimpleClass.method}; a lambda body is the method it is written in. */
    static String key(StackWalker.StackFrame f) {
        String cls = f.getClassName();
        cls = cls.substring(cls.lastIndexOf('.') + 1);
        int dollar = cls.indexOf('$');
        if (dollar > 0) {
            cls = cls.substring(0, dollar);
        }
        String method = f.getMethodName();
        if (method.startsWith("lambda$")) {
            int last = method.lastIndexOf('$');
            method = method.substring("lambda$".length(), last > "lambda$".length() ? last : method.length());
        }
        return cls + "." + method;
    }

    /** The first frames of the application on the stack, nearest first. */
    private static String chain(List<StackWalker.StackFrame> frames) {
        List<String> out = new ArrayList<>();
        for (StackWalker.StackFrame f : frames) {
            String cn = f.getClassName();
            if (!cn.startsWith("crowdvsmarket.") || cn.equals(IoGuard.class.getName())
                    || cn.startsWith("crowdvsmarket.server.persistence.Db") && out.isEmpty()) {
                continue;
            }
            String k = key(f);
            if (out.isEmpty() || !out.get(out.size() - 1).equals(k)) {
                out.add(k);
            }
            if (out.size() == 6) {
                break;
            }
        }
        return String.join(" < ", out);
    }

    private static void note(Map<String, Hit> into, String key, String detail) {
        into.computeIfAbsent(key, k -> new Hit(detail)).count.incrementAndGet();
        if (HOOKS.getAndIncrement() == 0) {
            Runtime.getRuntime().addShutdownHook(new Thread(IoGuard::dump, "herd-io-guard-dump"));
        }
    }

    private static void dump() {
        if (VIOLATIONS.isEmpty() && ALLOWED_HITS.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        VIOLATIONS.values().stream().map(h -> "VIOLATION x" + h.count + ": " + h.detail).sorted().forEach(l -> sb.append(l).append('\n'));
        ALLOWED_HITS.values().stream().map(h -> "ALLOWED x" + h.count + ": " + h.detail).sorted().forEach(l -> sb.append(l).append('\n'));
        try {
            Path dir = Path.of("target", "io-guard");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ProcessHandle.current().pid() + "-" + System.nanoTime() + ".txt"), sb.toString());
        } catch (IOException e) {
            // the guard's report is best effort; the violation itself was thrown or is in the log of the caller
        }
    }

    /** For the guard's own test: what has been recorded as a violation (keys), and forgets it. */
    static List<String> takeViolations() {
        List<String> keys = new ArrayList<>(VIOLATIONS.keySet());
        VIOLATIONS.clear();
        return keys;
    }
}
