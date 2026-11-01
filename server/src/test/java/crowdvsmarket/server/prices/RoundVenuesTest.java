package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The holder of the round venues (D-76): a decision is written once and whoever wrote first decided, it is kept in memory
 * next to its row, loaded again at start, and two rounds of a market that start together find each other.
 */
class RoundVenuesTest {

    private static final long T0 = 1_791_030_000_000L;
    private static final long STEP = 180_000L;
    private static final String ROUND = "spot_BTC-1h-" + T0;

    private final AtomicLong now = new AtomicLong(T0);
    private FakeRoundVenueStore store;
    private RoundVenues venues;

    @BeforeEach
    void setUp() {
        store = new FakeRoundVenueStore();
        venues = new RoundVenues(store, PricesConfig.defaults().retentionMs(), now::get);
    }

    @Test
    void aDecisionIsWrittenOnceAndRemembered() {
        String venue = venues.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 1_000);

        assertThat(venue).isEqualTo("coinbase");
        assertThat(store.rows).containsOnlyKeys(ROUND);
        assertThat(store.rows.get(ROUND)).isEqualTo(new RoundVenueStore.Decision(ROUND, "spot_BTC", "coinbase", T0 + 1_000));
        assertThat(venues.decided(ROUND)).contains("coinbase");
        assertThat(venues.decidedForStart("spot_BTC", T0)).contains("coinbase");
        assertThat(venues.decidedForStart("spot_BTC", T0 + STEP)).isEmpty();
        assertThat(venues.decidedForStart("spot_ETH", T0)).as("another market's round is another thing").isEmpty();
    }

    @Test
    void whoeverWroteFirstDecidedAndASecondCallGetsTheStoredVenue() {
        venues.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 1_000);
        RoundVenues another = new RoundVenues(store, PricesConfig.defaults().retentionMs(), now::get); // another process

        String venue = another.decide(ROUND, "spot_BTC", T0, "kraken", T0 + 2_000);

        assertThat(venue).as("the table's row, not what this call wanted").isEqualTo("coinbase");
        assertThat(another.decided(ROUND)).contains("coinbase");
        assertThat(store.rows.get(ROUND).venue()).isEqualTo("coinbase");
        assertThat(store.rows.get(ROUND).decidedAtMs()).as("never written twice").isEqualTo(T0 + 1_000);
        assertThat(store.inserts).hasSize(2); // the second tried, and the table kept the first
    }

    @Test
    void nothingIsDecidedUntilSomeoneDecides() {
        assertThat(venues.decided(ROUND)).isEmpty();
        assertThat(venues.venueOfRound(ROUND)).isEmpty();
        assertThat(store.finds).isEqualTo(1);
        assertThat(venues.decidedForStart("spot_BTC", T0)).isEmpty();
    }

    @Test
    void theDecisionsOfTheLastRetentionAreLoadedAtStartAndOlderOnesAreFoundInTheTable() {
        long retention = PricesConfig.defaults().retentionMs();
        long old = T0 - retention - RoundVenues.KEEP_EXTRA_MS - 60_000L;
        String recent = "spot_BTC-1h-" + (T0 - 60_000L);
        String ancient = "spot_BTC-1h-" + old;
        store.put(new RoundVenueStore.Decision(recent, "spot_BTC", "kraken", T0 - 59_000L));
        store.put(new RoundVenueStore.Decision(ancient, "spot_BTC", "coinbase", old + 1_000L));

        assertThat(venues.load()).as("only the recent row is read at start").isEqualTo(1);

        assertThat(venues.decided(recent)).contains("kraken");
        assertThat(venues.decidedForStart("spot_BTC", T0 - 60_000L)).contains("kraken");
        assertThat(venues.decided(ancient)).as("memory only").isEmpty();
        assertThat(venues.venueOfRound(ancient)).as("the table has it").contains("coinbase");
        assertThat(venues.decided(ancient)).as("and it is remembered after that").contains("coinbase");
    }

    @Test
    void aRestartLoadsWhatWasDecidedAndDecidingAgainChangesNothing() {
        venues.decide(ROUND, "spot_BTC", T0, "kraken", T0 + 1_000);

        RoundVenues afterARestart = new RoundVenues(store, PricesConfig.defaults().retentionMs(), now::get);
        assertThat(afterARestart.decided(ROUND)).as("not loaded yet: memory only").isEmpty();
        afterARestart.load();

        assertThat(afterARestart.decided(ROUND)).contains("kraken");
        assertThat(afterARestart.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 99_000L)).isEqualTo("kraken");
        assertThat(store.rows.get(ROUND).decidedAtMs()).isEqualTo(T0 + 1_000);
    }

    @Test
    void aFailedLoadLeavesMemoryEmptyAndDecidingStillReadsTheStoredRowBack() {
        venues.decide(ROUND, "spot_BTC", T0, "kraken", T0 + 1_000);
        RoundVenues afterARestart = new RoundVenues(store, PricesConfig.defaults().retentionMs(), now::get);
        store.failReads = true;

        assertThatThrownBy(afterARestart::load).isInstanceOf(IllegalStateException.class);
        store.failReads = false;

        assertThat(afterARestart.decided(ROUND)).isEmpty();
        assertThat(afterARestart.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 5_000L))
                .as("the row decides, whatever memory forgot").isEqualTo("kraken");
    }

    @Test
    void aDecisionThatCannotBeWrittenIsNoDecision() {
        store.failWrites = true;

        assertThatThrownBy(() -> venues.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 1_000))
                .isInstanceOf(IllegalStateException.class);

        assertThat(venues.decided(ROUND)).as("memory never runs ahead of the table").isEmpty();
        assertThat(venues.decidedForStart("spot_BTC", T0)).isEmpty();
    }

    @Test
    void rowsOfRoundsOlderThanTwiceTheRetentionAreForgottenInMemory() {
        long retention = PricesConfig.defaults().retentionMs();
        venues.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 1_000);
        String later = "spot_BTC-1h-" + (T0 + 10 * retention);

        venues.decide(later, "spot_BTC", T0 + 10 * retention, "coinbase", T0 + 10 * retention + 1_000);
        venues.prune(T0 + 10 * retention + 1_000);

        assertThat(venues.decided(ROUND)).isEmpty();
        assertThat(venues.decidedForStart("spot_BTC", T0)).isEmpty();
        assertThat(venues.decided(later)).contains("coinbase");
        assertThat(venues.size()).isEqualTo(1);
        assertThat(store.rows).as("the table keeps everything").hasSize(2);
    }

    @Test
    void aRowWhoseIdIsNoRoundIdIsSkippedAtLoad() {
        store.put(new RoundVenueStore.Decision("garbage", "spot_BTC", "kraken", T0));

        assertThat(venues.load()).isEqualTo(1);

        assertThat(venues.size()).isZero();
    }

    @Test
    void anInMemoryHolderKeepsTheFirstDecisionToo() {
        RoundVenues plain = RoundVenues.inMemory(PricesConfig.defaults().retentionMs(), now::get);

        assertThat(plain.decide(ROUND, "spot_BTC", T0, "kraken", T0)).isEqualTo("kraken");
        assertThat(plain.decide(ROUND, "spot_BTC", T0, "coinbase", T0 + 1)).isEqualTo("kraken");
        assertThat(plain.venueOfRound(ROUND)).contains("kraken");
        assertThat(plain.load()).isEqualTo(1);
    }
}
