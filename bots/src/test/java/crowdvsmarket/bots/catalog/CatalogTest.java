package crowdvsmarket.bots.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.bots.persona.Personas;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CatalogTest {

    private static final String ONE = """
            {"version": 1, "bots": [
              {"catalogId": "momentum-3-disciplined", "name": "Sprinter", "strategy": "momentum", "params": {"n": 3}, "persona": "disciplined"}
            ]}""";

    @Test
    void shippedCatalogLoadsWith21Bots() {
        Catalog c = Catalog.load();
        assertThat(c.bots()).hasSize(21);
        assertThat(c.bots().stream().map(CatalogEntry::catalogId).collect(Collectors.toSet())).hasSize(21);
        assertThat(c.bots().stream().map(CatalogEntry::name).collect(Collectors.toSet())).hasSize(21);
    }

    @Test
    void priceStrategiesHaveTwoOrThreeVariantsAndTheRestAreAsDesigned() {
        Map<String, Long> perStrategy = Catalog.load().bots().stream()
                .collect(Collectors.groupingBy(CatalogEntry::strategy, Collectors.counting()));
        for (String price : List.of("momentum", "rsi", "bollinger", "donchian", "ema")) {
            assertThat(perStrategy.get(price)).as(price).isBetween(2L, 3L);
        }
        assertThat(perStrategy).containsEntry("crowd-follow", 2L).containsEntry("crowd-contrarian", 2L).containsEntry("coin", 2L);
        assertThat(perStrategy.keySet()).containsExactlyInAnyOrderElementsOf(Strategies.kinds());
    }

    @Test
    void variantsOfOneStrategyDifferInParametersAndStrategyIdsAreUnique() {
        Catalog c = Catalog.load();
        Map<String, Long> ids = c.bots().stream().filter(e -> !e.strategy().startsWith("crowd") && !e.strategy().equals("coin"))
                .collect(Collectors.groupingBy(e -> e.signalStrategy().id(), Collectors.counting()));
        assertThat(ids.values()).allMatch(n -> n == 1L);
    }

    @Test
    void everyPersonaIsUsedAndEveryEntryBuildsAProfile() {
        Catalog c = Catalog.load();
        assertThat(c.bots().stream().map(e -> e.persona().split("@")[0]).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(Personas.ids());
        for (CatalogEntry e : c.bots()) {
            assertThat(e.profile("player-1").strategy().id()).isNotBlank();
            assertThat(e.profile("player-1").persona().id()).isEqualTo(e.persona().split("@")[0]);
        }
        assertThat(c.byId("momentum-3-disciplined")).isPresent();
        assertThat(c.byId("nope")).isEmpty();
    }

    /** Math review (bots_math_review_2026-10-16.md, sections 3 and 5): the personas' ranges and one full Maximalist. */
    @Test
    void personaVariantsStayInsideTheMathReviewRanges() {
        List<String> personas = Catalog.load().bots().stream().map(CatalogEntry::persona).toList();
        assertThat(personas.stream().filter(p -> p.equals("maximalist"))).hasSize(1);
        assertThat(personas.stream().filter(p -> p.equals("maximalist@0.5"))).hasSize(2);
        for (String p : personas) {
            String[] parts = p.contains("@") ? p.split("@")[1].split("/") : new String[0];
            if (p.startsWith("loss-chaser@")) {
                assertThat(Double.parseDouble(parts[0])).isBetween(0.5, 1.0);
                assertThat(Integer.parseInt(parts[1])).isBetween(3, 5);
            } else if (p.startsWith("house-money@")) {
                assertThat(Double.parseDouble(parts[0])).isBetween(0.5, 1.0);
                assertThat(Integer.parseInt(parts[1])).isBetween(3, 5);
                assertThat(Double.parseDouble(parts[2])).isBetween(1.5, 2.0);
            } else {
                assertThat(p).as("no bare loss-chaser or house-money").isNotIn("loss-chaser", "house-money");
            }
        }
    }

    @Test
    void aMinimalDocumentParses() {
        assertThat(Catalog.parse(ONE).bots()).hasSize(1);
    }

    @Test
    void invalidDocumentsAreRejectedWithTheEntryNamed() {
        Function<String, String> bad = doc -> {
            try {
                Catalog.parse(doc);
                return "accepted";
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        };
        assertThat(bad.apply("not json")).contains("JSON");
        assertThat(bad.apply("{\"version\": 2, \"bots\": []}")).contains("version");
        assertThat(bad.apply("{\"version\": 1, \"bots\": []}")).contains("no bots");
        assertThat(bad.apply(ONE.replace("\"disciplined\"", "\"reckless\""))).contains("unknown persona");
        assertThat(bad.apply(ONE.replace("\"momentum\"", "\"astrology\""))).contains("unknown strategy");
        assertThat(bad.apply(ONE.replace("{\"n\": 3}", "{}"))).contains("parameters");
        assertThat(bad.apply(ONE.replace("{\"n\": 3}", "{\"n\": 3, \"x\": 1}"))).contains("parameters");
        assertThat(bad.apply(ONE.replace("{\"n\": 3}", "{\"n\": 2.5}"))).contains("whole number");
        assertThat(bad.apply(ONE.replace("{\"n\": 3}", "{\"n\": 0}"))).contains("momentum-3-disciplined");
        assertThat(bad.apply(ONE.replace("momentum-3-disciplined", "Bad Id"))).contains("catalogId");
        String twice = ONE.replace("]}", ", " + ONE.substring(ONE.indexOf("{\"catalogId"), ONE.lastIndexOf("]}")) + "]}");
        assertThat(bad.apply(twice)).contains("duplicate catalogId");
        String rsi = "{\"version\": 1, \"bots\": [{\"catalogId\": \"r\", \"name\": \"R\", \"strategy\": \"rsi\","
                + " \"params\": {\"n\": 14, \"low\": 70, \"high\": 30}, \"persona\": \"cautious\"}]}";
        assertThat(bad.apply(rsi)).contains("RSI");
    }

    @Test
    void sameNameTwiceIsRejected() {
        String second = "{\"catalogId\": \"momentum-5-cautious\", \"name\": \"Sprinter\", \"strategy\": \"momentum\","
                + " \"params\": {\"n\": 5}, \"persona\": \"cautious\"}";
        String doc = ONE.replace("]}", ", " + second + "]}");
        assertThatThrownBy(() -> Catalog.parse(doc)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }

    @Test
    void jsonReaderHandlesTheBasics() {
        Object v = Json.parse("{\"a\": [1, 2.5, -3e2, true, false, null, \"x\\ny\\u0041\"], \"b\": {}}");
        assertThat(v).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        assertThat(m.get("a")).isEqualTo(java.util.Arrays.asList(1.0, 2.5, -300.0, true, false, null, "x\nyA"));
        assertThat(m.get("b")).isEqualTo(Map.of());
        assertThat(Set.of("{", "[1,", "{\"a\" 1}", "[1] x", "\"abc")).allSatisfy(
                s -> assertThatThrownBy(() -> Json.parse(s)).isInstanceOf(IllegalArgumentException.class));
    }
}
