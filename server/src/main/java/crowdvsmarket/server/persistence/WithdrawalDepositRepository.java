package crowdvsmarket.server.persistence;

import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Read model mirror of {@code LedgerBook}'s withdrawal/deposit request state machines (D-6) —
 *  written alongside the matching {@code GameEvent}, read directly by the API (never replayed;
 *  the in-memory state of record is {@code LedgerBook} itself, rebuilt from events). */
@ApplicationScoped
public class WithdrawalDepositRepository {

    @Inject
    Db db;

    /** D-53: {@code toAddress} is the on-chain destination this withdrawal was submitted to
     *  (the player's own registered wallet address — see {@code GameService#requestWithdrawal}'s
     *  javadoc for why there is no separate "withdraw to a different address" input). */
    public void insertWithdrawal(Connection c, String requestId, String playerId, long amountMojos,
                                  String toAddress, long atMs) throws SQLException {
        db.update(c, "INSERT INTO withdrawal_requests "
                + "(request_id, player_id, amount_mojos, state, to_address, requested_at_ms) "
                + "VALUES (?, ?, ?, 'REQUESTED', ?, ?)", requestId, playerId, amountMojos, toAddress, atMs);
    }

    /** {@link #insertWithdrawal} in a transaction of its own that leaves an existing row alone: the row of a request whose event is in the
     *  journal and whose staged write after it failed (D-107), written by the caller that needs it. */
    public void ensureWithdrawal(String requestId, String playerId, long amountMojos, String toAddress, long atMs) {
        db.tx(c -> db.update(c, "INSERT INTO withdrawal_requests "
                + "(request_id, player_id, amount_mojos, state, to_address, requested_at_ms) "
                + "VALUES (?, ?, ?, 'REQUESTED', ?, ?) ON CONFLICT (request_id) DO NOTHING",
                requestId, playerId, amountMojos, toAddress, atMs));
    }

    public void updateWithdrawalState(Connection c, String requestId, Enums.WithdrawalState state)
            throws SQLException {
        db.update(c, "UPDATE withdrawal_requests SET state = ? WHERE request_id = ?", state.name(), requestId);
    }

    /** D-53: records that this request's {@code POST /v1/play/withdrawals} call was accepted —
     *  {@code WithdrawalPollerService} only polls status for (and only ever retries submission for
     *  the absence of) rows this has touched. Its own small transaction, like every other
     *  read-model write in this class — never carries an engine-state effect, so it needs no
     *  {@code GameEvent}. */
    public void markWithdrawalSubmitted(String requestId, long atMs) {
        db.tx(c -> db.update(c, "UPDATE withdrawal_requests SET submitted_at_ms = ?, chain_state = ? "
                + "WHERE request_id = ?", atMs, Enums.WithdrawalChainState.SUBMITTED.wire(), requestId));
    }

    /** D-53: a non-terminal chain-state refresh from {@code GET /v1/play/withdrawals/{requestId}}
     *  ({@code "queued"}/{@code "in_flight"}) — read-model bookkeeping only, no engine effect
     *  (confirmed/failed go through {@code GameService#confirmWithdrawalOnChain}/
     *  {@code #failWithdrawalOnChain} instead, which also persist a {@code GameEvent}). */
    public void updateWithdrawalChainState(String requestId, String chainState, String coinId, Long height,
                                            long atMs) {
        db.tx(c -> db.update(c, "UPDATE withdrawal_requests SET chain_state = ?, coin_id = ?, height = ?, "
                + "last_checked_at_ms = ? WHERE request_id = ?", chainState, coinId, height, atMs, requestId));
    }

    /** D-53: the real on-chain confirmation — {@code coinId}/{@code height} become public in the
     *  requesting player's own withdrawal-request view ({@code GameService#withdrawal}). Called
     *  from within the same {@code db.tx} as the matching {@code WithdrawalConfirmedOnChain}
     *  event's append (see {@code GameService#confirmWithdrawalOnChain}). */
    public void markWithdrawalConfirmedOnChain(String requestId, String coinId, long height, long atMs) {
        db.tx(c -> {
            markWithdrawalConfirmedOnChain(c, requestId, coinId, height, atMs);
            return null;
        });
    }

    public void markWithdrawalConfirmedOnChain(Connection c, String requestId, String coinId, long height, long atMs)
            throws SQLException {
        db.update(c, "UPDATE withdrawal_requests SET state = 'CONFIRMED', chain_state = ?, "
                + "coin_id = ?, height = ?, last_checked_at_ms = ? WHERE request_id = ?",
                Enums.WithdrawalChainState.CONFIRMED.wire(), coinId, height, atMs, requestId);
    }

    /** D-53: the real on-chain failure — mirrors {@link #markWithdrawalConfirmedOnChain}, called
     *  from within the same {@code db.tx} as the matching {@code WithdrawalFailed} event's append
     *  (see {@code GameService#failWithdrawalOnChain}); the balance refund itself is the event's
     *  engine-side effect, not something this read-model write does. */
    public void markWithdrawalFailed(String requestId, String reason, long atMs) {
        db.tx(c -> {
            markWithdrawalFailed(c, requestId, reason, atMs);
            return null;
        });
    }

    public void markWithdrawalFailed(Connection c, String requestId, String reason, long atMs) throws SQLException {
        db.update(c, "UPDATE withdrawal_requests SET state = 'FAILED', chain_state = ?, "
                + "failure_reason = ?, last_checked_at_ms = ? WHERE request_id = ?",
                Enums.WithdrawalChainState.FAILED.wire(), reason, atMs, requestId);
    }

    /** Requests whose {@code POST /v1/play/withdrawals} call has never yet been accepted — either
     *  never attempted, or attempted and the response was lost/unreachable ({@code
     *  WithdrawalPollerService} retries these every tick; the submit call is "idempotent by
     *  requestId" per the FIXED INTERFACE, so a retry after an actually-successful-but-unobserved
     *  attempt is always safe). */
    public List<Row> pendingSubmission() {
        return db.read(c -> db.queryList(c, "SELECT * FROM withdrawal_requests "
                + "WHERE state = 'REQUESTED' AND submitted_at_ms IS NULL", this::toRow));
    }

    /** Requests already submitted but not yet in a terminal state — {@code
     *  WithdrawalPollerService} polls {@code GET /v1/play/withdrawals/{requestId}} for each of
     *  these every tick. */
    public List<Row> pendingStatusCheck() {
        return db.read(c -> db.queryList(c, "SELECT * FROM withdrawal_requests "
                + "WHERE state = 'REQUESTED' AND submitted_at_ms IS NOT NULL", this::toRow));
    }

    /** A withdrawal request row exactly as read from {@code withdrawal_requests} — the shape
     *  {@code WithdrawalPollerService} needs (not the public {@link Ledger.WithdrawalRequestDto},
     *  which is missing nothing this needs either, but keeping this as its own record avoids
     *  coupling the poller's internal iteration to the DTO's public shape). */
    public record Row(String requestId, String playerId, long amountMojos, String toAddress) {
    }

    private Row toRow(java.sql.ResultSet rs) throws SQLException {
        return new Row(rs.getString("request_id"), rs.getString("player_id"), rs.getLong("amount_mojos"),
                rs.getString("to_address"));
    }

    public Optional<Ledger.WithdrawalRequestDto> findWithdrawal(String requestId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT * FROM withdrawal_requests WHERE request_id = ?", this::toWithdrawalDto, requestId)));
    }

    private Ledger.WithdrawalRequestDto toWithdrawalDto(java.sql.ResultSet rs) throws SQLException {
        Long height = rs.getLong("height");
        if (rs.wasNull()) {
            height = null;
        }
        String coinId = rs.getString("coin_id");
        return new Ledger.WithdrawalRequestDto(rs.getString("request_id"), rs.getString("player_id"),
                BigDecimal.valueOf(rs.getLong("amount_mojos"), 3).toPlainString(),
                Enums.WithdrawalState.valueOf(rs.getString("state")), rs.getLong("requested_at_ms"),
                rs.getString("to_address"), rs.getString("chain_state"), coinId, height,
                rs.getString("failure_reason"), Ledger.coinsetVerifyHint(coinId));
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
