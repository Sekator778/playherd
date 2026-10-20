package crowdvsmarket.server.snapshot;

import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The canonical SHA-256 of the engine state (architecture plan F5): the hash of {@link StateCodec#encode}'s text, so it covers
 * exactly what a snapshot carries and two states with the same digest are the same state. The text is language independent (one
 * fact per line, sorted where order carries no meaning), which makes the digest a contract another implementation of the engine
 * can be checked against by replaying the same journal.
 */
public final class StateDigest {

    private StateDigest() {
    }

    /** The digest of the state in {@code hub} and {@code feed}. */
    public static String of(EngineHub hub, BetFeed feed) {
        return sha256(StateCodec.encode(hub, feed));
    }

    public static String sha256(String canonicalText) {
        return sha256(canonicalText.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The digest of what a replay of the journal determines, for comparing a snapshot with a replay from zero. Left out:
     * <ul>
     *   <li>the {@code oracleSeq} line: the live counter is also raised to the floor the chain service demanded
     *       ({@code server_meta}), which no replay knows; {@link #oracleSeqOf} is compared on its own (a replay may not be ahead);
     *   <li>with {@code withoutPlayers}, for a journal of schema version 1, which does not carry the roster (it comes from the
     *       {@code players} table when the state is built, and so do the names in the bet feed): the roster lines and the feed
     *       entries, and the closing line count with them.
     * </ul>
     */
    public static String ofJournalDetermined(String canonicalText, boolean withoutPlayers) {
        StringBuilder kept = new StringBuilder(canonicalText.length());
        int from = 0;
        while (from < canonicalText.length()) {
            int end = canonicalText.indexOf('\n', from);
            String line = canonicalText.substring(from, end + 1);
            boolean drop = line.startsWith("oracleSeq\t")
                    || (withoutPlayers && (line.startsWith("roster\t") || line.startsWith("feedEntry\t") || line.startsWith("end\t")));
            if (!drop) {
                kept.append(line);
            }
            from = end + 1;
        }
        return sha256(kept.toString());
    }

    /** The oracle sequence number a canonical text carries. */
    public static long oracleSeqOf(String canonicalText) {
        int at = canonicalText.indexOf("\noracleSeq\t");
        if (at < 0) {
            throw new IllegalArgumentException("no oracleSeq line");
        }
        int from = at + "\noracleSeq\t".length();
        return Long.parseLong(canonicalText.substring(from, canonicalText.indexOf('\n', from)));
    }
}
