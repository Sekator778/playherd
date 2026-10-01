package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Several markets on one {@link LedgerBook} (Р-69): one {@link GameEngine} — with its own
 * {@link CrowdAccount} — per market, all sharing the ledger, so one player's PLAY balance backs
 * bets on every pair. Here: BTC (40x, {@code l = 0.0125}) and a made-up "ETH" (25x,
 * {@code l = 0.02}) priced like ETH — the engine's price is whatever scale-2 number the caller
 * hands it, so ETH needs nothing but its own {@link MarketSpec}.
 *
 * <p>Expected outcomes were computed independently with python3 {@code decimal} (formulas of
 * docs/research/hl_liquidation_math.md section 2.2, size = round8(stake*leverage/price)):
 *
 * <pre>
 * BTC open 50000.00 low 49000.00 high 50500.00 close 49500.00
 *   alice long 40x 1000: liquidation 49367.09, low 49000.00 touches it -> liquidated
 *   bob short 10x 500: liquidation 54320.99 not reached -> payout 550.00
 * ETH open 2694.40 low 2600.00 high 2740.00 close 2650.00
 *   alice short 25x 1500: liquidation 2747.23 not reached -> payout 2117.95
 *   bob long 20x 700: liquidation 2611.92 (at BTC's rate it would be 2592.08), low 2600.00 -> liquidated
 * </pre>
 */
class GameEngineMultiMarketTest {

    private static final long HOUR = 3_600_000L;
    private static final long START = Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    private static final Instant BET_TIME = Instant.ofEpochMilli(START - 60_000L);
    private static final MarketSpec ETH = MarketSpec.of("ETH", 25);

    private static final RoundWindow BTC_WINDOW = new RoundWindow(Interval.HOURLY, START, START + HOUR);
    private static final RoundWindow ETH_WINDOW = new RoundWindow("ETH", Interval.HOURLY, START, START + HOUR);

    private static final RoundPrices BTC_PRICES = prices(candle("50000.00", "50500.00", "49000.00", "49500.00"));
    private static final RoundPrices ETH_PRICES = prices(candle("2694.40", "2740.00", "2600.00", "2650.00"));

    private final LedgerBook ledger = new LedgerBook();
    private final GameEngine btc = new GameEngine(ledger, new CrowdAccount());
    private final GameEngine eth = new GameEngine(ledger, new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH));

    private static Candle candle(String open, String high, String low, String close) {
        return new Candle(START, START + HOUR - 1, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ZERO, 0L);
    }

    private static RoundPrices prices(Candle only) {
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow window) {
                return only.open();
            }

            @Override
            public BigDecimal closePrice(RoundWindow window) {
                return only.close();
            }

            @Override
            public List<Candle> path(RoundWindow window) {
                return List.of(only);
            }
        };
    }

    /** Fails the test if an engine reads prices at all — a rejected window must be refused before that. */
    private static RoundPrices pricesThatMustNotBeRead() {
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow window) {
                throw new IllegalStateException("prices must not be consulted");
            }

            @Override
            public BigDecimal closePrice(RoundWindow window) {
                throw new IllegalStateException("prices must not be consulted");
            }

            @Override
            public List<Candle> path(RoundWindow window) {
                throw new IllegalStateException("prices must not be consulted");
            }
        };
    }

    private void placeScenarioBets() {
        btc.placeBet("alice", BTC_WINDOW, Direction.UP, new Leverage(40), PlayAmount.ofWholePlay(1_000), BET_TIME);
        eth.placeBet("alice", ETH_WINDOW, Direction.DOWN, new Leverage(25), PlayAmount.ofWholePlay(1_500), BET_TIME);
        btc.placeBet("bob", BTC_WINDOW, Direction.DOWN, new Leverage(10), PlayAmount.ofWholePlay(500), BET_TIME);
        eth.placeBet("bob", ETH_WINDOW, Direction.UP, new Leverage(20), PlayAmount.ofWholePlay(700), BET_TIME);
    }

    private static PlayerRoundOutcome outcome(RoundReport report, String playerId) {
        return report.playerOutcomes().stream().filter(o -> o.playerId().equals(playerId)).findFirst().orElseThrow();
    }

    private PlayAmount totalBalance() {
        PlayAmount total = PlayAmount.ZERO;
        for (String playerId : ledger.playerIds()) {
            total = total.plus(ledger.balance(playerId));
        }
        return total;
    }

    private static PlayAmount stakesInFlight(GameEngine engine, RoundWindow window) {
        PlayAmount total = PlayAmount.ZERO;
        for (Bet bet : engine.betBook().betsFor(window.roundId())) {
            total = total.plus(bet.stake());
        }
        return total;
    }

    // --- which market an engine is ---

    @Test
    void anEnginesMarketIsItsCrowdAccountsMarket() {
        assertThat(btc.spec()).isEqualTo(MarketSpec.BTC);
        assertThat(eth.spec()).isEqualTo(ETH);
        assertThat(eth.crowdAccount().spec()).isEqualTo(ETH);
        assertThat(new GameEngine().spec()).isEqualTo(MarketSpec.BTC);
    }

    // --- one player, two markets, one window ---

    @Test
    void oneHourlyWindowTwoMarketsBothRoundsResolveOnTheirOwnPricePath() {
        placeScenarioBets();

        // In flight: both stakes are debited from the one shared balance, and each bet sits in its
        // own market's book under that market's round id.
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(10_000 - 1_000 - 1_500));
        assertThat(ledger.balance("bob")).isEqualTo(PlayAmount.ofWholePlay(10_000 - 500 - 700));
        assertThat(btc.betBook().betsFor(BTC_WINDOW.roundId())).extracting(Bet::playerId).containsExactlyInAnyOrder("alice", "bob");
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).extracting(Bet::playerId).containsExactlyInAnyOrder("alice", "bob");
        assertThat(btc.betBook().betsFor(ETH_WINDOW.roundId())).isEmpty();
        assertThat(eth.betBook().betsFor(BTC_WINDOW.roundId())).isEmpty();
        PlayAmount packs = LedgerBook.STARTER_PACK.plus(LedgerBook.STARTER_PACK); // one pack per player, not per market
        assertThat(totalBalance().plus(stakesInFlight(btc, BTC_WINDOW)).plus(stakesInFlight(eth, ETH_WINDOW)))
                .as("balances + in-flight stakes == packs minted")
                .isEqualTo(packs);

        RoundReport btcReport = btc.runRound(BTC_WINDOW, BTC_PRICES);
        RoundReport ethReport = eth.runRound(ETH_WINDOW, ETH_PRICES);

        // each report is its own market's, on its own price path
        assertThat(btcReport.window()).isEqualTo(BTC_WINDOW);
        assertThat(ethReport.window()).isEqualTo(ETH_WINDOW);
        assertThat(btcReport.window().market()).isEqualTo("BTC");
        assertThat(ethReport.window().market()).isEqualTo("ETH");
        assertThat(btcReport.openPrice()).isEqualByComparingTo("50000.00");
        assertThat(btcReport.closePrice()).isEqualByComparingTo("49500.00");
        assertThat(ethReport.openPrice()).isEqualByComparingTo("2694.40");
        assertThat(ethReport.closePrice()).isEqualByComparingTo("2650.00");

        // BTC: alice's 40x long is liquidated, bob's 10x short settles
        assertThat(outcome(btcReport, "alice").liquidated()).isTrue();
        assertThat(outcome(btcReport, "alice").payout()).isEqualByComparingTo("0.00");
        assertThat(outcome(btcReport, "bob").liquidated()).isFalse();
        assertThat(outcome(btcReport, "bob").payout()).isEqualByComparingTo("550.00");
        // ETH: the same players, the other way round -- alice's 25x short settles, bob's 20x long is
        // liquidated, which only happens at the ETH market's own rate (l = 0.02, see the class comment)
        assertThat(outcome(ethReport, "alice").liquidated()).isFalse();
        assertThat(outcome(ethReport, "alice").payout()).isEqualByComparingTo("2117.95");
        assertThat(outcome(ethReport, "bob").liquidated()).isTrue();
        assertThat(outcome(ethReport, "bob").payout()).isEqualByComparingTo("0.00");

        // each market has its own crowd account, trading its own majority side at its own cap
        assertThat(btcReport.crowdResult().direction()).isEqualTo(Direction.UP); // 1000 up vs 500 down
        assertThat(btcReport.crowdResult().leverage()).isEqualTo(40);
        assertThat(btcReport.crowdResult().liquidated()).isTrue();
        assertThat(ethReport.crowdResult().direction()).isEqualTo(Direction.DOWN); // 1500 down vs 700 up
        assertThat(ethReport.crowdResult().leverage()).isEqualTo(25);
        assertThat(ethReport.crowdResult().liquidated()).isFalse();
        assertThat(btc.crowdAccount().capital()).isEqualByComparingTo("90000.00");
        assertThat(eth.crowdAccount().capital()).isGreaterThan(CrowdAccount.STARTING_CAPITAL);

        // bets are consumed, and both settlements landed on the one shared ledger
        assertThat(btc.betBook().betsFor(BTC_WINDOW.roundId())).isEmpty();
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).isEmpty();
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.fromMoney(new BigDecimal("9617.95"))); // 7500 + 2117.95
        assertThat(ledger.balance("bob")).isEqualTo(PlayAmount.fromMoney(new BigDecimal("9350.00"))); // 8800 + 550.00
        assertThat(ledger.seasonRecord("alice", Season.of(BET_TIME)).orElseThrow().roundsPlayed()).isEqualTo(2); // one per market

        // conservation, as MonthLongHourlySimulationTest checks it: total = packs + net round results
        PlayAmount net = PlayAmount.ZERO;
        for (RoundReport report : List.of(btcReport, ethReport)) {
            for (PlayerRoundOutcome o : report.playerOutcomes()) {
                net = net.plus(PlayAmount.fromMoney(o.payout()).minus(o.stake()));
            }
        }
        assertThat(totalBalance()).as("total PLAY = packs + net round winnings/losses").isEqualTo(packs.plus(net));
        assertThat(net).isEqualTo(PlayAmount.fromMoney(new BigDecimal("-1032.05"))); // -1000 + 50 + 617.95 - 700

        // ... and the append-only log replays every balance, each stake/settlement under its market's round id
        Map<String, PlayAmount> replayed = new HashMap<>();
        for (LedgerEntry entry : ledger.entries()) {
            replayed.merge(entry.playerId(), entry.delta(), PlayAmount::plus);
        }
        for (String playerId : ledger.playerIds()) {
            assertThat(replayed.get(playerId)).as("log replay for %s", playerId).isEqualTo(ledger.balance(playerId));
        }
        Set<String> roundIdsInLog = ledger.entries().stream()
                .filter(e -> e.type() == LedgerEntryType.STAKE_DEBIT || e.type() == LedgerEntryType.SETTLEMENT)
                .map(LedgerEntry::roundId)
                .collect(Collectors.toSet());
        assertThat(roundIdsInLog).containsExactlyInAnyOrder("1h-" + START, "ETH-1h-" + START);
        assertThat(ledger.entries().stream().filter(e -> e.type() == LedgerEntryType.STARTER_PACK)).hasSize(2);
    }

    @Test
    void oneBalanceBacksTheBetsOnEveryMarket() {
        btc.placeBet("alice", BTC_WINDOW, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(6_000), BET_TIME);

        // 4 000 PLAY left: a 5 000 bet on the other market is refused by the shared ledger
        assertThatThrownBy(() -> eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(5_000), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds balance");
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).isEmpty();

        eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(4_000), BET_TIME);
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ZERO);
    }

    // --- the leverage cap of the market ---

    @Test
    void aThirtyTimesBetOnTheTwentyFiveTimesMarketIsRejectedBeforeAnythingIsReserved() {
        ledger.ensureSeasonPack("alice", BET_TIME);
        int entriesBefore = ledger.entries().size();

        assertThatThrownBy(() -> eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(30), PlayAmount.ofWholePlay(500), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leverage must be in [2, 25] for ETH, got 30");
        assertThatThrownBy(() -> eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(40), PlayAmount.ofWholePlay(500), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leverage must be in [2, 25] for ETH, got 40");

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(ledger.entries()).hasSize(entriesBefore);
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).isEmpty();
    }

    @Test
    void theCapItselfIsAcceptedAndTheSameThirtyTimesIsFineOnBtc() {
        eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(25), PlayAmount.ofWholePlay(500), BET_TIME);
        btc.placeBet("alice", BTC_WINDOW, Direction.UP, new Leverage(30), PlayAmount.ofWholePlay(500), BET_TIME);
        btc.placeBet("bob", BTC_WINDOW, Direction.UP, new Leverage(40), PlayAmount.ofWholePlay(500), BET_TIME);

        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).singleElement().satisfies(b -> assertThat(b.leverage().value()).isEqualTo(25));
        assertThat(btc.betBook().betsFor(BTC_WINDOW.roundId())).hasSize(2);
    }

    @Test
    void aReplacementAboveTheCapIsRejectedAndTheOriginalBetStaysReserved() {
        eth.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(20), PlayAmount.ofWholePlay(1_000), BET_TIME);

        assertThatThrownBy(() -> eth.placeBet("alice", ETH_WINDOW, Direction.DOWN, new Leverage(30), PlayAmount.ofWholePlay(2_000), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class);

        // validation comes before the refund of the old bet: nothing was refunded, nothing re-reserved
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(9_000));
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).singleElement().satisfies(b -> {
            assertThat(b.leverage().value()).isEqualTo(20);
            assertThat(b.direction()).isEqualTo(Direction.UP);
            assertThat(b.stake()).isEqualTo(PlayAmount.ofWholePlay(1_000));
        });
    }

    // --- the market of the window ---

    @Test
    void aBtcWindowGivenToTheEthEngineIsRejectedAndSoIsAnEthWindowGivenToBtc() {
        ledger.ensureSeasonPack("alice", BET_TIME);
        int entriesBefore = ledger.entries().size();

        assertThatThrownBy(() -> eth.placeBet("alice", BTC_WINDOW, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(500), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to market BTC")
                .hasMessageContaining("this engine runs ETH");
        assertThatThrownBy(() -> btc.placeBet("alice", ETH_WINDOW, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(500), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belongs to market ETH")
                .hasMessageContaining("this engine runs BTC");

        assertThat(ledger.balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(ledger.entries()).hasSize(entriesBefore);
        assertThat(eth.betBook().betsFor(BTC_WINDOW.roundId())).isEmpty();
        assertThat(btc.betBook().betsFor(ETH_WINDOW.roundId())).isEmpty();
    }

    @Test
    void runRoundRejectsAWindowOfAnotherMarketWithoutTouchingPricesOrBets() {
        placeScenarioBets();

        assertThatThrownBy(() -> eth.runRound(BTC_WINDOW, pricesThatMustNotBeRead())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> btc.runRound(ETH_WINDOW, pricesThatMustNotBeRead())).isInstanceOf(IllegalArgumentException.class);

        // nothing resolved, nothing consumed: both rounds are still there to run properly
        assertThat(btc.betBook().betsFor(BTC_WINDOW.roundId())).hasSize(2);
        assertThat(eth.betBook().betsFor(ETH_WINDOW.roundId())).hasSize(2);
        assertThat(btc.crowdAccount().history()).isEmpty();
        assertThat(eth.crowdAccount().history()).isEmpty();
        assertThat(btc.runRound(BTC_WINDOW, BTC_PRICES).playerOutcomes()).hasSize(2);
        assertThat(eth.runRound(ETH_WINDOW, ETH_PRICES).playerOutcomes()).hasSize(2);
    }

    // --- the rate of the market ---

    @Test
    void theSameBetOnTheSameCandleLiquidatesAtTheEthRateButNotAtBtcsRate() {
        // bob: 20x long, 700 PLAY, on a candle (open 2694.40, low 2600.00) -- liquidation is 2611.92
        // at l = 0.02 but 2592.08 at BTC's l = 0.0125. Same lot-priced candle fed to the BTC engine.
        LedgerBook separateLedger = new LedgerBook();
        GameEngine btcEngine = new GameEngine(separateLedger, new CrowdAccount());
        GameEngine ethEngine = new GameEngine(separateLedger, new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH));
        btcEngine.placeBet("bob", BTC_WINDOW, Direction.UP, new Leverage(20), PlayAmount.ofWholePlay(700), BET_TIME);
        ethEngine.placeBet("bob", ETH_WINDOW, Direction.UP, new Leverage(20), PlayAmount.ofWholePlay(700), BET_TIME);

        RoundReport onBtcRate = btcEngine.runRound(BTC_WINDOW, ETH_PRICES);
        RoundReport onEthRate = ethEngine.runRound(ETH_WINDOW, ETH_PRICES);

        assertThat(onBtcRate.playerOutcomes()).singleElement().satisfies(o -> {
            assertThat(o.liquidated()).isFalse();
            assertThat(o.payout()).isEqualByComparingTo("469.30");
        });
        assertThat(onEthRate.playerOutcomes()).singleElement().satisfies(o -> {
            assertThat(o.liquidated()).isTrue();
            assertThat(o.payout()).isEqualByComparingTo("0.00");
        });
    }
}
