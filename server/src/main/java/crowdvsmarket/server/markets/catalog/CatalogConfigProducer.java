package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.prices.PricesConfig;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the {@code herd.markets.*} keys (each with its default, see {@link CatalogConfig}) into the
 * one {@link CatalogConfig} bean; whether the catalogs run at all follows {@link PricesConfig}
 * ({@code herd.mode} and {@code herd.prices.source}).
 */
@ApplicationScoped
public class CatalogConfigProducer {

    @Produces
    @Singleton
    @Startup // read at start-up, so that a bad value stops the server instead of the first catalog run
    CatalogConfig catalogConfig(
            PricesConfig prices,
            @ConfigProperty(name = "herd.markets.crypto", defaultValue = CatalogConfig.DEFAULT_CRYPTO) String crypto,
            @ConfigProperty(name = "herd.markets.stocks", defaultValue = CatalogConfig.DEFAULT_STOCKS) String stocks,
            @ConfigProperty(name = "herd.markets.indices", defaultValue = CatalogConfig.DEFAULT_INDICES) String indices,
            @ConfigProperty(name = "herd.markets.commodities", defaultValue = CatalogConfig.DEFAULT_COMMODITIES)
            String commodities,
            @ConfigProperty(name = "herd.markets.polymarket.enabled",
                    defaultValue = CatalogConfig.DEFAULT_POLYMARKET_ENABLED) boolean polymarketEnabled,
            @ConfigProperty(name = "herd.markets.politics.count",
                    defaultValue = CatalogConfig.DEFAULT_POLITICS_COUNT) int politicsCount,
            @ConfigProperty(name = "herd.markets.sports.count",
                    defaultValue = CatalogConfig.DEFAULT_SPORTS_COUNT) int sportsCount,
            @ConfigProperty(name = "herd.markets.odds.max-leverage",
                    defaultValue = CatalogConfig.DEFAULT_ODDS_MAX_LEVERAGE) int oddsMaxLeverage,
            @ConfigProperty(name = "herd.markets.polymarket.in-play-first",
                    defaultValue = CatalogConfig.DEFAULT_IN_PLAY_FIRST) boolean inPlayFirst) {
        return new CatalogConfig(prices.liveSources(), prices.syntheticSource(), CatalogConfig.parseNames(crypto),
                CatalogConfig.parseNames(stocks), CatalogConfig.parseNames(indices),
                CatalogConfig.parseNames(commodities), polymarketEnabled, politicsCount, sportsCount,
                oddsMaxLeverage, inPlayFirst);
    }
}
