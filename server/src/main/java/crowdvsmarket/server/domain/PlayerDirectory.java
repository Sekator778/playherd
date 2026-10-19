package crowdvsmarket.server.domain;

import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code players} dimension table (wallet address, nickname, bot flag/strategy) plus an
 * in-memory cache of it. Deliberately NOT part of the {@code game_events} replay log
 * ({@link EngineHub}'s javadoc) — a nickname or a bot's strategy assignment has no effect on
 * ledger/crowd-account math, so it is written straight to its row and just re-read (not replayed)
 * at startup.
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
        /** Р-53: reverse lookup for the deposit poller — chainsvc's {@code GET /v1/play/deposits}
         *  reports each coin's {@code playerIndex}, never a {@code playerId}. */
        final Map<Integer, String> idByPlayerIndex = new ConcurrentHashMap<>();
        /** Р-106: the live API keys, by the SHA-256 of the key. */
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
    /** Р-106: a bot of an outside developer, owned by a person and played through the API with a key. */
    public static final String KIND_API_BOT = "API_BOT";

    /**
     * {@code retiredAtMs}: when a bot retired (economy stage B4), {@code null} for everyone else; {@code replaced}: a new bot has taken
     * the place of a retired one. A retired bot is in no list.
     *
     * <p>{@code kind}, {@code ownerId}, {@code keyHash}, {@code keyHint} (Р-106): only an API bot has an owner and a key; {@code keyHash}
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

    /** Р-53: resolves a chainsvc-reported {@code playerIndex} (deposit poller) back to a
     *  {@code playerId} — {@link Optional#empty()} if unknown (defensive; should not happen for a
     *  coin chainsvc itself derived from an index this server handed out). */
    public Optional<String> playerIdForIndex(int playerIndex) {
        return Optional.ofNullable(maps.idByPlayerIndex.get(playerIndex));
    }

    /** Р-106: the API bot a live key belongs to, by the SHA-256 (hex) of the key; empty for an unknown or revoked key. */
    public Optional<Row> byKeyHash(String keyHash) {
        String id = maps.idByKeyHash.get(keyHash);
        return id == null ? Optional.empty() : byId(id);
    }

    /** Р-106: the API bots a person owns (revoked keys included: a bot is never deleted), oldest first. */
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

    /** Inserts a brand-new player row within the caller's transaction and returns it; the cache is
     *  not touched — pass the row to {@link #cache} after the commit. {@code playerIndex} must come
     *  from {@code ServerMetaRepository#reserveNextPlayerIndex()} — allocated once, before this
     *  call, never re-derived (Р-53). */
    public Row insert(Connection c, String playerId, String walletAddress, String nickname, boolean isBot,
                        String botStrategy, int playerIndex, long atMs) throws SQLException {
        db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, "
                        + "player_index, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
        return new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
    }

    /** Р-106: inserts an API bot (not a house bot: {@code is_bot} stays false) with its first key, within the caller's transaction;
     *  the cache gets the row through {@link #cache} after the commit. */
    public Row insertApiBot(Connection c, String playerId, String walletAddress, String nickname, int playerIndex,
                            String ownerId, String keyHash, String keyHint, long atMs) throws SQLException {
        db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, player_index, "
                        + "created_at_ms, kind, owner_player_id, api_key_hash, api_key_hint) "
                        + "VALUES (?, ?, ?, FALSE, NULL, ?, ?, ?, ?, ?, ?)",
                playerId, walletAddress, nickname, playerIndex, atMs, KIND_API_BOT, ownerId, keyHash, keyHint);
        return new Row(playerId, walletAddress, nickname, false, null, playerIndex, atMs, null, false, KIND_API_BOT, ownerId,
                keyHash, keyHint);
    }

    /** Writes a bot's key (a new one, or {@code null} for a revoked one) within the caller's transaction ({@link #cacheKey} after the commit). */
    public void updateKey(Connection c, String playerId, String keyHash, String keyHint) throws SQLException {
        db.update(c, "UPDATE players SET api_key_hash = ?, api_key_hint = ? WHERE player_id = ? AND kind = ?",
                keyHash, keyHint, playerId, KIND_API_BOT);
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

    /** Writes the retirement of a bot within the caller's transaction ({@link #cacheRetired} after the commit). */
    public void updateRetired(Connection c, String playerId, long atMs) throws SQLException {
        db.update(c, "UPDATE players SET retired_at_ms = ? WHERE player_id = ?", atMs, playerId);
    }

    /** The cache's side of {@link #updateRetired}, after the commit. */
    public void cacheRetired(String playerId, long atMs) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> prev.withRetiredAt(atMs));
    }

    /** Writes that a new bot has taken the place of the retired {@code playerId}, within the caller's transaction. */
    public void updateReplaced(Connection c, String playerId) throws SQLException {
        db.update(c, "UPDATE players SET replaced = TRUE WHERE player_id = ?", playerId);
    }

    /** The cache's side of {@link #updateReplaced}, after the commit. */
    public void cacheReplaced(String playerId) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> prev.withReplaced(true));
    }

    /** Writes the strategy (catalog id) of a bot within the caller's transaction ({@link #cacheBotStrategy} after the commit). */
    public void updateBotStrategy(Connection c, String playerId, String botStrategy) throws SQLException {
        db.update(c, "UPDATE players SET bot_strategy = ? WHERE player_id = ? AND is_bot", botStrategy, playerId);
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
