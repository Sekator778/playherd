package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.FakeApis;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.PolymarketLiveState;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.TickStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Selection, listing and closing of the Polymarket markets against the Gamma and CLOB answers of
 * 2026-10-01 (fixtures/polymarket/, 19:45 UTC): degenerate prices and wide spreads are never listed,
 * at most one market per event, the category is topped up after a close, games in play come first,
 * and every closing rule.
 */
class PolymarketCatalogTest {

    private static final long NOW = Fixtures.NOW;

    private final JsonNode politics = Fixtures.json("polymarket/gamma_markets_politics.json");
    private final JsonNode sports = Fixtures.json("polymarket/gamma_markets_sports.json");
    private final JsonNode liveEvents = Fixtures.json("polymarket/gamma_events_live.json");

    private final AtomicLong now = new AtomicLong(NOW);
    private FakeMarketRegistry registry;
    private FakeApis.Gamma gamma;
    private FakeApis.Clob clob;
    private PolymarketLiveState state;
    private TickStore store;
    private ClobBook book;

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        gamma = new FakeApis.Gamma();
        clob = new FakeApis.Clob();
        state = new PolymarketLiveState();
        store = Fixtures.tickStore(now::get);
        gamma.markets.handler = call -> "2".equals(call.tagId()) ? politics : sports;
        gamma.events.handler = call -> liveEvents;
        gamma.byTokens.handler = call -> Fixtures.parse("{\"markets\":[]}"); // nothing closed at the source
        book = ClobBook.of(politics.get("markets"), sports.get("markets"), liveEvents.get("events"));
        book.install(clob);
    }

    private PolymarketCatalog catalog() {
        return catalog(CatalogConfig.defaults());
    }

    private PolymarketCatalog catalog(CatalogConfig config) {
        return new PolymarketCatalog(registry, config, PricesConfig.defaults(), () -> gamma, () -> clob, state, store,
                now::get);
    }

    private static CatalogConfig config(int politics, int sports, boolean inPlayFirst) {
        return new CatalogConfig(true, false, List.of("BTC"), List.of(), List.of(), List.of(), true, politics, sports, 10,
                inPlayFirst);
    }

    private static String token(JsonNode marketsArray, String marketId) {
        for (JsonNode m : marketsArray) {
            if (marketId.equals(m.get("id").asText())) {
                return Fixtures.parse(m.get("clobTokenIds").asText()).get(0).asText();
            }
        }
        throw new AssertionError("no market " + marketId);
    }

    private List<String> activeIds(String category) {
        return registry.active().stream().filter(m -> "polymarket".equals(m.source()) && category.equals(m.category()))
                .map(Market::id).toList();
    }

    private Market market(String id) {
        return registry.byId(id).orElseThrow(() -> new AssertionError("not listed: " + id));
    }

    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    // ---------------------------------------------------------------------------------- politics

    @Test
    void listsTheTopPoliticsMarketsThatPassTheBandAtMostOnePerEvent() {
        boolean ok = catalog(config(3, 0, true)).refresh(NOW);

        assertThat(ok).isTrue();
        // by volume: Iran 0.145 passes; Senate 0.075 and the 0.0015/0.0005 tails are below the band;
        // Flavio 0.6215 passes; Lula 0.375 would pass but belongs to Flavio's event; the blockade market of
        // October 31 passes; its October 15 twin is the same event
        assertThat(registry.log).containsExactly("listed PM665374", "listed PM601826", "listed PM3128888");
        assertThat(activeIds(Category.POLITICS)).containsExactly("PM665374", "PM601826", "PM3128888");
    }

    @Test
    void aListedPoliticsMarketIsMadeFromItsGammaMarket() {
        catalog(config(3, 0, true)).refresh(NOW);

        Market iran = market("PM665374");
        assertThat(iran.kind()).isEqualTo(MarketKind.ODDS);
        assertThat(iran.category()).isEqualTo(Category.POLITICS);
        assertThat(iran.title()).isEqualTo("Will the U.S. invade Iran before 2027?");
        assertThat(iran.subtitle()).isEqualTo("Yes"); // the event title is the question again: not repeated (L2)
        assertThat(iran.symbol()).isEqualTo("IRAN"); // it was "US" (L1)
        assertThat(iran.iconUrl()).startsWith("https://polymarket-upload.s3");
        assertThat(iran.source()).isEqualTo("polymarket");
        assertThat(iran.sourceRef()).isEqualTo(token(politics.get("markets"), "665374")); // outcome 0's CLOB token
        assertThat(iran.maxLeverage()).isEqualTo(10);
        assertThat(iran.lotExp()).isEqualTo(Market.ODDS_LOT_EXP);
        assertThat(iran.endsAtMs()).isEqualTo(ms("2027-01-01T04:59:00Z"));
        assertThat(iran.listedAtMs()).isEqualTo(NOW);
        JsonNode meta = Fixtures.parse(iran.sourceMeta());
        assertThat(meta.get("gammaId").asText()).isEqualTo("665374");
        assertThat(meta.get("slug").asText()).isEqualTo("will-the-us-invade-iran-before-2027");
        assertThat(meta.get("conditionId").asText()).startsWith("0x5db999");
        assertThat(meta.get("outcome").asText()).isEqualTo("Yes");
        assertThat(meta.get("eventTitle").asText()).isEqualTo("Will the U.S. invade Iran before 2027?");
        assertThat(meta.get("tickSize").asText()).isEqualTo("0.01");
        assertThat(meta.has("gameStartTime")).isFalse(); // not a game
        assertThat(meta.has("gameStartMs")).isFalse();

        Market flavio = market("PM601826");
        assertThat(flavio.symbol()).isEqualTo("BOLSONAR"); // the last capitalised word of groupItemTitle "Flávio Bolsonaro"
        assertThat(flavio.subtitle()).isEqualTo("Yes · Brazil Presidential Election");
        assertThat(flavio.endsAtMs()).isEqualTo(ms("2026-10-05T03:59:00Z"));
        assertThat(market("PM3128888").symbol()).isEqualTo("IRANIAN"); // it was "US" as well, and so was Iran's
    }

    @Test
    void theTickersOfTheMarketsListedInOnePassAreUniqueAmongAllTheActiveMarketsOfAllCategories() {
        // a perp that already shows ticker IRAN, and a second market of the blockade event that wants IRANIAN
        registry.add(new Market("T_IRAN", MarketKind.PERP, Category.CRYPTO, "IRAN", "Iran coin", null, null, "hyperliquid",
                "IRAN", null, 10, 0, 8, MarketStatus.ACTIVE, 0L, null, null, null));

        catalog(config(4, 0, true)).refresh(NOW);

        List<String> symbols = registry.active().stream().map(Market::symbol).toList();
        assertThat(symbols).doesNotHaveDuplicates();
        assertThat(market("PM665374").symbol()).isEqualTo("INVADE"); // IRAN is taken: the next distinctive word
        assertThat(symbols).doesNotContain("US");
    }

    @Test
    void aClosedMarketsTickerIsFreeAgain() {
        // the ticker is unique among the ACTIVE markets: a closed one does not keep it
        registry.add(new Market("T_OLD", MarketKind.ODDS, Category.POLITICS, "IRAN", "old", null, null, "polymarket", "t",
                null, 10, 4, 8, MarketStatus.CLOSED, 0L, 1L, "test", null));

        catalog(config(1, 0, true)).refresh(NOW);

        assertThat(market("PM665374").symbol()).isEqualTo("IRAN");
    }

    @Test
    void aWideBookIsRejectedAndTheNextCandidateTakesItsPlace() {
        // Iran's book 0.10 / 0.20: the midpoint 0.15 is in the band, the spread 0.10 is not
        book.set(token(politics.get("markets"), "665374"), "0.15", "0.10", "0.20").install(clob);

        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(registry.log).containsExactly("listed PM601826", "listed PM3128888"); // only two pass: Lula is Flavio's event
        assertThat(registry.byId("PM665374")).isEmpty();
    }

    @Test
    void degenerateMidpointsAreNeverListed() {
        // the whole top of the politics listing at 0.0005 and 0.9995 (a one-sided book's midpoint)
        for (JsonNode m : politics.get("markets")) {
            book.set(token(politics.get("markets"), m.get("id").asText()), "0.9995", "0.999", "1");
        }
        book.install(clob);

        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(registry.log).isEmpty();
    }

    @Test
    void aTokenTheClobDoesNotKnowIsNotListed() {
        book.remove(token(politics.get("markets"), "665374")).install(clob);

        catalog(config(1, 0, true)).refresh(NOW);

        assertThat(registry.log).containsExactly("listed PM601826"); // Iran has no price; the next one does
    }

    @Test
    void aBookMissingItsBidOrItsAskIsRejected() {
        book.set(token(politics.get("markets"), "665374"), "0.145", null, "0.15");
        book.set(token(politics.get("markets"), "601826"), "0.6215", "0.621", null);
        book.install(clob);

        catalog(config(1, 0, true)).refresh(NOW);

        // Iran and Flavio have a one-sided book; the next in the band is Lula (0.375)
        assertThat(registry.log).containsExactly("listed PM601819");
    }

    @Test
    void theBoundariesOfTheBandAndTheSpreadAreInclusive() {
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.10"), bd("0.09"), bd("0.11")).acceptable()).isTrue();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.90"), bd("0.89"), bd("0.91")).acceptable()).isTrue();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.50"), bd("0.475"), bd("0.525")).acceptable()).isTrue(); // spread 0.05
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.0999"), bd("0.09"), bd("0.11")).acceptable()).isFalse();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.9001"), bd("0.89"), bd("0.91")).acceptable()).isFalse();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.50"), bd("0.474"), bd("0.525")).acceptable()).isFalse(); // 0.051
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.50"), bd("0.52"), bd("0.51")).acceptable()).isFalse(); // crossed
        assertThat(new PolymarketCatalog.ClobQuote(null, bd("0.5"), bd("0.5")).acceptable()).isFalse();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.5"), null, bd("0.5")).acceptable()).isFalse();
        assertThat(new PolymarketCatalog.ClobQuote(bd("0.5"), bd("0.5"), null).acceptable()).isFalse();
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void anEventThatAlreadyHasAnActiveMarketGetsNoSecondOne() {
        registry.add(activePm("PM999", "tok999", Category.POLITICS, null, "{\"eventKey\":\"event:45915\"}"));

        catalog(config(3, 0, true)).refresh(NOW);

        // the Brazilian election (event 45915) is taken: Flavio and Lula are out, the list is Iran + blockade
        assertThat(registry.log).containsExactly("listed PM665374", "listed PM3128888");
    }

    @Test
    void aMarketTheRegistryAlreadyKnowsIsNeverListedAgainNotEvenAClosedOne() {
        Market closed = activePm("PM665374", "tokIran", Category.POLITICS, null, null).closed(NOW - 1, "test");
        registry.add(closed);

        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(market("PM665374").status()).isEqualTo(MarketStatus.CLOSED);
        assertThat(registry.log).containsExactly("listed PM601826", "listed PM3128888"); // two in the band, none for Iran
    }

    @Test
    void aCategoryThatIsFullIsLeftAloneAndRefreshesAreFiveMinutesApart() {
        PolymarketCatalog catalog = catalog(config(3, 0, true));
        catalog.refresh(NOW);
        gamma.markets.requests.clear();

        catalog.refresh(NOW + 300_000); // full: no listing request at all

        assertThat(gamma.markets.requests).isEmpty();
        assertThat(registry.log).hasSize(3);
    }

    @Test
    void whenAMarketClosesTheCategoryIsToppedUpAtTheNextRefresh() {
        PolymarketCatalog catalog = catalog(config(3, 0, true));
        catalog.refresh(NOW);
        registry.close("PM601826", PolymarketCatalog.REASON_CLOSED, NOW + 1_000); // Flavio: the Brazilian event is free again

        catalog.refresh(NOW + 300_000);

        // Lula (0.375, the same event as Flavio) is the next one in the band
        assertThat(activeIds(Category.POLITICS)).containsExactlyInAnyOrder("PM665374", "PM3128888", "PM601819");
        assertThat(registry.log).contains("listed PM601819");
    }

    @Test
    void aMarketEndingWithinThirtyMinutesIsNoCandidateOneEndingLaterIs() {
        ObjectNode soon = (ObjectNode) politics.get("markets").get(0).deepCopy();
        soon.put("endDate", Instant.ofEpochMilli(NOW + 29 * 60_000).toString());
        ObjectNode later = (ObjectNode) politics.get("markets").get(3).deepCopy();
        later.put("endDate", Instant.ofEpochMilli(NOW + 31 * 60_000).toString());
        gamma.markets.handler = call -> wrap(soon, later);

        catalog(config(2, 0, true)).refresh(NOW);

        assertThat(registry.log).containsExactly("listed PM601826"); // 29 min: no; 31 min: yes
    }

    @Test
    void marketsThatCannotTradeOrAreNotBinaryAreDropped() {
        ObjectNode notAccepting = copy(politics, 0);
        notAccepting.put("acceptingOrders", false);
        ObjectNode noBook = copy(politics, 3);
        noBook.put("enableOrderBook", false);
        ObjectNode closed = copy(politics, 6);
        closed.put("closed", true);
        ObjectNode threeOutcomes = copy(politics, 4);
        threeOutcomes.put("outcomes", "[\"A\", \"B\", \"C\"]");
        ObjectNode brokenOutcomes = copy(politics, 1);
        brokenOutcomes.put("outcomes", "not json at all");
        ObjectNode noTokens = copy(politics, 2);
        noTokens.remove("clobTokenIds");
        gamma.markets.handler = call -> wrap(notAccepting, noBook, closed, threeOutcomes, brokenOutcomes, noTokens);

        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(registry.log).isEmpty();
    }

    @Test
    void gammasOddShapesAreReadWithoutFailingTheListing() {
        // id as a number, outcomes and tokens as real arrays, no image, no tick, a date-only endDate
        ObjectNode odd = copy(politics, 0);
        odd.put("id", 777001);
        JsonNode tokens = Fixtures.parse(odd.get("clobTokenIds").asText());
        odd.set("clobTokenIds", tokens);
        odd.set("outcomes", Fixtures.parse("[\"Yes\",\"No\"]"));
        odd.remove("image");
        odd.remove("icon");
        ((ObjectNode) odd.get("events").get(0)).remove("image");
        ((ObjectNode) odd.get("events").get(0)).remove("icon");
        odd.remove("orderPriceMinTickSize");
        odd.put("endDate", "2027-01-01");
        book.set(tokens.get(0).asText(), "0.30", "0.29", "0.31").install(clob);
        gamma.markets.handler = call -> wrap(odd);

        catalog(config(1, 0, true)).refresh(NOW);

        Market listed = market("PM777001");
        assertThat(listed.iconUrl()).isNull();
        assertThat(listed.endsAtMs()).isEqualTo(ms("2027-01-01T00:00:00Z"));
        assertThat(Fixtures.parse(listed.sourceMeta()).has("tickSize")).isFalse();
    }

    // -------------------------------------------------------------------------------------- sports

    @Test
    void gamesInPlayComeFirstOneMarketPerGameWithTheTeamsAbbreviationAsTicker() {
        catalog(config(0, 3, true)).refresh(NOW);

        // Denmark-Portugal: Portugal 0.715 (moneyline, highest volume) wins the game's slot; Wales-Norway: Norway;
        // CS2: the BO3 moneyline, not the map markets or the totals
        assertThat(registry.log).containsExactly("listed PM4678642", "listed PM4678632", "listed PM4642062");
        assertThat(market("PM4678642").symbol()).isEqualTo("PRT");
        assertThat(market("PM4678632").symbol()).isEqualTo("NOR");
        assertThat(market("PM4642062").symbol()).isEqualTo("FAZE");
        assertThat(gamma.events.requests).hasSize(1);
        FakeApis.Gamma.EventsCall call = gamma.events.requests.get(0);
        assertThat(call.tagId()).isEqualTo("1");
        assertThat(call.live()).isTrue();
        assertThat(call.closed()).isFalse();
        assertThat(call.order()).isEqualTo("volume24hr");
        assertThat(call.ascending()).isFalse();
        assertThat(gamma.markets.requests.get(0).tagId()).isEqualTo("1");
    }

    @Test
    void aGameMarketHasNoEndBecauseItsEndDateIsTheKickOff() {
        catalog(config(0, 3, true)).refresh(NOW);

        Market portugal = market("PM4678642");
        // Gamma's endDate is 18:45, the kick-off, an hour before this listing: not an end
        assertThat(portugal.endsAtMs()).isNull();
        // the team that wins, then the league (L2) — it was "Yes · Denmark vs. Portugal"
        assertThat(portugal.subtitle()).isEqualTo("Portugal to win · UEFA Nations League");
        assertThat(portugal.category()).isEqualTo(Category.SPORTS);
        JsonNode meta = Fixtures.parse(portugal.sourceMeta());
        assertThat(meta.get("gameStartTime").asText()).isEqualTo("2026-10-01 18:45:00+00");
        // G3: the kick-off as a NUMBER too (epoch ms) — the API reads this key for MarketDto.eventStartsAtMs
        assertThat(meta.get("gameStartMs").isNumber()).isTrue();
        assertThat(meta.get("gameStartMs").asLong()).isEqualTo(ms("2026-10-01T18:45:00Z"));
        assertThat(meta.get("gameStartMs").asLong()).isEqualTo(1_790_880_300_000L);
        assertThat(meta.get("sportsMarketType").asText()).isEqualTo("moneyline");
        assertThat(meta.get("gameId").asText()).isEqualTo("90108425");
        assertThat(meta.get("eventKey").asText()).isEqualTo("game:90108425");
    }

    @Test
    void aGameInPlayIsNotRejectedByTheRulesThatReadEndDateAndIsNotClosedByThem() {
        catalog(config(0, 3, true)).refresh(NOW);

        Market portugal = market("PM4678642");
        // the 5-minute rule would have closed it at once (endDate 18:45 is in the past)
        assertThat(catalog(config(0, 3, true)).closeReason(portugal, NOW)).isNull();
        assertThat(catalog(config(0, 3, true)).watch(NOW)).isZero();
        assertThat(portugal.isActive()).isTrue();
    }

    @Test
    void withTheInPlayExceptionOffTheRulesApplyToEveryMarketAsWritten() {
        catalog(config(0, 3, false)).refresh(NOW);

        // no request to the live listing; the soccer games' endDate is in the past: not candidates
        assertThat(gamma.events.requests).isEmpty();
        assertThat(registry.log).containsExactly("listed PM4642062", "listed PM3760412", "listed PM5073589");
        assertThat(market("PM4642062").endsAtMs()).isEqualTo(ms("2026-10-02T01:20:00Z")); // endsAtMs = endDate
    }

    @Test
    void whenNothingIsInPlayTheVolumeListFillsTheCategoryWithMoneylineFirst() {
        gamma.events.handler = call -> Fixtures.parse("{\"events\":[]}");
        gamma.markets.handler = call -> notLive(sports);

        catalog(config(0, 3, true)).refresh(NOW);

        // the three soccer games (end 18:45) fail the 30-minute rule now that they are not live; the next
        // moneylines by volume pass: CS2 (0.72), Steelers-Browns (0.575), Phillies-Braves (0.495)
        assertThat(registry.log).containsExactly("listed PM4642062", "listed PM3760412", "listed PM5073589");
        assertThat(market("PM4642062").symbol()).isEqualTo("FAZE");
        assertThat(market("PM3760412").symbol()).isEqualTo("STEELERS");
        assertThat(market("PM5073589").symbol()).isEqualTo("PHILLIES");
        assertThat(market("PM3760412").subtitle()).isEqualTo("Steelers to win · NFL 2026");
        assertThat(market("PM5073589").subtitle()).isEqualTo("Philadelphia Phillies to win · MLB");
        assertThat(market("PM3760412").endsAtMs()).isNull(); // a game: no end of its own
    }

    @Test
    void onlyMoneylineMarketsAreCandidatesOfSports() {
        List<GammaMarket> candidates = catalog(config(0, 3, false)).candidates(Category.SPORTS, NOW);

        // the listing has a totals market ("Germany vs. Serbia: O/U 3.5") and a futures market with no type at all
        // ("Will Shakhtar Donetsk win the 2026-27 UEFA Champions League"): neither is a candidate
        assertThat(candidates).isNotEmpty().allSatisfy(m -> assertThat(m.sportsMarketType()).isEqualTo("moneyline"));
        assertThat(candidates).extracting(GammaMarket::id).doesNotContain("4679591", "2772194");
    }

    @Test
    void theSameGameIsOneEventEvenWhenItsMarketsComeFromDifferentGammaEvents() {
        // Germany-Serbia: the main event and a second event ("More Markets") share the game id 90108423; two moneyline
        // markets of it, one in each event, are one game and take one place
        ObjectNode germany = copy(sports, 9); // "Will Germany win" in the main event
        ObjectNode serbia = copy(sports, 9);
        serbia.put("id", "4678614");
        serbia.put("question", "Will Serbia win on 2026-10-01?");
        serbia.put("groupItemTitle", "Serbia");
        serbia.put("clobTokenIds", "[\"tokSerbiaYes\", \"tokSerbiaNo\"]");
        ((ObjectNode) serbia.get("events").get(0)).put("id", "1042999");
        assertThat(germany.get("events").get(0).get("gameId").asText())
                .isEqualTo(serbia.get("events").get(0).get("gameId").asText());
        assertThat(germany.get("events").get(0).get("id").asText())
                .isNotEqualTo(serbia.get("events").get(0).get("id").asText());
        gamma.markets.handler = call -> wrap(serbia, germany);
        gamma.events.handler = call -> Fixtures.parse("{\"events\":[]}");
        book.set("tokSerbiaYes", "0.345", "0.34", "0.35")
                .set(token(sports.get("markets"), "4678613"), "0.62", "0.61", "0.63").install(clob); // both in the band

        catalog(config(0, 3, true)).refresh(NOW);

        assertThat(registry.log).containsExactly("listed PM4678614"); // the first of the two takes the game's only place
    }

    // ------------------------------------------------------ sports: only who wins, and a game cooled down (S1)

    private final JsonNode mlbLive = Fixtures.json("polymarket/gamma_events_live_mlb.json");

    private static final String NO_MARKETS = "{\"markets\":[]}";
    private static final long SIX_HOURS = 6 * 3_600_000L;

    /** The live-games fixture reduced to the events with these ids. */
    private static JsonNode eventsOf(JsonNode live, String... eventIds) {
        ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ArrayNode array = out.putArray("events");
        for (JsonNode event : live.get("events")) {
            if (List.of(eventIds).contains(event.get("id").asText())) {
                array.add(event);
            }
        }
        return out;
    }

    @Test
    void aTotalsMarketOfAGameWhoseMoneylineWasClosedIsNotListedInItsPlace() {
        // seen live on 2026-10-01: Phillies-Braves in play; the moneyline (PHI) was closed at 97%, and the category was
        // topped up with "Philadelphia Phillies vs. Atlanta Braves: O/U 8.5" under the same ticker PHI
        gamma.events.handler = call -> mlbLive;
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        book = ClobBook.of(mlbLive.get("events"));
        book.install(clob);
        PolymarketCatalog catalog = catalog(config(0, 3, true));

        catalog.refresh(NOW);

        // the game's moneyline takes the game's place; its totals, spread and first-inning markets are not candidates
        assertThat(registry.log).containsExactly("listed PM5073589");
        assertThat(market("PM5073589").symbol()).isEqualTo("PHI");
        assertThat(market("PM5073589").subtitle()).isEqualTo("Philadelphia Phillies to win · MLB");

        store.append("polymarket", NOW + 1_000, Map.of("PM5073589", bd("0.975"))); // the game is decided
        assertThat(catalog.watch(NOW + 2_000)).isEqualTo(1);
        catalog.refresh(NOW + 300_000L);

        assertThat(registry.log).containsExactly("listed PM5073589", "closed PM5073589: " + PolymarketCatalog.REASON_PRICE);
        assertThat(activeIds(Category.SPORTS)).as("fewer than the 3 configured is fine").isEmpty();
        // the O/U 8.5 market passes every other rule: it is the type alone that keeps it out
        JsonNode eventNode = mlbLive.get("events").get(0);
        GammaMarket totals = GammaMarket.parseAll(eventNode.get("markets"), GammaEvent.parse(eventNode)).stream()
                .filter(m -> m.id().equals("5165511")).findFirst().orElseThrow();
        assertThat(totals.question()).isEqualTo("Philadelphia Phillies vs. Atlanta Braves: O/U 8.5");
        assertThat(totals.sportsMarketType()).isEqualTo("totals");
        assertThat(catalog.passesStaticRules(totals, NOW + 300_000L)).isTrue();
        assertThat(catalog.readClob(List.of(totals.token())).get(totals.token()).acceptable()).isTrue();
        assertThat(catalog.candidates(Category.SPORTS, NOW + 300_000L)).isEmpty();
    }

    @Test
    void theTypeRuleDoesNotDependOnTheGameCooldown() {
        CatalogConfig noCooldown = new CatalogConfig(true, false, List.of("BTC"), List.of(), List.of(), List.of(), true,
                0, 3, 10, true, 0L);
        gamma.events.handler = call -> mlbLive;
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        book = ClobBook.of(mlbLive.get("events"));
        book.install(clob);
        registry.add(activePm("PM5073589", "x", Category.SPORTS, null, null).closed(NOW, "test"));

        catalog(noCooldown).refresh(NOW + 1_000L);

        assertThat(registry.log).isEmpty(); // the moneyline is known and closed; the rest of the game says nothing about who wins
    }

    @Test
    void aMarketThatIsNotTheGamesWinnerIsNeverASportsCandidate() {
        // the CS2 event with its BO3 moneyline taken out: what is left is two "Map N Winner" markets (child_moneyline,
        // both in the band) and the total "Games Total: O/U 2.5"
        ObjectNode event = (ObjectNode) eventsOf(liveEvents, "1038938").get("events").get(0).deepCopy();
        ArrayNode markets = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode m : event.get("markets")) {
            if (!"moneyline".equals(m.get("sportsMarketType").asText())) {
                markets.add(m);
            }
        }
        assertThat(markets).extracting(m -> m.get("sportsMarketType").asText())
                .containsExactly("child_moneyline", "child_moneyline", "totals");
        event.set("markets", markets);
        ObjectNode live = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        live.putArray("events").add(event);
        gamma.events.handler = call -> live;
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);

        catalog(config(0, 3, true)).refresh(NOW);

        assertThat(registry.log).isEmpty();
    }

    @Test
    void aSportsMarketWithoutAMarketTypeIsNotACandidateAndAPoliticsMarketDoesNotNeedOne() {
        // a futures market of the sports listing has no sportsMarketType; politics markets never have one
        ObjectNode shakhtar = copy(sports, 6);
        assertThat(shakhtar.has("sportsMarketType")).isFalse();
        book.set(token(sports.get("markets"), "2772194"), "0.5", "0.49", "0.51").install(clob); // in the band, a tight book
        gamma.markets.handler = call -> "1".equals(call.tagId()) ? wrap(shakhtar) : politics;
        gamma.events.handler = call -> Fixtures.parse("{\"events\":[]}");

        catalog(config(3, 3, true)).refresh(NOW);

        assertThat(activeIds(Category.SPORTS)).isEmpty();
        assertThat(activeIds(Category.POLITICS)).hasSize(3);
        assertThat(GammaMarket.parseAll(politics.get("markets"), null)).allSatisfy(
                m -> assertThat(m.sportsMarketType()).isNull());
    }

    @Test
    void afterAMarketOfAGameWasClosedNoMarketOfThatGameIsListedForSixHours() {
        // Denmark-Portugal has three moneyline markets: Portugal 0.715, the draw 0.20, Denmark 0.085 (out of the band)
        gamma.events.handler = call -> eventsOf(liveEvents, "1042358");
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        PolymarketCatalog catalog = catalog(config(0, 1, true));
        catalog.refresh(NOW);
        assertThat(registry.log).containsExactly("listed PM4678642"); // Portugal, the most traded of the game

        store.append("polymarket", NOW + 1_000, Map.of("PM4678642", bd("0.98")));
        assertThat(catalog.watch(NOW + 2_000)).isEqualTo(1); // closed by the price rule at NOW + 2 s
        long closedAt = NOW + 2_000;

        catalog.refresh(NOW + 300_000L);
        assertThat(activeIds(Category.SPORTS)).as("5 minutes after: the draw of the same game is in the band, and waits")
                .isEmpty();
        catalog.refresh(closedAt + SIX_HOURS - 1);
        assertThat(activeIds(Category.SPORTS)).as("one millisecond short of 6 hours").isEmpty();
        catalog.refresh(closedAt + SIX_HOURS);

        assertThat(activeIds(Category.SPORTS)).as("6 hours after the close").containsExactly("PM4678640");
        assertThat(market("PM4678640").symbol()).isEqualTo("DRAW");
    }

    @Test
    void theCooldownIsKeptInTheRegistryAndSurvivesARestartOfTheCatalog() {
        gamma.events.handler = call -> eventsOf(liveEvents, "1042358");
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        catalog(config(0, 1, true)).refresh(NOW);
        registry.close("PM4678642", PolymarketCatalog.REASON_ABSENT, NOW + 1_000L); // any reason, by anyone

        PolymarketCatalog afterRestart = catalog(config(0, 1, true)); // nothing in memory
        afterRestart.refresh(NOW + 3 * 3_600_000L);

        assertThat(activeIds(Category.SPORTS)).isEmpty();
        assertThat(afterRestart.candidates(Category.SPORTS, NOW + 3 * 3_600_000L)).isEmpty();
    }

    @Test
    void anotherGameIsNotHeldBackByTheCooldownOfTheOne() {
        // the two soccer games in play, one place: Denmark-Portugal's Portugal is listed and closed, the draw of that game
        // waits, and Wales-Norway's Norway (0.495) takes the place
        gamma.events.handler = call -> eventsOf(liveEvents, "1042358", "1042356");
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        PolymarketCatalog catalog = catalog(config(0, 1, true));
        catalog.refresh(NOW);
        registry.close("PM4678642", PolymarketCatalog.REASON_PRICE, NOW + 1_000L);

        catalog.refresh(NOW + 300_000L);

        assertThat(activeIds(Category.SPORTS)).containsExactly("PM4678632"); // Norway; not the draw of the first game
    }

    @Test
    void aCooldownOfZeroTurnsTheRuleOff() {
        CatalogConfig off = new CatalogConfig(true, false, List.of("BTC"), List.of(), List.of(), List.of(), true, 0, 1, 10,
                true, 0L);
        gamma.events.handler = call -> eventsOf(liveEvents, "1042358");
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        catalog(off).refresh(NOW);
        registry.close("PM4678642", PolymarketCatalog.REASON_PRICE, NOW + 1_000L);

        catalog(off).refresh(NOW + 2_000L);

        assertThat(activeIds(Category.SPORTS)).containsExactly("PM4678640"); // the draw, at once
    }

    @Test
    void aMarketClosedLongAgoOrOfNoGameStartsNoCooldown() {
        gamma.events.handler = call -> eventsOf(liveEvents, "1042358");
        gamma.markets.handler = call -> Fixtures.parse(NO_MARKETS);
        // a market of the game closed 7 hours ago: the cooldown is over; one of another game (and one with no game at
        // all) closed a minute ago: not this game's business
        String meta = "{\"gameId\":\"90108425\"}";
        registry.add(activePm("PM1", "t1", Category.SPORTS, null, meta).closed(NOW - 7 * 3_600_000L, "test"));
        registry.add(activePm("PM2", "t2", Category.SPORTS, null, "{\"gameId\":\"555\"}").closed(NOW - 60_000L, "test"));
        registry.add(activePm("PM3", "t3", Category.POLITICS, null, "{\"eventKey\":\"event:90108425\"}")
                .closed(NOW - 60_000L, "test"));

        catalog(config(0, 1, true)).refresh(NOW);

        assertThat(activeIds(Category.SPORTS)).containsExactly("PM4678642"); // Portugal, as if nothing had closed
    }

    // ---------------------------------------------------------------------------------- closing

    private Market activePm(String id, String token, String category, Long endsAtMs, String sourceMeta) {
        return new Market(id, MarketKind.ODDS, category, "PM", "Will it?", "Yes", null, "polymarket", token, sourceMeta,
                10, Market.ODDS_LOT_EXP, 9, MarketStatus.ACTIVE, 0L, null, null, endsAtMs);
    }

    /** Three successful answers in a row that lack the token: the first at {@code firstAt}, the last at {@code lastAt}. */
    private static void missingRun(PolymarketLiveState state, String id, long firstAt, long lastAt) {
        state.reportMissing(id, firstAt);
        state.reportMissing(id, (firstAt + lastAt) / 2);
        state.reportMissing(id, lastAt);
    }

    @Test
    void aTokenReportedMissingByOneAnswerAndThenNothingNeverClosesTheMarketForGood() {
        // F7: a partial /midpoints answer, then a CLOB that times out for a minute: the 60 s of wall time alone
        // closed the market, and a closed id is never listed again
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        state.reportMissing("PM1", NOW); // the only successful answer; every poll after it failed

        assertThat(catalog.closeReason(m, NOW + 60_000)).isNull();
        assertThat(catalog.closeReason(m, NOW + 3_600_000L)).isNull();
        assertThat(catalog.watch(NOW + 3_600_000L)).isZero();
        assertThat(registry.byId("PM1").orElseThrow().isActive()).isTrue();
    }

    @Test
    void twoAnswersAreNotEnoughEvenWhenTheNewestIsRecentAndAMinuteHasPassed() {
        // the count and the age of the newest answer are two separate conditions: this one isolates the count
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        state.reportMissing("PM1", NOW);
        state.reportMissing("PM1", NOW + 58_000);
        assertThat(state.missingAnswers("PM1")).hasValue(2);
        assertThat(catalog.closeReason(m, NOW + 60_000)).as("two answers, a minute, the newest 2 s old").isNull();

        state.reportMissing("PM1", NOW + 59_000);
        assertThat(state.missingAnswers("PM1")).hasValue(3);
        assertThat(catalog.closeReason(m, NOW + 60_000)).isEqualTo(PolymarketCatalog.REASON_ABSENT);
    }

    @Test
    void theNewestAnswerMustBeRecentAndTwoAnswersAreNotEnough() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        state.reportMissing("PM1", NOW);
        state.reportMissing("PM1", NOW + 3_000);
        assertThat(catalog.closeReason(m, NOW + 60_000)).as("two answers, the newest 57 s old").isNull();

        state.reportMissing("PM1", NOW + 6_000);
        assertThat(state.missingAnswers("PM1")).hasValue(3);
        // three answers and a minute since the first, but the newest is 54 s old: nothing has said so since
        assertThat(catalog.closeReason(m, NOW + 60_000)).isNull();

        // the limit is 15 s (written out: a test that adds the constant to itself would follow it wherever it goes)
        assertThat(PolymarketCatalog.TOKEN_ABSENT_EVIDENCE_MAX_AGE_MS).isEqualTo(15_000L);
        state.reportMissing("PM1", NOW + 45_000);
        assertThat(catalog.closeReason(m, NOW + 60_001)).as("the newest answer 15 s and 1 ms old").isNull();
        assertThat(catalog.closeReason(m, NOW + 60_000)).as("the newest answer 15 s old")
                .isEqualTo(PolymarketCatalog.REASON_ABSENT);
    }

    @Test
    void aFailedPollOrAnAnswerWithTheTokenStartsTheCountAgain() throws Exception {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        Market other = registry.add(activePm("PM2", "tok2", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();
        AtomicLong clock = new AtomicLong(NOW);
        crowdvsmarket.server.prices.PolymarketPriceSource source = crowdvsmarket.server.prices.PipelineTestSupport
                .polymarket(clob, new FakeApis.Data(), state, clock::get);
        crowdvsmarket.server.prices.PriceSource.Feed feed = source.feeds().get(0);
        List<Market> markets = List.of(m, other);

        // PM1's token is missing from answers that do have PM2's
        clob.midpoints.handler = body -> Fixtures.parse("{\"tok2\":\"0.5\"}");
        for (int i = 0; i < 3; i++) {
            source.poll(feed, markets);
            clock.addAndGet(3_000);
        }
        assertThat(state.missingAnswers("PM1")).hasValue(3);

        // the CLOB breaks: the poll throws, and what the answers before it said no longer stands
        clob.midpoints.handler = body -> {
            throw new IllegalStateException("HTTP 502");
        };
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> source.poll(feed, markets))
                .isInstanceOf(IllegalStateException.class);
        assertThat(state.missingSince("PM1")).isEmpty();
        assertThat(state.missingSince("PM2")).isEmpty();

        // an answer that has none of the tokens is an outage, not a mass closing — and it clears the runs too
        clob.midpoints.handler = body -> Fixtures.parse("{\"tok2\":\"0.5\"}");
        source.poll(feed, markets);
        assertThat(state.missingAnswers("PM1")).hasValue(1);
        clob.midpoints.handler = body -> Fixtures.parse("{}");
        source.poll(feed, markets);
        assertThat(state.missingSince("PM1")).isEmpty();

        // the token is back for one answer: the run is over
        clob.midpoints.handler = body -> Fixtures.parse("{\"tok2\":\"0.5\"}");
        source.poll(feed, markets);
        source.poll(feed, markets);
        clob.midpoints.handler = body -> Fixtures.parse("{\"tok1\":\"0.4\",\"tok2\":\"0.5\"}");
        source.poll(feed, markets);
        assertThat(state.missingSince("PM1")).isEmpty();
        assertThat(catalog.watch(clock.addAndGet(120_000))).isZero();
    }

    @Test
    void aTokenAbsentFromMidpointsForSixtySecondsClosesTheMarket() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        missingRun(state, "PM1", NOW, NOW + 57_000); // the polls of 0 s, 28.5 s and 57 s all lacked the token
        assertThat(catalog.closeReason(m, NOW + 59_999)).isNull();
        assertThat(catalog.closeReason(m, NOW + 60_000)).isEqualTo(PolymarketCatalog.REASON_ABSENT);
        assertThat(catalog.watch(NOW + 60_000)).isEqualTo(1);

        assertThat(registry.closeReasons).containsEntry("PM1", PolymarketCatalog.REASON_ABSENT);
        assertThat(m.isActive()).isTrue(); // the local copy is a value; the registry holds the closed one
        assertThat(registry.byId("PM1").orElseThrow().status()).isEqualTo(MarketStatus.CLOSED);
        assertThat(state.missingSince("PM1")).isEmpty(); // forgotten with the market
    }

    @Test
    void aTokenThatCameBackBeforeSixtySecondsIsNotClosed() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        state.reportMissing("PM1", NOW);
        state.reportPresent("PM1");

        assertThat(catalog().closeReason(m, NOW + 120_000)).isNull();
    }

    @Test
    void aMidpointOutsideThreeAndNinetySevenPercentClosesTheMarket() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        store.append("polymarket", NOW, Map.of("PM1", bd("0.0299")));
        assertThat(catalog.closeReason(m, NOW + 1_000)).isEqualTo(PolymarketCatalog.REASON_PRICE);
        store.append("polymarket", NOW + 3_000, Map.of("PM1", bd("0.03")));
        assertThat(catalog.closeReason(m, NOW + 4_000)).isNull(); // the limits themselves are inside
        store.append("polymarket", NOW + 6_000, Map.of("PM1", bd("0.97")));
        assertThat(catalog.closeReason(m, NOW + 7_000)).isNull();
        store.append("polymarket", NOW + 9_000, Map.of("PM1", bd("0.9701")));
        assertThat(catalog.closeReason(m, NOW + 10_000)).isEqualTo(PolymarketCatalog.REASON_PRICE);
    }

    @Test
    void aStaleTickDoesNotCloseAMarket() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        store.append("polymarket", NOW, Map.of("PM1", bd("0.0005")));

        assertThat(catalog().closeReason(m, NOW + 31_000)).isNull(); // older than the 30 s of the feed: no judgement
    }

    @Test
    void lessThanFiveMinutesToTheEndClosesTheMarketUnlessItHasNoEnd() {
        Market ending = registry.add(activePm("PM1", "tok1", Category.POLITICS, NOW + 300_000, null));
        Market open = registry.add(activePm("PM2", "tok2", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        assertThat(catalog.closeReason(ending, NOW)).isNull(); // exactly 5 minutes
        assertThat(catalog.closeReason(ending, NOW + 1)).isEqualTo(PolymarketCatalog.REASON_ENDING);
        assertThat(catalog.closeReason(open, NOW + 1_000_000_000L)).isNull();
    }

    @Test
    void watchClosesEveryMarketThatMustCloseAndNothingElse() {
        registry.add(activePm("PM1", "tok1", Category.POLITICS, NOW + 60_000, null));
        registry.add(activePm("PM2", "tok2", Category.SPORTS, null, null));
        store.append("polymarket", NOW, Map.of("PM2", bd("0.5")));

        assertThat(catalog().watch(NOW)).isEqualTo(1);

        assertThat(registry.closeReasons).containsOnlyKeys("PM1");
        assertThat(registry.byId("BTC").orElseThrow().isActive()).isTrue(); // only Polymarket markets are judged
    }

    @Test
    void aCloseAsksForAnEarlyTopUpInsteadOfWaitingFiveMinutes() {
        PolymarketCatalog catalog = catalog(config(3, 0, true));
        catalog.refreshJob(); // the first refresh lists three
        gamma.byTokens.requests.clear();
        gamma.markets.requests.clear();
        catalog.refreshJob(); // 5 minutes have not passed and nothing was asked for: no request at all
        assertThat(gamma.byTokens.requests).isEmpty();
        assertThat(gamma.markets.requests).isEmpty();

        registry.add(activePm("PM1", "tok1", Category.POLITICS, NOW + 60_000, null)); // about to end
        catalog.watch(NOW); // closes it
        catalog.refreshJob(); // asked for: refreshes now, not in five minutes

        assertThat(registry.closeReasons).containsKey("PM1");
        assertThat(gamma.byTokens.requests).isNotEmpty(); // the first step of every refresh ran again
    }

    @Test
    void everyCloseReasonIsAPlainSentenceTheWayItIsShownToThePlayers() {
        // G2: MarketDto.closeReason is shown as it stands, and GameService puts "market closed: " in front of it
        // when it refunds the bets of the market's rounds
        assertThat(PolymarketCatalog.REASON_CLOSED).isEqualTo("The source market ended");
        assertThat(PolymarketCatalog.REASON_ABSENT).isEqualTo("The source market stopped trading");
        assertThat(PolymarketCatalog.REASON_PRICE).isEqualTo("Price left the 3%–97% range");
        assertThat(PolymarketCatalog.REASON_ENDING).isEqualTo("The source market ends in less than 5 minutes");
        assertThat(List.of(PolymarketCatalog.REASON_CLOSED, PolymarketCatalog.REASON_ABSENT,
                PolymarketCatalog.REASON_PRICE, PolymarketCatalog.REASON_ENDING)).allSatisfy(reason -> {
                    assertThat(reason).matches("[A-Z][^\\[\\]()<>{}_=]*[^.]"); // a sentence: capital first, no code, no full stop
                    assertThat(reason).doesNotContain("token", "midpoint", "Gamma", "CLOB", "marketId");
                });
    }

    // ------------------------------------------------------------------ the first minutes of a market (G1)

    @Test
    void everyMarketItListsIsHandedToTheSeederForItsRecentBuckets() {
        FakeApis.Data data = new FakeApis.Data();
        // 60-second buckets over whatever range is asked: 0.5 flat
        data.history.handler = call -> {
            StringBuilder sb = new StringBuilder("{\"data\":[");
            for (long s = call.start() / 60 * 60; s <= call.end(); s += 60) {
                sb.append(s == call.start() / 60 * 60 ? "" : ",").append("{\"timestamp\":").append(s)
                        .append(",\"price\":0.5,\"resolution_seconds\":60}");
            }
            return Fixtures.parse(sb.append("]}").toString());
        };
        crowdvsmarket.server.prices.HistorySeeder seeder = crowdvsmarket.server.prices.PipelineTestSupport.seeder(
                List.of(crowdvsmarket.server.prices.PipelineTestSupport.polymarket(clob, data, state, now::get)), store,
                now::get);

        new PolymarketCatalog(registry, config(2, 0, true), PricesConfig.defaults(), () -> gamma, () -> clob, state,
                store, seeder, now::get).refresh(NOW);

        assertThat(activeIds(Category.POLITICS)).hasSize(2);
        assertThat(data.history.requests).extracting(FakeApis.Data.Call::tokenId)
                .containsExactlyInAnyOrderElementsOf(activeIds(Category.POLITICS).stream().map(id -> market(id).sourceRef())
                        .toList());
        for (String id : activeIds(Category.POLITICS)) {
            assertThat(store.size(id)).as(id).isPositive();
            // a seeded point is no live tick: no quote before the first poll, and nothing after "now"
            assertThat(store.last(id)).as(id).isEmpty();
            List<crowdvsmarket.server.prices.PricePoint> seeded = store.series(id, 0, Long.MAX_VALUE);
            assertThat(seeded.get(seeded.size() - 1).atMs()).as(id).isLessThanOrEqualTo(NOW);
        }
    }

    // ---------------------------------------------------------------------- closed at the source

    @Test
    void refreshClosesWhatGammaReportsClosedAndTakesTheDayChangeOfTheOthers() {
        JsonNode byTokens = Fixtures.json("polymarket/gamma_markets_by_tokens.json");
        String openToken = token(byTokens.get("markets"), "4678642");
        String closedToken = token(byTokens.get("markets"), "5140154");
        registry.add(activePm("PM4678642", openToken, Category.SPORTS, null, null));
        registry.add(activePm("PM5140154", closedToken, Category.SPORTS, null, null));
        gamma.byTokens.handler = call -> lookupByTokens(byTokens, call);

        catalog(config(0, 2, true)).refreshActive(NOW);

        assertThat(registry.closeReasons).containsOnlyKeys("PM5140154");
        assertThat(registry.closeReasons.get("PM5140154")).isEqualTo(PolymarketCatalog.REASON_CLOSED);
        assertThat(state.oneDayChange("PM4678642")).isEqualTo(0.24);
        // two lookups: the markets that are not closed (their day change), then the closed ones
        assertThat(gamma.byTokens.requests).extracting(FakeApis.Gamma.TokensCall::closed).containsExactly(false, true);
        assertThat(gamma.byTokens.requests).allSatisfy(
                call -> assertThat(call.tokenIds()).containsExactlyInAnyOrder(openToken, closedToken));
    }

    @Test
    void aMarketThatClosedAtTheSourceIsFoundByTheLookupThatAsksForClosedOnes() {
        // measured 2026-10-02: GET /markets/keyset?clob_token_ids=… without closed (or with closed=false) leaves
        // closed markets out — a catalog that never asked closed=true would never see a market close
        JsonNode byTokens = Fixtures.json("polymarket/gamma_markets_by_tokens.json");
        registry.add(activePm("PM5140154", token(byTokens.get("markets"), "5140154"), Category.SPORTS, null, null));
        gamma.byTokens.handler = call -> call.closed() ? lookupByTokens(byTokens, call)
                : Fixtures.parse("{\"markets\":[]}");

        catalog(config(0, 2, true)).refreshActive(NOW);

        assertThat(registry.closeReasons).containsOnlyKeys("PM5140154");
        assertThat(gamma.byTokens.requests).extracting(FakeApis.Gamma.TokensCall::closed).contains(true);
    }

    @Test
    void aMarketGammaStillHasOpenIsNotClosedByTheLookups() {
        JsonNode byTokens = Fixtures.json("polymarket/gamma_markets_by_tokens.json");
        registry.add(activePm("PM4678642", token(byTokens.get("markets"), "4678642"), Category.SPORTS, null, null));
        gamma.byTokens.handler = call -> lookupByTokens(byTokens, call);

        catalog(config(0, 2, true)).refreshActive(NOW);

        assertThat(registry.closeReasons).isEmpty();
        assertThat(registry.byId("PM4678642").orElseThrow().isActive()).isTrue();
    }

    /**
     * What Gamma answers a lookup by token, per the 2026-10-02 measurement: the markets of the fixture
     * among the asked tokens, those that are closed only when {@code closed=true} is asked, the open
     * ones only when it is not.
     */
    private static JsonNode lookupByTokens(JsonNode fixture, FakeApis.Gamma.TokensCall call) {
        ArrayNode markets = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode m : fixture.get("markets")) {
            String token = Fixtures.parse(m.get("clobTokenIds").asText()).get(0).asText();
            if (call.tokenIds().contains(token) && m.path("closed").asBoolean(false) == call.closed()) {
                markets.add(m);
            }
        }
        ObjectNode answer = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        answer.set("markets", markets);
        return answer;
    }

    @Test
    void theListedMarketsOneDayChangeIsTakenFromTheListing() {
        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(state.oneDayChange("PM601826")).isEqualTo(0.021); // Flavio, from Gamma's oneDayPriceChange
        assertThat(state.oneDayChange("PM665374")).isNull(); // Iran has none
    }

    // ------------------------------------------------------------------------- failures, switches

    @Test
    void aFailingGammaOrClobFailsTheRefreshAndRetriesSoonNotInFiveMinutes() {
        PolymarketCatalog catalog = catalog(config(3, 0, true));
        gamma.markets.handler = call -> {
            throw new IllegalStateException("gamma is down");
        };

        assertThat(catalog.refresh(NOW)).isFalse();
        assertThat(registry.log).isEmpty();

        gamma.markets.handler = call -> politics;
        gamma.markets.requests.clear();
        catalog.refreshJob(); // the retry does not wait for the 5 minutes
        assertThat(gamma.markets.requests).isNotEmpty();
        assertThat(registry.log).hasSize(3);
    }

    @Test
    void aClobThatIsDownListsNothingAndIsRetried() {
        PolymarketCatalog catalog = catalog(config(3, 0, true));
        clob.midpoints.handler = body -> {
            throw new IllegalStateException("clob is down");
        };

        assertThat(catalog.refresh(NOW)).isFalse();
        assertThat(registry.log).isEmpty();
    }

    @Test
    void aRegistryThatRefusesOneListingDoesNotStopTheOthers() {
        FakeMarketRegistry picky = new FakeMarketRegistry() {
            @Override
            public Market list(NewMarket spec, long nowMs) {
                if (spec.id().equals("PM665374")) {
                    throw new IllegalStateException("constraint violated");
                }
                return super.list(spec, nowMs);
            }
        };
        registry = picky;

        catalog(config(3, 0, true)).refresh(NOW);

        assertThat(picky.log).containsExactly("listed PM601826", "listed PM3128888");
    }

    @Test
    void theJobsDoNothingWhenPolymarketIsDisabledOrNotLive() {
        CatalogConfig disabled = new CatalogConfig(true, false, List.of("BTC"), List.of(), List.of(), List.of(), false,
                3, 3, 10, true);
        CatalogConfig replay = new CatalogConfig(false, false, List.of("BTC"), List.of(), List.of(), List.of(), true, 3,
                3, 10, true);
        CatalogConfig synthetic = new CatalogConfig(false, true, List.of("BTC"), List.of(), List.of(), List.of(), true,
                3, 3, 10, true);
        registry.add(activePm("PM1", "tok1", Category.POLITICS, NOW + 1_000, null));

        for (CatalogConfig config : List.of(disabled, replay, synthetic)) {
            PolymarketCatalog catalog = catalog(config);
            catalog.refreshJob();
            catalog.watchJob();
        }

        assertThat(gamma.markets.requests).isEmpty();
        assertThat(gamma.events.requests).isEmpty();
        assertThat(registry.closeReasons).isEmpty(); // not even the market that is about to end
    }

    // ------------------------------------------------------------------------------------ helpers

    private static ObjectNode copy(JsonNode listing, int index) {
        JsonNode array = listing.has("markets") ? listing.get("markets") : listing;
        return (ObjectNode) array.get(index).deepCopy();
    }

    private static JsonNode wrap(ObjectNode... markets) {
        ObjectNode out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ArrayNode array = out.putArray("markets");
        for (ObjectNode m : markets) {
            array.add(m);
        }
        return out;
    }

    /** The sports listing with every event stub switched to "not live". */
    private static JsonNode notLive(JsonNode listing) {
        JsonNode copy = listing.deepCopy();
        for (JsonNode market : copy.get("markets")) {
            for (JsonNode event : market.path("events")) {
                ((ObjectNode) event).put("live", false);
            }
        }
        return copy;
    }
}
