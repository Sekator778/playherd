package crowdvsmarket.server.persistence;

import crowdvsmarket.server.dto.Rounds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/** Log of every oracle record ever queued to chainsvc (Р-40/41/47) — {@code chainsvc_queued}
 *  tracks whether the best-effort POST to chainsvc actually succeeded; a {@code false} row is
 *  retried on the next scheduler tick ({@code OraclePublisherService}), so a chainsvc outage never
 *  loses a record (and never blocks the game, per the task's "never block the game on chain
 *  confirmation"). {@code state}/{@code height}/{@code coin_id} (V2 migration, task item 1c) are
 *  this server's own last-known copy of chainsvc's FIXED INTERFACE (b) per-record durable-delivery
 *  state, refreshed by {@code OracleReconciliationService}; both columns default to reflecting "we
 *  haven't asked chainsvc about this one yet" until the first successful poll. */
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

    /** Task item 1c — rows {@code OracleReconciliationService} still needs to poll chainsvc about:
     *  already handed to chainsvc at least once ({@code chainsvc_queued}, otherwise
     *  {@code OraclePublisherService} itself is still the one responsible for it) and not yet
     *  known {@code confirmed} (once confirmed, a record's chain state cannot regress — nothing
     *  left to poll for). */
    public java.util.List<ReconciliationRow> needsReconciliation() {
        return db.read(c -> db.queryList(c, """
                SELECT seq, ts, price_cents, results_hash, source, source_ts, queued_at_ms FROM oracle_records
                WHERE chainsvc_queued = TRUE AND state <> 'confirmed'
                ORDER BY seq ASC
                """, this::toReconciliationRow));
    }

    /** Stores this server's freshest known copy of one record's chainsvc-side state (task item
     *  1c). {@code height}/{@code coinId} are nullable (only meaningful once {@code state} is
     *  {@code "confirmed"}). */
    public void updateReconciledState(long seq, String state, Long height, String coinId, long checkedAtMs) {
        db.tx(c -> db.update(c, """
                UPDATE oracle_records SET state = ?, height = ?, coin_id = ?, state_checked_at_ms = ?
                WHERE seq = ?
                """, state, height, coinId, checkedAtMs, seq));
    }

    /** The optional {@code oracleAnchor} on a settled/cancelled round's DTO (task item 1c) — empty
     *  if this round never got an oracle record at all (e.g. a cancelled round today, see
     *  {@code GameService#cancelRound} — it does not call {@code enqueueOracleRecord}). */
    public Optional<Rounds.OracleAnchorDto> findAnchorByRoundId(String roundId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT seq, state, height FROM oracle_records WHERE round_id = ?",
                rs -> new Rounds.OracleAnchorDto(rs.getLong("seq"), rs.getString("state"),
                        (Long) rs.getObject("height")),
                roundId)));
    }

    private Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("seq"), rs.getLong("ts"), rs.getLong("price_cents"), rs.getString("results_hash"),
                rs.getString("source"), rs.getLong("source_ts"));
    }

    private ReconciliationRow toReconciliationRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReconciliationRow(rs.getLong("seq"), rs.getLong("ts"), rs.getLong("price_cents"),
                rs.getString("results_hash"), rs.getString("source"), rs.getLong("source_ts"),
                rs.getLong("queued_at_ms"));
    }

    public record Row(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
    }

    public record ReconciliationRow(long seq, long ts, long priceCents, String resultsHash, String source,
                                      long sourceTs, long queuedAtMs) {
    }
}
