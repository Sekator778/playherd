package crowdvsmarket.server.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bug fix (wave-1 finding): a bearer-token compare that returns as soon as the two strings'
 * *lengths* differ (before ever touching the constant-time loop over their characters) is not
 * actually constant-time — an attacker can time it to learn the secret token's length before
 * brute-forcing its content. {@link #equals(String, String)} first hashes both sides to a
 * fixed-length (32-byte SHA-256) digest, so the length of the caller's actual input never affects
 * how long the comparison takes, then compares those two fixed-length digests with {@link
 * MessageDigest#isEqual}, itself documented as constant-time for arrays of equal length.
 *
 * <p>Used by {@code AdminResource}'s operator-token check; the general shape (constant-time
 * compare over fixed-length digests) is also FIXED INTERFACE (a)'s requirement for chainsvc's own
 * bearer-token check, which is that service's own implementation, not this one.
 */
public final class ConstantTimeTokens {

    private ConstantTimeTokens() {
    }

    public static boolean equals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return MessageDigest.isEqual(digest(a), digest(b));
    }

    private static byte[] digest(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm (JLS/JCA spec) — never actually reachable.
            throw new IllegalStateException(e);
        }
    }
}
