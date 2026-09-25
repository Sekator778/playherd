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
}
