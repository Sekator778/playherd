package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Home aggregate" (Р-34). */
public final class Home {
    private Home() {
    }

    public record HomeSummaryDto(Rounds.RoundsPairDto hourly, Rounds.RoundsPairDto daily,
                                   Crowd.CrowdAccountDto crowd, List<Ratings.RichEntryDto> topRich,
                                   List<Ratings.ProphetEntryDto> topProphets) {
    }
}
