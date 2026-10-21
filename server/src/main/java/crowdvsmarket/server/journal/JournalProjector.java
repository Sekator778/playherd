package crowdvsmarket.server.journal;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The copy of the journal in Postgres (Р-107): a thread tails the journal in seq order and writes, per batch, in ONE transaction
 * <ul>
 *   <li>the {@code game_events} rows with the seq as their id (multi-row {@code INSERT ... ON CONFLICT (id) DO NOTHING}, the payload as
 *       the journal holds it, which the {@code jsonb} column normalises exactly as it always did), and
 *   <li>the rows of the event-derived projections that are not written anywhere else: {@code players} (registration, nickname, strategy,
 *       retirement - {@link PlayerDirectory#projectEvents}).
 * </ul>
 * The table and the projection move together, so the position of the projector - the largest id in {@code game_events} - is exact: a
 * restart resumes after it, a transaction that did not commit left nothing, and a batch that is projected a second time (the table
 * already has its ids) is checked against what the table holds, never silently skipped. The projector only ever reads what the journal has
 * synced to disk. <b>The game never waits for it</b> (except in the test mode {@code herd.journal.project-synchronously}); its lag and its
 * failures are in {@code /api/admin/status} ({@link Status}).
 *
 * <p>The markets table, the result rows of rounds, the ledger rows and the oracle records are projected by their own paths (the registry
 * writes the market row beside its event; the round rows follow the settlement without the engine lock; the ledger by its outbox) and are
 * repaired from the journal by the rebuild, as before.
 *
 * <p>A rebuild of the engine calls {@link #catchUp} first, so that the tables it reads (players) hold everything the journal holds.
 */
@ApplicationScoped
public class JournalProjector {

    private static final Logger LOG = Logger.getLogger(JournalProjector.class);

    /** Rows per {@code INSERT}: seven parameters a row. */
    static final int INSERT_CHUNK = 500;

    private static final Set<String> PLAYER_EVENTS = Set.of("PlayerRegistered", "NicknameChanged", "BotStrategyAssigned", "BotRetired");

    @Inject
    Journal journal;
    @Inject
    EventStore eventStore;
    @Inject
    Db db;
    @Inject
    PlayerDirectory players;

    @ConfigProperty(name = "herd.journal.projector.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "herd.journal.projector.batch", defaultValue = "2000")
    int batchSize;

    /** One projection at a time: the thread and whoever calls {@link #catchUp}. */
    private final ReentrantLock projectLock = new ReentrantLock();
    private final Object progress = new Object();
    private volatile long projectedSeq = -1; // -1: not read from the table yet
    private ChronicleJournalStore cursorStore;
    private ChronicleJournalStore.Cursor cursor;
    private long cursorGeneration = -1;

    private volatile Thread thread;
    private volatile boolean running;
    private volatile long batches;
    private volatile long projectedEvents;
    private volatile long failures;
    private volatile long failingSinceMs;
    private volatile String lastError;
    private volatile long lastBatchAtMs;
    private volatile long lastBatchMs;

    /** A test's hook, called inside the transaction of a batch after its rows are written and before it commits (a crash in the middle of a batch). */
    private volatile Runnable beforeCommit;

    void onStart(@Observes @Priority(200) StartupEvent ev) {
        if (!enabled) {
            LOG.warn("the journal projector is switched off (herd.journal.projector.enabled=false): game_events is not filled");
            return;
        }
        running = true;
        thread = new Thread(this::loop, "herd-journal-projector");
        thread.setDaemon(true);
        thread.start();
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        long backoffMs = 200;
        while (running) {
            try {
                int n = step();
                backoffMs = 200;
                if (n == 0) {
                    journal.awaitBeyond(projectedSeq, 250L);
                }
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
            } catch (Throwable e) {
                recordFailure(e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    if (!running) {
                        return;
                    }
                }
                backoffMs = Math.min(backoffMs * 2, 5_000L);
            }
        }
    }

    private void recordFailure(Throwable e) {
        long now = System.currentTimeMillis();
        if (failures++ == 0 || failingSinceMs == 0) {
            failingSinceMs = now;
        }
        lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (failures == 1 || failures % 20 == 0) {
            LOG.errorf(e, "the journal projector failed (%d in a row): game_events falls behind the journal until it can write again; "
                    + "the game is not affected", failures);
        }
        projectLock.lock();
        try {
            closeCursor(); // start again from the table's position (called by the projector thread, which owns the cursor)
        } finally {
            projectLock.unlock();
        }
    }

    /** Projects the next batch with the projector thread's own cursor (a Chronicle tailer belongs to the thread that made it); returns how
     *  many events it wrote (0: nothing more is durable). */
    int step() {
        projectLock.lock();
        try {
            ChronicleJournalStore store = journal.store();
            if (cursor == null || cursorStore != store || cursorGeneration != journal.generation()) {
                closeCursor();
                readPosition();
                cursorStore = store;
                cursorGeneration = journal.generation();
                cursor = store.cursor(projectedSeq);
            }
            return stepWith(cursor);
        } finally {
            projectLock.unlock();
        }
    }

    /** Called holding the lock: the position is the largest id of the table. */
    private void readPosition() {
        projectedSeq = db.read(c -> {
            try (var ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM game_events"); var rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    /** One batch from {@code from}, the events after the projector's position only (a cursor may lag behind another thread's work). */
    private int stepWith(ChronicleJournalStore.Cursor from) {
        List<JournalRecord> records;
        while (true) {
            records = from.next(batchSize);
            if (records.isEmpty()) {
                if (failures > 0) {
                    failures = 0;
                    failingSinceMs = 0;
                    lastError = null;
                }
                return 0;
            }
            long position = projectedSeq;
            records.removeIf(r -> r.seq() <= position);
            if (!records.isEmpty()) {
                break;
            }
        }
        long t0 = System.nanoTime();
        project(records);
        long last = records.get(records.size() - 1).seq();
        synchronized (progress) {
            projectedSeq = last;
            progress.notifyAll();
        }
        batches++;
        projectedEvents += records.size();
        lastBatchAtMs = System.currentTimeMillis();
        lastBatchMs = (System.nanoTime() - t0) / 1_000_000L;
        if (failures > 0) {
            LOG.infof("the journal projector writes again after %d failure(s)", failures);
            failures = 0;
            failingSinceMs = 0;
            lastError = null;
        }
        return records.size();
    }

    private void closeCursor() {
        if (cursor != null) {
            try {
                cursor.close();
            } catch (RuntimeException e) {
                LOG.debug("closing the projector's cursor failed", e);
            }
            cursor = null;
        }
    }

    private void project(List<JournalRecord> records) {
        db.tx(c -> {
            long inserted = 0;
            for (int from = 0; from < records.size(); from += INSERT_CHUNK) {
                List<JournalRecord> chunk = records.subList(from, Math.min(records.size(), from + INSERT_CHUNK));
                StringBuilder sql = new StringBuilder(
                        "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version, market, player_id) VALUES ");
                Object[] params = new Object[chunk.size() * 7];
                for (int i = 0; i < chunk.size(); i++) {
                    sql.append(i == 0 ? "(?, ?, ?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?, ?, ?)");
                    JournalRecord r = chunk.get(i);
                    params[i * 7] = r.seq();
                    params[i * 7 + 1] = r.type();
                    params[i * 7 + 2] = Db.jsonText(r.payload());
                    params[i * 7 + 3] = r.atMs();
                    params[i * 7 + 4] = r.schemaVersion();
                    params[i * 7 + 5] = r.market();
                    params[i * 7 + 6] = r.playerId();
                }
                sql.append(" ON CONFLICT (id) DO NOTHING");
                inserted += db.update(c, sql.toString(), params);
            }
            if (inserted < records.size()) {
                verifyExisting(c, records); // part of this batch is in the table already: it must be the same rows
            }
            List<GameEvent> forPlayers = new ArrayList<>();
            for (JournalRecord r : records) {
                if (PLAYER_EVENTS.contains(r.type())) {
                    forPlayers.add(eventStore.decode(r));
                }
            }
            if (!forPlayers.isEmpty()) {
                players.projectEvents(c, forPlayers);
            }
            Runnable hook = beforeCommit;
            if (hook != null) {
                hook.run();
            }
            return null;
        });
    }

    /** The rows of {@code records} that were in the table already (an earlier run wrote them) are compared with the journal's. */
    private void verifyExisting(java.sql.Connection c, List<JournalRecord> records) throws SQLException {
        long first = records.get(0).seq();
        long last = records.get(records.size() - 1).seq();
        var existing = new java.util.HashMap<Long, String[]>();
        for (Object[] row : db.queryList(c, "SELECT id, event_type, payload::text FROM game_events WHERE id BETWEEN ? AND ?",
                rs -> new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)}, first, last)) {
            existing.put((Long) row[0], new String[] {(String) row[1], (String) row[2]});
        }
        for (JournalRecord r : records) {
            String[] have = existing.get(r.seq());
            if (have == null) {
                throw new IllegalStateException("game_events lacks id " + r.seq() + " after its batch was written");
            }
            if (!have[0].equals(r.type()) || !sameJson(have[1], r.payload())) {
                throw new IllegalStateException("game_events row " + r.seq() + " differs from the journal's event of that seq (table: "
                        + have[0] + ", journal: " + r.type() + ") - the copy is not the journal's; not overwriting it");
            }
        }
    }

    private boolean sameJson(String a, String b) {
        try {
            JsonNode x = db.json().readTree(a);
            JsonNode y = db.json().readTree(b);
            return x.equals(y);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return a.equals(b);
        }
    }

    /**
     * Projects everything the journal has synced so far, on the calling thread (a rebuild of the engine reads the tables, so it first has them
     * hold the journal). Runs the steps of the thread, which is held off meanwhile.
     */
    public void catchUp() {
        projectLock.lock();
        try {
            readPosition();
            try (ChronicleJournalStore.Cursor mine = journal.store().cursor(projectedSeq)) {
                while (stepWith(mine) > 0) {
                    // until the journal's durable end
                }
            }
        } finally {
            projectLock.unlock();
        }
    }

    /** Waits until the event with {@code seq} is in the table (the synchronous mode of the tests); gives up with a warning after {@code timeoutMs}. */
    public void awaitProjected(long seq, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (progress) {
            while (projectedSeq < seq) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    LOG.warnf("the journal projector has not written seq %d within %d ms (it is at %d, last error: %s)", seq, timeoutMs,
                            projectedSeq, lastError);
                    return;
                }
                try {
                    progress.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** For tests: the position, 0 or more once the table has been read. */
    public long projectedSeq() {
        return projectedSeq;
    }

    /** For tests: stops the thread (a crash of the projector); {@link #restartForTests} starts a new one. */
    public void stopForTests() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        projectLock.lock();
        try {
            cursor = null; // belonged to the stopped thread
            projectedSeq = -1; // a restarted process knows nothing but the table
        } finally {
            projectLock.unlock();
        }
    }

    public void restartForTests() {
        if (thread == null) {
            running = true;
            thread = new Thread(this::loop, "herd-journal-projector");
            thread.setDaemon(true);
            thread.start();
        }
    }

    public void beforeCommitForTests(Runnable hook) {
        this.beforeCommit = hook;
    }

    /** The operator's view: {@code /api/admin/status} {@code journal.projector}. */
    public record Status(boolean running, long projectedSeq, long journalSeq, long lag, long batches, long events, long failures,
                         long failingSinceMs, String lastError, long lastBatchAtMs, long lastBatchMs) {
    }

    public Status status() {
        long journalSeq = journal.lastSeq();
        long projected = Math.max(0, projectedSeq);
        return new Status(thread != null && thread.isAlive(), projected, journalSeq, Math.max(0, journalSeq - projected), batches,
                projectedEvents, failures, failingSinceMs, lastError, lastBatchAtMs, lastBatchMs);
    }
}
