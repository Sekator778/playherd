package crowdvsmarket.server.snapshot;

import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/** The {@code engine_snapshots} table ({@code V19__engine_snapshots.sql}): a cache of the journal, see {@link SnapshotService}. */
@ApplicationScoped
public class EngineSnapshotRepository {

    @Inject
    Db db;

    /** A snapshot's row without its body. */
    public record Meta(long seq, int formatVersion, long takenAtMs, String stateSha256) {
    }

    /** A snapshot's row with its (compressed) body. */
    public record Stored(Meta meta, byte[] body) {
    }

    /** Writes the row; a snapshot of the same {@code seq} is replaced. */
    public void put(long seq, int formatVersion, long takenAtMs, String stateSha256, byte[] body) {
        db.tx(c -> db.update(c, """
                INSERT INTO engine_snapshots (seq, format_version, taken_at_ms, state_sha256, body) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (seq) DO UPDATE SET format_version = EXCLUDED.format_version, taken_at_ms = EXCLUDED.taken_at_ms,
                    state_sha256 = EXCLUDED.state_sha256, body = EXCLUDED.body
                """, seq, formatVersion, takenAtMs, stateSha256, body));
    }

    /** The snapshots, newest (highest {@code seq}) first, without their bodies. */
    public List<Meta> list() {
        return db.read(c -> db.queryList(c,
                "SELECT seq, format_version, taken_at_ms, state_sha256 FROM engine_snapshots ORDER BY seq DESC",
                rs -> new Meta(rs.getLong("seq"), rs.getInt("format_version"), rs.getLong("taken_at_ms"),
                        rs.getString("state_sha256"))));
    }

    /** One snapshot with its body, {@code null} when there is none of that {@code seq}. */
    public Stored find(long seq) {
        return db.read(c -> db.queryOne(c,
                "SELECT seq, format_version, taken_at_ms, state_sha256, body FROM engine_snapshots WHERE seq = ?",
                rs -> new Stored(new Meta(rs.getLong("seq"), rs.getInt("format_version"), rs.getLong("taken_at_ms"),
                        rs.getString("state_sha256")), rs.getBytes("body")), seq));
    }

    /** Deletes all but the {@code keep} newest snapshots. @return how many rows went */
    public int keepNewest(int keep) {
        return db.tx(c -> db.update(c, """
                DELETE FROM engine_snapshots WHERE seq NOT IN (SELECT seq FROM engine_snapshots ORDER BY seq DESC LIMIT ?)
                """, Math.max(1, keep)));
    }
}
