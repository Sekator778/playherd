package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Р-53: the chainsvc-derived PLAY CAT deposit address cached per player
 *  ({@code GameService#myDepositAddress}) — {@code player_deposit_addresses}, one row per player,
 *  fetched from chainsvc ({@code POST /v1/play/deposit-address}) at most once, ever (the address
 *  is deterministic given the player's {@code playerIndex}, so there is nothing to refresh). */
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

    public void insert(Connection c, String playerId, String address, String innerPuzzleHash, String catPuzzleHash,
                        long atMs) throws SQLException {
        db.update(c, "INSERT INTO player_deposit_addresses "
                        + "(player_id, address, inner_puzzle_hash, cat_puzzle_hash, created_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?)",
                playerId, address, innerPuzzleHash, catPuzzleHash, atMs);
    }

    private Row toRow(java.sql.ResultSet rs) throws SQLException {
        return new Row(rs.getString("player_id"), rs.getString("address"), rs.getString("inner_puzzle_hash"),
                rs.getString("cat_puzzle_hash"), rs.getLong("created_at_ms"));
    }
}
