package crowdvsmarket.server.push;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.service.GameService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The part of the "changed" notices that no event announces: a market's price moved, or its round windows moved on with the clock
 * (a betting round starts running, the running one ends). Four times a second (own thread) it compares a small signature of every running
 * market - the latest quote and the state of the round cursors against the clock - with the last one and publishes
 * {@code market:<id>} (and {@code markets}) when it differs. It reads memory only (the quote ring, the round cursors), takes no
 * lock, and does nothing at all while nobody listens to a market topic.
 *
 * <p>Bets, settlements and cancellations are published where they are committed ({@code GameService}).
 */
@ApplicationScoped
public class PushTicker {

    private static final Logger LOG = Logger.getLogger(PushTicker.class);
    private static final long TICK_MS = 250;

    @Inject
    PushHub hub;
    @Inject
    GameService game;
    @Inject
    RoundProgress progress;

    @ConfigProperty(name = "herd.push.price-interval-ms", defaultValue = "1000")
    long priceIntervalMs;

    /** Per market: the windows part of the signature, the price part, and when a price change was last announced. */
    private final Map<String, String> lastWindows = new HashMap<>();
    private final Map<String, String> lastPrice = new HashMap<>();
    private final Map<String, Long> lastPriceAnnouncedMs = new HashMap<>();

    private ScheduledExecutorService executor;

    /** Every 250 ms on a thread of its own (the Quarkus scheduler does not run anything more often than once a second). */
    void onStart(@Observes StartupEvent event) {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "herd-push-ticker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::tick, 1000, TICK_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void tick() {
        try {
            scan(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.debugf("push ticker: %s", e.toString());
        }
    }

    void scan(long nowMs) {
        if (!hub.enabled() || !(hub.hasSubscribers(PushTopics.MARKETS) || hub.hasSubscribers(PushTopics.MARKET_PREFIX))) {
            lastWindows.clear(); // nobody is looking: the next listener starts from a fresh read, not from a stale signature
            lastPrice.clear();
            return;
        }
        boolean any = false;
        for (Market market : game.runningMarkets()) {
            String windows = windows(market, nowMs);
            String price = price(market, nowMs);
            // a round moving on is announced at once; a price is announced at most every priceIntervalMs (a quote can change many
            // times a second, and every notice makes each client read again)
            boolean windowsMoved = !windows.equals(lastWindows.put(market.id(), windows));
            boolean priceMoved = !price.equals(lastPrice.get(market.id()))
                    && nowMs - lastPriceAnnouncedMs.getOrDefault(market.id(), 0L) >= priceIntervalMs;
            if (priceMoved) {
                lastPrice.put(market.id(), price);
                lastPriceAnnouncedMs.put(market.id(), nowMs);
            }
            if (windowsMoved || priceMoved) {
                hub.changed(PushTopics.market(market.id()));
                any = true;
            }
        }
        if (any) {
            hub.changed(PushTopics.MARKETS);
        }
    }

    private String price(Market market, long nowMs) {
        return game.latestQuote(market, nowMs)
                .map(q -> q.raw() + "@" + q.asOfMs() + (q.stale() ? "s" : "") + q.venue())
                .orElse("");
    }

    private String windows(Market market, long nowMs) {
        StringBuilder sb = new StringBuilder();
        for (Interval interval : Interval.values()) {
            Optional<RoundWindow> running = progress.findNext(market.id(), interval);
            sb.append('|');
            running.ifPresent(w -> sb.append(w.startMs()).append(nowMs >= w.endMs() ? "e" : ""));
        }
        return sb.toString();
    }
}
