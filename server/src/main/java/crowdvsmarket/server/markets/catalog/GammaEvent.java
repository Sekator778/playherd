package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The part of a Gamma event the catalog needs: its identity, its title (the subtitle of its markets),
 * whether its game is being played, the league or tournament it belongs to, and the teams (their
 * abbreviations make the card ticker). It comes either as the stub inside a market of a
 * {@code markets/keyset} answer (the series, no teams) or as the full object of an {@code events/keyset}
 * answer (the series, the sport and the teams).
 *
 * @param gameId a game has several events (the main one, "… - More Markets", …) with the same game id
 * @param league the league or tournament when Gamma gives one: the title of the event's first series
 *               ("UEFA Nations League", "NFL 2026", "MLB"), else the name of its sport ("CS2");
 *               {@code null} for an event that has neither (every politics event so far)
 */
record GammaEvent(String id, String title, boolean live, boolean ended, String gameId, String imageUrl,
                  List<Team> teams, String league) {

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
                GammaJson.text(node, "gameId"), image, List.copyOf(teams), league(node));
    }

    /** The first series' title, else the sport's name (polymarket_data_api.md §13.5); null when the event has neither. */
    private static String league(JsonNode event) {
        JsonNode series = event.get("series");
        if (series != null && series.isArray()) {
            for (JsonNode one : series) {
                String title = GammaJson.text(one, "title");
                if (title != null) {
                    return title;
                }
            }
        }
        JsonNode sport = event.get("sport");
        return sport != null && sport.isObject() ? GammaJson.text(sport, "name") : null;
    }
}
