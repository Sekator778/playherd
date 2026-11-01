package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-106, docs/product/fixed_payout_rules.md: the fixed-payout mode through {@link GameEngine}. A right direction pays 2 x stake, a wrong
 * one 0, a tie (close equal to open) the stake; the platform fee (3% of the stake, rounded up to 0.001 PLAY) and the network fee (0.1 PLAY)
 * are charged on top of the stake and kept on every outcome. Amounts are exact mojos.
 */
class GameEngineFixedPayoutTest {

    private static final long ROUND = 180_000L;
    private static final long START = Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    private static final MarketSpec TST = MarketSpec.of("TST", 25)
            .withEconomy(100, 40, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));
    private static final RoundWindow WINDOW = new RoundWindow("TST", Interval.HOURLY, START, START + ROUND);
    private static final Instant BEFORE = Instant.ofEpochMilli(START - 60_000L);

    private static PlayAmount play(String amount) {
        return PlayAmount.fromMoney(new BigDecimal(amount));
    }

    private static RoundPrices prices(String open, String high, String low, String close) {
        Candle candle = new Candle(START, START + ROUND - 1, new BigDecimal(open), new BigDecimal(high),
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

    private static final RoundPrices UP = prices("1000.00", "1010.00", "1000.00", "1010.00");
    private static final RoundPrices DOWN = prices("1000.00", "1000.00", "990.00", "990.00");
    private static final RoundPrices FLAT = prices("1000.00", "1010.00", "990.00", "1000.00");

    private static GameEngine newEngine(LedgerBook ledger) {
        return new GameEngine(ledger, new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), TST));
    }

    /** The balance change of {@code player} over the placing and settling of one fixed bet of {@code stake}. */
    private long netMojos(Direction direction, String stake, RoundPrices prices) {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        PlayAmount amount = play(stake);
        ledger.ensureSeasonPack("alice", BEFORE);
        PlayAmount start = ledger.balance("alice");
        engine.placeFixedBet("alice", WINDOW, direction, amount, EconomyRules.fixedFees(amount), BEFORE);
        engine.runRound(WINDOW, prices);
        return ledger.balance("alice").mojos() - start.mojos();
    }

    @Test
    void thePlatformFeeIsThreePercentRoundedUpToAThousandthAndTheNetworkFeeATenth() {
        assertThat(EconomyRules.fixedFees(play("100")).herd()).isEqualTo(play("3.000"));
        assertThat(EconomyRules.fixedFees(play("100")).network()).isEqualTo(play("0.100"));
        assertThat(EconomyRules.fixedFees(play("1")).herd()).isEqualTo(play("0.030"));
        assertThat(EconomyRules.fixedFees(play("33.333")).herd()).as("0.99999 rounds up to 1.000").isEqualTo(play("1.000"));
        assertThat(EconomyRules.fixedFees(play("33.334")).herd()).as("1.00002 rounds up to 1.001").isEqualTo(play("1.001"));
        assertThat(EconomyRules.fixedFees(play("0.001")).herd()).as("0.00003 rounds up to the smallest unit").isEqualTo(play("0.001"));
        assertThat(EconomyRules.fixedFees(play("250.5")).herd()).isEqualTo(play("7.515"));
    }

    @Test
    void aRightGuessPaysTwiceTheStakeAndTheFeesAreKept() {
        // stake 100: debit 103.1, payout 200 -> +96.9
        assertThat(netMojos(Direction.UP, "100", UP)).isEqualTo(96_900L);
        assertThat(netMojos(Direction.DOWN, "100", DOWN)).isEqualTo(96_900L);
    }

    @Test
    void aWrongGuessPaysNothingAndTheFeesAreKept() {
        assertThat(netMojos(Direction.DOWN, "100", UP)).isEqualTo(-103_100L);
        assertThat(netMojos(Direction.UP, "100", DOWN)).isEqualTo(-103_100L);
    }

    @Test
    void aTieGivesTheStakeBackAndKeepsTheFees() {
        assertThat(netMojos(Direction.UP, "100", FLAT)).isEqualTo(-3_100L);
        assertThat(netMojos(Direction.DOWN, "100", FLAT)).isEqualTo(-3_100L);
    }

    @Test
    void theOutcomeCarriesTheModeThePayoutAndNoLiquidation() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        PlayAmount stake = play("140");
        engine.placeFixedBet("alice", WINDOW, Direction.UP, stake, EconomyRules.fixedFees(stake), BEFORE);
        PlayerRoundOutcome outcome = engine.runRound(WINDOW, DOWN).playerOutcomes().get(0);
        assertThat(outcome.mode()).isEqualTo(BetMode.FIXED);
        assertThat(outcome.liquidated()).isFalse();
        assertThat(outcome.payout()).isEqualByComparingTo("0");
        assertThat(outcome.fees()).isEqualTo(new Fees(play("4.200"), play("0.100")));
        assertThat(outcome.liquidation()).isNull();
    }

    @Test
    void aFixedBetIsNeverLiquidatedWhateverThePathDoes() {
        // a wild path: -50% then back above the open; a percentage bet at x25 would be liquidated, a fixed one just wins
        assertThat(netMojos(Direction.UP, "100", prices("1000.00", "1020.00", "500.00", "1020.00"))).isEqualTo(96_900L);
    }

    @Test
    void theBooksBalanceAfterTheCutoverOnEveryOutcome() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        ledger.activateEconomy(PlayAmount.ofWholePlay(1_000_000), Instant.ofEpochMilli(START - 600_000L));
        for (RoundPrices prices : List.of(UP, DOWN, FLAT)) {
            PlayAmount stake = play("100");
            engine.placeFixedBet("alice", WINDOW, Direction.UP, stake, EconomyRules.fixedFees(stake), BEFORE);
            engine.placeFixedBet("bob", WINDOW, Direction.DOWN, stake, EconomyRules.fixedFees(stake), BEFORE);
            engine.runRound(WINDOW, prices);
            assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        }
    }

    @Test
    void cancellingOrReplacingAFixedBetRefundsTheStakeAndBothFees() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        ledger.ensureSeasonPack("alice", BEFORE);
        PlayAmount start = ledger.balance("alice");
        PlayAmount stake = play("100");
        engine.placeFixedBet("alice", WINDOW, Direction.UP, stake, EconomyRules.fixedFees(stake), BEFORE);
        assertThat(ledger.balance("alice")).isEqualTo(start.minus(play("103.100")));
        // replaced by a bigger one of the other side: the first is refunded in full
        PlayAmount bigger = play("200");
        engine.placeFixedBet("alice", WINDOW, Direction.DOWN, bigger, EconomyRules.fixedFees(bigger), BEFORE);
        assertThat(ledger.balance("alice")).isEqualTo(start.minus(play("206.100")));
        assertThat(engine.betBook().count(WINDOW.roundId())).isEqualTo(1);
        engine.cancelBet("alice", WINDOW, BEFORE);
        assertThat(ledger.balance("alice")).isEqualTo(start);
    }

    @Test
    void aFixedBetReplacesAPercentageBetAndTheOtherWayRound() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        ledger.ensureSeasonPack("alice", BEFORE);
        PlayAmount start = ledger.balance("alice");
        PlayAmount stake = play("100");
        engine.placeBet("alice", WINDOW, Direction.UP, new Leverage(10), stake, EconomyRules.fees(TST, Interval.HOURLY, 10, stake), BEFORE);
        engine.placeFixedBet("alice", WINDOW, Direction.UP, stake, EconomyRules.fixedFees(stake), BEFORE);
        assertThat(ledger.balance("alice")).isEqualTo(start.minus(play("103.100")));
        assertThat(engine.betBook().find(WINDOW.roundId(), "alice").orElseThrow().mode()).isEqualTo(BetMode.FIXED);
        engine.placeBet("alice", WINDOW, Direction.UP, new Leverage(10), stake, EconomyRules.fees(TST, Interval.HOURLY, 10, stake), BEFORE);
        assertThat(engine.betBook().find(WINDOW.roundId(), "alice").orElseThrow().mode()).isEqualTo(BetMode.PERCENTAGE);
    }

    @Test
    void aMixedRoundSettlesEachBetByItsOwnRulesAndTheCrowdAccountSeesBoth() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger);
        for (String p : List.of("fixedUp", "fixedDown", "pctUp")) {
            ledger.ensureSeasonPack(p, BEFORE);
        }
        PlayAmount start = ledger.balance("pctUp");
        PlayAmount stake = play("100");
        engine.placeFixedBet("fixedUp", WINDOW, Direction.UP, stake, EconomyRules.fixedFees(stake), BEFORE);
        engine.placeFixedBet("fixedDown", WINDOW, Direction.DOWN, stake, EconomyRules.fixedFees(stake), BEFORE);
        engine.placeBet("pctUp", WINDOW, Direction.UP, new Leverage(10), stake, EconomyRules.fees(TST, Interval.HOURLY, 10, stake), BEFORE);

        RoundReport report = engine.runRound(WINDOW, UP); // +1%

        PlayerRoundOutcome fixedUp = outcome(report, "fixedUp");
        PlayerRoundOutcome fixedDown = outcome(report, "fixedDown");
        PlayerRoundOutcome pctUp = outcome(report, "pctUp");
        assertThat(fixedUp.payout()).isEqualByComparingTo("200");
        assertThat(fixedDown.payout()).isEqualByComparingTo("0");
        assertThat(pctUp.mode()).isEqualTo(BetMode.PERCENTAGE);
        assertThat(pctUp.payout()).as("100 + 100 x 10 x 1%").isEqualByComparingTo("110");
        assertThat(ledger.balance("pctUp").mojos() - start.mojos())
                .isEqualTo(110_000L - 100_000L - EconomyRules.fees(TST, Interval.HOURLY, 10, stake).total().mojos());
        // 200 UP (one fixed, one percentage) against 100 DOWN: the crowd trades UP
        assertThat(report.crowdResult().traded()).isTrue();
        assertThat(report.crowdResult().direction()).isEqualTo(Direction.UP);
        assertThat(engine.betBook().count(WINDOW.roundId())).isZero();
    }

    @Test
    void replayingTheBetsGivesTheSameBalances() {
        LedgerBook live = new LedgerBook();
        GameEngine liveEngine = newEngine(live);
        LedgerBook replay = new LedgerBook();
        GameEngine replayEngine = newEngine(replay);
        PlayAmount stake = play("100.501");
        Fees fees = EconomyRules.fixedFees(stake);
        liveEngine.placeFixedBet("alice", WINDOW, Direction.DOWN, stake, fees, BEFORE);
        replayEngine.replayBet("alice", WINDOW, Direction.DOWN, Bet.FIXED_LEVERAGE, stake, fees, null, BetMode.FIXED, BEFORE);
        liveEngine.runRound(WINDOW, DOWN);
        replayEngine.runRound(WINDOW, DOWN);
        assertThat(replay.balance("alice")).isEqualTo(live.balance("alice"));
        assertThat(replay.entries().size()).isEqualTo(live.entries().size());
    }

    @Test
    void aFixedBetWithoutFeesOrWithLiquidationParametersIsRefused() {
        assertThatThrownBy(() -> new Bet("a", "r", Direction.UP, Bet.FIXED_LEVERAGE, play("1"), null, null, BetMode.FIXED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bet("a", "r", Direction.UP, new Leverage(5), play("1"), Fees.ZERO, null, BetMode.FIXED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PlayerRoundOutcome outcome(RoundReport report, String playerId) {
        return report.playerOutcomes().stream().filter(o -> o.playerId().equals(playerId)).findFirst().orElseThrow();
    }
}
