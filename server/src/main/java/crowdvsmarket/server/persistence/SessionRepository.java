package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/** {@code herd_session} cookie backing store (Р-4 §4.g: "выдаём серверную сессию... httpOnly-cookie
 *  с непрозрачным session id"). */
@ApplicationScoped
public class SessionRepository {

    @Inject
    Db db;

    public record Row(String sessionId, String playerId, long expiresAtMs) {
    }

    public void insert(String sessionId, String playerId, long createdAtMs, long expiresAtMs) {
        db.tx(c -> db.update(c,
                "INSERT INTO sessions (session_id, player_id, created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?)",
                sessionId, playerId, createdAtMs, expiresAtMs));
    }

    public Optional<Row> find(String sessionId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT session_id, player_id, expires_at_ms FROM sessions WHERE session_id = ?",
                rs -> new Row(rs.getString("session_id"), rs.getString("player_id"), rs.getLong("expires_at_ms")),
                sessionId)));
    }

    public void delete(String sessionId) {
        db.tx(c -> db.update(c, "DELETE FROM sessions WHERE session_id = ?", sessionId));
    }

    /** The ids of the sessions of {@code playerId} beyond its newest {@code keep}, oldest last (they are to go). */
    public List<String> idsBeyondNewest(String playerId, int keep) {
        return db.read(c -> db.queryList(c,
                "SELECT session_id FROM sessions WHERE player_id = ? ORDER BY created_at_ms DESC, session_id OFFSET ?",
                rs -> rs.getString("session_id"), playerId, keep));
    }

    /** Deletes the sessions that ran out before {@code nowMs}; returns how many. */
    public int deleteExpired(long nowMs) {
        return db.tx(c -> db.update(c, "DELETE FROM sessions WHERE expires_at_ms < ?", nowMs));
    }
}
