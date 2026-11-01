package crowdvsmarket.server.domain;

import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code players} dimension table (wallet address, nickname, bot flag/strategy) plus an
 * in-memory cache of it. Since architecture plan F4 the table is a projection of the journal, and since D-107 it is written by the
 * journal projector after the event is durable ({@link #projectEvents}), and a rebuild repairs it from the journal
 * ({@link #repairFromJournal}) before the cache is read. Not in the journal, by design: the API key of an API bot and the referral
 * columns, which live in {@code player_sidecar} (written before the command) and are copied into the row by the projector.
 *
 * <p><b>The cache never runs ahead of the table.</b> {@link #insert} and {@link #updateNickname}
 * write the row inside the caller's transaction and touch no memory; the caller puts the result
 * into the cache with {@link #cache}/{@link #cacheNickname} once the transaction has committed
 * (a commit that fails would otherwise leave a player in memory that the database does not have).
 * {@link #reload} reads the whole table first and swaps the cache in one step, so a failed read
 * leaves it as it was, and a lock-free reader never sees a half-filled one.
 */
@ApplicationScoped
public final class PlayerDirectory {

    @Inject
    Db db;

    /** The three lookups, always swapped together. */
    private static final class Maps {
        final Map<String, Row> byId = new ConcurrentHashMap<>();
        final Map<String, String> idByWallet = new ConcurrentHashMap<>();
        /** D-53: reverse lookup for the deposit poller — chainsvc's {@code GET /v1/play/deposits}
         *  reports each coin's {@code playerIndex}, never a {@code playerId}. */
        final Map<Integer, String> idByPlayerIndex = new ConcurrentHashMap<>();
        /** D-106: the live API keys, by the SHA-256 of the key. */
        final Map<String, String> idByKeyHash = new ConcurrentHashMap<>();

        void put(Row row) {
            Row previous = byId.put(row.playerId(), row);
            idByWallet.put(row.walletAddress(), row.playerId());
            idByPlayerIndex.put(row.playerIndex(), row.playerId());
            if (previous != null && previous.keyHash() != null) {
                idByKeyHash.remove(previous.keyHash(), row.playerId());
            }
            if (row.keyHash() != null) {
                idByKeyHash.put(row.keyHash(), row.playerId());
            }
        }
    }

    private volatile Maps maps = new Maps();

    /** What kind of player a row is (column {@code kind}, V17). {@code is_bot} stays what it was: the house's own bots only. */
    public static final String KIND_HUMAN = "HUMAN";
    public static final String KIND_HOUSE_BOT = "HOUSE_BOT";
    /** D-106: a bot of an outside developer, owned by a person and played through the API with a key. */
    public static final String KIND_API_BOT = "API_BOT";

    /**
     * {@code retiredAtMs}: when a bot retired (economy stage B4), {@code null} for everyone else; {@code replaced}: a new bot has taken
     * the place of a retired one. A retired bot is in no list.
     *
     * <p>{@code kind}, {@code ownerId}, {@code keyHash}, {@code keyHint} (D-106): only an API bot has an owner and a key; {@code keyHash}
     * is {@code null} for a bot whose key was revoked and never leaves the server (the DTOs carry {@code keyHint} only).
     */
    public record Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                        int playerIndex, long createdAtMs, Long retiredAtMs, boolean replaced,
                        String kind, String ownerId, String keyHash, String keyHint) {

        public Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                   int playerIndex, long createdAtMs, Long retiredAtMs, boolean replaced) {
            this(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, retiredAtMs, replaced,
                    isBot ? KIND_HOUSE_BOT : KIND_HUMAN, null, null, null);
        }

        public Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                   int playerIndex, long createdAtMs) {
            this(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, null, false);
        }

        public boolean isRetired() {
            return retiredAtMs != null;
        }

        public boolean isApiBot() {
            return KIND_API_BOT.equals(kind);
        }

        /** Whether the web app badges this player as a bot: the house's bots and the API bots alike. */
        public boolean displaysAsBot() {
            return isBot || isApiBot();
        }

        Row withNickname(String value) {
            return new Row(playerId, walletAddress, value, isBot, botStrategy, playerIndex, createdAtMs, retiredAtMs, replaced,
                    kind, ownerId, keyHash, keyHint);
        }

        Row withRetiredAt(Long value) {
            return new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, value, replaced,
                    kind, ownerId, keyHash, keyHint);
        }

        Row withReplaced(boolean value) {
            return new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, retiredAtMs, value,
                    kind, ownerId, keyHash, keyHint);
        }

        Row withBotStrategy(String value) {
            return new Row(playerId, walletAddress, nickname, isBot, value, playerIndex, createdAtMs, retiredAtMs, replaced,
                    kind, ownerId, keyHash, keyHint);
        }

        Row withKey(String hash, String hint) {
            return new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, retiredAtMs, replaced,
                    kind, ownerId, hash, hint);
        }

        public PlayerIdentity identity() {
            return new PlayerIdentity(playerId, nickname == null ? shortWallet(walletAddress) : nickname,
                    walletAddress);
        }

        private static String shortWallet(String address) {
            return address.length() <= 14 ? address : address.substring(0, 6) + "…" + address.substring(address.length() - 4);
        }
    }

    /** Reloads the whole cache from the DB — called at startup and at every rebuild, after the DB
     *  connection (and Flyway migration) are ready, before the event replay that depends on some of
     *  these ids already resolving to a wallet address for display purposes. */
    public synchronized void reload() {
        List<Row> rows = db.read(c -> db.queryList(c,
                "SELECT player_id, wallet_address, nickname, is_bot, bot_strategy, player_index, created_at_ms, "
                        + "retired_at_ms, replaced, kind, owner_player_id, api_key_hash, api_key_hint FROM players",
                rs -> new Row(rs.getString("player_id"), rs.getString("wallet_address"), rs.getString("nickname"),
                        rs.getBoolean("is_bot"), rs.getString("bot_strategy"), rs.getInt("player_index"),
                        rs.getLong("created_at_ms"), (Long) rs.getObject("retired_at_ms"), rs.getBoolean("replaced"),
                        rs.getString("kind"), rs.getString("owner_player_id"), rs.getString("api_key_hash"),
                        rs.getString("api_key_hint"))));
        Maps fresh = new Maps();
        for (Row row : rows) {
            fresh.put(row);
        }
        maps = fresh;
    }

    public Optional<Row> byId(String playerId) {
        return Optional.ofNullable(maps.byId.get(playerId));
    }

    public Optional<String> playerIdForWallet(String walletAddress) {
        return Optional.ofNullable(maps.idByWallet.get(walletAddress));
    }

    /** D-53: resolves a chainsvc-reported {@code playerIndex} (deposit poller) back to a
     *  {@code playerId} — {@link Optional#empty()} if unknown (defensive; should not happen for a
     *  coin chainsvc itself derived from an index this server handed out). */
    public Optional<String> playerIdForIndex(int playerIndex) {
        return Optional.ofNullable(maps.idByPlayerIndex.get(playerIndex));
    }

    /** D-106: the API bot a live key belongs to, by the SHA-256 (hex) of the key; empty for an unknown or revoked key. */
    public Optional<Row> byKeyHash(String keyHash) {
        String id = maps.idByKeyHash.get(keyHash);
        return id == null ? Optional.empty() : byId(id);
    }

    /** D-106: the API bots a person owns (revoked keys included: a bot is never deleted), oldest first. */
    public List<Row> apiBotsOf(String ownerId) {
        return maps.byId.values().stream().filter(r -> r.isApiBot() && ownerId.equals(r.ownerId()))
                .sorted(java.util.Comparator.comparingLong(Row::createdAtMs).thenComparing(Row::playerId)).toList();
    }

    public Collection<Row> all() {
        return List.copyOf(maps.byId.values());
    }

    public List<Row> bots() {
        return maps.byId.values().stream().filter(Row::isBot).toList();
    }

    /** Inserts a brand-new player row within the caller's transaction and returns it; the cache is not touched. Not used by the game any
     *  more (D-107: the projector writes the rows, {@link #projectEvents}); the directory's own test builds its fixtures with it. */
    public Row insert(Connection c, String playerId, String walletAddress, String nickname, boolean isBot,
                        String botStrategy, int playerIndex, long atMs) throws SQLException {
        db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, "
                        + "player_index, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
        return new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
    }

    /** The row the projector writes ({@link #projectEvents}) for the registration event, without writing it: what the cache gets at once, in
     *  the command that records the event. */
    public static Row rowOf(GameEvent.PlayerRegistered e, String keyHash, String keyHint) {
        return new Row(e.playerId(), e.walletAddress(), e.displayName(), KIND_HOUSE_BOT.equals(e.kind()), e.botStrategy(),
                e.playerIndex(), e.atMs(), null, false, e.kind(), e.ownerPlayerId(), keyHash, keyHint);
    }

    /** What the journal says about one player, folded in order: the target of {@link #repairFromJournal}. */
    private static final class Folded {
        GameEvent.PlayerRegistered registered;
        String nickname;
        String botStrategy;
        Long retiredAtMs;
    }

    /**
     * F4: the {@code players} table is a projection of the journal. Folds the registration, nickname, strategy and retirement events
     * of {@code events} and makes the table agree: a player the table lacks is inserted (a database built from the journal alone, or
     * a write that never committed), a nickname, strategy, retirement or replacement that differs is written. A player whose
     * registration is not in the journal (a journal of schema version 1) is left exactly as the table has it. The cache is reloaded
     * when anything changed.
     *
     * @return the number of rows written
     */
    public synchronized int repairFromJournal(List<GameEvent> events) {
        Map<String, Folded> folded = new java.util.LinkedHashMap<>();
        Set<String> replacedIds = new HashSet<>();
        for (GameEvent event : events) {
            switch (event) {
                case GameEvent.PlayerRegistered e when e.isRegistration() -> {
                    Folded f = folded.computeIfAbsent(e.playerId(), id -> new Folded());
                    if (f.registered == null) {
                        f.registered = e;
                        f.nickname = e.displayName();
                        f.botStrategy = e.botStrategy();
                        if (e.replacesPlayerId() != null) {
                            replacedIds.add(e.replacesPlayerId());
                        }
                    }
                }
                case GameEvent.NicknameChanged e -> folded.computeIfAbsent(e.playerId(), id -> new Folded()).nickname = e.nickname();
                case GameEvent.BotStrategyAssigned e ->
                        folded.computeIfAbsent(e.playerId(), id -> new Folded()).botStrategy = e.botStrategy();
                case GameEvent.BotRetired e -> folded.computeIfAbsent(e.playerId(), id -> new Folded()).retiredAtMs = e.atMs();
                default -> {
                }
            }
        }
        int written = db.tx(c -> {
            int n = 0;
            for (Map.Entry<String, Folded> entry : folded.entrySet()) {
                Folded f = entry.getValue();
                Row row = maps.byId.get(entry.getKey());
                if (row == null) {
                    if (f.registered != null) {
                        n += db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, "
                                        + "player_index, created_at_ms, kind, owner_player_id, retired_at_ms, replaced) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                                f.registered.playerId(), f.registered.walletAddress(), f.nickname,
                                KIND_HOUSE_BOT.equals(f.registered.kind()), f.botStrategy, f.registered.playerIndex(),
                                f.registered.atMs(), f.registered.kind(), f.registered.ownerPlayerId(), f.retiredAtMs,
                                replacedIds.contains(entry.getKey()));
                    }
                    continue;
                }
                if (f.registered == null) {
                    continue; // registered before F4: the table is the only record
                }
                if (!java.util.Objects.equals(row.nickname(), f.nickname)) {
                    n += db.update(c, "UPDATE players SET nickname = ? WHERE player_id = ?", f.nickname, entry.getKey());
                }
                if (row.isBot() && !java.util.Objects.equals(row.botStrategy(), f.botStrategy)) {
                    n += db.update(c, "UPDATE players SET bot_strategy = ? WHERE player_id = ?", f.botStrategy, entry.getKey());
                }
                if (f.retiredAtMs != null && row.retiredAtMs() == null) {
                    n += db.update(c, "UPDATE players SET retired_at_ms = ? WHERE player_id = ?", f.retiredAtMs, entry.getKey());
                }
            }
            for (String id : replacedIds) {
                Row row = maps.byId.get(id);
                if (row != null && !row.replaced()) {
                    n += db.update(c, "UPDATE players SET replaced = TRUE WHERE player_id = ?", id);
                }
            }
            return n;
        });
        if (written > 0) {
            reload();
        }
        return written;
    }

    /**
     * Writes a bot's key (a new one, or {@code null} for a revoked one) within the caller's transaction ({@link #cacheKey} after the
     * commit). The key is not in the journal: {@code player_sidecar} is where it lives (V21) - written first, before the command that
     * registers the bot, and here for a new or revoked key - and the {@code players} row, when the projector has written it, gets the same
     * value in the same transaction (a row it has not written yet gets the key from the sidecar when it does).
     */
    public void updateKey(Connection c, String playerId, String keyHash, String keyHint) throws SQLException {
        db.update(c, "INSERT INTO player_sidecar (player_id, api_key_hash, api_key_hint) VALUES (?, ?, ?) "
                + "ON CONFLICT (player_id) DO UPDATE SET api_key_hash = EXCLUDED.api_key_hash, api_key_hint = EXCLUDED.api_key_hint",
                playerId, keyHash, keyHint);
        db.update(c, "UPDATE players SET api_key_hash = ?, api_key_hint = ? WHERE player_id = ? AND kind = ?",
                keyHash, keyHint, playerId, KIND_API_BOT);
    }

    /** Takes back the sidecar row of an API bot whose registration did not happen. */
    public void forgetKey(String playerId) {
        db.tx(c -> db.update(c, "DELETE FROM player_sidecar WHERE player_id = ?", playerId));
    }

    /**
     * D-107: writes the {@code players} rows that the journal's events describe, in the projector's transaction: a registration inserts
     * the row (and takes the invite columns and the key from {@code player_sidecar}), a nickname, a strategy or a retirement updates it.
     * Idempotent: a row that exists is left as it is by the insert, the updates write what the journal says. Events of other types are
     * ignored. Does not touch the cache (the commands that made the events did).
     */
    public void projectEvents(Connection c, List<GameEvent> events) throws SQLException {
        for (GameEvent event : events) {
            switch (event) {
                case GameEvent.PlayerRegistered e when e.isRegistration() -> {
                    db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, player_index, "
                                    + "created_at_ms, kind, owner_player_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                            e.playerId(), e.walletAddress(), e.displayName(), KIND_HOUSE_BOT.equals(e.kind()), e.botStrategy(),
                            e.playerIndex(), e.atMs(), e.kind(), e.ownerPlayerId());
                    db.update(c, "UPDATE players p SET invite_code = s.invite_code, referred_by = s.referred_by, source = s.source, "
                            + "api_key_hash = s.api_key_hash, api_key_hint = s.api_key_hint FROM player_sidecar s "
                            + "WHERE s.player_id = p.player_id AND p.player_id = ?", e.playerId());
                    if (e.replacesPlayerId() != null) {
                        db.update(c, "UPDATE players SET replaced = TRUE WHERE player_id = ?", e.replacesPlayerId());
                    }
                }
                case GameEvent.NicknameChanged e ->
                        db.update(c, "UPDATE players SET nickname = ? WHERE player_id = ?", e.nickname(), e.playerId());
                case GameEvent.BotStrategyAssigned e ->
                        db.update(c, "UPDATE players SET bot_strategy = ? WHERE player_id = ? AND is_bot", e.botStrategy(), e.playerId());
                case GameEvent.BotRetired e ->
                        db.update(c, "UPDATE players SET retired_at_ms = ? WHERE player_id = ?", e.atMs(), e.playerId());
                default -> {
                }
            }
        }
    }

    /** The cache's side of {@link #updateKey}, after the commit: the old hash stops opening the bot at once. */
    public void cacheKey(String playerId, String keyHash, String keyHint) {
        Maps current = maps;
        Row prev = current.byId.get(playerId);
        if (prev != null) {
            current.put(prev.withKey(keyHash, keyHint));
        }
    }

    /** Puts a row {@link #insert} returned into the cache — after its transaction has committed. */
    public void cache(Row row) {
        maps.put(row);
    }

    /** Writes the nickname within the caller's transaction; the cache is not touched — call
     *  {@link #cacheNickname} after the commit. */
    public void updateNickname(Connection c, String playerId, String nickname) throws SQLException {
        db.update(c, "UPDATE players SET nickname = ? WHERE player_id = ?", nickname, playerId);
    }

    /** The cache's side of {@link #updateNickname}, after the commit. */
    public void cacheNickname(String playerId, String nickname) {
        Maps current = maps;
        current.byId.computeIfPresent(playerId, (id, prev) -> prev.withNickname(nickname));
    }

    /** The cache's side of {@link #updateRetired}, after the commit. */
    public void cacheRetired(String playerId, long atMs) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> prev.withRetiredAt(atMs));
    }

    /** The cache's side of {@link #updateReplaced}, after the commit. */
    public void cacheReplaced(String playerId) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> prev.withReplaced(true));
    }

    /** The cache's side of {@link #updateBotStrategy}, after the commit. */
    public void cacheBotStrategy(String playerId, String botStrategy) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> prev.withBotStrategy(botStrategy));
    }

    public Map<String, PlayerIdentity> identitiesById() {
        Map<String, PlayerIdentity> out = new java.util.HashMap<>();
        for (Row row : maps.byId.values()) {
            out.put(row.playerId(), row.identity());
        }
        return out;
    }
}
