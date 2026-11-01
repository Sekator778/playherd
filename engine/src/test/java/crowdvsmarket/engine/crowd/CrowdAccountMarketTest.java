package crowdvsmarket.engine.crowd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.execution.ExecutionAdapter;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link CrowdAccount} per market (D-69): it opens its position through the market-aware
 * {@code ExecutionAdapter.openPosition(spec, …)}, with that market's maintenance margin rate, and
 * never exceeds that market's leverage cap.
 */
class CrowdAccountMarketTest {

    private static final MarketSpec ETH = MarketSpec.of("ETH", 25); // l = 0.02
    private static final BigDecimal ENTRY = new BigDecimal("2694.40");

    /** Paper trading that remembers what it was asked to open and in which market. */
    private static final class RecordingAdapter implements ExecutionAdapter {
        private final PaperTradingAdapter delegate = new PaperTradingAdapter();
        final List<MarketSpec> specs = new ArrayList<>();
        final List<Position> positions = new ArrayList<>();

        @Override
        public Position openPosition(MarketSpec spec, String userId, String roundId, Direction direction,
                Leverage leverage, BigDecimal margin, BigDecimal entryPrice) {
            Position position = delegate.openPosition(spec, userId, roundId, direction, leverage, margin, entryPrice);
            specs.add(spec);
            positions.add(position);
            return position;
        }

        @Override
        public Optional<PositionSimulator.Outcome> getPosition(Position position) {
            return delegate.getPosition(position);
        }

        @Override
        public PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen) {
            return delegate.closePosition(position, priceHistorySinceOpen);
        }
    }

    private static Bet bet(String player, Direction direction, int leverage, long stakePlay) {
        return new Bet(player, "r1", direction, new Leverage(leverage), PlayAmount.ofWholePlay(stakePlay));
    }

    private static Candle candle(String open, String high, String low, String close) {
        return new Candle(0L, 3_600_000L - 1, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ZERO, 0L);
    }

    // --- which market an account is ---

    @Test
    void theShorterConstructorsAreTheBtcAccount() {
        assertThat(new CrowdAccount().spec()).isEqualTo(MarketSpec.BTC);
        assertThat(new CrowdAccount(new BigDecimal("5000.00")).spec()).isEqualTo(MarketSpec.BTC);
        assertThat(new CrowdAccount(new BigDecimal("5000.00"), new PaperTradingAdapter()).spec()).isEqualTo(MarketSpec.BTC);
    }

    @Test
    void theMarketConstructorKeepsItsSpecAndCapital() {
        CrowdAccount account = new CrowdAccount(new BigDecimal("2500.00"), new PaperTradingAdapter(), ETH);

        assertThat(account.spec()).isEqualTo(ETH);
        assertThat(account.capital()).isEqualByComparingTo("2500.00");
    }

    @Test
    void aMarketIsRequired() {
        assertThatThrownBy(() -> new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- leverage cap ---

    @Test
    void aTwentyFiveTimesMarketNeverProducesAFortyTimesCrowdPosition() {
        RecordingAdapter adapter = new RecordingAdapter();
        CrowdAccount account = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, adapter, ETH);
        // Bets above the cap can only be built by hand (GameEngine.placeBet rejects them): the
        // account must clamp on its own. Weighted average (40*100 + 30*100) / 200 = 35.
        List<Bet> bets = List.of(bet("alice", Direction.UP, 40, 100), bet("bob", Direction.UP, 30, 100));

        CrowdAccount.RoundResult result = account.processRound("ETH-1h-0", bets, ENTRY,
                List.of(candle("2694.40", "2700.00", "2690.00", "2695.00")));

        assertThat(result.leverage()).isEqualTo(25);
        assertThat(adapter.positions).singleElement().satisfies(p -> assertThat(p.leverage().value()).isEqualTo(25));
    }

    @Test
    void theSameBetsOnTheBtcAccountAreOnlyClampedAtForty() {
        CrowdAccount account = new CrowdAccount();
        List<Bet> bets = List.of(bet("alice", Direction.UP, 40, 100), bet("bob", Direction.UP, 30, 100));

        CrowdAccount.RoundResult result = account.processRound("1h-0", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.leverage()).isEqualTo(35);
    }

    @Test
    void aWeightedLeverageInsideTheCapIsUntouched() {
        CrowdAccount account = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH);
        // weighted average (10*300 + 20*100) / 400 = 12.5 -> HALF_UP -> 13, as for BTC (D-14)
        List<Bet> bets = List.of(bet("alice", Direction.UP, 10, 300), bet("bob", Direction.UP, 20, 100));

        CrowdAccount.RoundResult result = account.processRound("ETH-1h-0", bets, ENTRY,
                List.of(candle("2694.40", "2700.00", "2690.00", "2695.00")));

        assertThat(result.leverage()).isEqualTo(13);
    }

    @Test
    void aWeightedLeverageRoundingUpPastTheCapIsClampedToTheCap() {
        CrowdAccount account = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH);
        // weighted average (25*100 + 26*100) / 200 = 25.5 -> HALF_UP -> 26 -> clamped to the cap 25
        // (a bet at 26x is again only reachable by hand)
        List<Bet> bets = List.of(bet("alice", Direction.UP, 25, 100), bet("bob", Direction.UP, 26, 100));

        CrowdAccount.RoundResult result = account.processRound("ETH-1h-0", bets, ENTRY,
                List.of(candle("2694.40", "2700.00", "2690.00", "2695.00")));

        assertThat(result.leverage()).isEqualTo(25);
    }

    // --- the account trades in its own market ---

    @Test
    void thePositionIsOpenedInTheAccountsMarketWithThatMarketsRate() {
        RecordingAdapter adapter = new RecordingAdapter();
        CrowdAccount account = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, adapter, ETH);

        account.processRound("ETH-1h-0", List.of(bet("alice", Direction.UP, 20, 300)), ENTRY,
                List.of(candle("2694.40", "2700.00", "2690.00", "2695.00")));

        assertThat(adapter.specs).containsExactly(ETH);
        Position position = adapter.positions.get(0);
        assertThat(position.liquidationPrice()).isEqualTo(LiquidationCalculator.liquidationPrice(
                position.size(), ENTRY, new Leverage(20), Direction.UP.toSide(), new BigDecimal("0.02")));
        assertThat(position.liquidationPrice()).isNotEqualTo(LiquidationCalculator.liquidationPrice(
                position.size(), ENTRY, new Leverage(20), Direction.UP.toSide()));
    }

    @Test
    void theDefaultAccountOpensInBtc() {
        RecordingAdapter adapter = new RecordingAdapter();
        CrowdAccount account = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, adapter);

        account.processRound("1h-0", List.of(bet("alice", Direction.UP, 20, 300)), new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(adapter.specs).containsExactly(MarketSpec.BTC);
    }

    @Test
    void theCrowdAccountOnlyEverCallsTheMarketMethodNeverTheBtcOverloadOfAnAdapter() {
        // ExecutionAdapter's six-argument openPosition is a convenience for callers; an adapter that
        // overrides it with logic of its own is bypassed by the engine, which always names its market.
        List<String> calls = new ArrayList<>();
        PaperTradingAdapter paper = new PaperTradingAdapter();
        ExecutionAdapter adapter = new ExecutionAdapter() {
            @Override
            public Position openPosition(MarketSpec spec, String userId, String roundId, Direction direction,
                    Leverage leverage, BigDecimal margin, BigDecimal entryPrice) {
                calls.add("market:" + spec.id());
                return paper.openPosition(spec, userId, roundId, direction, leverage, margin, entryPrice);
            }

            @Override
            public Position openPosition(String userId, String roundId, Direction direction, Leverage leverage,
                    BigDecimal margin, BigDecimal entryPrice) {
                calls.add("btc-overload");
                return paper.openPosition(userId, roundId, direction, leverage, margin, entryPrice);
            }

            @Override
            public Optional<PositionSimulator.Outcome> getPosition(Position position) {
                return paper.getPosition(position);
            }

            @Override
            public PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen) {
                return paper.closePosition(position, priceHistorySinceOpen);
            }
        };
        List<Candle> path = List.of(candle("2694.40", "2700.00", "2690.00", "2695.00"));

        new CrowdAccount(CrowdAccount.STARTING_CAPITAL, adapter)
                .processRound("1h-0", List.of(bet("alice", Direction.UP, 20, 300)), ENTRY, path);
        new CrowdAccount(CrowdAccount.STARTING_CAPITAL, adapter, ETH)
                .processRound("ETH-1h-0", List.of(bet("alice", Direction.UP, 20, 300)), ENTRY, path);

        assertThat(calls).containsExactly("market:BTC", "market:ETH");
    }

    @Test
    void theSameCandleLiquidatesTheHigherRateMarketAndNotTheLowerRateOne() {
        // 20x long at 2694.40: liquidated at 2611.9 under l = 0.02 (a 25x market) but only at 2592.0
        // under BTC's l = 0.0125 — a candle whose low is 2600.00 sits between the two.
        List<Bet> bets = List.of(bet("alice", Direction.UP, 20, 300));
        List<Candle> path = List.of(candle("2694.40", "2700.00", "2600.00", "2650.00"));

        CrowdAccount.RoundResult onEth = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), ETH)
                .processRound("ETH-1h-0", bets, ENTRY, path);
        CrowdAccount.RoundResult onBtc = new CrowdAccount()
                .processRound("1h-0", bets, ENTRY, path);

        assertThat(onEth.liquidated()).isTrue();
        assertThat(onEth.capitalAfter()).isEqualByComparingTo(onEth.capitalBefore().subtract(onEth.margin()));
        assertThat(onBtc.liquidated()).isFalse();
    }
}
