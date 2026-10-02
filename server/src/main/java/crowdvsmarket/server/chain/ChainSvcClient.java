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

    /** One record of {@code POST /v1/oracle/records}'s {@code records} array.
     *
     *  <p>{@code asset} is the record's market in the public registry (record format 2,
     *  docs/research/markets_architecture.md §7 п.1; chain/c2-svc {@code OracleRecordDto.asset}):
     *  0 is BTC, the only market before format 2. A chain service older than format 2 drops the
     *  unknown JSON key and would publish a non-zero asset as a BTC price — which is why
     *  {@code OraclePublisherService} sends {@code asset > 0} only after
     *  {@link OracleStatus#supportsAssets()}. */
    record OracleRecord(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs,
                          int asset) {

        /** A BTC record (asset 0): the shape every record had before markets. */
        public OracleRecord(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
            this(seq, ts, priceCents, resultsHash, source, sourceTs, 0);
        }
    }

    /** FIXED INTERFACE (b) response of {@code POST /v1/oracle/records}: {@code acceptedSeqs} is
     *  exactly the {@code seq}s chainsvc <b>newly</b> accepted by this call and {@code queued} their
     *  count. A {@code seq} chainsvc already holds in any state (pending, in flight, confirmed,
     *  conflicting) is an idempotent no-op and is simply left out — so {@code acceptedSeqs} can be
     *  shorter than the request although nothing went wrong (chain/c2-svc
     *  {@code QueueFile::enqueue}, docs/research/chain_service.md §10.7). The caller
     *  ({@code OraclePublisherService}) marks {@code chainsvc_queued} for the seqs named here and asks
     *  {@link #oracleRecordStatus(long)} about the rest, instead of treating "not accepted" as "not
     *  held" (which resent a record chainsvc already had on every tick, forever). A chainsvc outage
     *  also comes back as an empty result — never as an exception. */
    record OracleRecordsResult(int queued, List<Long> acceptedSeqs) {
    }

    /** {@code GET /v1/oracle/status}. {@code stuckReason}/{@code conflicts} (task item 5, contract
     *  test Java<->Rust): present on chainsvc's real {@code OracleStatusResponse}
     *  (chain/c2-svc/src/oracle/types.rs) since the §11/§12 postmortem fixes but missing from this
     *  record until the contract-fixture test caught the drift.
     *
     *  <p>Record format 2 (docs/research/chain_service.md §16.3) adds the last four fields, all
     *  {@code null} when the chain service predates them: {@code maxKnownSeq} — the highest
     *  {@code seq} it knows in any state (what the server raises its own counter to,
     *  {@link OracleSeqGate}); {@code recordFormat} — 1 = records without {@code asset}, 2 = with
     *  (<b>absent means 1</b>, see {@link #effectiveRecordFormat()}); {@code feeCoinsFree} /
     *  {@code feeCoinsFreeMojos} — the operator wallet's spare fee coins ({@code null} also before
     *  the first wallet scan), the mojos as a decimal string like {@code operatorBalanceMojos}. */
    record OracleStatus(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                          int pendingRecords, String inFlightBundleId, String operatorBalanceMojos,
                          String stuckReason, List<Long> conflicts, Long maxKnownSeq, Integer recordFormat,
                          Integer feeCoinsFree, String feeCoinsFreeMojos) {

        /** A status as chainsvc sent it before record format 2 (no format-2 fields). */
        public OracleStatus(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                            int pendingRecords, String inFlightBundleId, String operatorBalanceMojos,
                            String stuckReason, List<Long> conflicts) {
            this(launcherId, network, lastConfirmedSeq, lastConfirmedHeight, pendingRecords, inFlightBundleId,
                    operatorBalanceMojos, stuckReason, conflicts, null, null, null, null);
        }

        /** {@code recordFormat}, with a chain service that does not send it counted as format 1. */
        public int effectiveRecordFormat() {
            return recordFormat == null ? 1 : recordFormat;
        }

        /** True when records with {@code asset > 0} may be sent: the chain service says format 2 or
         *  later. Format 1 — and a status that does not say — would publish them as BTC. */
        public boolean supportsAssets() {
            return effectiveRecordFormat() >= 2;
        }
    }

    /** FIXED INTERFACE (b)'s new {@code GET /v1/oracle/records/{seq}} — one record's own
     *  durable-delivery state, polled by {@code OracleReconciliationService} (task item 1c).
     *  {@code state} is one of {@code "queued"|"in_flight"|"confirmed"|"unknown"}; {@code height}/
     *  {@code coinId} are only ever non-null once {@code state} is {@code "confirmed"}. */
    record OracleRecordState(long seq, String state, Long height, String coinId) {
    }

    // --- Р-53: real on-chain PLAY withdraw/deposit (chainsvc FIXED INTERFACE, /v1/play/*) ---

    /** {@code POST /v1/play/withdrawals}'s answer. {@code 202 {accepted:true}} — idempotent by
     *  {@code requestId} and content per the FIXED INTERFACE, so {@code WithdrawalPollerService} retries a
     *  submission it never confirmed the response of exactly the same way it submits a brand-new one — or
     *  HTTP 409 (E3, chain/c2-svc {@code WithdrawalConflictResponse}, {@code docs/research/chain_service.md}
     *  §17.6): the chain service already holds this {@code requestId} with another recipient or amount (or
     *  cannot tell), and refuses it rather than answer with the old request's status. A conflict is not a
     *  transient failure: retrying it changes nothing, so it is a result of its own — {@link #isConflict()},
     *  with the chain service's own text in {@link #conflict()} — and {@code accepted} is {@code false}. */
    record PlayWithdrawalSubmitResult(boolean accepted, String conflict) {

        /** An answer without a conflict. */
        public PlayWithdrawalSubmitResult(boolean accepted) {
            this(accepted, null);
        }

        /** The chain service refused the id with HTTP 409; {@code message} is its {@code error} text. */
        public static PlayWithdrawalSubmitResult refusedAsConflict(String message) {
            return new PlayWithdrawalSubmitResult(false, message == null ? "request id conflict" : message);
        }

        public boolean isConflict() {
            return conflict != null;
        }
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

    /** {@code null} on a chainsvc/network failure — {@code WithdrawalSubmitter} treats that as "not yet
     *  accepted, retry next tick", never an error surfaced past the escrow debit that already happened
     *  (task: "never block the game on chain confirmation"). {@code requestId} is the id the chain service
     *  knows the withdrawal by ({@link ChainRequestIds#forWithdrawal}), not the engine's own. A refusal with
     *  HTTP 409 is a {@link PlayWithdrawalSubmitResult#isConflict() conflict result}, not {@code null}. */
    PlayWithdrawalSubmitResult submitPlayWithdrawal(String requestId, String toAddress, long amountMojos);

    /** {@code null} on a chainsvc/network failure — {@code WithdrawalPollerService} just retries
     *  next tick, same "degrade gracefully" contract as {@link #oracleRecordStatus(long)}. {@code requestId}
     *  is the chain service's id for the withdrawal ({@link ChainRequestIds#forWithdrawal}). */
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
