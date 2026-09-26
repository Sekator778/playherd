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

    public static ApiException rateLimited() {
        return new ApiException(Enums.ApiErrorCode.RATE_LIMITED, 429,
                "too many requests — please slow down and try again shortly");
    }

    public Enums.ApiErrorCode code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
