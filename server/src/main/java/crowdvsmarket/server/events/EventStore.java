package crowdvsmarket.server.events;

import crowdvsmarket.server.journal.Journal;
import crowdvsmarket.server.journal.JournalProjector;
import crowdvsmarket.server.journal.JournalRecord;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Append-only persistence for {@link GameEvent} — see that interface's javadoc for what this log is (and is not) used for.
 *
 * <p>Since Р-107 the events live in the {@link Journal} (a Chronicle Queue on disk); {@code game_events} is a copy that the
 * {@link JournalProjector} fills after it. Everything that used to read or write the table goes through here and now reads or writes
 * the journal: an event is accepted when {@link #appendAll} has returned (its excerpt is synced to disk), and the rebuild of the engine
 * reads the journal, never the table.
 */
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

    @Inject
    Journal journal;

    /** Lazily: the projector reads events back through this class. */
    @Inject
    Instance<JournalProjector> projector;

    /** Test and diagnostic mode: every append waits until the projector has written the copy, so that a read of {@code game_events}
     *  right after an answer sees it (the server tests run so; production does not: the game never waits for the copy). */
    @ConfigProperty(name = "herd.journal.project-synchronously", defaultValue = "false")
    boolean projectSynchronously;

    /** How long a synchronous append waits for the copy. */
    private static final long PROJECT_WAIT_MS = 30_000L;

    /**
     * Appends one event to the journal (durable when this returns). {@code c} is not used: the journal is not in the caller's
     * transaction any more; the parameter stays so that a caller that writes its own rows in the same transaction reads as before
     * (the event is written first, the rows after it: a crash in between leaves the event, and the rows are repaired from it).
     */
    public void append(Connection c, GameEvent event, long atMs) {
        appendAll(List.of(new Pending(event, atMs)));
    }

    /** The journal's record of {@code event}: the columns of the {@code game_events} row it becomes. */
    public JournalRecord record(Pending p) {
        GameEvent event = p.event();
        try {
            return new JournalRecord(0, event.getClass().getSimpleName(), CURRENT_SCHEMA_VERSION, p.atMs(), marketOf(event),
                    playerOf(event), db.json().writeValueAsString(event));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize " + event.getClass().getSimpleName(), e);
        }
    }

    /**
     * Appends {@code events}, in this order, as ONE excerpt of the journal and syncs it to disk: all or none. This is the durability
     * point of the core writer's batch ({@code CoreWriter}).
     *
     * @return the seq of the last event, the journal's last seq when {@code events} is empty
     */
    public long appendAll(List<Pending> events) {
        if (events.isEmpty()) {
            return journal.lastSeq();
        }
        List<JournalRecord> records = new ArrayList<>(events.size());
        for (Pending p : events) {
            records.add(record(p));
        }
        long last = journal.append(records);
        if (projectSynchronously) {
            projector.get().awaitProjected(last, PROJECT_WAIT_MS);
        }
        return last;
    }

    /**
     * The market an event belongs to, for the {@code game_events.market} column (V20, architecture plan step 1: the journal can be
     * read per market without parsing its JSON), or {@code null} for an event of no market. A round or bet event carries it in
     * {@code market} (BTC when the event was written before markets existed - the record's constructor says so), a listing or
     * closing in {@code marketId}. The switch has no default: a new event type does not compile until it is placed here.
     */
    public static String marketOf(GameEvent event) {
        return switch (event) {
            case GameEvent.BetPlaced e -> e.market();
            case GameEvent.BetCancelled e -> e.market();
            case GameEvent.RoundResolved e -> e.market();
            case GameEvent.RoundCancelled e -> e.market();
            case GameEvent.MarketListed e -> e.marketId();
            case GameEvent.MarketClosed e -> e.marketId();
            case GameEvent.PlayerRegistered e -> null;
            case GameEvent.NicknameChanged e -> null;
            case GameEvent.BotStrategyAssigned e -> null;
            case GameEvent.EconomyCutover e -> null;
            case GameEvent.BotRetired e -> null;
            case GameEvent.HerdSweptToTreasury e -> null;
            case GameEvent.WithdrawalRequested e -> null;
            case GameEvent.WithdrawalConfirmed e -> null;
            case GameEvent.WithdrawalFailed e -> null;
            case GameEvent.DepositRequested e -> null;
            case GameEvent.DepositConfirmed e -> null;
            case GameEvent.DepositFailed e -> null;
            case GameEvent.WithdrawalReversed e -> null;
            case GameEvent.DepositReversed e -> null;
            case GameEvent.DepositConfirmedOnChain e -> null;
            case GameEvent.WithdrawalConfirmedOnChain e -> null;
        };
    }

    /**
     * The player an event is about, for the {@code game_events.player_id} column, or {@code null} for an event that names none (a
     * round, a market, the treasury, and a confirmation or failure that names only its request id). Same rules as
     * {@link #marketOf}.
     */
    public static String playerOf(GameEvent event) {
        return switch (event) {
            case GameEvent.PlayerRegistered e -> e.playerId();
            case GameEvent.NicknameChanged e -> e.playerId();
            case GameEvent.BotStrategyAssigned e -> e.playerId();
            case GameEvent.BetPlaced e -> e.playerId();
            case GameEvent.BetCancelled e -> e.playerId();
            case GameEvent.BotRetired e -> e.playerId();
            case GameEvent.WithdrawalRequested e -> e.playerId();
            case GameEvent.DepositRequested e -> e.playerId();
            case GameEvent.WithdrawalReversed e -> e.playerId();
            case GameEvent.DepositReversed e -> e.playerId();
            case GameEvent.DepositConfirmedOnChain e -> e.playerId();
            case GameEvent.RoundResolved e -> null;
            case GameEvent.RoundCancelled e -> null;
            case GameEvent.EconomyCutover e -> null;
            case GameEvent.HerdSweptToTreasury e -> null;
            case GameEvent.WithdrawalConfirmed e -> null;
            case GameEvent.WithdrawalFailed e -> null;
            case GameEvent.DepositConfirmed e -> null;
            case GameEvent.DepositFailed e -> null;
            case GameEvent.WithdrawalConfirmedOnChain e -> null;
            case GameEvent.MarketListed e -> null;
            case GameEvent.MarketClosed e -> null;
        };
    }

    /** All events in journal order, for the startup rebuild ({@code EngineRebuildService#rebuild}). */
    public List<GameEvent> loadAll() {
        List<GameEvent> all = new ArrayList<>();
        replayAfter(0, (id, event) -> all.add(event));
        return all;
    }

    /** What {@link #replayAfter} hands over: an event with its seq (its place in the journal). */
    @FunctionalInterface
    public interface Replayed {
        void accept(long id, GameEvent event);
    }

    /**
     * Hands every event with {@code seq > afterId} to {@code sink}, in order, read from the journal page by page (architecture plan F5:
     * the journal is not held in memory). A record that cannot be decoded stops the replay with the exception of {@link #decode}.
     *
     * @return the seq of the last event handed over, {@code afterId} when there was none
     */
    public long replayAfter(long afterId, Replayed sink) {
        return replayAfter(afterId, Long.MAX_VALUE, sink);
    }

    /** {@link #replayAfter(long, Replayed)} for the events with {@code afterId < seq <= upToId} only. */
    public long replayAfter(long afterId, long upToId, Replayed sink) {
        return journal.store().replay(afterId, upToId, r -> sink.accept(r.seq(), decode(r)));
    }

    /** The events of the given types ({@code event_type} names), in order: what the repair of a projection folds. */
    public List<GameEvent> loadOfTypes(java.util.Collection<String> types) {
        java.util.Set<String> wanted = new java.util.HashSet<>(types);
        List<GameEvent> out = new ArrayList<>();
        journal.store().replay(0, Long.MAX_VALUE, r -> {
            if (wanted.contains(r.type())) {
                out.add(decode(r));
            }
        });
        return out;
    }

    /** The ids of the rounds with a {@code RoundResolved} or {@code RoundCancelled} event at or before {@code upToId}, read without
     *  decoding the events (their price paths are the bulk of the journal): the rounds that must have a result row. */
    public java.util.Set<String> resolvedRoundIds(long upToId) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        journal.store().replay(0, upToId, r -> {
            if (!"RoundResolved".equals(r.type()) && !"RoundCancelled".equals(r.type())) {
                return;
            }
            try {
                com.fasterxml.jackson.databind.JsonNode json = db.json().readTree(r.payload());
                String market = json.hasNonNull("market") ? json.get("market").asText() : "BTC";
                ids.add(new crowdvsmarket.engine.round.RoundWindow.RoundRef(market,
                        crowdvsmarket.engine.market.Interval.valueOf(json.get("interval").asText()),
                        json.get("windowStart").asLong()).roundId());
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("journal event " + r.seq() + " (RoundResolved) is not JSON", e);
            }
        });
        return ids;
    }

    /** Whether any event at or before {@code upToId} is of schema version 1 (a journal from before F4, which does not carry the
     *  roster). Version 2 is only ever written after version 1, so the first event of the journal says. */
    public boolean hasLegacyRows(long upToId) {
        try (var cursor = journal.store().cursor(0)) {
            List<JournalRecord> first = cursor.next(1);
            return !first.isEmpty() && first.get(0).seq() <= upToId && first.get(0).schemaVersion() == LEGACY_SCHEMA_VERSION;
        }
    }

    /** {@link #decode(long, String, String, int)} for tests in other packages (the cutover test decodes table rows with it). */
    public GameEvent decodeForTests(long rowId, String type, String payloadJson, int schemaVersion) {
        return decode(rowId, type, payloadJson, schemaVersion);
    }

    /** The event a journal record holds. */
    public GameEvent decode(JournalRecord r) {
        return decode(r.seq(), r.type(), r.payload(), r.schemaVersion());
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
            throw new IllegalStateException("journal event seq=" + rowId + " (type=" + type
                    + ") has unsupported schema_version=" + schemaVersion + " — this server only understands "
                    + LEGACY_SCHEMA_VERSION + " and " + CURRENT_SCHEMA_VERSION
                    + "; add a decode branch for it before replaying this data");
        }
        String resolvedType = resolveTypeAlias(type, TYPE_ALIASES);
        if (schemaVersion == LEGACY_SCHEMA_VERSION && SINCE_VERSION_2.contains(resolvedType)) {
            // Version 1: the records read the old shapes as they are (PlayerRegistered without a row is a pack grant, RoundResolved
            // without a sequence number takes it from server_meta when its rows are written); a type born in version 2 cannot be there.
            throw new IllegalStateException("journal event seq=" + rowId + " has event_type " + type
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
                default -> throw new IllegalStateException("journal event seq=" + rowId
                        + " has unknown event_type: " + type
                        + (resolvedType.equals(type) ? "" : " (alias table pointed it at unknown type: " + resolvedType + ")"));
            };
            return db.json().readValue(payloadJson, clazz);
        } catch (IllegalStateException e) {
            throw e; // already names rowId — don't double-wrap
        } catch (Exception e) {
            throw new RuntimeException("failed to decode journal event seq=" + rowId + " of type " + type, e);
        }
    }

    /** An event with the time it is stamped with, waiting to be written ({@link #appendAll}). */
    public record Pending(GameEvent event, long atMs) {
    }

    /** The seq of the last event in the journal ({@code 0} when it is empty): the version of the read views the core writer
     *  publishes after a batch has committed (architecture plan F8), the position a snapshot is taken at (F5). */
    public long lastId() {
        return journal.lastSeq();
    }
}
