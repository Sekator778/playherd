package crowdvsmarket.server.auth;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Auth;
import crowdvsmarket.server.persistence.AuthChallengeRepository;
import crowdvsmarket.server.service.GameService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Р-4/Р-45 sign-in: challenge/verify flow per docs/research/week1_wallet_auth.md §4, verified
 * against chainsvc (FIXED INTERFACE 3, {@code /v1/auth/verify}) — this server never implements the
 * BLS/Chia crypto itself. Works identically for a real Sage signature and for the local stand's
 * in-browser test wallet (Р-45: "та же схема и проверка подписи, что для Sage").
 */
@ApplicationScoped
public class AuthService {

    private static final String MESSAGE_PREFIX = "Herd sign-in\n"; // non-hex prefix — see the hex
    // footgun documented in docs/research/week1_wallet_auth.md §2: Sage decodes an all-hex-digit
    // message as raw hex bytes instead of UTF-8, so the very first bytes must never be hex-only.

    @Inject
    GameConfig config;
    @Inject
    AuthChallengeRepository challenges;
    @Inject
    ChainSvcClient chainSvc;
    @Inject
    GameService gameService;
    @Inject
    SessionService sessions;

    private final SecureRandom random = new SecureRandom();

    public Auth.AuthChallengeDto challenge(Auth.AuthChallengeRequest req) {
        byte[] nonceBytes = new byte[24];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        long now = System.currentTimeMillis();
        long expiresAt = now + config.challengeTtlMs();
        String address = req == null ? null : req.address();
        String message = MESSAGE_PREFIX
                + "Domain: herd.local\n"
                + "Network: testnet11\n"
                + "Address: " + (address == null ? "?" : address) + "\n"
                + "Nonce: " + nonce + "\n"
                + "Issued: " + Instant.ofEpochMilli(now) + "\n"
                + "Expires: " + Instant.ofEpochMilli(expiresAt);
        challenges.insert(nonce, message, address, expiresAt, now);
        return new Auth.AuthChallengeDto(nonce, message, expiresAt);
    }

    public record VerifyResult(Auth.AuthSessionDto session, String sessionId) {
    }

    public VerifyResult verify(Auth.AuthVerifyRequest req) {
        AuthChallengeRepository.Row row = challenges.find(req.nonce())
                .orElseThrow(ApiException::challengeExpiredOrUnknown);
        long now = System.currentTimeMillis();
        if (row.used() || row.expiresAtMs() < now) {
            throw ApiException.challengeExpiredOrUnknown();
        }
        if (row.address() != null && !row.address().equals(req.address())) {
            throw ApiException.challengeExpiredOrUnknown();
        }
        if (!challenges.markUsedIfUnused(req.nonce())) {
            throw ApiException.challengeExpiredOrUnknown(); // raced with another verify — one-time use
        }

        ChainSvcClient.AuthVerifyResult result;
        try {
            result = chainSvc.authVerify(req.address(), req.publicKey(), row.message(), req.signature());
        } catch (Exception e) {
            throw ApiException.unauthorized("chainsvc auth verification unavailable: " + e.getMessage());
        }
        if (!result.valid()) {
            throw ApiException.signatureInvalid(result.reason());
        }

        GameService.PlayerTouchResult touch = gameService.registerOrTouchPlayer(req.address(), now);
        String sessionId = sessions.create(touch.player().playerId(), now);
        return new VerifyResult(new Auth.AuthSessionDto(touch.player(), touch.isNewPlayer()), sessionId);
    }
}
