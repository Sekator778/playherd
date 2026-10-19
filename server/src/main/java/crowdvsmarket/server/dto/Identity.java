package crowdvsmarket.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** contract.ts §"Identity / profile". */
public final class Identity {
    private Identity() {
    }

    public record PlayerSummaryDto(String playerId, String nickname, String walletAddress, String displayName,
                                     Boolean isBot) {
    }

    /** {@code streak}: the win streak over ALL the player's settled rounds, not only the {@code history} page
     *  (Р-71; {@code domain.WinStreak} has the rule). */
    public record PublicProfileDto(PlayerSummaryDto player, Ledger.SeasonRecordDto seasonRecord,
                                     List<ProfileRoundEntryDto> history, StreakDto streak) {
    }

    /** contract.ts {@code PublicProfileDto.streak}: the run of wins that ends at the newest settled round
     *  ({@code current}) and the longest run there has ever been ({@code best}). */
    public record StreakDto(int current, int best) {
    }

    /** {@code asset}: the market's symbol ("BTC", "NVDA"), {@code marketId}: its id; {@code durationSec}: the
     *  real length of the round (its window in {@code round_results}), so a row can be labelled "3 MIN" even
     *  after its market has left {@code markets()}; {@code kind}: the market's kind, for the same reason (a
     *  row of an {@code ODDS} market is worded as odds) — all contract.ts. {@code mode} (Р-106): how the bet was paid; for a
     *  {@code FIXED} one {@code leverage} is a placeholder and {@code liquidated} false. */
    public record ProfileRoundEntryDto(String roundId, Enums.Interval interval, String marketId, String asset,
                                         Long durationSec, Enums.MarketKind kind, long settledAtMs,
                                         Enums.Direction direction, int leverage, String stake, boolean liquidated,
                                         Boolean directionCorrect, String payout, String fees,
                                         @JsonInclude(JsonInclude.Include.NON_NULL) Enums.BetMode mode) {
    }
}
