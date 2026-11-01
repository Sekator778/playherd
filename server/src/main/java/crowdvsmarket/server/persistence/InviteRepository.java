package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Personal invite codes and the acquisition record ({@code players.invite_code / referred_by / source}, migration V15; since D-107 the truth is {@code player_sidecar}, V21).
 *
 * <p><b>The code is stored, random, and public.</b> 8 lowercase hex characters from a {@link SecureRandom}: it reveals neither
 * the wallet address nor the player id, cannot be derived from either, and is the same shape the migration's backfill gives
 * existing players. Stored rather than derived because a derivation needs a server secret that would have to live for ever;
 * a unique index guards the (rare) clash and {@link #assignCode} retries.
 *
 * <p><b>Recorded once.</b> {@link #recordAtRegistration} is called inside the transaction that inserts the player row and is
 * never called again for that player; there is no update path.
 */
@ApplicationScoped
public class InviteRepository {

    /** What a client may send: a code is 4..16 alphanumerics, a channel tag is 1..32 of {@code [a-z0-9_-]}. */
    private static final Pattern CODE = Pattern.compile("^[a-z0-9]{4,16}$");
    private static final Pattern SOURCE = Pattern.compile("^[a-z0-9_-]{1,32}$");

    @Inject
    Db db;

    private final SecureRandom random = new SecureRandom();

    /** The normalised code (trimmed, lower case), or {@code null} when the text is not shaped like a code. */
    public static String normaliseCode(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return CODE.matcher(s).matches() ? s : null;
    }

    /** The normalised channel tag, or {@code null}. */
    public static String normaliseSource(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return SOURCE.matcher(s).matches() ? s : null;
    }

    /** The id of the real (non-bot) player who owns the code, empty for an unknown or malformed code. */
    public Optional<String> playerIdForCode(String rawCode) {
        String code = normaliseCode(rawCode);
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT s.player_id FROM player_sidecar s WHERE s.invite_code = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM players p WHERE p.player_id = s.player_id AND p.is_bot)", rs -> rs.getString(1), code)));
    }

    /** The player's code, empty when they have none yet (a bot, or a clash in the migration's backfill). */
    public Optional<String> codeOf(String playerId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT invite_code FROM player_sidecar WHERE player_id = ?", rs -> rs.getString(1), playerId)));
    }

    /** The player's code; assigns one first if they have none. Safe to call concurrently: whoever writes first stays. */
    public String ensureCode(String playerId) {
        Optional<String> have = codeOf(playerId);
        if (have.isPresent()) {
            return have.get();
        }
        db.tx(c -> {
            assignCode(c, playerId);
            return null;
        });
        return codeOf(playerId).orElseThrow(() -> new IllegalStateException("no invite code for player " + playerId));
    }

    /** Players who registered through the player's link, bots excluded. */
    public int referredCount(String playerId) {
        Integer n = db.read(c -> db.queryOne(c,
                "SELECT COUNT(*) FROM player_sidecar WHERE referred_by = ?", rs -> rs.getInt(1), playerId));
        return n == null ? 0 : n;
    }

    /**
     * At registration, BEFORE the command that registers the player (D-107: the columns are not in the journal, so they are written first,
     * and a registration that then does not happen leaves a row nothing refers to): gives the player a code and writes {@code referred_by} /
     * {@code source} into {@code player_sidecar}. {@code referrerId} null (unknown code, none sent) or equal to {@code playerId}
     * (self-referral) records no referrer; an unusable {@code source} records none. A second call for the same player is a no-op.
     */
    public void recordAtRegistration(Connection c, String playerId, String referrerId, String rawSource)
            throws SQLException {
        String referrer = referrerId != null && !referrerId.equals(playerId) ? referrerId : null;
        String source = normaliseSource(rawSource);
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%08x", random.nextInt());
            int rows = db.update(c, "INSERT INTO player_sidecar (player_id, invite_code, referred_by, source) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", playerId, code, referrer, source);
            if (rows > 0) {
                return;
            }
            if (db.queryOne(c, "SELECT 1 FROM player_sidecar WHERE player_id = ?", rs -> 1, playerId) != null) {
                return; // already recorded
            }
        }
        throw new IllegalStateException("could not assign an invite code to " + playerId);
    }

    /** {@link #recordAtRegistration} in a transaction of its own. */
    public void recordAtRegistration(String playerId, String referrerId, String rawSource) {
        db.tx(c -> {
            recordAtRegistration(c, playerId, referrerId, rawSource);
            return null;
        });
    }

    /** Takes back what {@link #recordAtRegistration} wrote for a registration that did not happen. */
    public void forget(String playerId) {
        db.tx(c -> db.update(c, "DELETE FROM player_sidecar WHERE player_id = ?", playerId));
    }

    private void assignCode(Connection c, String playerId) throws SQLException {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%08x", random.nextInt());
            int rows = db.update(c, "INSERT INTO player_sidecar (player_id, invite_code) VALUES (?, ?) ON CONFLICT (player_id) DO UPDATE "
                    + "SET invite_code = EXCLUDED.invite_code WHERE player_sidecar.invite_code IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM player_sidecar WHERE invite_code = EXCLUDED.invite_code)", playerId, code);
            if (rows > 0) {
                return;
            }
            if (db.queryOne(c, "SELECT invite_code FROM player_sidecar WHERE player_id = ?", rs -> rs.getString(1), playerId) != null) {
                return; // already has one
            }
        }
        throw new IllegalStateException("could not assign an invite code to " + playerId);
    }
}
