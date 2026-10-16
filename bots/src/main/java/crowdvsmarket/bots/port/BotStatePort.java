package crowdvsmarket.bots.port;

import crowdvsmarket.bots.api.BotAccount;
import java.util.Optional;

/** A bot's view of its own account. */
public interface BotStatePort {

    /** The account of {@code botId}; empty for an unknown bot. */
    Optional<BotAccount> account(String botId);
}
