package crowdvsmarket.engine.ledger;

import crowdvsmarket.engine.season.Season;
import java.util.Map;

/**
 * One player's ledger aggregate: current PLAY balance, the season they are currently active in,
 * and every {@link SeasonRecord} they have ever had (past seasons are kept, not discarded — Р-10:
 * "прошлые сезоны — в архиве").
 */
public record PlayerAccount(String playerId, PlayAmount balance, Season currentSeason, Map<Season, SeasonRecord> seasons) {

    SeasonRecord currentSeasonRecord() {
        return seasons.get(currentSeason);
    }

    /**
     * The player's balance at the end of {@code season}: the {@code baselineBalance} of the earliest record of a later season, or the
     * balance now when there is none (see {@link LedgerBook#seasonEndBalance}, which this is).
     */
    public PlayAmount seasonEndBalance(Season season) {
        Season next = null;
        for (Season candidate : seasons.keySet()) {
            if (candidate.compareTo(season) > 0 && (next == null || candidate.compareTo(next) < 0)) {
                next = candidate;
            }
        }
        return next == null ? balance : seasons.get(next).baselineBalance();
    }
}
