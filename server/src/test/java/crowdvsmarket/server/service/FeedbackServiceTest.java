package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Feedback;
import crowdvsmarket.server.geoip.CountryLookup;
import crowdvsmarket.server.persistence.FeedbackRepository;
import crowdvsmarket.server.prices.RecordingLogger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link FeedbackService} without the application: what is cleaned, what is refused, what is stored with the message, the
 * daily cap, the line in the log. The database is a mock; the wire (cookie, headers, 429 per address) is
 * {@code FeedbackRpcTest}.
 */
class FeedbackServiceTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 3_600_000L;

    private FeedbackService service;
    private FeedbackRepository repository;
    private GameConfig config;
    private RecordingLogger log;
    private String lookedUp;

    @BeforeEach
    void wire() {
        repository = mock(FeedbackRepository.class);
        config = mock(GameConfig.class);
        when(config.feedbackMaxPerDay()).thenReturn(1000);
        when(repository.insert(anyLong(), any())).thenReturn(41L);
        log = new RecordingLogger();
        service = new FeedbackService();
        service.config = config;
        service.repository = repository;
        service.countries = ip -> {
            lookedUp = ip;
            return "DE";
        };
        service.log = log;
    }

    private static Feedback.SubmitRequest message(String text) {
        return new Feedback.SubmitRequest(text, null, null, null, null);
    }

    private FeedbackRepository.NewFeedback stored() {
        ArgumentCaptor<FeedbackRepository.NewFeedback> captor = ArgumentCaptor.forClass(FeedbackRepository.NewFeedback.class);
        verify(repository).insert(eq(NOW), captor.capture());
        return captor.getValue();
    }

    private static void assertBadRequest(Runnable call, String messagePart) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.BAD_REQUEST);
            assertThat(e.httpStatus()).isEqualTo(400);
            assertThat(e.getMessage()).contains(messagePart);
        });
    }

    // --- what is stored ---

    @Test
    void aMessageIsStoredWithWhatTheClientSentAndTheCountryOfItsAddress() {
        Feedback.ReceiptDto receipt = service.submit(new Feedback.SubmitRequest("  Loved the rounds, hated the sound.  ",
                "Europe/Berlin", "de-DE", "/room/BTC", "web"), "p-7", "203.0.113.9", NOW);

        assertThat(receipt.id()).isEqualTo(41L);
        assertThat(stored()).isEqualTo(new FeedbackRepository.NewFeedback("Loved the rounds, hated the sound.", "p-7",
                "DE", "Europe/Berlin", "de-DE", "/room/BTC", "web"));
        assertThat(lookedUp).isEqualTo("203.0.113.9");
    }

    @Test
    void aVisitorWhoIsNotSignedInHasNoPlayerAndAnUnknownCountryIsNull() {
        service.countries = ip -> null;

        service.submit(message("hi"), null, "10.0.0.1", NOW);

        FeedbackRepository.NewFeedback row = stored();
        assertThat(row.playerId()).isNull();
        assertThat(row.country()).isNull();
        assertThat(row.timeZone()).isNull();
        assertThat(row.language()).isNull();
        assertThat(row.page()).isNull();
        assertThat(row.client()).isNull();
    }

    @Test
    void aLookupThatBlowsUpDoesNotStopTheMessage() {
        service.countries = ip -> {
            throw new IllegalStateException("the database is on fire");
        };

        Feedback.ReceiptDto receipt = service.submit(message("still here"), null, "203.0.113.9", NOW);

        assertThat(receipt.id()).isEqualTo(41L);
        assertThat(stored().country()).isNull();
    }

    // --- the message ---

    @Test
    void aMessageThatIsAbsentBlankOrOnlyNulCharactersIsRefused() {
        assertBadRequest(() -> service.submit(message(null), null, "1.2.3.4", NOW), "required");
        assertBadRequest(() -> service.submit(message(""), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message("   "), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message(" \n\t\r\n "), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message("\u0000"), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message(" \u0000 \u0000\u0000 "), null, "1.2.3.4", NOW), "empty");
        verify(repository, never()).insert(anyLong(), any());
        assertThat(lookedUp).as("a refused message never gets to the country").isNull();
    }

    @Test
    void theEndsAreTrimmedTheWayABrowserTrimsThem() {
        // no-break space, byte order mark, ideographic space, line and paragraph separators: JavaScript's trim() takes all
        // of them off, String.strip() only some
        String padding = "\u00A0\uFEFF\u3000\u2028\u2029 \t\r\n\u202F\u2007";

        service.submit(message(padding + "words" + padding), null, "1.2.3.4", NOW);

        assertThat(stored().message()).isEqualTo("words");
        assertBadRequest(() -> service.submit(message(padding), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message("\uFEFF"), null, "1.2.3.4", NOW), "empty");
        assertBadRequest(() -> service.submit(message("\u00A0\u00A0"), null, "1.2.3.4", NOW), "empty");
    }

    @Test
    void whatIsInsideAMessageIsNotTrimmed() {
        service.submit(message("a\u00A0b \u3000 c\uFEFFd"), null, "1.2.3.4", NOW);

        assertThat(stored().message()).isEqualTo("a\u00A0b \u3000 c\uFEFFd");
    }

    @Test
    void theLimitIsCountedOnTheTrimmedTextEvenWhenTheSpacesAreNoBreakOnes() {
        String text = "\u00A0\u00A0\u00A0" + "a".repeat(2000) + "\uFEFF\uFEFF";

        service.submit(message(text), null, "1.2.3.4", NOW);

        assertThat(stored().message()).hasSize(2000);
    }

    @Test
    void nulCharactersAreDroppedFromTheMiddleOfAMessage() {
        service.submit(message("he\u0000llo \u0000wor\u0000ld"), null, "1.2.3.4", NOW);

        assertThat(stored().message()).isEqualTo("hello world");
    }

    @Test
    void aMessageOfExactlyTheLimitIsTakenAndOneMoreIsRefused() {
        String longest = "a".repeat(FeedbackService.MAX_MESSAGE_LENGTH);

        service.submit(message(longest), null, "1.2.3.4", NOW);

        assertThat(stored().message()).hasSize(2000);
        assertBadRequest(() -> service.submit(message(longest + "b"), null, "1.2.3.4", NOW), "too long");
        assertBadRequest(() -> service.submit(message("x".repeat(100_000)), null, "1.2.3.4", NOW), "at most 2000");
    }

    @Test
    void theLimitIsCountedAfterTheNulCharactersAndTheEndsAreGone() {
        String text = "  \u0000" + "a".repeat(1990) + "\u0000\u0000" + "b".repeat(10) + "\n\n";

        service.submit(message(text), null, "1.2.3.4", NOW);

        assertThat(stored().message()).hasSize(2000).startsWith("aaaa").endsWith("bbbb");
    }

    @Test
    void theLimitIsInJavaCharsTheUnitOfAJavaScriptStringLength() {
        String emoji = "😀"; // U+1F600: two chars in Java, "length" 2 in JavaScript
        assertThat(emoji.length()).isEqualTo(2);

        service.submit(message(emoji.repeat(1000)), null, "1.2.3.4", NOW);

        assertThat(stored().message()).hasSize(2000);
        assertBadRequest(() -> service.submit(message(emoji.repeat(1001)), null, "1.2.3.4", NOW), "too long");
    }

    @Test
    void newlinesAndTabsInsideAMessageAreKept() {
        service.submit(message("first line\nsecond\tline\r\nthird"), null, "1.2.3.4", NOW);

        assertThat(stored().message()).isEqualTo("first line\nsecond\tline\r\nthird");
    }

    // --- what is optional ---

    @Test
    void theOptionalFieldsAreTrimmed() {
        service.submit(new Feedback.SubmitRequest("m", "  Asia/Tokyo ", " ja ", " /lobby ", " pwa "), null, "1.2.3.4", NOW);

        FeedbackRepository.NewFeedback row = stored();
        assertThat(row.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(row.language()).isEqualTo("ja");
        assertThat(row.page()).isEqualTo("/lobby");
        assertThat(row.client()).isEqualTo("pwa");
    }

    @Test
    void anOptionalFieldThatDoesNotFitIsNullAndNeverARefusal() {
        Feedback.ReceiptDto receipt = service.submit(new Feedback.SubmitRequest("m", "z".repeat(65), "l".repeat(36),
                "p".repeat(201), "desktop"), null, "1.2.3.4", NOW);

        assertThat(receipt.id()).isEqualTo(41L);
        FeedbackRepository.NewFeedback row = stored();
        assertThat(row.message()).isEqualTo("m");
        assertThat(row.timeZone()).isNull();
        assertThat(row.language()).isNull();
        assertThat(row.page()).isNull();
        assertThat(row.client()).isNull();
    }

    @Test
    void theLimitsOfTheOptionalFieldsAreInclusive() {
        service.submit(new Feedback.SubmitRequest("m", "z".repeat(64), "l".repeat(35), "/" + "p".repeat(199), "web"), null,
                "1.2.3.4", NOW);

        FeedbackRepository.NewFeedback row = stored();
        assertThat(row.timeZone()).hasSize(64);
        assertThat(row.language()).hasSize(35);
        assertThat(row.page()).hasSize(200);
        assertThat(row.client()).isEqualTo("web");
    }

    @Test
    void controlCharactersTurnAnOptionalFieldIntoNull() {
        assertThat(FeedbackService.cleanToken("Europe/\u0000Berlin", 64)).isNull();
        assertThat(FeedbackService.cleanToken("Europe\n/Berlin", 64)).isNull();
        assertThat(FeedbackService.cleanToken("Europe/Berlin\u001b[31m", 64)).isNull();
        assertThat(FeedbackService.cleanToken("a\u007fb", 64)).isNull();
        assertThat(FeedbackService.cleanToken("a\u0085b", 64)).isNull();
        assertThat(FeedbackService.cleanToken("a\tb", 64)).isNull();
        assertThat(FeedbackService.cleanToken("Europe/Berlin\n", 64)).as("only the ends are trimmed").isEqualTo("Europe/Berlin");
        assertThat(FeedbackService.cleanToken(null, 64)).isNull();
        assertThat(FeedbackService.cleanToken("", 64)).isNull();
        assertThat(FeedbackService.cleanToken("   ", 64)).isNull();
        assertThat(FeedbackService.cleanToken("Europe/Berlin", 64)).isEqualTo("Europe/Berlin");
    }

    @Test
    void aPageStartsWithASlash() {
        assertThat(FeedbackService.cleanPage("/room/BTC")).isEqualTo("/room/BTC");
        assertThat(FeedbackService.cleanPage("/")).isEqualTo("/");
        assertThat(FeedbackService.cleanPage("room/BTC")).isNull();
        assertThat(FeedbackService.cleanPage("https://evil.example/")).isNull();
        assertThat(FeedbackService.cleanPage("")).isNull();
        assertThat(FeedbackService.cleanPage(null)).isNull();
        assertThat(FeedbackService.cleanPage("/a\nb")).isNull();
    }

    @Test
    void theClientIsOneOfThreeWords() {
        for (String client : List.of("web", "app", "pwa")) {
            assertThat(FeedbackService.cleanClient(client)).isEqualTo(client);
        }
        assertThat(FeedbackService.cleanClient("WEB")).isNull();
        assertThat(FeedbackService.cleanClient("ios")).isNull();
        assertThat(FeedbackService.cleanClient("web app")).isNull();
        assertThat(FeedbackService.cleanClient("")).isNull();
        assertThat(FeedbackService.cleanClient(null)).isNull();
    }

    // --- the daily cap ---

    @Test
    void withTheCapOfTheDayReachedTheAnswerIs429AndNothingIsStored() {
        when(repository.countSince(NOW - DAY)).thenReturn(1000L);

        assertThatThrownBy(() -> service.submit(message("one too many"), null, "1.2.3.4", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.RATE_LIMITED);
                    assertThat(e.httpStatus()).isEqualTo(429);
                });
        verify(repository, never()).insert(anyLong(), any());
        assertThat(lookedUp).isNull();
    }

    @Test
    void oneBelowTheCapIsStillTakenAndTheCountLooksBackExactlyOneDay() {
        when(repository.countSince(NOW - DAY)).thenReturn(999L);

        service.submit(message("the thousandth"), null, "1.2.3.4", NOW);

        verify(repository).countSince(NOW - DAY);
        assertThat(stored().message()).isEqualTo("the thousandth");
    }

    @Test
    void theCapIsTheConfiguredOneAndZeroClosesTheDoor() {
        when(config.feedbackMaxPerDay()).thenReturn(0);
        when(repository.countSince(NOW - DAY)).thenReturn(0L);

        assertThatThrownBy(() -> service.submit(message("anyone?"), null, "1.2.3.4", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(429));
    }

    @Test
    void aRefusalForTheCapIsLoggedOncePerMinute() {
        when(repository.countSince(anyLong())).thenReturn(1000L);

        for (int i = 0; i < 3; i++) {
            long at = NOW + i * 1000L;
            assertThatThrownBy(() -> service.submit(message("x"), null, "1.2.3.4", at)).isInstanceOf(ApiException.class);
        }

        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("1000").contains("herd.feedback.max-per-day");
    }

    // --- the log ---

    @Test
    void theLogLineHasTheIdTheCountryAndTheLengthAndNeitherTheAddressNorTheText() {
        service.submit(message("The secret words of the sender"), "p-7", "203.0.113.9", NOW);

        assertThat(log.infos()).hasSize(1);
        String line = log.infos().get(0);
        assertThat(line).contains("41").contains("DE").contains("30 characters");
        assertThat(line).doesNotContain("203.0.113.9").doesNotContain("secret").doesNotContain("p-7");
        assertThat(log.warnings()).isEmpty();
    }

    @Test
    void anUnknownCountryIsSaidSo() {
        service.countries = ip -> null;

        service.submit(message("hi"), null, "203.0.113.9", NOW);

        assertThat(log.infos().get(0)).contains("country=unknown").doesNotContain("203.0.113.9");
    }

    @Test
    void aRefusedMessageLogsNothingAboutItsText() {
        assertBadRequest(() -> service.submit(message("   "), null, "203.0.113.9", NOW), "empty");

        assertThat(log.infos()).isEmpty();
    }

    // --- the listing ---

    @Test
    void theListingDefaultsAndClampsItsArguments() {
        when(repository.list(anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        service.list(null, null);
        verify(repository).list(0L, 200);

        service.list(-5L, 0);
        verify(repository, org.mockito.Mockito.times(2)).list(0L, 200);

        service.list(1234L, -3);
        verify(repository).list(1234L, 200);

        service.list(99L, 5000);
        verify(repository).list(99L, 1000);

        service.list(99L, 1000);
        verify(repository, org.mockito.Mockito.times(2)).list(99L, 1000);

        service.list(7L, 1);
        verify(repository).list(7L, 1);
    }
}
