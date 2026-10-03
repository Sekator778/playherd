package crowdvsmarket.server.prices;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the {@code herd.prices.coinbase.*}, {@code herd.prices.kraken.*}, {@code herd.prices.hyperliquid.ws-url} and
 * {@code spot-*}, {@code herd.prices.stream.*} keys (each with its default, see {@link SpotPricesConfig}) into the one
 * {@link SpotPricesConfig} bean — like {@link PricesConfigProducer}, so that every class of the pipeline can be built
 * by hand in a plain unit test.
 */
@ApplicationScoped
public class SpotPricesConfigProducer {

    @Produces
    @Singleton
    @Startup // read and validated at start-up: a bad value stops the server, not the first poll
    SpotPricesConfig spotPricesConfig(
            @ConfigProperty(name = "herd.prices.coinbase.url", defaultValue = SpotPricesConfig.DEFAULT_COINBASE_URL)
            String coinbaseUrl,
            @ConfigProperty(name = "herd.prices.coinbase.ws-url",
                    defaultValue = SpotPricesConfig.DEFAULT_COINBASE_WS_URL) String coinbaseWsUrl,
            @ConfigProperty(name = "herd.prices.hyperliquid.ws-url",
                    defaultValue = SpotPricesConfig.DEFAULT_HYPERLIQUID_WS_URL) String hyperliquidWsUrl,
            @ConfigProperty(name = "herd.prices.coinbase.stale-ms", defaultValue = SpotPricesConfig.DEFAULT_STALE_MS)
            long coinbaseStaleMs,
            @ConfigProperty(name = "herd.prices.coinbase.max-gap-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_GAP_MS) long coinbaseMaxGapMs,
            @ConfigProperty(name = "herd.prices.coinbase.max-basis",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_BASIS) double coinbaseMaxBasis,
            @ConfigProperty(name = "herd.prices.coinbase.fresh-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_COINBASE_FRESH_MS) long coinbaseFreshMs,
            @ConfigProperty(name = "herd.prices.coinbase.silence-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_COINBASE_SILENCE_MS) long coinbaseSilenceMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.spot-stale-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_STALE_MS) long spotStaleMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.spot-max-gap-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_GAP_MS) long spotMaxGapMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.spot-max-basis",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_BASIS) double spotMaxBasis,
            @ConfigProperty(name = "herd.prices.hyperliquid.spot-fresh-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_HYPERLIQUID_SPOT_FRESH_MS) long spotFreshMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.spot-silence-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_HYPERLIQUID_SPOT_SILENCE_MS) long spotSilenceMs,
            @ConfigProperty(name = "herd.prices.stream.reconnect-max-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_RECONNECT_MAX_MS) long reconnectMaxMs,
            @ConfigProperty(name = "herd.prices.stream.idle-ms", defaultValue = SpotPricesConfig.DEFAULT_IDLE_MS)
            long idleMs,
            @ConfigProperty(name = "herd.prices.kraken.url", defaultValue = SpotPricesConfig.DEFAULT_KRAKEN_URL)
            String krakenUrl,
            @ConfigProperty(name = "herd.prices.kraken.ws-url", defaultValue = SpotPricesConfig.DEFAULT_KRAKEN_WS_URL)
            String krakenWsUrl,
            @ConfigProperty(name = "herd.prices.kraken.stale-ms", defaultValue = SpotPricesConfig.DEFAULT_STALE_MS)
            long krakenStaleMs,
            @ConfigProperty(name = "herd.prices.kraken.max-gap-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_GAP_MS) long krakenMaxGapMs,
            @ConfigProperty(name = "herd.prices.kraken.max-basis",
                    defaultValue = SpotPricesConfig.DEFAULT_MAX_BASIS) double krakenMaxBasis,
            @ConfigProperty(name = "herd.prices.kraken.fresh-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_KRAKEN_FRESH_MS) long krakenFreshMs,
            @ConfigProperty(name = "herd.prices.kraken.silence-ms",
                    defaultValue = SpotPricesConfig.DEFAULT_KRAKEN_SILENCE_MS) long krakenSilenceMs) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl,
                new SpotPricesConfig.Limits(coinbaseStaleMs, coinbaseMaxGapMs, coinbaseMaxBasis, coinbaseFreshMs,
                        coinbaseSilenceMs),
                new SpotPricesConfig.Limits(spotStaleMs, spotMaxGapMs, spotMaxBasis, spotFreshMs, spotSilenceMs),
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl,
                new SpotPricesConfig.Limits(krakenStaleMs, krakenMaxGapMs, krakenMaxBasis, krakenFreshMs,
                        krakenSilenceMs));
    }
}
