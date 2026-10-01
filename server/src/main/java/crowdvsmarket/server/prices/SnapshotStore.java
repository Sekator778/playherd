package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code price_snapshots} table (V7): one row per poll of a price source,
 * {@code {"<marketId>": "<raw price>"}}, so that the tick buffer survives a restart
 * (docs/research/markets_architecture.md §3.2 rule 2). An interface so that {@link TickStore} can
 * be exercised without a database; {@link JdbcSnapshotStore} is the real one.
 */
public interface SnapshotStore {

    /** One stored poll. */
    record Snapshot(String source, long atMs, Map<String, BigDecimal> prices) {
    }

    /** Writes one row; a row with the same {@code (source, atMs)} is overwritten. */
    void save(String source, long atMs, Map<String, BigDecimal> prices);

    /** Hands every row with {@code at_ms >= fromMs} to {@code sink}, oldest first; returns how many. */
    int replaySince(long fromMs, Consumer<Snapshot> sink);

    /** Deletes the rows with {@code at_ms < cutoffMs}; returns how many. */
    int pruneBefore(long cutoffMs);
}
