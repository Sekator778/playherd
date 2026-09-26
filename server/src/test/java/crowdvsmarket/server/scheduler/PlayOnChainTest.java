package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.PlayChain;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Р-53: real on-chain PLAY withdraw/deposit against chainsvc's FIXED INTERFACE, mocked here exactly
 * like {@code GameFlowIntegrationTest}/{@code OracleReconciliationServiceTest} — this class never
 * talks to a real chain service. Drives {@link DepositPollerService}/{@link WithdrawalPollerService}
 * directly by calling their {@code poll()} method (same pattern {@code OraclePublisherServiceTest}/
 * {@code OracleReconciliationServiceTest} already use), rather than waiting on the real {@code
 * @Scheduled} cadence.
 */
@QuarkusTest
@TestProfile(PlayOnChainTest.OnChainEnabledProfile.class)
class PlayOnChainTest {

    public static class OnChainEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.deposit.enabled", "true", "herd.withdrawal.enabled", "true");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    GameService gameService;
    @Inject
    PlayerDirectory players;
    @Inject
    DepositPollerService depositPoller;
    @Inject
    WithdrawalPollerService withdrawalPoller;
    @Inject
    EngineRebuildService engineRebuildService;

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1onchain" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    private int playerIndexOf(String playerId) {
        return players.byId(playerId).orElseThrow().playerIndex();
    }

    // --- myDepositAddress: cached after the first chainsvc call ---

    @Test
    void myDepositAddressIsFetchedOnceAndCachedAfterThat() {
        String playerId = signUpPlayer("addr");
        int playerIndex = playerIndexOf(playerId);
        org.mockito.Mockito.when(chainSvcClient.playDepositAddress(playerIndex))
                .thenReturn(new ChainSvcClient.PlayDepositAddress("txch1deposit-addr", "0xinner", "0xcat"));

        PlayChain.DepositAddressDto first = gameService.myDepositAddress(playerId);
        PlayChain.DepositAddressDto second = gameService.myDepositAddress(playerId);

        assertThat(first.address()).isEqualTo("txch1deposit-addr");
        assertThat(second.address()).isEqualTo("txch1deposit-addr");
        org.mockito.Mockito.verify(chainSvcClient, org.mockito.Mockito.times(1)).playDepositAddress(playerIndex);
    }

    @Test
    void myDepositAddressSurfacesChainServiceUnavailableWhenChainsvcIsUnreachable() {
        String playerId = signUpPlayer("addr-unreachable");
        org.mockito.Mockito.when(chainSvcClient.playDepositAddress(org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(ApiException.class, () -> gameService.myDepositAddress(playerId));
    }

    // --- deposit poller: exactly-once crediting per coinId ---

    @Test
    void depositPollerCreditsEachCoinExactlyOnceAcrossOverlappingPolls() {
        String playerId = signUpPlayer("dep-once");
        int playerIndex = playerIndexOf(playerId);
        String balanceBefore = gameService.account(playerId).balance();

        ChainSvcClient.PlayDeposit deposit =
                new ChainSvcClient.PlayDeposit("0xcoin-exactly-once", playerIndex, 500_000L, 4_800_000L);
        // chainsvc re-reports the SAME coin across two ticks (e.g. sinceHeight didn't move) —
        // exactly the case deposit_confirmations' PRIMARY KEY(coin_id) guards against.
        org.mockito.Mockito.when(chainSvcClient.playDeposits(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayDepositsResult(List.of(deposit), 4_800_100L));

        depositPoller.poll();
        depositPoller.poll();

        String balanceAfter = gameService.account(playerId).balance();
        assertThat(new BigDecimal(balanceAfter).subtract(new BigDecimal(balanceBefore)))
                .as("500 PLAY credited exactly once, not twice, for the same coinId")
                .isEqualByComparingTo("500.000");
    }

    @Test
    void depositPollerSkipsAnUnknownPlayerIndexWithoutThrowing() {
        ChainSvcClient.PlayDeposit deposit =
                new ChainSvcClient.PlayDeposit("0xcoin-unknown-index", 999_999, 100_000L, 1L);
        org.mockito.Mockito.when(chainSvcClient.playDeposits(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayDepositsResult(List.of(deposit), 2L));

        depositPoller.poll(); // must not throw
    }

    @Test
    void creditOnChainDepositAppearsAsAPublicLedgerEntry() {
        String playerId = signUpPlayer("dep-ledger");
        gameService.creditOnChainDeposit(playerId, playerIndexOf(playerId), PlayAmount.ofWholePlay(321),
                "0xcoin-ledger-entry", 4_800_200L, System.currentTimeMillis());

        var page = gameService.myLedger(playerId, new crowdvsmarket.server.dto.Paging.PageParams(50, null));
        boolean found = page.items().stream().anyMatch(e ->
                e.type() == crowdvsmarket.server.dto.Enums.LedgerEntryType.DEPOSIT
                        && new BigDecimal(e.delta()).compareTo(new BigDecimal("321.000")) == 0);
        assertThat(found).as("the on-chain deposit must show up in the player's own ledger").isTrue();
    }

    // --- withdrawal: submit -> poll -> confirmed (coinId/height) or failed (refund) ---

    @Test
    void withdrawalConfirmedOnChainExposesCoinIdAndHeightAndDoesNotDebitAgain() {
        String playerId = signUpPlayer("wd-confirm");
        org.mockito.Mockito.when(chainSvcClient.submitPlayWithdrawal(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalSubmitResult(true));

        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(playerId, "1000.000",
                System.currentTimeMillis());
        assertThat(requested.state().name()).isEqualTo("REQUESTED");
        assertThat(requested.coinsetVerifyHint()).as("no coinId yet -> no verify hint yet").isNull();
        String balanceAfterRequest = gameService.account(playerId).balance();
        assertThat(balanceAfterRequest).isEqualTo("9000.000");

        org.mockito.Mockito.when(chainSvcClient.playWithdrawalStatus(requested.requestId()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalStatus(requested.requestId(), "confirmed",
                        "0xcoin-withdrawal-confirmed", 4_800_300L, null));

        withdrawalPoller.poll();

        Ledger.WithdrawalRequestDto confirmed = gameService.withdrawal(playerId, requested.requestId());
        assertThat(confirmed.state().name()).isEqualTo("CONFIRMED");
        assertThat(confirmed.coinId()).isEqualTo("0xcoin-withdrawal-confirmed");
        assertThat(confirmed.height()).isEqualTo(4_800_300L);
        // task item 3: a self-serve, no-trust-required hint the player can run themselves.
        assertThat(confirmed.coinsetVerifyHint()).isEqualTo(
                "POST https://testnet11.api.coinset.org/get_coin_record_by_name "
                        + "{\"name\":\"0xcoin-withdrawal-confirmed\"}");
        // balance unaffected by confirmation — the debit already happened at request time
        assertThat(gameService.account(playerId).balance()).isEqualTo("9000.000");

        // a second poll tick must not try to re-confirm an already-terminal request
        withdrawalPoller.poll();
        assertThat(gameService.account(playerId).balance()).isEqualTo("9000.000");
    }

    @Test
    void withdrawalFailedOnChainRefundsTheEscrowedAmount() {
        String playerId = signUpPlayer("wd-fail");
        org.mockito.Mockito.when(chainSvcClient.submitPlayWithdrawal(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalSubmitResult(true));

        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(playerId, "1500.000",
                System.currentTimeMillis());
        assertThat(gameService.account(playerId).balance()).isEqualTo("8500.000");

        org.mockito.Mockito.when(chainSvcClient.playWithdrawalStatus(requested.requestId()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalStatus(requested.requestId(), "failed", null, null,
                        "fee estimate rejected"));

        withdrawalPoller.poll();

        Ledger.WithdrawalRequestDto failed = gameService.withdrawal(playerId, requested.requestId());
        assertThat(failed.state().name()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("fee estimate rejected");
        assertThat(gameService.account(playerId).balance()).as("escrowed amount refunded on failure")
                .isEqualTo("10000.000");
    }

    @Test
    void withdrawalPollerRetriesSubmissionAfterAnInitialChainsvcOutage() {
        String playerId = signUpPlayer("wd-retry-submit");
        // submitPlayWithdrawal unstubbed -> null -> requestWithdrawal's own synchronous attempt
        // "fails" (degrades gracefully), leaving the request not-yet-submitted.
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(playerId, "300.000",
                System.currentTimeMillis());

        org.mockito.Mockito.when(chainSvcClient.submitPlayWithdrawal(org.mockito.ArgumentMatchers.eq(requested.requestId()),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(300_000L)))
                .thenReturn(new ChainSvcClient.PlayWithdrawalSubmitResult(true));
        org.mockito.Mockito.when(chainSvcClient.playWithdrawalStatus(requested.requestId()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalStatus(requested.requestId(), "queued", null, null, null));

        withdrawalPoller.poll(); // retries submission, then polls status (now "queued")

        Ledger.WithdrawalRequestDto stillPending = gameService.withdrawal(playerId, requested.requestId());
        assertThat(stillPending.state().name()).isEqualTo("REQUESTED");
        assertThat(stillPending.chainState()).isEqualTo("queued");
    }

    // --- replay after restart ---

    @Test
    void onChainDepositAndWithdrawalConfirmationSurviveEngineRebuildReplay() {
        String playerId = signUpPlayer("replay");
        int playerIndex = playerIndexOf(playerId);

        gameService.creditOnChainDeposit(playerId, playerIndex, PlayAmount.ofWholePlay(400),
                "0xcoin-replay-deposit", 4_800_400L, System.currentTimeMillis());

        org.mockito.Mockito.when(chainSvcClient.submitPlayWithdrawal(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalSubmitResult(true));
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(playerId, "600.000",
                System.currentTimeMillis());
        org.mockito.Mockito.when(chainSvcClient.playWithdrawalStatus(requested.requestId()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalStatus(requested.requestId(), "confirmed",
                        "0xcoin-replay-withdrawal", 4_800_500L, null));
        withdrawalPoller.poll();

        String balanceBeforeRebuild = gameService.account(playerId).balance();
        assertThat(balanceBeforeRebuild).isEqualTo("9800.000"); // 10000 + 400 deposit - 600 withdrawal

        engineRebuildService.rebuild();

        assertThat(gameService.account(playerId).balance()).isEqualTo(balanceBeforeRebuild);
        Ledger.WithdrawalRequestDto stillConfirmed = gameService.withdrawal(playerId, requested.requestId());
        assertThat(stillConfirmed.state().name()).isEqualTo("CONFIRMED");
        assertThat(stillConfirmed.coinId()).isEqualTo("0xcoin-replay-withdrawal");
    }

    @Test
    void duplicateChainStatusForASettledWithdrawalIsIgnoredAndReplayStillWorks() {
        String playerId = signUpPlayer("wd-duplicate-status");
        org.mockito.Mockito.when(chainSvcClient.submitPlayWithdrawal(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new ChainSvcClient.PlayWithdrawalSubmitResult(true));
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(playerId, "250.000",
                System.currentTimeMillis());
        long now = System.currentTimeMillis();

        // A second poll pass that read the row as pending before the first one committed ends up
        // calling in again after it — the confirm must not reach the event log twice, and a late
        // "failed" must not refund an already confirmed withdrawal.
        gameService.confirmWithdrawalOnChain(requested.requestId(), "0xcoin-dup", 4_800_600L, now);
        gameService.confirmWithdrawalOnChain(requested.requestId(), "0xcoin-dup", 4_800_600L, now);
        gameService.failWithdrawalOnChain(requested.requestId(), "late failure", now);

        assertThat(gameService.account(playerId).balance()).isEqualTo("9750.000");
        engineRebuildService.rebuild();
        assertThat(gameService.account(playerId).balance()).isEqualTo("9750.000");
        assertThat(gameService.withdrawal(playerId, requested.requestId()).state().name()).isEqualTo("CONFIRMED");
    }

    // --- concurrent requests ---

    @Test
    void concurrentWithdrawalRequestsNeverTakeTheBalanceNegative() throws InterruptedException {
        String playerId = signUpPlayer("concurrent-wd");
        // player starts with 10,000 PLAY (starter pack) — 12 concurrent requests of 1,000 each
        // means at most 10 can legitimately succeed.
        int attempts = 12;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    try {
                        gameService.requestWithdrawal(playerId, "1000.000", System.currentTimeMillis());
                        accepted.incrementAndGet();
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo(crowdvsmarket.server.dto.Enums.ApiErrorCode.WITHDRAWAL_EXCEEDS_BALANCE);
                        rejected.incrementAndGet();
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(unexpected).as("no unexpected exception: %s", unexpected).isEmpty();
        assertThat(accepted.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(attempts - 10);
        assertThat(gameService.account(playerId).balance()).isEqualTo("0.000");
    }

    @Test
    void concurrentDepositCreditsForTheSameCoinIdApplyExactlyOnce() throws InterruptedException {
        String playerId = signUpPlayer("concurrent-dep");
        int playerIndex = playerIndexOf(playerId);
        String balanceBefore = gameService.account(playerId).balance();

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger creditedCount = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    boolean credited = gameService.creditOnChainDeposit(playerId, playerIndex,
                            PlayAmount.ofWholePlay(250), "0xcoin-concurrent-dep", 4_800_600L,
                            System.currentTimeMillis());
                    if (credited) {
                        creditedCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(unexpected).as("no unexpected exception: %s", unexpected).isEmpty();
        assertThat(creditedCount.get()).as("exactly one concurrent caller must win the coinId reservation")
                .isEqualTo(1);
        assertThat(new BigDecimal(gameService.account(playerId).balance()).subtract(new BigDecimal(balanceBefore)))
                .isEqualByComparingTo("250.000");
    }
}
