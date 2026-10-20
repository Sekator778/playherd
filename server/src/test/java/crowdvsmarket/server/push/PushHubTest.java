package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The hub without a socket: the notices, the coalescing, the private topic, the limits, the slow and the dead client. */
class PushHubTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    /** A client that records what it is sent, and can be made slow (refusing) or dead (throwing). */
    private static final class FakeSink implements PushSink {
        final List<String> received = new CopyOnWriteArrayList<>();
        volatile boolean refuse;
        volatile boolean dead;
        final AtomicBoolean closed = new AtomicBoolean();
        volatile int closeCode;

        @Override
        public boolean offer(String text) {
            if (dead) {
                throw new IllegalStateException("gone");
            }
            if (refuse) {
                return false;
            }
            received.add(text);
            return true;
        }

        @Override
        public void close(int code, String reason) {
            closed.set(true);
            closeCode = code;
        }

        long notices(String topic) {
            return received.stream().filter(m -> m.contains("\"t\":\"n\"") && m.contains("\"topic\":\"" + topic + "\"")).count();
        }
    }

    private PushHub hub;

    private PushHub hub(PushSettings settings) {
        hub = new PushHub(settings, System::currentTimeMillis);
        return hub;
    }

    private static PushSettings settings(long coalesceMs, int maxPerIp, int maxPerPlayer, int maxPending, int maxDropped) {
        return new PushSettings(true, coalesceMs, 100, maxPerIp, maxPerPlayer, 5, maxPending, maxDropped, 3600, 1000);
    }

    @AfterEach
    void stop() {
        if (hub != null) {
            hub.shutdown();
        }
    }

    private PushHub.Client connect(PushHub hub, FakeSink sink, String ip, String playerId) {
        PushHub.Opened opened = hub.open(sink, ip, playerId);
        assertThat(opened.refusal()).isNull();
        return opened.client();
    }

    @Test
    void aSubscriberGetsANoticeWithTheTopicAndAVersion() {
        PushHub hub = hub(settings(50, 5, 5, 64, 100));
        FakeSink sink = new FakeSink();
        PushHub.Client client = connect(hub, sink, "1.1.1.1", null);
        hub.subscribe(client, List.of("markets", "round:BTC-H-1"));

        hub.changed("round:BTC-H-1");

        await().atMost(WAIT).untilAsserted(() -> assertThat(sink.notices("round:BTC-H-1")).isEqualTo(1));
        assertThat(sink.received.get(0)).matches("\\{\"t\":\"n\",\"topic\":\"round:BTC-H-1\",\"v\":\\d+}");
        assertThat(sink.notices("markets")).isZero();
    }

    @Test
    void nothingIsDoneForATopicNobodyListensTo() {
        PushHub hub = hub(settings(50, 5, 5, 64, 100));
        FakeSink sink = new FakeSink();
        hub.subscribe(connect(hub, sink, "1.1.1.1", null), List.of("markets"));

        hub.changed("market:ETH");
        hub.betChanged("ETH", "ETH-H-1", "p1");

        assertThat(hub.subscriberCount("market:ETH")).isZero();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(150));
        assertThat(sink.received).isEmpty();
    }

    @Test
    void aBurstOfChangesIsCoalescedIntoOneNoticeAtTheEndOfTheWindow() {
        PushHub hub = hub(settings(300, 5, 5, 64, 100));
        FakeSink sink = new FakeSink();
        hub.subscribe(connect(hub, sink, "1.1.1.1", null), List.of("markets"));
        hub.changed("markets");
        await().atMost(WAIT).until(() -> sink.notices("markets") == 1); // the first change goes out at once

        for (int i = 0; i < 500; i++) {
            hub.changed("markets");
        }

        // the 500 that follow inside the window become ONE notice, at the window's end
        await().atMost(WAIT).untilAsserted(() -> assertThat(sink.notices("markets")).isEqualTo(2));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(700));
        assertThat(sink.notices("markets")).isEqualTo(2);
    }

    @Test
    void noTopicGetsMoreThanOneNoticePerWindow() throws Exception {
        PushHub hub = hub(settings(200, 5, 5, 64, 100));
        FakeSink sink = new FakeSink();
        hub.subscribe(connect(hub, sink, "1.1.1.1", null), List.of("markets"));

        long end = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < end) {
            hub.changed("markets");
            Thread.sleep(5);
        }
        Thread.sleep(300);

        // 1 s of constant change with a 200 ms window: 5 or 6 notices, never one per change (about 200)
        assertThat(sink.notices("markets")).isBetween(4L, 7L);
    }

    @Test
    void theNoticeVersionGrows() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink sink = new FakeSink();
        hub.subscribe(connect(hub, sink, "1.1.1.1", null), List.of("markets"));
        hub.changed("markets");
        await().atMost(WAIT).until(() -> sink.notices("markets") == 1);
        hub.changed("markets");
        await().atMost(WAIT).until(() -> sink.notices("markets") == 2);

        long first = Long.parseLong(sink.received.get(0).replaceAll(".*\"v\":(\\d+).*", "$1"));
        long second = Long.parseLong(sink.received.get(1).replaceAll(".*\"v\":(\\d+).*", "$1"));
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void thePrivateTopicNeedsASignedInSocketAndOnlyTheOwnerIsTold() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink anonymous = new FakeSink();
        FakeSink alice = new FakeSink();
        FakeSink bob = new FakeSink();
        PushHub.Client anonymousClient = connect(hub, anonymous, "1.1.1.1", null);
        PushHub.Subscribed refused = hub.subscribe(anonymousClient, List.of("me"));
        hub.subscribe(connect(hub, alice, "1.1.1.2", "alice"), List.of("me"));
        hub.subscribe(connect(hub, bob, "1.1.1.3", "bob"), List.of("me"));

        hub.betChanged("BTC", "BTC-H-1", "alice");

        assertThat(refused.accepted()).isEmpty();
        assertThat(refused.rejected()).containsEntry("me", "auth");
        await().atMost(WAIT).untilAsserted(() -> assertThat(alice.notices("me")).isEqualTo(1));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(150));
        assertThat(bob.received).isEmpty();
        assertThat(anonymous.received).isEmpty();
    }

    @Test
    void aClientCannotAskForAnotherPlayersTopicOrABadOne() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        PushHub.Client client = connect(hub, new FakeSink(), "1.1.1.1", "alice");

        PushHub.Subscribed result = hub.subscribe(client, List.of("player:bob", "nonsense", "round:", "market:BTC", "feed:x y"));

        assertThat(result.accepted()).containsExactly("market:BTC");
        assertThat(result.rejected()).containsKeys("player:bob", "nonsense", "round:", "feed:x y");
        assertThat(hub.subscriberCount("player:bob")).isZero();
    }

    @Test
    void aSocketHoldsAtMostTheConfiguredNumberOfTopics() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100)); // maxTopics = 5
        PushHub.Client client = connect(hub, new FakeSink(), "1.1.1.1", null);

        PushHub.Subscribed result = hub.subscribe(client,
                List.of("market:A", "market:B", "market:C", "market:D", "market:E", "market:F"));

        assertThat(result.accepted()).hasSize(5);
        assertThat(result.rejected()).containsEntry("market:F", "too-many-topics");
    }

    @Test
    void unsubscribingAndClosingStopTheNotices() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink one = new FakeSink();
        FakeSink two = new FakeSink();
        PushHub.Client first = connect(hub, one, "1.1.1.1", null);
        PushHub.Client second = connect(hub, two, "1.1.1.2", null);
        hub.subscribe(first, List.of("markets"));
        hub.subscribe(second, List.of("markets"));
        hub.unsubscribe(first, List.of("markets"));
        hub.close(second);

        hub.changed("markets");

        assertThat(hub.subscriberCount("markets")).isZero();
        assertThat(hub.connectionCount()).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(150));
        assertThat(one.received).isEmpty();
        assertThat(two.received).isEmpty();
    }

    @Test
    void connectionsAreLimitedPerAddressAndPerPlayerAndFreedOnClose() {
        PushHub hub = hub(settings(0, 2, 3, 64, 100));
        PushHub.Client a1 = connect(hub, new FakeSink(), "9.9.9.9", null);
        connect(hub, new FakeSink(), "9.9.9.9", null);

        assertThat(hub.open(new FakeSink(), "9.9.9.9", null).refusal()).isEqualTo("ip-limit");
        hub.close(a1);
        assertThat(hub.open(new FakeSink(), "9.9.9.9", null).refusal()).isNull();

        connect(hub, new FakeSink(), "8.8.8.1", "p");
        connect(hub, new FakeSink(), "8.8.8.2", "p");
        connect(hub, new FakeSink(), "8.8.8.3", "p");
        assertThat(hub.open(new FakeSink(), "8.8.8.4", "p").refusal()).isEqualTo("player-limit");
        assertThat(hub.open(new FakeSink(), "8.8.8.4", "q").refusal()).isNull();
    }

    @Test
    void theWholeServerHasALimitToo() {
        hub = new PushHub(new PushSettings(true, 0, 2, 10, 10, 5, 64, 100, 3600, 1000), System::currentTimeMillis);
        connect(hub, new FakeSink(), "1.1.1.1", null);
        connect(hub, new FakeSink(), "1.1.1.2", null);

        assertThat(hub.open(new FakeSink(), "1.1.1.3", null).refusal()).isEqualTo("server-full");
    }

    @Test
    void aSlowClientLosesNoticesIsOwedAResyncAndNeverHoldsUpTheOthers() {
        PushHub hub = hub(settings(0, 5, 5, 64, 1000));
        FakeSink slow = new FakeSink();
        FakeSink fast = new FakeSink();
        hub.subscribe(connect(hub, slow, "1.1.1.1", null), List.of("markets"));
        hub.subscribe(connect(hub, fast, "1.1.1.2", null), List.of("markets"));
        slow.refuse = true;

        hub.changed("markets");
        await().atMost(WAIT).until(() -> fast.notices("markets") == 1);
        hub.changed("markets");
        await().atMost(WAIT).until(() -> fast.notices("markets") == 2);

        assertThat(slow.received).isEmpty();
        assertThat(slow.closed.get()).isFalse();

        slow.refuse = false; // its buffer drained
        hub.changed("markets");
        await().atMost(WAIT).until(() -> fast.notices("markets") == 3);
        assertThat(slow.received.get(0)).isEqualTo(PushHub.RESYNC);
        assertThat(slow.notices("markets")).isEqualTo(1);
    }

    @Test
    void aClientThatStaysSlowIsDisconnected() {
        PushHub hub = hub(settings(0, 5, 5, 64, 3));
        FakeSink slow = new FakeSink();
        hub.subscribe(connect(hub, slow, "1.1.1.1", null), List.of("markets"));
        slow.refuse = true;

        for (int i = 0; i < 6; i++) {
            hub.changed("markets");
            int expected = i;
            await().atMost(WAIT).until(() -> hub.version() > expected || slow.closed.get());
        }

        await().atMost(WAIT).until(() -> slow.closed.get());
        assertThat(slow.closeCode).isEqualTo(1008);
        assertThat(hub.connectionCount()).isZero();
        assertThat(hub.subscriberCount("markets")).isZero();
    }

    @Test
    void aDeadClientIsDroppedAndTheOthersStillGetTheirNotice() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink dead = new FakeSink();
        FakeSink alive = new FakeSink();
        hub.subscribe(connect(hub, dead, "1.1.1.1", null), List.of("markets"));
        hub.subscribe(connect(hub, alive, "1.1.1.2", null), List.of("markets"));
        dead.dead = true;

        hub.changed("markets");

        await().atMost(WAIT).untilAsserted(() -> assertThat(alive.notices("markets")).isEqualTo(1));
        await().atMost(WAIT).until(() -> dead.closed.get());
        assertThat(hub.connectionCount()).isEqualTo(1);
    }

    @Test
    void publishingNeverWaitsForAClient() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        AtomicInteger offers = new AtomicInteger();
        PushSink blocking = new PushSink() {
            @Override
            public boolean offer(String text) {
                offers.incrementAndGet();
                try {
                    Thread.sleep(2000); // a sink that stalls the hub's thread
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            @Override
            public void close(int code, String reason) {
            }
        };
        hub.subscribe(hub.open(blocking, "1.1.1.1", null).client(), List.of("markets"));

        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            hub.changed("markets");
            hub.betChanged("BTC", "BTC-H-1", "p" + i);
        }
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).as("the caller (the game) is not held up by a stalled client").isLessThan(500);
    }

    @Test
    void allPlayersChangedReachesEverySignedInSocket() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink alice = new FakeSink();
        FakeSink bob = new FakeSink();
        hub.subscribe(connect(hub, alice, "1.1.1.1", "alice"), List.of("me"));
        hub.subscribe(connect(hub, bob, "1.1.1.2", "bob"), List.of("me"));

        hub.roundCancelled("BTC", "BTC-H-1");

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(alice.notices("me")).isEqualTo(1);
            assertThat(bob.notices("me")).isEqualTo(1);
        });
    }

    @Test
    void roundSettledTellsTheRoundTheFeedTheMarketAndTheBettors() {
        PushHub hub = hub(settings(0, 5, 5, 64, 100));
        FakeSink watcher = new FakeSink();
        FakeSink bettor = new FakeSink();
        FakeSink other = new FakeSink();
        hub.subscribe(connect(hub, watcher, "1.1.1.1", null),
                List.of("round:R1", "feed:R1", "market:BTC", "markets"));
        hub.subscribe(connect(hub, bettor, "1.1.1.2", "alice"), List.of("me"));
        hub.subscribe(connect(hub, other, "1.1.1.3", "bob"), List.of("me"));

        hub.roundSettled("BTC", "R1", List.of("alice"));

        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(watcher.notices("round:R1")).isEqualTo(1);
            assertThat(watcher.notices("feed:R1")).isEqualTo(1);
            assertThat(watcher.notices("market:BTC")).isEqualTo(1);
            assertThat(watcher.notices("markets")).isEqualTo(1);
            assertThat(bettor.notices("me")).isEqualTo(1);
        });
        assertThat(other.received).isEmpty();
    }

    @Test
    void disabledMeansNoSocketsAndNoNotices() {
        hub = new PushHub(new PushSettings(false, 0, 10, 10, 10, 5, 64, 100, 3600, 1000), System::currentTimeMillis);

        assertThat(hub.open(new FakeSink(), "1.1.1.1", null).refusal()).isEqualTo("disabled");
        hub.changed("markets");
        assertThat(hub.version()).isZero();
    }

    @Test
    void aClientThatSendsTooMuchIsRefused() {
        hub = new PushHub(new PushSettings(true, 0, 10, 10, 10, 5, 64, 100, 3600, 1), System::currentTimeMillis);
        PushHub.Client client = connect(hub, new FakeSink(), "1.1.1.1", null);

        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (hub.allowClientMessage(client)) {
                allowed++;
            }
        }

        assertThat(allowed).as("a burst of ten, then one a second").isBetween(10, 12);
    }

    @Test
    void anOldSocketIsClosed() {
        long[] now = {1_000_000};
        hub = new PushHub(new PushSettings(true, 0, 10, 10, 10, 5, 64, 100, 60, 1000), () -> now[0]);
        FakeSink sink = new FakeSink();
        connect(hub, sink, "1.1.1.1", null);

        now[0] += 61_000;
        hub.shutdown(); // the sweep runs every 30 s on the hub's thread; call it the way it does
        try {
            java.lang.reflect.Method sweep = PushHub.class.getDeclaredMethod("sweepOldConnections");
            sweep.setAccessible(true);
            sweep.invoke(hub);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }

        assertThat(sink.closed.get()).isTrue();
        assertThat(hub.connectionCount()).isZero();
    }
}
