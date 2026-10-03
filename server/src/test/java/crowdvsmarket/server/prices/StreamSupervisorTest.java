package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamSupervisor}: connects when somebody wants the stream, subscribes after every connect, drops a connection
 * that was closed, failed or went silent, reconnects after 1, 2, 4 … up to the maximum seconds with the backoff starting
 * over once a connection has worked, closes a stream nobody has asked for, and stops with the application. The
 * decisions are {@code step(now)}'s, driven here with a clock of the test's own and a scripted transport — no thread,
 * no sleeping; one test runs the real virtual thread.
 */
class StreamSupervisorTest {

    private static final long SILENCE_MS = 10_000L;
    private static final long MAX_BACKOFF_MS = 60_000L;
    private static final long IDLE_MS = 60_000L;
    private static final long START = 1_000_000L;

    private final AtomicLong now = new AtomicLong(START);
    private final FakeWebSocketTransport transport = new FakeWebSocketTransport();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private List<String> subscribe = List.of("{\"subscribe\":\"all\"}");
    private String keepAlive;
    private volatile Boolean subscribedOnAVirtualThread;

    private final StreamSupervisor.Stream stream = new StreamSupervisor.Stream() {
        @Override
        public String name() {
            return "test";
        }

        @Override
        public URI uri() {
            return URI.create("wss://stream.test.invalid/ws");
        }

        @Override
        public List<String> subscribeMessages() {
            subscribedOnAVirtualThread = Thread.currentThread().isVirtual();
            return subscribe;
        }

        @Override
        public String keepAliveMessage() {
            return keepAlive;
        }

        @Override
        public void onMessage(String text, long rxMs) {
            received.add(rxMs + " " + text);
        }
    };

    private StreamSupervisor supervisor() {
        return new StreamSupervisor(stream, transport, now::get, SILENCE_MS, MAX_BACKOFF_MS, IDLE_MS, false);
    }

    private void step(StreamSupervisor supervisor, long atMs) {
        now.set(atMs);
        supervisor.step(atMs);
    }

    /** Steps every 250 ms (the thread's own tick) from the current time up to {@code untilMs}; returns the times at which a connect was tried. */
    private List<Long> stepUntil(StreamSupervisor supervisor, long untilMs) {
        List<Long> attemptsAt = new ArrayList<>();
        for (long at = now.get(); at <= untilMs; at += StreamSupervisor.TICK_MS) {
            int before = transport.attempts;
            step(supervisor, at);
            if (transport.attempts > before) {
                attemptsAt.add(at - START);
            }
        }
        return attemptsAt;
    }

    @Test
    void noOneWantsItNothingIsConnected() {
        StreamSupervisor supervisor = supervisor();

        stepUntil(supervisor, START + 5_000);

        assertThat(transport.attempts).isZero();
        assertThat(supervisor.connected()).isFalse();
    }

    @Test
    void whenWantedItConnectsAndSendsTheSubscription() {
        StreamSupervisor supervisor = supervisor();

        supervisor.want(START);
        step(supervisor, START);

        assertThat(supervisor.connected()).isTrue();
        assertThat(transport.attempts).isEqualTo(1);
        assertThat(transport.last().uri).isEqualTo(URI.create("wss://stream.test.invalid/ws"));
        assertThat(transport.last().sent).containsExactly("{\"subscribe\":\"all\"}");
        assertThat(transport.timeouts.get(0)).isEqualTo(Duration.ofMillis(StreamSupervisor.CONNECT_TIMEOUT_MS));
        assertThat(supervisor.connects()).isEqualTo(1);
    }

    @Test
    void messagesGoToTheStreamWithTheTimeTheyArrivedAt() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);

        now.set(START + 1_234);
        transport.last().deliver("{\"m\":1}");
        now.set(START + 2_000);
        transport.last().deliver("{\"m\":2}");

        assertThat(received).containsExactly((START + 1_234) + " {\"m\":1}", (START + 2_000) + " {\"m\":2}");
    }

    @Test
    void aSilentConnectionIsDroppedAndTheNextAttemptComesAfterOneSecond() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        transport.last().deliver("{\"m\":1}");

        // keep asking so that it is not an idle stream; a message every few seconds keeps it
        for (long at = START + 1_000; at <= START + 9_000; at += 1_000) {
            supervisor.want(at);
            step(supervisor, at);
        }
        assertThat(supervisor.connected()).as("9 s of silence is still within the 10 s").isTrue();

        supervisor.want(START + 10_001);
        step(supervisor, START + 10_001); // more than silenceMs since the connect
        assertThat(supervisor.connected()).isFalse();
        assertThat(transport.last().aborted).isTrue();
        assertThat(supervisor.drops()).isEqualTo(1);

        supervisor.want(START + 10_999);
        step(supervisor, START + 10_999);
        assertThat(transport.attempts).as("not yet: one second after the drop").isEqualTo(1);
        supervisor.want(START + 11_001);
        step(supervisor, START + 11_001);
        assertThat(transport.attempts).isEqualTo(2);
        assertThat(transport.last().sent).as("subscribed anew on the new connection").containsExactly("{\"subscribe\":\"all\"}");
    }

    @Test
    void anyMessageKeepsTheConnectionAlive_aHeartbeatIsOneToo() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);

        for (long at = START + 5_000; at <= START + 60_000; at += 5_000) {
            now.set(at);
            transport.last().deliver("{\"type\":\"heartbeat\"}");
            supervisor.want(at);
            step(supervisor, at);
        }

        assertThat(supervisor.connected()).isTrue();
        assertThat(transport.attempts).isEqualTo(1);
    }

    @Test
    void aConnectionTheServerClosedOrThatFailedIsReconnectedAfterOneSecond() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        transport.last().deliver("{\"m\":1}");

        transport.last().serverCloses(1006, "going away");
        supervisor.want(START + 500);
        step(supervisor, START + 500);
        assertThat(supervisor.connected()).isFalse();
        supervisor.want(START + 1_400);
        step(supervisor, START + 1_400);
        assertThat(transport.attempts).isEqualTo(1);
        supervisor.want(START + 1_500);
        step(supervisor, START + 1_500);
        assertThat(transport.attempts).isEqualTo(2);

        now.set(START + 2_000);
        transport.last().deliver("{\"m\":2}");
        transport.last().serverFails(new IOException("connection reset"));
        supervisor.want(START + 2_100);
        step(supervisor, START + 2_100);
        assertThat(supervisor.connected()).isFalse();
        supervisor.want(START + 3_100);
        step(supervisor, START + 3_100);
        assertThat(transport.attempts).isEqualTo(3);
    }

    @Test
    void aServerThatCannotBeReachedIsTriedAfter1_2_4_8_16_32_then_every_60_seconds() {
        transport.connectFailure = new IOException("connection refused");
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);

        // wanted all along: ask at every tick (a poll every 2 s does)
        List<Long> attemptsAt = new ArrayList<>();
        for (long at = START; at <= START + 190_000; at += StreamSupervisor.TICK_MS) {
            supervisor.want(at);
            int before = transport.attempts;
            step(supervisor, at);
            if (transport.attempts > before) {
                attemptsAt.add(at - START);
            }
        }

        assertThat(attemptsAt).containsExactly(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 63_000L, 123_000L, 183_000L);
        assertThat(supervisor.connected()).isFalse();
        assertThat(supervisor.connects()).isZero();
    }

    @Test
    void theBackoffStartsOverOnceAConnectionHasWorked() {
        transport.connectFailure = new IOException("connection refused");
        StreamSupervisor supervisor = supervisor();
        long at = START;
        // three failures, at 0, 1 and 3 s: waits of 1, 2 and 4 s, so the fourth attempt is due at +7 s
        for (; at < START + 7_000; at += StreamSupervisor.TICK_MS) {
            supervisor.want(at);
            step(supervisor, at);
        }
        assertThat(transport.attempts).isEqualTo(3);
        transport.connectFailure = null;
        at = START + 7_000;
        supervisor.want(at);
        step(supervisor, at); // the attempt at 7 s works
        assertThat(supervisor.connected()).isTrue();
        now.set(at + 500);
        transport.last().deliver("{\"m\":1}"); // it works: a message came
        supervisor.want(at + 500);
        step(supervisor, at + 500);

        transport.last().serverCloses(1001, "restart");
        supervisor.want(at + 1_000);
        step(supervisor, at + 1_000); // the loss is seen here
        supervisor.want(at + 1_900);
        step(supervisor, at + 1_900);
        assertThat(transport.attempts).as("not before a second").isEqualTo(4);
        supervisor.want(at + 2_000);
        step(supervisor, at + 2_000);
        assertThat(transport.attempts).as("a second after the loss, not eight").isEqualTo(5);
    }

    @Test
    void aServerThatAcceptsAndSaysNothingIsRetriedLessAndLessOften() {
        StreamSupervisor supervisor = supervisor();
        List<Long> connectsAt = new ArrayList<>();
        for (long at = START; at <= START + 80_000; at += StreamSupervisor.TICK_MS) {
            supervisor.want(at);
            int before = transport.attempts;
            step(supervisor, at);
            if (transport.attempts > before) {
                connectsAt.add(at - START);
            }
        }

        // connect at 0, silent for 10 s (dropped at 10.25 s: the first tick past silenceMs), connect again 1 s later, at
        // 11.25 s, silent again (dropped at 21.5 s), 2 s later at 23.5 s, then 4 s, 8 s ... — the backoff never starts
        // over, because nothing was ever received
        assertThat(connectsAt).containsExactly(0L, 11_250L, 23_500L, 37_750L, 56_000L);
    }

    @Test
    void aFailedSubscriptionIsAFailedConnect() {
        // the connection opens but the subscribe cannot be sent: the transport hands out links that refuse to send
        FakeWebSocketTransport refusing = new FakeWebSocketTransport() {
            @Override
            public Connection connect(URI uri, Duration timeout, Handler handler) throws IOException {
                Connection link = super.connect(uri, timeout, handler);
                ((Link) link).sendFailure = new IOException("broken pipe");
                return link;
            }
        };
        StreamSupervisor s = new StreamSupervisor(stream, refusing, now::get, SILENCE_MS, MAX_BACKOFF_MS, IDLE_MS, false);

        s.want(START);
        step(s, START);

        assertThat(s.connected()).isFalse();
        assertThat(refusing.last().aborted).isTrue();
        assertThat(s.connects()).isZero();
        s.want(START + 900);
        step(s, START + 900);
        assertThat(refusing.attempts).isEqualTo(1);
        s.want(START + 1_000);
        step(s, START + 1_000);
        assertThat(refusing.attempts).isEqualTo(2);
    }

    @Test
    void aStreamNobodyAsksForIsClosedAndComesBackAtOnce() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        transport.last().deliver("{\"m\":1}");
        FakeWebSocketTransport.Link first = transport.last();

        for (long at = START + 5_000; at <= START + IDLE_MS; at += 5_000) { // heartbeats flow, nobody polls
            now.set(at);
            first.deliver("{\"type\":\"heartbeat\"}");
            step(supervisor, at);
        }
        assertThat(supervisor.connected()).as("a minute after the last poll it is still open").isTrue();

        now.set(START + IDLE_MS + 1);
        first.deliver("{\"type\":\"heartbeat\"}");
        step(supervisor, START + IDLE_MS + 1);
        assertThat(supervisor.connected()).isFalse();
        assertThat(first.aborted).isTrue();
        assertThat(transport.attempts).isEqualTo(1);

        step(supervisor, START + IDLE_MS + 30_000);
        assertThat(transport.attempts).as("nobody wants it: no reconnect").isEqualTo(1);

        supervisor.want(START + IDLE_MS + 31_000);
        step(supervisor, START + IDLE_MS + 31_000);
        assertThat(supervisor.connected()).as("wanted again: connected at once, no backoff").isTrue();
        assertThat(transport.attempts).isEqualTo(2);
    }

    @Test
    void theKeepAliveGoesOutEveryThirtySecondsWhileConnected() {
        keepAlive = "{\"method\":\"ping\"}";
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);

        for (long at = START + 5_000; at <= START + 95_000; at += 5_000) {
            now.set(at);
            transport.last().deliver("{\"channel\":\"pong\"}");
            supervisor.want(at);
            step(supervisor, at);
        }

        assertThat(transport.last().sent).containsExactly("{\"subscribe\":\"all\"}", "{\"method\":\"ping\"}",
                "{\"method\":\"ping\"}", "{\"method\":\"ping\"}"); // at 30, 60 and 90 s after the connect
    }

    @Test
    void aLateMessageOfADroppedConnectionIsIgnored() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        FakeWebSocketTransport.Link first = transport.last();
        first.serverCloses(1006, "");
        supervisor.want(START + 100);
        step(supervisor, START + 100);
        supervisor.want(START + 1_200);
        step(supervisor, START + 1_200); // the second connection
        assertThat(transport.attempts).isEqualTo(2);

        first.deliver("{\"late\":true}"); // the old socket says one more thing
        transport.last().deliver("{\"fresh\":true}");

        assertThat(received).hasSize(1).allMatch(m -> m.contains("fresh"));
    }

    @Test
    void sendGoesOnTheOpenConnectionOnly() {
        StreamSupervisor supervisor = supervisor();
        assertThat(supervisor.send("{\"x\":1}")).as("nothing open").isFalse();

        supervisor.want(START);
        step(supervisor, START);
        assertThat(supervisor.send("{\"x\":2}")).isTrue();
        assertThat(transport.last().sent).containsExactly("{\"subscribe\":\"all\"}", "{\"x\":2}");

        transport.last().sendFailure = new IOException("broken pipe");
        assertThat(supervisor.send("{\"x\":3}")).as("a send that fails is false, not an exception").isFalse();
    }

    @Test
    void closeDropsTheConnectionAndNothingIsConnectedAfterIt() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        FakeWebSocketTransport.Link link = transport.last();

        supervisor.close();

        assertThat(link.aborted).isTrue();
        assertThat(supervisor.connected()).isFalse();
        supervisor.want(START + 100);
        step(supervisor, START + 100);
        step(supervisor, START + 5_000);
        assertThat(transport.attempts).isEqualTo(1);
    }

    @Test
    void theThreadIsAVirtualOneStartedByTheFirstWantAndEndedByClose() {
        StreamSupervisor supervisor = new StreamSupervisor(stream, transport, System::currentTimeMillis, SILENCE_MS,
                MAX_BACKOFF_MS, IDLE_MS);
        assertThat(supervisor.running()).as("not before somebody wants it").isFalse();
        assertThat(transport.attempts).isZero();

        supervisor.want(System.currentTimeMillis());
        await().atMost(Duration.ofSeconds(5)).until(() -> supervisor.connected() && !transport.last().sent.isEmpty());
        assertThat(supervisor.running()).isTrue();
        assertThat(transport.last().sent).containsExactly("{\"subscribe\":\"all\"}");
        assertThat(subscribedOnAVirtualThread).as("the supervisor works on a virtual thread").isTrue();

        transport.last().deliver("{\"live\":true}");
        assertThat(received).hasSize(1);

        supervisor.close();
        await().atMost(Duration.ofSeconds(5)).until(() -> !supervisor.running());
        assertThat(transport.last().aborted).isTrue();
        assertThat(supervisor.connected()).isFalse();
    }
}
