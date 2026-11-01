package crowdvsmarket.server.prices;

import java.util.List;
import java.util.Optional;

/**
 * The {@code round_venues} table (V9): the exchange each round of a market with a backup runs on, chosen once when the
 * round opens (D-76; {@link RoundVenues}). An interface so that the choice can be exercised without a database;
 * {@link JdbcRoundVenueStore} is the real one.
 */
public interface RoundVenueStore {

    /** One decision: the round, its market, the venue ({@code coinbase}, {@code kraken}, {@code hyperliquid}) and when it was made. */
    record Decision(String roundId, String marketId, String venue, long decidedAtMs) {
    }

    /**
     * Writes the decision unless the round already has one ({@code INSERT … ON CONFLICT DO NOTHING}), and returns what
     * the table holds for the round afterwards: the new row, or the one that was there first. Whoever wrote first
     * decided, and nothing writes a round's row twice.
     */
    Decision insertOnce(Decision decision);

    /** What the table holds for the round, if anything. */
    Optional<Decision> find(String roundId);

    /** The decisions made at or after {@code decidedAtMs}, oldest first — what the server loads at start. */
    List<Decision> since(long decidedAtMs);
}
