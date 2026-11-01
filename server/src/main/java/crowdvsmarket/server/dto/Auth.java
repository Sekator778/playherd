package crowdvsmarket.server.dto;

/** contract.ts §"Sign-in" (D-4; docs/research/week1_wallet_auth.md §4). */
public final class Auth {
    private Auth() {
    }

    public record AuthChallengeRequest(String address) {
    }

    public record AuthChallengeDto(String nonce, String message, long expiresAtMs) {
    }

    /** {@code referralCode} / {@code source}: what the client kept from the landing URL ({@code ?ref=<code>&src=<channel>}); both
     *  optional, read only when this sign-in registers a NEW player (contract.ts {@code AuthVerifyRequest.referralCode}). */
    public record AuthVerifyRequest(String nonce, String address, String publicKey, String signature, String referralCode,
                                      String source) {
        public AuthVerifyRequest(String nonce, String address, String publicKey, String signature) {
            this(nonce, address, publicKey, signature, null, null);
        }
    }

    /** contract.ts {@code InviteDto}: the player's own invite code and how many players registered through it (bots excluded). */
    public record InviteDto(String code, int referredCount) {
    }

    /** {@code starterPackGranted}: this sign-in credited a starter pack — a brand-new player's, or a returning player's first
     *  sign-in of a new season (contract.ts {@code AuthSessionDto.starterPackGranted}, optional there: an older server does not
     *  send it; this one always does). The web app tells the player about a pack on it. */
    public record AuthSessionDto(Identity.PlayerSummaryDto player, boolean isNewPlayer, boolean starterPackGranted) {
    }
}
