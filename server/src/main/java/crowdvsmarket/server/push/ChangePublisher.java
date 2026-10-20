package crowdvsmarket.server.push;

import java.util.Collection;

/**
 * The output side of the game (docs/research/lmax_roadmap.md step 5): the game says <i>what changed</i>, after the change is committed,
 * and whoever listens (the WebSocket hub, {@link PushHub}) tells the clients, who then read the RPC again. A notice carries a topic
 * and a version, never data, so the RPC answers stay the one source of the data shape.
 *
 * <p>Every method is a cheap, non-blocking hand-over: a map look-up and, at most, one task put on the hub's own thread. It never
 * waits for a client, a database or a lock, so it is safe to call under the engine's lock, and it never throws. The one writer
 * of the LMAX plan (F6) calls the same methods after its group commit; nothing here depends on where the caller runs.
 */
public interface ChangePublisher {

    /** Something behind {@code topic} changed ({@link PushTopics}). */
    void changed(String topic);

    /** Every signed-in player's own data may have changed (a round cancelled: who had a bet in it is not at hand). */
    void allPlayersChanged();

    /** A bet was placed or cancelled in {@code roundId} of {@code marketId} by {@code playerId}. */
    default void betChanged(String marketId, String roundId, String playerId) {
        changed(PushTopics.market(marketId));
        changed(PushTopics.round(roundId));
        changed(PushTopics.feed(roundId));
        changed(PushTopics.player(playerId));
    }

    /** A round was settled: the round, the market's windows, and the players who had a bet in it. */
    default void roundSettled(String marketId, String roundId, Collection<String> playerIds) {
        changed(PushTopics.market(marketId));
        changed(PushTopics.round(roundId));
        changed(PushTopics.feed(roundId));
        changed(PushTopics.MARKETS);
        for (String playerId : playerIds) {
            changed(PushTopics.player(playerId));
        }
    }

    /** A round was cancelled. */
    default void roundCancelled(String marketId, String roundId) {
        changed(PushTopics.market(marketId));
        changed(PushTopics.round(roundId));
        changed(PushTopics.feed(roundId));
        changed(PushTopics.MARKETS);
        allPlayersChanged();
    }

    /** Publishes nothing: what a {@code GameService} built by hand (a unit test) uses. */
    ChangePublisher NONE = new ChangePublisher() {
        @Override
        public void changed(String topic) {
        }

        @Override
        public void allPlayersChanged() {
        }
    };
}
