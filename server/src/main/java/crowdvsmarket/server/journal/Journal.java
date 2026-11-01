package crowdvsmarket.server.journal;

import crowdvsmarket.server.persistence.Db;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The event journal (D-107): where the game's events live. The truth is the Chronicle Queue in {@code herd.journal.dir}
 * ({@link ChronicleJournalStore}); the {@code game_events} table is a copy filled after it by {@link JournalProjector}. The core writer's
 * batch is durable when {@link #append} returns.
 *
 * <p><b>Start.</b> {@link #open} opens the queue. An empty queue next to a non-empty {@code game_events} is the first start after the move to
 * the queue: the table is imported ({@link JournalCutover}), under a marker file next to the directory that says "import in progress", so
 * that a start after a crash in the middle of the import wipes the half-written queue and imports again (the table is untouched until the
 * queue has been verified). A table that is AHEAD of the queue (more events than the queue holds) is refused: the queue is the truth, and
 * something has damaged or replaced it.
 */
@ApplicationScoped
public class Journal {

    private static final Logger LOG = Logger.getLogger(Journal.class);

    @ConfigProperty(name = "herd.journal.dir")
    String dir;

    @Inject
    Db db;

    private volatile ChronicleJournalStore store;
    private volatile JournalCutover.Result cutover;
    /** Counts the resets of a test fixture; a reader that cached a position of an earlier life of the journal starts again. */
    private final AtomicLong generation = new AtomicLong();
    private final Object news = new Object();
    private Consumer<Long> cutoverHook;

    void onStart(@Observes @Priority(100) StartupEvent ev) {
        store(); // the import of the table, if there is one, happens here: before the engine is rebuilt
    }

    /** The open queue; opens it (and imports the table, once) on first use. */
    public ChronicleJournalStore store() {
        ChronicleJournalStore s = store;
        if (s == null) {
            synchronized (this) {
                s = store;
                if (s == null) {
                    s = open();
                    store = s;
                }
            }
        }
        return s;
    }

    private Path directory() {
        return Path.of(dir).toAbsolutePath().normalize();
    }

    private Path marker() {
        Path d = directory();
        return d.resolveSibling(d.getFileName() + ".importing");
    }

    private ChronicleJournalStore open() {
        Path d = directory();
        Path marker = marker();
        if (Files.exists(marker)) {
            LOG.warnf("an import of game_events into the journal did not finish (%s) - the half-written queue %s is wiped and the import "
                    + "runs again from the table", marker, d);
            wipe(d);
        }
        ChronicleJournalStore s = ChronicleJournalStore.open(d);
        try {
            long[] table = tableState();
            if (s.durableSeq() == 0 && table[0] > 0) {
                touch(marker);
                cutover = JournalCutover.run(db, s, cutoverHook);
                // the ids the table hands out by itself must stay above the imported ones (a row inserted without an id, by hand or by a test)
                db.tx(c -> db.queryOne(c, "SELECT setval(pg_get_serial_sequence('game_events', 'id'), ?) AS v",
                        rs -> rs.getLong("v"), cutover.lastId()));
            } else if (table[1] > s.durableSeq()) {
                throw new IllegalStateException("game_events holds events up to id " + table[1] + " but the journal in " + d
                        + " ends at " + s.durableSeq() + ": the journal is the truth and is behind its copy - it was damaged, replaced "
                        + "or restored from an older backup. Restore the journal files of the same time as the database, or empty "
                        + "game_events to start over; the server will not start on this");
            }
        } catch (RuntimeException | Error e) {
            s.close();
            throw e;
        }
        delete(marker); // also the marker of an interrupted import into an empty table
        LOG.infof("the journal is open: %s, last seq %d", d, s.durableSeq());
        return s;
    }

    /** [row count, max id] of {@code game_events}. */
    private long[] tableState() {
        return db.read(c -> {
            long[] state = new long[2];
            try (var ps = c.prepareStatement("SELECT COUNT(*), COALESCE(MAX(id), 0) FROM game_events"); var rs = ps.executeQuery()) {
                rs.next();
                state[0] = rs.getLong(1);
                state[1] = rs.getLong(2);
            }
            return state;
        });
    }

    /** What the import of the table did on this start, or {@code null} when there was none. */
    public JournalCutover.Result cutover() {
        return cutover;
    }

    /** For a test of the crash in the middle of the import. */
    public void cutoverHook(Consumer<Long> hook) {
        this.cutoverHook = hook;
    }

    /** The operator's view of the journal: {@code /api/admin/status} {@code journal}. */
    public record Status(String dir, long lastSeq, JournalCutover.Result importedFromTable, JournalProjector.Status projector) {
    }

    public Status status(JournalProjector.Status projector) {
        return new Status(directory().toString(), lastSeq(), cutover, projector);
    }

    /** The seq of the last event on disk. */
    public long lastSeq() {
        return store().durableSeq();
    }

    /** Appends the events of a batch as one excerpt and syncs it to disk; returns the seq of the last one. */
    public long append(List<JournalRecord> events) {
        long last = store().append(events);
        wake();
        return last;
    }

    /** Wakes a reader that waits for news. */
    void wake() {
        synchronized (news) {
            news.notifyAll();
        }
    }

    /** Waits up to {@code timeoutMs} for the journal to grow beyond {@code seq}. */
    public void awaitBeyond(long seq, long timeoutMs) throws InterruptedException {
        synchronized (news) {
            if (store().durableSeq() > seq) {
                return;
            }
            news.wait(timeoutMs);
        }
    }

    public long generation() {
        return generation.get();
    }

    /**
     * A test fixture's reset (the database is emptied, so the journal is): closes the queue, deletes its files and opens an empty one. Not
     * for the application; the game must not run while it happens ({@code GameService#runExclusively}).
     */
    public synchronized void resetForTests() {
        ChronicleJournalStore s = store;
        if (s != null) {
            s.close();
            store = null;
        }
        wipe(directory());
        delete(marker());
        cutover = null;
        generation.incrementAndGet();
        store = ChronicleJournalStore.open(directory());
        wake();
    }

    /**
     * For a test: empties the queue and imports the {@code game_events} table into it, as the first start after the move does (a fixture
     * that loaded rows of an old journal into the table).
     */
    public synchronized JournalCutover.Result reimportFromTableForTests() {
        resetForTests();
        cutover = JournalCutover.run(db, store, null);
        generation.incrementAndGet();
        wake();
        return cutover;
    }

    /** For a test of the start: closes the queue without touching its files; the next use opens it again, as a restart does. */
    public synchronized void closeForTests() {
        cutover = null;
        ChronicleJournalStore s = store;
        if (s != null) {
            store = null;
            s.close();
        }
    }

    /** For a test: the marker file that says an import of the table is in progress. */
    public Path importMarkerForTests() {
        return marker();
    }

    @PreDestroy
    void close() {
        ChronicleJournalStore s = store;
        if (s != null) {
            store = null;
            s.close();
        }
    }

    private static void touch(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "import in progress\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void wipe(Path d) {
        if (!Files.exists(d)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(d)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
