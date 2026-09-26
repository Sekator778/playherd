package crowdvsmarket.server.events;

import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Append-only persistence for {@link GameEvent} (the {@code game_events} table) — see that
 *  interface's javadoc for what this log is (and is not) used for. */
@ApplicationScoped
public class EventStore {

    /** Task item 4 (event schema evolution): the {@code schema_version} every event this server
     *  writes today is stamped with ({@code V3__event_schema_version.sql} adds the column,
     *  {@code DEFAULT 1}, so every already-persisted row is version 1 too). {@link #decode} rejects
     *  any other value loudly rather than silently guessing how to read it, since there is no
     *  version-2 shape/branch to decode into yet — this exists purely as the hook a future,
     *  actual shape change hangs a new branch off of. */
    static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Task item 4 (event schema evolution): an explicit table from an old/renamed
     * {@code game_events.event_type} string to the current one, so renaming a {@link GameEvent}
     * record does not strand already-persisted rows — closing the independent critique's exact
     * scenario ("a class-rename would provably kill server startup entirely, not just one event
     * type", docs/reviews/2026-09-26-independent-critique.md). Empty today — no {@code GameEvent}
     * type has ever actually been renamed — add {@code "OldName", "NewName"} here (keeping the
     * record itself under its new name) the day one is. The resolution mechanism itself (not this
     * currently-empty literal) is exercised directly against a synthetic alias map by
     * {@code EventStoreSchemaEvolutionTest#aliasedTypeNameResolvesToCurrentType}, since a real
     * example would require an actual historical rename that has not happened yet.
     */
    static final Map<String, String> TYPE_ALIASES = Map.of();

    /** The alias-resolution mechanism itself, pulled out as a pure static function so
     *  {@code EventStoreSchemaEvolutionTest} can exercise it directly against a synthetic alias
     *  map (see {@link #TYPE_ALIASES}'s javadoc for why a real example isn't available yet). */
    static String resolveTypeAlias(String type, Map<String, String> aliases) {
        return aliases.getOrDefault(type, type);
    }

    @Inject
    Db db;

    /** Appends one event within the caller's already-open transaction (a mutating service method
     *  always writes its event and its read-model rows in the same {@link Db#tx}). */
    public void append(Connection c, GameEvent event, long atMs) throws SQLException {
        String type = event.getClass().getSimpleName();
        db.update(c, "INSERT INTO game_events (event_type, payload, at_ms, schema_version) VALUES (?, ?, ?, ?)",
                type, Db.jsonb(event), atMs, CURRENT_SCHEMA_VERSION);
    }

    /** All events in insertion order, for the startup rebuild ({@code EngineRebuildService#rebuild}). */
    public List<GameEvent> loadAll() {
        return db.read(c -> db.queryList(c,
                "SELECT id, event_type, payload, schema_version FROM game_events ORDER BY id ASC",
                rs -> decode(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getInt("schema_version"))));
    }

    /** Package-private (not {@code private}) so {@code EventStoreSchemaEvolutionTest} can drive it
     *  directly with a synthetic row id/type/payload/version — testing the real decode path
     *  {@link #loadAll} uses, not a reimplementation of it, the same approach the rest of this
     *  codebase already takes for its regression tests.
     *
     * <p>Task item 4: a row this server cannot decode — an unsupported {@code schemaVersion}, or
     * an {@code event_type} that is neither a known {@link GameEvent} record nor a
     * {@link #TYPE_ALIASES} entry — throws {@link IllegalStateException} naming {@code rowId}
     * (never silently skipped: {@link #loadAll}'s caller, {@code EngineRebuildService#rebuild},
     * replays every row into money-affecting engine state, so silently dropping one is worse than
     * failing startup loudly with enough detail — the row id — for an operator to go look at it). */
    GameEvent decode(long rowId, String type, String payloadJson, int schemaVersion) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("game_events row id=" + rowId + " (type=" + type
                    + ") has unsupported schema_version=" + schemaVersion + " — this server only understands "
                    + CURRENT_SCHEMA_VERSION + "; add a decode branch for it before replaying this data");
        }
        String resolvedType = resolveTypeAlias(type, TYPE_ALIASES);
        try {
            Class<? extends GameEvent> clazz = switch (resolvedType) {
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
                case "WithdrawalReversed" -> GameEvent.WithdrawalReversed.class;
                case "DepositReversed" -> GameEvent.DepositReversed.class;
                case "DepositConfirmedOnChain" -> GameEvent.DepositConfirmedOnChain.class;
                case "WithdrawalConfirmedOnChain" -> GameEvent.WithdrawalConfirmedOnChain.class;
                default -> throw new IllegalStateException("game_events row id=" + rowId
                        + " has unknown event_type: " + type
                        + (resolvedType.equals(type) ? "" : " (alias table pointed it at unknown type: " + resolvedType + ")"));
            };
            return db.json().readValue(payloadJson, clazz);
        } catch (IllegalStateException e) {
            throw e; // already names rowId — don't double-wrap
        } catch (Exception e) {
            throw new RuntimeException("failed to decode game_events row id=" + rowId + " of type " + type, e);
        }
    }
}
