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

        void put(Row row) {
            byId.put(row.playerId(), row);
            idByWallet.put(row.walletAddress(), row.playerId());
            idByPlayerIndex.put(row.playerIndex(), row.playerId());
        }
    }

    private volatile Maps maps = new Maps();

    /**
     * {@code retiredAtMs}: when a bot retired (economy stage B4), {@code null} for everyone else; {@code replaced}: a new bot has taken
     * the place of a retired one. A retired bot is in no list.
     */
    public record Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                        int playerIndex, long createdAtMs, Long retiredAtMs, boolean replaced) {

        public Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                   int playerIndex, long createdAtMs) {
            this(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, createdAtMs, null, false);
        }

        public boolean isRetired() {
            return retiredAtMs != null;
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
                        + "retired_at_ms, replaced FROM players",
                rs -> new Row(rs.getString("player_id"), rs.getString("wallet_address"), rs.getString("nickname"),
                        rs.getBoolean("is_bot"), rs.getString("bot_strategy"), rs.getInt("player_index"),
                        rs.getLong("created_at_ms"), (Long) rs.getObject("retired_at_ms"), rs.getBoolean("replaced"))));
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
        current.byId.computeIfPresent(playerId, (id, prev) -> new Row(prev.playerId(), prev.walletAddress(), nickname,
                prev.isBot(), prev.botStrategy(), prev.playerIndex(), prev.createdAtMs(), prev.retiredAtMs(),
                prev.replaced()));
    }

    /** Writes the retirement of a bot within the caller's transaction ({@link #cacheRetired} after the commit). */
    public void updateRetired(Connection c, String playerId, long atMs) throws SQLException {
        db.update(c, "UPDATE players SET retired_at_ms = ? WHERE player_id = ?", atMs, playerId);
    }

    /** The cache's side of {@link #updateRetired}, after the commit. */
    public void cacheRetired(String playerId, long atMs) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> new Row(prev.playerId(), prev.walletAddress(), prev.nickname(),
                prev.isBot(), prev.botStrategy(), prev.playerIndex(), prev.createdAtMs(), atMs, prev.replaced()));
    }

    /** Writes that a new bot has taken the place of the retired {@code playerId}, within the caller's transaction. */
    public void updateReplaced(Connection c, String playerId) throws SQLException {
        db.update(c, "UPDATE players SET replaced = TRUE WHERE player_id = ?", playerId);
    }

    /** The cache's side of {@link #updateReplaced}, after the commit. */
    public void cacheReplaced(String playerId) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> new Row(prev.playerId(), prev.walletAddress(), prev.nickname(),
                prev.isBot(), prev.botStrategy(), prev.playerIndex(), prev.createdAtMs(), prev.retiredAtMs(), true));
    }

    /** Writes the strategy (catalog id) of a bot within the caller's transaction ({@link #cacheBotStrategy} after the commit). */
    public void updateBotStrategy(Connection c, String playerId, String botStrategy) throws SQLException {
        db.update(c, "UPDATE players SET bot_strategy = ? WHERE player_id = ? AND is_bot", botStrategy, playerId);
    }

    /** The cache's side of {@link #updateBotStrategy}, after the commit. */
    public void cacheBotStrategy(String playerId, String botStrategy) {
        maps.byId.computeIfPresent(playerId, (id, prev) -> new Row(prev.playerId(), prev.walletAddress(), prev.nickname(),
                prev.isBot(), botStrategy, prev.playerIndex(), prev.createdAtMs(), prev.retiredAtMs(), prev.replaced()));
    }

    public Map<String, PlayerIdentity> identitiesById() {
        Map<String, PlayerIdentity> out = new java.util.HashMap<>();
        for (Row row : maps.byId.values()) {
            out.put(row.playerId(), row.identity());
        }
        return out;
    }
}
