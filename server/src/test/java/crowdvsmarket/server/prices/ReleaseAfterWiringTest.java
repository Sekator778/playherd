package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The waits of Р-79 inside the running application: each {@code herd.prices.<feed>.release-after-ms} key is read, through the
 * container and the producer, into the wait of its own source — a profile gives every key a value of its own, so a key read into
 * another source's wait, or not read at all, shows. (The environment variable of a key is its upper-cased name with dots and dashes
 * turned into underscores, as for every {@code herd.prices.*} key; what compose passes is listed in the compose files.)
 */
@QuarkusTest
@TestProfile(ReleaseAfterWiringTest.Waits.class)
class ReleaseAfterWiringTest {

    public static class Waits implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.prices.hyperliquid.release-after-ms", "1000",
                    "herd.prices.hyperliquid.spot-release-after-ms", "2000",
                    "herd.prices.coinbase.release-after-ms", "3000",
                    "herd.prices.kraken.release-after-ms", "4000",
                    "herd.prices.polymarket.release-after-ms", "5000",
                    "herd.prices.synthetic.release-after-ms", "0");
        }
    }

    @Inject
    PricesConfig prices;

    @Test
    void eachKeyIsReadIntoTheWaitOfItsOwnSource() {
        assertThat(prices.releaseAfter()).isEqualTo(new PricesConfig.ReleaseAfter(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 0L));
        assertThat(prices.releaseAfter().forFeed("hyperliquid:xyz")).as("both dexes of Hyperliquid").isEqualTo(1_000L);
        assertThat(prices.releaseAfter().forFeed("synthetic")).as("0 switches a source off").isZero();
    }

    @Test
    void theKeyOfTheVenueReleaseIsAnotherOneAndKeepsItsDefault() {
        assertThat(prices.venueReleaseAfterMs()).isEqualTo(120_000L);
    }
}
