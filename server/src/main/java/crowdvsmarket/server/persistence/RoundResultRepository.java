package crowdvsmarket.server.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Read model for settled/cancelled rounds — see {@code V1__init.sql}'s comment on {@code
 *  round_results}/{@code player_round_history}. Never replayed; written once, in the same
 *  transaction as the {@code RoundResolved}/{@code RoundCancelled} event, by {@code GameService}. */
@ApplicationScoped
public class RoundResultRepository {

    @Inject
    Db db;
    @Inject
    OracleRecordRepository oracleRecords;

    /** Latest {@code window_end_ms} ever resolved for {@code interval} — the seed for
     *  {@code RoundProgress} at startup (its next-to-resolve window starts exactly there). */
    public Optional<Long> latestWindowEnd(Interval interval) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT MAX(window_end_ms) AS m FROM round_results WHERE interval = ?",
                rs -> (Long) rs.getObject("m"), interval.name())));
    }

    public boolean exists(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT 1 FROM round_results WHERE round_id = ?",
                rs -> Boolean.TRUE, roundId)) != null;
    }

    public void insertSettled(Connection c, String roundId, Interval interval, long windowStart, long windowEnd,
                                long openPriceCents, long closePriceCents, List<Rounds.PublicPositionDto> positions,
                                Rounds.CrowdRoundResultDto crowd, String resultsHash, String canonicalJson,
                                long settledAtMs, List<PlayerHistoryRow> perPlayerHistory) throws SQLException {
        db.update(c, """
                INSERT INTO round_results (round_id, interval, window_start_ms, window_end_ms, status,
                    open_price_cents, close_price_cents, positions_json, crowd_json, results_hash,
                    canonical_json, settled_at_ms, created_at_ms)
                VALUES (?, ?, ?, ?, 'SETTLED', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                roundId, interval.name(), windowStart, windowEnd, openPriceCents, closePriceCents,
                Db.jsonb(positions), Db.jsonb(crowd), resultsHash, canonicalJson, settledAtMs, settledAtMs);
        for (PlayerHistoryRow row : perPlayerHistory) {
            db.update(c, """
                    INSERT INTO player_round_history (round_id, player_id, interval, settled_at_ms, direction,
                        leverage, stake_mojos, liquidated, direction_correct, payout_mojos)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    roundId, row.playerId(), interval.name(), settledAtMs, row.direction().name(), row.leverage(),
                    row.stakeMojos(), row.liquidated(), row.directionCorrect(), row.payoutMojos());
        }
    }

    public record PlayerHistoryRow(String playerId, Enums.Direction direction, int leverage, long stakeMojos,
                                     boolean liquidated, Boolean directionCorrect, long payoutMojos) {
    }

    public void insertCancelled(Connection c, String roundId, Interval interval, long windowStart, long windowEnd,
                                  String reason, long cancelledAtMs, int refundedBetCount, String resultsHash,
                                  String canonicalJson) throws SQLException {
        db.update(c, """
                INSERT INTO round_results (round_id, interval, window_start_ms, window_end_ms, status,
                    cancelled_at_ms, cancellation_reason, refunded_bet_count, results_hash, canonical_json,
                    created_at_ms)
                VALUES (?, ?, ?, ?, 'CANCELLED', ?, ?, ?, ?, ?, ?)
                """,
                roundId, interval.name(), windowStart, windowEnd, cancelledAtMs, reason, refundedBetCount,
                resultsHash, canonicalJson, cancelledAtMs);
    }

    public Optional<Rounds.RoundDto> findRoundDto(String roundId) {
        Rounds.RoundDto dto = db.read(c -> db.queryOne(c,
                "SELECT * FROM round_results WHERE round_id = ?", this::toRoundDto, roundId));
        return dto == null ? Optional.empty() : Optional.of(attachOracleAnchor(dto));
    }

    /** Task item 1c: the optional {@code oracleAnchor} on a settled/cancelled round's DTO — a
     *  separate lookup against {@code oracle_records} (not a SQL join in {@link #toRoundDto}),
     *  since it is best-effort/eventually-consistent bookkeeping owned by a different repository,
     *  not part of this read model's own row. Returns {@code dto} unchanged (no anchor) if this
     *  round never got an oracle record at all. */
    private Rounds.RoundDto attachOracleAnchor(Rounds.RoundDto dto) {
        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId(dto.roundId()).orElse(null);
        if (anchor == null) {
            return dto;
        }
        return switch (dto) {
            case Rounds.RoundSettled s -> new Rounds.RoundSettled(s.roundId(), s.window(), s.asset(), s.phase(),
                    s.openPrice(), s.closePrice(), s.positions(), s.crowd(), s.resultsHash(), anchor);
            case Rounds.RoundCancelled c -> new Rounds.RoundCancelled(c.roundId(), c.window(), c.asset(), c.phase(),
                    c.cancellation(), anchor);
            default -> dto; // toRoundDto never returns RoundBetting/RoundRunning
        };
    }

    /** The previous settled round's crowd-majority direction for {@code interval} — used by the
     *  {@code CONTRARIAN}/{@code FOLLOW_MAJORITY} bot strategies (Р-46). Empty if no settled round
     *  exists yet, or the most recent one was a tie/no-bets (crowd did not trade). */
    public Optional<Enums.Direction> lastMajorityDirection(Interval interval) {
        Rounds.CrowdRoundResultDto crowd = db.read(c -> db.queryOne(c, """
                SELECT crowd_json FROM round_results WHERE interval = ? AND status = 'SETTLED'
                ORDER BY settled_at_ms DESC LIMIT 1
                """,
                rs -> db.readJson(rs, "crowd_json", Rounds.CrowdRoundResultDto.class), interval.name()));
        return crowd != null && crowd.traded() ? Optional.of(crowd.direction()) : Optional.empty();
    }

    public Optional<String> canonicalJson(String roundId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT canonical_json FROM round_results WHERE round_id = ?",
                rs -> rs.getString("canonical_json"), roundId)));
    }

    public Paging.Page<Rounds.CancellationDto> listCancellations(Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        List<Rounds.CancellationDto> items = db.read(c -> db.queryList(c, """
                SELECT round_id, cancellation_reason, cancelled_at_ms, refunded_bet_count
                FROM round_results WHERE status = 'CANCELLED' AND cancelled_at_ms < ?
                ORDER BY cancelled_at_ms DESC LIMIT ?
                """,
                rs -> new Rounds.CancellationDto(rs.getString("round_id"), rs.getString("cancellation_reason"),
                        rs.getLong("cancelled_at_ms"), rs.getInt("refunded_bet_count")),
                before, limit + 1));
        boolean hasMore = items.size() > limit;
        return new Paging.Page<>(hasMore ? items.subList(0, limit) : items, hasMore);
    }

    public List<Identity.ProfileRoundEntryDto> playerHistory(String playerId, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT round_id, interval, settled_at_ms, direction, leverage, stake_mojos, liquidated,
                       direction_correct, payout_mojos
                FROM player_round_history WHERE player_id = ? ORDER BY settled_at_ms DESC LIMIT ?
                """,
                rs -> new Identity.ProfileRoundEntryDto(rs.getString("round_id"),
                        Enums.Interval.valueOf(rs.getString("interval")), rs.getLong("settled_at_ms"),
                        Enums.Direction.valueOf(rs.getString("direction")), rs.getInt("leverage"),
                        java.math.BigDecimal.valueOf(rs.getLong("stake_mojos"), 3).toPlainString(),
                        rs.getBoolean("liquidated"), (Boolean) rs.getObject("direction_correct"),
                        java.math.BigDecimal.valueOf(rs.getLong("payout_mojos"), 3).toPlainString()),
                playerId, limit));
    }

    private Rounds.RoundDto toRoundDto(java.sql.ResultSet rs) throws SQLException {
        String roundId = rs.getString("round_id");
        Enums.Interval interval = Enums.Interval.valueOf(rs.getString("interval"));
        Rounds.RoundWindowDto window = new Rounds.RoundWindowDto(interval, rs.getLong("window_start_ms"),
                rs.getLong("window_end_ms"));
        String status = rs.getString("status");
        if ("CANCELLED".equals(status)) {
            Rounds.CancellationDto cancellation = new Rounds.CancellationDto(roundId,
                    rs.getString("cancellation_reason"), rs.getLong("cancelled_at_ms"), rs.getInt("refunded_bet_count"));
            return new Rounds.RoundCancelled(roundId, window, cancellation);
        }
        List<Rounds.PublicPositionDto> positions = db.readJson(rs, "positions_json",
                new TypeReference<List<Rounds.PublicPositionDto>>() {
                });
        Rounds.CrowdRoundResultDto crowd = db.readJson(rs, "crowd_json", Rounds.CrowdRoundResultDto.class);
        String openPrice = java.math.BigDecimal.valueOf(rs.getLong("open_price_cents"), 2).toPlainString();
        String closePrice = java.math.BigDecimal.valueOf(rs.getLong("close_price_cents"), 2).toPlainString();
        return new Rounds.RoundSettled(roundId, window, openPrice, closePrice, positions, crowd,
                rs.getString("results_hash"));
    }
}
