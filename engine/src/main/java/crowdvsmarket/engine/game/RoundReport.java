package crowdvsmarket.engine.game;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything that happened when {@link GameEngine#runRound} resolved one round. {@code economyRules} is true when the crowd
 * account of the round was run under the economy-stage-A rules (the round held at least one bet placed under them: the
 * round length's cap, {@code l = κ / cap}, the remainder on liquidation); a derived account such as the anti-crowd line
 * follows the same flag.
 */
public record RoundReport(
        RoundWindow window,
        BigDecimal openPrice,
        BigDecimal closePrice,
        List<PlayerRoundOutcome> playerOutcomes,
        CrowdAccount.RoundResult crowdResult,
        boolean economyRules) {

    public RoundReport(RoundWindow window, BigDecimal openPrice, BigDecimal closePrice,
            List<PlayerRoundOutcome> playerOutcomes, CrowdAccount.RoundResult crowdResult) {
        this(window, openPrice, closePrice, playerOutcomes, crowdResult, false);
    }
}
