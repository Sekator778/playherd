package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link SnapshotStore} over plain JDBC like the rest of the server's repositories ({@link Db}).
 * Rows are replayed in one-hour slices, so the memory a restart needs stays bounded however much
 * the table holds (about 100 000 rows for the 48 hours of a live DAILY series).
 */
@ApplicationScoped
public class JdbcSnapshotStore implements SnapshotStore {

    static final long SLICE_MS = 3_600_000L;

    @Inject
    Db db;

    @Override
    public void save(String source, long atMs, Map<String, BigDecimal> prices) {
        Map<String, String> json = new LinkedHashMap<>();
        prices.forEach((marketId, raw) -> json.put(marketId, raw.toPlainString()));
        db.tx(c -> db.update(c, "INSERT INTO price_snapshots (source, at_ms, prices) VALUES (?, ?, ?) "
                + "ON CONFLICT (source, at_ms) DO UPDATE SET prices = EXCLUDED.prices",
                source, atMs, Db.jsonb(json)));
    }

    @Override
    public int replaySince(long fromMs, Consumer<Snapshot> sink) {
        Long newest = db.read(c -> db.queryOne(c, "SELECT MAX(at_ms) AS newest FROM price_snapshots",
                rs -> rs.getObject("newest") == null ? null : rs.getLong("newest")));
        if (newest == null) {
            return 0;
        }
        int rows = 0;
        for (long start = fromMs; start <= newest; start += SLICE_MS) {
            long sliceFrom = start;
            long sliceTo = start + SLICE_MS;
            List<Snapshot> slice = db.read(c -> db.queryList(c,
                    "SELECT source, at_ms, prices FROM price_snapshots WHERE at_ms >= ? AND at_ms < ? "
                            + "ORDER BY at_ms, source",
                    rs -> new Snapshot(rs.getString("source"), rs.getLong("at_ms"),
                            readPrices(rs.getString("prices"))),
                    sliceFrom, sliceTo));
            slice.forEach(sink);
            rows += slice.size();
        }
        return rows;
    }

    @Override
    public int pruneBefore(long cutoffMs) {
        return db.tx(c -> db.update(c, "DELETE FROM price_snapshots WHERE at_ms < ?", cutoffMs));
    }

    /** {@code {"BTC":"84629.0"}} → map; a value that is not a number is skipped, not fatal. */
    private Map<String, BigDecimal> readPrices(String json) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        try {
            JsonNode root = db.json().readTree(json);
            for (Map.Entry<String, JsonNode> e : root.properties()) {
                BigDecimal raw = Dec.of(e.getValue());
                if (raw != null) {
                    out.put(e.getKey(), raw);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("price_snapshots row is not valid JSON: " + json, e);
        }
        return out;
    }
}
