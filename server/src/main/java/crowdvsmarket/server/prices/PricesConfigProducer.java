package crowdvsmarket.server.prices;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the {@code herd.mode} / {@code herd.prices.*} keys (each with its default, see
 * {@link PricesConfig}) into the one {@link PricesConfig} bean. A producer instead of fields on the
 * consumers so that every class of the pipeline can be built by hand in a plain unit test with
 * {@link PricesConfig#defaults()}.
 */
@ApplicationScoped
public class PricesConfigProducer {

    @Produces
    @Singleton
    @Startup // read and validated at start-up: a bad herd.prices.source stops the server, not the first poll
    PricesConfig pricesConfig(
            @ConfigProperty(name = "herd.mode", defaultValue = PricesConfig.DEFAULT_MODE) String mode,
            @ConfigProperty(name = "herd.prices.source", defaultValue = PricesConfig.DEFAULT_SOURCE) String source,
            @ConfigProperty(name = "herd.round-durations.daily-sec", defaultValue = PricesConfig.DEFAULT_DAILY_SEC)
            long dailySec,
            @ConfigProperty(name = "herd.prices.hyperliquid.url", defaultValue = PricesConfig.DEFAULT_HYPERLIQUID_URL)
            String hyperliquidUrl,
            @ConfigProperty(name = "herd.prices.polymarket.clob-url",
                    defaultValue = PricesConfig.DEFAULT_POLYMARKET_CLOB_URL) String clobUrl,
            @ConfigProperty(name = "herd.prices.polymarket.gamma-url",
                    defaultValue = PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL) String gammaUrl,
            @ConfigProperty(name = "herd.prices.polymarket.data-url",
                    defaultValue = PricesConfig.DEFAULT_POLYMARKET_DATA_URL) String dataUrl,
            @ConfigProperty(name = "herd.prices.hyperliquid.stale-ms",
                    defaultValue = PricesConfig.DEFAULT_HYPERLIQUID_STALE_MS) long hyperliquidStaleMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.xyz-stale-ms",
                    defaultValue = PricesConfig.DEFAULT_HYPERLIQUID_XYZ_STALE_MS) long hyperliquidXyzStaleMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.max-gap-ms",
                    defaultValue = PricesConfig.DEFAULT_HYPERLIQUID_MAX_GAP_MS) long hyperliquidMaxGapMs,
            @ConfigProperty(name = "herd.prices.polymarket.stale-ms",
                    defaultValue = PricesConfig.DEFAULT_POLYMARKET_STALE_MS) long polymarketStaleMs,
            @ConfigProperty(name = "herd.prices.polymarket.max-gap-ms",
                    defaultValue = PricesConfig.DEFAULT_POLYMARKET_MAX_GAP_MS) long polymarketMaxGapMs,
            @ConfigProperty(name = "herd.prices.synthetic.stale-ms",
                    defaultValue = PricesConfig.DEFAULT_SYNTHETIC_STALE_MS) long syntheticStaleMs,
            @ConfigProperty(name = "herd.prices.synthetic.max-gap-ms",
                    defaultValue = PricesConfig.DEFAULT_SYNTHETIC_MAX_GAP_MS) long syntheticMaxGapMs,
            @ConfigProperty(name = "herd.prices.backfill-cooldown-ms",
                    defaultValue = PricesConfig.DEFAULT_BACKFILL_COOLDOWN_MS) long backfillCooldownMs,
            @ConfigProperty(name = "herd.prices.connect-timeout-ms",
                    defaultValue = PricesConfig.DEFAULT_CONNECT_TIMEOUT_MS) int connectTimeoutMs,
            @ConfigProperty(name = "herd.prices.read-timeout-ms",
                    defaultValue = PricesConfig.DEFAULT_READ_TIMEOUT_MS) int readTimeoutMs,
            @ConfigProperty(name = "herd.prices.quiet-carry-max-ms",
                    defaultValue = PricesConfig.DEFAULT_QUIET_CARRY_MAX_MS) long quietCarryMaxMs,
            @ConfigProperty(name = "herd.prices.hyperliquid.seed-budget-per-minute",
                    defaultValue = PricesConfig.DEFAULT_HYPERLIQUID_SEED_BUDGET) int seedBudgetPerMinute,
            @ConfigProperty(name = "herd.prices.require-minute-aligned-rounds",
                    defaultValue = PricesConfig.DEFAULT_REQUIRE_ALIGNED_ROUNDS) boolean requireMinuteAlignedRounds) {
        return new PricesConfig(PricesConfig.isLiveMode(mode), PricesConfig.SourceKind.parse(source),
                dailySec * 1000L, hyperliquidUrl, clobUrl, gammaUrl, dataUrl, hyperliquidStaleMs,
                hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                quietCarryMaxMs, seedBudgetPerMinute, requireMinuteAlignedRounds);
    }
}
