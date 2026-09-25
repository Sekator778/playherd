package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Log of every oracle record ever queued to chainsvc (Р-40/41/47) — {@code chainsvc_queued}
 *  tracks whether the best-effort POST to chainsvc actually succeeded; a {@code false} row is
 *  retried on the next scheduler tick ({@code OraclePublisherService}), so a chainsvc outage never
 *  loses a record (and never blocks the game, per the task's "never block the game on chain
 *  confirmation"). */
@ApplicationScoped
public class OracleRecordRepository {

    @Inject
    Db db;

    public void insert(String roundId, long seq, long ts, long priceCents, String resultsHash, String source,
                        long sourceTs, long queuedAtMs) {
        db.tx(c -> db.update(c, """
                INSERT INTO oracle_records (seq, round_id, ts, price_cents, results_hash, source, source_ts,
                    queued_at_ms, chainsvc_queued)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
                """, seq, roundId, ts, priceCents, resultsHash, source, sourceTs, queuedAtMs));
    }

    public java.util.List<Row> pending() {
        return db.read(c -> db.queryList(c, """
                SELECT seq, ts, price_cents, results_hash, source, source_ts FROM oracle_records
                WHERE chainsvc_queued = FALSE ORDER BY seq ASC
                """, this::toRow));
    }

    public void markQueued(java.util.List<Long> seqs) {
        if (seqs.isEmpty()) {
            return;
        }
        db.tx(c -> {
            for (long seq : seqs) {
                db.update(c, "UPDATE oracle_records SET chainsvc_queued = TRUE WHERE seq = ?", seq);
            }
            return null;
        });
    }

    public int pendingCount() {
        Long n = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM oracle_records WHERE chainsvc_queued = FALSE",
                rs -> rs.getLong("n")));
        return n == null ? 0 : n.intValue();
    }

    private Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("seq"), rs.getLong("ts"), rs.getLong("price_cents"), rs.getString("results_hash"),
                rs.getString("source"), rs.getLong("source_ts"));
    }

    public record Row(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
    }
}
