package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Р-53: detects real, chain-confirmed PLAY CAT deposits (chainsvc's {@code GET
 * /v1/play/deposits?sinceHeight=N}) and credits them automatically — the counterpart of a player
 * sending PLAY to their own {@code myDepositAddress()}, with no RPC needed on their end at all.
 * Every 30s tick: reads the {@code sinceHeight} cursor ({@link ServerMetaRepository}), asks
 * chainsvc for everything since, credits each item exactly once
 * ({@code GameService#creditOnChainDeposit}'s {@code deposit_confirmations} reservation — see its
 * javadoc), and only THEN advances the cursor to chainsvc's own reported {@code tipHeight}.
 *
 * <p><b>Why "only then":</b> {@code sinceHeight} is a single global cursor (that is all the FIXED
 * INTERFACE offers — no per-item acknowledgement), so advancing it past a coin this tick failed to
 * even attempt (e.g. chainsvc's response arrived but this server crashed/threw before finishing the
 * loop) would mean that coin is never looked at again. This tick therefore advances the cursor only
 * when every item in the batch was processed without throwing (whether it was freshly credited or
 * already-credited-and-skipped) — a partial failure just means the same, mostly-harmless (already-
 * reserved coins are idempotently skipped) batch is re-fetched and re-walked next tick, which is
 * cheap and always eventually makes progress once the underlying fault clears.
 */
@ApplicationScoped
public class DepositPollerService {

    private static final Logger LOG = Logger.getLogger(DepositPollerService.class);

    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    ChainSvcClient chainSvc;
    @Inject
    PlayerDirectory players;
    @Inject
    GameService gameService;
    @Inject
    GameConfig config;

    // SKIP: a pass slower than 30s (chainsvc timeouts) must not overlap the next one.
    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!config.depositEnabled()) {
            return;
        }
        long sinceHeight = serverMeta.depositPollSinceHeightOrInit();
        ChainSvcClient.PlayDepositsResult result;
        try {
            result = chainSvc.playDeposits(sinceHeight);
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/play/deposits unreachable — will retry next tick");
            return;
        }
        if (result == null) {
            return; // ChainSvcClientImpl already logged the failure
        }
        long now = System.currentTimeMillis();
        boolean allSucceeded = true;
        for (ChainSvcClient.PlayDeposit item : result.deposits()) {
            if (!creditOne(item, now)) {
                allSucceeded = false;
            }
        }
        if (allSucceeded && result.tipHeight() > sinceHeight) {
            serverMeta.advanceDepositPollSinceHeight(result.tipHeight());
        }
    }

    /** @return {@code false} if this item threw (the tick's cursor must not advance past it) —
     *          {@code true} otherwise, whether freshly credited or already-credited-and-skipped. */
    private boolean creditOne(ChainSvcClient.PlayDeposit item, long now) {
        try {
            Optional<String> playerId = players.playerIdForIndex(item.playerIndex());
            if (playerId.isEmpty()) {
                LOG.warnf("chainsvc reported a deposit for unknown playerIndex=%d (coinId=%s) — skipped",
                        item.playerIndex(), item.coinId());
                return true; // not retryable by waiting — no player will ever appear at this index
            }
            gameService.creditOnChainDeposit(playerId.get(), item.playerIndex(), new PlayAmount(item.amountMojos()),
                    item.coinId(), item.height(), now);
            return true;
        } catch (Exception e) {
            LOG.warnf(e, "failed to credit on-chain deposit coinId=%s — will retry next tick", item.coinId());
            return false;
        }
    }
}
