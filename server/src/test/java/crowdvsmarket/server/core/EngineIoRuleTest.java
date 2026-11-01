package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The rule "no database and no network under the engine lock, none inside a writer turn" as a static check of the compiled classes
 * (architecture plan, docs/research/lmax_roadmap.md step 1; the runtime half is {@link IoGuard}). No library: {@link ClassFile} reads
 * the class files.
 *
 * <ol>
 *   <li><b>The pure modules</b> ({@code engine}, {@code bots}) name no class of the database, the network or the server.
 *   <li><b>The commands.</b> Every lambda that becomes a {@link Command} (what runs inside the writer's turn, under the engine lock)
 *       is a root of a call graph over the application's classes: the calls it makes, those of the methods it calls, an interface or
 *       abstract call going to every implementation in the application. No path may end in a <i>sink</i>: {@code Db}, {@code EventStore},
 *       {@code java.sql}/{@code javax.sql}/{@code org.postgresql}, {@code java.net} sockets and connections, {@code java.net.http}, the
 *       JAX-RS client and any {@code @RegisterRestClient} interface. No command is exempt (the solo commands are gone). A lambda built
 *       inside the walk and passed on as a {@code Db.SqlWork} or {@code Runnable} (the after-commit actions, which run after the engine
 *       lock is released) is not followed.
 *   <li><b>The writer's stages.</b> The logic thread ({@code CoreWriter}) and the output stage ({@code OutputStage}, with the batch and slot
 *       classes they share) reach no sink at all, again by the call graph - no database, no network, and not the journal: the logic thread
 *       never waits for the disk. The journal stage ({@code JournalStage}) is the one place where a batch is appended and synced
 *       ({@code EventStore.appendAll}, D-107: a Chronicle Queue on disk, no database); nothing else in it reaches a sink. The only boundary is
 *       the rebuild after a failed commit ({@code CoreWriter.recover}).
 *   <li><b>The journal is written by the journal stage only</b> ({@link #onlyTheJournalStageWritesTheJournalForTheCommands}): no other class of
 *       the application calls {@code EventStore.append}/{@code appendAll} but the ones listed there, which run with the pipeline drained.
 * </ol>
 * The graph follows calls it can see in the bytecode; a call through a {@code Supplier}, a {@code Function} or a reflective lookup is
 * not seen (the runtime guard sees those). The known exceptions are {@link #BOUNDARIES}: a method on the walk that is allowed to reach a
 * sink, with the reason and the plan step that removes it; an entry that no walk reaches any more is itself a failure (the debt is paid:
 * delete the entry).
 */
class EngineIoRuleTest {

    private static final String COMMAND = "Lcrowdvsmarket/server/core/Command;";
    private static final String WRITER = "crowdvsmarket/server/core/CoreWriter";
    /** The classes of the logic thread and the output stage: no sink at all. */
    private static final List<String> NO_IO_STAGES = List.of(WRITER, "crowdvsmarket/server/core/OutputStage",
            "crowdvsmarket/server/core/WriterSlot", "crowdvsmarket/server/core/WriterBatch");
    /** The journal stage: the one place that appends to the journal. */
    private static final String JOURNAL_STAGE = "crowdvsmarket/server/core/JournalStage";

    /**
     * Methods ({@code package/Class.method}, any overload) where the walk stops and the I/O behind is allowed, each with why and the
     * plan step that takes it away. Debts: new code does not get an entry without the owner's word.
     */
    static final Map<String, String> BOUNDARIES = new TreeMap<>(Map.ofEntries(
            Map.entry("crowdvsmarket/server/core/CoreWriter.recover", "the rebuild after a failed commit (EngineRebuildService.rebuildStateLocked: snapshot, "
                    + "journal, ledger rows, players, markets under the engine write lock). TODO plan F5/F7: rebuild off the lock and swap")));

    // ---------------------------------------------------------------------------------------

    private final Map<String, ClassFile> app = load();
    private final Map<String, Set<String>> subtypes = subtypes(app);

    @Test
    void thePureModulesNameNoDatabaseNetworkOrServerClass() {
        List<String> bad = new ArrayList<>();
        int checked = 0;
        for (ClassFile cf : app.values()) {
            if (!cf.name.startsWith("crowdvsmarket/engine/") && !cf.name.startsWith("crowdvsmarket/bots/")) {
                continue;
            }
            checked++;
            for (String ref : cf.classRefs) {
                if (isSinkClass(ref, null) || ref.startsWith("crowdvsmarket/server/") || ref.startsWith("jakarta/")
                        || ref.startsWith("io/quarkus/")) {
                    bad.add(cf.name + " refers to " + ref);
                }
            }
        }
        assertThat(checked).as("engine and bots classes scanned").isGreaterThan(100);
        assertThat(bad).as("engine/bots classes that reach for the database, the network or the server").isEmpty();
    }

    @Test
    void noCommandReachesTheDatabaseOrTheNetwork() {
        assertThat(commandViolations(new Walk())).as("commands (run under the engine lock) that reach the database or the network")
                .isEmpty();
    }

    @Test
    void theWriterTouchesTheDatabaseOnlyToCommitABatch() {
        assertThat(writerViolations(new Walk()))
                .as("CoreWriter methods that reach the database or the network outside the batch commit").isEmpty();
    }

    @Test
    void everyAllowedBoundaryIsStillReached() {
        Walk walk = new Walk();
        commandViolations(walk);
        writerViolations(walk);
        Set<String> unused = new HashSet<>(BOUNDARIES.keySet());
        unused.removeAll(walk.metBoundaries);
        assertThat(unused).as("allowed boundaries that no walk reaches any more (the debt is paid: delete the entry)").isEmpty();
    }

    private List<String> commandViolations(Walk walk) {
        List<String> roots = new ArrayList<>();
        Map<String, String> label = new LinkedHashMap<>();
        for (ClassFile cf : app.values()) {
            for (ClassFile.Method m : cf.methods) {
                boolean solo = m.calls.stream().anyMatch(call -> call.name().equals("writeSolo"));
                for (ClassFile.Lambda l : m.lambdas) {
                    if (l.interfaceType().equals(COMMAND) && !solo) {
                        String key = l.body().key();
                        roots.add(key);
                        label.put(key, cf.name + "." + m.name);
                    }
                }
            }
        }
        assertThat(roots).as("command lambdas found in the bytecode").hasSizeGreaterThan(20);
        List<String> violations = new ArrayList<>();
        for (String root : roots) {
            String path = walk.findSink(root);
            if (path != null) {
                violations.add("command in " + label.get(root) + ": " + path);
            }
        }
        return violations;
    }

    private List<String> writerViolations(Walk walk) {
        assertThat(app.get(WRITER)).isNotNull();
        assertThat(app.get(JOURNAL_STAGE)).isNotNull();
        walk.exemptEdges = (from, call) -> from.startsWith(JOURNAL_STAGE + ".write") && isCommitCall(call);
        List<String> violations = new ArrayList<>();
        int roots = 0;
        List<String> stages = new ArrayList<>(NO_IO_STAGES);
        stages.add(JOURNAL_STAGE);
        for (String stage : stages) {
            ClassFile cf = app.get(stage);
            assertThat(cf).as(stage).isNotNull();
            for (ClassFile.Method m : cf.methods) {
                roots++;
                if (BOUNDARIES.containsKey(stage + "." + m.name)) {
                    walk.metBoundaries.add(stage + "." + m.name);
                    continue; // the writer's own exception, named in BOUNDARIES
                }
                String path = walk.findSink(stage + "." + m.name + m.desc);
                if (path != null) {
                    violations.add(path);
                }
            }
        }
        assertThat(roots).isGreaterThan(30);
        return violations;
    }

    @Test
    void theJournalStageIsTheOneThatAppendsAndTheLogicThreadReachesNoDiskAtAll() {
        // the exemption is for the journal stage's write only: the same walk without it finds the append there, and nowhere else
        Walk walk = new Walk();
        assertThat(walk.findSink(JOURNAL_STAGE + ".write(Ljava/util/List;)V")).as("the journal stage's write reaches the journal")
                .isNotNull().contains("EventStore.appendAll");
        for (String stage : NO_IO_STAGES) {
            for (ClassFile.Method m : app.get(stage).methods) {
                if (BOUNDARIES.containsKey(stage + "." + m.name)) {
                    continue;
                }
                assertThat(walk.findSink(stage + "." + m.name + m.desc)).as(stage + "." + m.name).isNull();
            }
        }
    }

    /** The classes other than the stage that call {@code EventStore.append}/{@code appendAll}, each with why it may. */
    private static final Map<String, String> JOURNAL_WRITERS = new TreeMap<>(Map.of(
            "crowdvsmarket/server/events/EventStore", "append(Connection...) is appendAll with one event",
            "crowdvsmarket/server/core/JournalStage", "the stage",
            "crowdvsmarket/server/markets/DbMarketRegistry", "a registry that is not part of the running game appends its events itself "
                    + "(the running game's registry takes its turn on the writer)",
            "crowdvsmarket/server/service/SidecarReconciler", "the rebuild's reconciliation, with the pipeline drained",
            "crowdvsmarket/server/service/RoundCancellations", "the cancel of a round in a state built aside by the rebuild, with the "
                    + "pipeline drained"));

    @Test
    void onlyTheJournalStageWritesTheJournalForTheCommands() {
        List<String> callers = new ArrayList<>();
        for (ClassFile cf : app.values()) {
            for (ClassFile.Method m : cf.methods) {
                for (ClassFile.Call call : m.calls) {
                    if (call.owner().equals("crowdvsmarket/server/events/EventStore")
                            && (call.name().equals("append") || call.name().equals("appendAll"))
                            && !JOURNAL_WRITERS.containsKey(cf.name.replaceAll("\\$.*", ""))) {
                        callers.add(cf.name + "." + m.name);
                    }
                }
            }
        }
        assertThat(callers).as("classes that append to the journal without being the journal stage or a listed rebuild-time writer")
                .isEmpty();
    }

    @Test
    void theScanSeesASinkWhenThereIsOne() {
        // the scan itself: a walk from a method that is known to call Db must find it (a scan that finds nothing proves nothing)
        Walk walk = new Walk();
        assertThat(walk.findSink("crowdvsmarket/server/persistence/RegistrationIpRepository.record(Ljava/lang/String;J)V")).isNotNull()
                .contains("persistence/Db");
        // a sink two calls away is found too: the state rebuild reads the journal through the event store
        assertThat(walk.findSink("crowdvsmarket/server/scheduler/EngineRebuildService.rebuild()V")).isNotNull();
    }

    @Test
    void theClassFileReaderFindsTheCommandLambdas() {
        long lambdas = app.values().stream().flatMap(cf -> cf.methods.stream()).flatMap(m -> m.lambdas.stream())
                .filter(l -> l.interfaceType().equals(COMMAND)).count();
        assertThat(lambdas).isGreaterThan(20);
    }

    // ---------------------------------------------------------------------------------------

    private static boolean isCommitCall(ClassFile.Call call) {
        String o = call.owner();
        return o.equals("crowdvsmarket/server/events/EventStore") && call.name().equals("appendAll");
    }

    /** Whether calling a method of {@code owner} is reaching the database or the network. */
    private boolean isSinkClass(String owner, String methodName) {
        if (owner.equals("crowdvsmarket/server/persistence/Db")) {
            return methodName == null || !methodName.equals("json") && !methodName.equals("jsonb");
        }
        if (owner.equals("crowdvsmarket/server/events/EventStore")) {
            // marketOf/playerOf/resolveTypeAlias are pure; lastId is the journal's last seq (D-107: a Chronicle Queue on disk, no database)
            return methodName == null || !methodName.equals("marketOf") && !methodName.equals("playerOf")
                    && !methodName.equals("resolveTypeAlias") && !methodName.equals("lastId");
        }
        if (owner.startsWith("java/sql/") || owner.startsWith("javax/sql/") || owner.startsWith("org/postgresql/")
                || owner.startsWith("java/net/http/") || owner.startsWith("jakarta/ws/rs/client/")) {
            return true;
        }
        if (owner.startsWith("java/net/")) {
            return !(owner.equals("java/net/URI") || owner.equals("java/net/URLEncoder") || owner.equals("java/net/URLDecoder")
                    || owner.equals("java/net/URISyntaxException") || owner.equals("java/net/MalformedURLException")
                    || owner.equals("java/net/UnknownHostException"));
        }
        ClassFile cf = app.get(owner);
        return cf != null && cf.restClient;
    }

    /** One walk over the call graph; remembers which boundaries it met. */
    private final class Walk {
        final Set<String> metBoundaries = new HashSet<>();
        java.util.function.BiPredicate<String, ClassFile.Call> exemptEdges = (from, call) -> false;

        /** The shortest path from {@code root} (owner.name+desc) to a sink, as text, or null. */
        String findSink(String root) {
            Map<String, String> parent = new HashMap<>();
            Deque<String> queue = new ArrayDeque<>();
            parent.put(root, null);
            queue.add(root);
            while (!queue.isEmpty()) {
                String key = queue.poll();
                ClassFile.Method m = method(key);
                if (m == null) {
                    continue;
                }
                String owner = key.substring(0, key.indexOf('.'));
                String boundary = boundary(owner, m.name);
                if (boundary != null) {
                    metBoundaries.add(boundary);
                    continue;
                }
                for (ClassFile.Call call : m.calls) {
                    if (exemptEdges.test(key, call)) {
                        continue;
                    }
                    if (isSinkClass(call.owner(), call.name())) {
                        return render(parent, key) + " -> " + call.owner() + "." + call.name();
                    }
                    for (String target : targets(call)) {
                        if (!parent.containsKey(target)) {
                            parent.put(target, key);
                            queue.add(target);
                        }
                    }
                }
                for (ClassFile.Lambda l : m.lambdas) {
                    if (l.interfaceType().equals("Lcrowdvsmarket/server/persistence/Db$SqlWork;")
                            || l.interfaceType().equals("Ljava/lang/Runnable;")) {
                        continue; // staged writes and after-commit actions run after the lock is released
                    }
                    ClassFile.Call body = l.body();
                    if (isSinkClass(body.owner(), body.name())) {
                        return render(parent, key) + " -> " + body.owner() + "." + body.name();
                    }
                    for (String target : targets(body)) {
                        if (!parent.containsKey(target)) {
                            parent.put(target, key);
                            queue.add(target);
                        }
                    }
                }
            }
            return null;
        }

        private String boundary(String owner, String method) {
            String k = owner + "." + method;
            return BOUNDARIES.containsKey(k) ? k : null;
        }

        private String render(Map<String, String> parent, String key) {
            List<String> chain = new ArrayList<>();
            for (String k = key; k != null; k = parent.get(k)) {
                chain.add(k.replaceAll("\\(.*", "").replace("crowdvsmarket/server/", "").replace("crowdvsmarket/", ""));
            }
            Collections.reverse(chain);
            return String.join(" -> ", chain);
        }
    }

    private ClassFile.Method method(String key) {
        int dot = key.indexOf('.');
        ClassFile cf = app.get(key.substring(0, dot));
        if (cf == null) {
            return null;
        }
        String nameDesc = key.substring(dot + 1);
        for (ClassFile.Method m : cf.methods) {
            if ((m.name + m.desc).equals(nameDesc)) {
                return m;
            }
        }
        return null;
    }

    /** The methods a call may run: the declaration found up the supertypes of the owner, and every override in the application. */
    private List<String> targets(ClassFile.Call call) {
        List<String> out = new ArrayList<>();
        ClassFile owner = app.get(call.owner());
        if (owner == null) {
            return out;
        }
        String nameDesc = call.name() + call.desc();
        // up: the nearest declaration (a call on a subclass may be inherited)
        Deque<String> up = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        up.add(call.owner());
        while (!up.isEmpty()) {
            String c = up.poll();
            if (!seen.add(c)) {
                continue;
            }
            ClassFile cf = app.get(c);
            if (cf == null) {
                continue;
            }
            if (declares(cf, nameDesc)) {
                out.add(c + "." + nameDesc);
            }
            if (cf.superName != null) {
                up.add(cf.superName);
            }
            up.addAll(cf.interfaces);
        }
        // down: every class of the application that is a subtype and declares it
        for (String sub : subtypes.getOrDefault(call.owner(), Set.of())) {
            ClassFile cf = app.get(sub);
            if (cf != null && declares(cf, nameDesc)) {
                String t = sub + "." + nameDesc;
                if (!out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static boolean declares(ClassFile cf, String nameDesc) {
        for (ClassFile.Method m : cf.methods) {
            if ((m.name + m.desc).equals(nameDesc)) {
                return true;
            }
        }
        return false;
    }

    /** For every class, the classes of the application that extend or implement it, directly or not. */
    private static Map<String, Set<String>> subtypes(Map<String, ClassFile> app) {
        Map<String, Set<String>> direct = new HashMap<>();
        for (ClassFile cf : app.values()) {
            List<String> parents = new ArrayList<>(cf.interfaces);
            if (cf.superName != null) {
                parents.add(cf.superName);
            }
            for (String p : parents) {
                direct.computeIfAbsent(p, k -> new HashSet<>()).add(cf.name);
            }
        }
        Map<String, Set<String>> all = new HashMap<>();
        for (String root : direct.keySet()) {
            Set<String> seen = new HashSet<>();
            Deque<String> q = new ArrayDeque<>(direct.get(root));
            while (!q.isEmpty()) {
                String c = q.poll();
                if (seen.add(c)) {
                    q.addAll(direct.getOrDefault(c, Set.of()));
                }
            }
            all.put(root, seen);
        }
        return all;
    }

    // ---------------------------------------------------------------------------------------

    /** The classes of the server, the engine and the bots, read from where their classes are (a directory or a jar). */
    private static Map<String, ClassFile> load() {
        Map<String, ClassFile> out = new HashMap<>();
        for (Class<?> anchor : List.of(Command.class, crowdvsmarket.engine.round.Direction.class,
                crowdvsmarket.bots.catalog.Catalog.class)) {
            Path where;
            try {
                where = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
            try {
                if (Files.isDirectory(where)) {
                    try (Stream<Path> files = Files.walk(where)) {
                        for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                            ClassFile cf = ClassFile.parse(Files.readAllBytes(p));
                            out.put(cf.name, cf);
                        }
                    }
                } else {
                    try (JarFile jar = new JarFile(where.toFile())) {
                        for (var entries = jar.entries(); entries.hasMoreElements();) {
                            var e = entries.nextElement();
                            if (e.getName().endsWith(".class") && !e.getName().equals("module-info.class")) {
                                ClassFile cf = ClassFile.parse(jar.getInputStream(e).readAllBytes());
                                out.put(cf.name, cf);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }
}
