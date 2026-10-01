package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.prices.PricesConfig;
import java.util.List;
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

        assertThat(c.crypto()).containsExactly("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE");
        assertThat(c.stocks()).containsExactly("xyz:NVDA", "xyz:TSLA");
        assertThat(c.indices()).containsExactly("xyz:SP500", "xyz:XYZ100");
        assertThat(c.commodities()).containsExactly("xyz:GOLD", "xyz:BRENTOIL");
        assertThat(c.polymarketEnabled()).isTrue();
        assertThat(c.politicsCount()).isEqualTo(3);
        assertThat(c.sportsCount()).isEqualTo(3);
        assertThat(c.oddsMaxLeverage()).isEqualTo(10);
        assertThat(c.inPlayFirst()).isTrue();
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

        CatalogConfig real = producer.catalogConfig(live, "BTC,ETH", "xyz:NVDA", "", "", false, 2, 1, 5, false);
        assertThat(real.liveSources()).isTrue();
        assertThat(real.syntheticSource()).isFalse();
        assertThat(real.crypto()).containsExactly("BTC", "ETH");
        assertThat(real.indices()).isEmpty();
        assertThat(real.polymarketEnabled()).isFalse();
        assertThat(real.politicsCount()).isEqualTo(2);
        assertThat(real.sportsCount()).isEqualTo(1);
        assertThat(real.oddsMaxLeverage()).isEqualTo(5);
        assertThat(real.inPlayFirst()).isFalse();

        CatalogConfig synthetic = producer.catalogConfig(live.withMode(true, PricesConfig.SourceKind.SYNTHETIC), "BTC", "",
                "", "", true, 3, 3, 10, true);
        assertThat(synthetic.liveSources()).isFalse();
        assertThat(synthetic.syntheticSource()).isTrue();

        CatalogConfig replay = producer.catalogConfig(live.withMode(false, PricesConfig.SourceKind.LIVE), "BTC", "", "", "",
                true, 3, 3, 10, true);
        assertThat(replay.liveSources()).isFalse();
        assertThat(replay.syntheticSource()).isFalse();
    }
}
