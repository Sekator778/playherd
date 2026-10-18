package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A scripted scenario whose stored bytes are compared across code versions (docs/research/load_bench_design_2026-10-17.md §5,
 * item 2): the same steps on the same scripted prices must leave the same {@code game_events} payloads, canonical results
 * and positions. With {@code -Dherd.bench.dump=<file>} the test writes them out ({@code SELECT payload::text ... ORDER BY id}
 * for the events of this scenario, plus the settled round's {@code canonical_json} and {@code positions_json}); run on two
 * trees and {@code diff} the files. Without the property it only checks that the scenario produced them.
 *
 * <p>The windows lie on the wall-clock grid, so the instants in the dump are written relative to the betting window's start
 * ({@code T+<ms>}) and any other 13-digit instant (a registration or listing time taken from the clock) as {@code <clock>};
 * player ids are numbered in order of appearance. Nothing else is touched.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ScriptedScenarioPayloadsTest extends LiveGameTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INSTANT = Pattern.compile("(?<![0-9A-Za-z.])\\d{13}(?![0-9A-Za-z.])");

    @Test
    void theScriptedScenarioStoresTheSameBytes() throws Exception {
        long firstEventId = count("SELECT COALESCE(MAX(id), 0) AS n FROM game_events");
        Market market = list("T_BYTES", MarketKind.PERP, Category.CRYPTO, "BYT", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        long at = betting.startMs() - 1;

        List<String> players = new ArrayList<>();
        for (String label : List.of("alpha", "beta", "gamma")) {
            players.add(gameService.registerOrTouchPlayer("txch1scripted" + label + "0".repeat(40), at).player().playerId());
        }
        gameService.placeBet(players.get(0), bet(betting, Enums.Direction.UP, 10, "300.000"), at);
        gameService.placeBet(players.get(1), bet(betting, Enums.Direction.DOWN, 5, "200.000"), at);
        gameService.placeBet(players.get(2), bet(betting, Enums.Direction.UP, 2, "100.000"), at);
        gameService.placeBet(players.get(1), bet(betting, Enums.Direction.UP, 4, "250.000"), at); // replaces its bet
        gameService.cancelBet(players.get(2), betting.roundId(), at);                            // refunds its bet
        gameService.placeBet(players.get(2), bet(betting, Enums.Direction.DOWN, 3, "120.500"), at);

        scheduler.tick(running.endMs());   // `running` settles empty; `betting` is the running round now
        prices.script(market, betting, "2500.000", "2550.000", "2480.000", "2600.000");
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();

        RoundWindow next = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(players.get(0), bet(next, Enums.Direction.DOWN, 7, "100.001"), next.startMs() - 1);
        gameService.cancelRoundById(next.roundId(), "scripted scenario", next.startMs() - 1);
        rebuild.rebuild(betting.endMs());

        Map<String, String> ids = new LinkedHashMap<>();
        for (int i = 0; i < players.size(); i++) {
            ids.put(players.get(i), "P" + (i + 1));
        }
        StringBuilder dump = new StringBuilder();
        List<String> events = db.read(c -> db.queryList(c,
                "SELECT event_type || ' ' || payload::text AS line FROM game_events WHERE id > ? ORDER BY id",
                rs -> rs.getString("line"), firstEventId));
        for (String line : events) {
            // the DAILY series sits on a grid twice as coarse as the HOURLY one, so whether a DAILY window has ended by the
            // scenario's clock depends on the wall-clock phase it is run at: not part of the comparison
            boolean daily = line.contains("\"interval\": \"DAILY\"");
            if (!daily && (line.contains(market.id()) || players.stream().anyMatch(line::contains))) {
                dump.append("event ").append(normalise(line, betting.startMs(), ids)).append('\n');
            }
        }
        for (RoundWindow w : List.of(betting, next)) {
            // player ids are random, and the positions of a result come ordered by them: sort them by the numbered id
            for (String column : List.of("canonical_json", "positions_json::text", "crowd_json::text")) {
                List<String> rows = db.read(c -> db.queryList(c,
                        "SELECT " + column + " AS doc FROM round_results WHERE round_id = ?",
                        rs -> String.valueOf(rs.getString("doc")), w.roundId()));
                for (String row : rows) {
                    dump.append("result ").append(column).append(' ')
                            .append(normalise(sortedByPlayer(row, ids), betting.startMs(), ids)).append('\n');
                }
            }
        }
        for (String id : players) {
            dump.append("account ").append(ids.get(id)).append(' ').append(gameService.account(id).balance()).append(' ')
                    .append(gameService.account(id).inPlay()).append('\n');
        }

        assertThat(dump.toString()).contains("event BetPlaced", "event RoundResolved", "event RoundCancelled", "result ");
        String path = System.getProperty("herd.bench.dump");
        if (path != null) {
            Files.writeString(Path.of(path), dump.toString(), StandardCharsets.UTF_8);
        }
    }

    private long count(String sql) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n")));
    }

    /** {@code doc} with every array of objects that have a {@code playerId} put in the order of the numbered ids. */
    private static String sortedByPlayer(String doc, Map<String, String> playerIds) throws Exception {
        if (doc.equals("null")) {
            return doc;
        }
        JsonNode tree = JSON.readTree(doc);
        sortArrays(tree, playerIds);
        return JSON.writeValueAsString(tree);
    }

    private static void sortArrays(JsonNode node, Map<String, String> playerIds) {
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            List<JsonNode> items = new ArrayList<>();
            array.forEach(items::add);
            if (!items.isEmpty() && items.stream().allMatch(i -> i.isObject() && i.has("playerId"))) {
                items.sort(Comparator.comparing(i -> playerIds.getOrDefault(i.get("playerId").asText(), i.get("playerId").asText())));
                array.removeAll();
                items.forEach(array::add);
            }
        }
        node.forEach(child -> sortArrays(child, playerIds));
    }

    private static String normalise(String text, long baseMs, Map<String, String> playerIds) {
        String out = text;
        for (Map.Entry<String, String> e : playerIds.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        Matcher m = INSTANT.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long delta = Long.parseLong(m.group()) - baseMs;
            m.appendReplacement(sb, Math.abs(delta) < 100_000_000L ? "T" + (delta >= 0 ? "+" : "") + delta : "<clock>");
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
