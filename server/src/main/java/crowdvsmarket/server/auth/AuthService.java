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
import org.jboss.logging.Logger;

/**
 * Р-4/Р-45 sign-in: challenge/verify flow per docs/research/week1_wallet_auth.md §4, verified
 * against chainsvc (FIXED INTERFACE 3, {@code /v1/auth/verify}) — this server never implements the
 * BLS/Chia crypto itself. Works identically for a real Sage signature and for the local stand's
 * in-browser test wallet (Р-45: "та же схема и проверка подписи, что для Sage").
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

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
                + "Domain: " + config.publicDomain().strip() + "\n"
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

    /** Verifies the signed challenge and signs the player in. A sign-in that meets an engine that is not ready
     *  (a failed recovery, {@code EngineRebuildService}) is refused with 503 {@code ENGINE_NOT_READY} BEFORE the
     *  one-time challenge is consumed: the 503 says "nothing was changed, please try again", and the same signed
     *  challenge must then still work (it used to be burned first — the retry got 401 {@code
     *  CHALLENGE_EXPIRED_OR_UNKNOWN} and the player a second Sage prompt, review of the core, finding 6). */
    public VerifyResult verify(Auth.AuthVerifyRequest req) {
        return verify(req, null);
    }

    /** {@link #verify(Auth.AuthVerifyRequest)} from a known client address: a NEW player is limited per address (economy stage B, plan B3). */
    public VerifyResult verify(Auth.AuthVerifyRequest req, String clientIp) {
        gameService.requireEngineReady();
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
            // Task item 3 (security minor): chainsvc's raw exception text (connection refused,
            // timeout, an internal stack trace fragment, …) used to be echoed straight into the
            // client-facing error.message — never surfaced to a client anywhere else in this
            // codebase (see e.g. RpcResource#argsArray/#obj's own comments on the same class of
            // leak). Logged here instead; the client gets a generic, honest "unavailable" message.
            LOG.warnf(e, "chainsvc auth verification unavailable for address %s", req.address());
            throw ApiException.unauthorized("sign-in temporarily unavailable — please try again shortly");
        }
        if (!result.valid()) {
            throw ApiException.signatureInvalid(result.reason());
        }

        GameService.PlayerTouchResult touch = gameService.registerOrTouchPlayer(req.address(), now, clientIp);
        String sessionId = sessions.create(touch.player().playerId(), now);
        return new VerifyResult(new Auth.AuthSessionDto(touch.player(), touch.isNewPlayer(), touch.starterPackGranted()), sessionId);
    }
}
