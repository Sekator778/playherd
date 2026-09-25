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

    record OracleStatus(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                          int pendingRecords, String inFlightBundleId, String operatorBalanceMojos) {
    }

    AuthVerifyResult authVerify(String address, String publicKey, String message, String signature);

    /** @return how many of {@code records} chainsvc queued (its own {@code {queued:int}}). */
    int oracleRecords(List<OracleRecord> records);

    /** {@code null} if chainsvc is unreachable — callers degrade gracefully (never block the game
     *  on chain confirmation), e.g. {@code serverInfo().oracle} reports nulls. */
    OracleStatus oracleStatus();

    boolean health();
}
