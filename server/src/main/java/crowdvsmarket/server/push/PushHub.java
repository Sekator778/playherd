package crowdvsmarket.server.push;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The output half of the game (lmax_roadmap.md §1 "Output Disruptor", step 5): who listens to which topic, and the notices that
 * tell them it changed. Knows nothing of WebSockets - a client is a {@link PushSink} - so the whole of it is tested without a socket.
 *
 * <p><b>The game is never blocked.</b> {@link #changed} is a map look-up and, when somebody listens and no notice for the topic is
 * pending, one task on the hub's own single thread; it takes no lock the game holds and waits for nothing. Every send happens on
 * that thread, through {@link PushSink#offer}, which refuses instead of waiting.
 *
 * <p><b>Coalescing.</b> A topic gets at most one notice per {@code coalesceMs}: the first change goes out at once, the changes that
 * follow within the window become ONE notice at the window's end. A notice is a topic and a version, not the state, so folding
 * changes together loses nothing: the client reads the state when it comes.
 *
 * <p><b>Slow and dead clients.</b> A client whose send buffer is full loses that notice and is owed a {@code resync} notice (read
 * everything again), sent as soon as the buffer takes one; after {@code maxDroppedNotices} lost in a row it is disconnected, as is a
 * client whose send failed. None of it reaches the game.
 */
@ApplicationScoped
public class PushHub implements ChangePublisher {

    private static final Logger LOG = Logger.getLogger(PushHub.class);
    static final String RESYNC = "{\"t\":\"resync\"}";
    private static final long NEVER = Long.MIN_VALUE / 2;

    /** One open socket. */
    public static final class Client {
        private final long id;
        private final PushSink sink;
        private final String ip;
        private final String playerId;
        private final long openedMs;
        private final Set<String> topics = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        // touched by the hub's thread only
        private boolean resyncOwed;
        private int dropped;
        // what the client may send: a token bucket
        private double tokens;
        private long refilledMs;

        private Client(long id, PushSink sink, String ip, String playerId, long nowMs, double burst) {
            this.id = id;
            this.sink = sink;
            this.ip = ip;
            this.playerId = playerId;
            this.openedMs = nowMs;
            this.tokens = burst;
            this.refilledMs = nowMs;
        }

        public String playerId() {
            return playerId;
        }

        public String ip() {
            return ip;
        }

        public int topicCount() {
            return topics.size();
        }
    }

    /** The outcome of {@link #open}: the client, or why not. */
    public record Opened(Client client, String refusal) {
    }

    /** The outcome of {@link #subscribe}: the topics now held (client names) and those refused with a reason. */
    public record Subscribed(List<String> accepted, Map<String, String> rejected) {
    }

    private static final class TopicState {
        final AtomicBoolean pending = new AtomicBoolean();
        volatile long lastSentMs = NEVER;
    }

    private final PushSettings settings;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Set<Client>> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TopicState> states = new ConcurrentHashMap<>();
    private final Map<String, Integer> perIp = new HashMap<>();
    private final Map<String, Integer> perPlayer = new HashMap<>();
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong clientIds = new AtomicLong();
    private final ScheduledThreadPoolExecutor executor;

    @Inject
    PushHub(PushConfig config) {
        this(config.settings(), System::currentTimeMillis);
    }

    PushHub(PushSettings settings, LongSupplier clock) {
        this.settings = settings;
        this.clock = clock;
        this.executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "herd-push");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.scheduleWithFixedDelay(this::sweepOldConnections, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public PushSettings settings() {
        return settings;
    }

    public long version() {
        return version.get();
    }

    public int connectionCount() {
        return clients.size();
    }

    public int subscriberCount(String topic) {
        Set<Client> subs = subscribers.get(topic);
        return subs == null ? 0 : subs.size();
    }

    /** Whether any client holds a topic that starts with {@code prefix} (the price ticker looks only while somebody listens). */
    public boolean hasSubscribers(String prefix) {
        for (Map.Entry<String, Set<Client>> entry : subscribers.entrySet()) {
            if (entry.getKey().startsWith(prefix) && !entry.getValue().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------
    // clients
    // ---------------------------------------------------------------------------------------

    /** Registers a socket unless a limit is reached ({@code server-full}, {@code ip-limit}, {@code player-limit}). */
    public synchronized Opened open(PushSink sink, String ip, String playerId) {
        if (!settings.enabled()) {
            return new Opened(null, "disabled");
        }
        if (clients.size() >= settings.maxConnections()) {
            return new Opened(null, "server-full");
        }
        if (perIp.getOrDefault(ip, 0) >= settings.maxPerIp()) {
            return new Opened(null, "ip-limit");
        }
        if (playerId != null && perPlayer.getOrDefault(playerId, 0) >= settings.maxPerPlayer()) {
            return new Opened(null, "player-limit");
        }
        Client client = new Client(clientIds.incrementAndGet(), sink, ip, playerId, clock.getAsLong(),
                Math.max(10, settings.clientMessagesPerSec() * 10));
        clients.add(client);
        perIp.merge(ip, 1, Integer::sum);
        if (playerId != null) {
            perPlayer.merge(playerId, 1, Integer::sum);
        }
        return new Opened(client, null);
    }

    /** Forgets a socket (closed by either side). Idempotent. */
    public synchronized void close(Client client) {
        if (!client.closed.compareAndSet(false, true)) {
            return;
        }
        clients.remove(client);
        for (String topic : client.topics) {
            removeSubscriber(topic, client);
        }
        client.topics.clear();
        perIp.computeIfPresent(client.ip, (k, n) -> n <= 1 ? null : n - 1);
        if (client.playerId != null) {
            perPlayer.computeIfPresent(client.playerId, (k, n) -> n <= 1 ? null : n - 1);
        }
    }

    /** Subscribes {@code client} to the client-named {@code requested} topics; {@code me} needs a signed-in socket. */
    public Subscribed subscribe(Client client, List<String> requested) {
        List<String> accepted = new ArrayList<>();
        Map<String, String> rejected = new HashMap<>();
        for (String name : requested) {
            if (!PushTopics.isClientTopic(name)) {
                rejected.put(String.valueOf(name), "bad-topic");
                continue;
            }
            String internal = internalName(client, name);
            if (internal == null) {
                rejected.put(name, "auth");
                continue;
            }
            if (!client.topics.contains(internal) && client.topics.size() >= settings.maxTopics()) {
                rejected.put(name, "too-many-topics");
                continue;
            }
            if (client.closed.get()) {
                rejected.put(name, "closed");
                continue;
            }
            if (client.topics.add(internal)) {
                subscribers.computeIfAbsent(internal, k -> ConcurrentHashMap.newKeySet()).add(client);
                if (client.closed.get()) { // closed while subscribing: do not leave a ghost
                    removeSubscriber(internal, client);
                }
            }
            accepted.add(name);
        }
        return new Subscribed(accepted, rejected);
    }

    public void unsubscribe(Client client, List<String> requested) {
        for (String name : requested) {
            String internal = internalName(client, name);
            if (internal != null && client.topics.remove(internal)) {
                removeSubscriber(internal, client);
            }
        }
    }

    private static String internalName(Client client, String name) {
        if (PushTopics.ME.equals(name)) {
            return client.playerId == null ? null : PushTopics.player(client.playerId);
        }
        return name;
    }

    private void removeSubscriber(String topic, Client client) {
        subscribers.computeIfPresent(topic, (k, set) -> {
            set.remove(client);
            return set.isEmpty() ? null : set;
        });
        if (!subscribers.containsKey(topic)) {
            states.remove(topic);
        }
    }

    /** Takes one message from the client's allowance; {@code false}: it sends too much. */
    public boolean allowClientMessage(Client client) {
        synchronized (client) {
            long now = clock.getAsLong();
            double burst = Math.max(10, settings.clientMessagesPerSec() * 10);
            client.tokens = Math.min(burst, client.tokens + (now - client.refilledMs) / 1000.0 * settings.clientMessagesPerSec());
            client.refilledMs = now;
            if (client.tokens < 1) {
                return false;
            }
            client.tokens -= 1;
            return true;
        }
    }

    // ---------------------------------------------------------------------------------------
    // publishing
    // ---------------------------------------------------------------------------------------

    @Override
    public void changed(String topic) {
        try {
            schedule(topic);
        } catch (RuntimeException e) {
            LOG.warnf("push: could not publish %s: %s", topic, e.toString()); // the game never sees a failure of the output side
        }
    }

    private void schedule(String topic) {
        if (!settings.enabled() || topic == null) {
            return;
        }
        Set<Client> subs = subscribers.get(topic);
        if (subs == null || subs.isEmpty()) {
            return;
        }
        TopicState state = states.computeIfAbsent(topic, k -> new TopicState());
        if (!state.pending.compareAndSet(false, true)) {
            return; // a notice for this topic is already on its way and will carry this change too
        }
        long delay = Math.max(0, state.lastSentMs + settings.coalesceMs() - clock.getAsLong());
        try {
            executor.schedule(() -> flush(topic, state), delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            state.pending.set(false); // shutting down
        }
    }

    @Override
    public void allPlayersChanged() {
        for (String topic : subscribers.keySet()) {
            if (topic.startsWith(PushTopics.PLAYER_PREFIX)) {
                changed(topic);
            }
        }
    }

    private void flush(String topic, TopicState state) {
        try {
            state.pending.set(false); // a change from here on schedules the next notice
            state.lastSentMs = clock.getAsLong();
            Set<Client> subs = subscribers.get(topic);
            if (subs == null) {
                return;
            }
            String text = "{\"t\":\"n\",\"topic\":\"" + PushTopics.publicName(topic) + "\",\"v\":" + version.incrementAndGet() + "}";
            for (Client client : subs) {
                deliver(client, text);
            }
        } catch (RuntimeException e) {
            LOG.warnf("push flush of %s failed: %s", topic, e.toString());
        }
    }

    private void deliver(Client client, String text) {
        if (client.closed.get()) {
            return;
        }
        try {
            if (client.resyncOwed && client.sink.offer(RESYNC)) {
                client.resyncOwed = false;
            }
            if (client.resyncOwed || !client.sink.offer(text)) {
                client.resyncOwed = true;
                client.dropped++;
                if (client.dropped > settings.maxDroppedNotices()) {
                    drop(client, 1008, "too slow");
                }
                return;
            }
            client.dropped = 0;
        } catch (RuntimeException e) {
            drop(client, 1011, "send failed");
        }
    }

    private void drop(Client client, int code, String reason) {
        close(client);
        try {
            client.sink.close(code, reason);
        } catch (RuntimeException e) {
            // already gone
        }
    }

    private void sweepOldConnections() {
        try {
            long now = clock.getAsLong();
            for (Client client : clients) {
                if (now - client.openedMs >= settings.maxAgeSec() * 1000) {
                    drop(client, 1000, "max age");
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("push sweep failed: %s", e.toString());
        }
    }
}
