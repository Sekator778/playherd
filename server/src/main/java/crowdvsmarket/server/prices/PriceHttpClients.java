package crowdvsmarket.server.prices;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Closeable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

/**
 * The REST clients of the price pipeline and the catalogs (Hyperliquid, Coinbase, Kraken, Polymarket), built on first use with
 * the base URL of their {@code herd.prices.*} key and the pipeline's timeouts — connect 3 s, read 5 s — because
 * {@link LiveMarketPrices#resolve} blocks on them. Built here rather than injected with
 * {@code @RestClient} because the timeouts of an injected client come from
 * {@code quarkus.rest-client.*} keys in {@code application.properties}, which this pipeline leaves
 * alone; the interfaces still declare their default base URI with {@code @RegisterRestClient}.
 *
 * <p>Lazy on purpose: with {@code herd.prices.source=synthetic} nothing here is ever asked, so no
 * client — and no connection — exists.
 */
@ApplicationScoped
public class PriceHttpClients {

    private static final Logger LOG = Logger.getLogger(PriceHttpClients.class);

    /** A client created by its first user, once. */
    private final class Lazy<T> implements Supplier<T> {

        private final Class<T> api;
        private final Supplier<String> baseUrl;
        private T client;

        Lazy(Class<T> api, Supplier<String> baseUrl) {
            this.api = api;
            this.baseUrl = baseUrl;
        }

        @Override
        public synchronized T get() {
            if (client == null) {
                client = build(api, baseUrl.get(), config);
                synchronized (PriceHttpClients.this) {
                    built.add(client);
                }
            }
            return client;
        }
    }

    private final PricesConfig config;
    private final List<Object> built = new ArrayList<>();
    private final Lazy<HyperliquidInfoApi> hyperliquid;
    private final Lazy<CoinbaseExchangeApi> coinbase;
    private final Lazy<KrakenApi> kraken;
    private final Lazy<PolymarketClobApi> clob;
    private final Lazy<PolymarketDataApi> data;
    private final Lazy<PolymarketGammaApi> gamma;

    @Inject
    public PriceHttpClients(PricesConfig config, SpotPricesConfig spot) {
        this.config = config;
        this.hyperliquid = new Lazy<>(HyperliquidInfoApi.class, config::hyperliquidUrl);
        this.coinbase = new Lazy<>(CoinbaseExchangeApi.class, spot::coinbaseUrl);
        this.kraken = new Lazy<>(KrakenApi.class, spot::krakenUrl);
        this.clob = new Lazy<>(PolymarketClobApi.class, config::polymarketClobUrl);
        this.data = new Lazy<>(PolymarketDataApi.class, config::polymarketDataUrl);
        this.gamma = new Lazy<>(PolymarketGammaApi.class, config::polymarketGammaUrl);
    }

    /** The clients with the default Coinbase address. */
    public PriceHttpClients(PricesConfig config) {
        this(config, SpotPricesConfig.defaults());
    }

    /** Each accessor returns a supplier that builds the client on its first {@code get()}. */
    public Supplier<HyperliquidInfoApi> hyperliquid() {
        return hyperliquid;
    }

    public Supplier<CoinbaseExchangeApi> coinbase() {
        return coinbase;
    }

    public Supplier<KrakenApi> kraken() {
        return kraken;
    }

    public Supplier<PolymarketClobApi> clob() {
        return clob;
    }

    public Supplier<PolymarketDataApi> data() {
        return data;
    }

    public Supplier<PolymarketGammaApi> gamma() {
        return gamma;
    }

    /** A client of {@code api} for {@code baseUrl} with the pipeline's connect and read timeouts. */
    public static <T> T build(Class<T> api, String baseUrl, PricesConfig config) {
        return build(api, baseUrl, config.connectTimeoutMs(), config.readTimeoutMs());
    }

    public static <T> T build(Class<T> api, String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build(api);
    }

    /** How many clients exist so far: none until something polls a real source. */
    public int builtCount() {
        synchronized (this) {
            return built.size();
        }
    }

    @PreDestroy
    void close() {
        synchronized (this) {
            for (Object client : built) {
                if (client instanceof Closeable c) {
                    try {
                        c.close();
                    } catch (Exception e) {
                        LOG.debugf("closing a REST client failed: %s", e.toString());
                    }
                }
            }
            built.clear();
        }
    }
}
