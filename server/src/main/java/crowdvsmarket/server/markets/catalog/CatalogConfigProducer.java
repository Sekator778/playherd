package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.prices.PricesConfig;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Optional;
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
            // Optional: SmallRye takes an empty value for none, and an empty default is no default for a String — the
            // default of this list is empty (no Hyperliquid perpetual: the crypto markets have their own venues)
            @ConfigProperty(name = "herd.markets.crypto") Optional<String> crypto,
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
                    defaultValue = CatalogConfig.DEFAULT_IN_PLAY_FIRST) boolean inPlayFirst,
            @ConfigProperty(name = "herd.markets.polymarket.game-cooldown",
                    defaultValue = CatalogConfig.DEFAULT_GAME_COOLDOWN) String gameCooldown,
            @ConfigProperty(name = "herd.markets.coinbase", defaultValue = CatalogConfig.DEFAULT_COINBASE)
            String coinbase,
            @ConfigProperty(name = "herd.markets.hyperliquid-spot",
                    defaultValue = CatalogConfig.DEFAULT_HYPERLIQUID_SPOT) String hyperliquidSpot,
            @ConfigProperty(name = "herd.markets.spot.max-leverage",
                    defaultValue = CatalogConfig.DEFAULT_SPOT_MAX_LEVERAGE) String spotMaxLeverage,
            @ConfigProperty(name = "herd.markets.spot.backup", defaultValue = CatalogConfig.DEFAULT_SPOT_BACKUP)
            String spotBackup) {
        return new CatalogConfig(prices.liveSources(), prices.syntheticSource(),
                CatalogConfig.parseNames(crypto.orElse(CatalogConfig.DEFAULT_CRYPTO)), CatalogConfig.parseNames(stocks),
                CatalogConfig.parseNames(indices), CatalogConfig.parseNames(commodities), polymarketEnabled,
                politicsCount, sportsCount, oddsMaxLeverage, inPlayFirst, CatalogConfig.parseDurationMs(gameCooldown),
                CatalogConfig.parseNames(coinbase), CatalogConfig.parseNames(hyperliquidSpot),
                CatalogConfig.parseLeverages(spotMaxLeverage), CatalogConfig.parseBackups(spotBackup),
                prices.disabledVenues());
    }
}
