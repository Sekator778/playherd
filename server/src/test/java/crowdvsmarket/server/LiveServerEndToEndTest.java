package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.server.domain.WinStreak;
import crowdvsmarket.server.testsupport.LiveServerProfile;
import crowdvsmarket.server.testsupport.LiveServerTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The whole server in real time, on the real price pipeline (docs/research/markets_architecture.md §3.2, §8):
 * {@code herd.mode=live}, {@code herd.prices.source=synthetic}, rounds of 6 and 12 seconds, the scheduler on,
 * no bots ({@link LiveServerProfile}). Nothing is scripted and nothing but the chain service is mocked.
 *
 * <p>After boot the synthetic catalog lists its PERP and ODDS markets and {@code markets} shows their prices;
 * a player bets on BTC and on an ODDS market in the betting rounds that are open; {@code myPositions} shows both
 * as {@code BETTING}, then as {@code RUNNING} with an entry price; both rounds settle from the pipeline's
 * prices; the player's balance plus what is in play is what they started with plus everything they won or lost;
 * {@code priceSeries} has ticks; the settled rounds and the profile history name the right market and the
 * real length of the round; the close of a round is the open of the next. Every wait is "until, at most":
 * the machine may be busy.
 */
@QuarkusTest
@TestProfile(LiveServerProfile.class)
class LiveServerEndToEndTest extends LiveServerTestBase {

    private static final String BTC = "BTC";
    private static final String ODDS = "PMSYN1";
    private static final BigDecimal STARTER_PACK = LedgerBook.STARTER_PACK.toMoney();

    @Test
    void theCatalogListsItsMarketsAndTheLobbyShowsTheirPrices() throws Exception {
        awaitMarketsListedWithPrices(LiveServerProfile.MARKET_IDS);

        JsonNode lobby = ok(null, "markets");

        assertThat(lobby.path("markets")).hasSize(5);
        List<String> ids = new ArrayList<>();
        lobby.path("markets").forEach(m -> ids.add(m.path("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(LiveServerProfile.MARKET_IDS);
        assertThat(ids.get(0)).as("BTC is always first: listed by the migration").isEqualTo(BTC);

        // the categories: crypto (BTC, ETH, DOGE), politics and sports (one synthetic ODDS market each)
        assertThat(lobby.path("categories")).hasSize(3);
        assertThat(lobby.path("categories").get(0).path("id").asText()).isEqualTo("crypto");
        assertThat(lobby.path("categories").get(0).path("marketCount").asInt()).isEqualTo(3);
        assertThat(lobby.path("categories").get(1).path("id").asText()).isEqualTo("politics");
        assertThat(lobby.path("categories").get(1).path("marketCount").asInt()).isEqualTo(1);
        assertThat(lobby.path("categories").get(2).path("id").asText()).isEqualTo("sports");
        assertThat(lobby.path("categories").get(2).path("marketCount").asInt()).isEqualTo(1);

        JsonNode btc = marketIn(lobby, BTC);
        assertThat(btc.path("kind").asText()).isEqualTo("PERP");
        assertThat(btc.path("source").asText()).isEqualTo("hyperliquid");
        assertThat(btc.path("oracleIndex").asInt()).isZero();
        assertThat(btc.path("priceDecimals").asInt()).isEqualTo(2);
        assertThat(btc.path("price").path("price").asText()).matches("\\d+\\.\\d{2}");

        JsonNode doge = marketIn(lobby, "DOGE");
        assertThat(doge.path("kind").asText()).isEqualTo("PERP");
        assertThat(doge.path("source").asText()).isEqualTo("synthetic");
        assertThat(doge.path("priceDecimals").asInt()).as("a price below 1 keeps its digits: lot exponent 5").isEqualTo(7);
        assertThat(doge.path("price").path("price").asText()).matches("0\\.\\d{7}");

        JsonNode politics = marketIn(lobby, ODDS);
        assertThat(politics.path("kind").asText()).isEqualTo("ODDS");
        assertThat(politics.path("category").asText()).isEqualTo("politics");
        assertThat(politics.path("maxLeverage").asInt()).isEqualTo(10);
        assertThat(politics.path("priceDecimals").asInt()).isEqualTo(6);
        assertThat(politics.path("price").path("price").asText()).matches("0\\.\\d{6}");
        assertThat(politics.path("subtitle").isNull()).as("an ODDS market says what its price is the chance of").isFalse();
        assertThat(marketIn(lobby, "PMSYN2").path("category").asText()).isEqualTo("sports");

        Set<Integer> indexes = new java.util.HashSet<>();
        for (JsonNode market : lobby.path("markets")) {
            indexes.add(market.path("oracleIndex").asInt());
            assertThat(market.path("series")).hasSize(2);
            assertThat(market.path("series").get(0).path("durationSec").asLong()).isEqualTo(6);
            assertThat(market.path("series").get(1).path("durationSec").asLong()).isEqualTo(12);
            // (each crowd account starts at 100 000; the other test of this class may already have moved some)
            assertThat(market.path("crowd").path("capital").asText()).matches("\\d+\\.\\d{2}");
            assertThat(market.path("crowd").path("roundsWon").asInt())
                    .isLessThanOrEqualTo(market.path("crowd").path("roundsTraded").asInt());
        }
        assertThat(indexes).as("every market has its own oracle index").hasSize(5);

        JsonNode info = ok(null, "serverInfo");
        assertThat(info.path("mode").asText()).isEqualTo("live");
        assertThat(info.path("priceSource").asText()).isEqualTo("synthetic");
        assertThat(info.path("markets").asInt()).isEqualTo(5);
        assertThat(info.path("bots").asInt()).isZero();
    }

    @Test
    void aPlayerBetsOnBtcAndAnOddsMarketAndBothRoundsSettleFromThePipelinesPrices() throws Exception {
        awaitMarketsListedWithPrices(LiveServerProfile.MARKET_IDS);
        Session me = signIn("e2e");
        BigDecimal starter = money(ok(me, "myAccount"), "balance");
        assertThat(starter).isEqualByComparingTo(STARTER_PACK);

        // --- bets in the betting rounds that are open (12 s series: seven seconds of betting left, at least) ---
        JsonNode btcSeries = awaitBettingWindow(BTC, "DAILY", 7_000L);
        JsonNode oddsSeries = awaitBettingWindow(ODDS, "DAILY", 5_000L);
        String btcRound = btcSeries.path("bettingRoundId").asText();
        String oddsRound = oddsSeries.path("bettingRoundId").asText();
        assertThat(oddsRound).startsWith(ODDS + "-1d-");
        assertThat(btcRound).as("BTC keeps the legacy round id").startsWith("1d-");
        ok(me, "placeBet", bet(btcRound, "UP", 5, "200.000"));
        ok(me, "placeBet", bet(oddsRound, "DOWN", 3, "150.500"));

        // --- myPositions: both BETTING, with no entry price yet; inPlay is the sum of their stakes ---
        List<JsonNode> pending = positions(me);
        assertThat(pending).hasSize(2);
        JsonNode btcBet = byMarket(pending, BTC);
        JsonNode oddsBet = byMarket(pending, ODDS);
        assertThat(btcBet.path("phase").asText()).isEqualTo("BETTING");
        assertThat(btcBet.path("roundId").asText()).isEqualTo(btcRound);
        assertThat(btcBet.path("interval").asText()).isEqualTo("DAILY");
        assertThat(btcBet.path("durationSec").asLong()).isEqualTo(12);
        assertThat(btcBet.path("direction").asText()).isEqualTo("UP");
        assertThat(btcBet.path("leverage").asInt()).isEqualTo(5);
        assertThat(btcBet.path("stake").asText()).isEqualTo("200.000");
        assertThat(btcBet.path("untilMs").asLong()).as("BETTING: when the round starts")
                .isEqualTo(btcSeries.path("bettingClosesAtMs").asLong());
        assertThat(oddsBet.path("phase").asText()).isEqualTo("BETTING");
        assertThat(oddsBet.path("symbol").asText()).isEqualTo("SYNP1");
        assertThat(oddsBet.path("stake").asText()).isEqualTo("150.500");
        for (JsonNode bet : pending) {
            assertThat(bet.path("entryPrice").isNull()).isTrue();
            assertThat(bet.path("liquidationPrice").isNull()).isTrue();
            assertThat(bet.path("size").isNull()).isTrue();
            assertThat(bet.path("liquidated").asBoolean()).isFalse();
        }
        JsonNode account = ok(me, "myAccount");
        assertThat(money(account, "inPlay")).isEqualByComparingTo("350.500");
        assertThat(money(account, "balance").add(money(account, "inPlay"))).isEqualByComparingTo(starter);

        // --- the lobby shows the live split of those rounds (it is cached for about a second: wait for it) ---
        await().atMost(Duration.ofSeconds(8)).pollInterval(POLL).untilAsserted(() -> {
            JsonNode btcNow = seriesWithBettingRound(market(BTC), btcRound);
            assertThat(btcNow).as("BTC's betting round is still the one bet on").isNotNull();
            assertThat(btcNow.path("participantCount").asInt()).isEqualTo(1);
            assertThat(btcNow.path("upStake").asText()).isEqualTo("200.000");
            assertThat(btcNow.path("downStake").asText()).isEqualTo("0.000");
            assertThat(btcNow.path("majorityDirection").asText()).isEqualTo("UP");
            JsonNode oddsNow = seriesWithBettingRound(market(ODDS), oddsRound);
            assertThat(oddsNow).isNotNull();
            assertThat(oddsNow.path("participantCount").asInt()).isEqualTo(1);
            assertThat(oddsNow.path("downStake").asText()).isEqualTo("150.500");
            assertThat(oddsNow.path("majorityDirection").asText()).isEqualTo("DOWN");
        });

        // --- the rounds start: both positions turn RUNNING with an entry price ---
        List<JsonNode> running = awaitRunning(me);
        JsonNode btcRunning = byMarket(running, BTC);
        JsonNode oddsRunning = byMarket(running, ODDS);
        assertThat(btcRunning.path("roundId").asText()).isEqualTo(btcRound);
        assertThat(btcRunning.path("entryPrice").asText()).matches("\\d+\\.\\d{2}");
        assertThat(oddsRunning.path("entryPrice").asText()).matches("0\\.\\d{6}");
        assertThat(new BigDecimal(btcRunning.path("liquidationPrice").asText()))
                .as("an UP position is liquidated below its entry")
                .isLessThan(new BigDecimal(btcRunning.path("entryPrice").asText()));
        assertThat(new BigDecimal(oddsRunning.path("liquidationPrice").asText()))
                .as("a DOWN position is liquidated above its entry")
                .isGreaterThan(new BigDecimal(oddsRunning.path("entryPrice").asText()));
        assertThat(oddsRunning.path("liquidationPrice").asText()).matches("0\\.\\d{6}");
        for (JsonNode position : running) {
            JsonNode series = position.path("marketId").asText().equals(BTC) ? btcSeries : oddsSeries;
            assertThat(new BigDecimal(position.path("size").asText())).isPositive();
            assertThat(position.path("liquidated").asBoolean()).isFalse();
            assertThat(position.path("untilMs").asLong()).as("RUNNING: when the round ends")
                    .isEqualTo(series.path("bettingClosesAtMs").asLong() + 12_000L);
        }
        assertThat(money(ok(me, "myAccount"), "inPlay")).as("a running position is still in play")
                .isEqualByComparingTo("350.500");

        // --- priceSeries: ticks of every kind of market ---
        assertPriceSeries(BTC, 2, "\\d+\\.\\d{2}");
        assertPriceSeries("DOGE", 7, "0\\.\\d{7}");
        assertPriceSeries(ODDS, 6, "0\\.\\d{6}");

        // --- both rounds settle from the pipeline's prices; the entry price shown while it ran was the open ---
        JsonNode btcSettled = awaitSettled(me, btcRound);
        JsonNode oddsSettled = awaitSettled(me, oddsRound);
        assertSettledRound(btcSettled, BTC, "BTC", me, 2, btcRunning);
        assertSettledRound(oddsSettled, ODDS, "SYNP1", me, 6, oddsRunning);

        // --- the books balance: balance + stakes in play == starter pack + Σ(payout − stake) over the history ---
        JsonNode account2 = ok(me, "myAccount");
        assertThat(money(account2, "inPlay")).as("nothing is left in play").isEqualByComparingTo("0.000");
        JsonNode profile = ok(me, "myProfile");
        BigDecimal net = BigDecimal.ZERO;
        for (JsonNode entry : profile.path("history")) {
            net = net.add(money(entry, "payout")).subtract(money(entry, "stake"));
        }
        assertThat(profile.path("history")).hasSize(2);
        assertThat(money(account2, "balance").add(money(account2, "inPlay")))
                .isEqualByComparingTo(starter.add(net));

        // --- the profile history and the results the player has not seen carry the right market and length ---
        for (JsonNode entry : profile.path("history")) {
            boolean isBtc = entry.path("marketId").asText().equals(BTC);
            assertThat(entry.path("marketId").asText()).isIn(BTC, ODDS);
            assertThat(entry.path("asset").asText()).isEqualTo(isBtc ? "BTC" : "SYNP1");
            assertThat(entry.path("roundId").asText()).isEqualTo(isBtc ? btcRound : oddsRound);
            assertThat(entry.path("interval").asText()).isEqualTo("DAILY");
            assertThat(entry.path("durationSec").asLong()).isEqualTo(12);
        }
        JsonNode unseen = ok(me, "unseenResults");
        assertThat(unseen).hasSize(2);
        for (JsonNode entry : unseen) {
            assertThat(entry.path("durationSec").asLong()).isEqualTo(12);
        }
        // the streak is the rule's answer for those two rounds, newest first (rounds that end at the same instant
        // are ordered by market id, then round id, the greater one first — the profile query's own order)
        WinStreak.Result expected = WinStreak.of(newestFirst(profile));
        assertThat(profile.path("streak").path("current").asInt()).isEqualTo(expected.current());
        assertThat(profile.path("streak").path("best").asInt()).isEqualTo(expected.best());

        // --- the close of a round is the open of the next, for consecutive rounds of every kind of market ---
        long dailyStart = btcSettled.path("window").path("startMs").asLong();
        assertCloseIsNextOpen(BTC, dailyStart);
        assertCloseIsNextOpen("DOGE", dailyStart);
        assertCloseIsNextOpen(ODDS, dailyStart);

        // --- the crowd traded the rounds the player was alone in: its accounts show it ---
        await().atMost(Duration.ofSeconds(10)).pollInterval(POLL).untilAsserted(() -> {
            JsonNode btcBrief = market(BTC).path("crowd");
            JsonNode oddsBrief = market(ODDS).path("crowd");
            assertThat(btcBrief.path("roundsTraded").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(oddsBrief.path("roundsTraded").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(btcBrief.path("lastOutcome").asText()).isIn("WIN", "LOSS", "LIQUIDATED", "FLAT");
            assertThat(oddsBrief.path("lastOutcome").asText()).isIn("WIN", "LOSS", "LIQUIDATED", "FLAT");
            assertThat(btcBrief.path("roundsWon").asInt()).isLessThanOrEqualTo(btcBrief.path("roundsTraded").asInt());
        });
        JsonNode overview = ok(null, "crowdOverview");
        assertThat(overview.path("markets")).hasSize(5);
        assertThat(overview.path("totalStartingCapital").asText()).isEqualTo("500000.00");
        for (JsonNode row : overview.path("markets")) {
            assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        }

        // --- the oracle log has begun: records of the settled rounds, the lowest seq is the first ---
        assertThat(ok(null, "serverInfo").path("oracle").path("firstSeq").isNull()).isFalse();
    }

    // ---------------------------------------------------------------------------------------

    private static JsonNode byMarket(List<JsonNode> positions, String marketId) {
        return positions.stream().filter(p -> p.path("marketId").asText().equals(marketId)).findFirst()
                .orElseThrow(() -> new AssertionError("no position on " + marketId + " in " + positions));
    }

    private static JsonNode seriesWithBettingRound(JsonNode market, String roundId) {
        for (JsonNode series : market.path("series")) {
            if (series.path("bettingRoundId").asText().equals(roundId)) {
                return series;
            }
        }
        return null;
    }

    /** Both of the player's positions RUNNING, each with its entry price. */
    private List<JsonNode> awaitRunning(Session me) {
        List<JsonNode>[] seen = new List[1];
        await().atMost(ROUND_TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            List<JsonNode> positions = positions(me);
            assertThat(positions).hasSize(2);
            for (JsonNode position : positions) {
                assertThat(position.path("phase").asText()).isEqualTo("RUNNING");
                assertThat(position.path("entryPrice").isNull()).as("the opening price is known").isFalse();
            }
            seen[0] = positions;
        });
        return seen[0];
    }

    private void assertPriceSeries(String marketId, int decimals, String priceFormat) throws Exception {
        JsonNode series = ok(null, "priceSeries", marketId);
        assertThat(series.path("marketId").asText()).isEqualTo(marketId);
        assertThat(series.path("priceDecimals").asInt()).isEqualTo(decimals);
        JsonNode ticks = series.path("ticks");
        assertThat(ticks.size()).as("%s: a tick a second since it was listed", marketId).isGreaterThanOrEqualTo(5);
        long previous = 0;
        for (JsonNode tick : ticks) {
            assertThat(tick.path("price").asText()).matches(priceFormat);
            assertThat(tick.path("atMs").asLong()).isGreaterThan(previous);
            previous = tick.path("atMs").asLong();
        }
        assertThat(previous).as("the newest tick is recent").isGreaterThan(System.currentTimeMillis() - 30_000L);
        // a chart that polls: after its newest tick only newer ones come
        long newest = previous;
        await().atMost(Duration.ofSeconds(20)).pollInterval(POLL).untilAsserted(() -> {
            JsonNode later = ok(null, "priceSeries", marketId, newest).path("ticks");
            assertThat(later.size()).as("a tick after %d", newest).isGreaterThanOrEqualTo(1);
            for (JsonNode tick : later) {
                assertThat(tick.path("atMs").asLong()).isGreaterThan(newest);
            }
        });
    }

    private void assertSettledRound(JsonNode round, String marketId, String asset, Session me, int decimals,
                                    JsonNode runningPosition) {
        String priceFormat = decimals == 2 ? "\\d+\\.\\d{2}" : "0\\.\\d{" + decimals + "}";
        assertThat(round.path("marketId").asText()).isEqualTo(marketId);
        assertThat(round.path("asset").asText()).isEqualTo(asset);
        assertThat(round.path("window").path("interval").asText()).isEqualTo("DAILY");
        assertThat(round.path("window").path("endMs").asLong() - round.path("window").path("startMs").asLong())
                .isEqualTo(12_000L);
        assertThat(round.path("openPrice").asText()).matches(priceFormat);
        assertThat(round.path("closePrice").asText()).matches(priceFormat);
        assertThat(round.path("openPrice").asText()).as("the entry price shown while it ran was the open")
                .isEqualTo(runningPosition.path("entryPrice").asText());
        assertThat(round.path("resultsHash").asText()).startsWith("0x");
        JsonNode mine = null;
        for (JsonNode position : round.path("positions")) {
            if (position.path("playerId").asText().equals(me.playerId())) {
                mine = position;
            }
        }
        assertThat(mine).as("the player's position in %s", round.path("roundId").asText()).isNotNull();
        assertThat(mine.path("entryPrice").asText()).isEqualTo(round.path("openPrice").asText());
        assertThat(mine.path("liquidationPrice").asText()).isEqualTo(runningPosition.path("liquidationPrice").asText());
        assertThat(mine.path("payout").isNull()).isFalse();
        assertThat(round.path("crowd").path("traded").asBoolean()).as("the player is the whole crowd: it trades").isTrue();
    }

    /** The history of the profile as the streak rule reads it: newest first, ties by market and round id, descending. */
    private static List<WinStreak.Round> newestFirst(JsonNode profile) {
        List<JsonNode> entries = new ArrayList<>();
        profile.path("history").forEach(entries::add);
        entries.sort(Comparator.<JsonNode>comparingLong(e -> e.path("settledAtMs").asLong())
                .thenComparing(e -> e.path("marketId").asText())
                .thenComparing(e -> e.path("roundId").asText()).reversed());
        List<WinStreak.Round> rounds = new ArrayList<>();
        for (JsonNode entry : entries) {
            rounds.add(new WinStreak.Round(money(entry, "stake").movePointRight(3).longValueExact(),
                    money(entry, "payout").movePointRight(3).longValueExact()));
        }
        return rounds;
    }

    private static String hourlyRoundId(String marketId, long startMs) {
        return (marketId.equals(BTC) ? "" : marketId + "-") + "1h-" + startMs;
    }

    /** For every pair of consecutive 6-second rounds of the market around the 12-second round that starts at
     *  {@code dailyStart} that have both settled: the close of the first is the open of the second, to the digit. */
    private void assertCloseIsNextOpen(String marketId, long dailyStart) {
        await().atMost(ROUND_TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            Map<Long, JsonNode> settled = new TreeMap<>();
            for (long start = dailyStart - 18_000L; start <= dailyStart + 12_000L; start += 6_000L) {
                JsonNode round = settledOrNull(hourlyRoundId(marketId, start));
                if (round != null) {
                    settled.put(start, round);
                }
            }
            int pairs = 0;
            for (Map.Entry<Long, JsonNode> first : settled.entrySet()) {
                JsonNode next = settled.get(first.getKey() + 6_000L);
                if (next != null) {
                    pairs++;
                    assertThat(next.path("openPrice").asText())
                            .as("%s: open of the round at %d == close of the one before it", marketId, first.getKey() + 6_000L)
                            .isEqualTo(first.getValue().path("closePrice").asText());
                }
            }
            assertThat(pairs).as("%s: two consecutive settled 6-second rounds", marketId).isGreaterThanOrEqualTo(1);
        });
    }
}
