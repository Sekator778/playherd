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
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * {@link StreamSupervisor}: connects when somebody wants the stream, subscribes after every connect, drops a connection
 * that was closed, failed or went silent, reconnects after 1, 2, 4 … up to the maximum seconds with the backoff starting
 * over once a connection has delivered data of a subscribed channel or stayed up for 30 s (a hello does not count, and a
 * session lost sooner than 30 s is followed by at least 5 s of silence), closes a stream nobody has asked for, and stops
 * with the application. The
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
    /** What the test's stream counts as data (a price of a subscribed channel): by default every message, as a stream that cannot tell. */
    private volatile Predicate<String> isData = text -> true;

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
        public boolean isData(String text) {
            return isData.test(text);
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

    /** Keeps the connection heard from: one message every 5 s from {@code fromMs} to {@code untilMs} (inclusive), the stream wanted all along. */
    private void stayUp(StreamSupervisor supervisor, FakeWebSocketTransport.Link link, long fromMs, long untilMs) {
        for (long at = fromMs + 5_000; at <= untilMs; at += 5_000) {
            now.set(at);
            link.deliver("{\"type\":\"heartbeat\"}");
            supervisor.want(at);
            step(supervisor, at);
        }
    }

    @Test
    void aSilentConnectionIsDroppedAndAfterAShortSessionTheNextAttemptComesAfterTheFloor() {
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

        supervisor.want(START + 14_999);
        step(supervisor, START + 14_999);
        assertThat(transport.attempts).as("not yet: the session lasted 10 s, so the next attempt is 5 s after the drop at the least")
                .isEqualTo(1);
        supervisor.want(START + 15_001);
        step(supervisor, START + 15_001);
        assertThat(transport.attempts).isEqualTo(2);
        assertThat(transport.last().sent).as("subscribed anew on the new connection").containsExactly("{\"subscribe\":\"all\"}");
    }

    @Test
    void aConnectionThatLivedForAWhileAndThenWentSilentIsRetriedAfterOneSecond() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        stayUp(supervisor, transport.last(), START, START + 35_000); // a message every 5 s for 35 s, then nothing

        supervisor.want(START + 45_000);
        step(supervisor, START + 45_000);
        assertThat(supervisor.connected()).as("10 s of silence since the last message: not yet").isTrue();
        supervisor.want(START + 45_001);
        step(supervisor, START + 45_001);
        assertThat(supervisor.connected()).isFalse();

        supervisor.want(START + 45_900);
        step(supervisor, START + 45_900);
        assertThat(transport.attempts).isEqualTo(1);
        supervisor.want(START + 46_001);
        step(supervisor, START + 46_001);
        assertThat(transport.attempts).as("a session of 45 s has worked: the backoff starts at one second, no floor").isEqualTo(2);
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
    void aConnectionThatLivedAndWasClosedByTheServerOrFailedIsReconnectedAfterOneSecond() {
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        stayUp(supervisor, transport.last(), START, START + 30_000); // a connection that works, for half a minute

        long closedAt = START + 30_500;
        now.set(closedAt);
        transport.last().serverCloses(1006, "going away");
        supervisor.want(closedAt + 500);
        step(supervisor, closedAt + 500);
        assertThat(supervisor.connected()).isFalse();
        supervisor.want(closedAt + 1_400);
        step(supervisor, closedAt + 1_400);
        assertThat(transport.attempts).isEqualTo(1);
        supervisor.want(closedAt + 1_500);
        step(supervisor, closedAt + 1_500);
        assertThat(transport.attempts).isEqualTo(2);

        long reconnectedAt = closedAt + 1_500;
        stayUp(supervisor, transport.last(), reconnectedAt, reconnectedAt + 30_000);
        long failedAt = reconnectedAt + 30_500;
        now.set(failedAt);
        transport.last().serverFails(new IOException("connection reset"));
        supervisor.want(failedAt + 100);
        step(supervisor, failedAt + 100);
        assertThat(supervisor.connected()).isFalse();
        supervisor.want(failedAt + 1_100);
        step(supervisor, failedAt + 1_100);
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
    void theBackoffStartsOverOnceAConnectionHasDeliveredDataButAShortSessionKeepsTheFloor() {
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
        transport.last().deliver("{\"m\":1}"); // it works: a data message came (the test's stream counts every message)
        supervisor.want(at + 500);
        step(supervisor, at + 500);

        transport.last().serverCloses(1001, "restart");
        supervisor.want(at + 1_000);
        step(supervisor, at + 1_000); // the loss is seen here, after a session of one second: short
        supervisor.want(at + 5_900);
        step(supervisor, at + 5_900);
        assertThat(transport.attempts).as("the floor under the backoff after a short session: not before 5 s").isEqualTo(4);
        supervisor.want(at + 6_000);
        step(supervisor, at + 6_000);
        assertThat(transport.attempts).as("5 s after the loss — the backoff had started over, so not the eight").isEqualTo(5);
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

        // connect at 0, silent for 10 s (dropped at 10.25 s: the first tick past silenceMs; a session of 10 s is short, so
        // the wait is the floor of 5 s, over the 1 s of the backoff), connect again at 15.25 s, silent again (dropped at
        // 25.5 s), 5 s later at 30.5 s (the backoff is 2 s, then 4 s: still under the floor), then the backoff itself, 8 s,
        // 16 s ... — it never starts over, because nothing was ever received
        assertThat(connectsAt).containsExactly(0L, 15_250L, 30_500L, 45_750L, 64_000L);
    }

    /**
     * Steps every 250 ms from the current time to {@code untilMs} with the stream wanted all along; {@code onConnect} runs right
     * after each connection that opens. Returns the times (from {@link #START}) at which connections opened.
     */
    private List<Long> stepWhileWanted(StreamSupervisor supervisor, long untilMs, Runnable onConnect) {
        List<Long> connectsAt = new ArrayList<>();
        for (long at = now.get(); at <= untilMs; at += StreamSupervisor.TICK_MS) {
            supervisor.want(at);
            step(supervisor, at);
            if (transport.links.size() > connectsAt.size()) {
                connectsAt.add(at - START);
                onConnect.run();
            }
        }
        return connectsAt;
    }

    @Test
    void aServerThatAcceptsSaysOneThingAndClosesIsRetriedLessAndLessOftenNotEveryFewSeconds() {
        // a hello is not data: the first message of a connection no longer says that it works
        isData = text -> !text.contains("hello");
        StreamSupervisor supervisor = supervisor();

        List<Long> connectsAt = stepWhileWanted(supervisor, START + 190_000, () -> {
            transport.last().deliver("{\"hello\":1}");
            transport.last().serverCloses(1000, "bye");
        });

        // each loss is seen a tick after the connect, and the wait is the backoff or, under it, the floor of 5 s after a
        // session that did not last: 5, 5, 5, 8, 16, 32, 60 s ... — and never the second a hello used to buy
        assertThat(connectsAt).containsExactly(0L, 5_250L, 10_500L, 15_750L, 24_000L, 40_250L, 72_500L, 132_750L);
    }

    @Test
    void aServerThatSendsDataAndClosesAtOnceIsRetriedAtTheFloorNotEveryFewSeconds() {
        StreamSupervisor supervisor = supervisor();

        List<Long> connectsAt = stepWhileWanted(supervisor, START + 30_000, () -> {
            transport.last().deliver("{\"price\":1}"); // a data message: the backoff starts over — but the session was short
            transport.last().serverCloses(1000, "bye");
        });

        assertThat(connectsAt).as("the floor of %d ms after a session shorter than %d ms", StreamSupervisor.SHORT_SESSION_FLOOR_MS,
                StreamSupervisor.STABLE_AFTER_MS).containsExactly(0L, 5_250L, 10_500L, 15_750L, 21_000L, 26_250L);
    }

    @Test
    void aConnectionThatStaysUpForThirtySecondsHasWorkedWhateverItSaidAndIsRetriedAfterOneSecond() {
        isData = text -> false; // nothing but heartbeats and acknowledgements, ever
        transport.connectFailure = new IOException("connection refused");
        StreamSupervisor supervisor = supervisor();
        long at = START;
        for (; at < START + 7_000; at += StreamSupervisor.TICK_MS) { // attempts at 0, 1 and 3 s fail: the backoff stands at 8 s
            supervisor.want(at);
            step(supervisor, at);
        }
        transport.connectFailure = null;
        supervisor.want(at);
        step(supervisor, at); // the attempt at 7 s works
        assertThat(supervisor.connected()).isTrue();
        assertThat(transport.attempts).isEqualTo(4);
        long openedAt = at;
        FakeWebSocketTransport.Link link = transport.last();

        // it stays up, heard from every 5 s with something that is no data, for 30 s
        for (long t = openedAt + 5_000; t <= openedAt + StreamSupervisor.STABLE_AFTER_MS; t += 5_000) {
            now.set(t);
            link.deliver("{\"type\":\"heartbeat\"}");
            supervisor.want(t);
            step(supervisor, t);
        }
        assertThat(supervisor.connected()).isTrue();
        long lostAt = openedAt + StreamSupervisor.STABLE_AFTER_MS + 500;
        now.set(lostAt);
        link.serverCloses(1001, "restart");
        supervisor.want(lostAt);
        step(supervisor, lostAt); // the loss is seen

        supervisor.want(lostAt + 900);
        step(supervisor, lostAt + 900);
        assertThat(transport.attempts).as("not before the one second").isEqualTo(4);
        supervisor.want(lostAt + 1_000);
        step(supervisor, lostAt + 1_000);
        assertThat(transport.attempts).as("a second after a session that lasted — not the eight of the backoff before it")
                .isEqualTo(5);
    }

    @Test
    void aSessionThatLastedJustUnderThirtySecondsAndSaidNothingUsefulStillGetsTheFloorAndTheBackoffGrows() {
        isData = text -> false;
        StreamSupervisor supervisor = supervisor();
        supervisor.want(START);
        step(supervisor, START);
        FakeWebSocketTransport.Link link = transport.last();
        for (long at = START + 5_000; at <= START + 29_000; at += 5_000) {
            now.set(at);
            link.deliver("{\"type\":\"heartbeat\"}");
            supervisor.want(at);
            step(supervisor, at);
        }
        now.set(START + 29_500);
        link.serverCloses(1001, "restart");
        supervisor.want(START + 29_500);
        step(supervisor, START + 29_500); // 29.5 s: under the limit

        supervisor.want(START + 34_400);
        step(supervisor, START + 34_400);
        assertThat(transport.attempts).as("the floor, not one second").isEqualTo(1);
        supervisor.want(START + 34_500);
        step(supervisor, START + 34_500);
        assertThat(transport.attempts).isEqualTo(2);
    }

    @Test
    void aDataMessageResetsTheBackoffEvenOnAShortSessionAndAHelloDoesNot() {
        // three connects that fail leave the backoff at 8 s (attempts at 0, 1 and 3 s); the one at 7 s works
        transport.connectFailure = new IOException("connection refused");
        StreamSupervisor supervisor = supervisor();
        long at = START;
        for (; at < START + 7_000; at += StreamSupervisor.TICK_MS) {
            supervisor.want(at);
            step(supervisor, at);
        }
        assertThat(transport.attempts).isEqualTo(3);
        transport.connectFailure = null;

        // hello only: it was a short session, nothing says it worked — the 8 s of the backoff stay
        isData = text -> !text.contains("hello");
        supervisor.want(at);
        step(supervisor, at);
        assertThat(supervisor.connected()).isTrue();
        transport.last().deliver("{\"hello\":1}");
        transport.last().serverCloses(1000, "bye");
        supervisor.want(at + 250);
        step(supervisor, at + 250); // the loss is seen at 7.25 s
        supervisor.want(at + 8_200);
        step(supervisor, at + 8_200);
        assertThat(transport.attempts).as("8 s after the loss, not one second and not five").isEqualTo(4);
        supervisor.want(at + 8_250);
        step(supervisor, at + 8_250);
        assertThat(transport.attempts).isEqualTo(5);

        // data: the backoff starts over — one second, held up to the floor of 5 s by the short session
        long reopened = at + 8_250;
        transport.last().deliver("{\"price\":1}");
        transport.last().serverCloses(1000, "bye");
        supervisor.want(reopened + 250);
        step(supervisor, reopened + 250);
        supervisor.want(reopened + 5_200);
        step(supervisor, reopened + 5_200);
        assertThat(transport.attempts).as("not before the floor").isEqualTo(5);
        supervisor.want(reopened + 5_250);
        step(supervisor, reopened + 5_250);
        assertThat(transport.attempts).as("the floor, not the eight (or sixteen) seconds of an unreset backoff").isEqualTo(6);
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
        supervisor.want(START + 5_200);
        step(supervisor, START + 5_200); // the second connection: 5 s after the loss, the floor after a session of 0.1 s
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

    // ---------------------------------------------------------------------- what the operator sees

    @Test
    void theHealthSaysWhetherAnythingIsWantedOpenAndHeard() {
        StreamSupervisor supervisor = supervisor();
        assertThat(supervisor.health(START)).isEqualTo(new StreamSupervisor.Health(false, false, Long.MIN_VALUE, 0, 0));

        supervisor.want(START);
        assertThat(supervisor.health(START).wanted()).isTrue();
        assertThat(supervisor.health(START).open()).as("wanted, not connected yet").isFalse();

        step(supervisor, START);
        now.set(START + 1_500);
        transport.last().deliver("{\"m\":1}");
        StreamSupervisor.Health open = supervisor.health(START + 2_000);
        assertThat(open.open()).isTrue();
        assertThat(open.lastMessageAtMs()).isEqualTo(START + 1_500);
        assertThat(open.connects()).isEqualTo(1);
        assertThat(open.drops()).isZero();

        assertThat(supervisor.health(START + IDLE_MS + 1).wanted()).as("nobody asked for a minute").isFalse();
        transport.last().serverCloses(1006, "");
        step(supervisor, START + 2_000);
        assertThat(supervisor.health(START + 2_000).drops()).isEqualTo(1);
    }

    @Test
    void theStatusOfAStreamIsIdleRestFallbackSilentOrConnected() {
        PriceSource.Feed feed = new PriceSource.Feed("test", 15_000L, 30_000L, m -> true);
        long never = Long.MIN_VALUE;
        long fresh = 5_000L;

        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(false, false, never, 0, 0),
                START, fresh, 0)).isEqualTo(new PriceSource.FeedStatus("test", "venue", "stream", "idle", null, 0));
        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(false, true, START, 1, 0), START,
                fresh, 0).state()).as("nobody wants it: closed soon, whatever it was doing").isEqualTo("idle");
        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(true, false, never, 0, 0), START,
                fresh, 3).state()).isEqualTo("rest-fallback");
        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(true, false, never, 0, 0), START,
                fresh, 3).restReads()).isEqualTo(3);
        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(true, true, never, 1, 0), START,
                fresh, 0).state()).as("open, nothing heard yet").isEqualTo("silent");
        assertThat(StreamSupervisor.status(feed, "venue", new StreamSupervisor.Health(true, true, START - 6_000L, 1, 0),
                START, fresh, 0).state()).as("open, not heard within the limit").isEqualTo("silent");
        PriceSource.FeedStatus connected = StreamSupervisor.status(feed, "venue",
                new StreamSupervisor.Health(true, true, START - 4_000L, 1, 0), START, fresh, 0);
        assertThat(connected.state()).isEqualTo("connected");
        assertThat(connected.lastMessageAtMs()).isEqualTo(START - 4_000L);
        assertThat(connected.mode()).isEqualTo("stream");
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
