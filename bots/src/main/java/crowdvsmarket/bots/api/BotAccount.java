package crowdvsmarket.bots.api;

import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.Objects;
import java.util.Set;

/**
 * A bot's own account as the brain sees it.
 *
 * @param balance     PLAY the bot can stake now (stake plus fees must fit in it)
 * @param lastOutcome result of the latest settled bet
 * @param streak      how many settled bets in a row ended like {@code lastOutcome} (0 when {@link LastOutcome#NONE})
 * @param betRoundIds round ids the bot has already bet in (one bet per round of a market)
 */
public record BotAccount(String botId, PlayAmount balance, LastOutcome lastOutcome, int streak, Set<String> betRoundIds) {

    public BotAccount {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        if (streak < 0 || (lastOutcome == LastOutcome.NONE && streak != 0)) {
            throw new IllegalArgumentException("inconsistent streak " + streak + " for " + lastOutcome);
        }
        betRoundIds = Set.copyOf(betRoundIds);
    }

    /** A fresh account with no history. */
    public static BotAccount fresh(String botId, PlayAmount balance) {
        return new BotAccount(botId, balance, LastOutcome.NONE, 0, Set.of());
    }

    public BotAccount withBalance(PlayAmount newBalance) {
        return new BotAccount(botId, newBalance, lastOutcome, streak, betRoundIds);
    }
}
