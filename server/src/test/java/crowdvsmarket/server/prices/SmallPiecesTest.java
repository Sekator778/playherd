package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The small parts: tolerant decimal reading, the state shared by the Polymarket source and catalog, source lookup. */
class SmallPiecesTest {

    @Test
    void decReadsStringsAndNumbersAndGivesNullForEverythingElse() {
        assertThat(Dec.of(Fixtures.parse("\"84629.0\""))).isEqualByComparingTo("84629.0");
        assertThat(Dec.of(Fixtures.parse(" \"  0.094534 \""))).isEqualByComparingTo("0.094534");
        assertThat(Dec.of(Fixtures.parse("0.525"))).isEqualByComparingTo("0.525");
        assertThat(Dec.of(Fixtures.parse("12"))).isEqualByComparingTo("12");
        assertThat(Dec.of(Fixtures.parse("\"1e3\""))).isEqualByComparingTo("1000");

        assertThat(Dec.of(null)).isNull();
        assertThat(Dec.of(Fixtures.parse("null"))).isNull();
        assertThat(Dec.of(Fixtures.parse("\"\""))).isNull();
        assertThat(Dec.of(Fixtures.parse("\"oops\""))).isNull();
        assertThat(Dec.of(Fixtures.parse("\"NaN\""))).isNull();
        assertThat(Dec.of(Fixtures.parse("[1]"))).isNull();
        assertThat(Dec.of(Fixtures.parse("{\"a\":1}").get("missing"))).isNull();
        assertThat(Dec.of(Fixtures.parse("true"))).isNull();
    }

    @Test
    void theLiveStateKeepsTheFirstMissingTimeUntilThePriceReturns() {
        PolymarketLiveState state = new PolymarketLiveState();

        assertThat(state.missingSince("PM1")).isEmpty();
        state.reportMissing("PM1", 100);
        state.reportMissing("PM1", 160); // the first sighting stays
        assertThat(state.missingSince("PM1")).hasValue(100);

        state.reportPresent("PM1");
        assertThat(state.missingSince("PM1")).isEmpty();
        state.reportMissing("PM1", 500);
        assertThat(state.missingSince("PM1")).hasValue(500); // a new absence starts a new clock
    }

    @Test
    void theLiveStateKeepsTheDayChangeUntilItIsClearedOrTheMarketIsForgotten() {
        PolymarketLiveState state = new PolymarketLiveState();

        state.setOneDayChange("PM1", 0.24);
        state.setOneDayChange("PM2", -0.4);
        assertThat(state.oneDayChange("PM1")).isEqualTo(0.24);

        state.setOneDayChange("PM1", null);
        assertThat(state.oneDayChange("PM1")).isNull();
        state.setOneDayChange("PM2", Double.NaN);
        assertThat(state.oneDayChange("PM2")).isNull();

        state.setOneDayChange("PM3", 0.1);
        state.reportMissing("PM3", 5);
        state.forget("PM3");
        assertThat(state.oneDayChange("PM3")).isNull();
        assertThat(state.missingSince("PM3")).isEmpty();
    }

    /** A source with one feed that handles the markets whose id starts with a prefix. */
    private static PriceSource source(String name, String prefix) {
        return new PriceSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public List<Feed> feeds() {
                return List.of(new Feed(name + "-feed", 1, 2, m -> m.id().startsWith(prefix)));
            }

            @Override
            public Map<String, Quote> poll(Feed feed, List<crowdvsmarket.server.markets.Market> markets) {
                return Map.of();
            }

            @Override
            public List<PricePoint> history(crowdvsmarket.server.markets.Market market, long fromMs, long toMs) {
                return List.of();
            }
        };
    }

    @Test
    void sourcesAreLookedUpByMarketAndByFeedId() {
        PriceSource a = source("a", "A");
        PriceSource b = source("b", "B");
        PriceSources sources = new PriceSources(List.of(a, b));

        assertThat(sources.bindingFor(TestMarkets.hl("A1", "A1", 0)).orElseThrow().source()).isSameAs(a);
        assertThat(sources.bindingFor(TestMarkets.hl("B1", "B1", 0)).orElseThrow().source()).isSameAs(b);
        assertThat(sources.bindingFor(TestMarkets.hl("C1", "C1", 0))).isEmpty();
        assertThat(sources.bindingById("b-feed").orElseThrow().source()).isSameAs(b);
        assertThat(sources.bindingById("nope")).isEmpty();
        assertThat(sources.bindings()).extracting(x -> x.feed().id()).containsExactly("a-feed", "b-feed");
        assertThat(sources.active()).containsExactly(a, b);
    }

    @Test
    void theFirstFeedThatHandlesAMarketWins() {
        PriceSource first = source("first", "X");
        PriceSource second = source("second", "X");

        assertThat(new PriceSources(List.of(first, second)).bindingFor(TestMarkets.hl("X1", "X1", 0)).orElseThrow()
                .source()).isSameAs(first);
    }

    @Test
    void aTickIsStoredAsTwoLongsAndComesBackExact() {
        TickRing ring = new TickRing();
        for (int i = 0; i < 1_000; i++) {
            ring.append(i, Px.encode(new BigDecimal("84629.1234567")));
        }
        assertThat(ring.size()).isEqualTo(1_000);
        assertThat(Px.decode(ring.last()[1])).isEqualByComparingTo("84629.1234567");
    }
}
