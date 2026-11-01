package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The price half of letting a round go (D-77): {@link LiveMarketPrices#otherVenueLive} says whether the exchange a round of a
 * market with a backup was <i>not</i> decided on has a live tick that is fresh at the instant asked about. Real tick rings,
 * the real holder of the decisions over an in-memory table, scripted sources (only their feeds' limits matter: 15 s of
 * staleness): nothing here touches a network or a database.
 */
class LiveMarketPricesReleaseTest {

    /** 2026-10-03T12:31:00Z, on a minute. */
    private static final long T0 = 1_791_030_660_000L;
    private static final long STEP = 180_000L;
    private static final long START = T0;
    private static final long END = T0 + STEP;
    /** The staleness limit of the scripted feeds. */
    private static final long STALE = 15_000L;
    /** Two minutes after the end of the round: the instant the scheduler asks at. */
    private static final long RELEASE = END + 120_000L;

    private final PricesConfig config = PricesConfig.defaults();
    private final AtomicLong clock = new AtomicLong(RELEASE);
    private final Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    private final Market shadow = Instruments.shadowOf(btc).orElseThrow();
    private final Market plain = TestMarkets.coinbase("ETH", 0, 2);

    private FakeSnapshotStore snapshots;
    private FakeRoundVenueStore venueStore;
    private TickStore store;
    private RoundVenues venues;
    private LiveMarketPrices prices;

    /** A venue's source: nothing but the feed that says which markets it prices and when its ticks go stale. */
    private static final class Venue implements PriceSource {

        private final String name;

        Venue(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed(name, STALE, 30_000, m -> name.equals(m.source())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) {
            return List.of();
        }
    }

    private final PriceSource coinbase = new Venue("coinbase");
    private final PriceSource kraken = new Venue("kraken");

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotStore();
        venueStore = new FakeRoundVenueStore();
        store = new TickStore(snapshots, config.retentionMs(), clock::get);
        venues = new RoundVenues(venueStore, config.retentionMs(), clock::get);
        prices = new LiveMarketPrices(store, new PriceSources(List.of(coinbase, kraken)), config, venues, clock::get);
    }

    private RoundWindow window() {
        return new RoundWindow(btc.id(), Interval.HOURLY, START, END);
    }

    private void decide(String venue) {
        venues.decide(window().roundId(), btc.id(), START, venue, START + 500);
    }

    private void primaryTickAt(long atMs) {
        store.append("coinbase", atMs, Map.of(btc.id(), BigDecimal.valueOf(100.0)));
    }

    private void backupTickAt(long atMs) {
        store.append("kraken", atMs, Map.of(shadow.id(), BigDecimal.valueOf(100.5)));
    }

    // ------------------------------------------------------------------ the other exchange of a round

    @Test
    void aRoundOnThePrimaryAsksAfterTheBackup() {
        decide("coinbase");
        backupTickAt(RELEASE - 1_000);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
    }

    @Test
    void aRoundOnTheBackupAsksAfterThePrimary() {
        decide("kraken");
        primaryTickAt(RELEASE - 1_000);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
    }

    @Test
    void theExchangeTheRoundRunsOnSaysNothingEitherWay() {
        decide("coinbase");
        primaryTickAt(RELEASE - 1_000); // its own exchange is alive again — it is the other one that is asked about

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).as("the backup is silent").isFalse();

        backupTickAt(RELEASE - 1_000);
        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).as("the backup is alive too").isTrue();
    }

    @Test
    void aRoundOnTheBackupWhoseBackupIsTheOneThatWentSilentIsLetGoWhenThePrimaryIsAlive() {
        decide("kraken");
        backupTickAt(RELEASE - 120_000L); // Kraken stopped two minutes ago
        primaryTickAt(RELEASE - 2_000);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
    }

    // ------------------------------------------------------------------ what "alive now" is

    @Test
    void aTickExactlyAtTheLimitIsFreshAndOneMillisecondOlderIsNot() {
        decide("coinbase");
        backupTickAt(RELEASE - STALE);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).as("at the limit").isTrue();
        assertThat(prices.otherVenueLive(btc, window(), RELEASE + 1)).as("a millisecond past it").isFalse();
    }

    @Test
    void theAnswerIsForTheInstantAskedAboutNotForTheClockOfTheObject() {
        decide("coinbase");
        backupTickAt(RELEASE - 1_000);
        clock.set(RELEASE + 3_600_000L); // an hour later by the object's own clock

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
        assertThat(prices.otherVenueLive(btc, window(), RELEASE + 3_600_000L)).isFalse();
    }

    @Test
    void historyACarriedTickAndAPinAreNotALiveTick() {
        decide("coinbase");
        long at = RELEASE - 1_000;
        store.mergeHistory(shadow.id(), List.of(new PricePoint(at - 10, new BigDecimal("100.5"))));
        store.mergeCarried(shadow.id(), List.of(new PricePoint(at - 5, new BigDecimal("100.5"))));
        store.pinBoundary(shadow.id(), at, Px.encode(new BigDecimal("100.5")));

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isFalse();

        backupTickAt(at - 20_000L); // a live tick, but too old
        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isFalse();

        backupTickAt(at);
        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
    }

    @Test
    void anExchangeThatIsSwitchedOffIsNeverAliveWhateverItsRingStillHolds() {
        decide("coinbase");
        backupTickAt(RELEASE - 1_000); // restored at start from the snapshots, say
        LiveMarketPrices krakenOff = new LiveMarketPrices(store, new PriceSources(List.of(coinbase)), config, venues,
                clock::get); // herd.prices.disabled-venues=kraken: its source does not exist

        assertThat(krakenOff.otherVenueLive(btc, window(), RELEASE)).isFalse();
        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isTrue();
    }

    // ------------------------------------------------------------------ when nothing can be said

    @Test
    void aRoundWithNoExchangeDecidedHasNoOtherOneAndTheQuestionDecidesNothing() {
        primaryTickAt(RELEASE - 1_000);
        backupTickAt(RELEASE - 1_000);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isFalse();
        assertThat(venueStore.inserts).as("a question is no decision").isEmpty();
        assertThat(venues.decided(window().roundId())).isEmpty();
    }

    @Test
    void aMarketWithoutABackupHasNoOtherExchange() {
        RoundWindow own = new RoundWindow(plain.id(), Interval.HOURLY, START, END);
        store.append("coinbase", RELEASE - 1_000, Map.of(plain.id(), BigDecimal.valueOf(100.0)));
        venues.decide(own.roundId(), plain.id(), START, "coinbase", START + 500);

        assertThat(prices.otherVenueLive(plain, own, RELEASE)).isFalse();
    }

    @Test
    void aDecisionThisConfigurationCannotReadIsNoReasonToLetTheRoundGo() {
        decide("hyperliquid"); // neither the primary Coinbase nor the backup Kraken of this market
        primaryTickAt(RELEASE - 1_000);
        backupTickAt(RELEASE - 1_000);

        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).isFalse();
    }

    @Test
    void aWindowOfAnotherMarketIsRefused() {
        RoundWindow other = new RoundWindow(plain.id(), Interval.HOURLY, START, END);

        assertThatThrownBy(() -> prices.otherVenueLive(btc, other, RELEASE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ a restart between the end of the round and its release

    @Test
    void afterARestartTheDecisionComesFromTheTableAndTheTicksFromTheSnapshots() {
        decide("coinbase");
        backupTickAt(RELEASE - 1_000);
        primaryTickAt(END - 60_000L); // the primary went silent before the end of the round

        TickStore restarted = new TickStore(snapshots, config.retentionMs(), clock::get);
        restarted.restore();
        RoundVenues freshHolder = new RoundVenues(venueStore, config.retentionMs(), clock::get); // nothing in memory
        LiveMarketPrices afterRestart = new LiveMarketPrices(restarted, new PriceSources(List.of(coinbase, kraken)),
                config, freshHolder, clock::get);

        assertThat(freshHolder.decided(window().roundId())).as("a restart forgets the memory").isEmpty();
        assertThat(afterRestart.otherVenueLive(btc, window(), RELEASE)).isTrue();
        assertThat(freshHolder.decided(window().roundId())).as("and the table brought the decision back").contains("coinbase");
    }

    @Test
    void aTableThatDoesNotAnswerIsAFailureOfTheQuestionNotAnAnswerToIt() {
        decide("coinbase");
        backupTickAt(RELEASE - 1_000);
        RoundVenues freshHolder = new RoundVenues(venueStore, config.retentionMs(), clock::get);
        venueStore.failReads = true;
        LiveMarketPrices afterRestart = new LiveMarketPrices(store, new PriceSources(List.of(coinbase, kraken)), config,
                freshHolder, clock::get);

        assertThatThrownBy(() -> afterRestart.otherVenueLive(btc, window(), RELEASE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("db is down");
        assertThat(prices.otherVenueLive(btc, window(), RELEASE)).as("what the memory knows is answered as usual").isTrue();
    }
}
