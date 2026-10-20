package crowdvsmarket.bots.catalog;

import crowdvsmarket.bots.brain.BotProfile;
import crowdvsmarket.bots.persona.Personas;
import crowdvsmarket.bots.signal.SignalStrategy;
import java.util.Map;
import java.util.Objects;

/**
 * One bot of the catalog, as data: its {@code catalogId} (written to {@code players.bot_strategy}), display name, the
 * strategy kind with its parameters, and the persona id.
 */
public record CatalogEntry(String catalogId, String name, String strategy, Map<String, Double> params, String persona) {

    public CatalogEntry {
        Objects.requireNonNull(catalogId, "catalogId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(persona, "persona");
        params = Map.copyOf(params);
    }

    /** The signal strategy this entry describes. */
    public SignalStrategy signalStrategy() {
        return Strategies.create(strategy, params);
    }

    /** The profile of the bot {@code botId} that plays as this entry, with the payout-mode share of {@link PayoutModes#configuredShare()}. */
    public BotProfile profile(String botId) {
        return profile(botId, PayoutModes.configuredShare());
    }

    /** The profile of the bot {@code botId} with {@code fixedShare} of the catalog on the fixed payout (Р-106). */
    public BotProfile profile(String botId, double fixedShare) {
        return new BotProfile(botId, signalStrategy(), Personas.byId(persona), PayoutModes.preferredMode(catalogId, fixedShare));
    }
}
