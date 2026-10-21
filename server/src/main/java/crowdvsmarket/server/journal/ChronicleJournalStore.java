package crowdvsmarket.server.journal;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.CRC32C;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.SyncMode;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.queue.RollCycles;
import net.openhft.chronicle.queue.TailerDirection;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;

/**
 * The event journal on disk (Р-107): an append-only Chronicle Queue (Apache-2.0, https://github.com/OpenHFT/Chronicle-Queue) that holds,
 * for every event the game has accepted, the columns of the {@code game_events} row. The queue is the source of truth; the table is a
 * copy filled after it ({@code JournalProjector}).
 *
 * <p><b>One excerpt, one batch.</b> The events of a batch of the core writer go into ONE excerpt (magic, format, events, CRC32C of all
 * that precedes it). A reader sees the excerpt whole or not at all, so a batch is all-or-nothing, as the database transaction it
 * replaces was. Each event carries its own {@code seq}: strictly increasing, gaps allowed (a journal imported from the table keeps the ids
 * of the table).
 *
 * <p><b>Durability point.</b> {@link #append} returns when the excerpt is written AND {@code msync(MS_SYNC)} has returned for the pages it
 * lies in: the queue is built with {@link SyncMode#SYNC} and {@link ExcerptAppender#sync()} is called after the write (both in the
 * open-source edition; sources of chronicle-queue 2026.7 {@code StoreAppender.sync} and chronicle-bytes 2026.4
 * {@code MappedBytesStore.syncUpTo}). A reader ({@link Cursor}) is given an event only after the sync that covers it
 * ({@link #durableSeq}), so whatever is projected or replayed is on disk.
 *
 * <p><b>A write that fails.</b> A queue cannot be shortened, so an excerpt whose write or sync failed (its callers were told so) is
 * <i>voided</i>: the next append first writes a VOID excerpt naming its seq range, and refuses to go on until that has been written and
 * synced. A reader that meets an excerpt followed by a VOID excerpt for it drops it. The VOID excerpt always follows the excerpt it voids
 * immediately (the append lock is held from the failure to the next append). A process that dies before the VOID excerpt is written leaves
 * an excerpt that the next start reads as accepted: its callers saw a dropped connection, not an answer, which is the unknown outcome of
 * any commit in flight.
 *
 * <p><b>Recovery.</b> The queue finishes or discards a write that a killed process left half done (a header that is never completed is
 * invisible to readers; the next appender takes the write lock after the timeout). On open the last excerpt is read back and its CRC
 * checked; a journal whose last excerpt fails the check is refused (never silently shortened).
 */
public final class ChronicleJournalStore implements AutoCloseable {

    static {
        // Chronicle reports usage statistics to a third party by default (chronicle-analytics, Google Analytics, and writes a client id into
        // the home directory); a game server has no business doing that. Documented: "chronicle.analytics.disable=true" before the first queue.
        if (System.getProperty("chronicle.analytics.disable") == null) {
            System.setProperty("chronicle.analytics.disable", "true");
        }
    }

    private static final int MAGIC = 0x4A524E31; // "JRN1": events
    private static final int MAGIC_VOID = 0x4A524E56; // "JRNV": the excerpt before this one is void
    private static final byte FORMAT = 1;
    /** Every N-th excerpt the position in the queue is remembered, so that a reader can start near the event it wants. */
    private static final int MARK_EVERY = 64;

    /** Where a test can make the store fail, see {@link #hook}. */
    public enum Point {
        /** Inside {@code append}, before the excerpt is written. */
        BEFORE_WRITE,
        /** The excerpt is written, not yet synced. */
        AFTER_WRITE,
        /** The excerpt is synced, the durable position not yet advanced. */
        AFTER_SYNC
    }

    /** What one excerpt holds: events, or the range of seqs of the excerpt before it that is void. */
    private record Excerpt(List<JournalRecord> records, long voidFrom, long voidTo) {
        boolean isVoid() {
            return records == null;
        }

        long last() {
            return records.get(records.size() - 1).seq();
        }
    }

    private final Path dir;
    private final ChronicleQueue queue;
    private final ReentrantLock appendLock = new ReentrantLock();
    private final ConcurrentSkipListMap<Long, Long> marks = new ConcurrentSkipListMap<>();
    private long writtenSeq; // guarded by appendLock: the seq of the last event written, synced or not
    private long voidFrom; // guarded by appendLock: the range of an excerpt that failed and is not marked void yet (0: none)
    private long voidTo;
    private int sinceMark;
    private volatile long durableSeq;
    private volatile Consumer<Point> hook = p -> {
    };
    private volatile boolean closed;
    /** An appender belongs to the thread that made it: the core writer, the market registry, the deposit credit each have theirs. */
    private final ThreadLocal<ExcerptAppender> appenders;

    private ChronicleJournalStore(Path dir, ChronicleQueue queue) {
        this.dir = dir;
        this.queue = queue;
        this.appenders = ThreadLocal.withInitial(queue::createAppender);
    }

    /** Opens (creating it when it is not there) the journal in {@code dir}. */
    public static ChronicleJournalStore open(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the journal directory " + dir, e);
        }
        ChronicleQueue queue = SingleChronicleQueueBuilder.single(dir.toFile())
                .rollCycle(RollCycles.FAST_DAILY)
                .syncMode(SyncMode.SYNC)
                .timeoutMS(5_000L)
                .build();
        ChronicleJournalStore store = new ChronicleJournalStore(dir, queue);
        try {
            store.recoverPosition();
        } catch (RuntimeException | Error e) {
            queue.close();
            throw e;
        }
        return store;
    }

    private void recoverPosition() {
        long last = 0;
        try (ExcerptTailer tailer = queue.createTailer()) {
            tailer.toEnd();
            tailer.direction(TailerDirection.BACKWARD);
            Excerpt[] box = new Excerpt[1];
            if (tailer.readBytes(b -> box[0] = decode(readAll(b)))) {
                // a VOID excerpt spent the numbers of the excerpt before it; a restart goes on above them
                last = box[0].isVoid() ? box[0].voidTo() : box[0].last();
            }
        }
        writtenSeq = last;
        durableSeq = last;
    }

    public Path dir() {
        return dir;
    }

    /** The seq of the last event on disk (0 for an empty journal). */
    public long durableSeq() {
        return durableSeq;
    }

    /** The seq the next event gets. */
    public long nextSeq() {
        appendLock.lock();
        try {
            return writtenSeq + 1;
        } finally {
            appendLock.unlock();
        }
    }

    /** For tests: runs {@code hook} at the points of {@link Point}; whatever it throws comes out of {@link #append}. */
    public void hook(Consumer<Point> hook) {
        this.hook = hook == null ? p -> {
        } : hook;
    }

    /**
     * Writes {@code events} (their {@code seq} is ignored: the store gives the next numbers) as one excerpt and syncs it to disk.
     *
     * @return the seq of the last event, {@link #durableSeq()} when {@code events} is empty
     */
    public long append(List<JournalRecord> events) {
        if (events.isEmpty()) {
            return durableSeq;
        }
        appendLock.lock();
        try {
            long seq = writtenSeq;
            List<JournalRecord> numbered = new ArrayList<>(events.size());
            for (JournalRecord e : events) {
                numbered.add(new JournalRecord(++seq, e.type(), e.schemaVersion(), e.atMs(), e.market(), e.playerId(), e.payload()));
            }
            return write(numbered);
        } finally {
            appendLock.unlock();
        }
    }

    /** Writes events that carry their seq (the import of the table, {@link JournalCutover}): strictly increasing and above the last one. */
    public long appendNumbered(List<JournalRecord> events) {
        if (events.isEmpty()) {
            return durableSeq;
        }
        appendLock.lock();
        try {
            long previous = writtenSeq;
            for (JournalRecord e : events) {
                if (e.seq() <= previous) {
                    throw new IllegalArgumentException("journal seq " + e.seq() + " does not follow " + previous);
                }
                previous = e.seq();
            }
            return write(events);
        } finally {
            appendLock.unlock();
        }
    }

    /** Called holding {@link #appendLock}. */
    private long write(List<JournalRecord> numbered) {
        if (closed) {
            throw new IllegalStateException("the journal is closed");
        }
        byte[] body = encode(numbered);
        hook.accept(Point.BEFORE_WRITE);
        markVoid();
        ExcerptAppender appender = appenders.get();
        long first = numbered.get(0).seq();
        long last = numbered.get(numbered.size() - 1).seq();
        try {
            appender.writeBytes(b -> b.write(body));
            long index = appender.lastIndexAppended();
            writtenSeq = last; // written: the numbers are spent whatever happens to the sync (a failed sync never reuses them)
            if (sinceMark++ % MARK_EVERY == 0) {
                marks.put(first, index);
            }
            hook.accept(Point.AFTER_WRITE);
            appender.sync();
            hook.accept(Point.AFTER_SYNC);
        } catch (Throwable t) {
            // the excerpt may be in the file, even on disk, and its callers will be told it failed: it is voided before anything else is written
            writtenSeq = Math.max(writtenSeq, last);
            voidFrom = first;
            voidTo = last;
            throw t;
        }
        durableSeq = last;
        return last;
    }

    /** Writes and syncs the VOID excerpt for an excerpt that failed, before anything else is appended; throws while that cannot be done. */
    private void markVoid() {
        if (voidFrom == 0) {
            return;
        }
        ExcerptAppender appender = appenders.get();
        byte[] body = encodeVoid(voidFrom, voidTo);
        appender.writeBytes(b -> b.write(body));
        appender.sync();
        voidFrom = 0;
        voidTo = 0;
    }

    /** A reader that hands over the events after {@code afterSeq}, in order, as far as they are durable. Not thread-safe; one per thread. */
    public Cursor cursor(long afterSeq) {
        ExcerptTailer tailer = queue.createTailer();
        Map.Entry<Long, Long> mark = marks.floorEntry(afterSeq);
        if (mark != null && !tailer.moveToIndex(mark.getValue())) {
            tailer.toStart();
        }
        return new Cursor(tailer, afterSeq);
    }

    /** Hands every durable event with {@code afterSeq < seq <= upToSeq} to {@code sink}, in order; returns the seq of the last one handed over. */
    public long replay(long afterSeq, long upToSeq, Consumer<JournalRecord> sink) {
        long last = afterSeq;
        try (Cursor cursor = cursor(afterSeq)) {
            while (true) {
                List<JournalRecord> page = cursor.next(5_000);
                if (page.isEmpty()) {
                    return last;
                }
                for (JournalRecord r : page) {
                    if (r.seq() > upToSeq) {
                        return last;
                    }
                    sink.accept(r);
                    last = r.seq();
                }
            }
        }
    }

    @Override
    public void close() {
        appendLock.lock();
        try {
            if (!closed) {
                closed = true;
                queue.close();
            }
        } finally {
            appendLock.unlock();
        }
    }

    /** See {@link #cursor}. */
    public final class Cursor implements AutoCloseable {
        private final ExcerptTailer tailer;
        private final long after;
        private long lastRead;
        private final ArrayDeque<Excerpt> ahead = new ArrayDeque<>();
        private int read;

        private Cursor(ExcerptTailer tailer, long after) {
            this.tailer = tailer;
            this.after = after;
            this.lastRead = after;
        }

        /** The next events, whole excerpts, about {@code max} of them; empty when there is nothing durable beyond the last one handed over. */
        public List<JournalRecord> next(int max) {
            List<JournalRecord> out = new ArrayList<>();
            while (out.size() < max) {
                if (ahead.isEmpty()) {
                    if (!readExcerpt()) {
                        break;
                    }
                    if (ahead.isEmpty()) {
                        continue; // an excerpt entirely before the place this reader started at
                    }
                }
                Excerpt head = ahead.peekFirst();
                if (head.isVoid()) {
                    ahead.pollFirst(); // the excerpt it voids was dropped already, or was before the place this reader started at
                    continue;
                }
                if (ahead.size() < 2) {
                    readExcerpt(); // look at what follows: a VOID excerpt for this one, if its write failed
                }
                if (ahead.size() >= 2) {
                    Excerpt second = ahead.stream().skip(1).findFirst().orElseThrow();
                    if (second.isVoid() && second.voidFrom() <= head.records().get(0).seq() && second.voidTo() >= head.last()) {
                        ahead.pollFirst();
                        ahead.pollFirst();
                        continue;
                    }
                }
                if (head.last() > durableSeq) {
                    break; // written, not synced yet (or failed, and not voided yet): it waits
                }
                out.addAll(ahead.pollFirst().records());
            }
            return out;
        }

        private boolean readExcerpt() {
            Excerpt[] box = new Excerpt[1];
            long index = tailer.index();
            if (!tailer.readBytes(b -> box[0] = decode(readAll(b)))) {
                return false;
            }
            Excerpt excerpt = box[0];
            if (excerpt.isVoid()) {
                ahead.addLast(excerpt);
                return true;
            }
            List<JournalRecord> records = excerpt.records();
            if (read++ % MARK_EVERY == 0 && index > 0) {
                marks.putIfAbsent(records.get(0).seq(), index);
            }
            List<JournalRecord> wanted = new ArrayList<>(records.size());
            for (JournalRecord r : records) {
                if (r.seq() <= lastRead) {
                    if (r.seq() > after) {
                        throw new IllegalStateException("the journal is not in order: seq " + r.seq() + " after " + lastRead);
                    }
                    continue; // before the place the reader was asked to start at
                }
                wanted.add(r);
                lastRead = r.seq();
            }
            if (!wanted.isEmpty()) {
                ahead.addLast(new Excerpt(wanted, 0, 0));
            }
            return true;
        }

        @Override
        public void close() {
            tailer.close();
        }
    }

    // ---------------------------------------------------------------------------------------

    private static byte[] readAll(BytesIn<?> b) {
        byte[] bytes = new byte[(int) b.readRemaining()];
        b.read(bytes);
        return bytes;
    }

    static byte[] encode(List<JournalRecord> events) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + events.size() * 256);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeInt(events.size());
            for (JournalRecord e : events) {
                out.writeLong(e.seq());
                out.writeInt(e.schemaVersion());
                out.writeLong(e.atMs());
                string(out, e.type());
                string(out, e.market());
                string(out, e.playerId());
                string(out, e.payload());
            }
            return withCrc(bos, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] encodeVoid(long from, long to) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(MAGIC_VOID);
            out.writeByte(FORMAT);
            out.writeLong(from);
            out.writeLong(to);
            return withCrc(bos, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] withCrc(ByteArrayOutputStream bos, DataOutputStream out) throws IOException {
        CRC32C crc = new CRC32C();
        byte[] soFar = bos.toByteArray();
        crc.update(soFar, 0, soFar.length);
        out.writeInt((int) crc.getValue());
        return bos.toByteArray();
    }

    private static Excerpt decode(byte[] body) {
        if (body.length < 13) {
            throw new IllegalStateException("a journal excerpt of " + body.length + " bytes is too short");
        }
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length - 4);
        int stored = ByteBuffer.wrap(body, body.length - 4, 4).getInt();
        if (stored != (int) crc.getValue()) {
            throw new IllegalStateException("a journal excerpt fails its checksum - the journal is damaged");
        }
        try {
            DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(body, 0, body.length - 4));
            int magic = in.readInt();
            if ((magic != MAGIC && magic != MAGIC_VOID) || in.readByte() != FORMAT) {
                throw new IllegalStateException("a journal excerpt of an unknown format");
            }
            if (magic == MAGIC_VOID) {
                return new Excerpt(null, in.readLong(), in.readLong());
            }
            int n = in.readInt();
            List<JournalRecord> records = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                long seq = in.readLong();
                int version = in.readInt();
                long atMs = in.readLong();
                String type = string(in);
                String market = string(in);
                String player = string(in);
                String payload = string(in);
                records.add(new JournalRecord(seq, type, version, atMs, market, player, payload));
            }
            if (records.isEmpty()) {
                throw new IllegalStateException("a journal excerpt without events");
            }
            return new Excerpt(records, 0, 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String string(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
