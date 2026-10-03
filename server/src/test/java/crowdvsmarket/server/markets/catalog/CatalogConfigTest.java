package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.prices.PricesConfig;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The {@code herd.markets.*} settings: list parsing, category order, the producer's use of the mode and source. */
class CatalogConfigTest {

    @Test
    void namesAreTrimmedBlanksAndRepeatsDroppedAndTheOrderKept() {
        assertThat(CatalogConfig.parseNames(" BTC, ETH ,,xyz:NVDA, BTC ,")).containsExactly("BTC", "ETH", "xyz:NVDA");
        assertThat(CatalogConfig.parseNames("")).isEmpty();
        assertThat(CatalogConfig.parseNames(" , ")).isEmpty();
    }

    @Test
    void theDefaultsAreTheArchitecturesTable() {
        CatalogConfig c = CatalogConfig.defaults();

        assertThat(c.crypto()).as("no Hyperliquid perpetual: the crypto markets are priced by their own venues").isEmpty();
        assertThat(c.coinbase()).containsExactly("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD");
        assertThat(c.hyperliquidSpot()).containsExactly("HYPE");
        assertThat(c.spotMaxLeverage()).containsOnly(Map.entry("BTC", 40), Map.entry("ETH", 25), Map.entry("SOL", 20),
                Map.entry("XRP", 20), Map.entry("DOGE", 10), Map.entry("HYPE", 10)); // the caps of the perpetuals
        assertThat(c.stocks()).containsExactly("xyz:NVDA", "xyz:TSLA");
        assertThat(c.indices()).containsExactly("xyz:SP500", "xyz:XYZ100");
        assertThat(c.commodities()).containsExactly("xyz:GOLD", "xyz:BRENTOIL");
        assertThat(c.polymarketEnabled()).isTrue();
        assertThat(c.politicsCount()).isEqualTo(3);
        assertThat(c.sportsCount()).isEqualTo(3);
        assertThat(c.oddsMaxLeverage()).isEqualTo(10);
        assertThat(c.inPlayFirst()).isTrue();
        assertThat(c.gameCooldownMs()).isEqualTo(6 * 3_600_000L); // herd.markets.polymarket.game-cooldown: 6h
    }

    @Test
    void theConstructorOfBeforeTheGameCooldownKeepsWorkingWithItsDefault() {
        CatalogConfig old = new CatalogConfig(true, false, List.of("BTC"), List.of(), List.of(), List.of(), true, 3, 3, 10,
                true);

        assertThat(old.gameCooldownMs()).isEqualTo(21_600_000L);
    }

    @Test
    void aDurationIsReadInTheSpellingsOfQuarkus() {
        assertThat(CatalogConfig.parseDurationMs("6h")).isEqualTo(21_600_000L);
        assertThat(CatalogConfig.parseDurationMs(" 30M ")).isEqualTo(1_800_000L);
        assertThat(CatalogConfig.parseDurationMs("45s")).isEqualTo(45_000L);
        assertThat(CatalogConfig.parseDurationMs("500ms")).isEqualTo(500L);
        assertThat(CatalogConfig.parseDurationMs("1d")).isEqualTo(86_400_000L);
        assertThat(CatalogConfig.parseDurationMs("90")).isEqualTo(90_000L); // a bare number is seconds
        assertThat(CatalogConfig.parseDurationMs("0")).isZero(); // the rule switched off
        assertThat(CatalogConfig.parseDurationMs("PT6H")).isEqualTo(21_600_000L);
        assertThat(CatalogConfig.parseDurationMs("pt1h30m")).isEqualTo(5_400_000L);
        for (String bad : new String[] {"", "six hours", "h", "-1h", "6 h", "1.5h", "99999999999999d"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> CatalogConfig.parseDurationMs(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CatalogConfig.parseDurationMs(null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new CatalogConfig(true, false, List.of(), List.of(),
                        List.of(), List.of(), true, 3, 3, 10, true, -1L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("game-cooldown");
    }

    @Test
    void perpNamesListEachNameOnceInTheCategoryOfItsFirstList() {
        CatalogConfig c = new CatalogConfig(true, false, List.of("BTC", "ETH"), List.of("xyz:NVDA", "ETH"),
                List.of("xyz:SP500"), List.of("xyz:GOLD", "xyz:NVDA"), true, 3, 3, 10, true);

        assertThat(c.perpNames()).extracting(CatalogConfig.PerpName::name)
                .containsExactly("BTC", "ETH", "xyz:NVDA", "xyz:SP500", "xyz:GOLD");
        assertThat(c.perpNames()).extracting(CatalogConfig.PerpName::category).containsExactly(Category.CRYPTO,
                Category.CRYPTO, Category.STOCKS, Category.INDICES, Category.COMMODITIES);
    }

    @Test
    void oddsCountsExistOnlyForPoliticsAndSports() {
        CatalogConfig c = new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 4, 2, 10, true);

        assertThat(c.oddsCount(Category.POLITICS)).isEqualTo(4);
        assertThat(c.oddsCount(Category.SPORTS)).isEqualTo(2);
        assertThat(c.oddsCount(Category.CRYPTO)).isZero();
    }

    @Test
    void theProducerTakesWhetherTheCatalogsRunFromTheModeAndTheSource() {
        CatalogConfigProducer producer = new CatalogConfigProducer();
        PricesConfig live = PricesConfig.defaults();

        CatalogConfig real = producer.catalogConfig(live, Optional.of("BTC,ETH"), "xyz:NVDA", "", "", false, 2, 1, 5, false,
                "30m", "BTC-USD, ETH-USD", "HYPE", "BTC:40,ETH:25");
        assertThat(real.liveSources()).isTrue();
        assertThat(real.syntheticSource()).isFalse();
        assertThat(real.crypto()).containsExactly("BTC", "ETH");
        assertThat(real.coinbase()).containsExactly("BTC-USD", "ETH-USD");
        assertThat(real.hyperliquidSpot()).containsExactly("HYPE");
        assertThat(real.spotMaxLeverage()).containsOnly(Map.entry("BTC", 40), Map.entry("ETH", 25));
        assertThat(real.indices()).isEmpty();
        assertThat(real.polymarketEnabled()).isFalse();
        assertThat(real.politicsCount()).isEqualTo(2);
        assertThat(real.sportsCount()).isEqualTo(1);
        assertThat(real.oddsMaxLeverage()).isEqualTo(5);
        assertThat(real.inPlayFirst()).isFalse();
        assertThat(real.gameCooldownMs()).isEqualTo(1_800_000L);

        CatalogConfig synthetic = producer.catalogConfig(live.withMode(true, PricesConfig.SourceKind.SYNTHETIC),
                Optional.of("BTC"), "", "", "", true, 3, 3, 10, true, "6h", "BTC-USD", "HYPE", "BTC:40");
        assertThat(synthetic.liveSources()).isFalse();
        assertThat(synthetic.syntheticSource()).isTrue();

        CatalogConfig replay = producer.catalogConfig(live.withMode(false, PricesConfig.SourceKind.LIVE),
                Optional.of("BTC"), "", "", "", true, 3, 3, 10, true, "6h", "BTC-USD", "HYPE", "BTC:40");
        assertThat(replay.liveSources()).isFalse();
        assertThat(replay.syntheticSource()).isFalse();
    }

    @Test
    void noCryptoPerpetualIsTheDefaultWhetherTheKeyIsAbsentOrEmpty() {
        CatalogConfigProducer producer = new CatalogConfigProducer();

        // an unset key (SmallRye takes an empty value for unset, and so gives Optional.empty) names nothing
        CatalogConfig c = producer.catalogConfig(PricesConfig.defaults(), Optional.empty(), CatalogConfig.DEFAULT_STOCKS,
                CatalogConfig.DEFAULT_INDICES, CatalogConfig.DEFAULT_COMMODITIES, true, 3, 3, 10, true, "6h",
                CatalogConfig.DEFAULT_COINBASE, CatalogConfig.DEFAULT_HYPERLIQUID_SPOT,
                CatalogConfig.DEFAULT_SPOT_MAX_LEVERAGE);

        assertThat(c.crypto()).isEmpty();
        assertThat(c.perpNames()).extracting(CatalogConfig.PerpName::name)
                .containsExactly("xyz:NVDA", "xyz:TSLA", "xyz:SP500", "xyz:XYZ100", "xyz:GOLD", "xyz:BRENTOIL");
    }

    @Test
    void theLeverageTableIsParsedCappedAndHasAFallback() {
        assertThat(CatalogConfig.parseLeverages(" BTC:40, ETH : 25 ,,HYPE:10")).containsExactly(Map.entry("BTC", 40),
                Map.entry("ETH", 25), Map.entry("HYPE", 10)); // order kept
        assertThat(CatalogConfig.parseLeverages("")).isEmpty();
        for (String bad : new String[] {"BTC", "BTC:", ":40", "BTC:forty", "BTC:40:1", "BTC 40"}) {
            assertThatThrownBy(() -> CatalogConfig.parseLeverages(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("herd.markets.spot.max-leverage");
        }

        CatalogConfig c = new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10, true,
                21_600_000L, List.of("BTC-USD"), List.of("HYPE"), Map.of("BTC", 50, "ETH", 25));
        assertThat(c.spotLeverage("ETH")).isEqualTo(25);
        assertThat(c.spotLeverage("BTC")).as("never above 40, whatever the table says").isEqualTo(40);
        assertThat(c.spotLeverage("PEPE")).as("a symbol that is in no table").isEqualTo(10);
        assertThatThrownBy(() -> new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10,
                true, 0L, List.of(), List.of(), Map.of("BTC", 1))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2x");
    }

    @Test
    void theOfflineWalkKeepsItsSixCryptoMarketsWhateverTheDefaultListIs() {
        CatalogConfig defaults = CatalogConfig.defaults();

        assertThat(defaults.syntheticPerpNames()).extracting(CatalogConfig.PerpName::name)
                .containsExactly("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE", "xyz:NVDA", "xyz:TSLA", "xyz:SP500",
                        "xyz:XYZ100", "xyz:GOLD", "xyz:BRENTOIL");
        assertThat(defaults.syntheticPerpNames().subList(0, 6)).extracting(CatalogConfig.PerpName::category)
                .containsOnly(Category.CRYPTO);

        // names configured under crypto are what it lists, as ever
        CatalogConfig named = new CatalogConfig(false, true, List.of("BTC", "ETH", "DOGE"), List.of("BTC"), List.of("BTC"),
                List.of("BTC"), true, 1, 1, 10, true);
        assertThat(named.syntheticPerpNames()).isEqualTo(named.perpNames());
        assertThat(named.syntheticPerpNames()).extracting(CatalogConfig.PerpName::name).containsExactly("BTC", "ETH", "DOGE");
    }
}
