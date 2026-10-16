package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotMetrics;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.PriceBar;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The catalog bots in the running application (docs/research/bots_module_design.md section 7): through the real scheduler pass,
 * the real {@code BotService}, the real ports and the real {@code GameService.placeBet}, the 21 bots of the {@code bots} module's
 * catalog play a round on candles - every bet is valid (leverage within the cap of its stake, one bet per bot per round of a
 * market, only on markets with a fresh price) - and the bots' day shows on the operator's status page. (The planning, the
 * migration of the old bots and the respawn are unit-tested with fakes in {@code BotServiceCatalogTest}; the ports against the
 * module's contract in {@code ServerBotPortsContractTest}.) Bots live in this class only: they would bet in the other
 * multi-market tests too.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CatalogBotsTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long STEP = 60_000L;

    @Inject
    PlayerDirectory players;
    @Inject
    BotMetrics botMetrics;
    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    private List<String> catalogBots() {
        if (players.bots().isEmpty()) {
            for (CatalogEntry entry : Catalog.load().bots()) {
                gameService.createBotPlayer("Bot " + entry.name(), entry.catalogId(), System.currentTimeMillis());
            }
        }
        return players.bots().stream().map(PlayerDirectory.Row::playerId).toList();
    }

    @Test
    void theCatalogBotsPlayARoundOnCandlesWithValidBetsAndTheDayShowsOnTheStatusPage() {
        List<Market> markets = List.of(
                list("T_CBOT_A", MarketKind.PERP, Category.CRYPTO, "CA", 40, 0),
                list("T_CBOT_B", MarketKind.PERP, Category.CRYPTO, "CB", 25, 0),
                list("T_CBOT_C", MarketKind.PERP, Category.STOCKS, "CC", 10, 0),
                list("T_CBOT_STALE", MarketKind.PERP, Category.CRYPTO, "CS", 25, 0));
        for (Market market : markets) {
            prices.setPrice(market, "100.00");
        }
        prices.setStale(markets.get(3), "100.00");
        List<String> bots = catalogBots();
        assertThat(bots).hasSize(Catalog.load().bots().size());

        RoundWindow like = roundProgress.next("BTC", HOURLY);
        RoundWindow running = new RoundWindow("BTC", HOURLY, like.startMs() + 90 * STEP, like.endMs() + 90 * STEP);
        for (Market market : markets) {
            alignTo(market, HOURLY, running);
            // the daily series is not what this test looks at: park it far from the window under test
            roundProgress.seed(market.id(), Interval.DAILY, calendar.windowContaining(market.id(), Interval.DAILY, System.currentTimeMillis()));
            // two hours of 1-minute candles before the bots decide, a market that has been drifting up
            List<PriceBar> candles = new ArrayList<>();
            for (int i = 120; i >= 1; i--) {
                BigDecimal close = new BigDecimal("100").add(new BigDecimal((120 - i) * 0.05 + (i % 5 == 0 ? -0.6 : 0.2)));
                candles.add(new PriceBar(running.endMs() / STEP * STEP - i * STEP, close, close.add(BigDecimal.ONE),
                        close.subtract(BigDecimal.ONE), close));
            }
            prices.setBars(market, candles);
        }

        scheduler.tick(running.startMs());                  // betting is open: the bots plan their shots
        scheduler.tick(running.endMs() - 1);                // ... and all of them are due before it closes

        Set<String> botsThatBet = new HashSet<>();
        Set<String> oneBetPerRound = new HashSet<>();
        int bets = 0;
        for (Market market : markets) {
            RoundWindow betting = RoundClock.next(new RoundWindow(market.id(), HOURLY, running.startMs(), running.endMs()));
            for (Bet bet : hub.engine(market.id(), HOURLY).betBook().betsFor(betting.roundId())) {
                if (!bots.contains(bet.playerId())) {
                    continue;
                }
                bets++;
                botsThatBet.add(bet.playerId());
                assertThat(market.id()).as("a market with a stale price is not bet on").isNotEqualTo("T_CBOT_STALE");
                assertThat(oneBetPerRound.add(bet.playerId() + "/" + betting.roundId())).isTrue();
                assertThat(bet.leverage().value()).as("%s on %s", bet.playerId(), market.id())
                        .isBetween(2, EconomyRules.maxLeverage(market.spec(), HOURLY, bet.stake()));
            }
        }
        assertThat(bets).as("21 personas over 3 markets with fresh prices").isGreaterThan(20);
        assertThat(botsThatBet.size()).isGreaterThan(10);

        // the bots' day, as the operator sees it
        long at = running.endMs();
        int placed = botMetrics.lastDays(at, 2).stream().mapToInt(d -> (int) d.bets()).sum();
        assertThat(placed).isGreaterThanOrEqualTo(bets);
        BotMetrics.Day today = botMetrics.lastDays(at, 1).get(0);
        assertThat(today.botsCreated()).isEqualTo(Catalog.load().bots().size());
        assertThat(today.starterPacks()).isEqualTo(new BigDecimal(10_000).multiply(new BigDecimal(today.botsCreated())).setScale(3).toPlainString());
        Response status = given().header("Authorization", "Bearer " + operatorToken).get("/api/admin/status");
        assertThat(status.statusCode()).as(status.asString()).isEqualTo(200);
        List<java.util.Map<String, Object>> days = status.jsonPath().getList("botDays");
        assertThat(days).hasSize(7);
        assertThat(days.get(0).keySet()).containsExactlyInAnyOrder("day", "bets", "staked", "fees", "pnl", "retirements", "botsCreated",
                "starterPacks");
        assertThat(days.stream().mapToInt(d -> ((Number) d.get("botsCreated")).intValue()).sum()).isGreaterThanOrEqualTo(21);
    }
}
