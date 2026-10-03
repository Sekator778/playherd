package crowdvsmarket.server.prices;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

/**
 * The WebSocket client the price streams talk through: {@link JdkWebSocketTransport} (the JDK's own client on virtual
 * threads) in production, a script in the tests — so that nothing in the pipeline's tests ever opens a socket.
 * Deliberately small: one text message in, one text message out, and an honest report of how the connection ended.
 */
public interface WebSocketTransport {

    /** What an open connection reports. Every callback comes from a thread of the transport, never from the caller's. */
    interface Handler {

        /** One whole text message (a message the peer split into frames arrives once). */
        void onText(String message);

        /** The peer closed the connection. */
        void onClosed(int code, String reason);

        /** The connection failed. */
        void onError(Throwable error);
    }

    /** An open connection. */
    interface Connection {

        /** Sends one text message; returns when it is on its way, throws when it cannot be sent. Thread-safe. */
        void sendText(String message) throws IOException;

        /** Drops the connection at once, without the closing handshake. */
        void abort();
    }

    /**
     * Opens a connection to {@code uri} and returns when it is open. Blocks the calling thread (a virtual one, in
     * production) for at most about {@code timeout}.
     *
     * @throws IOException when the connection cannot be had
     */
    Connection connect(URI uri, Duration timeout, Handler handler) throws IOException, InterruptedException;
}
