package crowdvsmarket.server.events;

import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** Append-only persistence for {@link GameEvent} (the {@code game_events} table) — see that
 *  interface's javadoc for what this log is (and is not) used for. */
@ApplicationScoped
public class EventStore {

    @Inject
    Db db;

    /** Appends one event within the caller's already-open transaction (a mutating service method
     *  always writes its event and its read-model rows in the same {@link Db#tx}). */
    public void append(Connection c, GameEvent event, long atMs) throws SQLException {
        String type = event.getClass().getSimpleName();
        db.update(c, "INSERT INTO game_events (event_type, payload, at_ms) VALUES (?, ?, ?)",
                type, Db.jsonb(event), atMs);
    }

    /** All events in insertion order, for the startup rebuild ({@code EngineHub#rebuild}). */
    public List<GameEvent> loadAll() {
        return db.read(c -> db.queryList(c,
                "SELECT event_type, payload FROM game_events ORDER BY id ASC",
                rs -> decode(rs.getString("event_type"), rs.getString("payload"))));
    }

    private GameEvent decode(String type, String payloadJson) {
        try {
            Class<? extends GameEvent> clazz = switch (type) {
                case "PlayerRegistered" -> GameEvent.PlayerRegistered.class;
                case "BetPlaced" -> GameEvent.BetPlaced.class;
                case "BetCancelled" -> GameEvent.BetCancelled.class;
                case "RoundResolved" -> GameEvent.RoundResolved.class;
                case "RoundCancelled" -> GameEvent.RoundCancelled.class;
                case "WithdrawalRequested" -> GameEvent.WithdrawalRequested.class;
                case "WithdrawalConfirmed" -> GameEvent.WithdrawalConfirmed.class;
                case "WithdrawalFailed" -> GameEvent.WithdrawalFailed.class;
                case "DepositRequested" -> GameEvent.DepositRequested.class;
                case "DepositConfirmed" -> GameEvent.DepositConfirmed.class;
                case "DepositFailed" -> GameEvent.DepositFailed.class;
                default -> throw new IllegalStateException("unknown game_events.event_type: " + type);
            };
            return db.json().readValue(payloadJson, clazz);
        } catch (Exception e) {
            throw new RuntimeException("failed to decode game_events row of type " + type, e);
        }
    }
}
