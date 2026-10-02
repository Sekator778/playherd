package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.server.testsupport.LiveServerProfile;
import crowdvsmarket.server.testsupport.LiveServerTestBase;
import crowdvsmarket.server.testsupport.LiveServerWithBotsProfile;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LiveServerEndToEndTest} with a few bots in the game ({@link LiveServerWithBotsProfile}: three bots,
 * each betting in every round on up to three random markets): the lobby fills with their bets, the crowd trades,
 * the rounds a player and bots are in together settle with both in them, and the player's books still balance —
 * balance plus stakes in play equals the starter pack plus everything won or lost.
 */
@QuarkusTest
@TestProfile(LiveServerWithBotsProfile.class)
class LiveServerWithBotsEndToEndTest extends LiveServerTestBase {

    private static final String BTC = "BTC";
    private static final String ODDS = "PMSYN1";

    @Test
    void botsFillTheLobbyAndAPlayerWhoBetsAmongThemKeepsBalancedBooks() throws Exception {
        awaitMarketsListedWithPrices(LiveServerProfile.MARKET_IDS);
        assertThat(ok(null, "serverInfo").path("bots").asInt()).isEqualTo(3);
        Session me = signIn("bots");
        BigDecimal starter = money(ok(me, "myAccount"), "balance");
        assertThat(starter).isEqualByComparingTo(LedgerBook.STARTER_PACK.toMoney());

        // --- the player bets in the open 12-second rounds of BTC and the ODDS market ---
        JsonNode btcSeries = awaitBettingWindow(BTC, "DAILY", 7_000L);
        JsonNode oddsSeries = awaitBettingWindow(ODDS, "DAILY", 5_000L);
        String btcRound = btcSeries.path("bettingRoundId").asText();
        String oddsRound = oddsSeries.path("bettingRoundId").asText();
        ok(me, "placeBet", bet(btcRound, "UP", 5, "200.000"));
        ok(me, "placeBet", bet(oddsRound, "DOWN", 3, "150.500"));
        List<JsonNode> pending = positions(me);
        assertThat(pending).as("the player's own bets, whoever else is betting").hasSize(2);
        assertThat(pending).allSatisfy(p -> assertThat(p.path("phase").asText()).isEqualTo("BETTING"));

        // --- the bots' bets show in the lobby: some round has more than the player in it ---
        await().atMost(Duration.ofSeconds(60)).pollInterval(POLL).untilAsserted(() -> {
            boolean botsInSomeRound = false;
            for (JsonNode market : ok(null, "markets").path("markets")) {
                for (JsonNode series : market.path("series")) {
                    String round = series.path("bettingRoundId").asText();
                    boolean mine = round.equals(btcRound) || round.equals(oddsRound);
                    if (series.path("participantCount").asInt() > (mine ? 1 : 0)) {
                        botsInSomeRound = true;
                    }
                }
            }
            assertThat(botsInSomeRound).as("a bot has bet in some betting round").isTrue();
        });
        // ... and so does the live bet feed of a round: a bot's bet is marked as one
        await().atMost(Duration.ofSeconds(60)).pollInterval(POLL).untilAsserted(() -> {
            boolean aBot = false;
            for (JsonNode market : ok(null, "markets").path("markets")) {
                for (JsonNode series : market.path("series")) {
                    if (series.path("participantCount").asInt() > 0) {
                        JsonNode feed = ok(null, "betFeed", series.path("bettingRoundId").asText());
                        for (JsonNode entry : feed.path("entries")) {
                            aBot |= entry.path("isBot").asBoolean();
                        }
                    }
                }
            }
            assertThat(aBot).as("a feed entry of a bot").isTrue();
        });

        // --- the rounds settle; the player is in them with whoever else bet ---
        JsonNode btcSettled = awaitSettled(me, btcRound);
        JsonNode oddsSettled = awaitSettled(me, oddsRound);
        for (JsonNode round : List.of(btcSettled, oddsSettled)) {
            boolean mine = false;
            for (JsonNode position : round.path("positions")) {
                mine |= position.path("playerId").asText().equals(me.playerId());
                assertThat(position.path("payout").isNull()).as("a settled position has its payout").isFalse();
            }
            assertThat(mine).as("the player's position in %s", round.path("roundId").asText()).isTrue();
        }
        assertThat(btcSettled.path("marketId").asText()).isEqualTo(BTC);
        assertThat(oddsSettled.path("marketId").asText()).isEqualTo(ODDS);

        // --- the books balance: balance + stakes in play == starter pack + Σ(payout − stake) over the history ---
        JsonNode account = ok(me, "myAccount");
        JsonNode profile = ok(me, "myProfile");
        BigDecimal net = BigDecimal.ZERO;
        for (JsonNode entry : profile.path("history")) {
            net = net.add(money(entry, "payout")).subtract(money(entry, "stake"));
        }
        assertThat(profile.path("history")).hasSize(2);
        assertThat(money(account, "inPlay")).isEqualByComparingTo("0.000");
        assertThat(money(account, "balance").add(money(account, "inPlay"))).isEqualByComparingTo(starter.add(net));

        // --- the crowd has traded somewhere, and its accounts say so ---
        await().atMost(Duration.ofSeconds(20)).pollInterval(POLL).untilAsserted(() -> {
            JsonNode overview = ok(null, "crowdOverview");
            long traded = 0;
            for (JsonNode row : overview.path("markets")) {
                traded += row.path("roundsTraded").asLong();
                assertThat(row.path("roundsWon").asLong()).isLessThanOrEqualTo(row.path("roundsTraded").asLong());
            }
            assertThat(traded).isGreaterThanOrEqualTo(2);
        });
    }
}
