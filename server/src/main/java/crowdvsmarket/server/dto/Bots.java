package crowdvsmarket.server.dto;

/** Р-106: the bots of outside developers, played through the API with a key. contract.ts §"API bots". */
public final class Bots {
    private Bots() {
    }

    /**
     * One API bot as its owner sees it. {@code keyHint} is the key's prefix and last four characters (never enough to use it), {@code null}
     * once the key is revoked; {@code keyActive} says whether a key opens the bot now.
     */
    public record ApiBotDto(String playerId, String nickname, String keyHint, boolean keyActive, long createdAtMs) {
    }

    /** What creating a bot and regenerating a key answer: the bot, and its new key in full - the only time the key is ever sent. */
    public record ApiBotKeyDto(ApiBotDto bot, String apiKey) {
    }
}
