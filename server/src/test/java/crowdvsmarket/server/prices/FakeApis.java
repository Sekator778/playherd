package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Scripted stand-ins for the four REST clients: each records the requests it got and answers with
 * whatever its handler returns (or throws). The tests of the sources and catalogs never open a
 * socket.
 */
public final class FakeApis {

    private FakeApis() {
    }

    /** One scripted endpoint. */
    public static class Script<I, O> {

        public final List<I> requests = new ArrayList<>();
        public Function<I, O> handler = in -> {
            throw new IllegalStateException("unexpected call " + in);
        };

        O call(I in) {
            requests.add(in);
            return handler.apply(in);
        }
    }

    public static class Hyperliquid implements HyperliquidInfoApi {

        public final Script<JsonNode, JsonNode> info = new Script<>();

        @Override
        public JsonNode info(JsonNode body) {
            return info.call(body);
        }

        /** The requests whose {@code type} is {@code type}. */
        public List<JsonNode> requestsOfType(String type) {
            return info.requests.stream().filter(r -> type.equals(r.path("type").asText())).toList();
        }
    }

    public static class Clob implements PolymarketClobApi {

        public final Script<JsonNode, JsonNode> midpoints = new Script<>();
        public final Script<JsonNode, JsonNode> prices = new Script<>();

        @Override
        public JsonNode midpoints(JsonNode tokens) {
            return midpoints.call(tokens);
        }

        @Override
        public JsonNode prices(JsonNode tokenSides) {
            return prices.call(tokenSides);
        }
    }

    public static class Data implements PolymarketDataApi {

        public record Call(String tokenId, long start, long end, int bucketSeconds, String cursor) {
        }

        public final Script<Call, JsonNode> history = new Script<>();

        @Override
        public JsonNode pricesHistory(String tokenId, long startSeconds, long endSeconds, int bucketSeconds,
                                      String cursor) {
            return history.call(new Call(tokenId, startSeconds, endSeconds, bucketSeconds, cursor));
        }
    }

    public static class Gamma implements PolymarketGammaApi {

        public record MarketsCall(String tagId, boolean closed, String order, boolean ascending, int limit) {
        }

        public record EventsCall(String tagId, boolean live, boolean closed, String order, boolean ascending,
                                 int limit) {
        }

        public record TokensCall(List<String> tokenIds, int limit) {
        }

        public final Script<MarketsCall, JsonNode> markets = new Script<>();
        public final Script<EventsCall, JsonNode> events = new Script<>();
        public final Script<TokensCall, JsonNode> byTokens = new Script<>();

        @Override
        public JsonNode markets(String tagId, boolean closed, String order, boolean ascending, int limit) {
            return markets.call(new MarketsCall(tagId, closed, order, ascending, limit));
        }

        @Override
        public JsonNode marketsByTokens(List<String> tokenIds, int limit) {
            return byTokens.call(new TokensCall(List.copyOf(tokenIds), limit));
        }

        @Override
        public JsonNode events(String tagId, boolean live, boolean closed, String order, boolean ascending,
                               int limit) {
            return events.call(new EventsCall(tagId, live, closed, order, ascending, limit));
        }
    }
}
