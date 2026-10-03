package crowdvsmarket.server.prices;

import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** {@link RoundVenueStore} over plain JDBC like the rest of the server's repositories ({@link Db}). */
@ApplicationScoped
public class JdbcRoundVenueStore implements RoundVenueStore {

    @Inject
    Db db;

    @Override
    public Decision insertOnce(Decision decision) {
        return db.tx(c -> {
            db.update(c, "INSERT INTO round_venues (round_id, market_id, venue, decided_at_ms) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (round_id) DO NOTHING", decision.roundId(), decision.marketId(), decision.venue(),
                    decision.decidedAtMs());
            return db.queryOne(c, "SELECT round_id, market_id, venue, decided_at_ms FROM round_venues WHERE round_id = ?",
                    JdbcRoundVenueStore::decision, decision.roundId());
        });
    }

    @Override
    public Optional<Decision> find(String roundId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT round_id, market_id, venue, decided_at_ms FROM round_venues WHERE round_id = ?",
                JdbcRoundVenueStore::decision, roundId)));
    }

    @Override
    public List<Decision> since(long decidedAtMs) {
        return db.read(c -> db.queryList(c, "SELECT round_id, market_id, venue, decided_at_ms FROM round_venues "
                + "WHERE decided_at_ms >= ? ORDER BY decided_at_ms, round_id", JdbcRoundVenueStore::decision,
                decidedAtMs));
    }

    private static Decision decision(ResultSet rs) throws SQLException {
        return new Decision(rs.getString("round_id"), rs.getString("market_id"), rs.getString("venue"),
                rs.getLong("decided_at_ms"));
    }
}
