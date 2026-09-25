package crowdvsmarket.engine.game;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.List;

/** Everything that happened when {@link GameEngine#runRound} resolved one round. */
public record RoundReport(
        RoundWindow window,
        BigDecimal openPrice,
        BigDecimal closePrice,
        List<PlayerRoundOutcome> playerOutcomes,
        CrowdAccount.RoundResult crowdResult) {
}
