package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Home aggregate" (D-34). */
public final class Home {
    private Home() {
    }

    public record HomeSummaryDto(Rounds.RoundsPairDto hourly, Rounds.RoundsPairDto daily,
                                   Crowd.CrowdAccountDto crowd, List<Ratings.RichEntryDto> topRich,
                                   List<Ratings.ProphetEntryDto> topProphets) {
    }
}
