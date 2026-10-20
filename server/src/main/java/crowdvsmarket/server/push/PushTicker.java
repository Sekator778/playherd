package crowdvsmarket.server.push;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The part of the "changed" notices that no event announces: a market's price moved, or its round windows moved on with the clock
 * (a betting round starts running, the running one ends). Four times a second it compares a small signature of every running
 * market - the latest quote and the state of the round cursors against the clock - with the last one and publishes
 * {@code market:<id>} (and {@code markets}) when it differs. It reads memory only (the quote ring, the round cursors), takes no
 * lock, and does nothing at all while nobody listens to a market topic.
 *
 * <p>Bets, settlements and cancellations are published where they are committed ({@code GameService}).
 */
@ApplicationScoped
public class PushTicker {

    private static final Logger LOG = Logger.getLogger(PushTicker.class);

    @Inject
    PushHub hub;
    @Inject
    GameService game;
    @Inject
    RoundProgress progress;

    private final Map<String, String> last = new HashMap<>();

    @Scheduled(every = "0.25s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "push-ticker")
    void tick() {
        try {
            scan(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.debugf("push ticker: %s", e.toString());
        }
    }

    void scan(long nowMs) {
        if (!hub.enabled() || !(hub.hasSubscribers(PushTopics.MARKETS) || hub.hasSubscribers(PushTopics.MARKET_PREFIX))) {
            last.clear(); // nobody is looking: the next listener starts from a fresh read, not from a stale signature
            return;
        }
        boolean any = false;
        for (Market market : game.runningMarkets()) {
            String signature = signature(market, nowMs);
            if (!signature.equals(last.put(market.id(), signature))) {
                hub.changed(PushTopics.market(market.id()));
                any = true;
            }
        }
        if (any) {
            hub.changed(PushTopics.MARKETS);
        }
    }

    private String signature(Market market, long nowMs) {
        StringBuilder sb = new StringBuilder();
        Optional<PriceQuote> quote = game.latestQuote(market, nowMs);
        quote.ifPresent(q -> sb.append(q.raw()).append('@').append(q.asOfMs()).append(q.stale() ? "s" : "").append(q.venue()));
        for (Interval interval : Interval.values()) {
            Optional<RoundWindow> running = progress.findNext(market.id(), interval);
            sb.append('|');
            running.ifPresent(w -> sb.append(w.startMs()).append(nowMs >= w.endMs() ? "e" : ""));
        }
        return sb.toString();
    }
}
