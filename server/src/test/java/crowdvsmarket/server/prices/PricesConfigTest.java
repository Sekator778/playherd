package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.util.List;
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
        return produce(mode, source, dailySec, venueReleaseAfterMs, PricesConfig.ReleaseAfter.defaults(), disabledVenues);
    }

    private static PricesConfig produce(String mode, String source, long dailySec, long venueReleaseAfterMs,
                                        PricesConfig.ReleaseAfter waits, Optional<String> disabledVenues) {
        return new PricesConfigProducer().pricesConfig(mode, source, dailySec, PricesConfig.DEFAULT_HYPERLIQUID_URL,
                PricesConfig.DEFAULT_POLYMARKET_CLOB_URL, PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL,
                PricesConfig.DEFAULT_POLYMARKET_DATA_URL, 15_000, 20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000,
                3_000, 5_000, 600_000L, 260, true, 0.0015, 1_800_000L, venueReleaseAfterMs, waits.hyperliquidMs(),
                waits.hyperliquidSpotMs(), waits.coinbaseMs(), waits.krakenMs(), waits.polymarketMs(),
                waits.syntheticMs(), disabledVenues);
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

    // ------------------------------------------------------------------ the release of a round whose source gives no price (Р-79)

    private static final PricesConfig.ReleaseAfter DISTINCT_WAITS =
            new PricesConfig.ReleaseAfter(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L);

    @Test
    void everySourceHasItsOwnWaitFiveMinutesAndFifteenForPolymarketByDefault() {
        PricesConfig.ReleaseAfter waits = PricesConfig.defaults().releaseAfter();

        assertThat(waits.hyperliquidMs()).as("Hyperliquid's perpetuals, both dexes").isEqualTo(300_000L);
        assertThat(waits.hyperliquidSpotMs()).isEqualTo(300_000L);
        assertThat(waits.coinbaseMs()).isEqualTo(300_000L);
        assertThat(waits.krakenMs()).isEqualTo(300_000L);
        assertThat(waits.polymarketMs()).isEqualTo(900_000L);
        assertThat(waits.syntheticMs()).isEqualTo(300_000L);
        assertThat(PricesConfig.DEFAULT_RELEASE_AFTER_MS).isEqualTo("300000");
        assertThat(PricesConfig.DEFAULT_POLYMARKET_RELEASE_AFTER_MS).isEqualTo("900000");
        assertThat(PricesConfig.ReleaseAfter.defaults()).isEqualTo(waits);
        assertThat(produce("live", "live", 900).releaseAfter()).as("what the producer reads with no key set").isEqualTo(waits);
    }

    @Test
    void theWaitsTheProducerReadsAreTheOnesItIsGiven() {
        PricesConfig produced = produce("live", "live", 900, 120_000L, DISTINCT_WAITS, Optional.empty());

        assertThat(produced.releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(produced.venueReleaseAfterMs()).as("the other release is its own setting").isEqualTo(120_000L);
    }

    @Test
    void zeroSwitchesTheReleaseOffForThatSourceAloneAndANegativeWaitStopsTheServerNamingTheKey() {
        assertThat(PricesConfig.ReleaseAfter.off()).isEqualTo(new PricesConfig.ReleaseAfter(0, 0, 0, 0, 0, 0));
        assertThat(new PricesConfig.ReleaseAfter(0, 2, 3, 4, 5, 6).forFeed("hyperliquid")).isZero();
        assertThat(new PricesConfig.ReleaseAfter(0, 2, 3, 4, 5, 6).forFeed("coinbase")).isEqualTo(3L);
        assertThat(produce("live", "live", 900, 120_000L, PricesConfig.ReleaseAfter.off(), Optional.empty())
                .releaseAfter().forFeed("polymarket")).isZero();

        String[] keys = {"herd.prices.hyperliquid.release-after-ms", "herd.prices.hyperliquid.spot-release-after-ms",
                "herd.prices.coinbase.release-after-ms", "herd.prices.kraken.release-after-ms",
                "herd.prices.polymarket.release-after-ms", "herd.prices.synthetic.release-after-ms"};
        for (int i = 0; i < keys.length; i++) {
            long[] ms = {0, 0, 0, 0, 0, 0};
            ms[i] = -1;
            assertThatThrownBy(() -> new PricesConfig.ReleaseAfter(ms[0], ms[1], ms[2], ms[3], ms[4], ms[5]))
                    .as(keys[i]).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(keys[i]);
            assertThatThrownBy(() -> produce("live", "live", 900, 120_000L,
                    new PricesConfig.ReleaseAfter(ms[0], ms[1], ms[2], ms[3], ms[4], ms[5]), Optional.empty()))
                    .as("at start-up, not at the first release").isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(keys[i]);
        }
    }

    @Test
    void everyFeedOfEverySourceHasItsWaitAndNoOtherFeedHasOne() {
        FakeWebSocketTransport transport = new FakeWebSocketTransport();
        List<PriceSource> sources = List.of(PipelineTestSupport.hyperliquid(null, () -> 0L),
                PipelineTestSupport.hyperliquidSpot(null, transport, () -> 0L),
                PipelineTestSupport.coinbase(null, transport, () -> 0L), PipelineTestSupport.kraken(null, transport, () -> 0L),
                PipelineTestSupport.polymarket(null, null, new PolymarketLiveState(), () -> 0L),
                PipelineTestSupport.synthetic(Fixtures.tickStore(() -> 0L), () -> 0L));

        List<String> feeds = sources.stream().flatMap(source -> source.feeds().stream()).map(PriceSource.Feed::id).toList();

        assertThat(feeds).containsExactlyInAnyOrder("hyperliquid", "hyperliquid:xyz", "hyperliquid:spot", "coinbase", "kraken",
                "polymarket", "synthetic");
        for (String feed : feeds) {
            assertThat(PricesConfig.ReleaseAfter.defaults().forFeed(feed)).as("the wait of " + feed).isPositive();
        }
        assertThat(PricesConfig.ReleaseAfter.defaults().forFeed("hyperliquid:xyz"))
                .as("both dexes have the setting of Hyperliquid").isEqualTo(PricesConfig.ReleaseAfter.defaults().forFeed("hyperliquid"));
        assertThat(DISTINCT_WAITS.forFeed("hyperliquid")).isEqualTo(1_000L);
        assertThat(DISTINCT_WAITS.forFeed("hyperliquid:xyz")).isEqualTo(1_000L);
        assertThat(DISTINCT_WAITS.forFeed("hyperliquid:spot")).isEqualTo(2_000L);
        assertThat(DISTINCT_WAITS.forFeed("coinbase")).isEqualTo(3_000L);
        assertThat(DISTINCT_WAITS.forFeed("kraken")).isEqualTo(4_000L);
        assertThat(DISTINCT_WAITS.forFeed("polymarket")).isEqualTo(5_000L);
        assertThat(DISTINCT_WAITS.forFeed("synthetic")).isEqualTo(6_000L);
        assertThat(DISTINCT_WAITS.forFeed("unknown")).as("a feed nobody knows is no wait: the hour of Р-23").isZero();
    }

    @Test
    void theWaitOfAnInstrumentIsTheWaitOfTheFeedThatPricesIt() {
        PricesConfig config = PricesConfig.defaults().withReleaseAfter(DISTINCT_WAITS);

        assertThat(config.releaseAfterMs(TestMarkets.hl("BTC", "BTC", 0))).as("a Hyperliquid perpetual").isEqualTo(1_000L);
        assertThat(config.releaseAfterMs(TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 10, 2, 5)))
                .as("a perpetual of the dex xyz: the same setting").isEqualTo(1_000L);
        assertThat(config.releaseAfterMs(TestMarkets.hlSpot("HYPE", "@107", 2, 2))).as("a Hyperliquid spot pair")
                .isEqualTo(2_000L);
        assertThat(config.releaseAfterMs(TestMarkets.coinbase("BTC", 0, 1))).isEqualTo(3_000L);
        assertThat(config.releaseAfterMs(TestMarkets.kraken("BTC", 0, 1))).isEqualTo(4_000L);
        assertThat(config.releaseAfterMs(Instruments.shadowOf(TestMarkets.coinbaseWithKraken("BTC", 0, 1)).orElseThrow()))
                .as("the shadow of a backup is priced by the backup's feed").isEqualTo(4_000L);
        assertThat(config.releaseAfterMs(TestMarkets.odds("PM1", "123"))).isEqualTo(5_000L);
        assertThat(config.releaseAfterMs(TestMarkets.synthetic("S1", MarketKind.PERP, "SYN", 0, null))).isEqualTo(6_000L);
    }

    @Test
    void aSourceThatIsSwitchedOffHasNoWaitAndNeitherHasAMarketNoFeedKnows() {
        PricesConfig config = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(0, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L));

        assertThat(config.releaseAfterMs(TestMarkets.hl("BTC", "BTC", 0))).as("0 for Hyperliquid").isZero();
        assertThat(config.releaseAfterMs(TestMarkets.coinbase("BTC", 0, 1))).isEqualTo(3_000L);
        Market unknownDex = TestMarkets.perp("flx_GOLD", "flx:GOLD", Category.COMMODITIES, 10, 2, 6);
        assertThat(config.releaseAfterMs(unknownDex)).as("a dex nothing polls").isZero();
        Market unknownSource = new Market("M1", MarketKind.PERP, Category.CRYPTO, "M", "M", null, null, "binance", "M-USD", null,
                10, 0, 9, MarketStatus.ACTIVE, 0L, null, null, null);
        assertThat(PricesConfig.defaults().releaseAfterMs(unknownSource)).as("a source nobody knows").isZero();
    }

    @Test
    void inSyntheticModeTheWalkPricesEveryMarketWhateverItsSourceSaysAndInReplayModeThereIsNoWait() {
        PricesConfig walk = PricesConfig.defaults().withReleaseAfter(DISTINCT_WAITS)
                .withMode(true, PricesConfig.SourceKind.SYNTHETIC);

        assertThat(walk.releaseAfterMs(TestMarkets.hl("BTC", "BTC", 0))).isEqualTo(6_000L);
        assertThat(walk.releaseAfterMs(TestMarkets.odds("PM1", "123"))).isEqualTo(6_000L);
        assertThat(walk.releaseAfterMs(TestMarkets.coinbase("BTC", 0, 1))).isEqualTo(6_000L);

        PricesConfig replay = PricesConfig.defaults().withReleaseAfter(DISTINCT_WAITS)
                .withMode(false, PricesConfig.SourceKind.LIVE);
        assertThat(replay.releaseAfterMs(TestMarkets.hl("BTC", "BTC", 0))).as("replay has no exchange to wait for").isZero();
    }

    @Test
    void everyOtherSettingSurvivesTheChangeOfTheWaitsAndTheWaitsSurviveEveryOtherChange() {
        PricesConfig own = PricesConfig.defaults().withReleaseAfter(DISTINCT_WAITS);

        assertThat(own.venueReleaseAfterMs()).isEqualTo(120_000L);
        assertThat(own.disabledVenues()).isEmpty();
        assertThat(own.withVenueReleaseAfterMs(5).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withDisabledVenues(Set.of("kraken")).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withHyperliquidMaxBasis(0.002).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withHyperliquidMaxHealMs(5).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withQuietCarryMaxMs(1).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withHyperliquidSeedBudget(1).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withDailyRoundMs(1_000).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withEndpoints("http://x", 1, 1).releaseAfter()).isEqualTo(DISTINCT_WAITS);
        assertThat(own.withMode(true, PricesConfig.SourceKind.SYNTHETIC).releaseAfter()).isEqualTo(DISTINCT_WAITS);
    }

    @Test
    void theConstructorOfBeforeTheWaitsKeepsWorkingWithTheirDefaults() {
        PricesConfig before = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L, "h", "c", "g", "d", 15_000,
                20_000, 30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000, 600_000L, 260, true, 0.0015,
                1_800_000L, Set.of("kraken"), 30_000L); // the 24 components of before

        assertThat(before.releaseAfter()).isEqualTo(PricesConfig.ReleaseAfter.defaults());
        assertThat(before.venueReleaseAfterMs()).isEqualTo(30_000L);
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
