package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link PricesConfig} and its producer: the architecture's limits, the retention, the mode and source switches. */
class PricesConfigTest {

    private static PricesConfig produce(String mode, String source, long dailySec) {
        return new PricesConfigProducer().pricesConfig(mode, source, dailySec, PricesConfig.DEFAULT_HYPERLIQUID_URL,
                PricesConfig.DEFAULT_POLYMARKET_CLOB_URL, PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL,
                PricesConfig.DEFAULT_POLYMARKET_DATA_URL, 15_000, 20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000,
                3_000, 5_000);
    }

    @Test
    void theDefaultsAreTheLimitsOfTheArchitecture() {
        PricesConfig c = PricesConfig.defaults();

        assertThat(c.hyperliquidStaleMs()).isEqualTo(15_000);
        assertThat(c.hyperliquidXyzStaleMs()).isEqualTo(20_000);
        assertThat(c.hyperliquidMaxGapMs()).isEqualTo(30_000);
        assertThat(c.polymarketStaleMs()).isEqualTo(30_000);
        assertThat(c.polymarketMaxGapMs()).isEqualTo(90_000);
        assertThat(c.syntheticStaleMs()).isEqualTo(5_000);
        assertThat(c.syntheticMaxGapMs()).isEqualTo(10_000);
        assertThat(c.connectTimeoutMs()).isEqualTo(3_000);
        assertThat(c.readTimeoutMs()).isEqualTo(5_000);
        assertThat(c.backfillCooldownMs()).isEqualTo(60_000);
        assertThat(c.hyperliquidUrl()).isEqualTo("https://api.hyperliquid.xyz");
        assertThat(c.polymarketClobUrl()).isEqualTo("https://clob.polymarket.com");
        assertThat(c.polymarketGammaUrl()).isEqualTo("https://gamma-api.polymarket.com");
        assertThat(c.polymarketDataUrl()).isEqualTo("https://data-api.polymarket.com");
    }

    @Test
    void theBufferCoversTwoDailyRoundsPlusTenMinutes() {
        assertThat(produce("live", "live", 900).retentionMs()).isEqualTo(2 * 900_000L + 600_000L); // the stand: 40 min
        assertThat(produce("live", "live", 86_400).retentionMs()).isEqualTo(2 * 86_400_000L + 600_000L); // live: 48 h 10 min
    }

    @Test
    void theSwitches() {
        PricesConfig realSources = produce("live", "live", 900);
        assertThat(realSources.liveSources()).isTrue();
        assertThat(realSources.syntheticSource()).isFalse();
        assertThat(realSources.pipelineActive()).isTrue();

        PricesConfig walk = produce("LIVE", " Synthetic ", 900); // case and blanks do not matter
        assertThat(walk.liveSources()).isFalse();
        assertThat(walk.syntheticSource()).isTrue();
        assertThat(walk.pipelineActive()).isTrue();

        PricesConfig replay = produce("replay", "live", 900);
        assertThat(replay.liveSources()).isFalse();
        assertThat(replay.syntheticSource()).isFalse();
        assertThat(replay.pipelineActive()).isFalse();
        assertThat(produce("replay", "synthetic", 900).syntheticSource()).isFalse(); // mode replay switches it all off
    }

    @Test
    void aSourceThatIsNeitherLiveNorSyntheticFailsFastWithAClearMessage() {
        assertThatThrownBy(() -> produce("live", "polymarket", 900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("herd.prices.source")
                .hasMessageContaining("polymarket");
    }

    @Test
    void anUnknownModeIsRejectedByTheServersOwnParser() {
        assertThatThrownBy(() -> produce("paper", "live", 900)).isInstanceOf(IllegalArgumentException.class);
    }
}
