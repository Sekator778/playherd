package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.prices.Fixtures;
import org.junit.jupiter.api.Test;

/** The card ticker of a Polymarket market: at most 8 characters, upper case, from the team, the outcome or the question. */
class MarketLabelsTest {

    private static GammaMarket market(String question, String outcomes) {
        return market(question, outcomes, "");
    }

    private static GammaMarket market(String question, String outcomes, String eventJson) {
        String escaped = outcomes.replace("\"", "\\\"");
        return GammaMarket.parse(Fixtures.parse("{\"id\":\"123\",\"question\":\"" + question.replace("\"", "'")
                + "\",\"outcomes\":\"" + escaped + "\",\"clobTokenIds\":\"[\\\"a\\\",\\\"b\\\"]\""
                + eventJson + "}"), null).orElseThrow();
    }

    private static String teamsEvent(String... namesAndAbbreviations) {
        StringBuilder teams = new StringBuilder();
        for (int i = 0; i < namesAndAbbreviations.length; i += 2) {
            teams.append(i > 0 ? "," : "").append("{\"name\":\"").append(namesAndAbbreviations[i])
                    .append("\",\"abbreviation\":\"").append(namesAndAbbreviations[i + 1]).append("\"}");
        }
        return ",\"events\":[{\"id\":\"1\",\"title\":\"T\",\"teams\":[" + teams + "]}]";
    }

    @Test
    void aYesNoQuestionIsNamedByItsFirstSignificantWord() {
        assertThat(MarketLabels.symbol(market("Will the U.S. invade Iran before 2027?", "[\"Yes\",\"No\"]"))).isEqualTo("US");
        assertThat(MarketLabels.symbol(market("Will Bitcoin hit 100k by December?", "[\"Yes\",\"No\"]")))
                .isEqualTo("BITCOIN");
        assertThat(MarketLabels.symbol(market("Will the Fed cut rates in 2026?", "[\"Yes\",\"No\"]"))).isEqualTo("FED");
    }

    @Test
    void numbersFillersAndPossessivesAreSkipped() {
        assertThat(MarketLabels.symbol(market("Will 2026 be the hottest year?", "[\"Yes\",\"No\"]"))).isEqualTo("HOTTEST");
        assertThat(MarketLabels.symbol(market("Will Trump's tariffs stay?", "[\"Yes\",\"No\"]"))).isEqualTo("TRUMP");
        assertThat(MarketLabels.symbol(market("Over or under?", "[\"Over\",\"Under\"]"))).isEqualTo("OVER");
    }

    @Test
    void aLongWordIsCutToEightCharacters() {
        assertThat(MarketLabels.symbol(market("Will Alexandria Ocasio-Cortez run?", "[\"Yes\",\"No\"]"))).isEqualTo("ALEXANDR");
        assertThat(MarketLabels.symbol(market("Will Flávio Bolsonaro win?", "[\"Yes\",\"No\"]"))).isEqualTo("FLÁVIO");
    }

    @Test
    void anOutcomeThatNamesSomethingWinsOverTheQuestion() {
        assertThat(MarketLabels.symbol(market("Steelers vs. Browns", "[\"Steelers\",\"Browns\"]"))).isEqualTo("STEELERS");
        assertThat(MarketLabels.symbol(market("Philadelphia Phillies vs. Atlanta Braves",
                "[\"Philadelphia Phillies\",\"Atlanta Braves\"]"))).isEqualTo("PHILADEL");
        assertThat(MarketLabels.symbol(market("Totals", "[\"Over\",\"Under\"]"))).isEqualTo("TOTALS"); // generic outcome: the question
    }

    @Test
    void theTeamsAbbreviationIsUsedWhenTheEventKnowsTheTeams() {
        String event = teamsEvent("Denmark", "den", "Portugal", "prt");

        // outcome 0 is the team
        assertThat(MarketLabels.symbol(market("Denmark vs. Portugal", "[\"Portugal\",\"Denmark\"]", event))).isEqualTo("PRT");
        // outcome 0 is Yes: the team the question mentions first
        assertThat(MarketLabels.symbol(market("Will Portugal win on 2026-10-01?", "[\"Yes\",\"No\"]", event))).isEqualTo("PRT");
        // the team the question mentions first
        assertThat(MarketLabels.symbol(market("Will Denmark beat Portugal?", "[\"Yes\",\"No\"]", event))).isEqualTo("DEN");
        // a market on a draw is on neither team
        assertThat(MarketLabels.symbol(market("Will Denmark vs. Portugal end in a draw?", "[\"Yes\",\"No\"]", event)))
                .isEqualTo("DRAW");
        assertThat(MarketLabels.symbol(market("Will the match be drawn?", "[\"Yes\",\"No\"]"))).isEqualTo("MATCH");
        // the team name matches without regard to case
        assertThat(MarketLabels.symbol(market("x", "[\"DENMARK\",\"y\"]", event))).isEqualTo("DEN");
    }

    @Test
    void aTeamWithoutAnAbbreviationFallsBackToItsName() {
        String event = ",\"events\":[{\"id\":\"1\",\"teams\":[{\"name\":\"FaZe\"},{\"name\":\"Nemiga\"}]}]";

        assertThat(MarketLabels.symbol(market("FaZe vs Nemiga", "[\"FaZe\",\"Nemiga\"]", event))).isEqualTo("FAZE");
    }

    @Test
    void whenThereIsNoSignificantWordTheTickerIsPmAndTheId() {
        assertThat(MarketLabels.symbol(market("Will the?", "[\"Yes\",\"No\"]"))).isEqualTo("PM123");
    }

    @Test
    void everySymbolOfTheSampleFitsTheCardAndIsUpperCase() {
        for (String file : new String[] {"gamma_markets_politics.json", "gamma_markets_sports.json"}) {
            for (var node : Fixtures.json("polymarket/" + file).get("markets")) {
                GammaMarket m = GammaMarket.parse(node, null).orElseThrow();
                String symbol = MarketLabels.symbol(m);
                assertThat(symbol).as(m.question()).isNotBlank().hasSizeLessThanOrEqualTo(8)
                        .isEqualTo(symbol.toUpperCase(java.util.Locale.ROOT));
            }
        }
    }
}
