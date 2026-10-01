package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** An in-memory {@link SnapshotStore}: the rows of {@code price_snapshots} without a database. */
public class FakeSnapshotStore implements SnapshotStore {

    public final List<Snapshot> rows = new ArrayList<>();
    /** Set to make every {@link #save} fail like a database that is down. */
    public boolean failSaves;

    @Override
    public synchronized void save(String source, long atMs, Map<String, BigDecimal> prices) {
        if (failSaves) {
            throw new IllegalStateException("db is down");
        }
        rows.removeIf(r -> r.source().equals(source) && r.atMs() == atMs);
        rows.add(new Snapshot(source, atMs, new LinkedHashMap<>(prices)));
    }

    @Override
    public synchronized int replaySince(long fromMs, Consumer<Snapshot> sink) {
        List<Snapshot> matching = rows.stream().filter(r -> r.atMs() >= fromMs)
                .sorted(Comparator.comparingLong(Snapshot::atMs)).toList();
        matching.forEach(sink);
        return matching.size();
    }

    @Override
    public synchronized int pruneBefore(long cutoffMs) {
        int before = rows.size();
        rows.removeIf(r -> r.atMs() < cutoffMs);
        return before - rows.size();
    }
}
