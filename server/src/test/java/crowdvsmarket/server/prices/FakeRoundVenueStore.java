package crowdvsmarket.server.prices;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link RoundVenueStore}: the rows of {@code round_venues} without a database — {@link #insertOnce} keeps the
 * row that was there first, like {@code INSERT … ON CONFLICT DO NOTHING}; every write is counted and recorded, and
 * {@link #failWrites} makes them fail like a database that is down.
 */
public class FakeRoundVenueStore implements RoundVenueStore {

    public final Map<String, Decision> rows = new LinkedHashMap<>();
    /** Every {@link #insertOnce} call, in order, whether it wrote or not. */
    public final List<Decision> inserts = new ArrayList<>();
    public int finds;
    public boolean failWrites;
    public boolean failReads;

    @Override
    public synchronized Decision insertOnce(Decision decision) {
        inserts.add(decision);
        if (failWrites) {
            throw new IllegalStateException("db is down");
        }
        return rows.computeIfAbsent(decision.roundId(), id -> decision);
    }

    @Override
    public synchronized Optional<Decision> find(String roundId) {
        finds++;
        if (failReads) {
            throw new IllegalStateException("db is down");
        }
        return Optional.ofNullable(rows.get(roundId));
    }

    @Override
    public synchronized List<Decision> since(long decidedAtMs) {
        if (failReads) {
            throw new IllegalStateException("db is down");
        }
        return rows.values().stream().filter(d -> d.decidedAtMs() >= decidedAtMs).toList();
    }

    /** A row that was written by somebody else — another process, an earlier run. */
    public synchronized void put(Decision decision) {
        rows.put(decision.roundId(), decision);
    }
}
