package crowdvsmarket.server.dto;

/** contract.ts §"Sign-in" (Р-4; docs/research/week1_wallet_auth.md §4). */
public final class Auth {
    private Auth() {
    }

    public record AuthChallengeRequest(String address) {
    }

    public record AuthChallengeDto(String nonce, String message, long expiresAtMs) {
    }

    public record AuthVerifyRequest(String nonce, String address, String publicKey, String signature) {
    }

    public record AuthSessionDto(Identity.PlayerSummaryDto player, boolean isNewPlayer) {
    }
}
