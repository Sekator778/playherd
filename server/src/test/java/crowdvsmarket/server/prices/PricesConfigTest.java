package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PricesConfig} and its producer: the architecture's limits, the retention, the mode and source switches. */
class PricesConfigTest {

    private static PricesConfig produce(String mode, String source, long dailySec) {
        return produce(mode, source, dailySec, Optional.empty());
    }

    private static PricesConfig produce(String mode, String source, long dailySec, Optional<String> disabledVenues) {
        return produce(mode, source, dailySec, 120_000L, disabledVenues);
    }

    private static PricesConfig produce(String mode, String source, long dailySec, long venueReleaseAfterMs,
                                        Optional<String> disabledVenues) {
        return new PricesConfigProducer().pricesConfig(mode, source, dailySec, PricesConfig.DEFAULT_HYPERLIQUID_URL,
                PricesConfig.DEFAULT_POLYMARKET_CLOB_URL, PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL,
                PricesConfig.DEFAULT_POLYMARKET_DATA_URL, 15_000, 20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000,
                3_000, 5_000, 600_000L, 260, true, 0.0015, 1_800_000L, venueReleaseAfterMs, disabledVenues);
    }

    @Test
    void noVenueIsDisabledByDefaultAndTheSwitchNamesVenuesInAnyCase() {
        assertThat(PricesConfig.defaults().disabledVenues()).isEmpty();
        assertThat(produce("live", "live", 900).disabledVenues()).isEmpty();
        assertThat(produce("live", "live", 900, Optional.of("")).disabledVenues()).isEmpty();

        PricesConfig coinbaseOff = produce("live", "live", 900, Optional.of(" Coinbase ,, kraken,coinbase"));

        assertThat(coinbaseOff.disabledVenues()).containsExactlyInAnyOrder("coinbase", "kraken");
        assertThat(coinbaseOff.venueDisabled("coinbase")).isTrue();
        assertThat(coinbaseOff.venueDisabled("hyperliquid")).isFalse();
        assertThat(PricesConfig.parseVenues("hyperliquid,polymarket")).containsExactly("hyperliquid", "polymarket");
    }

    @Test
    void aTypoInTheSwitchStopsTheServerRatherThanLeavingTheVenueOn() {
        assertThatThrownBy(() -> produce("live", "live", 900, Optional.of("coinbse")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("herd.prices.disabled-venues").hasMessageContaining("coinbse");
        assertThatThrownBy(() -> produce("live", "live", 900, Optional.of("synthetic")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOtherSettingsSurviveTheSwitchAndTheSwitchSurvivesTheOtherSettings() {
        PricesConfig off = PricesConfig.defaults().withDisabledVenues(Set.of("coinbase"));

        assertThat(off.hyperliquidMaxBasis()).isEqualTo(0.0015);
        assertThat(off.withHyperliquidMaxBasis(0.002).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withHyperliquidMaxHealMs(5).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withQuietCarryMaxMs(1).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withHyperliquidSeedBudget(1).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withDailyRoundMs(1_000).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withEndpoints("http://x", 1, 1).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withMode(true, PricesConfig.SourceKind.SYNTHETIC).disabledVenues()).containsExactly("coinbase");
        assertThat(off.withVenueReleaseAfterMs(5).disabledVenues()).containsExactly("coinbase");
    }

    // ------------------------------------------------------------------ the release of a round from a silent exchange (Р-77)

    @Test
    void aRoundIsLetGoFromASilentExchangeAfterTwoMinutesByDefault() {
        assertThat(PricesConfig.defaults().venueReleaseAfterMs()).isEqualTo(120_000L);
        assertThat(PricesConfig.DEFAULT_VENUE_RELEASE_AFTER_MS).isEqualTo("120000");
        assertThat(produce("live", "live", 900).venueReleaseAfterMs()).isEqualTo(120_000L);
        assertThat(produce("live", "live", 900, 30_000L, Optional.empty()).venueReleaseAfterMs()).isEqualTo(30_000L);
    }

    @Test
    void zeroSwitchesTheReleaseOffAndANegativeWaitStopsTheServer() {
        assertThat(PricesConfig.defaults().withVenueReleaseAfterMs(0).venueReleaseAfterMs()).isZero();
        assertThat(produce("live", "live", 900, 0L, Optional.empty()).venueReleaseAfterMs()).isZero();
        assertThatThrownBy(() -> PricesConfig.defaults().withVenueReleaseAfterMs(-1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("herd.prices.venue-release-after-ms");
        assertThatThrownBy(() -> produce("live", "live", 900, -1L, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("herd.prices.venue-release-after-ms");
    }

    @Test
    void everyOtherSettingSurvivesTheChangeOfTheReleaseAndTheReleaseSurvivesEveryOtherChange() {
        PricesConfig slow = PricesConfig.defaults().withVenueReleaseAfterMs(300_000L);

        assertThat(slow.disabledVenues()).isEmpty();
        assertThat(slow.hyperliquidMaxHealMs()).isEqualTo(1_800_000L);
        assertThat(slow.withDisabledVenues(Set.of("kraken")).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withHyperliquidMaxBasis(0.002).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withHyperliquidMaxHealMs(5).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withQuietCarryMaxMs(1).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withHyperliquidSeedBudget(1).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withDailyRoundMs(1_000).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withEndpoints("http://x", 1, 1).venueReleaseAfterMs()).isEqualTo(300_000L);
        assertThat(slow.withMode(true, PricesConfig.SourceKind.SYNTHETIC).venueReleaseAfterMs()).isEqualTo(300_000L);
    }

    @Test
    void theConstructorOfBeforeTheReleaseKeepsWorkingWithTheDefault() {
        PricesConfig before = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L, "h", "c", "g", "d", 15_000,
                20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000, 600_000L, 260, true, 0.0015,
                1_800_000L, Set.of("kraken")); // the 23 components of before

        assertThat(before.venueReleaseAfterMs()).isEqualTo(120_000L);
        assertThat(before.disabledVenues()).containsExactly("kraken");
    }

    @Test
    void theGapSettingsHaveTheirDefaultsAndTheEarlierConstructorsKeepWorking() {
        PricesConfig c = PricesConfig.defaults();

        assertThat(c.hyperliquidMaxBasis()).isEqualTo(0.0015); // 15 bp
        assertThat(c.hyperliquidMaxHealMs()).isEqualTo(30 * 60_000L); // half an hour
        PricesConfig twenty = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L, "h", "c", "g", "d", 15_000,
                20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000, 600_000L, 260, true); // before the bridge
        assertThat(twenty.hyperliquidMaxBasis()).isEqualTo(0.0015);
        assertThat(twenty.hyperliquidMaxHealMs()).isEqualTo(1_800_000L);
        PricesConfig old = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L, "h", "c", "g", "d", 15_000,
                20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000);
        assertThat(old.hyperliquidMaxBasis()).isEqualTo(0.0015);
        assertThat(old.hyperliquidMaxHealMs()).isEqualTo(1_800_000L);
        assertThat(c.withHyperliquidMaxBasis(0.002).hyperliquidMaxBasis()).isEqualTo(0.002);
        assertThat(c.withHyperliquidMaxHealMs(0).hyperliquidMaxHealMs()).isZero(); // 0 switches bridging off
        // the other settings survive the change of one of them
        assertThat(c.withHyperliquidMaxBasis(0.002).hyperliquidMaxHealMs()).isEqualTo(1_800_000L);
        assertThat(c.withHyperliquidMaxHealMs(5).hyperliquidMaxBasis()).isEqualTo(0.0015);
        assertThat(c.withMode(true, PricesConfig.SourceKind.SYNTHETIC).hyperliquidMaxBasis()).isEqualTo(0.0015);
        assertThat(c.withQuietCarryMaxMs(1L).hyperliquidMaxHealMs()).isEqualTo(1_800_000L);
        assertThat(produce("live", "live", 900).hyperliquidMaxBasis()).isEqualTo(0.0015);
    }

    @Test
    void aNegativeOrNonsenseGapSettingFailsAtStartupNotAtTheFirstSettlement() {
        PricesConfig c = PricesConfig.defaults();

        assertThatThrownBy(() -> c.withHyperliquidMaxBasis(-0.001)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("herd.prices.hyperliquid.max-basis");
        assertThatThrownBy(() -> c.withHyperliquidMaxBasis(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.withHyperliquidMaxBasis(Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.withHyperliquidMaxHealMs(-1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("herd.prices.hyperliquid.max-heal-ms");
        assertThat(c.withHyperliquidMaxBasis(0).hyperliquidMaxBasis()).isZero(); // zero tolerance is legal (strict)
    }

    @Test
    void theNewSettingsHaveTheirDefaultsAndTheOldConstructorKeepsWorking() {
        PricesConfig c = PricesConfig.defaults();

        assertThat(c.quietCarryMaxMs()).isEqualTo(600_000L); // ten minutes
        assertThat(c.hyperliquidSeedBudgetPerMinute()).isEqualTo(260); // 900 - 600 polling - 40 catalog
        assertThat(c.requireMinuteAlignedRounds()).isTrue();
        PricesConfig old = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L, "h", "c", "g", "d", 15_000,
                20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000); // the 17 components of before
        assertThat(old.quietCarryMaxMs()).isEqualTo(600_000L);
        assertThat(old.hyperliquidSeedBudgetPerMinute()).isEqualTo(260);
        assertThat(old.requireMinuteAlignedRounds()).isTrue();
        assertThat(c.withQuietCarryMaxMs(1_000L).quietCarryMaxMs()).isEqualTo(1_000L);
        assertThat(c.withHyperliquidSeedBudget(100).hyperliquidSeedBudgetPerMinute()).isEqualTo(100);
        assertThat(produce("live", "live", 900).quietCarryMaxMs()).isEqualTo(600_000L);
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
