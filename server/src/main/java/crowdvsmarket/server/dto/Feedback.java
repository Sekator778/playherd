package crowdvsmarket.server.dto;

/**
 * In-app feedback (Р-81): the {@code submitFeedback} RPC and the operator's listing. Additive, not in {@code contract.ts}
 * (the same situation as {@code ledgerCorrections}); docs/research/server_design.md §22 has the whole contract.
 */
public final class Feedback {
    private Feedback() {
    }

    /**
     * {@code args[0]} of {@code submitFeedback}. Only {@code message} counts for the answer: it must be 1..2000 characters
     * after the NUL characters are dropped and the ends are trimmed ({@code 400 BAD_REQUEST} otherwise). The rest is what
     * the browser knows and the server cannot — it is cleaned and never a reason to refuse the message:
     * {@code timeZone} (at most 64 characters), {@code language} (at most 35), {@code page} (starts with {@code "/"}, at
     * most 200), {@code client} ({@code "web"}, {@code "app"} or {@code "pwa"}); a value that does not fit, or has a
     * control character in it, is stored as {@code null}.
     */
    public record SubmitRequest(String message, String timeZone, String language, String page, String client) {
    }

    /** The answer of {@code submitFeedback}: the number the message was stored under. */
    public record ReceiptDto(long id) {
    }

    /**
     * One message as the operator reads it ({@code GET /api/admin/feedback}), newest first. {@code country} is the ISO 3166-1
     * alpha-2 code the server derived from the IP address (the address itself is not stored), {@code null} when unknown;
     * {@code playerId} is set when the sender was signed in, and then {@code nickname} is the player's nickname ({@code null}
     * while they have none) and {@code roundsPlayed} the number of settled rounds they had a bet in — {@code null} for a
     * sender who was not signed in, which is not the same as {@code 0}.
     */
    public record FeedbackItemDto(long id, long receivedAtMs, String message, String country, String timeZone,
                                  String language, String page, String client, String playerId, String nickname,
                                  Integer roundsPlayed) {
    }
}
