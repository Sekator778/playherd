package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Economy stage A (D-92, docs/product/economy_implementation_plan.md A1-A5) through {@link GameEngine}: a test market "TST"
 * priced at 1000.00 with caps 100 (short round, {@code HOURLY}) / 40 (long round, {@code DAILY}), {@code κ = 0.1}, so
 * {@code l = 0.001} on the short round. Numbers worked by hand:
 *
 * <pre>
 * stake 200, L 100, entry 1000: Q = 200 * 100 / 1000 = 20
 *   long  liquidation price = (20000 - 200) / (20 * 0.999) = 990.99; low 990.50 -> remainder 200 + 20 * (990.50 - 1000) = 10.00
 *   short liquidation price = (20000 + 200) / (20 * 1.001) = 1008.99; high 1009.50 -> remainder 200 - 20 * 9.50 = 10.00
 * </pre>
 */
class GameEngineEconomyTest {

    private static final long SHORT_ROUND = 180_000L;
    private static final long LONG_ROUND = 900_000L;
    private static final long START = Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    private static final Instant BET_TIME = Instant.ofEpochMilli(START - 60_000L);
    private static final MarketSpec TST = MarketSpec.of("TST", 25)
            .withEconomy(100, 40, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));

    private static final RoundWindow SHORT = new RoundWindow("TST", Interval.HOURLY, START, START + SHORT_ROUND);
    private static final RoundWindow LONG = new RoundWindow("TST", Interval.DAILY, START, START + LONG_ROUND);

    private final LedgerBook ledger = new LedgerBook();
    private final CrowdAccount crowd = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), TST);
    private final GameEngine engine = new GameEngine(ledger, crowd);

    private static RoundPrices path(String open, String high, String low, String close) {
        Candle candle = new Candle(START, START + SHORT_ROUND - 1, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ZERO, 0L);
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow window) {
                return candle.open();
            }

            @Override
            public BigDecimal closePrice(RoundWindow window) {
                return candle.close();
            }

            @Override
            public List<Candle> path(RoundWindow window) {
                return List.of(candle);
            }
        };
    }

    private static PlayAmount play(long whole) {
        return PlayAmount.ofWholePlay(whole);
    }

    private void bet(String player, RoundWindow window, Direction direction, int leverage, long stakePlay) {
        PlayAmount stake = play(stakePlay);
        Fees fees = EconomyRules.fees(TST, window.interval(), leverage, stake);
        engine.placeBet(player, window, direction, new Leverage(leverage), stake, fees, BET_TIME);
    }

    private BigDecimal payoutOf(RoundReport report, String player) {
        return report.playerOutcomes().stream().filter(o -> o.playerId().equals(player)).findFirst().orElseThrow().payout();
    }

    // --- A1: liquidation returns the remainder at the candle extreme ---

    @Test
    void liquidatedLongGetsTheRemainderAtTheCandleLow() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        // closes back above the entry: the same-candle liquidation still wins, at the low
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1005.00", "990.50", "1004.00"));
        assertThat(report.playerOutcomes().get(0).liquidated()).isTrue();
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("10.00");
    }

    @Test
    void liquidatedShortGetsTheRemainderAtTheCandleHigh() {
        bet("alice", SHORT, Direction.DOWN, 100, 200);
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1009.50", "996.00", "996.00"));
        assertThat(report.playerOutcomes().get(0).liquidated()).isTrue();
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("10.00");
    }

    @Test
    void gapThroughTheMarginPaysZeroNotANegativeAmount() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        bet("bob", SHORT, Direction.DOWN, 100, 200);
        // low 900 / high 1100: equity at the extreme is 200 - 2000 < 0
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1100.00", "900.00", "1000.00"));
        assertThat(report.playerOutcomes()).allMatch(PlayerRoundOutcome::liquidated);
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("0");
        assertThat(payoutOf(report, "bob")).isEqualByComparingTo("0");
    }

    @Test
    void aLegacyBetStillLosesTheWholeStakeOnLiquidation() {
        // placed without fees = the pre-economy shape an old event replays as: original cap (25), l = 1/(2*25), payout 0
        engine.placeBet("alice", SHORT, Direction.UP, new Leverage(25), play(200), BET_TIME);
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1005.00", "970.00", "1004.00"));
        assertThat(report.playerOutcomes().get(0).liquidated()).isTrue();
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("0");
        assertThat(ledger.balance("alice")).isEqualTo(play(10_000 - 200));
        assertThat(ledger.entries()).noneMatch(e -> e.type() == LedgerEntryType.FEE_HERD || e.type() == LedgerEntryType.FEE_NETWORK);
    }

    @Test
    void aLiquidatedPositionHasTheMaintenanceRateOfItsRoundLength() {
        // 40x on the long round: l = 0.1 / 40 = 0.0025, liquidation price (1 - 1/40) / (1 - 0.0025) * 1000 = 977.44
        bet("alice", LONG, Direction.UP, 40, 200);
        RoundReport notReached = engine.runRound(LONG, path("1000.00", "1005.00", "977.50", "1004.00"));
        assertThat(notReached.playerOutcomes().get(0).liquidated()).isFalse();
        bet("alice", new RoundWindow("TST", Interval.DAILY, START + LONG_ROUND, START + 2 * LONG_ROUND), Direction.UP, 40, 200);
        RoundReport reached = engine.runRound(new RoundWindow("TST", Interval.DAILY, START + LONG_ROUND, START + 2 * LONG_ROUND),
                path("1000.00", "1005.00", "977.40", "1004.00"));
        assertThat(reached.playerOutcomes().get(0).liquidated()).isTrue();
    }

    // --- A2: caps per round length ---

    @Test
    void theCapIsPerRoundLength() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        assertThatThrownBy(() -> bet("alice", SHORT, Direction.UP, 101, 200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[2, 100]");
        bet("bob", LONG, Direction.UP, 40, 200);
        assertThatThrownBy(() -> bet("bob", LONG, Direction.UP, 41, 200))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[2, 40]");
    }

    @Test
    void specCapsAndRates() {
        assertThat(TST.capFor(Interval.HOURLY)).isEqualTo(100);
        assertThat(TST.capFor(Interval.DAILY)).isEqualTo(40);
        assertThat(TST.maintenanceRateFor(Interval.HOURLY)).isEqualByComparingTo("0.001");
        assertThat(TST.maintenanceRateFor(Interval.DAILY)).isEqualByComparingTo("0.0025");
        assertThat(MarketSpec.BTC.capFor(Interval.HOURLY)).isEqualTo(1200);
        assertThat(MarketSpec.BTC.capFor(Interval.DAILY)).isEqualTo(500);
        assertThat(MarketSpec.BTC.maxLeverage()).as("the original single cap, kept for replay").isEqualTo(40);
    }

    // --- A3: stake tiers ---

    @Test
    void theStakeTierLowersTheLeverageAllowedForABet() {
        assertThatThrownBy(() -> bet("alice", SHORT, Direction.UP, 51, 300)) // 300 PLAY: cap / 2 = 50
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[2, 50]").hasMessageContaining("300 PLAY");
        assertThat(ledger.entries()).as("a rejected bet changes nothing").isEmpty();
        bet("alice", SHORT, Direction.UP, 50, 300);
    }

    @Test
    void tiersFollowThePlan() {
        MarketSpec btc = MarketSpec.BTC; // 1200 / 500
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(100))).isEqualTo(1200);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(250))).isEqualTo(1200);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, new PlayAmount(250_001))).isEqualTo(600);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(500))).isEqualTo(600);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(501))).isEqualTo(240);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(1_200))).isEqualTo(240);
        assertThat(EconomyRules.maxLeverage(btc, Interval.HOURLY, play(1_201))).isEqualTo(120);
        assertThat(EconomyRules.maxLeverage(btc, Interval.DAILY, play(100))).isEqualTo(500);
        assertThat(EconomyRules.maxLeverage(btc, Interval.DAILY, play(999))).isEqualTo(100);
        assertThat(EconomyRules.maxLeverage(btc, Interval.DAILY, play(5_000))).isEqualTo(50);
        MarketSpec odds = MarketSpec.of("PM1", 5);
        assertThat(EconomyRules.maxLeverage(odds, Interval.HOURLY, play(100))).isEqualTo(5);
        assertThat(EconomyRules.maxLeverage(odds, Interval.HOURLY, play(300))).isEqualTo(2);
        assertThat(EconomyRules.maxLeverage(odds, Interval.HOURLY, play(5_000))).as("never below 2").isEqualTo(2);
    }

    // --- A4: fees ---

    @Test
    void theHerdFeeScalesWithLeverageUpToTheTwentyPercentGuard() {
        // r_m = 1 bp: fee = stake * min(20%, 0.0001 * L) in the short round
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 10, play(1_000)).herd()).isEqualTo(play(1)); // 0.1% of 1000
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 50, play(1_000)).herd()).isEqualTo(play(5));
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 100, play(1_000)).herd()).isEqualTo(play(10)); // 1%
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 500, play(1_000)).herd()).as("no cap below 20%").isEqualTo(play(50));
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 3_000, play(1_000)).herd()).as("capped at 20%").isEqualTo(play(200));
        // the long round: k = 2.2
        assertThat(EconomyRules.fees(TST, Interval.DAILY, 100, play(1_000)).herd()).isEqualTo(play(22));
        // BTC r_m = 0.92 bp
        assertThat(EconomyRules.fees(MarketSpec.BTC, Interval.HOURLY, 100, play(1_000)).herd()).isEqualTo(new PlayAmount(9_200));
        assertThat(EconomyRules.fees(MarketSpec.BTC, Interval.HOURLY, 1_200, play(1_000)).herd()).isEqualTo(new PlayAmount(110_400));
    }

    @Test
    void feesRoundUpToAThousandthAndHaveAMinimum() {
        // 100.001 * 0.0003 = 0.0300003 -> 0.031
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 3, new PlayAmount(100_001)).herd()).isEqualTo(new PlayAmount(31));
        // 100 * 0.0002 = 0.02 exactly
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 2, play(100)).herd()).isEqualTo(new PlayAmount(20));
        // a tiny product never rounds to 0: minimum 0.001
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 2, new PlayAmount(1)).herd()).isEqualTo(new PlayAmount(1));
        assertThat(EconomyRules.fees(TST, Interval.HOURLY, 2, play(100)).network()).isEqualTo(new PlayAmount(100)); // 0.1 PLAY
    }

    @Test
    void aBetDebitsTheStakeAndBothFeesAndRecordsThemInTheLedger() {
        bet("alice", SHORT, Direction.UP, 100, 200); // herd fee 200 * 1% = 2.000, network 0.100
        assertThat(ledger.balance("alice")).isEqualTo(new PlayAmount(10_000_000 - 200_000 - 2_000 - 100));
        List<LedgerEntry> entries = ledger.entries().stream().filter(e -> e.roundId() != null).toList();
        assertThat(entries).extracting(LedgerEntry::type)
                .containsExactly(LedgerEntryType.STAKE_DEBIT, LedgerEntryType.FEE_HERD, LedgerEntryType.FEE_NETWORK);
        assertThat(entries).extracting(LedgerEntry::delta)
                .containsExactly(new PlayAmount(-200_000), new PlayAmount(-2_000), new PlayAmount(-100));
        assertThat(entries).allMatch(e -> e.roundId().equals(SHORT.roundId()));
    }

    @Test
    void theBalanceMustCoverTheStakePlusTheFees() {
        // 9 950 + 200.0 + 0.1 would be fine; 9 999.95 stake + fees is not
        assertThatThrownBy(() -> engine.placeBet("alice", SHORT, Direction.UP, new Leverage(2), new PlayAmount(9_999_950),
                new Fees(new PlayAmount(10), new PlayAmount(100)), BET_TIME))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds balance");
    }

    @Test
    void cancellingBeforeTheRoundRefundsTheStakeAndBothFees() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        engine.cancelBet("alice", SHORT, BET_TIME);
        assertThat(ledger.balance("alice")).isEqualTo(play(10_000));
        assertThat(ledger.entries()).extracting(LedgerEntry::type).contains(
                LedgerEntryType.STAKE_REFUND, LedgerEntryType.FEE_HERD_REFUND, LedgerEntryType.FEE_NETWORK_REFUND);
    }

    @Test
    void replacingABetRefundsTheOldOnesFees() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        bet("alice", SHORT, Direction.DOWN, 10, 200); // herd fee 200 * 0.1% = 0.2
        assertThat(ledger.balance("alice")).isEqualTo(new PlayAmount(10_000_000 - 200_000 - 200 - 100));
    }

    @Test
    void aCancelledRoundRefundsTheStakeAndBothFees() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        engine.betBook().betsFor(SHORT.roundId()).forEach(b -> engine.refundBet(b, BET_TIME));
        assertThat(ledger.balance("alice")).isEqualTo(play(10_000));
    }

    @Test
    void aTieReturnsTheStakeButNotTheFees() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1000.50", "999.50", "1000.00"));
        assertThat(report.playerOutcomes().get(0).liquidated()).isFalse();
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("200.00");
        assertThat(ledger.balance("alice")).isEqualTo(new PlayAmount(10_000_000 - 2_000 - 100));
    }

    // --- A5: payout cap ---

    @Test
    void aPayoutIsCappedAtTwentyTimesTheStake() {
        bet("alice", SHORT, Direction.UP, 100, 100);
        // Q = 10, +300 -> pnl 3000, payout would be 3100
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1300.00", "1000.00", "1300.00"));
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("2000.00");
    }

    @Test
    void aPayoutIsCappedAtPointOnePercentOfTheTreasuryConstant() {
        assertThat(EconomyRules.maxPayout(play(10_000))).isEqualTo(new PlayAmount(99_998_100));
        assertThat(EconomyRules.maxPayout(play(100))).isEqualTo(play(2_000));
        bet("alice", SHORT, Direction.UP, 10, 5_000); // tier cap / 10 = 10; 20 x 5 000 = 100 000 > 99 998.1
        // Q = 50, price x3 -> pnl 100 000
        RoundReport report = engine.runRound(SHORT, path("1000.00", "3000.00", "1000.00", "3000.00"));
        assertThat(payoutOf(report, "alice")).isEqualByComparingTo("99998.10");
    }

    // --- the crowd account follows the same rules ---

    @Test
    void aLiquidatedCrowdPositionKeepsTheRemainderToo() {
        bet("alice", SHORT, Direction.UP, 100, 200);
        RoundReport report = engine.runRound(SHORT, path("1000.00", "1005.00", "990.50", "1004.00"));
        // crowd margin = 10% of 100 000 = 10 000 at the stake-weighted 100x: Q = 1000, remainder = 10 000 + 1000 * (990.50 - 1000) = 500
        assertThat(report.crowdResult().liquidated()).isTrue();
        assertThat(report.crowdResult().capitalAfter()).isEqualByComparingTo("90500.00");
    }

    @Test
    void theCrowdLeverageIsClampedToTheCapOfTheRoundLength() {
        engine.placeBet("alice", LONG, Direction.UP, new Leverage(40), play(200), EconomyRules.fees(TST, Interval.HOURLY, 40, play(200)), BET_TIME);
        RoundReport report = engine.runRound(LONG, path("1000.00", "1001.00", "999.00", "1000.00"));
        assertThat(report.crowdResult().leverage()).isEqualTo(40);
    }
}
