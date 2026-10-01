package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Restart recovery against the real {@code price_snapshots} table (V7, Dev Services Postgres): a
 * poll is one row, a new {@link TickStore} over the same table rebuilds the rings, rows older than
 * the retention are neither restored nor kept. The in-memory rules are {@link TickStoreTest}.
 */
@QuarkusTest
class TickStoreRestartTest {

    private static final long RETENTION = 2_400_000L;
    private static final long T0 = 1_790_880_000_000L;

    @Inject
    JdbcSnapshotStore jdbc;
    @Inject
    Db db;
    @Inject
    TickStore wired;

    private final AtomicLong now = new AtomicLong(T0);

    @BeforeEach
    @AfterEach
    void clean() {
        db.tx(c -> db.update(c, "DELETE FROM price_snapshots"));
    }

    private TickStore newStore(long retention) {
        return new TickStore(jdbc, retention, now::get);
    }

    private long rows() {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM price_snapshots", rs -> rs.getLong("n")));
    }

    private static Map<String, BigDecimal> prices(Object... idAndPrice) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndPrice.length; i += 2) {
            m.put((String) idAndPrice[i], new BigDecimal(idAndPrice[i + 1].toString()));
        }
        return m;
    }

    @Test
    void aPollIsExactlyOneRowWithTheMarketsPricesAsJson() {
        TickStore store = newStore(RETENTION);

        store.append("hyperliquid", T0, prices("BTC", "84629.0", "ETH", "2696.8", "DOGE", "0.094534"));

        assertThat(rows()).isEqualTo(1);
        String json = db.read(c -> db.queryOne(c, "SELECT prices::text AS p FROM price_snapshots WHERE source = ? AND at_ms = ?",
                rs -> rs.getString("p"), "hyperliquid", T0));
        assertThat(parse(json)).containsOnlyKeys("BTC", "ETH", "DOGE");
        assertThat(parse(json).get("BTC")).isEqualTo("84629.0");
        assertThat(parse(json).get("DOGE")).isEqualTo("0.094534");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parse(String json) {
        try {
            return db.json().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theSameSourceAndTimeIsOneRowNotTwo() {
        TickStore store = newStore(RETENTION);

        store.append("hyperliquid", T0, prices("BTC", "84629.0"));
        store.append("hyperliquid", T0, prices("BTC", "84631.0"));
        store.append("polymarket", T0, prices("PM1", "0.5"));

        assertThat(rows()).isEqualTo(2);
        TickStore restarted = newStore(RETENTION);
        restarted.restore();
        assertThat(restarted.last("BTC").orElseThrow().raw()).isEqualByComparingTo("84631.0"); // the later write won
    }

    @Test
    void aRestartedStoreHasTheSameTicksAndFeeds() {
        TickStore before = newStore(RETENTION);
        for (int i = 0; i < 50; i++) {
            before.append("hyperliquid", T0 - 150_000 + i * 3_000L, prices("BTC", 84_000 + i, "ETH", 2_600 + i));
            if (i % 2 == 0) {
                before.append("hyperliquid:xyz", T0 - 150_000 + i * 3_000L + 1, prices("xyz_NVDA", "231." + i));
            }
        }

        TickStore after = newStore(RETENTION);
        int restored = after.restore();

        assertThat(restored).isEqualTo(75);
        for (String market : List.of("BTC", "ETH", "xyz_NVDA")) {
            assertThat(after.series(market, 0, Long.MAX_VALUE)).isEqualTo(before.series(market, 0, Long.MAX_VALUE));
        }
        assertThat(after.feedOf("BTC")).contains("hyperliquid");
        assertThat(after.feedOf("xyz_NVDA")).contains("hyperliquid:xyz");
        assertThat(after.last("BTC").orElseThrow().atMs()).isEqualTo(T0 - 150_000 + 49 * 3_000L);
    }

    @Test
    void rowsOlderThanTheRetentionAreNotRestoredAndGoAtThePrune() {
        TickStore store = newStore(RETENTION);
        store.append("hyperliquid", T0 - RETENTION - 1_000, prices("BTC", "1"));
        store.append("hyperliquid", T0 - RETENTION + 1_000, prices("BTC", "2"));
        store.append("hyperliquid", T0, prices("BTC", "3"));

        TickStore restarted = newStore(RETENTION);
        assertThat(restarted.restore()).isEqualTo(2);
        assertThat(restarted.series("BTC", 0, Long.MAX_VALUE)).extracting(p -> p.raw().intValue()).containsExactly(2, 3);

        restarted.prune();
        assertThat(rows()).isEqualTo(2);
        now.addAndGet(RETENTION); // the middle row is now older than the buffer too
        restarted.prune();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void restoreReadsSeveralHoursOfRowsSliceBySlice() {
        long retention = 10 * 3_600_000L;
        TickStore store = newStore(retention);
        for (int hour = 0; hour < 5; hour++) { // a row every hour and a bit, across more than one slice each
            store.append("hyperliquid", T0 - hour * 4_000_000L, prices("BTC", 84_000 - hour));
        }

        TickStore restarted = newStore(retention);

        assertThat(restarted.restore()).isEqualTo(5);
        assertThat(restarted.size("BTC")).isEqualTo(5);
        assertThat(restarted.last("BTC").orElseThrow().atMs()).isEqualTo(T0);
    }

    @Test
    void anEmptyTableRestoresNothing() {
        assertThat(newStore(RETENTION).restore()).isZero();
    }

    @Test
    void theTickStoreBeanIsWiredToTheTableThroughTheJdbcStore() {
        wired.append("hyperliquid", System.currentTimeMillis(), prices("WIRED_TEST", "12.5"));

        assertThat(rows()).isEqualTo(1);
        assertThat(wired.last("WIRED_TEST")).isPresent();
    }
}
