package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Р-76, "a round runs entirely on one exchange", in the price pipeline: a market with a backup exchange has a ring per
 * venue; the venue of a round is chosen once, when it opens — the primary if it has a <i>live</i> tick fresh at the
 * round's start, else the backup, else the round waits for history to heal a start — written once, never changed, and the
 * round is settled from its venue's ring alone. Scripted sources and a scripted clock: nothing here touches a network or
 * a database.
 */
class LiveMarketPricesVenuesTest {

    /** 2026-10-03T12:31:00Z, on a minute. */
    private static final long T0 = 1_791_030_660_000L;
    private static final long STEP = 180_000L;
    private static final long START = T0;
    private static final long END = T0 + STEP;

    private final PricesConfig config = PricesConfig.defaults();
    private final AtomicLong now = new AtomicLong(START + 1_000);
    private final Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    private final Market shadow = Instruments.shadowOf(btc).orElseThrow();
    private final Market plain = TestMarkets.coinbase("ETH", 0, 2);

    private FakeSnapshotStore snapshots;
    private TickStore store;
    private FakeRoundVenueStore venueStore;
    private RoundVenues venues;
    private Venue coinbase;
    private Venue kraken;
    private LiveMarketPrices prices;

    /** A venue's source: the feed, and the history the test scripts per instrument. */
    private static final class Venue implements PriceSource {

        final String name;
        final List<String> historyAsked = new ArrayList<>();
        Function<Market, List<PricePoint>> history = m -> List.of();
        IOException historyFailure;

        Venue(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed(name, 15_000, 30_000, m -> name.equals(m.source())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            historyAsked.add(market.id());
            if (historyFailure != null) {
                throw historyFailure;
            }
            return history.apply(market);
        }
    }

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, config.retentionMs(), now::get);
        venueStore = new FakeRoundVenueStore();
        venues = new RoundVenues(venueStore, config.retentionMs(), now::get);
        coinbase = new Venue("coinbase");
        kraken = new Venue("kraken");
        prices = prices(store, venues, List.of(coinbase, kraken));
    }

    private LiveMarketPrices prices(TickStore ticks, RoundVenues holder, List<PriceSource> sources) {
        return new LiveMarketPrices(ticks, new PriceSources(sources), config, holder, now::get);
    }

    private static RoundWindow window(Market market) {
        return new RoundWindow(market.id(), Interval.HOURLY, START, END);
    }

    private static RoundWindow window(Market market, Interval interval, long start, long end) {
        return new RoundWindow(market.id(), interval, start, end);
    }

    /** Live ticks of the primary: {@code spot_BTC} at {@code price} every {@code stepMs} from {@code from} to {@code to}. */
    private void primary(long from, long to, long stepMs, double price) {
        for (long at = from; at <= to; at += stepMs) {
            store.append("coinbase", at, Map.of(btc.id(), BigDecimal.valueOf(price)));
        }
    }

    /** Live ticks of the backup: {@code spot_BTC~kraken}. */
    private void backup(long from, long to, long stepMs, double price) {
        for (long at = from; at <= to; at += stepMs) {
            store.append("kraken", at, Map.of(shadow.id(), BigDecimal.valueOf(price)));
        }
    }

    private static PricePoint point(long at, double price) {
        return new PricePoint(at, BigDecimal.valueOf(price));
    }

    // ------------------------------------------------------------------ the choice at the open

    @Test
    void theRoundOpensOnThePrimaryWhenItHasALiveTickFreshAtTheStart() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        backup(START - 2_000, END + 3_000, 3_000, 100.5);

        Optional<String> venue = prices.openRound(btc, window(btc), START + 500);

        assertThat(venue).contains("coinbase");
        assertThat(prices.decidedVenue(btc, window(btc))).contains("coinbase");
        assertThat(venueStore.rows.get(window(btc).roundId())).isEqualTo(new RoundVenueStore.Decision(window(btc).roundId(),
                "spot_BTC", "coinbase", START + 500));
        assertThat(coinbase.historyAsked).as("nothing to heal").isEmpty();
        assertThat(kraken.historyAsked).isEmpty();
    }

    @Test
    void theRoundOpensOnTheBackupWhenOnlyItHasALiveTickFreshAtTheStart() {
        primary(START - 20_000, START - 16_000, 1_000, 100.0); // the primary's last tick: 16 s before the start (limit 15 s)
        backup(START - 2_000, END + 3_000, 3_000, 100.5);

        Optional<String> venue = prices.openRound(btc, window(btc), START + 500);

        assertThat(venue).as("Kraken, not Coinbase").contains("kraken");
        assertThat(venueStore.rows.get(window(btc).roundId()).venue()).isEqualTo("kraken");
    }

    @Test
    void aPrimaryTickThatIsExactlyAtTheLimitIsFreshAndOneMillisecondOlderIsNot() {
        primary(START - 15_000, START - 15_000, 1, 100.0); // 15 000 ms before: exactly the feed's staleMs
        backup(START - 2_000, END + 3_000, 3_000, 100.5);

        assertThat(prices.openRound(btc, window(btc), START + 500)).as("at the limit: fresh").contains("coinbase");

        RoundWindow other = window(btc, Interval.DAILY, START + 600_000L, START + 600_000L + STEP * 5);
        primary(START + 600_000L - 15_001, START + 600_000L - 15_001, 1, 100.0);
        backup(START + 600_000L - 2_000, START + 600_000L, 1_000, 100.5);
        assertThat(prices.openRound(btc, other, START + 601_000L)).as("a millisecond past it: not").contains("kraken");
    }

    @Test
    void aHistoryPointIsNotALiveTickAndAPrimaryWithOnlyThatLosesToALiveBackup() {
        // the primary's ring holds a history point (and carries) at the start, but nothing live; the backup is live
        store.mergeHistory(btc.id(), List.of(point(START - 1, 99.0)));
        backup(START - 2_000, END + 3_000, 3_000, 100.5);

        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("kraken");
    }

    @Test
    void aPinThatStandsForALiveTickIsALiveTickAtTheStartButAPinOfANeverLivePriceIsNot() {
        // the previous round settled on the primary and pinned its close at the boundary: the live tick it stands for is 4 s old
        primary(START - 4_000, START - 4_000, 1, 100.0);
        store.pinBoundary(btc.id(), START, Px.encode(new BigDecimal("100.0")));
        backup(START - 1_000, END, 1_000, 100.5);

        assertThat(prices.openRound(btc, window(btc), START + 500)).as("the pin stands for a tick that was fresh").contains("coinbase");

        // a pin whose real tick is 20 s old does not make the primary live
        TickStore other = Fixtures.tickStore(now::get);
        FakeRoundVenueStore otherStore = new FakeRoundVenueStore();
        LiveMarketPrices separate = prices(other, new RoundVenues(otherStore, config.retentionMs(), now::get),
                List.of(coinbase, kraken));
        other.append("coinbase", START - 20_000, Map.of(btc.id(), new BigDecimal("100.0")));
        other.pinBoundary(btc.id(), START, Px.encode(new BigDecimal("100.0")));
        other.append("kraken", START - 1_000, Map.of(shadow.id(), new BigDecimal("100.5")));

        assertThat(separate.openRound(btc, window(btc), START + 500)).as("not live; the backup is").contains("kraken");
    }

    // ------------------------------------------------------------------ no live tick at the start: the rest of the evidence

    /** What {@link HistorySeeder} leaves in a ring: history points from {@code from} to {@code to}, no live tick, no pin. */
    private void seeded(Market instrument, String feed, long from, long to, long stepMs, double price) {
        List<PricePoint> points = new ArrayList<>();
        for (long at = from; at <= to; at += stepMs) {
            points.add(point(at, price));
        }
        store.seedHistory(instrument.id(), feed, points);
    }

    @Test
    void aMarketListedMidWindowWhosePrimaryRingHoldsOnlySeededHistoryAtTheStartOpensOnThePrimary() {
        // listed 100 s into the round: the seeds gave both rings the past (a point at the start itself, the close of the
        // minute before it), the polls began after the listing — no live tick at the start on either side
        now.set(START + 100_000L);
        seeded(btc, "coinbase", START - 900_000L, START + 90_000L, 20_000L, 99.0);
        seeded(shadow, "kraken", START - 900_000L, START + 90_000L, 20_000L, 99.5);
        primary(START + 102_000L, START + 110_000L, 2_000L, 100.0);
        backup(START + 102_000L, START + 110_000L, 2_000L, 100.5);

        Optional<String> venue = prices.openRound(btc, window(btc), now.get());

        assertThat(venue).as("history is not a live tick: the primary's and the backup's alike — the primary is first").contains("coinbase");
        assertThat(coinbase.historyAsked).as("nothing to heal").isEmpty();
    }

    @Test
    void aVenueWithSeededHistoryAtTheStartBeatsOneWithNothingBothWays() {
        seeded(btc, "coinbase", START - 600_000L, START, 20_000L, 99.0);
        assertThat(prices.openRound(btc, window(btc), START + 500)).as("only the primary can price the start").contains("coinbase");

        RoundWindow later = window(btc, Interval.HOURLY, START + 600_000L, START + 600_000L + STEP);
        seeded(shadow, "kraken", START, START + 600_000L, 20_000L, 99.5);
        assertThat(prices.openRound(btc, later, START + 601_000L)).as("only the backup can").contains("kraken");
    }

    @Test
    void aPinOnTheBackupAtTheStartDoesNotBeatAPrimaryThatCanPriceTheStartFromHistory() {
        // the previous round ran on the backup and pinned its close at this boundary; the backup's last live tick is 40 s old,
        // and the primary's ring holds history up to a millisecond before the start
        backup(START - 60_000L, START - 40_000L, 5_000L, 100.5);
        store.pinBoundary(shadow.id(), START, Px.encode(new BigDecimal("100.5")));
        store.mergeHistory(btc.id(), List.of(point(START - 20_000L, 99.7), point(START - 1, 99.9)));

        assertThat(prices.openRound(btc, window(btc), START + 500)).as("the pin is non-live evidence, the primary is first")
                .contains("coinbase");
    }

    @Test
    void aPinOnTheBackupAloneStillPricesTheStartWhenThePrimaryHasNothingAtAll() {
        backup(START - 60_000L, START - 40_000L, 5_000L, 100.5);
        store.pinBoundary(shadow.id(), START, Px.encode(new BigDecimal("100.5")));

        assertThat(prices.openRound(btc, window(btc), START + 500)).as("a price at the start is a price at the start")
                .contains("kraken");
        assertThat(coinbase.historyAsked).isEmpty();
    }

    @Test
    void aCarriedTickIsNonLiveEvidenceToo() {
        // the primary's last live tick is 100 s old, a quiet hole was carried across to a few seconds before the start; the
        // backup's ring is empty: the primary prices the start, but never as a live tick
        primary(START - 130_000L, START - 100_000L, 10_000L, 100.0);
        store.mergeCarried(btc.id(), List.of(point(START - 90_000L, 100.0), point(START - 10_000L, 100.0)));
        backup(START - 50_000L, START - 40_000L, 5_000L, 100.5); // stale at the start (limit 15 s)

        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");
    }

    @Test
    void aLiveTickOfTheBackupBeatsHistoryOfThePrimaryAndAPinChangesNothingAboutThat() {
        store.mergeHistory(btc.id(), List.of(point(START - 1, 99.9)));
        backup(START - 4_000L, START - 4_000L, 1, 100.5); // a live tick, fresh at the start
        store.pinBoundary(shadow.id(), START, Px.encode(new BigDecimal("100.5"))); // the previous round's close, standing for it

        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("kraken");
    }

    @Test
    void whenNeitherVenueHasAnythingAtTheStartTheRoundIsUndecidedAtTheOpenAndDecidedLaterByHealingThePrimaryFirst() {
        primary(START + 30_000L, END + 3_000L, 3_000L, 100.0);
        backup(START + 40_000L, END + 3_000L, 3_000L, 100.5);
        coinbase.history = m -> List.of(point(START - 20_000L, 99.5), point(START - 1, 99.8));
        kraken.history = m -> List.of(point(START - 20_000L, 99.6), point(START - 1, 99.9));

        assertThat(prices.openRound(btc, window(btc), START + 1_000)).as("nothing to price the start with").isEmpty();
        assertThat(prices.openRound(btc, window(btc), START + 2_000)).as("and opening asks no history").isEmpty();
        assertThat(coinbase.historyAsked).isEmpty();
        assertThat(kraken.historyAsked).isEmpty();
        assertThat(venueStore.rows).isEmpty();

        now.set(END + 1_000);
        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).as("the settlement heals the start, the primary first").isEqualTo("coinbase");
        assertThat(data.open()).isEqualByComparingTo("99.80");
        assertThat(coinbase.historyAsked).containsExactly("spot_BTC");
        assertThat(kraken.historyAsked).as("the backup is never asked").isEmpty();
    }

    @Test
    void theChoiceIsWrittenOnceNoMatterHowOftenTheRoundIsOpened() {
        primary(START - 2_000, END, 3_000, 100.0);

        for (int i = 0; i < 5; i++) {
            assertThat(prices.openRound(btc, window(btc), START + 500 + i * 1_000L)).contains("coinbase");
        }

        assertThat(venueStore.inserts).hasSize(1);
    }

    @Test
    void aMarketWithNoBackupHasNoVenueNothingIsWrittenAndItsPricesCarryNone() {
        for (long at = START - 2_000; at <= END + 3_000; at += 3_000) {
            store.append("coinbase", at, Map.of(plain.id(), new BigDecimal("2700.5")));
        }

        assertThat(prices.openRound(plain, window(plain), START + 500)).isEmpty();
        assertThat(prices.decidedVenue(plain, window(plain))).isEmpty();
        RoundPriceData data = prices.resolve(plain, window(plain));

        assertThat(data.venue()).isNull();
        assertThat(venueStore.inserts).isEmpty();
        assertThat(venues.size()).isZero();
        assertThat(prices.soFar(plain, window(plain), START + 30_000).orElseThrow().venue()).isNull();
    }

    // ------------------------------------------------------------------ waiting for a start to be priced

    @Test
    void whenNoVenueCanPriceTheStartTheRoundWaitsAndSaysWhy() {
        primary(START + 30_000, END + 3_000, 3_000, 100.0); // nothing at or before the start on either side
        backup(START + 40_000, END + 3_000, 3_000, 100.5);
        now.set(END + 1_000);

        assertThat(prices.openRound(btc, window(btc), START + 1_000)).isEmpty();
        assertThat(venueStore.rows).isEmpty();
        assertThat(prices.decidedVenue(btc, window(btc))).isEmpty();
        assertThat(prices.soFar(btc, window(btc), START + 20_000)).as("no venue, no price of the running round").isEmpty();

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("no exchange can price the start").hasMessageContaining("coinbase")
                .hasMessageContaining("kraken");
        assertThat(venueStore.rows).as("nothing is decided on a failed attempt").isEmpty();
    }

    @Test
    void theFirstVenueThatCanPriceTheStartLaterIsTakenAndThePrimaryComesFirst() {
        primary(START + 30_000, END + 3_000, 3_000, 100.0);
        backup(START + 40_000, END + 3_000, 3_000, 100.5);
        now.set(END + 1_000);
        // both venues' histories can give the start; the primary is asked first and answers, so the backup is never asked
        coinbase.history = m -> List.of(point(START - 20_000, 99.5), point(START - 1, 99.8));
        kraken.history = m -> List.of(point(START - 20_000, 99.6), point(START - 1, 99.9));

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).isEqualTo("coinbase");
        assertThat(data.open()).as("the healed price of the primary at the start").isEqualByComparingTo("99.80");
        assertThat(coinbase.historyAsked).containsExactly("spot_BTC");
        assertThat(kraken.historyAsked).isEmpty();
        assertThat(venueStore.rows.get(window(btc).roundId()).venue()).isEqualTo("coinbase");
    }

    @Test
    void whenOnlyTheBackupCanBeHealedTheRoundIsPricedThere() {
        primary(START + 30_000, END + 3_000, 3_000, 100.0);
        backup(START + 25_000, END + 3_000, 3_000, 100.5); // the first tick is within the gap rule of the healed start
        now.set(END + 1_000);
        coinbase.historyFailure = new IOException("Coinbase is down");
        kraken.history = m -> List.of(point(START - 20_000, 99.6), point(START - 1, 99.9));

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).isEqualTo("kraken");
        assertThat(data.open()).isEqualByComparingTo("99.90");
        assertThat(coinbase.historyAsked).as("tried first").containsExactly("spot_BTC");
        assertThat(kraken.historyAsked).containsExactly("spot_BTC~kraken");
        assertThat(venueStore.rows.get(window(btc).roundId()).venue()).isEqualTo("kraken");
    }

    @Test
    void aStartThatNeitherHistoryCanHealIsAHoleAndTheRoundStaysWithoutAVenue() {
        primary(START + 30_000, END + 3_000, 3_000, 100.0);
        backup(START + 40_000, END + 3_000, 3_000, 100.5);
        now.set(END + 1_000);
        coinbase.historyFailure = new IOException("down");
        kraken.historyFailure = new IOException("down too");

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);

        assertThat(venueStore.rows).isEmpty();
        // the scheduler retries; each venue is asked once per cooldown, not once per retry
        long asked = coinbase.historyAsked.size() + kraken.historyAsked.size();
        now.addAndGet(5_000);
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(coinbase.historyAsked.size() + kraken.historyAsked.size()).isEqualTo(asked);
    }

    @Test
    void aVenueThatWasDecidedLateOnBecauseNothingCouldPriceTheStartSettlesWithItsOwnTicksOnly() {
        primary(START + 30_000, END + 3_000, 3_000, 100.0);
        now.set(END + 1_000);
        coinbase.history = m -> List.of(point(START - 1, 99.8));

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).isEqualTo("coinbase");
        assertThat(data.close()).isEqualByComparingTo("100.00");
        assertThat(data.path()).isNotEmpty();
    }

    // ------------------------------------------------------------------ a decided round is never moved

    @Test
    void aRoundDecidedOnThePrimaryIsNeverSettledFromTheBackupWhenThePrimaryCannotPriceIt() {
        primary(START - 2_000, START + 20_000, 3_000, 100.0); // the primary dies 20 s into the round
        backup(START - 2_000, END + 3_000, 3_000, 100.5);
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");
        now.set(END + 1_000);

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("no price for spot_BTC " + window(btc).roundId());

        assertThat(prices.decidedVenue(btc, window(btc))).as("not switched").contains("coinbase");
        assertThat(venueStore.rows.get(window(btc).roundId()).venue()).isEqualTo("coinbase");
        assertThat(kraken.historyAsked).as("the backup is not asked for a round that is not its own").isEmpty();
        assertThat(venueStore.inserts).hasSize(1);
    }

    @Test
    void aRoundDecidedOnTheBackupIsNeverSettledFromThePrimaryWhenThePrimaryComesBack() {
        primary(START - 30_000, START - 20_000, 1_000, 100.0);
        backup(START - 2_000, START + 20_000, 3_000, 100.5); // the backup dies 20 s into the round; the primary recovers
        primary(START + 5_000, END + 3_000, 3_000, 100.0);
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("kraken");
        now.set(END + 1_000);

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("no price for spot_BTC~kraken");

        assertThat(prices.decidedVenue(btc, window(btc))).contains("kraken");
        assertThat(coinbase.historyAsked).isEmpty();
    }

    @Test
    void theChoiceSurvivesARestartEvenIfTheVenuesLookDifferentNow() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        backup(START - 2_000, END + 3_000, 3_000, 100.5);
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");

        // a new process: the tick rings come back from the snapshot rows, the decisions from the table — and by then the
        // primary's ticks are gone from the rings and only the backup has any
        TickStore restarted = Fixtures.tickStore(now::get);
        restarted.append("kraken", START - 2_000, Map.of(shadow.id(), new BigDecimal("100.5")));
        RoundVenues reloaded = new RoundVenues(venueStore, config.retentionMs(), now::get);
        reloaded.load();
        LiveMarketPrices afterARestart = prices(restarted, reloaded, List.of(coinbase, kraken));

        assertThat(afterARestart.decidedVenue(btc, window(btc))).contains("coinbase");
        assertThat(afterARestart.openRound(btc, window(btc), START + 90_000L)).contains("coinbase");
        assertThat(venueStore.inserts).hasSize(1);
    }

    @Test
    void aRestartThatLostTheMemoryOfTheDecisionsStillGetsTheStoredOneBack() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");
        TickStore restarted = Fixtures.tickStore(now::get);
        restarted.append("kraken", START - 2_000, Map.of(shadow.id(), new BigDecimal("100.5")));
        LiveMarketPrices noMemory = prices(restarted, new RoundVenues(venueStore, config.retentionMs(), now::get),
                List.of(coinbase, kraken)); // never loaded

        assertThat(noMemory.decidedVenue(btc, window(btc))).as("memory only: nothing yet").isEmpty();
        // by the rings alone it would be Kraken, but the table's row decides: whoever wrote first decided
        assertThat(noMemory.openRound(btc, window(btc), START + 90_000L)).contains("coinbase");
        assertThat(venueStore.rows.get(window(btc).roundId()).venue()).isEqualTo("coinbase");
        assertThat(venueStore.rows.get(window(btc).roundId()).decidedAtMs()).as("not written twice").isEqualTo(START + 500);
        assertThat(noMemory.decidedVenue(btc, window(btc))).contains("coinbase");
    }

    // ------------------------------------------------------------------ two series that start together

    @Test
    void theRoundsOfBothSeriesThatStartTogetherAgree() {
        primary(START - 2_000, START + 900_000L, 3_000, 100.0);
        backup(START - 2_000, START + 900_000L, 3_000, 100.5);
        RoundWindow hourly = window(btc, Interval.HOURLY, START, END);
        RoundWindow daily = window(btc, Interval.DAILY, START, START + 900_000L);

        Optional<String> a = prices.openRound(btc, daily, START + 500);
        Optional<String> b = prices.openRound(btc, hourly, START + 600);

        assertThat(a).contains("coinbase");
        assertThat(b).isEqualTo(a);
        assertThat(venueStore.rows).containsOnlyKeys(hourly.roundId(), daily.roundId());
        assertThat(venueStore.rows.values()).extracting(RoundVenueStore.Decision::venue).containsOnly("coinbase");
    }

    @Test
    void aSecondRoundThatStartsTogetherTakesWhatTheFirstDecidedWhateverTheRingsSayByThen() {
        // the primary is dead at the start: the first round goes to the backup; then a live tick of the primary appears at
        // a time before the start (a late arrival: a restored row, say) — by the rings alone the primary would win now
        backup(START - 2_000, END + 3_000, 3_000, 100.5);
        RoundWindow hourly = window(btc, Interval.HOURLY, START, END);
        RoundWindow daily = window(btc, Interval.DAILY, START, START + 900_000L);
        assertThat(prices.openRound(btc, daily, START + 500)).contains("kraken");
        primary(START - 3_000, START - 1_000, 1_000, 100.0);

        Optional<String> second = prices.openRound(btc, hourly, START + 800);

        assertThat(second).as("the rounds that start together do not disagree").contains("kraken");
        assertThat(venueStore.rows.get(hourly.roundId()).venue()).isEqualTo("kraken");
        // a round that starts at another moment decides for itself, by the rings
        RoundWindow later = window(btc, Interval.HOURLY, END, END + STEP);
        primary(END - 2_000, END + 100_000L, 3_000, 100.0);
        backup(END - 2_000, END + 100_000L, 3_000, 100.5);
        assertThat(prices.openRound(btc, later, END + 500)).contains("coinbase");
    }

    // ------------------------------------------------------------------ settled from its own venue

    @Test
    void aRoundOnTheBackupIsSettledFromTheBackupsRingAlone() {
        // the primary has no tick at the start (so the round opens on the backup) but trades through the round at 90;
        // the backup's prices are 100 → 101 in steps
        primary(START + 10_000, END + 3_000, 3_000, 90.0);
        for (long at = START - 2_000; at <= END + 3_000; at += 3_000) {
            backup(at, at, 1, 100.0 + Math.max(0, at - START) / 180_000.0);
        }
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("kraken");
        now.set(END + 1_000);

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).isEqualTo("kraken");
        assertThat(data.open()).as("Kraken's price at the start, not Coinbase's").isEqualByComparingTo("100.00");
        assertThat(data.close()).as("the last Kraken tick at or before the end: 100 + 178/180").isEqualByComparingTo("100.99");
        assertThat(data.path()).allSatisfy(c -> {
            assertThat(c.low()).isGreaterThanOrEqualTo(new BigDecimal("99.99"));
            assertThat(c.high()).isLessThanOrEqualTo(new BigDecimal("101.01"));
        });
        // the pins of the settled round are in the backup's ring, and the primary's has none
        assertThat(store.latestPin(shadow.id())).hasValue(END);
        assertThat(store.latestPin(btc.id())).isEmpty();
    }

    @Test
    void aRoundOnThePrimaryIsSettledFromThePrimarysRingAlone() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        backup(START - 2_000, END + 3_000, 3_000, 80.0);
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");
        now.set(END + 1_000);

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.venue()).isEqualTo("coinbase");
        assertThat(data.open()).isEqualByComparingTo("100.00");
        assertThat(data.close()).isEqualByComparingTo("100.00");
        assertThat(store.latestPin(btc.id())).hasValue(END);
        assertThat(store.latestPin(shadow.id())).isEmpty();
    }

    @Test
    void theRunningPricesAreFromTheRoundsVenueToo() {
        primary(START - 2_000, START + 30_000, 3_000, 90.0);
        backup(START - 2_000, START + 30_000, 3_000, 100.5);
        primary(START - 20_000, START - 17_000, 1_000, 90.0); // stale at the start
        // (the ring has both: the earlier ticks at 90 are older than 15 s at the start, the ones from START - 2 s are fresh)
        assertThat(prices.soFar(btc, window(btc), START + 20_000)).as("undecided: no price").isEmpty();
        assertThat(prices.openRound(btc, window(btc), START + 500)).contains("coinbase");

        Optional<RoundPriceData> running = prices.soFar(btc, window(btc), START + 20_000);

        assertThat(running).isPresent();
        assertThat(running.get().venue()).isEqualTo("coinbase");
        assertThat(running.get().open()).isEqualByComparingTo("90.00");
    }

    @Test
    void theNextRoundOpensAtTheOtherVenuesPriceAfterASwitchAndAtTheClosedPriceWithinAVenue() {
        // round 1 runs on the backup (the primary has no tick until the last seconds of it); the primary is back at the
        // boundary, so round 2 opens on the primary, at the primary's price — 0.3 from where round 1 closed on Kraken:
        // the basis between the two venues. The close of a round and the open of the next are one price within a venue.
        backup(START - 2_000, END + 2 * STEP, 3_000, 100.3);
        primary(END - 4_000, END + 2 * STEP, 3_000, 100.0);
        RoundWindow first = window(btc);
        RoundWindow second = window(btc, Interval.HOURLY, END, END + STEP);
        RoundWindow third = window(btc, Interval.HOURLY, END + STEP, END + 2 * STEP);
        assertThat(prices.openRound(btc, first, START + 500)).contains("kraken");
        assertThat(prices.openRound(btc, second, END + 500)).as("Coinbase has a live tick at the boundary").contains("coinbase");
        assertThat(prices.openRound(btc, third, END + STEP + 500)).contains("coinbase");
        now.set(END + 2 * STEP + 1_000);

        RoundPriceData one = prices.resolve(btc, first);
        RoundPriceData two = prices.resolve(btc, second);
        RoundPriceData three = prices.resolve(btc, third);

        assertThat(one.venue()).isEqualTo("kraken");
        assertThat(two.venue()).isEqualTo("coinbase");
        assertThat(one.close()).isEqualByComparingTo("100.30");
        assertThat(two.open()).as("Coinbase's price at the boundary").isEqualByComparingTo("100.00");
        assertThat(two.open()).as("not the close of the round before it, on another exchange")
                .isNotEqualByComparingTo(one.close());
        assertThat(three.open()).as("Р-40 holds within a venue").isEqualByComparingTo(two.close());
    }

    // ------------------------------------------------------------------ a venue that is off

    @Test
    void aVenueWithNoSourceGivesNoPriceWhateverItsRingStillHolds() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0); // restored ticks, fresh at the start by their time ...
        backup(START - 2_000, END + 3_000, 3_000, 100.5);
        LiveMarketPrices coinbaseOff = prices(store, new RoundVenues(new FakeRoundVenueStore(), config.retentionMs(), now::get),
                List.of(kraken)); // ... but herd.prices.disabled-venues=coinbase: its source does not exist

        assertThat(coinbaseOff.openRound(btc, window(btc), START + 500)).contains("kraken");
    }

    @Test
    void withBothVenuesOffNothingIsDecided() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        backup(START - 2_000, END + 3_000, 3_000, 100.5);
        LiveMarketPrices allOff = prices(store, new RoundVenues(new FakeRoundVenueStore(), config.retentionMs(), now::get),
                List.of());

        assertThat(allOff.openRound(btc, window(btc), START + 500)).isEmpty();
        assertThatThrownBy(() -> allOff.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
    }

    // ------------------------------------------------------------------ what is shown

    @Test
    void theLobbyShowsThePrimarysPriceWhileItIsFreshAndSaysNoVenue() {
        now.set(START + 100_000L);
        primary(START + 90_000L, START + 99_000L, 3_000, 100.0);
        backup(START + 90_000L, START + 99_000L, 3_000, 100.5);

        PriceQuote quote = prices.latest(btc).orElseThrow();

        assertThat(quote.raw()).isEqualByComparingTo("100.0");
        assertThat(quote.venue()).isNull();
        assertThat(quote.stale()).isFalse();
    }

    @Test
    void theLobbyShowsTheBackupsPriceWhenThePrimaryHasNoFreshTickAndSaysWhichVenue() {
        now.set(START + 100_000L);
        primary(START + 60_000L, START + 70_000L, 3_000, 100.0); // 30 s old
        backup(START + 90_000L, START + 99_000L, 3_000, 100.5);

        PriceQuote quote = prices.latest(btc).orElseThrow();

        assertThat(quote.raw()).isEqualByComparingTo("100.5");
        assertThat(quote.venue()).isEqualTo("kraken");
        assertThat(quote.stale()).isFalse();
        assertThat(quote.asOfMs()).isEqualTo(START + 99_000L);
    }

    @Test
    void withNeitherFreshTheLobbyShowsTheNewerOfTheTwoAsStaleAndTheTiesGoToThePrimary() {
        now.set(START + 200_000L);
        primary(START + 60_000L, START + 70_000L, 3_000, 100.0);
        backup(START + 90_000L, START + 99_000L, 3_000, 100.5);

        PriceQuote newer = prices.latest(btc).orElseThrow();

        assertThat(newer.stale()).isTrue();
        assertThat(newer.venue()).as("the newer tick is Kraken's").isEqualTo("kraken");
        assertThat(newer.raw()).isEqualByComparingTo("100.5");

        store.append("coinbase", START + 99_000L, Map.of(btc.id(), new BigDecimal("100.1"))); // the same instant
        PriceQuote tie = prices.latest(btc).orElseThrow();
        assertThat(tie.venue()).as("a tie is the primary's").isNull();
        assertThat(tie.raw()).isEqualByComparingTo("100.1");
        assertThat(tie.stale()).isTrue();
    }

    @Test
    void theLobbyShowsWhicheverVenueHasAnyTickAndNothingWhenNeitherDoes() {
        now.set(START + 100_000L);
        assertThat(prices.latest(btc)).isEmpty();

        backup(START + 90_000L, START + 99_000L, 3_000, 100.5);
        assertThat(prices.latest(btc).orElseThrow().venue()).isEqualTo("kraken");
    }

    @Test
    void theQuoteOfAMarketWithNoBackupIsItsOwnWhateverThePipelineKnowsOfShadows() {
        now.set(START + 100_000L);
        store.append("coinbase", START + 99_000L, Map.of(plain.id(), new BigDecimal("2700.5")));

        PriceQuote quote = prices.latest(plain).orElseThrow();

        assertThat(quote.venue()).isNull();
        assertThat(quote.raw()).isEqualByComparingTo("2700.5");
    }

    @Test
    void theChartOfAMarketIsItsPrimarysSeriesAndTheBackupsPointsAreNotMixedIn() {
        primary(START, START + 9_000, 3_000, 100.0);
        backup(START, START + 9_000, 3_000, 100.5);

        List<PricePoint> series = prices.series(btc.id(), START, START + 10_000);

        assertThat(series).hasSize(4);
        assertThat(series).extracting(p -> p.raw().stripTrailingZeros().toPlainString()).containsOnly("100");
        assertThat(prices.series(shadow.id(), START, START + 10_000)).as("a ring of its own, for whoever asks for it by key")
                .hasSize(4);
    }

    // ------------------------------------------------------------------ the database

    @Test
    void aChoiceThatCannotBeWrittenIsNotMadeAndTheNextTryDoesIt() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        venueStore.failWrites = true;

        assertThatThrownBy(() -> prices.openRound(btc, window(btc), START + 500)).isInstanceOf(IllegalStateException.class);
        assertThat(prices.decidedVenue(btc, window(btc))).isEmpty();
        assertThat(prices.soFar(btc, window(btc), START + 10_000)).isEmpty();

        venueStore.failWrites = false;
        assertThat(prices.openRound(btc, window(btc), START + 1_500)).contains("coinbase");
    }

    @Test
    void aSettlementThatCannotReadOrWriteTheChoiceFailsAsAnyAttemptDoesAndNothingIsSettledOnAGuess() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        now.set(END + 1_000);
        venueStore.failWrites = true;

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(RuntimeException.class);

        assertThat(venueStore.rows).isEmpty();
        assertThat(store.latestPin(btc.id())).as("no pin either: nothing was settled").isEmpty();
    }

    @Test
    void aVenueThatIsNeitherOfTheMarketsTwoCannotBePriced() {
        primary(START - 2_000, END + 3_000, 3_000, 100.0);
        venueStore.put(new RoundVenueStore.Decision(window(btc).roundId(), "spot_BTC", "binance", START));
        now.set(END + 1_000);

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("binance").hasMessageContaining("neither its primary exchange coinbase")
                .hasMessageContaining("backup kraken");
        assertThat(prices.soFar(btc, window(btc), START + 10_000)).isEmpty();
    }

    @Test
    void theWindowOfAnotherMarketIsRefusedLikeEverywhereElse() {
        assertThatThrownBy(() -> prices.openRound(btc, window(plain), START)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prices.decidedVenue(btc, window(plain))).isInstanceOf(IllegalArgumentException.class);
    }
}
