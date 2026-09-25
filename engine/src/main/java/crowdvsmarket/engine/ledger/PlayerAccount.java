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
}
