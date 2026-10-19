package crowdvsmarket.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Р-106: the keys of API bots. A key is {@value #PREFIX} and 43 URL-safe characters (256 random bits); the server stores only its
 * SHA-256 (hex), looked up by that hash, so a copy of the database opens nothing. The prefix makes a key recognisable to a secret
 * scanner and to its owner; a header value that does not have the shape is refused before any lookup.
 */
public final class ApiKeys {

    public static final String PREFIX = "herd_";
    private static final int RANDOM_BYTES = 32;
    private static final int KEY_LENGTH = PREFIX.length() + 43;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether {@code token} has the shape of a key (length and alphabet); says nothing about whether such a key exists. */
    public static boolean wellFormed(String token) {
        if (token == null || token.length() != KEY_LENGTH || !token.startsWith(PREFIX)) {
            return false;
        }
        for (int i = PREFIX.length(); i < token.length(); i++) {
            char c = token.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** SHA-256 of the key, lower-case hex: what the {@code players} table keeps. */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** What the owner sees to tell keys apart: {@code herd_...} and the last four characters. */
    public static String hint(String key) {
        return PREFIX + "..." + key.substring(key.length() - 4);
    }
}
