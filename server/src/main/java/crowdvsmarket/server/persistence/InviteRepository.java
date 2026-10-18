package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Personal invite codes and the acquisition record ({@code players.invite_code / referred_by / source}, migration V15).
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
                "SELECT player_id FROM players WHERE invite_code = ? AND NOT is_bot", rs -> rs.getString(1), code)));
    }

    /** The player's code, empty when they have none yet (a bot, or a clash in the migration's backfill). */
    public Optional<String> codeOf(String playerId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT invite_code FROM players WHERE player_id = ?", rs -> rs.getString(1), playerId)));
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
                "SELECT COUNT(*) FROM players WHERE referred_by = ? AND NOT is_bot", rs -> rs.getInt(1), playerId));
        return n == null ? 0 : n;
    }

    /**
     * At registration, in the transaction that inserted the new player's row: gives the player a code and writes
     * {@code referred_by} / {@code source}. {@code referrerId} null (unknown code, none sent) or equal to {@code playerId}
     * (self-referral) records no referrer; an unusable {@code source} records none. The {@code WHERE referred_by IS NULL AND
     * source IS NULL} guard makes a second call a no-op.
     */
    public void recordAtRegistration(Connection c, String playerId, String referrerId, String rawSource)
            throws SQLException {
        assignCode(c, playerId);
        String referrer = referrerId != null && !referrerId.equals(playerId) ? referrerId : null;
        String source = normaliseSource(rawSource);
        if (referrer != null || source != null) {
            db.update(c, "UPDATE players SET referred_by = ?, source = ? WHERE player_id = ? "
                    + "AND referred_by IS NULL AND source IS NULL", referrer, source, playerId);
        }
    }

    private void assignCode(Connection c, String playerId) throws SQLException {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%08x", random.nextInt());
            int rows = db.update(c, "UPDATE players SET invite_code = ? WHERE player_id = ? AND invite_code IS NULL "
                    + "AND NOT EXISTS (SELECT 1 FROM players WHERE invite_code = ?)", code, playerId, code);
            if (rows > 0) {
                return;
            }
            if (db.queryOne(c, "SELECT invite_code FROM players WHERE player_id = ?", rs -> rs.getString(1), playerId) != null) {
                return; // already has one
            }
        }
        throw new IllegalStateException("could not assign an invite code to " + playerId);
    }
}
