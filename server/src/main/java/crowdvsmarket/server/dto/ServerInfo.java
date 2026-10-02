package crowdvsmarket.server.dto;

/** contract.ts §"FIXED INTERFACES" additive method {@code serverInfo(): Promise<ServerInfoDto>}. */
public final class ServerInfo {
    private ServerInfo() {
    }

    public record ReplayInfoDto(String sourceFromIso, String sourceToIso, double speedup) {
    }

    public record RoundDurationsDto(long hourly, long daily) {
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
     * (Р-45) is offered on the sign-in sheet.
     */
    public record ServerInfoDto(String mode, String priceSource, int markets, ReplayInfoDto replay,
                                  RoundDurationsDto roundDurationsSec, int bots, OracleInfoDto oracle,
                                  long serverTimeMs, String playAssetId, boolean testWallet) {
    }
}
