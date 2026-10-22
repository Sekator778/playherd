package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
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

    /**
     * What the limit counts an address as: an IPv6 address is its /64 prefix (a subscriber holds a whole /64 and rotates the rest of the
     * address freely, so the exact address would give an unlimited number of places), an IPv4 address and anything that is not a literal
     * address is itself. Never resolves a name.
     */
    static String limitKey(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        String literal = ip.strip();
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.startsWith("[") && literal.endsWith("]")) {
            literal = literal.substring(1, literal.length() - 1);
        }
        if (!literal.matches("[0-9a-fA-F:.]+")) {
            return ip;
        }
        try {
            java.net.InetAddress address = java.net.InetAddress.getByName(literal);
            if (!(address instanceof java.net.Inet6Address)) {
                return address.getHostAddress(); // an IPv4-mapped address is the IPv4 address it carries
            }
            return "v6/64:" + HexFormat.of().formatHex(address.getAddress(), 0, 8);
        } catch (java.net.UnknownHostException | RuntimeException e) {
            return ip;
        }
    }

    /** The salted hash of {@code ip} (an IPv6 address by its /64 prefix, see {@link #limitKey}), hex. */
    public String hash(String ip) {
        ip = limitKey(ip);
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

    /** How many players registered, from any address, since {@code sinceMs} (the start of the UTC day): the daily cap of all new players. */
    public int countSince(long sinceMs) {
        return db.tx(c -> {
            Integer count = db.queryOne(c, "SELECT COUNT(*) AS n FROM registration_ips WHERE at_ms >= ?", rs -> rs.getInt("n"), sinceMs);
            return count == null ? 0 : count;
        });
    }

    /** Counts one more registration for the address. */
    public void record(String ipHash, long nowMs) {
        db.tx(c -> db.update(c, "INSERT INTO registration_ips (ip_hash, at_ms) VALUES (?, ?)", ipHash, nowMs));
    }

    /** Takes back one registration recorded for the address at {@code nowMs} (a registration that did not happen after all). */
    public void forget(String ipHash, long nowMs) {
        db.tx(c -> db.update(c, "DELETE FROM registration_ips WHERE ctid = (SELECT ctid FROM registration_ips WHERE ip_hash = ? AND at_ms = ? LIMIT 1)",
                ipHash, nowMs));
    }

    /** {@link #record(String, long)} within the caller's transaction (the batch of the core writer). */
    public void record(Connection c, String ipHash, long nowMs) throws SQLException {
        db.update(c, "INSERT INTO registration_ips (ip_hash, at_ms) VALUES (?, ?)", ipHash, nowMs);
    }
}
