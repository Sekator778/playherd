package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Polymarket's CLOB midpoints and data-API history against the answers of 2026-10-01 (fixtures/
 * polymarket/): the live probability of outcome 0 per token, a missing token reported, the outage
 * guard, the 24-hour change and the 60-second buckets as history.
 */
class PolymarketPriceSourceTest {

    private static final String IRAN = "55115078421062885512539156303747803058407616201213034911037320915726138659123";
    private static final String FLAVIO = "109876868437950584369987384406356259939519193117253465815665152916226511121427";
    private static final String PORTUGAL = "30832906867199915342888320351856964164180073964149724713946648532860513266622";
    private static final String CLOSED = "1234567890123";

    private static final Market M_IRAN = TestMarkets.odds("PM665374", IRAN);
    private static final Market M_FLAVIO = TestMarkets.odds("PM601826", FLAVIO);
    private static final Market M_PORTUGAL = TestMarkets.odds("PM4678642", PORTUGAL);
    private static final Market M_CLOSED = TestMarkets.odds("PM5140154", CLOSED);

    private final AtomicLong now = new AtomicLong(1_790_883_900_000L);
    private PolymarketLiveState state;
    private FakeApis.Clob clob;
    private FakeApis.Data data;
    private PolymarketPriceSource source;

    @BeforeEach
    void setUp() {
        state = new PolymarketLiveState();
        clob = new FakeApis.Clob();
        data = new FakeApis.Data();
        source = new PolymarketPriceSource(() -> clob, () -> data, state, PricesConfig.defaults(), now::get);
    }

    @Test
    void readsTheMidpointOfEachTokenAsAProbability() {
        JsonNode answer = Fixtures.json("polymarket/clob_midpoints.json");

        Map<String, PriceSource.Quote> quotes = source.parse(answer, List.of(M_IRAN, M_FLAVIO, M_PORTUGAL));

        assertThat(quotes.get("PM665374").raw()).isEqualByComparingTo("0.145");
        assertThat(quotes.get("PM601826").raw()).isEqualByComparingTo("0.6205");
        assertThat(quotes.get("PM4678642").raw()).isEqualByComparingTo("0.715");
        assertThat(quotes.values()).allSatisfy(q -> assertThat(q.change24h()).isNull()); // nothing from the catalog yet
    }

    @Test
    void theOneDayChangeFromTheCatalogBecomesARelativeChange() {
        state.setOneDayChange("PM4678642", 0.24); // Gamma: probability +0.24 over 24 h; now 0.715, a day ago 0.475
        state.setOneDayChange("PM665374", -0.01); // 0.145 now, 0.155 a day ago

        Map<String, PriceSource.Quote> quotes = source.parse(Fixtures.json("polymarket/clob_midpoints.json"),
                List.of(M_IRAN, M_PORTUGAL, M_FLAVIO));

        assertThat(quotes.get("PM4678642").change24h()).isEqualTo(0.24 / 0.475, org.assertj.core.data.Offset.offset(1e-7));
        assertThat(quotes.get("PM665374").change24h()).isEqualTo(-0.01 / 0.155, org.assertj.core.data.Offset.offset(1e-7));
        assertThat(quotes.get("PM601826").change24h()).isNull();
    }

    @Test
    void aChangeThatWouldMeanANonPositivePriceADayAgoGivesNone() {
        assertThat(PolymarketPriceSource.relativeChange(new BigDecimal("0.065"), -0.4)).isEqualTo(-0.4 / 0.465,
                org.assertj.core.data.Offset.offset(1e-7));
        assertThat(PolymarketPriceSource.relativeChange(new BigDecimal("0.30"), 0.30)).isNull(); // was 0
        assertThat(PolymarketPriceSource.relativeChange(new BigDecimal("0.30"), 0.45)).isNull(); // was negative
        assertThat(PolymarketPriceSource.relativeChange(new BigDecimal("0.30"), null)).isNull();
    }

    @Test
    void aTokenMissingFromAnAnswerThatHasOthersIsReportedWithTheTimeOfTheFirstSighting() {
        JsonNode answer = Fixtures.json("polymarket/clob_midpoints_with_closed.json"); // flavio and portugal only

        Map<String, PriceSource.Quote> quotes = source.parse(answer, List.of(M_FLAVIO, M_PORTUGAL, M_CLOSED));
        long firstSeen = now.get();
        now.addAndGet(3_000);
        source.parse(answer, List.of(M_FLAVIO, M_PORTUGAL, M_CLOSED));

        assertThat(quotes).containsOnlyKeys("PM601826", "PM4678642");
        assertThat(state.missingSince("PM5140154")).hasValue(firstSeen); // kept from the first poll
        assertThat(state.missingSince("PM601826")).isEmpty();
    }

    @Test
    void aTokenThatComesBackIsNoLongerMissing() {
        state.reportMissing("PM601826", 5);

        source.parse(Fixtures.json("polymarket/clob_midpoints.json"), List.of(M_FLAVIO, M_IRAN));

        assertThat(state.missingSince("PM601826")).isEmpty();
    }

    @Test
    void anAnswerWithNoneOfSeveralTokensIsTakenForAnOutageAndReportsNothing() {
        Map<String, PriceSource.Quote> quotes = source.parse(Fixtures.parse("{}"), List.of(M_FLAVIO, M_PORTUGAL));

        assertThat(quotes).isEmpty();
        assertThat(state.missingSince("PM601826")).isEmpty();
        assertThat(state.missingSince("PM4678642")).isEmpty();
    }

    @Test
    void aLoneTokenThatIsMissingIsReportedItIsTheOnlyWayItsClosingShowsUp() {
        source.parse(Fixtures.parse("{}"), List.of(M_CLOSED));

        assertThat(state.missingSince("PM5140154")).isPresent();
    }

    @Test
    void aMidpointThatIsNotAProbabilityIsDroppedButTheTokenCountsAsPresent() {
        JsonNode answer = Fixtures.parse("{\"" + IRAN + "\":\"1.5\",\"" + FLAVIO + "\":\"0\",\"" + PORTUGAL + "\":\"0.5\"}");

        Map<String, PriceSource.Quote> quotes = source.parse(answer, List.of(M_IRAN, M_FLAVIO, M_PORTUGAL));

        assertThat(quotes).containsOnlyKeys("PM4678642");
        assertThat(state.missingSince("PM665374")).isEmpty();
    }

    @Test
    void pollsOnceForAllTokensWithTheDocumentedBody() throws Exception {
        clob.midpoints.handler = body -> Fixtures.json("polymarket/clob_midpoints.json");

        Map<String, PriceSource.Quote> quotes = source.poll(source.feeds().get(0), List.of(M_IRAN, M_PORTUGAL));

        assertThat(quotes).containsOnlyKeys("PM665374", "PM4678642");
        assertThat(clob.midpoints.requests).hasSize(1);
        assertThat(clob.midpoints.requests.get(0).toString())
                .isEqualTo("[{\"token_id\":\"" + IRAN + "\"},{\"token_id\":\"" + PORTUGAL + "\"}]");
    }

    @Test
    void anAnswerThatIsNotAnObjectIsAFailedPoll() {
        clob.midpoints.handler = body -> Fixtures.parse("[]");

        assertThatThrownBy(() -> source.poll(source.feeds().get(0), List.of(M_IRAN))).isInstanceOf(IOException.class);
    }

    @Test
    void theFeedHandlesPolymarketMarketsWithTheArchitecturesLimits() {
        PriceSource.Feed feed = source.feeds().get(0);

        assertThat(feed.id()).isEqualTo("polymarket");
        assertThat(feed.staleMs()).isEqualTo(30_000);
        assertThat(feed.maxGapMs()).isEqualTo(90_000);
        assertThat(feed.handles().test(M_IRAN)).isTrue();
        assertThat(feed.handles().test(TestMarkets.btc())).isFalse();
    }

    // -------------------------------------------------------------------------------------- history

    @Test
    void historyIsTheSixtySecondBucketsAndTheTerminalTick() {
        JsonNode answer = Fixtures.json("polymarket/data_prices_history.json");
        long first = answer.get("data").get(0).get("timestamp").asLong() * 1000;
        long last = answer.get("data").get(answer.get("data").size() - 1).get("timestamp").asLong() * 1000;

        List<PricePoint> points = PolymarketPriceSource.historyPoints(answer, 0, Long.MAX_VALUE);

        assertThat(points).hasSize(answer.get("data").size());
        assertThat(points.get(0).atMs()).isEqualTo(first);
        assertThat(points.get(points.size() - 1).atMs()).isEqualTo(last); // the terminal tick, resolution_seconds 0
        assertThat(points).extracting(p -> p.raw().toPlainString())
                .containsExactly("0.725", "0.695", "0.715", "0.705", "0.715", "0.715", "0.715", "0.715");
        assertThat(PolymarketPriceSource.historyPoints(answer, first + 1, last - 1)).hasSize(points.size() - 2);
    }

    @Test
    void historyAsksTheDataApiForSixtySecondBucketsOverTheRange() throws Exception {
        data.history.handler = call -> Fixtures.json("polymarket/data_prices_history.json");
        long from = 1_790_883_100_500L;
        long to = 1_790_883_700_200L;

        source.history(M_PORTUGAL, from, to);

        assertThat(data.history.requests).hasSize(1);
        FakeApis.Data.Call call = data.history.requests.get(0);
        assertThat(call.tokenId()).isEqualTo(PORTUGAL);
        assertThat(call.start()).isEqualTo(1_790_883_100L); // seconds, rounded outwards
        assertThat(call.end()).isEqualTo(1_790_883_701L);
        assertThat(call.bucketSeconds()).isEqualTo(60);
        assertThat(call.cursor()).isNull();
    }

    @Test
    void historyFollowsThePagesAndMergesThem() throws Exception {
        data.history.handler = call -> call.cursor() == null
                ? Fixtures.parse("{\"data\":[{\"timestamp\":1790883300,\"price\":0.5,\"resolution_seconds\":60}],"
                + "\"pagination\":{\"has_more\":true,\"next_cursor\":\"c1\"}}")
                : Fixtures.parse("{\"data\":[{\"timestamp\":1790883360,\"price\":0.6,\"resolution_seconds\":60}],"
                + "\"pagination\":{\"has_more\":false,\"next_cursor\":null}}");

        List<PricePoint> points = source.history(M_PORTUGAL, 1_790_883_000_000L, 1_790_884_000_000L);

        assertThat(points).extracting(PricePoint::atMs).containsExactly(1_790_883_300_000L, 1_790_883_360_000L);
        assertThat(data.history.requests).extracting(FakeApis.Data.Call::cursor).containsExactly(null, "c1");
    }

    @Test
    void historyOfALongerHoleThanTheApiServesAsksForItsNewestFifteenDays() throws Exception {
        data.history.handler = call -> Fixtures.parse("{\"data\":[]}");
        long to = 1_790_883_700_000L;

        source.history(M_PORTUGAL, to - 40L * 24 * 3_600_000L, to);

        FakeApis.Data.Call call = data.history.requests.get(0);
        assertThat(call.end() - call.start()).isEqualTo(15L * 24 * 3_600);
    }
}
