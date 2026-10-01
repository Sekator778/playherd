package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.prices.Fixtures;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Gamma's JSON read defensively (docs/research/polymarket_data_api.md §2.3): JSON inside strings,
 * ids as numbers, dates in two spellings, optional and missing fields, markets that are not binary.
 */
class GammaMarketTest {

    private static final String TOKEN_A = "11111111111111111111111111111111111111111111111111111111111111111111111111111";
    private static final String TOKEN_B = "22222222222222222222222222222222222222222222222222222222222222222222222222222";

    private static String market(String extra) {
        return "{\"id\":\"42\",\"question\":\"Will it?\",\"outcomes\":\"[\\\"Yes\\\", \\\"No\\\"]\","
                + "\"clobTokenIds\":\"[\\\"" + TOKEN_A + "\\\", \\\"" + TOKEN_B + "\\\"]\"," + extra + "}";
    }

    private static Optional<GammaMarket> parse(String json) {
        return GammaMarket.parse(Fixtures.parse(json), null);
    }

    @Test
    void readsARealMarketOfTheSample() {
        JsonNode iran = Fixtures.json("polymarket/gamma_markets_politics.json").get("markets").get(0);

        GammaMarket m = GammaMarket.parse(iran, null).orElseThrow();

        assertThat(m.id()).isEqualTo("665374");
        assertThat(m.question()).isEqualTo("Will the U.S. invade Iran before 2027?");
        assertThat(m.slug()).isEqualTo("will-the-us-invade-iran-before-2027");
        assertThat(m.outcomes()).containsExactly("Yes", "No");
        assertThat(m.tokenIds()).hasSize(2);
        assertThat(m.token()).isEqualTo(m.tokenIds().get(0));
        assertThat(m.outcome()).isEqualTo("Yes");
        assertThat(m.acceptingOrders()).isTrue();
        assertThat(m.enableOrderBook()).isTrue();
        assertThat(m.closed()).isFalse();
        assertThat(m.endMs()).isEqualTo(1_798_779_540_000L);
        assertThat(m.tickSize()).isEqualByComparingTo("0.01");
        assertThat(m.event().id()).isEqualTo("73130");
        assertThat(m.isGame()).isFalse();
        assertThat(m.inPlay()).isFalse();
        assertThat(m.eventKey()).isEqualTo("event:73130");
        assertThat(m.volume24hr()).isGreaterThan(400_000);
    }

    @Test
    void aGameMarketOfTheSampleKnowsItsGameAndThatItIsInPlay() {
        JsonNode portugal = Fixtures.json("polymarket/gamma_markets_sports.json").get("markets").get(0);

        GammaMarket m = GammaMarket.parse(portugal, null).orElseThrow();

        assertThat(m.isGame()).isTrue();
        assertThat(m.isMoneyline()).isTrue();
        assertThat(m.inPlay()).isTrue(); // the event stub says live
        assertThat(m.gameStartTime()).isEqualTo("2026-10-01 18:45:00+00");
        assertThat(m.eventKey()).isEqualTo("game:90108425");
        assertThat(m.oneDayChange()).isEqualTo(0.24);
    }

    @Test
    void outcomesAndTokensMayBeRealArraysAndIdsNumbers() {
        GammaMarket m = parse("{\"id\":777,\"question\":\"Q?\",\"outcomes\":[\"A\",\"B\"],\"clobTokenIds\":[\"x\",\"y\"]}")
                .orElseThrow();

        assertThat(m.id()).isEqualTo("777");
        assertThat(m.outcomes()).containsExactly("A", "B");
        assertThat(m.tokenIds()).containsExactly("x", "y");
    }

    @Test
    void aMarketThatIsNotBinaryOrHasNoTokensOrNoQuestionIsNotAMarketForUs() {
        assertThat(parse("{\"id\":\"1\",\"question\":\"Q\",\"outcomes\":\"[\\\"A\\\",\\\"B\\\",\\\"C\\\"]\","
                + "\"clobTokenIds\":\"[\\\"x\\\",\\\"y\\\",\\\"z\\\"]\"}")).isEmpty();
        assertThat(parse("{\"id\":\"1\",\"question\":\"Q\",\"outcomes\":\"[\\\"A\\\",\\\"B\\\"]\"}")).isEmpty();
        assertThat(parse("{\"id\":\"1\",\"question\":\"Q\",\"outcomes\":\"[\\\"A\\\",\\\"B\\\"]\","
                + "\"clobTokenIds\":\"[\\\"x\\\"]\"}")).isEmpty();
        assertThat(parse("{\"id\":\"1\",\"outcomes\":\"[\\\"A\\\",\\\"B\\\"]\",\"clobTokenIds\":\"[\\\"x\\\",\\\"y\\\"]\"}"))
                .isEmpty();
        assertThat(parse("{\"question\":\"Q\",\"outcomes\":\"[\\\"A\\\",\\\"B\\\"]\",\"clobTokenIds\":\"[\\\"x\\\",\\\"y\\\"]\"}"))
                .isEmpty();
        assertThat(parse("{\"id\":\"1\",\"question\":\"Q\",\"outcomes\":\"garbage\",\"clobTokenIds\":\"also garbage\"}"))
                .isEmpty();
        assertThat(GammaMarket.parse(Fixtures.parse("[]"), null)).isEmpty();
        assertThat(GammaMarket.parse(null, null)).isEmpty();
    }

    @Test
    void missingOptionalFieldsAreNullOrTheConservativeDefault() {
        GammaMarket m = parse(market("\"x\":1")).orElseThrow();

        assertThat(m.acceptingOrders()).isFalse(); // absent: not a candidate
        assertThat(m.enableOrderBook()).isFalse();
        assertThat(m.closed()).isFalse();
        assertThat(m.endMs()).isNull();
        assertThat(m.gameStartTime()).isNull();
        assertThat(m.tickSize()).isNull();
        assertThat(m.imageUrl()).isNull();
        assertThat(m.oneDayChange()).isNull();
        assertThat(m.sportsMarketType()).isNull();
        assertThat(m.volume24hr()).isZero();
        assertThat(m.event()).isNull();
        assertThat(m.eventKey()).isEqualTo("market:42");
    }

    @Test
    void booleansAndNumbersMayArriveAsText() {
        GammaMarket m = parse(market("\"acceptingOrders\":\"true\",\"enableOrderBook\":\"TRUE\",\"closed\":\"false\","
                + "\"oneDayPriceChange\":\"-0.04\",\"volume24hr\":\"1234.5\",\"orderPriceMinTickSize\":\"0.001\""))
                .orElseThrow();

        assertThat(m.acceptingOrders()).isTrue();
        assertThat(m.enableOrderBook()).isTrue();
        assertThat(m.closed()).isFalse();
        assertThat(m.oneDayChange()).isEqualTo(-0.04);
        assertThat(m.volume24hr()).isEqualTo(1234.5);
        assertThat(m.tickSize()).isEqualByComparingTo("0.001");
    }

    @Test
    void garbageNumbersAndBooleansFallBackInsteadOfFailing() {
        GammaMarket m = parse(market("\"acceptingOrders\":\"maybe\",\"oneDayPriceChange\":\"n/a\",\"volume24hr\":null"))
                .orElseThrow();

        assertThat(m.acceptingOrders()).isFalse();
        assertThat(m.oneDayChange()).isNull();
        assertThat(m.volume24hr()).isZero();
    }

    @Test
    void theImageFallsBackFromImageToIconToTheEventsImage() {
        assertThat(parse(market("\"image\":\"i.png\",\"icon\":\"c.png\"")).orElseThrow().imageUrl()).isEqualTo("i.png");
        assertThat(parse(market("\"icon\":\"c.png\"")).orElseThrow().imageUrl()).isEqualTo("c.png");
        assertThat(parse(market("\"events\":[{\"id\":\"9\",\"image\":\"e.png\"}]")).orElseThrow().imageUrl())
                .isEqualTo("e.png");
    }

    @Test
    void datesInBothOfGammasSpellings() {
        assertThat(GammaJson.millis("2027-01-01T04:59:00Z")).isEqualTo(1_798_779_540_000L);
        assertThat(GammaJson.millis("2026-10-01 18:45:00+00")).isEqualTo(1_790_880_300_000L); // gameStartTime
        assertThat(GammaJson.millis("2026-10-01 19:36:36.105447+00")).isEqualTo(1_790_883_396_105L);
        assertThat(GammaJson.millis("2026-10-01T20:45:00+02:00")).isEqualTo(1_790_880_300_000L);
        assertThat(GammaJson.millis("2026-10-01")).isEqualTo(1_790_812_800_000L); // the start of that UTC day
        assertThat(GammaJson.millis("  2026-10-01T18:45:00Z ")).isEqualTo(1_790_880_300_000L);
        assertThat(GammaJson.millis("soon")).isNull();
        assertThat(GammaJson.millis("")).isNull();
        assertThat(GammaJson.millis(null)).isNull();
    }

    @Test
    void anEventStubAndAFullEventBothParse() {
        JsonNode full = Fixtures.json("polymarket/gamma_events_live.json").get("events").get(0);

        GammaEvent event = GammaEvent.parse(full);

        assertThat(event.id()).isEqualTo("1042358");
        assertThat(event.title()).isEqualTo("Denmark vs. Portugal");
        assertThat(event.live()).isTrue();
        assertThat(event.ended()).isFalse();
        assertThat(event.gameId()).isEqualTo("90108425");
        assertThat(event.teams()).extracting(GammaEvent.Team::name).containsExactly("Denmark", "Portugal");
        assertThat(event.teams()).extracting(GammaEvent.Team::abbreviation).containsExactly("den", "prt");
        assertThat(event.key()).isEqualTo("game:90108425");

        GammaEvent stub = GammaEvent.parse(Fixtures.parse("{\"id\":\"5\",\"title\":\"T\"}"));
        assertThat(stub.live()).isFalse();
        assertThat(stub.teams()).isEmpty();
        assertThat(stub.key()).isEqualTo("event:5");
        assertThat(GammaEvent.parse(null)).isNull();
    }
}
