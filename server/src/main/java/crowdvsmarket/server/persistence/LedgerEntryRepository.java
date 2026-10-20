package crowdvsmarket.server.persistence;

import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.BitSet;
import java.util.List;

/**
 * The players' ledger history, a projection of the journal ({@code V16__ledger_entries.sql}): one row per movement of a
 * balance, keyed by the {@code LedgerBook}'s own sequence number. Written after the engine lock by {@code GameService}
 * ({@code INSERT ... ON CONFLICT (seq) DO NOTHING}, so writing a movement twice is harmless) and by the rebuild for the
 * movements the table lacks; read by {@code GameService#myLedger}. The engine never reads it.
 */
@ApplicationScoped
public class LedgerEntryRepository {

    @Inject
    Db db;

    /** The sequence numbers the table holds: {@code 1..denseThrough} all of them, and beyond that only those set in
     *  {@code sparse} ({@code null} when the table has no gap, the usual case: a crash or a failed write only loses the tail). */
    public record Stored(long denseThrough, BitSet sparse) {

        public boolean has(long seq) {
            return seq <= denseThrough || (sparse != null && seq <= Integer.MAX_VALUE && sparse.get((int) seq));
        }

        /** Whether the table holds every movement {@code 1..last} (a snapshot of a ledger whose last movement is {@code last}
         *  stands on rows that are all there). */
        public boolean hasAllThrough(long last) {
            if (last <= denseThrough) {
                return true;
            }
            return sparse != null && last <= Integer.MAX_VALUE && sparse.nextClearBit(1) > last;
        }
    }

    /** Writes {@code entries} in ONE transaction; a movement the table already has is left as it is. */
    public void insertBatch(List<LedgerEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ledger_entries (seq, player_id, type, delta_mojos, round_id, at_ms) "
                            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (seq) DO NOTHING")) {
                for (LedgerEntry e : entries) {
                    ps.setLong(1, e.sequence());
                    ps.setString(2, e.playerId());
                    ps.setString(3, e.type().name());
                    ps.setLong(4, e.delta().mojos());
                    if (e.roundId() == null) {
                        ps.setNull(5, Types.VARCHAR);
                    } else {
                        ps.setString(5, e.roundId());
                    }
                    ps.setLong(6, e.atEpochMs());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * Which movements the table holds: what a rebuild compares the replayed ledger with to write only the missing ones. A
     * table without gaps is recognised from its row count and highest {@code seq} (two index reads); only a table with
     * a gap is read through, {@code seq} by {@code seq}, into a bit set.
     */
    public Stored stored() {
        long[] stats = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n, COALESCE(MAX(seq), 0) AS m FROM ledger_entries",
                rs -> new long[] {rs.getLong("n"), rs.getLong("m")}));
        if (stats[0] == stats[1]) {
            return new Stored(stats[1], null);
        }
        BitSet seqs = new BitSet();
        db.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT seq FROM ledger_entries")) {
                ps.setFetchSize(10_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        seqs.set((int) rs.getLong(1));
                    }
                }
            }
            return null;
        });
        return new Stored(0, seqs);
    }

    /** A player's movements, newest first (by {@code seq}), those booked before {@code beforeMs} only, at most {@code limit}. */
    public List<LedgerEntry> newestFirst(String playerId, long beforeMs, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT seq, player_id, type, delta_mojos, round_id, at_ms FROM ledger_entries
                WHERE player_id = ? AND at_ms < ? ORDER BY seq DESC LIMIT ?
                """,
                rs -> new LedgerEntry(rs.getLong("seq"), rs.getString("player_id"),
                        LedgerEntryType.valueOf(rs.getString("type")), new PlayAmount(rs.getLong("delta_mojos")),
                        rs.getString("round_id"), rs.getLong("at_ms")),
                playerId, beforeMs, limit));
    }
}
