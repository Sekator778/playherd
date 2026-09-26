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
 */
@ApplicationScoped
public final class PlayerDirectory {

    @Inject
    Db db;

    private final Map<String, Row> byId = new ConcurrentHashMap<>();
    private final Map<String, String> idByWallet = new ConcurrentHashMap<>();
    /** Р-53: reverse lookup for the deposit poller — chainsvc's {@code GET /v1/play/deposits}
     *  reports each coin's {@code playerIndex}, never a {@code playerId}. */
    private final Map<Integer, String> idByPlayerIndex = new ConcurrentHashMap<>();

    public record Row(String playerId, String walletAddress, String nickname, boolean isBot, String botStrategy,
                        int playerIndex, long createdAtMs) {
        public PlayerIdentity identity() {
            return new PlayerIdentity(playerId, nickname == null ? shortWallet(walletAddress) : nickname,
                    walletAddress);
        }

        private static String shortWallet(String address) {
            return address.length() <= 14 ? address : address.substring(0, 6) + "…" + address.substring(address.length() - 4);
        }
    }

    /** Reloads the whole cache from the DB — called once at startup, after the DB connection (and
     *  Flyway migration) are ready, before the event replay that depends on some of these ids
     *  already resolving to a wallet address for display purposes. */
    public synchronized void reload() {
        byId.clear();
        idByWallet.clear();
        idByPlayerIndex.clear();
        List<Row> rows = db.read(c -> db.queryList(c,
                "SELECT player_id, wallet_address, nickname, is_bot, bot_strategy, player_index, created_at_ms "
                        + "FROM players",
                rs -> new Row(rs.getString("player_id"), rs.getString("wallet_address"), rs.getString("nickname"),
                        rs.getBoolean("is_bot"), rs.getString("bot_strategy"), rs.getInt("player_index"),
                        rs.getLong("created_at_ms"))));
        for (Row row : rows) {
            byId.put(row.playerId(), row);
            idByWallet.put(row.walletAddress(), row.playerId());
            idByPlayerIndex.put(row.playerIndex(), row.playerId());
        }
    }

    public Optional<Row> byId(String playerId) {
        return Optional.ofNullable(byId.get(playerId));
    }

    public Optional<String> playerIdForWallet(String walletAddress) {
        return Optional.ofNullable(idByWallet.get(walletAddress));
    }

    /** Р-53: resolves a chainsvc-reported {@code playerIndex} (deposit poller) back to a
     *  {@code playerId} — {@link Optional#empty()} if unknown (defensive; should not happen for a
     *  coin chainsvc itself derived from an index this server handed out). */
    public Optional<String> playerIdForIndex(int playerIndex) {
        return Optional.ofNullable(idByPlayerIndex.get(playerIndex));
    }

    public Collection<Row> all() {
        return List.copyOf(byId.values());
    }

    public List<Row> bots() {
        return byId.values().stream().filter(Row::isBot).toList();
    }

    /** Inserts a brand-new player row (within the caller's transaction) and updates the cache.
     *  {@code playerIndex} must come from {@code ServerMetaRepository#reserveNextPlayerIndex()} —
     *  allocated once, before this call, never re-derived (Р-53). */
    public void insert(Connection c, String playerId, String walletAddress, String nickname, boolean isBot,
                        String botStrategy, int playerIndex, long atMs) throws SQLException {
        db.update(c, "INSERT INTO players (player_id, wallet_address, nickname, is_bot, bot_strategy, "
                        + "player_index, created_at_ms) VALUES (?, ?, ?, ?, ?, ?, ?)",
                playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
        Row row = new Row(playerId, walletAddress, nickname, isBot, botStrategy, playerIndex, atMs);
        byId.put(playerId, row);
        idByWallet.put(walletAddress, playerId);
        idByPlayerIndex.put(playerIndex, playerId);
    }

    public void updateNickname(Connection c, String playerId, String nickname) throws SQLException {
        db.update(c, "UPDATE players SET nickname = ? WHERE player_id = ?", nickname, playerId);
        Row prev = byId.get(playerId);
        if (prev != null) {
            byId.put(playerId, new Row(prev.playerId(), prev.walletAddress(), nickname, prev.isBot(),
                    prev.botStrategy(), prev.playerIndex(), prev.createdAtMs()));
        }
    }

    public Map<String, PlayerIdentity> identitiesById() {
        Map<String, PlayerIdentity> out = new java.util.HashMap<>();
        for (Row row : byId.values()) {
            out.put(row.playerId(), row.identity());
        }
        return out;
    }
}
