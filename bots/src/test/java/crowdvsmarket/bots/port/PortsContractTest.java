package crowdvsmarket.bots.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BetResult;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The contract of the three ports. Published in the module's {@code test-jar}: the in-memory fake passes it, and so must
 * every adapter written later (the server over {@code GameService}, a client of the public API). A subclass supplies
 * the ports and a fixture:
 *
 * <ul>
 *   <li>at least one open market whose current round the seeded bot has not bet in, with at least one candle;
 *   <li>{@link #seededBotId()}: a bot with a balance of at least 10 000 PLAY and no bet in the open rounds;
 *   <li>optionally {@link #brokeBotId()}: a bot whose balance is below the minimum stake plus fees.
 * </ul>
 *
 * A fresh instance (so a fresh fixture) is created for every test, and tests may place bets.
 */
public abstract class PortsContractTest {

    protected abstract MarketDataPort marketData();

    protected abstract BettingPort betting();

    protected abstract BotStatePort state();

    protected abstract String seededBotId();

    /** A bot that cannot afford the minimum stake, or {@code null} when the fixture has none. */
    protected String brokeBotId() {
        return null;
    }

    // --- MarketDataPort ---

    @Test
    void marketsAreOnePerIdWithSaneWindows() {
        List<MarketSnapshot> markets = marketData().markets();
        assertThat(markets).isNotEmpty();
        Set<String> ids = new HashSet<>();
        for (MarketSnapshot m : markets) {
            assertThat(ids.add(m.marketId())).as("one snapshot per market: " + m.marketId()).isTrue();
            assertThat(m.windowEndMs()).isGreaterThan(m.windowStartMs());
            assertThat(m.roundId()).isNotBlank();
            assertThat(m.minStake().isPositive()).isTrue();
            assertThat(m.maxLeverage(m.minStake())).isGreaterThanOrEqualTo(2);
            assertThat(m.maxLeverage(PlayAmount.ofWholePlay(100_000))).isLessThanOrEqualTo(m.maxLeverage(m.minStake()));
        }
        assertThat(markets).anyMatch(MarketSnapshot::open);
    }

    @Test
    void barsAreAtMostTheAskedNumberOldestFirstAndConsistent() {
        for (MarketSnapshot m : marketData().markets()) {
            List<BarDto> bars = marketData().bars(m.marketId(), 30);
            assertThat(bars.size()).isLessThanOrEqualTo(30);
            for (int i = 1; i < bars.size(); i++) {
                assertThat(bars.get(i).openTimeMs()).isGreaterThan(bars.get(i - 1).openTimeMs());
            }
            List<BarDto> more = marketData().bars(m.marketId(), 60);
            assertThat(more.size()).isGreaterThanOrEqualTo(bars.size());
            if (!bars.isEmpty()) {
                assertThat(more.get(more.size() - 1)).as("the newest candle is the same however many are asked for")
                        .isEqualTo(bars.get(bars.size() - 1));
            }
        }
    }

    @Test
    void unknownMarketGivesEmptyAnswersNotExceptions() {
        assertThatCode(() -> marketData().bars("NO_SUCH_MARKET", 10)).doesNotThrowAnyException();
        assertThat(marketData().bars("NO_SUCH_MARKET", 10)).isEmpty();
        assertThat(marketData().previousMajority("NO_SUCH_MARKET")).isEmpty();
        assertThat(marketData().bars(openMarket().marketId(), 0)).isEmpty();
    }

    // --- BotStatePort ---

    @Test
    void unknownBotHasNoAccount() {
        assertThat(state().account("no-such-bot-" + System.nanoTime())).isEmpty();
    }

    @Test
    void seededBotHasAConsistentAccount() {
        BotAccount a = state().account(seededBotId()).orElseThrow();
        assertThat(a.botId()).isEqualTo(seededBotId());
        assertThat(a.balance().compareTo(PlayAmount.ofWholePlay(10_000))).isGreaterThanOrEqualTo(0);
        assertThat(a.betRoundIds()).doesNotContain(openMarket().roundId());
    }

    // --- BettingPort ---

    @Test
    void aValidBetIsAcceptedAndChargesStakePlusFees() {
        MarketSnapshot m = openMarket();
        BotAccount before = state().account(seededBotId()).orElseThrow();
        PlayAmount stake = m.minStake();
        BetResult r = betting().place(request(m, stake, 2));
        assertThat(r.accepted()).as(r.reason()).isTrue();
        assertThat(r.feesCharged()).isEqualTo(m.fees(2, stake).total());
        BotAccount after = state().account(seededBotId()).orElseThrow();
        assertThat(after.balance()).isEqualTo(before.balance().minus(stake).minus(r.feesCharged()));
        assertThat(after.betRoundIds()).contains(m.roundId());
    }

    @Test
    void aSecondBetInTheSameRoundIsRefusedAndCostsNothing() {
        MarketSnapshot m = openMarket();
        assertThat(betting().place(request(m, m.minStake(), 2)).accepted()).isTrue();
        BotAccount mid = state().account(seededBotId()).orElseThrow();
        BetResult second = betting().place(request(m, m.minStake(), 2));
        assertThat(second.accepted()).isFalse();
        assertThat(second.reason()).isNotBlank();
        assertThat(second.feesCharged()).isEqualTo(PlayAmount.ZERO);
        assertThat(state().account(seededBotId()).orElseThrow().balance()).isEqualTo(mid.balance());
    }

    @Test
    void aStakeBelowTheMinimumIsRefused() {
        MarketSnapshot m = openMarket();
        BetResult r = betting().place(request(m, m.minStake().minus(new PlayAmount(1)), 2));
        assertThat(r.accepted()).isFalse();
    }

    @Test
    void aLeverageAboveTheCapOfTheStakeTierIsRefused() {
        MarketSnapshot m = openMarket();
        PlayAmount stake = m.minStake();
        assertThat(betting().place(request(m, stake, m.maxLeverage(stake) + 1)).accepted()).isFalse();
        assertThat(betting().place(request(m, stake, 1)).accepted()).isFalse();
        // the cap itself is fine
        assertThat(betting().place(request(m, stake, m.maxLeverage(stake))).accepted()).isTrue();
    }

    @Test
    void aStakeAboveTheBalanceIsRefused() {
        MarketSnapshot m = openMarket();
        BotAccount a = state().account(seededBotId()).orElseThrow();
        PlayAmount tooMuch = a.balance().plus(PlayAmount.ofWholePlay(1));
        assertThat(betting().place(request(m, tooMuch, 2)).accepted()).isFalse();
        assertThat(state().account(seededBotId()).orElseThrow().balance()).isEqualTo(a.balance());
    }

    @Test
    void aBrokeBotCannotBet() {
        Assumptions.assumeTrue(brokeBotId() != null, "fixture has no broke bot");
        MarketSnapshot m = openMarket();
        BetRequest req = new BetRequest(brokeBotId(), m.marketId(), m.roundId(), Direction.UP, m.minStake(), 2);
        assertThat(betting().place(req).accepted()).isFalse();
    }

    @Test
    void anUnknownMarketOrBotOrAStaleRoundIsRefusedNotThrown() {
        MarketSnapshot m = openMarket();
        assertThatCode(() -> betting().place(new BetRequest(seededBotId(), "NO_SUCH_MARKET", "x", Direction.UP, m.minStake(), 2)))
                .doesNotThrowAnyException();
        assertThat(betting().place(new BetRequest(seededBotId(), "NO_SUCH_MARKET", "x", Direction.UP, m.minStake(), 2)).accepted())
                .isFalse();
        assertThat(betting().place(new BetRequest("no-such-bot", m.marketId(), m.roundId(), Direction.UP, m.minStake(), 2)).accepted())
                .isFalse();
        assertThat(betting().place(new BetRequest(seededBotId(), m.marketId(), m.roundId() + "-stale", Direction.UP, m.minStake(), 2))
                .accepted()).isFalse();
    }

    @Test
    void aClosedMarketRefusesBets() {
        Optional<MarketSnapshot> closed = marketData().markets().stream().filter(x -> !x.open()).findFirst();
        Assumptions.assumeTrue(closed.isPresent(), "fixture has no closed market");
        MarketSnapshot m = closed.get();
        assertThat(betting().place(request(m, m.minStake(), 2)).accepted()).isFalse();
    }

    // --- helpers ---

    /** The first open market the seeded bot has not bet in. */
    protected final MarketSnapshot openMarket() {
        BotAccount a = state().account(seededBotId()).orElseThrow();
        return marketData().markets().stream()
                .filter(x -> x.open() && !a.betRoundIds().contains(x.roundId()))
                .findFirst().orElseThrow();
    }

    private BetRequest request(MarketSnapshot m, PlayAmount stake, int leverage) {
        return new BetRequest(seededBotId(), m.marketId(), m.roundId(), Direction.UP, stake, leverage);
    }
}
