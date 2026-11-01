package crowdvsmarket.server.persistence;

import crowdvsmarket.server.dto.Rounds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Log of every oracle record ever queued to chainsvc (D-40/41/47) — {@code chainsvc_queued}
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

    /** One record to queue (docs/research/markets_architecture.md §6/§7, V7): {@code market} and
     *  {@code assetIndex} (the market's append-only registry index, BTC = 0) say which market's
     *  close price {@code priceCents} is — engine (lot) price times 100 — and what goes on chain as
     *  the record's asset. */
    public record NewRecord(String market, int assetIndex, String roundId, long seq, long ts, long priceCents,
                              String resultsHash, String source, long sourceTs, long queuedAtMs) {
    }

    /** Inserts one record inside the caller's transaction — settlement writes it together with the
     *  {@code RoundResolved} event and the round's result row. */
    public void insert(Connection c, NewRecord r) throws SQLException {
        db.update(c, """
                INSERT INTO oracle_records (seq, round_id, ts, price_cents, results_hash, source, source_ts,
                    queued_at_ms, chainsvc_queued, market, asset_index)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)
                """, r.seq(), r.roundId(), r.ts(), r.priceCents(), r.resultsHash(), r.source(), r.sourceTs(),
                r.queuedAtMs(), r.market(), r.assetIndex());
    }

    /** A BTC record (market {@code BTC}, asset index 0) in a transaction of its own — the shape this
     *  table had before markets. */
    public void insert(String roundId, long seq, long ts, long priceCents, String resultsHash, String source,
                        long sourceTs, long queuedAtMs) {
        db.tx(c -> {
            insert(c, new NewRecord("BTC", 0, roundId, seq, ts, priceCents, resultsHash, source, sourceTs,
                    queuedAtMs));
            return null;
        });
    }

    /** Records waiting for chainsvc, oldest first, every market's — with the {@code asset_index} the
     *  wire format carries since record format 2 (docs/research/markets_architecture.md §7) and the time
     *  of the last POST attempt, if there was one (V8). This query does not decide what may be sent: a
     *  record with an asset above 0 must not reach a chain service that predates format 2 (it would
     *  publish it as a BTC price), and that check belongs to {@code OraclePublisherService}, which knows
     *  what chainsvc says about itself. A row in state {@code conflict} (a seq the chain service held
     *  before this server ever sent it, {@link #markConflict}) is not waiting for anything: it is never
     *  sent. */
    public java.util.List<Row> pending() {
        return db.read(c -> db.queryList(c, """
                SELECT seq, ts, price_cents, results_hash, source, source_ts, market, asset_index,
                    post_attempted_at_ms
                FROM oracle_records
                WHERE chainsvc_queued = FALSE AND state <> 'conflict' ORDER BY seq ASC
                """, this::toRow));
    }

    /** Never a row this server flagged as a collision: it stays unqueued on purpose. */
    public void markQueued(java.util.List<Long> seqs) {
        if (seqs.isEmpty()) {
            return;
        }
        db.tx(c -> {
            for (long seq : seqs) {
                db.update(c, "UPDATE oracle_records SET chainsvc_queued = TRUE WHERE seq = ? AND state <> 'conflict'",
                        seq);
            }
            return null;
        });
    }

    /** Puts on record, BEFORE the POST, that this server is about to send these rows: the first attempt time
     *  stays (a row already attempted keeps its value). Only a row with a value here may later be
     *  {@link #markKnown marked known} — see V8. */
    public void markPostAttempted(java.util.List<Long> seqs, long atMs) {
        if (seqs.isEmpty()) {
            return;
        }
        db.tx(c -> {
            for (long seq : seqs) {
                db.update(c, "UPDATE oracle_records SET post_attempted_at_ms = ? WHERE seq = ? "
                        + "AND post_attempted_at_ms IS NULL AND NOT (chainsvc_queued = FALSE AND state = 'conflict')",
                        atMs, seq);
            }
            return null;
        });
    }

    /** chainsvc already holds {@code seq} (it answered {@code queued}, {@code in_flight},
     *  {@code confirmed} or {@code conflict} for it) although this server never saw the POST that
     *  put it there succeed — a response lost after chainsvc had persisted the batch. Marks the row
     *  handed over, so it is not resent on every tick, and keeps the state chainsvc reported, which is
     *  exactly what {@link #updateReconciledState} would store on the next reconciliation pass.
     *
     *  <p>Only for a row this server has POSTed before ({@code post_attempted_at_ms}, V8): the held
     *  record is then the one it sent. A row that was never POSTed is not touched — chainsvc holds somebody
     *  else's record under that seq ({@link #markConflict}) — and the answer is false. */
    public boolean markKnown(long seq, String state, Long height, String coinId, long checkedAtMs) {
        return db.tx(c -> db.update(c, """
                UPDATE oracle_records SET chainsvc_queued = TRUE, state = ?, height = ?, coin_id = ?,
                    state_checked_at_ms = ?
                WHERE seq = ? AND post_attempted_at_ms IS NOT NULL AND NOT (chainsvc_queued = FALSE AND state = 'conflict')
                """, state, height, coinId, checkedAtMs, seq)) > 0;
    }

    /** A seq collision: chainsvc holds a record under {@code seq} that this server never sent. The row gets state
     *  {@code conflict} — what the round's {@code oracleAnchor} and the public journal show, with no height and
     *  no coin: nothing of the other record is claimed — and stays unqueued: it is not pending, not reconciled,
     *  not counted. Only a row never POSTed can be flagged (false for any other). */
    public boolean markConflict(long seq, long checkedAtMs) {
        return db.tx(c -> db.update(c, """
                UPDATE oracle_records SET state = 'conflict', height = NULL, coin_id = NULL, state_checked_at_ms = ?,
                    chainsvc_queued = FALSE
                WHERE seq = ? AND post_attempted_at_ms IS NULL
                """, checkedAtMs, seq)) > 0;
    }

    /** A content collision: chainsvc says it holds a DIFFERENT record under a {@code seq} this server did
     *  POST (its answer's {@code conflictSeqs}, or the content of {@code GET /v1/oracle/records/{seq}} —
     *  docs/research/chain_service.md §18). Same outcome as {@link #markConflict}: state {@code conflict},
     *  nothing of the other record claimed, never retried. Only a row not yet handed over can be flagged. */
    public boolean markContentConflict(long seq, long checkedAtMs) {
        return db.tx(c -> db.update(c, """
                UPDATE oracle_records SET state = 'conflict', height = NULL, coin_id = NULL, state_checked_at_ms = ?
                WHERE seq = ? AND chainsvc_queued = FALSE
                """, checkedAtMs, seq)) > 0;
    }

    /** Rows waiting for chainsvc (a collision is not waiting for anything, see {@link #pending}). */
    public int pendingCount() {
        Long n = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM oracle_records "
                + "WHERE chainsvc_queued = FALSE AND state <> 'conflict'", rs -> rs.getLong("n")));
        return n == null ? 0 : n.intValue();
    }

    /** Task item 1c — rows {@code OracleReconciliationService} still needs to poll chainsvc about:
     *  already handed to chainsvc at least once ({@code chainsvc_queued}, otherwise
     *  {@code OraclePublisherService} itself is still the one responsible for it) and not yet
     *  known {@code confirmed} (once confirmed, a record's chain state cannot regress — nothing
     *  left to poll for). */
    public java.util.List<ReconciliationRow> needsReconciliation() {
        return db.read(c -> db.queryList(c, """
                SELECT seq, ts, price_cents, results_hash, source, source_ts, queued_at_ms, market, asset_index
                FROM oracle_records
                WHERE chainsvc_queued = TRUE AND state <> 'confirmed'
                ORDER BY seq ASC
                """, this::toReconciliationRow));
    }

    /** Stores this server's freshest known copy of one record's chainsvc-side state (task item
     *  1c). {@code height}/{@code coinId} are nullable (only meaningful once {@code state} is
     *  {@code "confirmed"}). */
    public void updateReconciledState(long seq, String state, Long height, String coinId, long checkedAtMs) {
        // never over a collision this server flagged: the seq's record on chainsvc is somebody else's
        db.tx(c -> db.update(c, """
                UPDATE oracle_records SET state = ?, height = ?, coin_id = ?, state_checked_at_ms = ?
                WHERE seq = ? AND NOT (chainsvc_queued = FALSE AND state = 'conflict')
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

    /**
     * The public records journal ({@code GET /api/oracle/records}, docs/research/markets_architecture.md
     * §7 item 6): up to {@code limit} rows with {@code seq >= fromSeq}, ascending, of every market, queued
     * to chainsvc or not yet — plus {@code firstSeq}, the lowest {@code seq} this database holds
     * ({@code null} while it holds none). A reader that sees a chain {@code seq} below {@code firstSeq}
     * knows it was published before this database existed. A read over the primary key, so it costs
     * the page, not the table.
     */
    public JournalPage journal(long fromSeq, int limit) {
        return db.read(c -> {
            Long firstSeq = db.queryOne(c, "SELECT MIN(seq) AS first_seq FROM oracle_records",
                    rs -> (Long) rs.getObject("first_seq"));
            // the venue is the round's (V9): the record is written in the transaction that writes the round's result row
            java.util.List<JournalRow> rows = db.queryList(c, """
                    SELECT o.seq, o.round_id, o.market, o.asset_index, o.ts, o.price_cents, o.results_hash, o.state,
                        r.venue
                    FROM oracle_records o LEFT JOIN round_results r ON r.round_id = o.round_id
                    WHERE o.seq >= ? ORDER BY o.seq ASC LIMIT ?
                    """, this::toJournalRow, fromSeq, limit);
            return new JournalPage(firstSeq, rows);
        });
    }

    private Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getLong("seq"), rs.getLong("ts"), rs.getLong("price_cents"), rs.getString("results_hash"),
                rs.getString("source"), rs.getLong("source_ts"), rs.getString("market"), rs.getInt("asset_index"),
                (Long) rs.getObject("post_attempted_at_ms"));
    }

    private ReconciliationRow toReconciliationRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReconciliationRow(rs.getLong("seq"), rs.getLong("ts"), rs.getLong("price_cents"),
                rs.getString("results_hash"), rs.getString("source"), rs.getLong("source_ts"),
                rs.getLong("queued_at_ms"), rs.getString("market"), rs.getInt("asset_index"));
    }

    private JournalRow toJournalRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JournalRow(rs.getLong("seq"), rs.getString("round_id"), rs.getString("market"),
                rs.getInt("asset_index"), rs.getLong("ts"), rs.getLong("price_cents"), rs.getString("results_hash"),
                rs.getString("state"), rs.getString("venue"));
    }

    /** {@code postAttemptedAtMs}: when this server first put the row in a POST to chainsvc ({@code null}: never). */
    public record Row(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs,
                        String market, int assetIndex, Long postAttemptedAtMs) {

        /** A row that was never POSTed. */
        public Row(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs,
                   String market, int assetIndex) {
            this(seq, ts, priceCents, resultsHash, source, sourceTs, market, assetIndex, null);
        }
    }

    /** {@code market}/{@code assetIndex}: which market's close price {@code priceCents} is — a
     *  re-POST of the record must carry the same asset the first one did. */
    public record ReconciliationRow(long seq, long ts, long priceCents, String resultsHash, String source,
                                      long sourceTs, long queuedAtMs, String market, int assetIndex) {
    }

    /** One row of the public journal, in the field names of the JSON it becomes. {@code venue}: the exchange the
     *  round ran on — the one {@code priceCents} is the close of — for a market that has a backup; {@code null} for
     *  any other. */
    public record JournalRow(long seq, String roundId, String market, int assetIndex, long ts, long priceCents,
                               String resultsHash, String state, String venue) {

        /** A row of a round that ran on no particular venue. */
        public JournalRow(long seq, String roundId, String market, int assetIndex, long ts, long priceCents,
                          String resultsHash, String state) {
            this(seq, roundId, market, assetIndex, ts, priceCents, resultsHash, state, null);
        }
    }

    /** {@link #journal}'s answer: the page and the start of this database's {@code seq} range. */
    public record JournalPage(Long firstSeq, java.util.List<JournalRow> rows) {
    }
}
