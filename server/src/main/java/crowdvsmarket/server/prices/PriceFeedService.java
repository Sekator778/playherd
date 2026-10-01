package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Polls the price sources on their schedules and hands every answer to {@link TickStore}
 * (docs/research/markets_architecture.md §3.2 rule 1): Hyperliquid's default dex every 3 s and dex
 * {@code xyz} every 6 s (together 600 of the 1200 weight units a minute the IP is allowed), the
 * Polymarket midpoints every 3 s, the synthetic walk every second. Every job runs with
 * {@code concurrentExecution = SKIP}: a poll that hangs on a slow source is not stacked up behind
 * itself, the next ones are skipped until it returns (the HTTP timeouts are 3 s connect, 5 s read).
 * The periods are the defaults of {@code herd.prices.hyperliquid.poll-every},
 * {@code herd.prices.hyperliquid.xyz-poll-every}, {@code herd.prices.polymarket.poll-every} and
 * {@code herd.prices.synthetic.poll-every}.
 *
 * <p>Everything here happens only with {@code herd.mode=live}: the jobs of the real sources with
 * {@code herd.prices.source=live}, the synthetic job with {@code synthetic}; in mode replay each
 * job returns at once. A poll polls the <i>active</i> markets of its feed; a failed poll is logged
 * (once per minute while it keeps failing) and costs one tick — {@link LiveMarketPrices} turns the
 * missing ticks into a stale flag and, for a round window, into a hole to backfill.
 */
@ApplicationScoped
public class PriceFeedService {

    private static final Logger LOG = Logger.getLogger(PriceFeedService.class);

    static final String HYPERLIQUID_EVERY = "${herd.prices.hyperliquid.poll-every:3s}";
    static final String HYPERLIQUID_XYZ_EVERY = "${herd.prices.hyperliquid.xyz-poll-every:6s}";
    static final String POLYMARKET_EVERY = "${herd.prices.polymarket.poll-every:3s}";
    static final String SYNTHETIC_EVERY = "${herd.prices.synthetic.poll-every:1s}";
    static final String PRUNE_EVERY = "10m";

    static final String JOB_HYPERLIQUID = "price-feed-hyperliquid";
    static final String JOB_HYPERLIQUID_XYZ = "price-feed-hyperliquid-xyz";
    static final String JOB_POLYMARKET = "price-feed-polymarket";
    static final String JOB_SYNTHETIC = "price-feed-synthetic";
    static final String JOB_PRUNE = "price-feed-prune";

    private final PriceSources sources;
    private final TickStore store;
    private final MarketRegistry registry;
    private final PricesConfig config;
    private final LongSupplier clock;
    private final ThrottledLog failures;

    @Inject
    public PriceFeedService(PriceSources sources, TickStore store, MarketRegistry registry, PricesConfig config) {
        this(sources, store, registry, config, System::currentTimeMillis);
    }

    PriceFeedService(PriceSources sources, TickStore store, MarketRegistry registry, PricesConfig config,
                     LongSupplier clock) {
        this.sources = sources;
        this.store = store;
        this.registry = registry;
        this.config = config;
        this.clock = clock;
        this.failures = new ThrottledLog(LOG, clock, 60_000L);
    }

    /** Rebuilds the tick buffer from the snapshot table before the first poll (live mode only). */
    void onStart(@Observes StartupEvent event) {
        if (config.pipelineActive()) {
            try {
                store.restore();
            } catch (RuntimeException e) {
                LOG.warnf("could not restore the price buffer, starting empty: %s", e.toString());
            }
        }
    }

    @Scheduled(every = HYPERLIQUID_EVERY, concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_HYPERLIQUID)
    void pollHyperliquid() {
        if (config.liveSources()) {
            pollFeed(HyperliquidPriceSource.FEED_MAIN);
        }
    }

    @Scheduled(every = HYPERLIQUID_XYZ_EVERY, concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_HYPERLIQUID_XYZ)
    void pollHyperliquidXyz() {
        if (config.liveSources()) {
            pollFeed(HyperliquidPriceSource.FEED_XYZ);
        }
    }

    @Scheduled(every = POLYMARKET_EVERY, concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_POLYMARKET)
    void pollPolymarket() {
        if (config.liveSources()) {
            pollFeed(PolymarketPriceSource.FEED);
        }
    }

    @Scheduled(every = SYNTHETIC_EVERY, concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB_SYNTHETIC)
    void pollSynthetic() {
        if (config.syntheticSource()) {
            pollFeed(SyntheticPriceSource.FEED);
        }
    }

    /** Every 10 minutes: drops the ticks and snapshot rows older than the retention. */
    @Scheduled(every = PRUNE_EVERY, concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = JOB_PRUNE)
    void prune() {
        if (config.pipelineActive()) {
            store.prune();
        }
    }

    /** One poll of one feed: reads its markets' prices and stores them as one snapshot. */
    void pollFeed(String feedId) {
        PriceSources.Binding binding = sources.bindingById(feedId).orElse(null);
        if (binding == null) {
            return;
        }
        List<Market> markets = registry.active().stream().filter(m -> binding.feed().handles().test(m)).toList();
        if (markets.isEmpty()) {
            return;
        }
        String key = "poll " + feedId;
        Map<String, PriceSource.Quote> quotes;
        try {
            quotes = binding.source().poll(binding.feed(), markets);
        } catch (Exception e) {
            failures.failure(key, "poll of " + feedId + " failed", e);
            return;
        }
        failures.success(key, "poll of " + feedId);
        long arrivedAt = clock.getAsLong();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (Market market : markets) {
            PriceSource.Quote quote = quotes.get(market.id());
            if (quote == null) {
                continue;
            }
            if (!fitsTheEngine(market, quote.raw())) {
                failures.failure("range " + market.id(), "dropped the price " + quote.raw() + " of " + market.id()
                        + " from " + feedId + ": not a price the engine can hold at lot exponent " + market.lotExp(),
                        null);
                continue;
            }
            prices.put(market.id(), quote.raw());
            store.setChange24h(market.id(), quote.change24h());
        }
        store.append(feedId, arrivedAt, prices);
    }

    /** A raw price must give a positive lot price at the market's fixed exponent (an engine {@code Candle} needs {@code low > 0}). */
    static boolean fitsTheEngine(Market market, BigDecimal raw) {
        return raw != null && raw.signum() > 0 && market.toEngine(raw).signum() > 0;
    }
}
