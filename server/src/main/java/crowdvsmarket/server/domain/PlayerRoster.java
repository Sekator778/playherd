package crowdvsmarket.server.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who the players are, as far as replaying the journal needs to know (architecture plan F4): the display name and whether the
 * web app badges the player as a bot, for the bet feed. It lives in the {@link EngineHub} and is kept by {@code EventApplier} from
 * {@code PlayerRegistered}, {@code NicknameChanged} and {@code BotStrategyAssigned}, so applying an event reads nothing outside
 * the hub. A journal of schema version 1 has no row in its {@code PlayerRegistered}: the rebuild seeds the players it knows from the
 * {@code players} table ({@link #seed}) before replaying, and an event then overrides the seed.
 */
public final class PlayerRoster {

    /** One player. {@code nickname} is {@code null} for a person who has none (the app shows a short form of the wallet address). */
    public record Entry(String playerId, String walletAddress, String nickname, String kind, String botStrategy,
                        String ownerPlayerId) {

        /** The name the app shows for the player. */
        public String displayName() {
            return nickname == null ? shortWallet(walletAddress) : nickname;
        }

        /** Whether the app badges the player as a bot: the house's bots and the API bots alike. */
        public boolean displaysAsBot() {
            return !PlayerDirectory.KIND_HUMAN.equals(kind);
        }

        Entry withNickname(String value) {
            return new Entry(playerId, walletAddress, value, kind, botStrategy, ownerPlayerId);
        }

        Entry withBotStrategy(String value) {
            return new Entry(playerId, walletAddress, nickname, kind, value, ownerPlayerId);
        }
    }

    static String shortWallet(String address) {
        return address.length() <= 14 ? address : address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }

    private final Map<String, Entry> byId = new ConcurrentHashMap<>();

    public Optional<Entry> find(String playerId) {
        return Optional.ofNullable(byId.get(playerId));
    }

    public Collection<Entry> all() {
        return byId.values();
    }

    public void register(Entry entry) {
        byId.put(entry.playerId(), entry);
    }

    public void setNickname(String playerId, String nickname) {
        byId.computeIfPresent(playerId, (id, prev) -> prev.withNickname(nickname));
    }

    public void setBotStrategy(String playerId, String botStrategy) {
        byId.computeIfPresent(playerId, (id, prev) -> prev.withBotStrategy(botStrategy));
    }

    /** Puts the players of a journal of schema version 1 (the {@code players} table) into the roster, before the replay. */
    public void seed(Collection<PlayerDirectory.Row> rows) {
        for (PlayerDirectory.Row row : rows) {
            register(new Entry(row.playerId(), row.walletAddress(), row.nickname(), row.kind(), row.botStrategy(), row.ownerId()));
        }
    }
}
