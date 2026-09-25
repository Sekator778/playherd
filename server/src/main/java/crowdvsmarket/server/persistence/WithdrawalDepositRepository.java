package crowdvsmarket.server.persistence;

import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/** Read model mirror of {@code LedgerBook}'s withdrawal/deposit request state machines (Р-6) —
 *  written alongside the matching {@code GameEvent}, read directly by the API (never replayed;
 *  the in-memory state of record is {@code LedgerBook} itself, rebuilt from events). */
@ApplicationScoped
public class WithdrawalDepositRepository {

    @Inject
    Db db;

    public void insertWithdrawal(Connection c, String requestId, String playerId, long amountMojos, long atMs)
            throws SQLException {
        db.update(c, "INSERT INTO withdrawal_requests (request_id, player_id, amount_mojos, state, requested_at_ms) "
                + "VALUES (?, ?, ?, 'REQUESTED', ?)", requestId, playerId, amountMojos, atMs);
    }

    public void updateWithdrawalState(Connection c, String requestId, Enums.WithdrawalState state)
            throws SQLException {
        db.update(c, "UPDATE withdrawal_requests SET state = ? WHERE request_id = ?", state.name(), requestId);
    }

    public Optional<Ledger.WithdrawalRequestDto> findWithdrawal(String requestId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT * FROM withdrawal_requests WHERE request_id = ?", this::toWithdrawalDto, requestId)));
    }

    private Ledger.WithdrawalRequestDto toWithdrawalDto(java.sql.ResultSet rs) throws SQLException {
        return new Ledger.WithdrawalRequestDto(rs.getString("request_id"), rs.getString("player_id"),
                BigDecimal.valueOf(rs.getLong("amount_mojos"), 3).toPlainString(),
                Enums.WithdrawalState.valueOf(rs.getString("state")), rs.getLong("requested_at_ms"));
    }

    public void insertDeposit(Connection c, String requestId, String playerId, long amountMojos, long atMs)
            throws SQLException {
        db.update(c, "INSERT INTO deposit_requests (request_id, player_id, amount_mojos, state, requested_at_ms) "
                + "VALUES (?, ?, ?, 'REQUESTED', ?)", requestId, playerId, amountMojos, atMs);
    }

    public void updateDepositState(Connection c, String requestId, Enums.DepositState state) throws SQLException {
        db.update(c, "UPDATE deposit_requests SET state = ? WHERE request_id = ?", state.name(), requestId);
    }

    public Optional<Ledger.DepositRequestDto> findDeposit(String requestId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT * FROM deposit_requests WHERE request_id = ?", this::toDepositDto, requestId)));
    }

    private Ledger.DepositRequestDto toDepositDto(java.sql.ResultSet rs) throws SQLException {
        return new Ledger.DepositRequestDto(rs.getString("request_id"), rs.getString("player_id"),
                BigDecimal.valueOf(rs.getLong("amount_mojos"), 3).toPlainString(),
                Enums.DepositState.valueOf(rs.getString("state")), rs.getLong("requested_at_ms"));
    }
}
