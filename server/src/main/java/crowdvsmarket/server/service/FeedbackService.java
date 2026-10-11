package crowdvsmarket.server.service;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Feedback;
import crowdvsmarket.server.geoip.CountryLookup;
import crowdvsmarket.server.persistence.FeedbackRepository;
import crowdvsmarket.server.util.LogThrottle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Р-81: the feedback a visitor leaves in the app — one message, no name, no e-mail — stored with what the server could
 * learn about the sender on the way in: the country of the IP address (derived and then forgotten: the address is neither
 * stored nor logged nor returned), the browser's time zone and language the client sends, and the player's id when the
 * sender is signed in. Docs: docs/research/server_design.md §22.
 *
 * <p>Open to everyone, signed in or not. The per-IP budget is taken by {@code RpcResource} before the body is read (the
 * same place as every limited method); the daily cap on everyone together is checked here, right before the insert.
 */
@ApplicationScoped
public class FeedbackService {

    /** The longest message: {@code String.length()}, the unit of a JavaScript string's {@code length}. */
    public static final int MAX_MESSAGE_LENGTH = 2000;
    static final int MAX_TIME_ZONE_LENGTH = 64;
    static final int MAX_LANGUAGE_LENGTH = 35;
    static final int MAX_PAGE_LENGTH = 200;
    /** The clients that may name themselves: the website, the app of a store, the website installed to the home screen —
     *  the union {@code FeedbackClient} of contract.ts ({@code ContractUnionsTest} holds the two lists equal). */
    public static final Set<String> CLIENTS = Set.of("web", "app", "pwa");

    public static final int DEFAULT_LIST_LIMIT = 200;
    public static final int MAX_LIST_LIMIT = 1000;

    private static final long DAY_MS = 24L * 3_600_000L;

    @Inject
    GameConfig config;
    @Inject
    FeedbackRepository repository;
    @Inject
    CountryLookup countries;

    /** Package-private: a test swaps it for one that records. */
    Logger log = Logger.getLogger(FeedbackService.class);
    private final LogThrottle capLog = new LogThrottle(60_000L);

    /**
     * Stores the message.
     *
     * @param playerId  the signed-in player, {@code null} for a visitor who is not
     * @param clientIp  the sender's address as the rate limiter sees it (proxy-aware); used for the country and then dropped
     * @throws ApiException {@code 400 BAD_REQUEST} for a message that is empty, absent or longer than {@value
     *                      #MAX_MESSAGE_LENGTH} characters; {@code 429 RATE_LIMITED} when {@code herd.feedback.max-per-day}
     *                      messages came in during the last 24 hours
     */
    public Feedback.ReceiptDto submit(Feedback.SubmitRequest request, String playerId, String clientIp, long nowMs) {
        String message = cleanMessage(request.message());
        String timeZone = cleanToken(request.timeZone(), MAX_TIME_ZONE_LENGTH);
        String language = cleanToken(request.language(), MAX_LANGUAGE_LENGTH);
        String page = cleanPage(request.page());
        String client = cleanClient(request.client());

        if (repository.countSince(nowMs - DAY_MS) >= config.feedbackMaxPerDay()) {
            if (capLog.allow("cap", nowMs)) {
                log.warnf("feedback: %d messages came in during the last 24 hours (herd.feedback.max-per-day) — "
                        + "refusing new ones until older ones drop out of the day", config.feedbackMaxPerDay());
            }
            throw ApiException.rateLimited();
        }

        String country = countryOf(clientIp);
        long id = repository.insert(nowMs, new FeedbackRepository.NewFeedback(message, playerId, country, timeZone,
                language, page, client));
        // id, country, length: neither the address nor the text
        log.infof("feedback %d accepted: country=%s, %d characters", id, country == null ? "unknown" : country,
                message.length());
        return new Feedback.ReceiptDto(id);
    }

    /** The lookup is best-effort: whatever goes wrong in it, the message is still taken, without a country. */
    private String countryOf(String clientIp) {
        try {
            return countries.countryOf(clientIp);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The operator's listing: messages received at or after {@code sinceMs} ({@code null} or negative: all), newest first;
     * {@code limit} defaults to {@value #DEFAULT_LIST_LIMIT} ({@code null} or not positive) and is at most {@value
     * #MAX_LIST_LIMIT}.
     */
    public List<Feedback.FeedbackItemDto> list(Long sinceMs, Integer limit) {
        long since = sinceMs == null || sinceMs < 0 ? 0 : sinceMs;
        int count = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        return repository.list(since, count);
    }

    // --- cleaning what the client sends ---

    /** U+0000 dropped (a Postgres {@code TEXT} cannot hold it), the ends trimmed ({@link #trim}), then 1..{@value
     *  #MAX_MESSAGE_LENGTH}. */
    static String cleanMessage(String raw) {
        if (raw == null) {
            throw ApiException.badRequest("message is required");
        }
        String text = trim(raw.replace("\u0000", ""));
        if (text.isEmpty()) {
            throw ApiException.badRequest("message is empty");
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw ApiException.badRequest("message is too long: " + text.length() + " characters, at most "
                    + MAX_MESSAGE_LENGTH);
        }
        return text;
    }

    /** The trimmed value, or {@code null} when it is absent, empty, longer than {@code maxLength} or has a control character. */
    static String cleanToken(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String text = trim(raw);
        if (text.isEmpty() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl)) {
            return null;
        }
        return text;
    }

    /**
     * The ends trimmed the way a browser trims them. The web form counts {@code text.trim().length} (JavaScript), and a limit
     * of "2000 characters" must mean the same text to both sides: {@code String.strip()} leaves a no-break space (U+00A0 and the
     * other no-break spaces) and a byte order mark (U+FEFF) at the ends, which {@code trim()} of JavaScript takes off — a
     * message of nothing else would be "not empty" here and empty there. So: every Java whitespace, every space separator
     * ({@code Character.isSpaceChar}: Zs, Zl, Zp — U+00A0 among them), and U+FEFF.
     */
    static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\uFEFF';
    }

    /** A route of the app: {@link #cleanToken} with at most {@value #MAX_PAGE_LENGTH} characters, starting with "/". */
    static String cleanPage(String raw) {
        String page = cleanToken(raw, MAX_PAGE_LENGTH);
        return page != null && page.startsWith("/") ? page : null;
    }

    /** {@code web}, {@code app} or {@code pwa}; anything else is {@code null}. */
    static String cleanClient(String raw) {
        String client = cleanToken(raw, 8);
        return client != null && CLIENTS.contains(client) ? client : null;
    }
}
