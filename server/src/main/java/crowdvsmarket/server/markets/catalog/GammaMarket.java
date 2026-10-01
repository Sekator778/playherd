package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One Gamma market as the Polymarket catalog needs it (docs/research/polymarket_data_api.md §2.3),
 * read defensively by {@link #parse}. {@code event} is the parent event — taken from the market's own
 * {@code events[0]} stub (a {@code markets/keyset} answer) or handed in by the caller (an
 * {@code events/keyset} answer carries the markets inside the event).
 *
 * @param endMs         {@code endDate}; for a game market this is usually the kick-off time, not an end
 *                      (the sample soccer markets of 2026-10-01 have {@code endDate == gameStartTime})
 * @param oneDayChange  {@code oneDayPriceChange}: the absolute change of outcome 0's probability in 24 h
 */
record GammaMarket(
        String id,
        String question,
        String slug,
        String conditionId,
        List<String> outcomes,
        List<String> tokenIds,
        boolean acceptingOrders,
        boolean enableOrderBook,
        boolean closed,
        Long endMs,
        String gameStartTime,
        BigDecimal tickSize,
        String imageUrl,
        Double oneDayChange,
        String sportsMarketType,
        double volume24hr,
        GammaEvent event) {

    /** A game market has a start time (futures such as "wins the 2027 Champions League" have none). */
    boolean isGame() {
        return gameStartTime != null;
    }

    boolean isMoneyline() {
        return "moneyline".equals(sportsMarketType);
    }

    /** The game it belongs to is being played right now. */
    boolean inPlay() {
        return event != null && event.live() && !event.ended();
    }

    /** What "at most one market per event" counts: the game when there is one, else the event, else the market itself. */
    String eventKey() {
        return event != null ? event.key() : "market:" + id;
    }

    /** Outcome 0's CLOB token: the market's price source. */
    String token() {
        return tokenIds.get(0);
    }

    String outcome() {
        return outcomes.get(0);
    }

    /**
     * Reads one market object. Empty when it lacks what a market needs at all: an id, a question,
     * exactly two outcomes and two token ids.
     */
    static Optional<GammaMarket> parse(JsonNode node, GammaEvent parent) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String id = GammaJson.text(node, "id");
        String question = GammaJson.text(node, "question");
        List<String> outcomes = GammaJson.stringList(node, "outcomes");
        List<String> tokens = GammaJson.stringList(node, "clobTokenIds");
        if (id == null || question == null || outcomes.size() != 2 || tokens.size() != 2) {
            return Optional.empty();
        }
        GammaEvent event = parent;
        if (event == null) {
            JsonNode stubs = node.get("events");
            if (stubs != null && stubs.isArray() && !stubs.isEmpty()) {
                event = GammaEvent.parse(stubs.get(0));
            }
        }
        Double tick = GammaJson.number(node, "orderPriceMinTickSize");
        String image = GammaJson.text(node, "image");
        if (image == null) {
            image = GammaJson.text(node, "icon");
        }
        if (image == null && event != null) {
            image = event.imageUrl();
        }
        Double volume = GammaJson.number(node, "volume24hr");
        return Optional.of(new GammaMarket(id, question, GammaJson.text(node, "slug"),
                GammaJson.text(node, "conditionId"), List.copyOf(outcomes), List.copyOf(tokens),
                GammaJson.bool(node, "acceptingOrders", false), GammaJson.bool(node, "enableOrderBook", false),
                GammaJson.bool(node, "closed", false), GammaJson.millis(GammaJson.text(node, "endDate")),
                GammaJson.text(node, "gameStartTime"), tick == null ? null : BigDecimal.valueOf(tick), image,
                GammaJson.number(node, "oneDayPriceChange"), GammaJson.text(node, "sportsMarketType"),
                volume == null ? 0.0 : volume, event));
    }

    /** The markets of an answer's array ({@code markets} of a market listing, or an event's {@code markets}). */
    static List<GammaMarket> parseAll(JsonNode array, GammaEvent parent) {
        List<GammaMarket> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                parse(node, parent).ifPresent(out::add);
            }
        }
        return out;
    }
}
