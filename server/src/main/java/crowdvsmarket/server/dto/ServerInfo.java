package crowdvsmarket.server.dto;

/** contract.ts §"FIXED INTERFACES" additive method {@code serverInfo(): Promise<ServerInfoDto>}. */
public final class ServerInfo {
    private ServerInfo() {
    }

    public record ReplayInfoDto(String sourceFromIso, String sourceToIso, double speedup) {
    }

    public record RoundDurationsDto(long hourly, long daily) {
    }

    public record OracleInfoDto(String network, String launcherId, Long lastConfirmedSeq, Long lastConfirmedHeight,
                                  int pendingRecords, String operatorBalanceMojos) {
    }

    public record ServerInfoDto(String mode, ReplayInfoDto replay, RoundDurationsDto roundDurationsSec, int bots,
                                  OracleInfoDto oracle, long serverTimeMs) {
    }
}
