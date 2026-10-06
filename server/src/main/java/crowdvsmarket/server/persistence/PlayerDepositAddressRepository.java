package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.Optional;

/** Р-53: the chainsvc-derived PLAY CAT deposit address cached per player
 *  ({@code GameService#myDepositAddress}) — {@code player_deposit_addresses}, one row per player,
 *  fetched from chainsvc ({@code POST /v1/play/deposit-address}) and written once (the address
 *  is deterministic given the player's {@code playerIndex}, so there is nothing to refresh; two first
 *  calls at once both ask chainsvc and get the same address, and {@link #insertOnce} keeps one row). */
@ApplicationScoped
public class PlayerDepositAddressRepository {

    @Inject
    Db db;

    public record Row(String playerId, String address, String innerPuzzleHash, String catPuzzleHash, long createdAtMs) {
    }

    public Optional<Row> find(String playerId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT * FROM player_deposit_addresses WHERE player_id = ?", this::toRow, playerId)));
    }

    /**
     * Writes the player's address unless the player already has one ({@code INSERT … ON CONFLICT (player_id) DO
     * NOTHING}) and returns the row that is stored afterwards — this call's, or the earlier one's: whoever wrote first
     * stays, an address is never rotated. A plain INSERT here failed with a duplicate key for the second of two first
     * calls of {@code myDepositAddress} at once (one HTTP 500 on the live run of 2026-10-02, {@code
     * docs/product/design/markets-lobby.md} §13.4 S7). The insert and the read-back share one transaction; under
     * READ COMMITTED a writer that waited for the other's commit sees that row in the read.
     */
    public Row insertOnce(String playerId, String address, String innerPuzzleHash, String catPuzzleHash, long atMs) {
        return db.tx(c -> {
            db.update(c, "INSERT INTO player_deposit_addresses "
                            + "(player_id, address, inner_puzzle_hash, cat_puzzle_hash, created_at_ms) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (player_id) DO NOTHING",
                    playerId, address, innerPuzzleHash, catPuzzleHash, atMs);
            Row stored = db.queryOne(c, "SELECT * FROM player_deposit_addresses WHERE player_id = ?", this::toRow,
                    playerId);
            if (stored == null) {
                throw new IllegalStateException("no deposit address row for player " + playerId + " after its insert");
            }
            return stored;
        });
    }

    private Row toRow(java.sql.ResultSet rs) throws SQLException {
        return new Row(rs.getString("player_id"), rs.getString("address"), rs.getString("inner_puzzle_hash"),
                rs.getString("cat_puzzle_hash"), rs.getLong("created_at_ms"));
    }
}
