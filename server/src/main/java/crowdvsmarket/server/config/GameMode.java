package crowdvsmarket.server.config;

/** {@code herd.mode} (D-46): {@code replay} is the default for the local Docker stand. */
public enum GameMode {
    LIVE, REPLAY;

    public static GameMode parse(String raw) {
        return GameMode.valueOf(raw.trim().toUpperCase());
    }
}
