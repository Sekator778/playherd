package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.RoundWindow.RoundRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Market-aware rounds (D-69): a BTC window keeps the legacy round id (published history, DB keys
 * and result hashes depend on it), every other market gets {@code "<market>-<code>-<startMs>"};
 * {@link RoundWindow#parseRoundId} is the exact inverse; {@link RoundClock} keeps the market.
 */
class RoundWindowMarketTest {

    private static final long APRIL_1 = Instant.parse("2026-04-01T00:00:00Z").toEpochMilli(); // 1_775_001_600_000
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    // --- round ids ---

    @Test
    void aBtcWindowKeepsTheLegacyRoundId() {
        assertThat(APRIL_1).isEqualTo(1_775_001_600_000L);

        assertThat(new RoundWindow(Interval.HOURLY, APRIL_1, APRIL_1 + HOUR).roundId()).isEqualTo("1h-1775001600000");
        assertThat(new RoundWindow(Interval.DAILY, APRIL_1, APRIL_1 + DAY).roundId()).isEqualTo("1d-1775001600000");
        assertThat(new RoundWindow(MarketSpec.BTC_ID, Interval.HOURLY, APRIL_1, APRIL_1 + HOUR).roundId()).isEqualTo("1h-1775001600000");
        assertThat(new RoundWindow("BTC", Interval.DAILY, 0L, DAY).roundId()).isEqualTo("1d-0");
    }

    @Test
    void anyOtherMarketPrefixesItsId() {
        assertThat(new RoundWindow("ETH", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR).roundId()).isEqualTo("ETH-1h-1775001600000");
        assertThat(new RoundWindow("ETH", Interval.DAILY, APRIL_1, APRIL_1 + DAY).roundId()).isEqualTo("ETH-1d-1775001600000");
        assertThat(new RoundWindow("SOL_2", Interval.HOURLY, 0L, HOUR).roundId()).isEqualTo("SOL_2-1h-0");
        // the id is case-sensitive: only exactly "BTC" is the legacy market
        assertThat(new RoundWindow("btc", Interval.HOURLY, 0L, HOUR).roundId()).isEqualTo("btc-1h-0");
    }

    @Test
    void theSameSlotInTwoMarketsHasTwoDifferentIds() {
        RoundWindow btc = new RoundWindow(Interval.HOURLY, APRIL_1, APRIL_1 + HOUR);
        RoundWindow eth = new RoundWindow("ETH", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR);
        RoundWindow sol = new RoundWindow("SOL", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR);

        assertThat(List.of(btc.roundId(), eth.roundId(), sol.roundId())).doesNotHaveDuplicates();
        assertThat(btc).isNotEqualTo(eth);
    }

    @Test
    void theThreeArgumentConstructorIsTheBtcWindow() {
        RoundWindow legacy = new RoundWindow(Interval.HOURLY, APRIL_1, APRIL_1 + HOUR);

        assertThat(legacy.market()).isEqualTo("BTC");
        assertThat(legacy).isEqualTo(new RoundWindow("BTC", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR));
        assertThat(legacy).hasSameHashCodeAs(new RoundWindow("BTC", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR));
    }

    // --- validation ---

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "BT-C", "ETH 1", "ETH/USD", "ЕТН", "123456789012345678901234567890ABC"})
    void anIllegalMarketIdIsRejected(String market) {
        assertThatThrownBy(() -> new RoundWindow(market, Interval.HOURLY, 0L, HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market id");
    }

    @ParameterizedTest
    @NullSource
    void aNullMarketIsABadArgumentLikeAnyOtherIllegalMarketId(String market) {
        assertThatThrownBy(() -> new RoundWindow(market, Interval.HOURLY, 0L, HOUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market id");
        assertThatThrownBy(() -> new RoundRef(market, Interval.HOURLY, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market id");
        assertThatThrownBy(() -> RoundClock.windowContaining(market, Interval.HOURLY, 0L, RoundDurations.DEFAULT, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWindowMustStillEndAfterItStarts() {
        assertThatThrownBy(() -> new RoundWindow("ETH", Interval.HOURLY, HOUR, HOUR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoundWindow("ETH", Interval.HOURLY, HOUR, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoundWindow(Interval.HOURLY, HOUR, HOUR)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- parseRoundId ---

    @Test
    void parseRoundIdRoundTripsEveryMarketIntervalAndStart() {
        List<String> markets = List.of("BTC", "ETH", "SOL", "DOGE_2", "a", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
        List<Long> starts = List.of(0L, 1L, HOUR, APRIL_1, 1_775_001_600_001L, Long.MAX_VALUE - DAY);
        for (String market : markets) {
            for (Interval interval : Interval.values()) {
                for (long start : starts) {
                    RoundWindow window = new RoundWindow(market, interval, start, start + HOUR);

                    RoundRef ref = RoundWindow.parseRoundId(window.roundId());

                    assertThat(ref).as(window.roundId()).isEqualTo(new RoundRef(market, interval, start));
                    assertThat(ref.roundId()).isEqualTo(window.roundId());
                }
            }
        }
    }

    @Test
    void parseRoundIdReadsTheLegacyTwoPartFormAsBtc() {
        assertThat(RoundWindow.parseRoundId("1h-1775001600000")).isEqualTo(new RoundRef("BTC", Interval.HOURLY, APRIL_1));
        assertThat(RoundWindow.parseRoundId("1d-0")).isEqualTo(new RoundRef("BTC", Interval.DAILY, 0L));
    }

    @Test
    void parseRoundIdReadsTheThreePartFormAsMarketCodeStart() {
        assertThat(RoundWindow.parseRoundId("ETH-1h-1775001600000")).isEqualTo(new RoundRef("ETH", Interval.HOURLY, APRIL_1));
        assertThat(RoundWindow.parseRoundId("SOL_2-1d-86400000")).isEqualTo(new RoundRef("SOL_2", Interval.DAILY, DAY));
        // a market literally called "1h" is legal (ids are [A-Za-z0-9_]) and still unambiguous: three parts
        assertThat(RoundWindow.parseRoundId("1h-1d-5")).isEqualTo(new RoundRef("1h", Interval.DAILY, 5L));
    }

    @Test
    void parseRoundIdReadsTheExplicitBtcSpellingAsTheSameRefAsTheLegacyId() {
        // E2: three parts are market, code, start — BTC included. An id built generically as
        // market + code + start therefore parses for every market, BTC's two-part form being its alias.
        assertThat(RoundWindow.parseRoundId("BTC-1h-1775001600000")).isEqualTo(new RoundRef("BTC", Interval.HOURLY, APRIL_1));
        assertThat(RoundWindow.parseRoundId("BTC-1d-0")).isEqualTo(new RoundRef("BTC", Interval.DAILY, 0L));
        assertThat(RoundWindow.parseRoundId("BTC-1h-1775001600000")).isEqualTo(RoundWindow.parseRoundId("1h-1775001600000"));
    }

    @Test
    void theBtcAliasNormalisesToTheLegacyIdSoTheKeyIsOneString() {
        for (Interval interval : Interval.values()) {
            RoundRef viaAlias = RoundWindow.parseRoundId("BTC-" + interval.code() + "-" + APRIL_1);

            assertThat(viaAlias.roundId()).isEqualTo(interval.code() + "-" + APRIL_1);
            assertThat(viaAlias.roundId()).isEqualTo(new RoundWindow(interval, APRIL_1, APRIL_1 + HOUR).roundId());
            assertThat(RoundWindow.parseRoundId(viaAlias.roundId())).isEqualTo(viaAlias);
        }
    }

    @Test
    void onlyBtcHasAnAliasEveryOtherMarketHasOneSpelling() {
        // "ETH-1h-5" is the one spelling of that round (roundId() prints it), nothing shortens it
        assertThat(RoundWindow.parseRoundId("ETH-1h-5").roundId()).isEqualTo("ETH-1h-5");
        // and a market whose id merely looks like the two-part form is still a market
        assertThat(RoundWindow.parseRoundId("1h-1d-5")).isEqualTo(new RoundRef("1h", Interval.DAILY, 5L));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", "-", "--", "---", "1h", "1h-", "-5", "1h-abc", "1h-5-", "1h-5.5", "1h- 5", " 1h-5", "1h-5 ", "1h-0x10",
            "2h-100", "1H-100", "1w-100", "hourly-100", "1h-9223372036854775808" /* overflows long */,
            "ETH-2h-100", "ETH-1H-100", "ETH-1h-xyz", "ETH-1h-", "ETH--5", "ETH-1h-5-6", "a-b-c-d", "ETH-1h-5-6-7",
            "-1h-5" /* empty market */, "ETH!-1h-5", "ET H-1h-5", "ЕТН-1h-5" /* Cyrillic */,
            "123456789012345678901234567890ABC-1h-5" /* 33-char market */,
            // not the canonical spelling of an otherwise readable id: a round has one id (plus the BTC alias)
            "1h-05", "1h-+5", "1h--5", "ETH-1h-007", "ETH-1h-+7", "ETH-1h-٣" /* Arabic-Indic digit */,
            "BTC-1h-05", "BTC-1h-+5", "BTC-1h-٣", "BTC-2h-5", "BTC-1H-5", "BTC-1h-", "BTC-1h-abc", "BTC-1h-9223372036854775808"})
    void malformedRoundIdsAreRejected(String roundId) {
        assertThatThrownBy(() -> RoundWindow.parseRoundId(roundId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("malformed roundId: " + roundId);
    }

    @Test
    void everyIdARoundWindowProducesParsesBackToItself() {
        for (String id : List.of("1h-0", "1d-86400000", "ETH-1h-7", "X-1d-1775001600000")) {
            assertThat(RoundWindow.parseRoundId(id).roundId()).isEqualTo(id);
        }
    }

    // --- Interval.fromCode ---

    @Test
    void intervalFromCodeIsTheInverseOfCode() {
        assertThat(Interval.fromCode("1h")).isEqualTo(Interval.HOURLY);
        assertThat(Interval.fromCode("1d")).isEqualTo(Interval.DAILY);
        for (Interval interval : Interval.values()) {
            assertThat(Interval.fromCode(interval.code())).isEqualTo(interval);
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "1H", "1D", "2h", "1w", "hourly", "HOURLY", " 1h"})
    void intervalFromCodeRejectsAnythingElse(String code) {
        assertThatThrownBy(() -> Interval.fromCode(code)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- RoundClock keeps the market ---

    @Test
    void nextKeepsTheMarketAndStaysContiguous() {
        RoundWindow eth = new RoundWindow("ETH", Interval.HOURLY, APRIL_1, APRIL_1 + HOUR);

        RoundWindow next = RoundClock.next(eth);

        assertThat(next.market()).isEqualTo("ETH");
        assertThat(next.interval()).isEqualTo(Interval.HOURLY);
        assertThat(next.startMs()).isEqualTo(eth.endMs());
        assertThat(next.endMs() - next.startMs()).isEqualTo(HOUR);
        assertThat(next.roundId()).isEqualTo("ETH-1h-" + (APRIL_1 + HOUR));
        assertThat(RoundClock.next(RoundClock.next(eth)).market()).isEqualTo("ETH");
    }

    @Test
    void nextOfABtcWindowIsStillBtcWithTheLegacyId() {
        RoundWindow btc = new RoundWindow(Interval.DAILY, APRIL_1, APRIL_1 + DAY);

        RoundWindow next = RoundClock.next(btc);

        assertThat(next.market()).isEqualTo("BTC");
        assertThat(next.roundId()).isEqualTo("1d-" + (APRIL_1 + DAY));
    }

    @Test
    void windowContainingWithAMarketFloorsLikeTheBtcOverloads() {
        long midHour = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();

        RoundWindow eth = RoundClock.windowContaining("ETH", Interval.HOURLY, midHour, RoundDurations.DEFAULT, 0L);

        assertThat(eth.market()).isEqualTo("ETH");
        assertThat(eth.startMs()).isEqualTo(Instant.parse("2026-04-05T13:00:00Z").toEpochMilli());
        assertThat(eth.endMs()).isEqualTo(Instant.parse("2026-04-05T14:00:00Z").toEpochMilli());
        assertThat(eth.roundId()).isEqualTo("ETH-1h-" + eth.startMs());
        // same slot as the BTC overload, only the market differs
        RoundWindow btc = RoundClock.windowContaining(Interval.HOURLY, midHour);
        assertThat(eth.startMs()).isEqualTo(btc.startMs());
        assertThat(eth.endMs()).isEqualTo(btc.endMs());
    }

    @Test
    void windowContainingWithBtcAsTheMarketEqualsTheExistingOverload() {
        long t = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();

        assertThat(RoundClock.windowContaining("BTC", Interval.HOURLY, t, RoundDurations.DEFAULT, 0L))
                .isEqualTo(RoundClock.windowContaining(Interval.HOURLY, t, RoundDurations.DEFAULT, 0L))
                .isEqualTo(RoundClock.windowContaining(Interval.HOURLY, t));
    }

    @Test
    void theExistingOverloadsReturnBtcWindows() {
        long t = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();

        assertThat(RoundClock.windowContaining(Interval.HOURLY, t).market()).isEqualTo("BTC");
        assertThat(RoundClock.windowContaining(Interval.DAILY, t).market()).isEqualTo("BTC");
        assertThat(RoundClock.windowContaining(Interval.HOURLY, t, new RoundDurations(180_000L, 900_000L), 12_345L).market())
                .isEqualTo("BTC");
    }

    @Test
    void windowContainingWithAMarketHonoursTestModeDurationsAndTheOrigin() {
        RoundDurations testMode = new RoundDurations(180_000L, 900_000L); // D-46: 3 min / 15 min
        long origin = Instant.parse("2026-04-05T13:47:22.500Z").toEpochMilli();

        RoundWindow third = RoundClock.windowContaining("ETH", Interval.HOURLY, origin + 450_000L, testMode, origin);

        assertThat(third.market()).isEqualTo("ETH");
        assertThat(third.startMs()).isEqualTo(origin + 360_000L);
        assertThat(third.endMs()).isEqualTo(origin + 540_000L);
        assertThat(RoundClock.next(third).market()).isEqualTo("ETH");
        assertThat(RoundClock.next(third).startMs()).isEqualTo(third.endMs());
    }

    @Test
    void windowContainingRejectsAnIllegalMarketId() {
        assertThatThrownBy(() -> RoundClock.windowContaining("E-TH", Interval.HOURLY, 0L, RoundDurations.DEFAULT, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
