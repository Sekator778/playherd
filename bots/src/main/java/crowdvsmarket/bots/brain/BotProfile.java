package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.persona.Persona;
import crowdvsmarket.bots.signal.SignalStrategy;
import java.util.Objects;

/** Who a bot is: its id (the seed source and the account key), its rule and its character. */
public record BotProfile(String botId, SignalStrategy strategy, Persona persona) {

    public BotProfile {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(persona, "persona");
    }
}
