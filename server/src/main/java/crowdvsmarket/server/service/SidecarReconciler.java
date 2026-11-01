package crowdvsmarket.server.service;

import crowdvsmarket.engine.ledger.WithdrawalRequest;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.DepositConfirmationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * What the database holds beside the journal and must agree with it (D-107): the journal is the truth and is durable first; the rows
 * below are written a moment later (or, for a deposit coin, a moment earlier), so a crash in between leaves them disagreeing. Run by every
 * rebuild of the engine, which is what a restart and every recovery from a failure are.
 * <ul>
 *   <li><b>Deposit coins.</b> A coin is reserved in {@code deposit_confirmations} (committed) and then its {@code DepositConfirmedOnChain}
 *       event is appended. A reserved coin whose event is not in the journal is a crash in between: the event is appended now, from the
 *       reservation row, which has everything the event has. (Reserving first can lose a credit that a crash delays; appending first
 *       could credit twice.)
 *   <li><b>Withdrawal rows.</b> The row of a withdrawal request, and its terminal state, are written after the event is durable. A request
 *       of the engine without a row, or one the engine has closed while the row says it is still requested, is written from the engine.
 * </ul>
 */
@ApplicationScoped
public class SidecarReconciler {

    private static final Logger LOG = Logger.getLogger(SidecarReconciler.class);

    @Inject
    Db db;
    @Inject
    EventStore eventStore;
    @Inject
    DepositConfirmationRepository depositConfirmations;

    /** Appends the event of every reserved deposit coin that has none in the journal. Before the replay, so that the replay applies them.
     *  @return how many events were appended */
    public int depositEvents() {
        List<DepositConfirmationRepository.Row> reserved = depositConfirmations.all();
        if (reserved.isEmpty()) {
            return 0;
        }
        Set<String> credited = new HashSet<>();
        for (GameEvent e : eventStore.loadOfTypes(List.of("DepositConfirmedOnChain"))) {
            credited.add(((GameEvent.DepositConfirmedOnChain) e).coinId());
        }
        int appended = 0;
        for (DepositConfirmationRepository.Row row : reserved) {
            if (credited.contains(row.coinId())) {
                continue;
            }
            LOG.warnf("deposit coin %s is reserved for %s but its event is not in the journal (the process stopped between the two) - "
                    + "appending it", row.coinId(), row.playerId());
            eventStore.append(null, new GameEvent.DepositConfirmedOnChain(row.playerId(), row.playerIndex(), row.amountMojos(),
                    row.coinId(), row.height(), row.creditedAtMs()), row.creditedAtMs());
            appended++;
        }
        return appended;
    }

    /** Writes the rows of the withdrawal requests of {@code requests} that the table lacks or has behind the engine. */
    public int withdrawalRows(List<WithdrawalRequest> requests, PlayerDirectory players) {
        if (requests.isEmpty()) {
            return 0;
        }
        return db.tx(c -> {
            int written = 0;
            for (WithdrawalRequest w : requests) {
                String toAddress = players.byId(w.playerId()).map(PlayerDirectory.Row::walletAddress).orElse(null);
                int inserted = db.update(c, "INSERT INTO withdrawal_requests (request_id, player_id, amount_mojos, state, to_address, "
                        + "requested_at_ms) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (request_id) DO NOTHING",
                        w.requestId(), w.playerId(), w.amount().mojos(), w.state().name(), toAddress, w.requestedAtEpochMs());
                if (inserted > 0) {
                    LOG.warnf("withdrawal %s is in the journal but its row was never written - written from the engine", w.requestId());
                    written++;
                } else if (w.state() != crowdvsmarket.engine.ledger.WithdrawalState.REQUESTED) {
                    int moved = db.update(c, "UPDATE withdrawal_requests SET state = ? WHERE request_id = ? AND state = 'REQUESTED'",
                            w.state().name(), w.requestId());
                    if (moved > 0) {
                        LOG.warnf("withdrawal %s is %s in the journal but its row says requested - row brought up to date",
                                w.requestId(), w.state());
                        written++;
                    }
                }
            }
            return written;
        });
    }
}
