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

    record OracleStatus(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                          int pendingRecords, String inFlightBundleId, String operatorBalanceMojos) {
    }

    /** FIXED INTERFACE (b)'s new {@code GET /v1/oracle/records/{seq}} — one record's own
     *  durable-delivery state, polled by {@code OracleReconciliationService} (task item 1c).
     *  {@code state} is one of {@code "queued"|"in_flight"|"confirmed"|"unknown"}; {@code height}/
     *  {@code coinId} are only ever non-null once {@code state} is {@code "confirmed"}. */
    record OracleRecordState(long seq, String state, Long height, String coinId) {
    }

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
