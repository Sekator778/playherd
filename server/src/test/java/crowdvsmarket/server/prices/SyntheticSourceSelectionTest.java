package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code herd.prices.source=synthetic} selects the synthetic walk and nothing else — through the CDI
 * container, with the real {@code Instance<PriceSource>} lookup. The mode stays {@code replay}, so
 * nothing polls and nothing can reach the network: this is only about which sources are active.
 */
@QuarkusTest
@TestProfile(SyntheticSourceSelectionTest.SyntheticSource.class)
class SyntheticSourceSelectionTest {

    public static class SyntheticSource implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.prices.source", "synthetic");
        }
    }

    @Inject
    PricesConfig prices;
    @Inject
    PriceSources sources;
    @Inject
    PriceHttpClients clients;

    @Test
    void theSourceSwitchIsReadFromTheConfiguration() {
        assertThat(prices.source()).isEqualTo(PricesConfig.SourceKind.SYNTHETIC);
        assertThat(prices.liveMode()).isFalse(); // still replay: the walk is armed, not running
        assertThat(prices.syntheticSource()).isFalse();
    }

    @Test
    void onlyTheSyntheticSourceIsActiveAndItsFeedPricesEveryMarket() {
        assertThat(sources.active()).extracting(PriceSource::name).containsExactly("synthetic");
        assertThat(sources.bindings()).extracting(b -> b.feed().id()).containsExactly("synthetic");
        assertThat(sources.bindingFor(TestMarkets.btc())).isPresent();
        assertThat(sources.bindingFor(TestMarkets.odds("PM1", "t"))).isPresent();
    }

    @Test
    void noRestClientIsBuiltForTheSyntheticWalk() {
        assertThat(clients.builtCount()).isZero();
    }
}
