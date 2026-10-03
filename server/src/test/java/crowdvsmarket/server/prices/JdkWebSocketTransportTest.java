package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link JdkWebSocketTransport} against a real WebSocket on the loopback interface ({@link LocalWebSocketServer}) and the
 * JDK's own client: whole messages whatever the framing, the order of a burst, a 20 kB message (what Hyperliquid's
 * {@code allMids} is), the end of a connection reported as it was, a refused connection as an {@code IOException}, every
 * callback on a virtual thread, and a handshake the caller stopped waiting for (a server slower than the wait, an interrupt)
 * called off so that it leaves no orphan socket.
 */
class JdkWebSocketTransportTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final JdkWebSocketTransport transport = new JdkWebSocketTransport();
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<Boolean> onVirtualThreads = new CopyOnWriteArrayList<>();
    private LocalWebSocketServer server;

    private final WebSocketTransport.Handler handler = new WebSocketTransport.Handler() {
        @Override
        public void onText(String message) {
            onVirtualThreads.add(Thread.currentThread().isVirtual());
            messages.add(message);
        }

        @Override
        public void onClosed(int code, String reason) {
            onVirtualThreads.add(Thread.currentThread().isVirtual());
            events.add("closed " + code + " " + reason);
        }

        @Override
        public void onError(Throwable error) {
            onVirtualThreads.add(Thread.currentThread().isVirtual());
            events.add("error " + error.getClass().getSimpleName());
        }
    };

    @BeforeEach
    void startServer() throws IOException {
        server = new LocalWebSocketServer();
    }

    @AfterEach
    void stop() throws IOException {
        transport.shutdown();
        server.close();
    }

    private WebSocketTransport.Connection connect() throws Exception {
        WebSocketTransport.Connection connection = transport.connect(server.uri(), TIMEOUT, handler);
        assertThat(server.awaitClient(5_000)).isTrue();
        return connection;
    }

    @Test
    void textGoesBothWaysAndEveryCallbackRunsOnAVirtualThread() throws Exception {
        WebSocketTransport.Connection connection = connect();

        connection.sendText("{\"method\":\"subscribe\"}");
        server.send("{\"channel\":\"allMids\"}");

        await().atMost(TIMEOUT).until(() -> server.received.size() == 1 && messages.size() == 1);
        assertThat(server.received).containsExactly("{\"method\":\"subscribe\"}");
        assertThat(messages).containsExactly("{\"channel\":\"allMids\"}");
        assertThat(onVirtualThreads).isNotEmpty().containsOnly(true);
    }

    @Test
    void aMessageSplitIntoFramesArrivesOnceAndWhole() throws Exception {
        connect();

        server.sendFragmented("{\"channel\":", "\"allMids\",", "\"data\":{}}");
        server.send("{\"next\":1}");

        await().atMost(TIMEOUT).until(() -> messages.size() == 2);
        assertThat(messages).containsExactly("{\"channel\":\"allMids\",\"data\":{}}", "{\"next\":1}");
    }

    @Test
    void aTwentyKilobyteMessageIsOneMessage() throws Exception {
        connect();
        String big = "{\"channel\":\"allMids\",\"data\":{\"mids\":{" + "\"@1\":\"1.5\",".repeat(2_000) + "\"x\":\"1\"}}}";
        assertThat(big.length()).isGreaterThan(20_000);

        server.send(big);

        await().atMost(TIMEOUT).until(() -> messages.size() == 1);
        assertThat(messages.get(0)).isEqualTo(big);
    }

    @Test
    void aBurstArrivesInOrderWhateverTheHandlerDoes() throws Exception {
        connect();
        int n = 300;

        for (int i = 0; i < n; i++) {
            server.send("{\"n\":" + i + "}");
        }

        await().atMost(TIMEOUT).until(() -> messages.size() == n);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            expected.add("{\"n\":" + i + "}");
        }
        assertThat(messages).isEqualTo(expected);
    }

    @Test
    void theServerClosingIsReportedWithItsCodeAndReason() throws Exception {
        connect();

        server.closeWith(1001, "going away");

        await().atMost(TIMEOUT).until(() -> !events.isEmpty());
        assertThat(events).containsExactly("closed 1001 going away");
    }

    @Test
    void aConnectionThatIsDroppedWithoutAWordIsAnErrorOrAClose() throws Exception {
        connect();

        server.drop();

        await().atMost(TIMEOUT).until(() -> !events.isEmpty());
        assertThat(events.get(0)).matches("error .*|closed .*");
    }

    @Test
    void sendingAfterAbortFails() throws Exception {
        WebSocketTransport.Connection connection = connect();

        connection.abort();

        assertThatThrownBy(() -> connection.sendText("late")).isInstanceOf(IOException.class);
    }

    @Test
    void aSendIsWhollyOnTheWireWhenSendTextReturns() throws Exception {
        WebSocketTransport.Connection connection = connect();
        AtomicInteger sent = new AtomicInteger();

        // several threads send at once: the JDK client allows one send at a time, the transport takes turns
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            int id = t;
            threads.add(Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 25; i++) {
                    try {
                        connection.sendText("t" + id + "-" + i);
                        sent.incrementAndGet();
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }

        await().atMost(TIMEOUT).until(() -> server.received.size() == 100);
        assertThat(sent.get()).isEqualTo(100);
        assertThat(server.received).doesNotHaveDuplicates();
    }

    @Test
    void aConnectionNobodyListensOnIsAnIOException() throws Exception {
        URI nobody;
        try (ServerSocket free = new ServerSocket(0)) {
            nobody = URI.create("ws://127.0.0.1:" + free.getLocalPort() + "/ws"); // closed again at once
        }

        assertThatThrownBy(() -> transport.connect(nobody, Duration.ofSeconds(2), handler))
                .isInstanceOf(IOException.class).hasMessageContaining("could not open");
    }

    // ------------------------------------------------------------------ a handshake that finishes after the caller gave up

    @Test
    void aHandshakeThatTakesLongerThanTheCallerWaitsIsAnIOExceptionAndIsCalledOffNotLeftToOpenAnOrphanSocket() throws Exception {
        server.delayHandshake(800); // the server answers the upgrade request 800 ms after it has read it

        // the caller waits 200 ms (the JDK's own limit, 5 s, is far off): it gives up
        assertThatThrownBy(() -> transport.open(server.uri(), TIMEOUT, 200, handler)).isInstanceOf(IOException.class)
                .hasMessageContaining("took more than 200 ms");

        assertThat(server.awaitClientGone(3_000)).as("the client's connection is over, not left to open when the answer comes")
                .isTrue();
        assertThat(server.leftDuringHandshake).as("and it was dropped at once, before the slow server answered").isTrue();
    }

    @Test
    void aConnectAttemptThatIsInterruptedWhileTheHandshakeIsSlowIsCalledOffToo() throws Exception {
        server.delayHandshake(800);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                transport.connect(server.uri(), TIMEOUT, handler);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        Thread.sleep(200); // it is waiting for the handshake
        caller.interrupt();
        caller.join(5_000);

        assertThat(thrown.get()).as("the interrupt is reported as it is").isInstanceOf(InterruptedException.class);
        assertThat(server.awaitClientGone(3_000)).as("no orphan socket is left behind").isTrue();
        assertThat(server.leftDuringHandshake).isTrue();
    }

    @Test
    void aHandshakeThatCompletedAtTheMomentTheCallerGaveUpHasItsSocketAborted() {
        // the race the JDK's cancel cannot win: the future completed after the wait ran out and before it was called off
        WebSocket socket = mock(WebSocket.class);
        CompletableFuture<WebSocket> completed = CompletableFuture.completedFuture(socket);

        JdkWebSocketTransport.abandon(completed);

        verify(socket).abort();
        assertThat(completed.isCancelled()).as("a future that is done stays what it was").isFalse();
    }

    @Test
    void aHandshakeThatIsStillGoingOnIsCancelledAndOneThatFailedIsLeftAlone() {
        CompletableFuture<WebSocket> pending = new CompletableFuture<>();
        CompletableFuture<WebSocket> failed = CompletableFuture.failedFuture(new IOException("refused"));

        JdkWebSocketTransport.abandon(pending);
        JdkWebSocketTransport.abandon(failed); // no socket, nothing to abort, nothing thrown

        assertThat(pending.isCancelled()).isTrue();
        assertThat(failed.isCompletedExceptionally()).isTrue();
    }

    @Test
    void aHandshakeThatCompletesInTimeIsAnOrdinaryConnectionAndIsNotAborted() throws Exception {
        server.delayHandshake(300);

        WebSocketTransport.Connection connection = transport.open(server.uri(), TIMEOUT, 3_000, handler);

        assertThat(server.awaitClient(5_000)).isTrue();
        connection.sendText("still here");
        await().atMost(TIMEOUT).until(() -> server.received.size() == 1);
        assertThat(server.awaitClientGone(500)).as("a connection somebody holds stays open").isFalse();
    }

    @Test
    void theClientIsBuiltByTheFirstConnectionAndShutDownWithTheApplication() throws Exception {
        assertThat(transport.started()).as("nothing is built before a connection is wanted").isFalse();

        connect();
        assertThat(transport.started()).isTrue();

        transport.shutdown();
        assertThat(transport.started()).isFalse();
        await().atMost(TIMEOUT).until(() -> !events.isEmpty() || server.clientClosed);
    }
}
