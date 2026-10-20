package crowdvsmarket.server.snapshot;

import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Snapshots of the engine state (architecture plan F5, docs/research/lmax_roadmap.md step 4): a restart loads the newest snapshot
 * that verifies and replays only the journal after it ({@code EngineStateBuilder}), instead of every event ever written.
 *
 * <p><b>Taking.</b> A snapshot is taken between two batches of the core writer: {@link GameService#runExclusively} holds the
 * writer's gate and the engine lock, so memory is exactly what the journal holds, and {@code seq} is the id of the journal's last
 * row. Only the encoding of the state to text ({@link StateCodec#encode}) happens under the lock; compressing, hashing and the
 * write to {@code engine_snapshots} run on a thread of their own, so the writer is not held by the database. A snapshot is taken when
 * {@code herd.snapshot.every-events} journal rows have been added since the last one, or {@code herd.snapshot.every-minutes} have
 * passed and there is anything new; the newest {@code herd.snapshot.keep} are kept.
 *
 * <p><b>Trusting.</b> A snapshot is a cache of the journal. {@link #verified} gives a row's state only when its format version is
 * the one this code reads and the SHA-256 of the decompressed text equals the stored one; anything else is ignored with a log line
 * and never loaded. The nightly {@link #check} replays the journal from zero up to the newest snapshot's {@code seq} and compares the
 * two digests: a difference is an ERROR in the log and {@code snapshots.lastCheck} of the admin status.
 */
@ApplicationScoped
public class SnapshotService {

    private static final Logger LOG = Logger.getLogger(SnapshotService.class);

    @Inject
    EngineHub hub;
    @Inject
    BetFeed betFeed;
    @Inject
    EventStore eventStore;
    @Inject
    EngineSnapshotRepository repository;
    @Inject
    GameService gameService;
    @Inject
    EngineRebuildService rebuildService;
    @Inject
    MarketRegistry marketRegistry;

    /** Master switch: off, nothing is taken and a rebuild replays the whole journal. */
    @ConfigProperty(name = "herd.snapshot.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "herd.snapshot.every-events", defaultValue = "50000")
    long everyEvents;
    @ConfigProperty(name = "herd.snapshot.every-minutes", defaultValue = "60")
    long everyMinutes;
    @ConfigProperty(name = "herd.snapshot.keep", defaultValue = "3")
    int keep;

    /** A snapshot that passed its checks: the canonical state text and where in the journal it stands. */
    public record Verified(long seq, long takenAtMs, String stateSha256, String text) {
    }

    /** The outcome of one {@link #check}. {@code ok} is {@code null} when there was nothing to compare (no snapshot). */
    public record Check(long atMs, long seq, Boolean ok, String expectedSha256, String replayedSha256, String note) {
    }

    /** What the admin status shows. {@code lastCheck} is {@code null} until the first check has run. */
    public record Status(boolean enabled, int stored, Long lastSeq, Long lastTakenAtMs, long journalLastId, String lastError,
                         Check lastCheck) {
    }

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "herd-snapshot-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastSeq = -1;
    private volatile long lastTakenAtMs;
    private volatile String lastError;
    private volatile Check lastCheck;

    @PreDestroy
    void stop() {
        writer.shutdown();
    }

    public boolean enabled() {
        return enabled;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // taking
    // ---------------------------------------------------------------------------------------------------------------

    /** The scheduler's cue: takes a snapshot when the thresholds say so. */
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "herd-snapshot")
    void tick() {
        if (!enabled || !rebuildService.isReady()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long last = knownLastSeq();
            long head = eventStore.lastId();
            long added = head - last;
            boolean byCount = added >= everyEvents;
            boolean byTime = added > 0 && now - lastTakenAtMs >= everyMinutes * 60_000L;
            if (byCount || byTime) {
                take(now);
            }
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOG.error("the engine snapshot could not be taken", e);
        }
    }

    private long knownLastSeq() {
        if (lastSeq < 0) {
            List<EngineSnapshotRepository.Meta> stored = repository.list();
            lastSeq = stored.isEmpty() ? 0 : stored.get(0).seq();
            lastTakenAtMs = stored.isEmpty() ? 0 : stored.get(0).takenAtMs();
        }
        return lastSeq;
    }

    /**
     * Takes a snapshot of the state as it is now: the encoding under the writer's gate and the engine lock, the rest on the
     * snapshot thread. The ledger rows the engine has not written yet are written first, so a snapshot does not stand ahead
     * of its {@code ledger_entries} more than it must.
     *
     * @return the stored snapshot once it is written; completes exceptionally when the write fails
     * @throws IllegalStateException the engine is not ready (its memory cannot be trusted)
     */
    public CompletableFuture<EngineSnapshotRepository.Meta> take(long nowMs) {
        if (!rebuildService.isReady()) {
            throw new IllegalStateException("the engine is not ready: no snapshot of it");
        }
        gameService.flushLedgerRows();
        long[] seq = new long[1];
        String[] text = new String[1];
        gameService.runExclusively(() -> {
            seq[0] = eventStore.lastId();
            text[0] = StateCodec.encode(hub, betFeed);
        });
        return CompletableFuture.supplyAsync(() -> write(seq[0], nowMs, text[0]), writer);
    }

    private EngineSnapshotRepository.Meta write(long seq, long takenAtMs, String text) {
        try {
            String sha = StateDigest.sha256(text);
            repository.put(seq, StateCodec.FORMAT_VERSION, takenAtMs, sha, gzip(text));
            int dropped = repository.keepNewest(keep);
            lastSeq = Math.max(lastSeq, seq);
            lastTakenAtMs = takenAtMs;
            lastError = null;
            LOG.infof("engine snapshot taken at journal row %d (%d bytes of state, %d older one(s) dropped)", seq,
                    text.length(), dropped);
            return new EngineSnapshotRepository.Meta(seq, StateCodec.FORMAT_VERSION, takenAtMs, sha);
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOG.error("the engine snapshot at journal row " + seq + " could not be written", e);
            throw e;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // trusting
    // ---------------------------------------------------------------------------------------------------------------

    /** The stored snapshots, newest first, without their bodies. */
    public List<EngineSnapshotRepository.Meta> stored() {
        return repository.list();
    }

    /**
     * The state of the snapshot {@code meta} describes, or empty (with a log line saying why) when it must not be loaded: a format
     * version this code does not read, a body that does not decompress, a hash that is not the stored one.
     */
    public Optional<Verified> verified(EngineSnapshotRepository.Meta meta) {
        if (meta.formatVersion() != StateCodec.FORMAT_VERSION) {
            LOG.warnf("engine snapshot %d ignored: format version %d, this server reads %d", meta.seq(), meta.formatVersion(),
                    StateCodec.FORMAT_VERSION);
            return Optional.empty();
        }
        EngineSnapshotRepository.Stored row = repository.find(meta.seq());
        if (row == null) {
            LOG.warnf("engine snapshot %d ignored: the row is gone", meta.seq());
            return Optional.empty();
        }
        String text;
        try {
            text = gunzip(row.body());
        } catch (IOException | RuntimeException e) {
            LOG.warnf("engine snapshot %d ignored: the body cannot be decompressed (%s)", meta.seq(), e);
            return Optional.empty();
        }
        String sha = StateDigest.sha256(text);
        if (!sha.equals(meta.stateSha256())) {
            LOG.warnf("engine snapshot %d ignored: its state hashes to %s, the row says %s", meta.seq(), sha, meta.stateSha256());
            return Optional.empty();
        }
        return Optional.of(new Verified(meta.seq(), meta.takenAtMs(), sha, text));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // checking
    // ---------------------------------------------------------------------------------------------------------------

    /** The nightly check (and the operator's, and the tests'). See the class comment. */
    @Scheduled(cron = "{herd.snapshot.check-cron}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "herd-snapshot-check")
    void nightly() {
        if (!enabled) {
            return;
        }
        try {
            check(System.currentTimeMillis());
        } catch (RuntimeException e) {
            lastCheck = new Check(System.currentTimeMillis(), -1, null, null, null, "the check failed: " + e);
            LOG.error("the nightly check of the engine snapshots failed", e);
        }
    }

    /**
     * Replays the journal from the start up to the newest verified snapshot's {@code seq} into a state of its own (the live
     * one is not touched, no lock is taken) and compares its digest with the snapshot's. A journal of schema version 1 does not
     * reproduce the roster and the bet feed's names, so for it the two are left out of both digests
     * ({@link StateDigest#ofJournalDetermined}).
     */
    public Check check(long nowMs) {
        Verified snapshot = null;
        for (EngineSnapshotRepository.Meta meta : repository.list()) {
            snapshot = verified(meta).orElse(null);
            if (snapshot != null) {
                break;
            }
        }
        if (snapshot == null) {
            Check none = new Check(nowMs, -1, null, null, null, "no snapshot to check");
            lastCheck = none;
            return none;
        }
        String replayed = replayText(snapshot.seq(), snapshot.text());
        boolean legacy = eventStore.hasLegacyRows(snapshot.seq());
        String expected = legacy ? StateDigest.ofJournalDetermined(snapshot.text()) : snapshot.stateSha256();
        String actual = legacy ? StateDigest.ofJournalDetermined(replayed) : StateDigest.sha256(replayed);
        boolean ok = expected.equals(actual);
        Check result = new Check(nowMs, snapshot.seq(), ok, expected, actual,
                ok ? "the journal replayed from zero gives the snapshot's state" + (legacy ? " (journal of schema version 1: roster and feed names not compared)" : "")
                        : "the journal replayed from zero gives another state than the snapshot");
        lastCheck = result;
        if (ok) {
            LOG.infof("engine snapshot %d verified against a replay of the journal from zero (%s)", snapshot.seq(), actual);
        } else {
            LOG.errorf("ENGINE SNAPSHOT MISMATCH at journal row %d: the snapshot says %s, a replay from zero gives %s - "
                    + "the snapshot is not to be trusted and one of the two paths is wrong", snapshot.seq(), expected, actual);
        }
        return result;
    }

    /** The canonical text of the state the journal up to {@code seq} builds, in a hub of its own. The markets are those the
     *  snapshot text names. */
    String replayText(long seq, String snapshotText) {
        EngineHub aside = new EngineHub();
        BetFeed feed = new BetFeed();
        Set<String> wanted = new HashSet<>();
        for (String line : snapshotText.split("\n")) {
            if (line.startsWith("market\t")) {
                wanted.add(line.substring("market\t".length()));
            }
        }
        List<Market> markets = new ArrayList<>(marketRegistry.all());
        for (Market market : markets) {
            if (wanted.contains(market.id())) {
                aside.install(market);
            }
        }
        aside.ledgerOutbox().skipStored(sequence -> true); // the movements are not kept anywhere: only the state is compared
        EventApplier applier = EventApplier.over(aside, feed);
        eventStore.replayAfter(0, seq, (id, event) -> applier.apply(event));
        return StateCodec.encode(aside, feed);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // status
    // ---------------------------------------------------------------------------------------------------------------

    public Status status() {
        List<EngineSnapshotRepository.Meta> stored = repository.list();
        return new Status(enabled, stored.size(), stored.isEmpty() ? null : stored.get(0).seq(),
                stored.isEmpty() ? null : stored.get(0).takenAtMs(), eventStore.lastId(), lastError, lastCheck);
    }

    // ---------------------------------------------------------------------------------------------------------------

    static byte[] gzip(String text) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(1024, text.length() / 8));
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    static String gunzip(byte[] body) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
