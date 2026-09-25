package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Identity / profile". */
public final class Identity {
    private Identity() {
    }

    public record PlayerSummaryDto(String playerId, String nickname, String walletAddress, String displayName,
                                     Boolean isBot) {
    }

    public record PublicProfileDto(PlayerSummaryDto player, Ledger.SeasonRecordDto seasonRecord,
                                     List<ProfileRoundEntryDto> history) {
    }

    public record ProfileRoundEntryDto(String roundId, Enums.Interval interval, long settledAtMs,
                                         Enums.Direction direction, int leverage, String stake, boolean liquidated,
                                         Boolean directionCorrect, String payout) {
    }
}
