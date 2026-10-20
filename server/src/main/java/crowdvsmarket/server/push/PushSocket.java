package crowdvsmarket.server.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * {@code /api/ws}: the endpoint (Quarkus WebSockets Next). Messages from the client, JSON text:
 * <pre>
 *   {"op":"sub","topics":["markets","round:BTC-..."]}    -> {"t":"subscribed","topics":[...],"rejected":{"me":"auth"}}
 *   {"op":"unsub","topics":[...]}                        -> {"t":"unsubscribed"}
 *   {"op":"ping"}                                        -> {"t":"pong"}
 * </pre>
 * From the server: {@code {"t":"hello","v":N}} on open, {@code {"t":"n","topic":"...","v":N}} notices, {@code {"t":"resync"}}
 * (notices were dropped for this client: read everything again), {@code {"t":"error","code":"..."}}. The server never sends data.
 * Who may do what is decided by {@link PushUpgradeCheck} (limits, session) and {@link PushHub} (the topics); this class only
 * connects a socket to the hub.
 */
@WebSocket(path = "/api/ws")
public class PushSocket {

    private static final Logger LOG = Logger.getLogger(PushSocket.class);
    private static final int MAX_MESSAGE_CHARS = 4096;

    @Inject
    PushHub hub;
    @Inject
    ObjectMapper json;

    private final ConcurrentHashMap<String, Held> open = new ConcurrentHashMap<>();

    private record Held(PushHub.Client client, ConnectionSink sink) {
    }

    /** One connection as the hub sees it. A write that has not completed counts as pending; past the limit a notice is refused. */
    private final class ConnectionSink implements PushSink {
        private final WebSocketConnection connection;
        private final AtomicInteger pending = new AtomicInteger();
        private volatile PushHub.Client client;

        ConnectionSink(WebSocketConnection connection) {
            this.connection = connection;
        }

        @Override
        public boolean offer(String text) {
            if (connection.isClosed()) {
                return false;
            }
            if (pending.incrementAndGet() > hub.settings().maxPendingSends()) {
                pending.decrementAndGet();
                return false;
            }
            try {
                connection.sendText(text).subscribe().with(
                        ok -> pending.decrementAndGet(),
                        failure -> {
                            pending.decrementAndGet();
                            PushHub.Client c = client;
                            if (c != null) {
                                hub.close(c); // a dead socket: forget it
                            }
                        });
            } catch (RuntimeException e) {
                pending.decrementAndGet();
                return false;
            }
            return true;
        }

        @Override
        public void close(int code, String reason) {
            try {
                connection.close(new CloseReason(code, reason)).subscribe().with(ok -> { }, failure -> { });
            } catch (RuntimeException e) {
                // already closed
            }
        }
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        String ip = connection.userData().get(PushUpgradeCheck.IP);
        String playerId = connection.userData().get(PushUpgradeCheck.PLAYER);
        ConnectionSink sink = new ConnectionSink(connection);
        PushHub.Opened opened = hub.open(sink, ip == null ? "unknown" : ip, playerId);
        if (opened.client() == null) {
            sink.close(1013, opened.refusal()); // try again later
            return;
        }
        sink.client = opened.client();
        open.put(connection.id(), new Held(opened.client(), sink));
        sink.offer("{\"t\":\"hello\",\"v\":" + hub.version() + ",\"auth\":" + (playerId != null) + "}");
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        Held held = open.get(connection.id());
        if (held == null) {
            return;
        }
        try {
            if (message.length() > MAX_MESSAGE_CHARS) {
                held.sink().close(1009, "message too big");
                return;
            }
            if (!hub.allowClientMessage(held.client())) {
                held.sink().close(1008, "too many messages");
                return;
            }
            JsonNode body = json.readTree(message);
            String op = body.path("op").asText("");
            switch (op) {
                case "sub" -> {
                    PushHub.Subscribed result = hub.subscribe(held.client(), topics(body));
                    held.sink().offer(json.writeValueAsString(java.util.Map.of(
                            "t", "subscribed", "topics", result.accepted(), "rejected", result.rejected())));
                }
                case "unsub" -> {
                    hub.unsubscribe(held.client(), topics(body));
                    held.sink().offer("{\"t\":\"unsubscribed\"}");
                }
                case "ping" -> held.sink().offer("{\"t\":\"pong\"}");
                default -> held.sink().offer("{\"t\":\"error\",\"code\":\"bad-op\"}");
            }
        } catch (Exception e) {
            LOG.debugf("push: bad message from %s: %s", connection.id(), e.toString());
            held.sink().offer("{\"t\":\"error\",\"code\":\"bad-message\"}");
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        Held held = open.remove(connection.id());
        if (held != null) {
            hub.close(held.client());
        }
    }

    private static List<String> topics(JsonNode body) {
        List<String> topics = new ArrayList<>();
        JsonNode array = body.path("topics");
        if (array.isArray()) {
            for (JsonNode topic : array) {
                topics.add(topic.asText(""));
            }
        }
        return topics;
    }
}
