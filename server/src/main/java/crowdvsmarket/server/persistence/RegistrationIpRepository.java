package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Economy stage B (plan B3): the counter behind "at most N new players per IP address per 24 hours". The address itself is never
 * stored: a row is the SHA-256 of a random salt (kept in {@code server_meta}, made on first use) and the address, with the time of the
 * registration, and a row older than the window is deleted. Nobody can read an address back from it, and a hash without the salt
 * cannot be tested against a list of addresses.
 */
@ApplicationScoped
public class RegistrationIpRepository {

    static final String SALT_KEY = "registration_ip_salt";
    /** The window of the limit: 24 hours. */
    public static final long WINDOW_MS = 24L * 3_600_000L;

    @Inject
    Db db;
    @Inject
    ServerMetaRepository serverMeta;

    private final SecureRandom random = new SecureRandom();
    private volatile String salt;

    /** The salted hash of {@code ip}, hex. */
    public String hash(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String salt() {
        String cached = salt;
        if (cached != null) {
            return cached;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        serverMeta.setIfAbsent(SALT_KEY, HexFormat.of().formatHex(bytes));
        cached = serverMeta.get(SALT_KEY).orElseThrow();
        salt = cached;
        return cached;
    }

    /** How many players the address (by its hash) registered in the window that ends at {@code nowMs}. Deletes the rows that left the window. */
    public int countInWindow(String ipHash, long nowMs) {
        return db.tx(c -> {
            db.update(c, "DELETE FROM registration_ips WHERE at_ms <= ?", nowMs - WINDOW_MS);
            Integer count = db.queryOne(c, "SELECT COUNT(*) AS n FROM registration_ips WHERE ip_hash = ? AND at_ms > ?",
                    rs -> rs.getInt("n"), ipHash, nowMs - WINDOW_MS);
            return count == null ? 0 : count;
        });
    }

    /** Counts one more registration for the address. */
    public void record(String ipHash, long nowMs) {
        db.tx(c -> db.update(c, "INSERT INTO registration_ips (ip_hash, at_ms) VALUES (?, ?)", ipHash, nowMs));
    }
}
