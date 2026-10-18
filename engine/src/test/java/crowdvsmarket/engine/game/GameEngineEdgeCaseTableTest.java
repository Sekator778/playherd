package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.LedgerBook;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The edge cases of a settled bet in one place, table-driven (the audit of 2026-10-18 asked whether liquidation, fee and payout
 * hold at the boundaries). What the neighbouring tests already cover is not repeated: the happy paths of
 * {@code GameEngineEconomyTest} (liquidation remainder at the extreme, one gap through the margin, one tie, the 20x cap, the fee
 * formula), {@code GameEngineTreasuryTest} (the cap follows the treasury balance) and {@code EconomyRulesFeesTest}. This class adds:
 *
 * <ul>
 *   <li>the stake-tier boundaries 250 / 250.001 / 500 / 500.001 / 1200 / 1200.001 PLAY at the highest leverage of the tier and one
 *       above it, both directions, both round lengths, through {@code placeBet};
 *   <li>a candle low (high) exactly on the liquidation price (liquidated) and one tick off it (not), both directions, both round
 *       lengths;
 *   <li>the extreme at the point where the margin is exactly gone, one tick inside it and beyond it (payout 0, never negative);
 *   <li>a tie (close == open) with the fees kept, both directions, the short round, the long round and the odds factor 3.0;
 *   <li>the payout cap at 20x the stake one tick below, at and above it, and the stake at which 20x meets 0.1 % of the treasury;
 *   <li>the 20 % fee guard one step below, at and above it for k = 1 / 2.2 / 3.0.
 * </ul>
 *
 * Test market "TST", entry 1000.00, caps 100 (short round) / 40 (long round), kappa 0.1, r_m = 1 bp. Worked by hand for stake 200:
 * short, L 100: Q = 20, l = 0.001, long liquidation 19800 / (20 * 0.999) = 990.99, short 20200 / (20 * 1.001) = 1008.99;
 * long round, L 40: Q = 8, l = 0.0025, long 7800 / (8 * 0.9975) = 977.44, short 8200 / (8 * 1.0025) = 1022.44.
 */
class GameEngineEdgeCaseTableTest {

    private static final long SHORT_ROUND = 180_000L;
    private static final long LONG_ROUND = 900_000L;
    private static final long START = Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    private static final Instant BET_TIME = Instant.ofEpochMilli(START - 60_000L);

    private static final MarketSpec TST = MarketSpec.of("TST", 25)
            .withEconomy(100, 40, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));
    /** An odds market: r_m = 2.5 bp, k = 3.0 in the long round, caps 10 / 10. */
    private static final MarketSpec ODDS = MarketSpec.of("ODD", 10)
            .withEconomy(10, 10, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.00025"), new BigDecimal("3.0"));

    private static RoundWindow window(MarketSpec spec, Interval interval) {
        return new RoundWindow(spec.id(), interval, START, START + (interval == Interval.HOURLY ? SHORT_ROUND : LONG_ROUND));
    }

    private static RoundPrices single(RoundWindow window, String open, String high, String low, String close) {
        Candle candle = new Candle(window.startMs(), window.endMs() - 1, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ZERO, 0L);
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow w) {
                return candle.open();
            }

            @Override
            public BigDecimal closePrice(RoundWindow w) {
                return candle.close();
            }

            @Override
            public List<Candle> path(RoundWindow w) {
                return List.of(candle);
            }
        };
    }

    private final LedgerBook ledger = new LedgerBook();

    private GameEngine engine(MarketSpec spec) {
        return new GameEngine(ledger, new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), spec));
    }

    private static void bet(GameEngine engine, MarketSpec spec, RoundWindow window, Direction direction, int leverage, PlayAmount stake) {
        engine.placeBet("alice", window, direction, new Leverage(leverage), stake,
                EconomyRules.fees(spec, window.interval(), leverage, stake), BET_TIME);
    }

    private static PlayAmount play(String amount) {
        return PlayAmount.fromMoney(new BigDecimal(amount));
    }

    private static PlayerRoundOutcome only(RoundReport report) {
        assertThat(report.playerOutcomes()).hasSize(1);
        return report.playerOutcomes().get(0);
    }

    // --- stake tier boundaries ---

    static Stream<Arguments> tierBoundaries() {
        // stake, highest leverage of the tier in the short round (cap 100), in the long round (cap 40)
        List<Object[]> rows = List.of(
                new Object[] {"250", 100, 40}, new Object[] {"250.001", 50, 20},
                new Object[] {"500", 50, 20}, new Object[] {"500.001", 20, 8},
                new Object[] {"1200", 20, 8}, new Object[] {"1200.001", 10, 4});
        return rows.stream().flatMap(r -> Stream.of(Direction.UP, Direction.DOWN).flatMap(d -> Stream.of(
                Arguments.of(r[0], Interval.HOURLY, d, r[1]), Arguments.of(r[0], Interval.DAILY, d, r[2]))));
    }

    @ParameterizedTest(name = "stake {0} {1} {2}: highest leverage {3}")
    @MethodSource("tierBoundaries")
    void theTierMaximumIsAcceptedAndOneAboveItIsRejected(String stake, Interval interval, Direction direction, int max) {
        GameEngine engine = engine(TST);
        RoundWindow window = window(TST, interval);
        assertThat(EconomyRules.maxLeverage(TST, interval, play(stake))).isEqualTo(max);
        assertThatThrownBy(() -> bet(engine, TST, window, direction, max + 1, play(stake)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[2, " + max + "]");
        assertThat(ledger.entries()).as("a rejected bet changes nothing").isEmpty();
        bet(engine, TST, window, direction, max, play(stake));
        assertThat(engine.betBook().betsFor(window.roundId())).hasSize(1);
        bet(engine, TST, window, direction, 2, play(stake)); // the replacement at the lowest leverage is accepted too
    }

    // --- the liquidation price is hit exactly / missed by one tick ---

    static Stream<Arguments> liquidationEdge() {
        // interval, direction, leverage, high, low, expected liquidated, expected payout
        return Stream.of(
                // short round, long position: liquidation price 990.99, remainder at the low = 200 + 20 * (low - 1000)
                Arguments.of(Interval.HOURLY, Direction.UP, 100, "1000.00", "990.99", true, "19.80"),
                Arguments.of(Interval.HOURLY, Direction.UP, 100, "1000.00", "991.00", false, "200.00"),
                // short round, short position: liquidation price 1008.99, remainder at the high = 200 - 20 * (high - 1000)
                Arguments.of(Interval.HOURLY, Direction.DOWN, 100, "1008.99", "1000.00", true, "20.20"),
                Arguments.of(Interval.HOURLY, Direction.DOWN, 100, "1008.98", "1000.00", false, "200.00"),
                // long round at 40x: l = 0.0025, long 977.44 (Q = 8), short 1022.44
                Arguments.of(Interval.DAILY, Direction.UP, 40, "1000.00", "977.44", true, "19.52"),
                Arguments.of(Interval.DAILY, Direction.UP, 40, "1000.00", "977.45", false, "200.00"),
                Arguments.of(Interval.DAILY, Direction.DOWN, 40, "1022.44", "1000.00", true, "20.48"),
                Arguments.of(Interval.DAILY, Direction.DOWN, 40, "1022.43", "1000.00", false, "200.00"));
    }

    @ParameterizedTest(name = "{0} {1} x{2} high {3} low {4}: liquidated {5}, payout {6}")
    @MethodSource("liquidationEdge")
    void aCandleExtremeExactlyOnTheLiquidationPriceLiquidatesOneTickOffItDoesNot(
            Interval interval, Direction direction, int leverage, String high, String low, boolean liquidated, String payout) {
        GameEngine engine = engine(TST);
        RoundWindow window = window(TST, interval);
        bet(engine, TST, window, direction, leverage, PlayAmount.ofWholePlay(200));
        // closes at the entry: a position that is not liquidated is a tie and gets the stake back
        PlayerRoundOutcome outcome = only(engine.runRound(window, single(window, "1000.00", high, low, "1000.00")));
        assertThat(outcome.liquidated()).isEqualTo(liquidated);
        assertThat(outcome.payout()).isEqualByComparingTo(payout);
    }

    // --- a gap through the liquidation price: the margin is gone at 990.00 / 1010.00 (Q = 20) ---

    static Stream<Arguments> gaps() {
        return Stream.of(
                Arguments.of(Direction.UP, "1000.00", "990.01", "0.20"),   // one tick inside the margin: 0.20 left
                Arguments.of(Direction.UP, "1000.00", "990.00", "0.00"),   // exactly gone
                Arguments.of(Direction.UP, "1000.00", "989.99", "0.00"),   // beyond: never negative
                Arguments.of(Direction.UP, "1000.00", "500.00", "0.00"),
                Arguments.of(Direction.DOWN, "1009.99", "1000.00", "0.20"),
                Arguments.of(Direction.DOWN, "1010.00", "1000.00", "0.00"),
                Arguments.of(Direction.DOWN, "1010.01", "1000.00", "0.00"),
                Arguments.of(Direction.DOWN, "2000.00", "1000.00", "0.00"));
    }

    @ParameterizedTest(name = "{0} high {1} low {2}: payout {3}")
    @MethodSource("gaps")
    void aGapThroughTheMarginPaysTheRemainderOrZeroNeverLess(Direction direction, String high, String low, String payout) {
        GameEngine engine = engine(TST);
        RoundWindow window = window(TST, Interval.HOURLY);
        bet(engine, TST, window, direction, 100, PlayAmount.ofWholePlay(200));
        PlayerRoundOutcome outcome = only(engine.runRound(window, single(window, "1000.00", high, low, "1000.00")));
        assertThat(outcome.liquidated()).isTrue();
        assertThat(outcome.payout()).isEqualByComparingTo(payout);
        assertThat(outcome.payout().signum()).isGreaterThanOrEqualTo(0);
        assertThat(ledger.balance("alice")).isEqualTo(
                PlayAmount.ofWholePlay(10_000).minus(PlayAmount.ofWholePlay(200)).minus(EconomyRules.fees(TST, Interval.HOURLY, 100, PlayAmount.ofWholePlay(200)).total())
                        .plus(PlayAmount.fromMoney(new BigDecimal(payout))));
    }

    // --- a tie: the stake comes back, both fees stay with the game ---

    static Stream<Arguments> ties() {
        // spec, interval, direction, leverage, fees kept (herd + network) = 200 * min(20%, k * r_m * L) + 0.1
        return Stream.of(
                Arguments.of(TST, Interval.HOURLY, Direction.UP, 100, "2.100"),     // 200 * 1 * 1 bp * 100 = 2.000
                Arguments.of(TST, Interval.HOURLY, Direction.DOWN, 100, "2.100"),
                Arguments.of(TST, Interval.DAILY, Direction.UP, 40, "1.860"),       // 200 * 2.2 * 1 bp * 40 = 1.760
                Arguments.of(TST, Interval.DAILY, Direction.DOWN, 40, "1.860"),
                Arguments.of(ODDS, Interval.HOURLY, Direction.UP, 10, "0.600"),     // 200 * 1 * 2.5 bp * 10 = 0.500
                Arguments.of(ODDS, Interval.DAILY, Direction.DOWN, 10, "1.600"));  // 200 * 3.0 * 2.5 bp * 10 = 1.500
    }

    @ParameterizedTest(name = "{0} {1} {2} x{3}: fees kept {4}")
    @MethodSource("ties")
    void aTieReturnsTheWholeStakeAndKeepsBothFees(MarketSpec spec, Interval interval, Direction direction, int leverage, String feesKept) {
        GameEngine engine = engine(spec);
        RoundWindow window = window(spec, interval);
        bet(engine, spec, window, direction, leverage, PlayAmount.ofWholePlay(200));
        PlayerRoundOutcome outcome = only(engine.runRound(window, single(window, "1000.00", "1000.40", "999.60", "1000.00")));
        assertThat(outcome.liquidated()).isFalse();
        assertThat(outcome.payout()).isEqualByComparingTo("200.00");
        assertThat(ledger.balance("alice")).isEqualTo(PlayAmount.ofWholePlay(10_000).minus(play(feesKept)));
        assertThat(outcome.fees().total()).isEqualTo(play(feesKept));
    }

    // --- the payout cap ---

    static Stream<Arguments> capAtTwentyTimesTheStake() {
        // stake 100 at x100, Q = 10: payout = 100 + 10 * move; 20 x stake = 2000 is reached at a move of 190
        return Stream.of(
                Arguments.of(Direction.UP, "1189.99", "1999.90"),
                Arguments.of(Direction.UP, "1190.00", "2000.00"),
                Arguments.of(Direction.UP, "1190.01", "2000.00"),
                Arguments.of(Direction.UP, "1900.00", "2000.00"),
                Arguments.of(Direction.DOWN, "810.01", "1999.90"),
                Arguments.of(Direction.DOWN, "810.00", "2000.00"),
                Arguments.of(Direction.DOWN, "809.99", "2000.00"));
    }

    @ParameterizedTest(name = "{0} close {1}: payout {2}")
    @MethodSource("capAtTwentyTimesTheStake")
    void aPayoutIsCappedAtTwentyTimesTheStakeOneTickBelowAtAndAbove(Direction direction, String close, String payout) {
        GameEngine engine = engine(TST);
        RoundWindow window = window(TST, Interval.HOURLY);
        bet(engine, TST, window, direction, 100, PlayAmount.ofWholePlay(100));
        RoundPrices prices = direction == Direction.UP
                ? single(window, "1000.00", close, "1000.00", close)
                : single(window, "1000.00", "1000.00", close, close);
        assertThat(only(engine.runRound(window, prices)).payout()).isEqualByComparingTo(payout);
    }

    static Stream<Arguments> stakeAgainstTreasury() {
        // treasury 99 998 100 PLAY -> 0.1 % = 99 998.100 PLAY, met by 20 x stake at the stake 4 999.905
        return Stream.of(
                Arguments.of("100", "99998100", "2000.000"),
                Arguments.of("4999.904", "99998100", "99998.080"),
                Arguments.of("4999.905", "99998100", "99998.100"),
                Arguments.of("4999.906", "99998100", "99998.100"),
                Arguments.of("100000", "99998100", "99998.100"),
                Arguments.of("100", "1000", "1.000"),
                Arguments.of("0.040", "1000", "0.800"),
                Arguments.of("0.050", "1000", "1.000"),
                Arguments.of("0.060", "1000", "1.000"));
    }

    @ParameterizedTest(name = "stake {0}, treasury {1}: at most {2}")
    @MethodSource("stakeAgainstTreasury")
    void theMostABetCanBePaidIsTheLowerOfTwentyTimesTheStakeAndAThousandthOfTheTreasury(String stake, String treasury, String maximum) {
        assertThat(EconomyRules.maxPayout(play(stake), PlayAmount.ofWholePlay(Long.parseLong(treasury)))).isEqualTo(play(maximum));
    }

    // --- the 20 % fee guard ---

    static Stream<Arguments> feeGuard() {
        // spec, interval, leverage, herd fee on a stake of 1000 PLAY; share = k * r_m * L
        return Stream.of(
                Arguments.of(TST, Interval.HOURLY, 1999, "199.900"),   // k 1,   1 bp: 19.99 %
                Arguments.of(TST, Interval.HOURLY, 2000, "200.000"),   // exactly 20 %
                Arguments.of(TST, Interval.HOURLY, 2001, "200.000"),   // clamped
                Arguments.of(TST, Interval.DAILY, 909, "199.980"),     // k 2.2, 1 bp: 19.998 %
                Arguments.of(TST, Interval.DAILY, 910, "200.000"),     // 20.02 % clamped
                Arguments.of(ODDS, Interval.HOURLY, 799, "199.750"),   // k 1,   2.5 bp: 19.975 %
                Arguments.of(ODDS, Interval.HOURLY, 800, "200.000"),
                Arguments.of(ODDS, Interval.DAILY, 266, "199.500"),    // k 3.0, 2.5 bp: 19.95 %
                Arguments.of(ODDS, Interval.DAILY, 267, "200.000"));   // 20.025 % clamped
    }

    @ParameterizedTest(name = "{0} {1} x{2}: herd fee {3}")
    @MethodSource("feeGuard")
    void theHerdFeeIsTwentyPercentAtMostAndExactlyTheFormulaBelowIt(MarketSpec spec, Interval interval, int leverage, String herd) {
        assertThat(EconomyRules.fees(spec, interval, leverage, PlayAmount.ofWholePlay(1_000)).herd()).isEqualTo(play(herd));
    }

    @Test
    void theFeeFactorsAreOneTwoPointTwoAndThree() {
        assertThat(TST.feeFactorFor(Interval.HOURLY)).isEqualByComparingTo("1");
        assertThat(TST.feeFactorFor(Interval.DAILY)).isEqualByComparingTo("2.2");
        assertThat(ODDS.feeFactorFor(Interval.HOURLY)).isEqualByComparingTo("1");
        assertThat(ODDS.feeFactorFor(Interval.DAILY)).isEqualByComparingTo("3.0");
    }
}
