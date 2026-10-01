package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.prices.FakeApis;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A scripted order book for the fake CLOB: per token {@code mid}, best {@code bid} and best
 * {@code ask}, answered the way the live endpoints did on 2026-10-01 — {@code /midpoints} as
 * {@code {"<token>":"0.145"}}, {@code /prices} as {@code {"<token>":{"BUY":<bid>,"SELL":<ask>}}}
 * (BUY is the bid) — and silent about a token that is not in it. {@link #of} derives the book from
 * the {@code outcomePrices[0]}, {@code bestBid} and {@code bestAsk} of Gamma market objects, which on
 * the fixtures equal the CLOB's numbers (polymarket_data_api.md §3.5).
 */
final class ClobBook {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String[]> quotes = new LinkedHashMap<>(); // token -> {mid, bid, ask}

    static ClobBook of(JsonNode... marketsOrEvents) {
        ClobBook book = new ClobBook();
        for (JsonNode array : marketsOrEvents) {
            for (JsonNode node : array) {
                if (node.has("markets") && node.get("markets").isArray()) {
                    for (JsonNode market : node.get("markets")) {
                        book.add(market);
                    }
                } else {
                    book.add(node);
                }
            }
        }
        return book;
    }

    private void add(JsonNode market) {
        try {
            String token = JSON.readTree(market.get("clobTokenIds").asText()).get(0).asText();
            String mid = JSON.readTree(market.get("outcomePrices").asText()).get(0).asText();
            String bid = market.hasNonNull("bestBid") ? market.get("bestBid").asText() : null;
            String ask = market.hasNonNull("bestAsk") ? market.get("bestAsk").asText() : null;
            quotes.put(token, new String[] {mid, bid, ask});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    ClobBook set(String token, String mid, String bid, String ask) {
        quotes.put(token, new String[] {mid, bid, ask});
        return this;
    }

    ClobBook remove(String token) {
        quotes.remove(token);
        return this;
    }

    /** Answers {@code /midpoints} and {@code /prices} of {@code clob} from this book. */
    void install(FakeApis.Clob clob) {
        clob.midpoints.handler = body -> {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (JsonNode item : body) {
                String[] q = quotes.get(item.get("token_id").asText());
                if (q != null && q[0] != null) {
                    out.put(item.get("token_id").asText(), q[0]);
                }
            }
            return out;
        };
        clob.prices.handler = body -> {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (JsonNode item : body) {
                String token = item.get("token_id").asText();
                String[] q = quotes.get(token);
                if (q == null) {
                    continue;
                }
                String price = "BUY".equals(item.get("side").asText()) ? q[1] : q[2];
                if (price != null) {
                    ObjectNode sides = out.has(token) ? (ObjectNode) out.get(token) : out.putObject(token);
                    sides.put(item.get("side").asText(), price);
                }
            }
            return out;
        };
    }
}
