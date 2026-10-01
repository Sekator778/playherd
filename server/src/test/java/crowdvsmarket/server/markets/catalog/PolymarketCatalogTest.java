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
        assertThat(iran.subtitle()).isEqualTo("Yes · Will the U.S. invade Iran before 2027?");
        assertThat(iran.symbol()).isEqualTo("US");
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
        assertThat(flavio.symbol()).isEqualTo("FLÁVIO");
        assertThat(flavio.subtitle()).isEqualTo("Yes · Brazil Presidential Election");
        assertThat(flavio.endsAtMs()).isEqualTo(ms("2026-10-05T03:59:00Z"));
        assertThat(market("PM3128888").symbol()).isEqualTo("US");
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
        assertThat(portugal.subtitle()).isEqualTo("Yes · Denmark vs. Portugal");
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
        assertThat(market("PM5073589").symbol()).isEqualTo("PHILADEL");
        assertThat(market("PM3760412").endsAtMs()).isNull(); // a game: no end of its own
    }

    @Test
    void moneylineMarketsAreTriedBeforeTheOtherTypes() {
        List<GammaMarket> candidates = catalog(config(0, 3, false)).candidates(Category.SPORTS, NOW);

        List<String> types = candidates.stream().map(m -> m.isMoneyline() ? "ml" : "other").toList();
        assertThat(types.lastIndexOf("ml")).isLessThan(types.indexOf("other"));
    }

    @Test
    void theSameGameIsOneEventEvenWhenItsMarketsComeFromDifferentGammaEvents() {
        // Germany-Serbia: the main event and "More Markets" share the game id 90108423 in the sports listing
        ObjectNode germany = copy(sports, 9); // "Will Germany win" in the main event
        ObjectNode totals = copy(sports, 7); // "O/U 3.5" in the More Markets event
        assertThat(germany.get("events").get(0).get("gameId").asText())
                .isEqualTo(totals.get("events").get(0).get("gameId").asText());
        assertThat(germany.get("events").get(0).get("id").asText())
                .isNotEqualTo(totals.get("events").get(0).get("id").asText());
        gamma.markets.handler = call -> wrap(totals, germany);
        gamma.events.handler = call -> Fixtures.parse("{\"events\":[]}");
        book.set(token(sports.get("markets"), "4679591"), "0.345", "0.34", "0.35")
                .set(token(sports.get("markets"), "4678613"), "0.62", "0.61", "0.63").install(clob); // Germany now in the band

        catalog(config(0, 3, true)).refresh(NOW);

        assertThat(registry.log).hasSize(1); // the first of the two takes the game's only slot
    }

    // ---------------------------------------------------------------------------------- closing

    private Market activePm(String id, String token, String category, Long endsAtMs, String sourceMeta) {
        return new Market(id, MarketKind.ODDS, category, "PM", "Will it?", "Yes", null, "polymarket", token, sourceMeta,
                10, Market.ODDS_LOT_EXP, 9, MarketStatus.ACTIVE, 0L, null, null, endsAtMs);
    }

    @Test
    void aTokenAbsentFromMidpointsForSixtySecondsClosesTheMarket() {
        Market m = registry.add(activePm("PM1", "tok1", Category.POLITICS, null, null));
        PolymarketCatalog catalog = catalog();

        state.reportMissing("PM1", NOW);
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
            assertThat(store.last(id).orElseThrow().atMs()).as(id).isLessThanOrEqualTo(NOW);
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
