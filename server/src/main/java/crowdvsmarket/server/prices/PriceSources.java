package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The price sources that are switched on by {@code herd.prices.source}: {@code live} activates the Hyperliquid
 * (perpetuals and spot), Coinbase and Polymarket sources, {@code synthetic} only the synthetic walk. Nothing of the
 * other group is touched — their beans are never even instantiated, so no REST client is built and no WebSocket
 * is opened.
 */
@ApplicationScoped
@Unremovable
public class PriceSources {

    /** A source together with one of its feeds. */
    public record Binding(PriceSource source, PriceSource.Feed feed) {
    }

    private final List<PriceSource> active;
    private final List<Binding> bindings;

    @Inject
    public PriceSources(Instance<PriceSource> all, PricesConfig config) {
        this(select(all, config));
    }

    /** The given sources, whatever the configuration — for tests. */
    PriceSources(List<PriceSource> active) {
        this.active = List.copyOf(active);
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
            // The bean class tells which group a source is in without instantiating it.
            boolean isSynthetic = SyntheticPriceSource.class.isAssignableFrom(handle.getBean().getBeanClass());
            if (isSynthetic == synthetic) {
                out.add(handle.get());
            }
        }
        return out;
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
}
