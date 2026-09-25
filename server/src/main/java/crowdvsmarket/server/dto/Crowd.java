package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Crowd account" (Р-5, Р-14, Р-17, Р-25, Р-26). */
public final class Crowd {
    private Crowd() {
    }

    public record CrowdHistoryPointDto(String roundId, long atMs, String seasonBoundary, String crowdCapital,
                                         String btcHoldCapital, String antiCrowdCapital) {
    }

    public record SeasonReturnDto(String season, double returnPct) {
    }

    public record CrowdAccountDto(String capital, List<CrowdHistoryPointDto> history,
                                    List<SeasonReturnDto> seasonReturns) {
    }
}
