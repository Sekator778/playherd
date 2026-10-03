package crowdvsmarket.server.prices;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Keeps one WebSocket stream up: connects, sends the subscription, notices a connection that has gone silent or has
 * been closed, and connects again — after 1, 2, 4 … seconds, up to {@code herd.prices.stream.reconnect-max-ms} (60 s),
 * with the subscription sent anew each time. One small supervisor per stream, each on a <b>virtual thread</b> of its
 * own; it never touches a Vert.x event-loop thread, and what it waits for (a connection opening, a send, the next
 * look) is waited for on its own thread, so nothing a caller does ever blocks on the network but the REST fallbacks of
 * the sources.
 *
 * <p><b>What it watches.</b> A connection is dead when the peer closed it or it failed, and silent when nothing at all
 * — a quote, a heartbeat, an acknowledgement — has arrived for {@code silenceMs} (a connection that opens and never
 * says a word is silent too). Either drops it and schedules the next attempt.
 *
 * <p><b>When the backoff starts over.</b> A connection has <i>worked</i> when it has delivered a data message of a
 * subscribed channel — what the stream says is one ({@link Stream#isData}: a price; not a greeting, an acknowledgement, a
 * heartbeat or an error) — or has stayed up for {@value #STABLE_AFTER_MS} ms. Only then does the backoff start again at
 * one second, so a stream that was healthy comes back within a second. The first message of a connection, whatever it is,
 * proves nothing: a server that accepts, says one thing and closes, again and again, would be dialled every second
 * for ever; it is tried less and less often like a server that is down. And a session that was lost before
 * {@value #STABLE_AFTER_MS} ms — data or not — is followed by at least {@value #SHORT_SESSION_FLOOR_MS} ms of silence: a
 * server that flaps is not hammered. The supervisor does not judge what a message says beyond that: {@link Stream#onMessage}
 * does.
 *
 * <p><b>Nobody asking, no connection.</b> The stream runs while somebody wants it ({@link #want}, which a source calls
 * from every poll): after {@code idleMs} without a call it is closed, and the next call connects again at once. With no
 * active market of a feed nothing is polled, so nothing is connected.
 *
 * <p>The decisions are made by {@link #step}, a function of the clock; the virtual thread only calls it every
 * {@link #TICK_MS} — which is also how the tests drive it, with a clock of their own and a scripted
 * {@link WebSocketTransport}. {@link #close} (the sources call it when the application stops) ends the thread and
 * drops the connection.
 */
final class StreamSupervisor implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(StreamSupervisor.class);

    /** What a venue tells the supervisor about its stream, and what it does with what comes. */
    interface Stream {

        /** For logs and the thread's name. */
        String name();

        URI uri();

        /** The messages that subscribe to what is wanted now: sent after every connect, the first and each again. */
        List<String> subscribeMessages();

        /** A message the server wants now and then to keep the connection open ({@code null}: none). */
        default String keepAliveMessage() {
            return null;
        }

        /**
         * Whether the message is <i>data</i> of a channel this stream subscribed to — a price — and not a greeting, the
         * acknowledgement of a subscription, a heartbeat or an error. A connection that has delivered one has proved that the
         * subscription works, and the backoff of the next reconnect starts over. The default is a stream that cannot tell:
         * every message is data. Called on the transport's thread for each message until the first data one.
         */
        default boolean isData(String message) {
            return true;
        }

        /** One message of the stream, received at {@code rxMs} by the supervisor's clock. Runs on the transport's thread. */
        void onMessage(String text, long rxMs);
    }

    /** How often the thread looks at the stream: the longest it is unattended, and the resolution of every timeout. */
    static final long TICK_MS = 250L;
    static final long INITIAL_BACKOFF_MS = 1_000L;
    /** A connection that stays up this long has worked, whatever it said: its loss is retried from the shortest backoff. */
    static final long STABLE_AFTER_MS = 30_000L;
    /** The least wait before the next attempt after a session that did not last {@link #STABLE_AFTER_MS}: the floor under the backoff. */
    static final long SHORT_SESSION_FLOOR_MS = 5_000L;
    static final long CONNECT_TIMEOUT_MS = 5_000L;
    /** Hyperliquid closes a connection nothing is sent on for a minute (hl_multi_asset_live.md §6): half of that. */
    static final long KEEP_ALIVE_EVERY_MS = 30_000L;

    private static final long NEVER = Long.MIN_VALUE;

    /** One connection and what is known about it. A late callback of a dropped one finds {@code current} elsewhere and is ignored. */
    private final class Session implements WebSocketTransport.Handler {

        volatile WebSocketTransport.Connection connection;
        volatile long lastRxMs;
        /** When the connection opened (the first look at it before then: the attempt's own time). */
        volatile long openedAtMs;
        /** A data message of a subscribed channel arrived ({@link Stream#isData}): the subscription works. */
        volatile boolean dataSeen;
        /** Why the peer or the network ended it, {@code null} while it lives. */
        volatile String ended;

        Session(long nowMs) {
            this.lastRxMs = nowMs;
            this.openedAtMs = nowMs;
        }

        @Override
        public void onText(String message) {
            if (current != this) {
                return;
            }
            long now = clock.getAsLong();
            lastRxMs = now;
            lastMessageMs = now;
            if (!dataSeen && isDataQuietly(message)) {
                dataSeen = true;
            }
            stream.onMessage(message, now);
        }

        private boolean isDataQuietly(String message) {
            try {
                return stream.isData(message);
            } catch (RuntimeException e) { // a message the stream cannot even look at is no data
                return false;
            }
        }

        /** The connection has worked: it delivered data, or stayed up for {@link #STABLE_AFTER_MS}. */
        boolean worked(long nowMs) {
            return dataSeen || nowMs - openedAtMs >= STABLE_AFTER_MS;
        }

        @Override
        public void onClosed(int code, String reason) {
            ended = "closed by the server (" + code + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")";
        }

        @Override
        public void onError(Throwable error) {
            ended = "failed: " + error;
        }

        void abortQuietly() {
            WebSocketTransport.Connection c = connection;
            if (c != null) {
                try {
                    c.abort();
                } catch (RuntimeException e) {
                    LOG.debugf("aborting the %s stream failed: %s", stream.name(), e.toString());
                }
            }
        }
    }

    private final Stream stream;
    private final WebSocketTransport transport;
    private final LongSupplier clock;
    private final long silenceMs;
    private final long reconnectMaxMs;
    private final long idleMs;
    private final boolean threaded;
    private final ThrottledLog failures;

    private volatile Session current;
    private volatile long lastWantedMs = NEVER;
    private volatile long lastMessageMs = NEVER;
    private volatile boolean closed;
    private Thread thread; // started by the first want(), guarded by this
    // only step() touches these
    private long nextAttemptAtMs;
    private long backoffMs = INITIAL_BACKOFF_MS;
    private long lastKeepAliveAtMs;
    private volatile int connects;
    private volatile int drops;

    /** The production supervisor: its thread starts with the first {@link #want}. */
    StreamSupervisor(Stream stream, WebSocketTransport transport, LongSupplier clock, long silenceMs,
                     long reconnectMaxMs, long idleMs) {
        this(stream, transport, clock, silenceMs, reconnectMaxMs, idleMs, true);
    }

    /** {@code threaded = false}: no thread, the caller calls {@link #step} — for tests. */
    StreamSupervisor(Stream stream, WebSocketTransport transport, LongSupplier clock, long silenceMs,
                     long reconnectMaxMs, long idleMs, boolean threaded) {
        this.stream = stream;
        this.transport = transport;
        this.clock = clock;
        this.silenceMs = silenceMs;
        this.reconnectMaxMs = reconnectMaxMs;
        this.idleMs = idleMs;
        this.threaded = threaded;
        this.failures = new ThrottledLog(LOG, clock, 60_000L);
    }

    /** Somebody needs the stream now: keeps it (or starts it). Cheap, and safe to call from any thread, every poll. */
    void want(long nowMs) {
        lastWantedMs = nowMs;
        if (threaded && !closed) {
            ensureThread();
        }
    }

    private synchronized void ensureThread() {
        if (thread == null && !closed) {
            thread = Thread.ofVirtual().name(stream.name() + "-stream").start(this::run);
        }
    }

    /** Sends a message on the connection that is open now; {@code false} when there is none or it could not be sent. */
    boolean send(String message) {
        Session session = current;
        WebSocketTransport.Connection connection = session == null ? null : session.connection;
        if (connection == null) {
            return false;
        }
        try {
            connection.sendText(message);
            return true;
        } catch (IOException e) {
            LOG.debugf("could not send on the %s stream: %s", stream.name(), e.toString());
            return false;
        }
    }

    /** A connection is open and has had no reason to be dropped yet. */
    boolean connected() {
        Session session = current;
        return session != null && session.connection != null && session.ended == null;
    }

    /**
     * What a stream tells the operator: whether somebody wants it ({@link #want} within {@code idleMs}), whether a
     * connection is open, when the last message of any connection came, how many connections were opened and dropped.
     */
    record Health(boolean wanted, boolean open, long lastMessageAtMs, int connects, int drops) {
    }

    Health health(long nowMs) {
        long wantedAt = lastWantedMs;
        return new Health(wantedAt != NEVER && nowMs - wantedAt <= idleMs, connected(), lastMessageMs, connects, drops);
    }

    /**
     * The {@link PriceSource.FeedStatus} of a streamed feed (its fields explain the states): {@code idle} when nobody asks
     * for the stream, {@code rest-fallback} when asked and no connection is usable, {@code silent} when open but not heard
     * from within {@code freshMs}, else {@code connected}.
     */
    static PriceSource.FeedStatus status(PriceSource.Feed feed, String venue, Health health, long nowMs, long freshMs,
                                         int restReads) {
        String state;
        if (!health.wanted()) {
            state = "idle";
        } else if (!health.open()) {
            state = "rest-fallback";
        } else if (health.lastMessageAtMs() == NEVER || nowMs - health.lastMessageAtMs() > freshMs) {
            state = "silent";
        } else {
            state = "connected";
        }
        return new PriceSource.FeedStatus(feed.id(), venue, "stream", state,
                health.lastMessageAtMs() == NEVER ? null : health.lastMessageAtMs(), restReads);
    }

    /** How many times a connection was opened (the first included) and how many were dropped — for tests and logs. */
    int connects() {
        return connects;
    }

    int drops() {
        return drops;
    }

    /** The supervisor's thread exists and has not ended (a virtual thread, started by the first {@link #want}). */
    synchronized boolean running() {
        return thread != null && thread.isAlive();
    }

    private void run() {
        while (!closed) {
            long wait = TICK_MS;
            try {
                wait = step(clock.getAsLong());
            } catch (RuntimeException e) {
                failures.failure("step", "the " + stream.name() + " stream supervisor failed", e);
            }
            try {
                Thread.sleep(Math.max(1L, wait));
            } catch (InterruptedException e) {
                break;
            }
        }
        dropCurrent("the application is stopping");
    }

    /**
     * One look at the stream at {@code nowMs}: connects when it is wanted and due, drops a connection that was closed,
     * failed or went silent, closes one nobody wants, sends the keep-alive. Returns how long to leave the stream alone.
     */
    long step(long nowMs) {
        if (closed) {
            return TICK_MS;
        }
        long wanted = lastWantedMs;
        Session session = current;
        if (wanted == NEVER || nowMs - wanted > idleMs) {
            if (session != null) {
                drop(session, "nobody has polled for " + (nowMs - wanted) / 1000 + " s", false, nowMs);
            }
            return TICK_MS;
        }
        if (session == null) {
            if (nowMs < nextAttemptAtMs) {
                return Math.min(TICK_MS, nextAttemptAtMs - nowMs);
            }
            connect(nowMs);
            return TICK_MS;
        }
        if (session.worked(nowMs)) {
            // data of a subscribed channel, or half a minute up: the next loss is retried from the shortest backoff
            // (a hello, an acknowledgement or an error does not count, however promptly it came)
            backoffMs = INITIAL_BACKOFF_MS;
        }
        String ended = session.ended;
        if (ended != null) {
            drop(session, ended, true, nowMs);
        } else if (nowMs - session.lastRxMs > silenceMs) {
            drop(session, "silent for " + (nowMs - session.lastRxMs) / 1000 + " s", true, nowMs);
        } else {
            keepAlive(nowMs);
        }
        return TICK_MS;
    }

    private void connect(long nowMs) {
        Session session = new Session(nowMs);
        current = session; // before the connection exists: its first message may arrive before connect() returns
        try {
            WebSocketTransport.Connection connection = transport.connect(stream.uri(),
                    Duration.ofMillis(CONNECT_TIMEOUT_MS), session);
            session.connection = connection;
            for (String message : stream.subscribeMessages()) {
                connection.sendText(message);
            }
            long opened = clock.getAsLong();
            session.lastRxMs = opened; // the silence is counted from here, not from the start of a slow connect
            session.openedAtMs = opened; // and so is the time it has been up
            lastKeepAliveAtMs = opened;
            connects++;
            LOG.infof("the %s stream is connected (%s)", stream.name(), stream.uri());
            failures.success("connect", "connecting the " + stream.name() + " stream");
        } catch (IOException | RuntimeException e) {
            current = null;
            session.abortQuietly();
            failures.failure("connect", "could not connect the " + stream.name() + " stream, trying again in "
                    + backoffMs / 1000 + " s", e);
            scheduleReconnect(nowMs, 0L);
        } catch (InterruptedException e) {
            current = null;
            session.abortQuietly();
            Thread.currentThread().interrupt();
        }
    }

    private void keepAlive(long nowMs) {
        String message = stream.keepAliveMessage();
        if (message != null && nowMs - lastKeepAliveAtMs >= KEEP_ALIVE_EVERY_MS) {
            lastKeepAliveAtMs = nowMs;
            send(message);
        }
    }

    private void drop(Session session, String why, boolean reconnect, long nowMs) {
        if (current != session) {
            return;
        }
        current = null;
        session.abortQuietly();
        drops++;
        if (reconnect) {
            // a session that was lost before it could prove itself is not followed by an attempt sooner than the floor
            long floorMs = nowMs - session.openedAtMs < STABLE_AFTER_MS ? SHORT_SESSION_FLOOR_MS : 0L;
            LOG.warnf("the %s stream was lost (%s) after %d s, connecting again in %d s", stream.name(), why,
                    (nowMs - session.openedAtMs) / 1000, Math.max(backoffMs, floorMs) / 1000);
            scheduleReconnect(nowMs, floorMs);
        } else {
            LOG.infof("the %s stream is closed (%s)", stream.name(), why);
            nextAttemptAtMs = 0L; // wanted again: connected at once
            backoffMs = INITIAL_BACKOFF_MS;
        }
    }

    /** The next attempt after the backoff or the floor, whichever is longer; the backoff doubles for the one after. */
    private void scheduleReconnect(long nowMs, long floorMs) {
        nextAttemptAtMs = nowMs + Math.max(backoffMs, floorMs);
        backoffMs = Math.min(backoffMs * 2, reconnectMaxMs);
    }

    private void dropCurrent(String why) {
        Session session = current;
        if (session != null) {
            current = null;
            session.abortQuietly();
            LOG.infof("the %s stream is closed (%s)", stream.name(), why);
        }
    }

    @Override
    public void close() {
        closed = true;
        Thread t;
        synchronized (this) {
            t = thread;
        }
        if (t != null) {
            t.interrupt();
        } else {
            dropCurrent("the application is stopping");
        }
    }
}
