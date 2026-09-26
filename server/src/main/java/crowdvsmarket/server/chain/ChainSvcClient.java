package crowdvsmarket.server.chain;

import java.util.List;

/**
 * FIXED INTERFACE (3): server ⇄ chainsvc, port 8090. A plain CDI-bean interface (not the raw
 * MicroProfile REST Client type, see {@link ChainSvcRestClient}) so {@code @QuarkusTest}s can
 * swap in a Mockito mock via {@code @InjectMock ChainSvcClient} — the task's "mock the chainsvc
 * client" for the in-process 3-fast-rounds integration test.
 */
public interface ChainSvcClient {

    record AuthVerifyResult(boolean valid, String reason) {
    }

    /** One record of {@code POST /v1/oracle/records}'s {@code records} array. */
    record OracleRecord(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
    }

    /** FIXED INTERFACE (b) response of {@code POST /v1/oracle/records}: {@code queued} is how
     *  many of the request's records chainsvc now durably holds (freshly queued *or* already
     *  known — a record already {@code confirmed}/{@code queued} is accepted idempotently, not an
     *  error, so {@code queued} can equal the request size even when nothing new was queued);
     *  {@code acceptedSeqs} is exactly which {@code seq}s that covers — the caller
     *  ({@code OraclePublisherService}) marks only those {@code chainsvc_queued}, not "the whole
     *  batch iff the count matches", so a partial accept (chainsvc down mid-batch, one bad record,
     *  …) doesn't cause an already-accepted record to be resent forever nor an unaccepted one to
     *  be marked done. */
    record OracleRecordsResult(int queued, List<Long> acceptedSeqs) {
    }

    /** {@code stuckReason}/{@code conflicts} (task item 5, contract test Java<->Rust): present on
     *  chainsvc's real {@code OracleStatusResponse} (chain/c2-svc/src/oracle/types.rs) since the
     *  §11/§12 postmortem fixes but missing from this record until the contract-fixture test
     *  caught the drift — additive, {@code null}/empty until a caller actually needs them. */
    record OracleStatus(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                          int pendingRecords, String inFlightBundleId, String operatorBalanceMojos,
                          String stuckReason, List<Long> conflicts) {
    }

    /** FIXED INTERFACE (b)'s new {@code GET /v1/oracle/records/{seq}} — one record's own
     *  durable-delivery state, polled by {@code OracleReconciliationService} (task item 1c).
     *  {@code state} is one of {@code "queued"|"in_flight"|"confirmed"|"unknown"}; {@code height}/
     *  {@code coinId} are only ever non-null once {@code state} is {@code "confirmed"}. */
    record OracleRecordState(long seq, String state, Long height, String coinId) {
    }

    // --- Р-53: real on-chain PLAY withdraw/deposit (chainsvc FIXED INTERFACE, /v1/play/*) ---

    /** {@code POST /v1/play/withdrawals}'s {@code 202 {accepted:true}} — idempotent by
     *  {@code requestId} per the FIXED INTERFACE, so {@code WithdrawalPollerService} retries a
     *  submission it never confirmed the response of exactly the same way it submits a brand-new
     *  one. */
    record PlayWithdrawalSubmitResult(boolean accepted) {
    }

    /** {@code GET /v1/play/withdrawals/{requestId}}. {@code state} is one of
     *  {@code "queued"|"in_flight"|"confirmed"|"failed"}; {@code coinId}/{@code height} are only
     *  ever non-null once {@code state} is {@code "confirmed"}; {@code reason} is only ever
     *  non-null once {@code state} is {@code "failed"}. */
    record PlayWithdrawalStatus(String requestId, String state, String coinId, Long height, String reason) {
    }

    /** {@code POST /v1/play/deposit-address}'s response — {@code innerPuzzleHash}/{@code
     *  catPuzzleHash} are cached alongside {@code address} ({@code
     *  PlayerDepositAddressRepository}) for this server's own bookkeeping, even though {@code
     *  myDepositAddress()} only ever hands the player {@code address} itself. */
    record PlayDepositAddress(String address, String innerPuzzleHash, String catPuzzleHash) {
    }

    /** One item of {@code GET /v1/play/deposits}'s {@code deposits} array. */
    record PlayDeposit(String coinId, int playerIndex, long amountMojos, long height) {
    }

    /** {@code GET /v1/play/deposits}'s full response — {@code tipHeight} is the next
     *  {@code sinceHeight} {@code DepositPollerService} polls with, once every deposit in this
     *  batch is fully processed (see that class's javadoc). */
    record PlayDepositsResult(List<PlayDeposit> deposits, long tipHeight) {
    }

    /** {@code null} on a chainsvc/network failure — {@code GameService#requestWithdrawal}/{@code
     *  WithdrawalPollerService} treat that as "not yet accepted, retry next tick", never an error
     *  surfaced past the escrow debit that already happened (task: "never block the game on chain
     *  confirmation"). */
    PlayWithdrawalSubmitResult submitPlayWithdrawal(String requestId, String toAddress, long amountMojos);

    /** {@code null} on a chainsvc/network failure — {@code WithdrawalPollerService} just retries
     *  next tick, same "degrade gracefully" contract as {@link #oracleRecordStatus(long)}. */
    PlayWithdrawalStatus playWithdrawalStatus(String requestId);

    /** {@code null} on a chainsvc/network failure — {@code GameService#myDepositAddress} surfaces
     *  {@link crowdvsmarket.server.dto.ApiException#chainServiceUnavailable()} in that case (unlike
     *  most chainsvc reads, there is no cached fallback for a player whose address has genuinely
     *  never been derived yet). */
    PlayDepositAddress playDepositAddress(int playerIndex);

    /** {@code null} on a chainsvc/network failure — {@code DepositPollerService} just retries next
     *  tick with the same {@code sinceHeight} (never advances the cursor without a real response). */
    PlayDepositsResult playDeposits(long sinceHeight);

    AuthVerifyResult authVerify(String address, String publicKey, String message, String signature);

    OracleRecordsResult oracleRecords(List<OracleRecord> records);

    /** {@code null} if chainsvc is unreachable — same "callers degrade gracefully" contract as
     *  {@link #oracleStatus()}; {@code OracleReconciliationService} just retries next tick. */
    OracleRecordState oracleRecordStatus(long seq);

    /** {@code null} if chainsvc is unreachable — callers degrade gracefully (never block the game
     *  on chain confirmation), e.g. {@code serverInfo().oracle} reports nulls. */
    OracleStatus oracleStatus();

    boolean health();
}
