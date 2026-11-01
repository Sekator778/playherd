package crowdvsmarket.server.dto;

/**
 * Thrown by service code for any {@link Enums.ApiErrorCode} failure; mapped by
 * {@code crowdvsmarket.server.api.ApiExceptionMapper} to FIXED INTERFACE (1)'s error envelope
 * ({@code {"error":{"code","message"}}}) with the given HTTP status.
 */
public final class ApiException extends RuntimeException {

    private final Enums.ApiErrorCode code;
    private final int httpStatus;

    public ApiException(Enums.ApiErrorCode code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static ApiException notSignedIn() {
        return new ApiException(Enums.ApiErrorCode.NOT_SIGNED_IN, 401, "not signed in");
    }

    public static ApiException roundNotOpenForBetting(String roundId) {
        return new ApiException(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING, 409,
                "round " + roundId + " is not open for betting");
    }

    public static ApiException stakeBelowMinimum(String message) {
        return new ApiException(Enums.ApiErrorCode.STAKE_BELOW_MINIMUM, 422, message);
    }

    public static ApiException insufficientBalance(String message) {
        return new ApiException(Enums.ApiErrorCode.INSUFFICIENT_BALANCE, 422, message);
    }

    public static ApiException invalidLeverage(String message) {
        return new ApiException(Enums.ApiErrorCode.INVALID_LEVERAGE, 422, message);
    }

    public static ApiException withdrawalExceedsBalance(String message) {
        return new ApiException(Enums.ApiErrorCode.WITHDRAWAL_EXCEEDS_BALANCE, 422, message);
    }

    public static ApiException challengeExpiredOrUnknown() {
        return new ApiException(Enums.ApiErrorCode.CHALLENGE_EXPIRED_OR_UNKNOWN, 401,
                "challenge expired, unknown, or already used");
    }

    public static ApiException signatureInvalid(String reason) {
        return new ApiException(Enums.ApiErrorCode.SIGNATURE_INVALID, 401,
                "signature invalid" + (reason == null ? "" : ": " + reason));
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(Enums.ApiErrorCode.UNAUTHORIZED, 401, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(Enums.ApiErrorCode.NOT_FOUND, 404, message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(Enums.ApiErrorCode.BAD_REQUEST, 400, message);
    }

    public static ApiException invalidNickname(String message) {
        return new ApiException(Enums.ApiErrorCode.INVALID_NICKNAME, 400, message);
    }

    /** Wave-2 security fix: {@code requestWithdrawal}/{@code requestDeposit} reject a zero/
     *  negative amount with this, distinct from {@code badRequest} (which covers "not parseable as
     *  a number at all"). */
    public static ApiException invalidAmount(String message) {
        return new ApiException(Enums.ApiErrorCode.INVALID_AMOUNT, 400, message);
    }

    /** D-53 update: {@code requestDeposit} (the pre-D-53 self-report RPC) is now PERMANENTLY
     *  retired, unconditionally — not merely gated by {@code herd.deposit.enabled} the way it was
     *  under the original task item 1 fix this method dates from. Self-reporting an amount and
     *  auto-crediting it was exactly the fraud vector that fix closed (live-reproduced: 5,000,000
     *  fake PLAY minted in ten seconds); real on-chain deposits (D-53) are detected and credited
     *  automatically by the deposit poller once a player sends PLAY to {@code myDepositAddress()}
     *  — there is no legitimate reason for this RPC to ever credit anything again, flag or no
     *  flag. Same {@link Enums.ApiErrorCode#DEPOSIT_VERIFICATION_UNAVAILABLE}/503 as before (no
     *  client-visible code change), reworded. Also reused, unmodified, for {@code
     *  myDepositAddress} when {@code herd.deposit.enabled} is off — same "the real feature is not
     *  available yet" meaning as here. */
    public static ApiException depositVerificationUnavailable() {
        return new ApiException(Enums.ApiErrorCode.DEPOSIT_VERIFICATION_UNAVAILABLE, 503,
                "self-reported deposits are no longer accepted — call myDepositAddress() and send "
                        + "PLAY there; it is credited automatically once confirmed on chain");
    }

    /** Same task item, same reasoning, for {@code requestWithdrawal} — unlike {@link
     *  #depositVerificationUnavailable()}, still gated by {@code herd.withdrawal.enabled}
     *  (D-53: once enabled, {@code requestWithdrawal} performs the real on-chain flow). */
    public static ApiException withdrawalUnavailable() {
        return new ApiException(Enums.ApiErrorCode.WITHDRAWAL_UNAVAILABLE, 503,
                "withdrawals are not available yet — this server is not configured to send PLAY "
                        + "on-chain as a real CAT; nothing was debited");
    }

    /** D-53: {@code requestWithdrawal} rejects an amount below the minimum (proposal,
     *  {@code GameService.MIN_WITHDRAWAL}), distinct from {@link #invalidAmount(String)} (which
     *  covers "not positive at all") so a client can tell the two apart. */
    public static ApiException withdrawalBelowMinimum(String message) {
        return new ApiException(Enums.ApiErrorCode.WITHDRAWAL_BELOW_MINIMUM, 422, message);
    }

    /** D-53: {@code myDepositAddress} could not reach chainsvc to derive a brand-new player's
     *  deposit address (never thrown once a player's address is already cached). */
    public static ApiException chainServiceUnavailable() {
        return new ApiException(Enums.ApiErrorCode.CHAIN_SERVICE_UNAVAILABLE, 503,
                "could not reach the chain service to derive a deposit address — please try again shortly");
    }

    /** Economy stage B (plan B3): too many new players from one IP address in 24 hours. */
    public static ApiException registrationLimit(String message) {
        return new ApiException(Enums.ApiErrorCode.REGISTRATION_LIMIT, 429, message);
    }

    /** Economy stage B (plan B5): the bet is over the treasury exposure limit; {@code message} names the room left. */
    public static ApiException exposureLimitExceeded(String message) {
        return new ApiException(Enums.ApiErrorCode.EXPOSURE_LIMIT_EXCEEDED, 422, message);
    }

    public static ApiException rateLimited() {
        return new ApiException(Enums.ApiErrorCode.RATE_LIMITED, 429,
                "too many requests — please slow down and try again shortly");
    }

    /** docs/research/markets_architecture.md §8: a market id (or a round id naming one) this server
     *  has never listed. 404. */
    public static ApiException marketNotFound(String marketId) {
        return new ApiException(Enums.ApiErrorCode.MARKET_NOT_FOUND, 404, "unknown market " + marketId);
    }

    /** The market exists but is {@code CLOSED}: it opens no new rounds and takes no bets (§3.3,
     *  T-10). 409. */
    public static ApiException marketClosed(String marketId) {
        return new ApiException(Enums.ApiErrorCode.MARKET_CLOSED, 409,
                "market " + marketId + " is closed — it takes no new bets");
    }

    /** No price for the market right now (nothing ticked yet, or the source is down): the caller
     *  may retry. 503. */
    public static ApiException priceUnavailable(String message) {
        return new ApiException(Enums.ApiErrorCode.PRICE_UNAVAILABLE, 503, message);
    }

    /** The game engine's in-memory state cannot be trusted right now — a recovery from a failed
     *  settlement is still rebuilding it from the event log — so nothing that reads or changes it is
     *  answered until that has succeeded ({@code EngineRebuildService}: fail-stop). Nothing was
     *  changed by the call that gets this; it may simply be repeated. 503. */
    public static ApiException engineNotReady() {
        return new ApiException(Enums.ApiErrorCode.ENGINE_NOT_READY, 503,
                "the game engine is recovering — nothing was changed, please try again in a moment");
    }

    /** D-106: the API key sent as {@code Authorization: Bearer} is unknown, malformed or revoked. */
    public static ApiException apiKeyInvalid() {
        return new ApiException(Enums.ApiErrorCode.API_KEY_INVALID, 401, "the API key is not valid (unknown, malformed or revoked)");
    }

    /** D-106: a call an API key may not make; {@code method} is the RPC name the client asked for. */
    public static ApiException apiCallNotAllowed(String method) {
        return new ApiException(Enums.ApiErrorCode.API_CALL_NOT_ALLOWED, 403,
                "the call " + method + " is not available with an API key - it is for a signed-in person");
    }

    /** D-106: the owner has the most API bots already; {@code message} names the number. */
    public static ApiException botLimitReached(String message) {
        return new ApiException(Enums.ApiErrorCode.BOT_LIMIT_REACHED, 409, message);
    }

    /** D-106: an API bot asks (or is asked) to withdraw PLAY to a wallet. */
    public static ApiException botWithdrawalNotAllowed() {
        return new ApiException(Enums.ApiErrorCode.BOT_WITHDRAWAL_NOT_ALLOWED, 403,
                "an API bot cannot withdraw PLAY to a wallet - its balance stays in the game");
    }

    public Enums.ApiErrorCode code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
