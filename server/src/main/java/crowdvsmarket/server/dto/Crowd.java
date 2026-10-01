package crowdvsmarket.server.dto;

import java.util.List;

/** contract.ts §"Crowd account" (Р-5, Р-14, Р-17, Р-25, Р-26) — one account per market (Т-6). */
public final class Crowd {
    private Crowd() {
    }

    /** {@code holdCapital}: "just hold the asset" on the same starting capital, bought at the market's
     *  first resolved round (Р-26, per market) — was {@code btcHoldCapital} while there was only BTC. */
    public record CrowdHistoryPointDto(String roundId, long atMs, String seasonBoundary, String crowdCapital,
                                         String holdCapital, String antiCrowdCapital) {
    }

    public record SeasonReturnDto(String season, double returnPct) {
    }

    public record CrowdAccountDto(String marketId, String symbol, String capital,
                                    List<CrowdHistoryPointDto> history, List<SeasonReturnDto> seasonReturns) {
    }
}
