package crowdvsmarket.server.push;

import java.util.regex.Pattern;

/**
 * The topics of {@code /api/ws}. What a client may subscribe to:
 * <ul>
 *   <li>{@code markets} - the lobby: any market's price or round windows changed;
 *   <li>{@code market:<id>} - one market: its price, its round windows, the crowd of its betting round;
 *   <li>{@code round:<roundId>} - one round: a bet came in, it was settled or cancelled;
 *   <li>{@code feed:<roundId>} - the bet feed of one round;
 *   <li>{@code me} - the signed-in player's own positions and balance (private: needs the session cookie of the upgrade).
 * </ul>
 * Inside the server {@code me} is {@code player:<playerId>}; a client never names another player's topic.
 */
public final class PushTopics {

    public static final String MARKETS = "markets";
    /** What the client calls the private topic. */
    public static final String ME = "me";
    static final String PLAYER_PREFIX = "player:";
    static final String MARKET_PREFIX = "market:";

    private static final Pattern CLIENT_TOPIC = Pattern.compile("^(markets|me|(market|round|feed):[A-Za-z0-9_.:-]{1,80})$");

    private PushTopics() {
    }

    public static String market(String marketId) {
        return MARKET_PREFIX + marketId;
    }

    public static String round(String roundId) {
        return "round:" + roundId;
    }

    public static String feed(String roundId) {
        return "feed:" + roundId;
    }

    public static String player(String playerId) {
        return PLAYER_PREFIX + playerId;
    }

    /** Whether a client may ask for {@code topic}: one of the shapes above, and never an internal {@code player:} topic. */
    public static boolean isClientTopic(String topic) {
        return topic != null && CLIENT_TOPIC.matcher(topic).matches();
    }

    /** The name a client sees: the private topic is always {@code me}. */
    static String publicName(String topic) {
        return topic.startsWith(PLAYER_PREFIX) ? ME : topic;
    }
}
