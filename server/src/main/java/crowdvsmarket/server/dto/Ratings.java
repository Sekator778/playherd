package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Ratings". */
public final class Ratings {
    private Ratings() {
    }

    public record RichEntryDto(int rank, Identity.PlayerSummaryDto player, String growth, int roundsPlayed) {
    }

    public record ProphetEntryDto(int rank, Identity.PlayerSummaryDto player, double accuracy, int eligibleRounds,
                                    int roundsPlayed) {
    }

    public record RatingsDto(String season, int roundsThreshold, List<RichEntryDto> rich,
                               List<ProphetEntryDto> prophets) {
    }

    /** The "This week" boards: the same entries as {@link RatingsDto}, over the window that starts at {@code weekStartMs} (Monday
     *  00:00 UTC) and ends now; {@code rich} holds winners only (Р-90). */
    public record WeeklyRatingsDto(long weekStartMs, int roundsThreshold, List<RichEntryDto> rich,
                                     List<ProphetEntryDto> prophets) {
    }
}
