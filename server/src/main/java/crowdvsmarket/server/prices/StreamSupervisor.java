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
 * says a word is silent too). Either drops it and schedules the next attempt. The backoff starts again at one second
 * once a connection has delivered a message, so a stream that was healthy comes back within a second, while a server
 * that accepts connections and hangs up (or one that is down) is tried less and less often. The supervisor
 * does not judge what a message says: {@link Stream#onMessage} does.
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

        /** One message of the stream, received at {@code rxMs} by the supervisor's clock. Runs on the transport's thread. */
        void onMessage(String text, long rxMs);
    }

    /** How often the thread looks at the stream: the longest it is unattended, and the resolution of every timeout. */
    static final long TICK_MS = 250L;
    static final long INITIAL_BACKOFF_MS = 1_000L;
    static final long CONNECT_TIMEOUT_MS = 5_000L;
    /** Hyperliquid closes a connection nothing is sent on for a minute (hl_multi_asset_live.md §6): half of that. */
    static final long KEEP_ALIVE_EVERY_MS = 30_000L;

    private static final long NEVER = Long.MIN_VALUE;

    /** One connection and what is known about it. A late callback of a dropped one finds {@code current} elsewhere and is ignored. */
    private final class Session implements WebSocketTransport.Handler {

        volatile WebSocketTransport.Connection connection;
        volatile long lastRxMs;
        /** At least one message arrived: the connection works. */
        volatile boolean healthy;
        /** Why the peer or the network ended it, {@code null} while it lives. */
        volatile String ended;

        Session(long nowMs) {
            this.lastRxMs = nowMs;
        }

        @Override
        public void onText(String message) {
            if (current != this) {
                return;
            }
            long now = clock.getAsLong();
            lastRxMs = now;
            healthy = true;
            stream.onMessage(message, now);
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
        if (session.healthy) {
            backoffMs = INITIAL_BACKOFF_MS; // a connection that works: the next loss is retried within a second
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
            lastKeepAliveAtMs = opened;
            connects++;
            LOG.infof("the %s stream is connected (%s)", stream.name(), stream.uri());
            failures.success("connect", "connecting the " + stream.name() + " stream");
        } catch (IOException | RuntimeException e) {
            current = null;
            session.abortQuietly();
            failures.failure("connect", "could not connect the " + stream.name() + " stream, trying again in "
                    + backoffMs / 1000 + " s", e);
            scheduleReconnect(nowMs);
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
            LOG.warnf("the %s stream was lost (%s), connecting again in %d s", stream.name(), why, backoffMs / 1000);
            scheduleReconnect(nowMs);
        } else {
            LOG.infof("the %s stream is closed (%s)", stream.name(), why);
            nextAttemptAtMs = 0L; // wanted again: connected at once
            backoffMs = INITIAL_BACKOFF_MS;
        }
    }

    private void scheduleReconnect(long nowMs) {
        nextAttemptAtMs = nowMs + backoffMs;
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
