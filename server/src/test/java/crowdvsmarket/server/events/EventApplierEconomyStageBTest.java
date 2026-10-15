package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketEconomy;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Economy stage B (Р-92) and the event log, on plain objects (no database): the cutover is an event, so a log that crosses it replays to
 * exactly the same books, the three new events replay, and a bet is settled with the liquidation parameters stored in its {@code BetPlaced}
 * - not with the market's row of the day of the replay.
 *
 * <p>Market "TST", cap 25 on both round lengths, kappa 0.1: {@code l = 0.004}. Entry 1000.00; a bet of 200 PLAY at 25x long is liquidated at
 * {@code 1000 * (1 - 1/25) / (1 - 0.004) = 963.86}.
 */
class EventApplierEconomyStageBTest {

    private static final long START = Instant0.EPOCH_2026_04_15;
    private static final long END = START + 180_000L;

    /** The instants of the tests in one place. */
    private static final class Instant0 {
        static final long EPOCH_2026_04_15 = java.time.Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    }

    private static Market tst(int cap) {
        return new Market("TST", MarketKind.PERP, "crypto", "TST", "Test", null, null, "synthetic", "TST", null,
                25, 1, 1, MarketStatus.ACTIVE, 0L, null, null, null,
                new MarketEconomy(cap, cap, new BigDecimal("0.1"), new BigDecimal("1.0"), null));
    }

    private static EventApplier applierOver(EngineHub hub) {
        return EventApplier.over(hub, new BetFeed(), new PlayerDirectory());
    }

    private static EngineHub hubWith(Market market) {
        EngineHub hub = new EngineHub();
        hub.install(market);
        return hub;
    }

    private static GameEvent.RoundResolved resolved(String low, String close) {
        return new GameEvent.RoundResolved(Interval.HOURLY, START, END, "1000.00", close,
                List.of(new GameEvent.CandleData(START, END - 1, "1000.00", "1005.00", low, close, "0", 0)), END, "TST");
    }

    private static Map<String, Object> snapshot(LedgerBook ledger, String... players) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("treasury", ledger.treasury());
        out.put("herd", ledger.herd());
        out.put("openStakes", ledger.openStakes());
        out.put("active", ledger.economyActive());
        out.put("identity", ledger.identityDelta());
        for (String p : players) {
            out.put("balance " + p, ledger.balance(p));
            out.put("packs " + p, ledger.packCredited(p));
            out.put("fees " + p, ledger.feesPaid(p));
            out.put("retired " + p, ledger.isRetired(p));
        }
        return out;
    }

    /** A log that crosses the cutover: old-rule bets before it, one of them still open at the cutover, new-rule ones after. */
    private static List<GameEvent> logAcrossTheCutover() {
        long before = START - 3_600_000L;
        List<GameEvent> log = new ArrayList<>();
        log.add(new GameEvent.PlayerRegistered("p1", before));
        log.add(new GameEvent.PlayerRegistered("p2", before));
        log.add(new GameEvent.PlayerRegistered("bot", before));
        // a legacy bet that wins and one that is open when the cutover comes
        long oldStart = before + 1_000_000L;
        log.add(new GameEvent.BetPlaced("p1", Interval.HOURLY, oldStart, oldStart + 180_000L, Direction.UP, 10, 500_000L,
                oldStart - 100, "TST"));
        log.add(new GameEvent.RoundResolved(Interval.HOURLY, oldStart, oldStart + 180_000L, "1000.00", "1010.00",
                List.of(new GameEvent.CandleData(oldStart, oldStart + 179_999L, "1000.00", "1010.00", "999.00", "1010.00", "0", 0)),
                oldStart + 180_000L, "TST"));
        log.add(new GameEvent.BetPlaced("p2", Interval.HOURLY, START, END, Direction.DOWN, 10, 300_000L, START - 500, "TST"));
        log.add(new GameEvent.EconomyCutover(PlayAmount.ofWholePlay(1_000_000).mojos(), START - 400));
        // after it: a stage-B bet with fees and stored liquidation parameters, in the same round
        log.add(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L, START - 300, "TST",
                500L, 100L, 25, "0.1"));
        log.add(new GameEvent.BetPlaced("bot", Interval.HOURLY, START, END, Direction.UP, 5, 100_000L, START - 200, "TST",
                50L, 100L, 25, "0.1"));
        log.add(resolved("968.00", "1004.00"));
        log.add(new GameEvent.HerdSweptToTreasury(150L + 100L, END + 1_000));
        log.add(new GameEvent.BotRetired("bot", END + 2_000));
        return log;
    }

    private static EngineHub replay(List<GameEvent> log) {
        EngineHub hub = hubWith(tst(25));
        EventApplier applier = applierOver(hub);
        for (GameEvent event : log) {
            applier.apply(event);
        }
        return hub;
    }

    @Test
    void aLogThatCrossesTheCutoverReplaysToTheSameBooksEveryTime() {
        List<GameEvent> log = logAcrossTheCutover();

        EngineHub first = replay(log);
        EngineHub second = replay(log);

        assertThat(snapshot(first.ledger(), "p1", "p2", "bot")).isEqualTo(snapshot(second.ledger(), "p1", "p2", "bot"));
        LedgerBook ledger = first.ledger();
        assertThat(ledger.economyActive()).isTrue();
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.openStakes()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.isRetired("bot")).isTrue();
        assertThat(ledger.balance("bot")).isEqualTo(PlayAmount.ZERO);
        // p1: pack 10000, won 5 PLAY... : the legacy bet before the cutover minted its win (minted, packs counted)
        assertThat(ledger.packCredited("p1")).isEqualTo(PlayAmount.ofWholePlay(10_000));
        assertThat(ledger.openingTotal()).isEqualTo(PlayAmount.ofWholePlay(1_000_000));
    }

    @Test
    void theCutoverOpensTreasuryFromWhatThePlayersHoldAtThatMoment() {
        List<GameEvent> log = logAcrossTheCutover();
        int cutoverAt = 0;
        while (!(log.get(cutoverAt) instanceof GameEvent.EconomyCutover)) {
            cutoverAt++;
        }
        EngineHub hub = hubWith(tst(25));
        EventApplier applier = applierOver(hub);
        for (int i = 0; i < cutoverAt; i++) {
            applier.apply(log.get(i));
        }
        PlayAmount held = hub.ledger().playersTotal().plus(hub.ledger().openStakes());
        assertThat(hub.ledger().openStakes()).as("p2's legacy bet is open").isEqualTo(new PlayAmount(300_000L));
        assertThat(hub.ledger().economyActive()).isFalse();

        applier.apply(log.get(cutoverAt));

        assertThat(hub.ledger().treasury()).isEqualTo(PlayAmount.ofWholePlay(1_000_000).minus(held));
        assertThat(hub.ledger().herd()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aSecondCutoverEventChangesNothing() {
        EngineHub hub = replay(logAcrossTheCutover());
        Map<String, Object> before = snapshot(hub.ledger(), "p1", "p2", "bot");

        applierOver(hub).apply(new GameEvent.EconomyCutover(PlayAmount.ofWholePlay(7).mojos(), END + 9_000));

        assertThat(snapshot(hub.ledger(), "p1", "p2", "bot")).isEqualTo(before);
    }

    @Test
    void aBetIsSettledWithTheLiquidationParametersStoredInItsEventNotTheMarketsRowOfTheDayOfTheReplay() {
        // today the market's cap is 10: l = 0.01, which would liquidate this bet (969.70) at the low of 968.00;
        // the bet was placed when it was 25: l = 0.004 (963.86), and that is what settles it
        EngineHub hub = hubWith(tst(10));
        EventApplier applier = applierOver(hub);
        applier.apply(new GameEvent.PlayerRegistered("p1", START - 1_000));
        applier.apply(new GameEvent.PlayerRegistered("p2", START - 1_000));
        applier.apply(new GameEvent.BetPlaced("p1", Interval.HOURLY, START, END, Direction.UP, 25, 200_000L, START - 500, "TST",
                500L, 100L, 25, "0.1"));
        // no stored parameters (a stage-A row): the market's row of the day decides
        applier.apply(new GameEvent.BetPlaced("p2", Interval.HOURLY, START, END, Direction.UP, 10, 200_000L, START - 500, "TST",
                500L, 100L));

        RoundReport report = (RoundReport) applier.apply(resolved("968.00", "1004.00"));

        var p1 = report.playerOutcomes().stream().filter(o -> o.playerId().equals("p1")).findFirst().orElseThrow();
        var p2 = report.playerOutcomes().stream().filter(o -> o.playerId().equals("p2")).findFirst().orElseThrow();
        assertThat(p1.liquidated()).isFalse();
        assertThat(p1.liquidation().cap()).isEqualTo(25);
        assertThat(p1.liquidation().kappa()).isEqualByComparingTo("0.1");
        assertThat(p2.liquidated()).as("l = 0.01 -> liquidation price 1000 * 0.9 / 0.99 = 909.09 at x10: not touched either").isFalse();
        assertThat(p2.liquidation()).isNull();
    }

    @Test
    void theStoredParametersAreTheOnesInThePlacedEvent() {
        GameEvent.BetPlaced placed = new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100_000L, 1L, "TST",
                7L, 100L, 600, "0.1");

        assertThat(placed.liquidation().cap()).isEqualTo(600);
        assertThat(placed.liquidation().kappa()).isEqualByComparingTo("0.1");
        assertThat(new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100_000L, 1L, "TST", 7L, 100L)
                .liquidation()).isNull();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new GameEvent.BetPlaced("p",
                Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100_000L, 1L, "TST", 7L, 100L, 600, null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new GameEvent.BetPlaced("p",
                Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100_000L, 1L, "TST", null, null, 600, "0.1"));
    }
}
