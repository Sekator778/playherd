package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Р-53: the two background halves of the real on-chain withdrawal flow that
 * {@code GameService#requestWithdrawal} does not do synchronously — reused pattern from {@code
 * OraclePublisherService}/{@code OracleReconciliationService} (task: "reuse its patterns"), same
 * "never block the game, degrade gracefully on a chainsvc outage" discipline. Every {@link
 * #poll()} tick:
 *
 * <ol>
 *   <li><b>Retry submission</b> for every request {@code requestWithdrawal}'s own synchronous
 *       {@code POST /v1/play/withdrawals} attempt never got a confirmed {@code accepted:true} for
 *       (chainsvc was down, timed out, …) — safe to retry indefinitely because the FIXED INTERFACE
 *       documents that endpoint as idempotent by {@code requestId}.</li>
 *   <li><b>Poll status</b> ({@code GET /v1/play/withdrawals/{requestId}}) for every request already
 *       submitted but not yet terminal, driving {@code confirmed}/{@code failed} into {@code
 *       GameService#confirmWithdrawalOnChain}/{@code #failWithdrawalOnChain} (which persist the
 *       matching {@code GameEvent} and update the read model); {@code queued}/{@code in_flight}
 *       just refresh the read-model's own last-known chain state.</li>
 * </ol>
 *
 * <p>Each row is processed in its own try/catch — one bad/unreachable row never stops the rest of
 * the tick, and a transition {@code LedgerBook} itself rejects (e.g. a request some other path
 * already confirmed) is simply logged and skipped, not retried forever.
 */
@ApplicationScoped
public class WithdrawalPollerService {

    private static final Logger LOG = Logger.getLogger(WithdrawalPollerService.class);

    @Inject
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    ChainSvcClient chainSvc;
    @Inject
    GameService gameService;
    @Inject
    GameConfig config;

    @Scheduled(every = "30s")
    void poll() {
        if (!config.withdrawalEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        retrySubmissions(now);
        pollStatuses(now);
    }

    private void retrySubmissions(long now) {
        List<WithdrawalDepositRepository.Row> pending;
        try {
            pending = withdrawalDeposits.pendingSubmission();
        } catch (Exception e) {
            LOG.debugf(e, "failed to load withdrawals pending submission — will retry next tick");
            return;
        }
        for (WithdrawalDepositRepository.Row row : pending) {
            try {
                ChainSvcClient.PlayWithdrawalSubmitResult result =
                        chainSvc.submitPlayWithdrawal(row.requestId(), row.toAddress(), row.amountMojos());
                if (result != null && result.accepted()) {
                    withdrawalDeposits.markWithdrawalSubmitted(row.requestId(), now);
                }
            } catch (Exception e) {
                LOG.debugf(e, "withdrawal submit retry failed for %s — will retry next tick", row.requestId());
            }
        }
    }

    private void pollStatuses(long now) {
        List<WithdrawalDepositRepository.Row> pending;
        try {
            pending = withdrawalDeposits.pendingStatusCheck();
        } catch (Exception e) {
            LOG.debugf(e, "failed to load withdrawals pending status check — will retry next tick");
            return;
        }
        for (WithdrawalDepositRepository.Row row : pending) {
            try {
                pollOne(row, now);
            } catch (Exception e) {
                LOG.warnf(e, "withdrawal status poll failed for %s — will retry next tick", row.requestId());
            }
        }
    }

    private void pollOne(WithdrawalDepositRepository.Row row, long now) {
        ChainSvcClient.PlayWithdrawalStatus status = chainSvc.playWithdrawalStatus(row.requestId());
        if (status == null) {
            return; // chainsvc unreachable this tick
        }
        switch (status.state()) {
            case "confirmed" -> gameService.confirmWithdrawalOnChain(row.requestId(), status.coinId(),
                    status.height() == null ? 0L : status.height(), now);
            case "failed" -> gameService.failWithdrawalOnChain(row.requestId(), status.reason(), now);
            default -> withdrawalDeposits.updateWithdrawalChainState(row.requestId(), status.state(),
                    status.coinId(), status.height(), now);
        }
    }
}
