package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.prices.Fixtures;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The card texts of a Polymarket market (L1, L2): a ticker of at most 8 characters, upper case, unique among the
 * active markets, from the market's own short label (a person), the team (a game) or the question; and a subtitle
 * that says what the outcome is. Table-driven over the real markets of the 2026-10-01/02 samples
 * ({@code fixtures/polymarket/}, with {@code groupItemTitle} and the series as Gamma sends them) and the titles
 * of the live stand that read poorly: two politics markets that were both "US", Lula as "LUIZ", the games as
 * "STEELERS", "PHI" and "NORTH".
 */
class MarketLabelsTest {

    private static GammaMarket market(String question, String outcomes) {
        return market(question, outcomes, "");
    }

    private static GammaMarket market(String question, String outcomes, String extraJson) {
        String escaped = outcomes.replace("\"", "\\\"");
        return GammaMarket.parse(Fixtures.parse("{\"id\":\"123\",\"question\":\"" + question.replace("\"", "'")
                + "\",\"outcomes\":\"" + escaped + "\",\"clobTokenIds\":\"[\\\"a\\\",\\\"b\\\"]\""
                + extraJson + "}"), null).orElseThrow();
    }

    private static String teamsEvent(String... namesAndAbbreviations) {
        StringBuilder teams = new StringBuilder();
        for (int i = 0; i < namesAndAbbreviations.length; i += 2) {
            teams.append(i > 0 ? "," : "").append("{\"name\":\"").append(namesAndAbbreviations[i])
                    .append("\",\"abbreviation\":\"").append(namesAndAbbreviations[i + 1]).append("\"}");
        }
        return ",\"events\":[{\"id\":\"1\",\"title\":\"T\",\"teams\":[" + teams + "]}]";
    }

    private static GammaMarket sample(String file, String id) {
        for (JsonNode node : Fixtures.json("polymarket/" + file).get("markets")) {
            if (id.equals(node.get("id").asText())) {
                return GammaMarket.parse(node, null).orElseThrow();
            }
        }
        throw new AssertionError("no market " + id + " in " + file);
    }

    private static GammaMarket liveGameMarket(String eventId, String marketId) {
        for (JsonNode eventNode : Fixtures.json("polymarket/gamma_events_live.json").get("events")) {
            if (eventId.equals(eventNode.get("id").asText())) {
                GammaEvent event = GammaEvent.parse(eventNode);
                for (JsonNode node : eventNode.get("markets")) {
                    if (marketId.equals(node.get("id").asText())) {
                        return GammaMarket.parse(node, event).orElseThrow();
                    }
                }
            }
        }
        throw new AssertionError("no market " + marketId + " in event " + eventId);
    }

    // ---------------------------------------------------------------- the ticker of the real markets

    @Test
    void politicsMarketsGetTheLastCapitalisedWordOfTheNameTheMarketItselfUses() {
        // the label Gamma gives (groupItemTitle) is the name; the question is the fallback
        record Row(String id, String symbol) {
        }
        List<Row> table = List.of(
                new Row("601830", "ALCKMIN"), // groupItemTitle "Geraldo Alckmin"
                new Row("601826", "BOLSONAR"), // "Flávio Bolsonaro", cut to eight characters
                new Row("601819", "SILVA"), // "Luiz Inácio Lula da Silva": it was LUIZ, the first name
                new Row("601818", "FREITAS"), // "Tarcisio de Freitas": the particle belongs to the name
                new Row("559681", null)); // not in this fixture
        for (Row row : table) {
            if (row.symbol() == null) {
                continue;
            }
            assertThat(MarketLabels.symbol(sample("gamma_markets_politics.json", row.id()))).as(row.id())
                    .isEqualTo(row.symbol());
        }
    }

    @Test
    void aSubjectWithNoNameInItIsNamedByItsFirstProperNounNotByAGenericWord() {
        // "Will the U.S. invade Iran before 2027?" and "US announces end of Iranian blockade…" were both "US"
        assertThat(MarketLabels.symbol(sample("gamma_markets_politics.json", "665374"))).isEqualTo("IRAN"); // no label
        assertThat(MarketLabels.symbol(sample("gamma_markets_politics.json", "3128888"))).isEqualTo("IRANIAN"); // label is a date
        assertThat(MarketLabels.symbol(sample("gamma_markets_politics.json", "562831"))).isEqualTo("BALANCE"); // label not in the question
    }

    @Test
    void theNameTheMarketItselfUsesAsItsShortLabelBeatsTheFirstProperNounOfTheQuestion() {
        // the samples' labels repeat the first name run of the question; here they do not
        String yesNo = "[\"Yes\",\"No\"]";
        String question = "Will the next Prime Minister of the UK be Keir Starmer?";
        GammaMarket labelled = market(question, yesNo, ",\"groupItemTitle\":\"Keir Starmer\"");
        GammaMarket unlabelled = market(question, yesNo);
        GammaMarket labelNotInTheQuestion = market(question, yesNo, ",\"groupItemTitle\":\"Other\"");

        assertThat(MarketLabels.symbol(labelled)).isEqualTo("STARMER");
        assertThat(MarketLabels.symbol(unlabelled)).as("no label: the first run of capitalised words, its last word")
                .isEqualTo("MINISTER");
        assertThat(MarketLabels.symbol(labelNotInTheQuestion)).as("a label the question does not say names nothing")
                .isEqualTo("MINISTER");
        // and when the label's own name is taken, its other words come before the question's words
        assertThat(MarketLabels.symbol(labelled, Set.of("STARMER"))).isEqualTo("KEIR");
    }

    @Test
    void gamesGetTheTeamsAbbreviationWhenGammaGivesOneElseTheTeamNameShortenedToEightCharacters() {
        // the live-games listing carries the teams (abbreviations) ...
        assertThat(MarketLabels.symbol(liveGameMarket("1042358", "4678642"))).isEqualTo("PRT"); // Portugal, Yes/No market
        assertThat(MarketLabels.symbol(liveGameMarket("1042358", "4678638"))).isEqualTo("DEN");
        assertThat(MarketLabels.symbol(liveGameMarket("1042358", "4678640"))).isEqualTo("DRAW"); // on neither team
        assertThat(MarketLabels.symbol(liveGameMarket("1038938", "4642062"))).isEqualTo("FAZE");
        // ... the markets listing does not: the team's name, shortened
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "4678642"))).isEqualTo("PORTUGAL");
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "3760412"))).isEqualTo("STEELERS");
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "5073589"))).isEqualTo("PHILLIES"); // not PHILADEL
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "4678603"))).isEqualTo("LIECHTEN");
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "4642062"))).isEqualTo("FAZE");
    }

    @Test
    void aFuturesMarketOnATeamIsNotAGameAndIsNamedLikeAPerson() {
        assertThat(MarketLabels.symbol(sample("gamma_markets_sports.json", "2772194"))).isEqualTo("DONETSK");
    }

    // --------------------------------------------------------------------------- clashes (L1)

    @Test
    void aSymbolIsUniqueAmongTheActiveMarketsTheNextDistinctiveWordThenANumber() {
        GammaMarket october31 = sample("gamma_markets_politics.json", "3128888");
        GammaMarket october15 = sample("gamma_markets_politics.json", "4906127"); // the same subject, another date

        assertThat(MarketLabels.symbol(october31, Set.of())).isEqualTo("IRANIAN");
        assertThat(MarketLabels.symbol(october15, Set.of("IRANIAN"))).isEqualTo("ANNOUNCE"); // the next distinctive word
        assertThat(MarketLabels.symbol(october15, Set.of("IRANIAN", "ANNOUNCE"))).isEqualTo("END");
        // taken symbols are compared without case, and any market's symbol counts: a perp's too
        assertThat(MarketLabels.symbol(october31, Set.of("iranian"))).isEqualTo("ANNOUNCE");
    }

    @Test
    void whenEveryWordIsTakenANumericSuffixIsAppendedAndKeptWithinEightCharacters() {
        GammaMarket m = market("Will Trump win?", "[\"Yes\",\"No\"]");
        Set<String> taken = new LinkedHashSet<>(MarketLabels.candidates(m));
        assertThat(MarketLabels.symbol(m, taken)).isEqualTo("TRUMP2");
        taken.add("TRUMP2");
        assertThat(MarketLabels.symbol(m, taken)).isEqualTo("TRUMP3");

        GammaMarket long8 = market("Will Bolsonaro win?", "[\"Yes\",\"No\"]");
        Set<String> all = new LinkedHashSet<>(MarketLabels.candidates(long8));
        all.add("BOLSONA2");
        assertThat(MarketLabels.symbol(long8, all)).hasSize(8).isEqualTo("BOLSONA3");
    }

    @Test
    void twoNumberRangesOfOneEventAreToldApartByTheNameBehindThem() {
        // "Will Elon Musk post 160-179 tweets …" and "… 180-199 …": the labels are numbers, the name is in the question
        GammaMarket first = market("Will Elon Musk post 160-179 tweets from September 25 to October 2, 2026?",
                "[\"Yes\",\"No\"]", ",\"groupItemTitle\":\"160-179\"");
        GammaMarket second = market("Will Elon Musk post 180-199 tweets from September 25 to October 2, 2026?",
                "[\"Yes\",\"No\"]", ",\"groupItemTitle\":\"180-199\"");

        assertThat(MarketLabels.symbol(first)).isEqualTo("MUSK");
        assertThat(MarketLabels.symbol(second, Set.of("MUSK"))).isEqualTo("ELON");
    }

    @Test
    void theSymbolsOfTheWholeSampleAreUniqueWhenListedOneAfterTheOther() {
        Set<String> taken = new LinkedHashSet<>();
        for (String file : new String[] {"gamma_markets_politics.json", "gamma_markets_sports.json"}) {
            for (JsonNode node : Fixtures.json("polymarket/" + file).get("markets")) {
                GammaMarket m = GammaMarket.parse(node, null).orElseThrow();
                String symbol = MarketLabels.symbol(m, taken);
                assertThat(symbol).as(m.question()).isNotBlank().hasSizeLessThanOrEqualTo(8)
                        .isEqualTo(symbol.toUpperCase(Locale.ROOT));
                assertThat(taken).as(m.question()).doesNotContain(symbol);
                taken.add(symbol);
            }
        }
        assertThat(taken).doesNotContain("US");
    }

    // ------------------------------------------------------------------------------- the words

    @Test
    void theCountryMonthsNumbersAndFillerWordsNeverMakeATicker() {
        assertThat(MarketLabels.candidates(market("Will the U.S. invade Iran before 2027?", "[\"Yes\",\"No\"]")))
                .doesNotContain("US", "WILL", "THE", "BEFORE", "2027");
        assertThat(MarketLabels.candidates(market("Will USA win by March 31, 2027?", "[\"Yes\",\"No\"]")))
                .doesNotContain("USA", "MARCH", "WILL", "BY", "31", "2027");
        assertThat(MarketLabels.candidates(market("Who will win after August 2026?", "[\"Yes\",\"No\"]")))
                .doesNotContain("WHO", "AFTER", "AUGUST", "WILL");
        assertThat(MarketLabels.symbol(market("Will 2026 be the hottest year?", "[\"Yes\",\"No\"]"))).isEqualTo("HOTTEST");
        assertThat(MarketLabels.symbol(market("Will Trump's tariffs stay?", "[\"Yes\",\"No\"]"))).isEqualTo("TRUMP");
    }

    @Test
    void aBareYesNoOverUnderOutcomeNamesNothing() {
        assertThat(MarketLabels.symbol(market("Over or under?", "[\"Over\",\"Under\"]"))).isEqualTo("OVER");
        assertThat(MarketLabels.candidates(market("Will it rain in Paris?", "[\"Yes\",\"No\"]"))).doesNotContain("YES", "NO");
    }

    @Test
    void aLongWordIsCutToEightCharacters() {
        assertThat(MarketLabels.symbol(market("Will Alexandria Ocasio-Cortez run?", "[\"Yes\",\"No\"]")))
                .isEqualTo("CORTEZ"); // a hyphen separates words: the last capitalised word of the name
        assertThat(MarketLabels.symbol(market("Will Zbigniew Brzezinski run?", "[\"Yes\",\"No\"]"))).isEqualTo("BRZEZINS");
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
        // the team name matches without regard to case
        assertThat(MarketLabels.symbol(market("x", "[\"DENMARK\",\"y\"]", event))).isEqualTo("DEN");
    }

    @Test
    void twoGamesOfOneAbbreviationAreToldApartByTheTeamsName() {
        // PHI is the Eagles in the NFL and the Phillies in the MLB
        String nfl = teamsEvent("Philadelphia Eagles", "phi", "Dallas Cowboys", "dal");
        String mlb = teamsEvent("Philadelphia Phillies", "phi", "Atlanta Braves", "atl");
        GammaMarket eagles = market("Eagles vs. Cowboys", "[\"Philadelphia Eagles\",\"Dallas Cowboys\"]", nfl);
        GammaMarket phillies = market("Phillies vs. Braves", "[\"Philadelphia Phillies\",\"Atlanta Braves\"]", mlb);

        assertThat(MarketLabels.symbol(eagles)).isEqualTo("PHI");
        assertThat(MarketLabels.symbol(phillies, Set.of("PHI"))).isEqualTo("PHILLIES");
    }

    @Test
    void aTeamWithoutAnAbbreviationFallsBackToItsName() {
        String event = ",\"events\":[{\"id\":\"1\",\"teams\":[{\"name\":\"FaZe\"},{\"name\":\"Nemiga\"}]}]";

        assertThat(MarketLabels.symbol(market("FaZe vs Nemiga", "[\"FaZe\",\"Nemiga\"]", event))).isEqualTo("FAZE");
    }

    @Test
    void shortenKeepsAShortNameWholeAndTakesTheNicknameOfALongOne() {
        assertThat(MarketLabels.shorten("Browns")).isEqualTo("BROWNS");
        assertThat(MarketLabels.shorten("Atlanta Braves")).isEqualTo("BRAVES");
        assertThat(MarketLabels.shorten("North Carolina Tar Heels")).isEqualTo("HEELS");
        assertThat(MarketLabels.shorten("Netherlands")).isEqualTo("NETHERLA");
        assertThat(MarketLabels.shorten("Real Madrid")).isEqualTo("MADRID");
        assertThat(MarketLabels.shorten("LA FC")).isEqualTo("LAFC"); // fits once the space is gone
        assertThat(MarketLabels.shorten("Esport Academy Copenhagen")).isEqualTo("COPENHAG");
    }

    @Test
    void whenThereIsNoSignificantWordTheTickerIsPmAndTheId() {
        assertThat(MarketLabels.symbol(market("Will the?", "[\"Yes\",\"No\"]"))).isEqualTo("PM123");
    }

    // -------------------------------------------------------------------------- the subtitle (L2)

    @Test
    void aGamesSubtitleSaysWhoWinsAndInWhichLeagueNotTheTitleAgain() {
        // it was "Steelers · Steelers vs. Browns" under the title "Steelers vs. Browns"
        GammaMarket steelers = sample("gamma_markets_sports.json", "3760412");
        assertThat(steelers.question()).isEqualTo("Steelers vs. Browns");
        assertThat(MarketLabels.subtitle(steelers)).isEqualTo("Steelers to win · NFL 2026");
        assertThat(MarketLabels.subtitle(sample("gamma_markets_sports.json", "5073589")))
                .isEqualTo("Philadelphia Phillies to win · MLB");
        // Yes/No: the team is the market's own label for it
        assertThat(MarketLabels.subtitle(sample("gamma_markets_sports.json", "4678642")))
                .isEqualTo("Portugal to win · UEFA Nations League");
        // from the live-games listing: the league also comes from the sport, and a draw is a draw
        assertThat(MarketLabels.subtitle(liveGameMarket("1042358", "4678642")))
                .isEqualTo("Portugal to win · UEFA Nations League");
        assertThat(MarketLabels.subtitle(liveGameMarket("1042358", "4678640"))).isEqualTo("Draw · UEFA Nations League");
        for (String id : List.of("3760412", "5073589", "4678642", "4642062", "4678603")) {
            GammaMarket game = sample("gamma_markets_sports.json", id);
            assertThat(MarketLabels.subtitle(game)).as(id).doesNotContain(game.question());
        }
    }

    @Test
    void aGameWithoutALeagueIsJustWhoWins() {
        assertThat(MarketLabels.subtitle(market("Steelers vs. Browns", "[\"Steelers\",\"Browns\"]",
                ",\"sportsMarketType\":\"moneyline\",\"gameStartTime\":\"2026-10-02 00:15:00+00\"")))
                .isEqualTo("Steelers to win");
    }

    @Test
    void aTotalsMarketIsNotABetOnAWinner() {
        assertThat(MarketLabels.subtitle(sample("gamma_markets_sports.json", "4679591")))
                .startsWith("Over").doesNotContain("to win");
    }

    @Test
    void anOddsMarketOutsideSportsShowsItsEventTitleOnlyWhenItIsNotTheQuestionAgain() {
        // an event of one market: the title is the question
        assertThat(MarketLabels.subtitle(sample("gamma_markets_politics.json", "665374"))).isEqualTo("Yes");
        // an event of many: which election the name is about
        assertThat(MarketLabels.subtitle(sample("gamma_markets_politics.json", "601819")))
                .isEqualTo("Yes · Brazil Presidential Election");
        assertThat(MarketLabels.subtitle(sample("gamma_markets_politics.json", "3128888")))
                .isEqualTo("Yes · US announces end of Iranian blockade by...?");
        // the same words in another case, without the punctuation: still the question again
        assertThat(MarketLabels.subtitle(market("Will it rain?", "[\"Yes\",\"No\"]",
                ",\"events\":[{\"id\":\"9\",\"title\":\"WILL IT RAIN\"}]"))).isEqualTo("Yes");
        assertThat(MarketLabels.subtitle(market("Will it rain?", "[\"Yes\",\"No\"]"))).isEqualTo("Yes"); // no event at all
    }
}
