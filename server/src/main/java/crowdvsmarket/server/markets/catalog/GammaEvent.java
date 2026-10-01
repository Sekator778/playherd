package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of a Gamma event the catalog needs: its identity, its title (the subtitle of its markets),
 * whether its game is being played, and the teams (their abbreviations make the card ticker). It
 * comes either as the stub inside a market of a {@code markets/keyset} answer (no teams) or as the
 * full object of an {@code events/keyset} answer.
 *
 * @param gameId a game has several events (the main one, "… - More Markets", …) with the same game id
 */
record GammaEvent(String id, String title, boolean live, boolean ended, String gameId, String imageUrl,
                  List<Team> teams) {

    record Team(String name, String abbreviation) {
    }

    /** The identity of the game, so that the several events of one game count as one. */
    String key() {
        return gameId != null ? "game:" + gameId : "event:" + id;
    }

    static GammaEvent parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<Team> teams = new ArrayList<>();
        JsonNode array = node.get("teams");
        if (array != null && array.isArray()) {
            for (JsonNode team : array) {
                String name = GammaJson.text(team, "name");
                if (name != null) {
                    teams.add(new Team(name, GammaJson.text(team, "abbreviation")));
                }
            }
        }
        String image = GammaJson.text(node, "image");
        if (image == null) {
            image = GammaJson.text(node, "icon");
        }
        return new GammaEvent(GammaJson.text(node, "id"), GammaJson.text(node, "title"),
                GammaJson.bool(node, "live", false), GammaJson.bool(node, "ended", false),
                GammaJson.text(node, "gameId"), image, List.copyOf(teams));
    }
}
