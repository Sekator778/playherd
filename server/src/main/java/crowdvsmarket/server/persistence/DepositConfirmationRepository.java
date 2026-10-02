package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/** Р-53: {@code deposit_confirmations} — the deposit poller's exactly-once guard AND the public
 *  read model of every real on-chain PLAY CAT deposit ever credited (see
 *  {@code DepositPollerService}'s javadoc for the crash-window trade-off {@link #tryReserve}'s
 *  ordering relies on). Never replayed — a coin either already has a row here (already credited,
 *  forever) or it does not (not yet credited). */
@ApplicationScoped
public class DepositConfirmationRepository {

    @Inject
    Db db;

    public record Row(String coinId, String playerId, int playerIndex, long amountMojos, long height, long creditedAtMs) {
    }

    /** Inserts {@code coinId} iff it has never been reserved before, in its own transaction.
     *  @return {@code true} if this call is the one that reserved it (the caller must credit the
     *          balance next); {@code false} if it was already reserved by an earlier call (this
     *          coin was already credited — skip, not an error: chainsvc's {@code GET
     *          /v1/play/deposits} can legitimately re-report the same coin across overlapping
     *          {@code sinceHeight} windows). */
    public boolean tryReserve(String coinId, String playerId, int playerIndex, long amountMojos, long height,
                               long creditedAtMs) {
        return db.tx(c -> tryReserve(c, coinId, playerId, playerIndex, amountMojos, height, creditedAtMs));
    }

    /** {@link #tryReserve(String, String, int, long, long, long)} inside the caller's transaction: {@code
     *  GameService#creditOnChainDeposit} reserves the coin in the very transaction that appends the {@code
     *  DepositConfirmedOnChain} event, so a coin is never reserved without its credit being in the log. */
    public boolean tryReserve(java.sql.Connection c, String coinId, String playerId, int playerIndex,
                               long amountMojos, long height, long creditedAtMs) throws java.sql.SQLException {
        return db.update(c, "INSERT INTO deposit_confirmations "
                        + "(coin_id, player_id, player_index, amount_mojos, height, credited_at_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (coin_id) DO NOTHING",
                coinId, playerId, playerIndex, amountMojos, height, creditedAtMs) == 1;
    }

    public Optional<Row> find(String coinId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT * FROM deposit_confirmations WHERE coin_id = ?", this::toRow, coinId)));
    }

    public List<Row> forPlayer(String playerId) {
        return db.read(c -> db.queryList(c,
                "SELECT * FROM deposit_confirmations WHERE player_id = ? ORDER BY credited_at_ms DESC",
                this::toRow, playerId));
    }

    private Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("coin_id"), rs.getString("player_id"), rs.getInt("player_index"),
                rs.getLong("amount_mojos"), rs.getLong("height"), rs.getLong("credited_at_ms"));
    }
}
