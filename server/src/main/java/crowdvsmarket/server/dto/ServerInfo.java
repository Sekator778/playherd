package crowdvsmarket.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** contract.ts §"FIXED INTERFACES" additive method {@code serverInfo(): Promise<ServerInfoDto>}. */
public final class ServerInfo {
    private ServerInfo() {
    }

    public record ReplayInfoDto(String sourceFromIso, String sourceToIso, double speedup) {
    }

    /** The length of the two series in seconds, on the wire under the {@code Interval} names contract.ts keys them
     *  by — {@code HOURLY} (the shorter) and {@code DAILY} (the longer), whatever their real length: the Status
     *  page read {@code roundDurationsSec.HOURLY} and printed "NaN SEC" for the lower-case keys this record used
     *  to give (parity review, finding 1). */
    public record RoundDurationsDto(@JsonProperty("HOURLY") long hourly, @JsonProperty("DAILY") long daily) {
    }

    /** {@code firstSeq}: the lowest {@code seq} this server's database knows ({@code oracle_records}); records
     *  below it were published before this database existed and can only be checked on chain. {@code null}
     *  until the first record. */
    public record OracleInfoDto(String network, String launcherId, Long lastConfirmedSeq, Long lastConfirmedHeight,
                                  int pendingRecords, String operatorBalanceMojos, Long firstSeq) {
    }

    /**
     * {@code priceSource}: where prices come from (Т-12) — {@code "replay"} in {@code mode=replay}, otherwise
     * {@code herd.prices.source} ({@code "live"} | {@code "synthetic"}); {@code markets}: how many markets are
     * active (played) on this server; {@code playAssetId}: the PLAY CAT's asset id ({@code "0x"} + 64 hex),
     * {@code null} when the configured value is not one; {@code testWallet}: whether the browser test wallet
     * (D-45) is offered on the sign-in sheet; {@code engineReady}: {@code false} while the server rebuilds its
     * state after a failure — every call that reads or changes the engine answers 503 {@code ENGINE_NOT_READY}
     * until it is {@code true} again (contract.ts: absent means true; the server always says).
     */
    public record ServerInfoDto(String mode, String priceSource, int markets, ReplayInfoDto replay,
                                  RoundDurationsDto roundDurationsSec, int bots, OracleInfoDto oracle,
                                  long serverTimeMs, String playAssetId, boolean testWallet, boolean engineReady) {
    }
}
