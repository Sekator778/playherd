package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotStrategy;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Т-8 in the running application (docs/research/markets_architecture.md §2): through the real scheduler
 * pass, the real {@code BotService} and the real {@code GameService.placeBet}, every bot bets on
 * {@code herd.bots.markets-per-round} (3) distinct active markets in a round of a series, within each
 * market's leverage cap, and never on a market without a fresh price. (The planning itself is
 * unit-tested with fakes in {@code BotServiceTest}.) Bots live in this class only: they would bet in
 * the other multi-market tests too.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class BotsOnSeveralMarketsTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long STEP = 60_000L;

    @Inject
    PlayerDirectory players;

    /** One bot per strategy, created once for the whole class (the directory keeps them between tests). */
    private List<String> bots() {
        if (players.bots().isEmpty()) {
            int i = 0;
            for (BotStrategy strategy : BotStrategy.values()) {
                gameService.createBotPlayer("Bot " + (++i) + " (" + strategy.label() + ")", strategy.name(),
                        System.currentTimeMillis());
            }
        }
        return players.bots().stream().map(PlayerDirectory.Row::playerId).toList();
    }

    /** The markets' running window, {@code stepsAhead} windows after BTC's — a window of its own per test,
     *  since the bots plan each betting window only once. */
    private RoundWindow alignAll(List<Market> markets, int stepsAhead) {
        RoundWindow like = roundProgress.next("BTC", HOURLY);
        RoundWindow running = new RoundWindow("BTC", HOURLY, like.startMs() + stepsAhead * STEP,
                like.endMs() + stepsAhead * STEP);
        for (Market market : markets) {
            alignTo(market, HOURLY, running);
            // the daily series is not what this test looks at: park it far from the window under test
            roundProgress.seed(market.id(), Interval.DAILY, calendar.windowContaining(market.id(), Interval.DAILY,
                    System.currentTimeMillis()));
        }
        return running;
    }

    private Map<String, Set<String>> hourlyBetsByBot(List<Market> markets, RoundWindow running) {
        Map<String, Set<String>> marketsByBot = new HashMap<>();
        for (Market market : markets) {
            RoundWindow betting = RoundClock.next(new RoundWindow(market.id(), HOURLY, running.startMs(),
                    running.endMs()));
            for (Bet bet : hub.engine(market.id(), HOURLY).betBook().betsFor(betting.roundId())) {
                marketsByBot.computeIfAbsent(bet.playerId(), k -> new HashSet<>()).add(market.id());
                assertThat(bet.leverage().value()).as("%s on %s", bet.playerId(), market.id())
                        .isBetween(2, market.maxLeverage());
            }
        }
        return marketsByBot;
    }

    @Test
    void everyBotBetsOnThreeDistinctMarketsOfFiveWithinEachMarketsLeverageCap() {
        List<Market> markets = List.of(
                list("T_BOT_A", MarketKind.PERP, Category.CRYPTO, "BA", 40, 0),
                list("T_BOT_B", MarketKind.PERP, Category.CRYPTO, "BB", 25, 0),
                list("T_BOT_C", MarketKind.ODDS, Category.POLITICS, "BC", 10, 4),
                list("T_BOT_D", MarketKind.ODDS, Category.SPORTS, "BD", 10, 4),
                list("T_BOT_E", MarketKind.PERP, Category.STOCKS, "BE", 5, 0));
        for (Market market : markets) {
            prices.setPrice(market, "100.00");
        }
        List<String> bots = bots();
        RoundWindow running = alignAll(markets, 0);

        scheduler.tick(running.startMs());                          // betting is open: the bots plan their bets
        scheduler.tick(running.endMs() - 1);                        // ... and all of them are due before it closes

        Map<String, Set<String>> byBot = hourlyBetsByBot(markets, running);
        assertThat(byBot.keySet()).containsExactlyInAnyOrderElementsOf(bots);
        for (String bot : bots) {
            assertThat(byBot.get(bot)).as("markets of %s", bot).hasSize(3);
        }
        // one bet per player per market round, so the feed of each round has one entry per bot that picked it
        int bets = byBot.values().stream().mapToInt(Set::size).sum();
        assertThat(bets).isEqualTo(bots.size() * 3);
    }

    @Test
    void aMarketWithoutAFreshPriceIsNotBetOnWhateverTheOthersDo() {
        Market a = list("T_BOT_FA", MarketKind.PERP, Category.CRYPTO, "FA", 25, 0);
        Market b = list("T_BOT_FB", MarketKind.PERP, Category.CRYPTO, "FB", 25, 0);
        Market c = list("T_BOT_FC", MarketKind.PERP, Category.CRYPTO, "FC", 25, 0);
        Market stale = list("T_BOT_FSTALE", MarketKind.PERP, Category.CRYPTO, "FS", 25, 0);
        Market silent = list("T_BOT_FNONE", MarketKind.PERP, Category.CRYPTO, "FN", 25, 0);
        List<Market> markets = List.of(a, b, c, stale, silent);
        prices.setPrice(a, "100.00");
        prices.setPrice(b, "100.00");
        prices.setPrice(c, "100.00");
        prices.setStale(stale, "100.00");        // the last tick is older than the source's staleness limit
        prices.clearLatest(silent);              // no tick at all
        List<String> bots = bots();
        RoundWindow running = alignAll(markets, 30);

        scheduler.tick(running.startMs());
        scheduler.tick(running.endMs() - 1);

        Map<String, Set<String>> byBot = hourlyBetsByBot(markets, running);
        assertThat(byBot.keySet()).containsExactlyInAnyOrderElementsOf(bots);
        for (String bot : bots) {
            assertThat(byBot.get(bot)).as("markets of %s", bot).containsExactlyInAnyOrder(a.id(), b.id(), c.id());
        }
    }
}
