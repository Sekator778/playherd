package crowdvsmarket.server.journal;

import crowdvsmarket.server.persistence.Db;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The one-time move of the journal from the table to the queue (Р-107): on the first start with an empty journal directory every row of
 * {@code game_events} is copied, in id order, with the same id and the payload as the table stores it ({@code payload::text}), into the
 * queue; then the count and a SHA-256 over every column of every row are checked against what the queue reads back. Only after that does the
 * queue count as the journal ({@link Journal} deletes its "import in progress" marker). The table is not changed.
 *
 * <p>Idempotent in the only sense that matters for a crash: the target is wiped before an import starts, so a start that finds the marker
 * of an import that did not finish simply runs it again from the table, which is untouched.
 */
public final class JournalCutover {

    private static final Logger LOG = Logger.getLogger(JournalCutover.class);

    /** Events per excerpt of the import. */
    static final int CHUNK = 2_000;

    /** What the import did. */
    public record Result(long rows, long lastId, String sha256, long tookMs) {
    }

    private JournalCutover() {
    }

    /** The digest the import checks: count and every column of every event, in order. */
    public static final class Digest {
        private final MessageDigest sha;
        private long rows;
        private long lastSeq;
        private String hex;

        public Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        public void add(JournalRecord r) {
            update(Long.toString(r.seq()));
            update(r.type());
            update(Integer.toString(r.schemaVersion()));
            update(Long.toString(r.atMs()));
            update(r.market());
            update(r.playerId());
            update(r.payload());
            rows++;
            lastSeq = r.seq();
        }

        private void update(String s) {
            if (s == null) {
                sha.update((byte) 0x01);
            } else {
                sha.update((byte) 0x02);
                sha.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            sha.update((byte) 0x00);
        }

        public long rows() {
            return rows;
        }

        public long lastSeq() {
            return lastSeq;
        }

        /** The digest so far, as hex; the same text however often it is asked for. */
        public String hex() {
            if (hex == null) {
                hex = HexFormat.of().formatHex(sha.digest());
            }
            return hex;
        }
    }

    /** The columns of {@code game_events} in the order of the digest. */
    static final String SELECT = "SELECT id, event_type, payload::text AS payload, at_ms, schema_version, market, player_id "
            + "FROM game_events ORDER BY id ASC";

    /**
     * Copies the table into {@code store} (which must be empty) and verifies the copy.
     *
     * @param afterRows test hook, called after every chunk with the number of rows written so far
     * @throws IllegalStateException when the copy does not read back as the table
     */
    public static Result run(Db db, ChronicleJournalStore store, Consumer<Long> afterRows) {
        long t0 = System.nanoTime();
        Digest source = new Digest();
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(SELECT)) {
                ps.setFetchSize(CHUNK);
                try (ResultSet rs = ps.executeQuery()) {
                    List<JournalRecord> chunk = new ArrayList<>(CHUNK);
                    while (rs.next()) {
                        JournalRecord r = new JournalRecord(rs.getLong("id"), rs.getString("event_type"), rs.getInt("schema_version"),
                                rs.getLong("at_ms"), rs.getString("market"), rs.getString("player_id"), rs.getString("payload"));
                        source.add(r);
                        chunk.add(r);
                        if (chunk.size() == CHUNK) {
                            store.appendNumbered(chunk);
                            chunk = new ArrayList<>(CHUNK);
                            if (afterRows != null) {
                                afterRows.accept(source.rows());
                            }
                        }
                    }
                    if (!chunk.isEmpty()) {
                        store.appendNumbered(chunk);
                        if (afterRows != null) {
                            afterRows.accept(source.rows());
                        }
                    }
                }
            }
            return null;
        });
        Digest copy = new Digest();
        store.replay(0, Long.MAX_VALUE, copy::add);
        if (copy.rows() != source.rows() || !copy.hex().equals(source.hex())) {
            throw new IllegalStateException("the import of game_events into the journal does not read back as the table: table "
                    + source.rows() + " rows up to id " + source.lastSeq() + " sha256 " + source.hex() + ", journal " + copy.rows()
                    + " rows up to seq " + copy.lastSeq() + " sha256 " + copy.hex());
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.infof("the journal is imported from game_events: %d rows up to id %d, sha256 %s, %d ms", source.rows(), source.lastSeq(),
                source.hex(), tookMs);
        return new Result(source.rows(), source.lastSeq(), source.hex(), tookMs);
    }
}
