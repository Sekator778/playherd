package crowdvsmarket.server.prices;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A scripted {@link WebSocketTransport}: every {@link #connect} is recorded and answered with a {@link Link} the test
 * drives — {@link Link#deliver} is the server sending a message, {@link Link#serverCloses} and {@link Link#serverFails}
 * are the connection ending — and {@link #connectFailure} makes the next connects fail. The tests of the streamed
 * sources never open a socket.
 */
public class FakeWebSocketTransport implements WebSocketTransport {

    /** One open (or dropped) connection. */
    public static final class Link implements Connection {

        public final URI uri;
        public final Handler handler;
        /** What the client sent, in order. */
        public final List<String> sent = new CopyOnWriteArrayList<>();
        public volatile boolean aborted;
        /** When set, {@link #sendText} throws it. */
        public volatile IOException sendFailure;

        Link(URI uri, Handler handler) {
            this.uri = uri;
            this.handler = handler;
        }

        @Override
        public void sendText(String message) throws IOException {
            IOException failure = sendFailure;
            if (failure != null) {
                throw failure;
            }
            sent.add(message);
        }

        @Override
        public void abort() {
            aborted = true;
        }

        /** The server sends a message. */
        public void deliver(String message) {
            handler.onText(message);
        }

        public void serverCloses(int code, String reason) {
            handler.onClosed(code, reason);
        }

        public void serverFails(Throwable error) {
            handler.onError(error);
        }
    }

    public final List<Link> links = new CopyOnWriteArrayList<>();
    /** How long each connect was allowed, in order. */
    public final List<Duration> timeouts = new CopyOnWriteArrayList<>();
    /** When set, {@link #connect} throws it instead of opening anything. */
    public volatile IOException connectFailure;
    /** Counts the connect calls, the failed ones too. */
    public volatile int attempts;

    @Override
    public Connection connect(URI uri, Duration timeout, Handler handler) throws IOException {
        attempts++;
        timeouts.add(timeout);
        IOException failure = connectFailure;
        if (failure != null) {
            throw failure;
        }
        Link link = new Link(uri, handler);
        links.add(link);
        return link;
    }

    /** The connection opened last. */
    public Link last() {
        if (links.isEmpty()) {
            throw new AssertionError("nothing connected");
        }
        return links.get(links.size() - 1);
    }

    /** The messages sent on every connection, in the order of the connections. */
    public List<String> allSent() {
        List<String> out = new ArrayList<>();
        for (Link link : links) {
            out.addAll(link.sent);
        }
        return out;
    }
}
