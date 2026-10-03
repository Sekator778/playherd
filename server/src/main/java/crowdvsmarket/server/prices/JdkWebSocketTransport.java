package crowdvsmarket.server.prices;

import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * {@link WebSocketTransport} over {@code java.net.http.WebSocket} — no dependency beyond the JDK — with an
 * {@link HttpClient} whose executor is {@link Executors#newVirtualThreadPerTaskExecutor}: every connection's
 * callbacks run on virtual threads, so a stream costs no platform thread, nothing here ever runs on (or blocks) a
 * Vert.x event-loop thread, and a slow handler stalls only its own connection.
 *
 * <p>The client is built on the first connection, not at start-up (a deployment with the synthetic source never opens
 * a socket), and shut down with the application. {@code request(1)} is asked after every message: the JDK client
 * delivers nothing more until it is, so a handler that is slow is back-pressure, not a growing queue. A message sent
 * by the peer in several frames is handed on once, whole. Writes are serialised — the JDK client allows one send at a
 * time — with a {@link ReentrantLock} rather than a monitor, so that a virtual thread waiting for a send does not pin
 * its carrier.
 */
@ApplicationScoped
@Unremovable
public class JdkWebSocketTransport implements WebSocketTransport {

    private static final Logger LOG = Logger.getLogger(JdkWebSocketTransport.class);

    /** A send that has not finished after this long means the connection is not going anywhere. */
    private static final long SEND_TIMEOUT_MS = 10_000L;

    private final Object lock = new Object();
    private ExecutorService executor; // with client, created by the first connection; guarded by lock
    private HttpClient client;

    @Override
    public Connection connect(URI uri, Duration timeout, Handler handler) throws IOException, InterruptedException {
        HttpClient http = client();
        try {
            WebSocket socket = http.newWebSocketBuilder().connectTimeout(timeout)
                    .buildAsync(uri, new Listener(handler)).get(timeout.toMillis() + 2_000L, TimeUnit.MILLISECONDS);
            return new Link(socket);
        } catch (ExecutionException e) {
            throw new IOException("could not open " + uri + ": " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("opening " + uri + " took more than " + timeout.toMillis() + " ms", e);
        }
    }

    private HttpClient client() {
        synchronized (lock) {
            if (client == null) {
                executor = Executors.newVirtualThreadPerTaskExecutor();
                client = HttpClient.newBuilder().executor(executor).build();
            }
            return client;
        }
    }

    /** Whether the client has been built (by the first connection) and not been shut down since. */
    boolean started() {
        synchronized (lock) {
            return client != null;
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (lock) {
            if (client != null) {
                client.shutdownNow(); // drops the sockets that are still open
                executor.shutdownNow();
                client = null;
            }
        }
    }

    private static final class Link implements Connection {

        private final WebSocket socket;
        private final ReentrantLock sending = new ReentrantLock();

        Link(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void sendText(String message) throws IOException {
            sending.lock();
            try {
                socket.sendText(message, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("could not send on the WebSocket: " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while sending on the WebSocket", e);
            } finally {
                sending.unlock();
            }
        }

        @Override
        public void abort() {
            socket.abort();
        }
    }

    /** The JDK's callbacks as a {@link Handler}'s: whole messages, one more requested after each. */
    private static final class Listener implements WebSocket.Listener {

        private final Handler handler;
        private final StringBuilder partial = new StringBuilder();

        Listener(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String message = partial.toString();
                partial.setLength(0);
                try {
                    handler.onText(message);
                } catch (RuntimeException e) { // one bad message must not end the connection
                    LOG.warnf("a WebSocket message could not be handled: %s", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handler.onClosed(statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            handler.onError(error);
        }
    }
}
