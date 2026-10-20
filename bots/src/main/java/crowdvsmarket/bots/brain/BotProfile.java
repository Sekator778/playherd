package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.persona.Persona;
import crowdvsmarket.bots.signal.SignalStrategy;
import crowdvsmarket.engine.round.BetMode;
import java.util.Objects;

/**
 * Who a bot is: its id (the seed source and the account key), its rule and its character, and the payout mode it prefers
 * (Р-106): a fixed trait of the bot, {@link BetMode#FIXED} where the market allows it, else the percentage mode.
 */
public record BotProfile(String botId, SignalStrategy strategy, Persona persona, BetMode preferredMode) {

    public BotProfile {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(persona, "persona");
        Objects.requireNonNull(preferredMode, "preferredMode");
    }

    /** A bot that plays the percentage mode. */
    public BotProfile(String botId, SignalStrategy strategy, Persona persona) {
        this(botId, strategy, persona, BetMode.PERCENTAGE);
    }
}
