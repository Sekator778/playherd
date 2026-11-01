package crowdvsmarket.engine.player;

/**
 * Model-only representation of how a player is shown in the UI (D-15): nickname (chosen at first
 * login) + shortened wallet address. No DID (D-15 explicitly rules that out).
 */
public record PlayerIdentity(String playerId, String nickname, String walletAddress) {

    public PlayerIdentity {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("nickname must not be blank");
        }
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("walletAddress must not be blank");
        }
    }

    /**
     * {@code "nickname (first6…last4)"} — proposal for the shortened form (see
     * docs/research/round_engine_design.md); addresses of 14 characters or fewer are shown in full
     * since shortening would not save anything.
     */
    public String displayName() {
        return nickname + " (" + shorten(walletAddress) + ")";
    }

    static String shorten(String address) {
        if (address.length() <= 14) {
            return address;
        }
        return address.substring(0, 6) + "…" + address.substring(address.length() - 4);
    }
}
