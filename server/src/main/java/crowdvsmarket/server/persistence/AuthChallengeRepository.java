package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Single-use sign-in nonces (Р-4; docs/research/week1_wallet_auth.md §4) — not part of the
 *  engine's replay log (auth is orthogonal to game state), but still DB-backed so a challenge
 *  issued just before a restart is not silently forgotten while still within its TTL. */
@ApplicationScoped
public class AuthChallengeRepository {

    @Inject
    Db db;

    public record Row(String nonce, String message, String address, long expiresAtMs, boolean used) {
    }

    public void insert(String nonce, String message, String address, long expiresAtMs, long createdAtMs) {
        db.tx(c -> db.update(c,
                "INSERT INTO auth_challenges (nonce, message, address, expires_at_ms, used, created_at_ms) "
                        + "VALUES (?, ?, ?, ?, FALSE, ?)",
                nonce, message, address, expiresAtMs, createdAtMs));
    }

    public Optional<Row> find(String nonce) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT nonce, message, address, expires_at_ms, used FROM auth_challenges WHERE nonce = ?",
                rs -> new Row(rs.getString("nonce"), rs.getString("message"), rs.getString("address"),
                        rs.getLong("expires_at_ms"), rs.getBoolean("used")),
                nonce)));
    }

    /** Marks the nonce used, but only if it was not already — returns whether this call is the
     *  one that consumed it (false means "already used", i.e. a replay attempt). */
    public boolean markUsedIfUnused(String nonce) {
        return db.tx(c -> markUsedIfUnused(c, nonce));
    }

    public boolean markUsedIfUnused(Connection c, String nonce) throws SQLException {
        int updated = db.update(c, "UPDATE auth_challenges SET used = TRUE WHERE nonce = ? AND used = FALSE", nonce);
        return updated == 1;
    }
}
