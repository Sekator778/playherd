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

    /** The {@code schema_version} every event this server writes is stamped with. Version 2 is architecture plan F4 (the journal
     *  is self-contained): {@code PlayerRegistered} carries the player's row, {@code RoundResolved} the oracle sequence number, and
     *  the types {@code NicknameChanged}, {@code BotStrategyAssigned}, {@code MarketListed}, {@code MarketClosed} exist. A row of
     *  version 1 (all that was written before, {@code V3__event_schema_version.sql} defaults to it) is decoded by the same records,
     *  whose new components are then absent: {@link #decode} has the explicit branch. Any other version is refused loudly. */
    static final int CURRENT_SCHEMA_VERSION = 2;

    /** The version of every journal written before F4. */
    static final int LEGACY_SCHEMA_VERSION = 1;

    /** The event types that exist from version 2 on: a version-1 row cannot be one of them. */
    private static final java.util.Set<String> SINCE_VERSION_2 = java.util.Set.of("NicknameChanged", "BotStrategyAssigned",
            "MarketListed", "MarketClosed");

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
        if (schemaVersion != CURRENT_SCHEMA_VERSION && schemaVersion != LEGACY_SCHEMA_VERSION) {
            throw new IllegalStateException("game_events row id=" + rowId + " (type=" + type
                    + ") has unsupported schema_version=" + schemaVersion + " — this server only understands "
                    + LEGACY_SCHEMA_VERSION + " and " + CURRENT_SCHEMA_VERSION
                    + "; add a decode branch for it before replaying this data");
        }
        String resolvedType = resolveTypeAlias(type, TYPE_ALIASES);
        if (schemaVersion == LEGACY_SCHEMA_VERSION && SINCE_VERSION_2.contains(resolvedType)) {
            // Version 1: the records read the old shapes as they are (PlayerRegistered without a row is a pack grant, RoundResolved
            // without a sequence number takes it from server_meta when its rows are written); a type born in version 2 cannot be there.
            throw new IllegalStateException("game_events row id=" + rowId + " has event_type " + type
                    + " that did not exist in schema_version 1");
        }
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
                case "EconomyCutover" -> GameEvent.EconomyCutover.class;
                case "BotRetired" -> GameEvent.BotRetired.class;
                case "HerdSweptToTreasury" -> GameEvent.HerdSweptToTreasury.class;
                case "NicknameChanged" -> GameEvent.NicknameChanged.class;
                case "BotStrategyAssigned" -> GameEvent.BotStrategyAssigned.class;
                case "MarketListed" -> GameEvent.MarketListed.class;
                case "MarketClosed" -> GameEvent.MarketClosed.class;
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

    /** An event with the time it is stamped with, waiting to be written ({@link #appendAll}). */
    public record Pending(GameEvent event, long atMs) {
    }

    /** The id of the last event in the journal ({@code 0} when it is empty), read in the caller's transaction: the version of
     *  the read views the core writer publishes after a batch has committed (architecture plan F8). */
    public long lastId(Connection c) throws SQLException {
        Long last = db.queryOne(c, "SELECT COALESCE(MAX(id), 0) AS last_id FROM game_events", rs -> rs.getLong("last_id"));
        return last == null ? 0L : last;
    }

    /** {@link #lastId(Connection)} in a transaction of its own. */
    public long lastId() {
        return db.read(this::lastId);
    }

    /** Rows per INSERT statement of {@link #appendAll} (four parameters a row, far below the driver's limit). */
    static final int APPEND_CHUNK = 200;

    /**
     * Appends {@code events}, in this order, within the caller's open transaction: the batch of the core writer
     * ({@code CoreWriter}). Every row is what {@link #append} writes (type, payload, time, schema version); a single event is
     * written by {@link #append} itself, more than one by multi-row {@code INSERT} statements of {@link #APPEND_CHUNK} rows,
     * whose rows get their ids in the order of the {@code VALUES} list.
     */
    public void appendAll(Connection c, List<Pending> events) throws SQLException {
        if (events.isEmpty()) {
            return;
        }
        if (events.size() == 1) {
            append(c, events.get(0).event(), events.get(0).atMs());
            return;
        }
        for (int from = 0; from < events.size(); from += APPEND_CHUNK) {
            List<Pending> chunk = events.subList(from, Math.min(events.size(), from + APPEND_CHUNK));
            StringBuilder sql = new StringBuilder("INSERT INTO game_events (event_type, payload, at_ms, schema_version) VALUES ");
            Object[] params = new Object[chunk.size() * 4];
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
                Pending p = chunk.get(i);
                params[i * 4] = p.event().getClass().getSimpleName();
                params[i * 4 + 1] = Db.jsonb(p.event());
                params[i * 4 + 2] = p.atMs();
                params[i * 4 + 3] = CURRENT_SCHEMA_VERSION;
            }
            db.update(c, sql.toString(), params);
        }
    }
}
