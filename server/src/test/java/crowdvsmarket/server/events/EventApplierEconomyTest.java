package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Economy stage A (Р-92) and the event log: a {@code BetPlaced} written before the change carries no fee fields and must
 * replay exactly as it always did (no fees, the original settlement rules), one written after carries its fees and the
 * replay charges exactly those. Plain objects, no database: the applier and the hub are wired by hand.
 *
 * <p>Market "TST" (not one of the plan's twelve, so its stage-A caps default to its original single cap): cap 25 for both
 * round lengths, {@code l = 1/50} for a legacy bet and {@code l = 0.1 / 25 = 0.004} under the new rules.
 * Entry 1000.00, a bet of 200 PLAY at 25x long: legacy liquidation price {@code 1000 * (1 - 1/25) / (1 - 0.02) = 979.59}.
 */
class EventApplierEconomyTest {

    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 180_000L;

    private EngineHub hub;
    private EventApplier applier;

    @BeforeEach
    void wire() {
        hub = new EngineHub();
        hub.ensureMarket(tst(25));
        applier = new EventApplier();
        applier.hub = hub;
        applier.betFeed = new BetFeed();
        applier.players = new PlayerDirectory();
        applier.apply(new GameEvent.PlayerRegistered("p1", START - 1_000));
    }

    private static Market tst(int maxLeverage) {
        return new Market("TST", MarketKind.PERP, "crypto", "TST", "Test", null, null, "synthetic", "TST", null,
                maxLeverage, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null);
    }

    private static GameEvent.RoundResolved resolved(String low, String close) {
        return new GameEvent.RoundResolved(Interval.HOURLY, START, END, "1000.00", close,
                List.of(new GameEvent.CandleData(START, END - 1, "1000.00", "1005.00", low, close, "0", 0)), END, "TST");
    }

    private long balanceMojos() {
        return hub.ledger().balance("p1").mojos();
    }

    @Test
    void anOldBetPlacedReplaysWithNoFeesAndLosesTheWholeStakeWhenLiquidated() {
        // the shape written before the economy change: no fee keys at all
        GameEvent.BetPlaced old = new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L,
                START - 500, "TST");
        assertThat(old.feeHerdMojos()).isNull();
        assertThat(old.feeNetMojos()).isNull();
        assertThat(old.hasFees()).isFalse();

        applier.apply(old);
        assertThat(balanceMojos()).as("the stake only, no fees").isEqualTo(10_000_000L - 200_000L);

        RoundReport report = (RoundReport) applier.apply(resolved("970.00", "1004.00"));

        assertThat(report.playerOutcomes().get(0).liquidated()).isTrue();
        assertThat(report.playerOutcomes().get(0).payout()).isEqualByComparingTo("0");
        assertThat(report.playerOutcomes().get(0).fees()).isNull();
        assertThat(report.economyRules()).isFalse();
        assertThat(balanceMojos()).isEqualTo(10_000_000L - 200_000L);
        assertThat(hub.ledger().entries()).extracting(e -> e.type())
                .doesNotContain(LedgerEntryType.FEE_HERD, LedgerEntryType.FEE_NETWORK);
    }

    @Test
    void anOldBetPlacedCancelledRefundsOnlyTheStake() {
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 10, 100_000L, START - 500));
        applier.apply(new GameEvent.BetCancelled("p1", Interval.HOURLY, START, END, START - 400));
        assertThat(balanceMojos()).isEqualTo(10_000_000L);
        assertThat(hub.ledger().entries()).extracting(e -> e.type())
                .doesNotContain(LedgerEntryType.FEE_HERD_REFUND, LedgerEntryType.FEE_NETWORK_REFUND);
    }

    @Test
    void aBetPlacedWithFeesReplaysTheStoredFeesAndFollowsTheNewRules() {
        // 25x at the stage-A rules: l = 0.004, liquidation price 1000 * (1 - 1/25) / (1 - 0.004) = 963.86;
        // low 970.00 does NOT liquidate it any more (it did under the legacy l = 1/50)
        GameEvent.BetPlaced placed = new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L,
                START - 500, "TST", 500L, 100L);
        assertThat(placed.hasFees()).isTrue();
        applier.apply(placed);
        assertThat(balanceMojos()).isEqualTo(10_000_000L - 200_000L - 500L - 100L);

        RoundReport report = (RoundReport) applier.apply(resolved("970.00", "1004.00"));

        assertThat(report.economyRules()).isTrue();
        assertThat(report.playerOutcomes().get(0).liquidated()).isFalse();
        assertThat(report.playerOutcomes().get(0).fees()).isNotNull();
        // pnl = 200 * 25 / 1000 = 5 lots * 4.00 = 20.00 -> payout 220.00; the fees are not returned
        assertThat(balanceMojos()).isEqualTo(10_000_000L - 200_000L - 600L + 220_000L);
    }

    @Test
    void aReplayedBetIsNotRejectedWhenTheCapIsLowerNowThanWhenItWasPlaced() {
        // placed at 90x when the cap of the short round was 100; replayed against a market whose cap is 50
        Market lowered = tst(25);
        EngineHub other = new EngineHub();
        other.install(new Market(lowered.id(), lowered.kind(), lowered.category(), lowered.symbol(), lowered.title(), null,
                null, "synthetic", "TST", null, 25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null,
                new crowdvsmarket.server.markets.MarketEconomy(50, 40, new BigDecimal("0.1"), new BigDecimal("1.0"), null)));
        EventApplier replay = EventApplier.over(other, new BetFeed(), applier.players);
        replay.apply(new GameEvent.PlayerRegistered("p1", START - 1_000));

        replay.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 90, 200_000L, START - 500,
                "TST", 1_000L, 100L));

        assertThat(other.ledger().balance("p1")).isEqualTo(new PlayAmount(10_000_000L - 200_000L - 1_100L));
    }

    @Test
    void cancellingABetWithFeesRefundsTheStakeAndBothFees() {
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L, START - 500,
                "TST", 500L, 100L));
        applier.apply(new GameEvent.BetCancelled("p1", Interval.HOURLY, START, END, START - 400, "TST"));
        assertThat(balanceMojos()).isEqualTo(10_000_000L);
        assertThat(hub.ledger().entries()).extracting(e -> e.type()).contains(LedgerEntryType.STAKE_REFUND,
                LedgerEntryType.FEE_HERD_REFUND, LedgerEntryType.FEE_NETWORK_REFUND);
    }

    @Test
    void aCancelledRoundRefundsTheStakeAndBothFeesOfEveryBet() {
        applier.apply(new GameEvent.PlayerRegistered("p2", START - 1_000));
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L, START - 500,
                "TST", 500L, 100L));
        applier.apply(new GameEvent.BetPlaced("p2", Interval.HOURLY, START, END, Direction.DOWN, 10, 100_000L, START - 500,
                "TST")); // a legacy bet in the same round: stake only
        applier.apply(new GameEvent.RoundCancelled(Interval.HOURLY, START, END, "test", END, "TST"));
        assertThat(balanceMojos()).isEqualTo(10_000_000L);
        assertThat(hub.ledger().balance("p2").mojos()).isEqualTo(10_000_000L);
    }
}
