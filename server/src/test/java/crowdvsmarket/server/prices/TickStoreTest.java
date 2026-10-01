package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link TickStore} without a database: what a poll stores, what it refuses, the one snapshot row
 * per poll, recovery after a restart and the retention. (The same table against a real Postgres is
 * {@link TickStoreRestartTest}.)
 */
class TickStoreTest {

    private static final long RETENTION = 2_400_000L; // 2 x 900 s + 10 min, the stand's
    private static final long T0 = 1_790_880_000_000L;

    private final AtomicLong now = new AtomicLong(T0);
    private FakeSnapshotStore snapshots;
    private TickStore store;

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, RETENTION, now::get);
    }

    private static Map<String, BigDecimal> prices(Object... idAndPrice) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndPrice.length; i += 2) {
            m.put((String) idAndPrice[i], new BigDecimal(idAndPrice[i + 1].toString()));
        }
        return m;
    }

    @Test
    void aPollBecomesOneTickPerMarketAndExactlyOneSnapshotRow() {
        store.append("hyperliquid", T0, prices("BTC", "84629.0", "ETH", "2696.8"));

        assertThat(store.last("BTC")).hasValueSatisfying(p -> {
            assertThat(p.atMs()).isEqualTo(T0);
            assertThat(p.raw()).isEqualByComparingTo("84629.0");
        });
        assertThat(store.last("ETH")).isPresent();
        assertThat(snapshots.rows).hasSize(1);
        assertThat(snapshots.rows.get(0).source()).isEqualTo("hyperliquid");
        assertThat(snapshots.rows.get(0).atMs()).isEqualTo(T0);
        assertThat(snapshots.rows.get(0).prices()).containsOnlyKeys("BTC", "ETH");
        assertThat(store.feedOf("BTC")).contains("hyperliquid");
    }

    @Test
    void aNonPositiveOrAbsurdPriceIsDroppedAndNeverStoredNorSnapshotted() {
        store.append("hyperliquid", T0, prices("BTC", "84629.0", "ETH", "0", "SOL", "-3", "DOGE", "999999999999"));

        assertThat(store.last("BTC")).isPresent();
        assertThat(store.last("ETH")).isEmpty();
        assertThat(store.last("SOL")).isEmpty();
        assertThat(store.last("DOGE")).isEmpty();
        assertThat(snapshots.rows.get(0).prices()).containsOnlyKeys("BTC");
    }

    @Test
    void seedHistoryAddsOnlyWhatIsOlderThanEveryTickAndNeverOverwritesAndWritesNoRow() {
        store.append("hyperliquid", T0, prices("BTC", "90000"));
        List<PricePoint> history = List.of(new PricePoint(T0 - 20_000, new BigDecimal("84000")),
                new PricePoint(T0 - 10_000, new BigDecimal("84010")), new PricePoint(T0, new BigDecimal("1")),
                new PricePoint(T0 + 10_000, new BigDecimal("2")), new PricePoint(T0 - 5_000, new BigDecimal("0")));

        int added = store.seedHistory("BTC", "hyperliquid:late", history);

        assertThat(added).isEqualTo(2); // the ones before the oldest tick; the zero price is no price
        assertThat(store.series("BTC", 0, Long.MAX_VALUE)).extracting(PricePoint::atMs)
                .containsExactly(T0 - 20_000, T0 - 10_000, T0);
        assertThat(store.last("BTC").orElseThrow().raw()).isEqualByComparingTo("90000");
        assertThat(store.feedOf("BTC")).contains("hyperliquid"); // the feed that delivered a live tick is kept
        assertThat(snapshots.rows).hasSize(1); // only the poll wrote a row: a restart asks the source again
    }

    @Test
    void seedHistoryIntoAnEmptyRingTakesEverythingAndNamesTheFeed() {
        int added = store.seedHistory("ETH", "hyperliquid", List.of(new PricePoint(T0 - 10_000, new BigDecimal("2697")),
                new PricePoint(T0, new BigDecimal("2698"))));

        assertThat(added).isEqualTo(2);
        assertThat(store.feedOf("ETH")).contains("hyperliquid");
        assertThat(store.seedHistory("ETH", "hyperliquid", List.of())).isZero();
        assertThat(store.seedHistory("SOL", "hyperliquid", List.of())).isZero();
        assertThat(store.feedOf("SOL")).isEmpty(); // nothing seeded, nothing claimed
    }

    @Test
    void pinBoundaryMakesATickAtExactlyTheTimeUnlessOneIsThereAndWritesNoRow() {
        store.append("hyperliquid", T0, prices("BTC", "84000"));
        int rows = snapshots.rows.size();

        store.pinBoundary("BTC", T0 + 10_000, Px.encode(new BigDecimal("84000")));
        store.pinBoundary("BTC", T0, Px.encode(new BigDecimal("1"))); // a tick is there: the pin yields to it
        store.pinBoundary("ETH", T0, Px.encode(new BigDecimal("2697"))); // a market with no ring yet gets one

        assertThat(store.series("BTC", 0, Long.MAX_VALUE)).extracting(PricePoint::atMs).containsExactly(T0, T0 + 10_000);
        assertThat(store.series("BTC", T0, T0 + 1).get(0).raw()).isEqualByComparingTo("84000");
        assertThat(store.last("ETH").orElseThrow().raw()).isEqualByComparingTo("2697");
        assertThat(snapshots.rows).hasSize(rows); // memory only, like a history merge
    }

    @Test
    void tickAfterIsTheFirstTickLaterThanTheTime() {
        store.append("hyperliquid", T0, prices("BTC", "84000"));
        store.append("hyperliquid", T0 + 3_000, prices("BTC", "84001"));

        assertThat(store.tickAfter("BTC", T0)[0]).isEqualTo(T0 + 3_000);
        assertThat(store.tickAfter("BTC", T0 - 1)[0]).isEqualTo(T0);
        assertThat(store.tickAfter("BTC", T0 + 3_000)).isNull();
        assertThat(store.tickAfter("NOPE", 0)).isNull();
    }

    @Test
    void aPollOfOnlyBadPricesWritesNoRow() {
        store.append("hyperliquid", T0, prices("ETH", "0"));

        assertThat(snapshots.rows).isEmpty();
    }

    @Test
    void aSnapshotFailureNeverCostsTheLivePrice() {
        snapshots.failSaves = true;

        store.append("hyperliquid", T0, prices("BTC", "84629.0"));
        store.append("hyperliquid", T0 + 3_000, prices("BTC", "84630.0"));

        assertThat(store.last("BTC")).hasValueSatisfying(p -> assertThat(p.raw()).isEqualByComparingTo("84630.0"));
        assertThat(store.size("BTC")).isEqualTo(2);
    }

    @Test
    void seriesIsClosedAtTheStartAndOpenAtTheEnd() {
        for (int i = 0; i < 10; i++) {
            store.append("hyperliquid", T0 + i * 3_000L, prices("BTC", 84_000 + i));
        }

        List<PricePoint> series = store.series("BTC", T0 + 6_000, T0 + 15_000);

        assertThat(series).extracting(PricePoint::atMs).containsExactly(T0 + 6_000, T0 + 9_000, T0 + 12_000);
        assertThat(store.series("NOPE", 0, Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void floorIsTheBoundaryPriceSource() {
        store.append("hyperliquid", T0, prices("BTC", "100"));
        store.append("hyperliquid", T0 + 3_000, prices("BTC", "101"));

        assertThat(store.floor("BTC", T0 + 2_999)).hasValueSatisfying(p -> assertThat(p.raw()).isEqualByComparingTo("100"));
        assertThat(store.floor("BTC", T0 + 3_000)).hasValueSatisfying(p -> assertThat(p.raw()).isEqualByComparingTo("101"));
        assertThat(store.floor("BTC", T0 - 1)).isEmpty();
    }

    @Test
    void historyFillsAHoleAndNeverOverwritesALiveTick() {
        store.append("hyperliquid", T0, prices("BTC", "100"));
        store.append("hyperliquid", T0 + 30_000, prices("BTC", "103"));

        int added = store.mergeHistory("BTC", List.of(new PricePoint(T0 + 10_000, new BigDecimal("101")),
                new PricePoint(T0 + 20_000, new BigDecimal("102")), new PricePoint(T0 + 30_000, new BigDecimal("999")),
                new PricePoint(T0 + 25_000, new BigDecimal("-1"))));

        assertThat(added).isEqualTo(2);
        assertThat(store.series("BTC", T0, T0 + 31_000)).extracting(p -> p.raw().intValue())
                .containsExactly(100, 101, 102, 103);
        assertThat(snapshots.rows).hasSize(2); // history is not a poll: no row
    }

    @Test
    void change24hIsKeptPerMarketAndClearedWithNull() {
        store.setChange24h("BTC", 0.0098);
        assertThat(store.change24h("BTC")).isEqualTo(0.0098);

        store.setChange24h("BTC", null);
        assertThat(store.change24h("BTC")).isNull();
        store.setChange24h("ETH", Double.NaN);
        assertThat(store.change24h("ETH")).isNull();
    }

    @Test
    void restoreRebuildsTheRingsFromTheRowsOfTheRetention() {
        store.append("hyperliquid", T0 - RETENTION - 60_000, prices("BTC", "1")); // older than the buffer
        store.append("hyperliquid", T0 - 60_000, prices("BTC", "84600", "ETH", "2690"));
        store.append("hyperliquid:xyz", T0 - 30_000, prices("xyz_NVDA", "231.7"));
        store.append("hyperliquid", T0, prices("BTC", "84629.0"));

        TickStore restarted = new TickStore(snapshots, RETENTION, now::get);
        int rows = restarted.restore();

        assertThat(rows).isEqualTo(3);
        assertThat(restarted.series("BTC", 0, Long.MAX_VALUE)).extracting(p -> p.raw().intValue())
                .containsExactly(84600, 84629);
        assertThat(restarted.last("ETH")).isPresent();
        assertThat(restarted.last("xyz_NVDA")).hasValueSatisfying(p -> assertThat(p.atMs()).isEqualTo(T0 - 30_000));
        assertThat(restarted.feedOf("xyz_NVDA")).contains("hyperliquid:xyz");
        assertThat(restarted.feedOf("BTC")).contains("hyperliquid");
    }

    @Test
    void pruneDropsTicksAndRowsOlderThanTheRetention() {
        store.append("hyperliquid", T0, prices("BTC", "100"));
        store.append("hyperliquid", T0 + 3_000, prices("BTC", "101"));
        now.set(T0 + RETENTION + 1_000); // the first tick is now just out of the buffer, the second is in

        store.prune();

        assertThat(store.series("BTC", 0, Long.MAX_VALUE)).extracting(PricePoint::atMs).containsExactly(T0 + 3_000);
        assertThat(snapshots.rows).extracting(SnapshotStore.Snapshot::atMs).containsExactly(T0 + 3_000);
    }

    @Test
    void readersAndTheWriterShareTheStoreSafely() throws Exception {
        TickStore shared = new TickStore(new SnapshotStore() {
            @Override
            public void save(String source, long atMs, Map<String, BigDecimal> prices) {
            }

            @Override
            public int replaySince(long fromMs, java.util.function.Consumer<Snapshot> sink) {
                return 0;
            }

            @Override
            public int pruneBefore(long cutoffMs) {
                return 0;
            }
        }, RETENTION, now::get);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                shared.append("hyperliquid", T0 + i * 1_000L, prices("BTC", 80_000 + i % 1_000));
            }
        });
        writer.start();
        while (writer.isAlive()) {
            shared.last("BTC");
            shared.series("BTC", T0, T0 + 5_000_000L);
            TickRing.Window w = shared.window("BTC", T0 + 1_000_000L, T0 + 2_000_000L);
            for (int i = 1; i < w.atMs().length; i++) {
                assertThat(w.atMs()[i]).isGreaterThan(w.atMs()[i - 1]);
            }
        }
        writer.join();

        assertThat(shared.size("BTC")).isEqualTo(20_000);
    }
}
