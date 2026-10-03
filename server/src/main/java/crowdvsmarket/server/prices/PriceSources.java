package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The price sources that are switched on by {@code herd.prices.source}: {@code live} activates the Hyperliquid
 * (perpetuals and spot), Coinbase, Kraken and Polymarket sources, {@code synthetic} only the synthetic walk. Nothing of
 * the other group is touched — their beans are never even instantiated, so no REST client is built and no WebSocket
 * is opened. The same goes for a venue named in {@code herd.prices.disabled-venues}: its sources are not instantiated, so it
 * gives no price over any channel (no stream, no REST), and its feeds are listed as {@code disabled} on the operator's
 * status page.
 */
@ApplicationScoped
@Unremovable
public class PriceSources {

    /** A source together with one of its feeds. */
    public record Binding(PriceSource source, PriceSource.Feed feed) {
    }

    /** The feeds of each venue, for the status page of a venue that is switched off (its sources are not instantiated). */
    private static final Map<String, List<String>> FEEDS_OF = Map.of(
            "hyperliquid", List.of(HyperliquidPriceSource.FEED_MAIN, HyperliquidPriceSource.FEED_XYZ,
                    HyperliquidSpotPriceSource.FEED),
            "coinbase", List.of(CoinbasePriceSource.FEED),
            "kraken", List.of(KrakenPriceSource.FEED),
            "polymarket", List.of(PolymarketPriceSource.FEED));

    private final List<PriceSource> active;
    private final List<Binding> bindings;
    private final Set<String> disabledVenues;

    @Inject
    public PriceSources(Instance<PriceSource> all, PricesConfig config) {
        this(select(all, config), config.disabledVenues());
    }

    /** The given sources, whatever the configuration — for tests. */
    PriceSources(List<PriceSource> active) {
        this(active, Set.of());
    }

    PriceSources(List<PriceSource> active, Set<String> disabledVenues) {
        this.active = List.copyOf(active);
        this.disabledVenues = Set.copyOf(disabledVenues);
        List<Binding> all = new ArrayList<>();
        for (PriceSource source : this.active) {
            for (PriceSource.Feed feed : source.feeds()) {
                all.add(new Binding(source, feed));
            }
        }
        this.bindings = List.copyOf(all);
    }

    private static List<PriceSource> select(Instance<PriceSource> all, PricesConfig config) {
        boolean synthetic = config.source() == PricesConfig.SourceKind.SYNTHETIC;
        List<PriceSource> out = new ArrayList<>();
        for (Instance.Handle<PriceSource> handle : all.handles()) {
            // The bean class tells which group a source is in, and which venue, without instantiating it.
            Class<?> bean = handle.getBean().getBeanClass();
            boolean isSynthetic = SyntheticPriceSource.class.isAssignableFrom(bean);
            if (isSynthetic != synthetic) {
                continue;
            }
            if (!isSynthetic && config.venueDisabled(venueOf(bean))) {
                continue;
            }
            out.add(handle.get());
        }
        return out;
    }

    /** The venue a source class prices from ({@code PriceSource#name}), without an instance of it; empty for another class. */
    static String venueOf(Class<?> source) {
        if (HyperliquidPriceSource.class.isAssignableFrom(source) || HyperliquidSpotPriceSource.class.isAssignableFrom(source)) {
            return HyperliquidPriceSource.NAME;
        }
        if (CoinbasePriceSource.class.isAssignableFrom(source)) {
            return CoinbasePriceSource.NAME;
        }
        if (KrakenPriceSource.class.isAssignableFrom(source)) {
            return KrakenPriceSource.NAME;
        }
        if (PolymarketPriceSource.class.isAssignableFrom(source)) {
            return PolymarketPriceSource.NAME;
        }
        return "";
    }

    public List<PriceSource> active() {
        return active;
    }

    /** Every active feed with its source, in a fixed order. */
    public List<Binding> bindings() {
        return bindings;
    }

    /** The first active feed that handles the market. */
    public Optional<Binding> bindingFor(Market market) {
        return bindings.stream().filter(b -> b.feed().handles().test(market)).findFirst();
    }

    public Optional<Binding> bindingById(String feedId) {
        return bindings.stream().filter(b -> b.feed().id().equals(feedId)).findFirst();
    }

    /** The venues that give no price ({@code herd.prices.disabled-venues}). */
    public Set<String> disabledVenues() {
        return disabledVenues;
    }

    /**
     * How every feed is doing, for the operator's status page: the active ones as their source says, and the feeds of a
     * disabled venue as {@code disabled}.
     */
    public List<PriceSource.FeedStatus> statuses(long nowMs) {
        List<PriceSource.FeedStatus> out = new ArrayList<>();
        for (Binding binding : bindings) {
            out.add(binding.source().status(binding.feed(), nowMs));
        }
        for (String venue : new TreeSet<>(disabledVenues)) {
            for (String feed : FEEDS_OF.getOrDefault(venue, List.of())) {
                boolean polled = feed.equals(HyperliquidPriceSource.FEED_MAIN) || feed.equals(HyperliquidPriceSource.FEED_XYZ)
                        || feed.equals(PolymarketPriceSource.FEED);
                out.add(new PriceSource.FeedStatus(feed, venue, polled ? "poll" : "stream", "disabled", null, 0));
            }
        }
        return out;
    }
}
