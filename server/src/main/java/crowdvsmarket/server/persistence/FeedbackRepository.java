package crowdvsmarket.server.persistence;

import crowdvsmarket.server.dto.Feedback;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * D-81: {@code feedback} — the messages visitors leave in the app (V10). A plain table: not an event, never replayed.
 * The IP address a message came from is not here and never reaches this class; the country derived from it is.
 */
@ApplicationScoped
public class FeedbackRepository {

    @Inject
    Db db;

    /** What is stored about one message; every field but {@code message} may be {@code null}. */
    public record NewFeedback(String message, String playerId, String country, String timeZone, String language,
                              String page, String client) {
    }

    /** Stores the message and returns the id it got. */
    public long insert(long receivedAtMs, NewFeedback feedback) {
        return db.tx(c -> db.queryOne(c, """
                INSERT INTO feedback (received_at_ms, message, player_id, country, time_zone, language, page, client)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """,
                rs -> rs.getLong("id"), receivedAtMs, feedback.message(), feedback.playerId(), feedback.country(),
                feedback.timeZone(), feedback.language(), feedback.page(), feedback.client()));
    }

    /**
     * The messages received at or after {@code sinceMs}, newest first (an id breaks a tie), at most {@code limit}, each with
     * the nickname of its sender and the number of rounds the sender has played — the rows of {@code player_round_history},
     * one per settled round the player had a bet in (cancelled rounds have none). A message from a visitor who was not
     * signed in has neither ({@code null}, not {@code 0}); a player who has set no nickname has the count only. Answered
     * from the {@code received_at_ms} index and, per signed-in row, the {@code (player_id, settled_at_ms)} one.
     */
    public List<Feedback.FeedbackItemDto> list(long sinceMs, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT f.id, f.received_at_ms, f.message, f.country, f.time_zone, f.language, f.page, f.client,
                       f.player_id, p.nickname,
                       CASE WHEN f.player_id IS NULL THEN NULL
                            ELSE (SELECT COUNT(*) FROM player_round_history h WHERE h.player_id = f.player_id) END
                           AS rounds_played
                FROM feedback f LEFT JOIN players p ON p.player_id = f.player_id
                WHERE f.received_at_ms >= ?
                ORDER BY f.received_at_ms DESC, f.id DESC
                LIMIT ?
                """,
                this::toItem, sinceMs, limit));
    }

    /** How many messages were received at or after {@code sinceMs}: the daily cap counts the last 24 hours with it. */
    public long countSince(long sinceMs) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM feedback WHERE received_at_ms >= ?",
                rs -> rs.getLong("n"), sinceMs));
    }

    private Feedback.FeedbackItemDto toItem(ResultSet rs) throws SQLException {
        int rounds = rs.getInt("rounds_played");
        Integer roundsPlayed = rs.wasNull() ? null : rounds;
        return new Feedback.FeedbackItemDto(rs.getLong("id"), rs.getLong("received_at_ms"), rs.getString("message"),
                rs.getString("country"), rs.getString("time_zone"), rs.getString("language"), rs.getString("page"),
                rs.getString("client"), rs.getString("player_id"), rs.getString("nickname"), roundsPlayed);
    }
}
