package crowdvsmarket.server.push;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** A WebSocket client for the tests of {@code /api/ws}: keeps every text frame it gets and the close it saw. */
final class WsTestClient implements WebSocket.Listener {

    final List<String> messages = new CopyOnWriteArrayList<>();
    volatile int closeCode = -1;
    volatile String closeReason;
    WebSocket socket;
    private final StringBuilder partial = new StringBuilder();

    /** Connects to {@code uri} (ws://) with the session cookie {@code sessionCookie} when it is not null. */
    static WsTestClient connect(URI uri, String sessionCookie) {
        WsTestClient client = new WsTestClient();
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (sessionCookie != null) {
            builder.header("Cookie", "herd_session=" + sessionCookie);
        }
        client.socket = builder.buildAsync(uri, client).orTimeout(20, java.util.concurrent.TimeUnit.SECONDS).join();
        return client;
    }

    void send(String text) {
        socket.sendText(text, true).join();
    }

    long count(String needle) {
        return messages.stream().filter(m -> m.contains(needle)).count();
    }

    boolean has(String needle) {
        return count(needle) > 0;
    }

    void awaitMessage(String needle) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(20))
                .until(() -> has(needle));
    }

    /** Subscribes and waits for the server's answer (every {@code subscribed} message counts: the Nth is waited for). */
    void subscribe(String... topics) {
        long before = count("\"t\":\"subscribed\"");
        StringBuilder sb = new StringBuilder("{\"op\":\"sub\",\"topics\":[");
        for (int i = 0; i < topics.length; i++) {
            sb.append(i == 0 ? "" : ",").append('"').append(topics[i]).append('"');
        }
        send(sb.append("]}").toString());
        await().atMost(Duration.ofSeconds(15)).until(() -> count("\"t\":\"subscribed\"") > before);
    }

    void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        } catch (RuntimeException e) {
            // already closed
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            messages.add(partial.toString());
            partial.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closeCode = statusCode;
        closeReason = reason;
        return null;
    }
}
