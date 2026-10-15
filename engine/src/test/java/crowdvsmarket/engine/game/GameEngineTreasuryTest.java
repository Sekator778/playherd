package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;

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
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.LiquidationParams;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Economy stage B through {@link GameEngine}: the payout cap follows TREASURY, a bet is settled with the liquidation parameters it was
 * placed with, and over a long simulated run (many players, both round lengths, cancellations, liquidations, a pack every month, a bot that
 * retires, the daily sweep) the books keep balancing: {@code TREASURY + HERD + players + open stakes = opening total (+ deposits - withdrawals)}.
 */
class GameEngineTreasuryTest {

    private static final long SHORT_ROUND = 180_000L;
    private static final long START = Instant.parse("2026-04-15T10:00:00Z").toEpochMilli();
    private static final MarketSpec TST = MarketSpec.of("TST", 25)
            .withEconomy(100, 40, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));

    private static PlayAmount play(long whole) {
        return PlayAmount.ofWholePlay(whole);
    }

    private static RoundPrices path(long startMs, String open, String high, String low, String close) {
        Candle candle = new Candle(startMs, startMs + SHORT_ROUND - 1, new BigDecimal(open), new BigDecimal(high),
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

    private static GameEngine newEngine(LedgerBook ledger, MarketSpec spec) {
        return new GameEngine(ledger, new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), spec));
    }

    @Test
    void thePayoutCapFollowsTheTreasuryBalanceAfterTheCutover() {
        LedgerBook ledger = new LedgerBook();
        GameEngine engine = newEngine(ledger, TST);
        ledger.activateEconomy(play(100_000), Instant.ofEpochMilli(START - 600_000L)); // a small treasury: 0.1% = 100 PLAY
        RoundWindow window = new RoundWindow("TST", Interval.HOURLY, START, START + SHORT_ROUND);
        PlayAmount stake = play(200);
        engine.placeBet("alice", window, Direction.UP, new Leverage(50), stake, EconomyRules.fees(TST, 50, stake),
                Instant.ofEpochMilli(START - 60_000L));
        PlayAmount treasury = ledger.treasury(); // 100000 minus alice's pack: 0.1% of it is 90 PLAY

        // +2% at x50 would pay 200 + 200 = 400; 20 x the stake is 4000, but the cap is 0.1% of the treasury, 90
        RoundReport report = engine.runRound(window, path(START, "1000.00", "1020.00", "1000.00", "1020.00"));

        BigDecimal cap = EconomyRules.maxPayout(stake, treasury).toMoney();
        assertThat(report.playerOutcomes().get(0).payout()).isEqualByComparingTo(cap.setScale(2, java.math.RoundingMode.DOWN));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
    }

    @Test
    void aBetIsSettledWithTheLiquidationParametersItWasPlacedWithNotTheMarketsToday() {
        // the same bet, placed on a market whose cap was 100 (l = 0.001) then replayed on one whose cap is now 20 (l = 0.005)
        LedgerBook ledger = new LedgerBook();
        MarketSpec lowered = MarketSpec.of("TST", 25).withEconomy(20, 20, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"));
        GameEngine engine = newEngine(ledger, lowered);
        RoundWindow window = new RoundWindow("TST", Interval.HOURLY, START, START + SHORT_ROUND);
        PlayAmount stake = play(200);
        // x20 is the largest leverage today; stored cap 100 -> l = 0.1 / 100 = 0.001
        engine.replayBet("stored", window, Direction.UP, new Leverage(20), stake, EconomyRules.fees(TST, 20, stake),
                new LiquidationParams(100, MarketSpec.DEFAULT_KAPPA), Instant.ofEpochMilli(START - 60_000L));
        // no stored parameters: today's row decides (cap 20 -> l = 0.005)
        engine.replayBet("current", window, Direction.UP, new Leverage(20), stake, EconomyRules.fees(TST, 20, stake),
                Instant.ofEpochMilli(START - 60_000L));

        // x20: liquidation near -5% + maintenance: l=0.001 -> entry*(1-0.05)/(1-0.001) = 950.95 ; l=0.005 -> 954.77. Low 953.00 liquidates only "current".
        RoundReport report = engine.runRound(window, path(START, "1000.00", "1001.00", "953.00", "1000.00"));

        PlayerRoundOutcome stored = report.playerOutcomes().stream().filter(o -> o.playerId().equals("stored")).findFirst().orElseThrow();
        PlayerRoundOutcome current = report.playerOutcomes().stream().filter(o -> o.playerId().equals("current")).findFirst().orElseThrow();
        assertThat(stored.liquidated()).as("l = 0.001 from the stored cap 100").isFalse();
        assertThat(current.liquidated()).as("l = 0.005 from today's cap 20").isTrue();
        assertThat(stored.liquidation()).isEqualTo(new LiquidationParams(100, MarketSpec.DEFAULT_KAPPA));
    }

    @Test
    void theBooksBalanceOverALongSimulatedRun() {
        Random random = new Random(20261015);
        LedgerBook ledger = new LedgerBook();
        MarketSpec[] specs = {TST, MarketSpec.of("ETHX", 25).withEconomy(60, 30, MarketSpec.DEFAULT_KAPPA, new BigDecimal("0.0001"))};
        GameEngine[][] engines = new GameEngine[specs.length][2];
        for (int m = 0; m < specs.length; m++) {
            CrowdAccount crowd = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), specs[m]);
            engines[m][0] = new GameEngine(ledger, crowd);
            engines[m][1] = new GameEngine(ledger, crowd);
        }
        PlayAmount opening = play(5_000_000);
        int players = 30;
        // life before the cutover: ten days under the old rules, with packs minted and wins minted
        long t = Instant.parse("2026-04-20T00:00:00Z").toEpochMilli();
        for (int p = 0; p < players; p++) {
            ledger.ensureSeasonPack("p" + p, Instant.ofEpochMilli(t));
        }
        t = runRounds(random, ledger, engines, specs, players, t, 400, false);
        String holder = null;
        for (int p = 0; p < players && holder == null; p++) {
            holder = ledger.balance("p" + p).compareTo(play(100)) >= 0 ? "p" + p : null;
        }
        ledger.reserveStake(holder, "open-at-cutover", play(100), Instant.ofEpochMilli(t)); // a stake still open at the cutover
        ledger.activateEconomy(opening, Instant.ofEpochMilli(t));
        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        ledger.refundStake(holder, "open-at-cutover", play(100), Instant.ofEpochMilli(t));
        // then six weeks under the new ones: month rollovers, fees, caps, cancellations, a retiring bot, the daily sweep
        runRounds(random, ledger, engines, specs, players, t, 6 * 7 * 24 * 20, true);

        assertThat(ledger.identityDelta()).isEqualTo(PlayAmount.ZERO);
        assertThat(ledger.treasury().plus(ledger.herd()).plus(ledger.playersTotal()).plus(ledger.openStakes()))
                .isEqualTo(opening);
        assertThat(ledger.herd().isPositive()).isTrue();
        assertThat(ledger.openStakes()).isEqualTo(PlayAmount.ZERO);
    }

    /** Runs {@code rounds} 3-minute rounds from {@code startMs} with random bets; returns the time after the last one. */
    private long runRounds(Random random, LedgerBook ledger, GameEngine[][] engines, MarketSpec[] specs, int players,
                           long startMs, int rounds, boolean economy) {
        long t = startMs;
        long lastSweepDay = -1;
        boolean botRetired = false;
        for (int r = 0; r < rounds; r++) {
            int m = random.nextInt(specs.length);
            Interval interval = random.nextInt(4) == 0 ? Interval.DAILY : Interval.HOURLY;
            GameEngine engine = engines[m][interval == Interval.HOURLY ? 0 : 1];
            MarketSpec spec = specs[m];
            RoundWindow window = new RoundWindow(spec.id(), interval, t, t + SHORT_ROUND);
            List<String> bettors = new ArrayList<>();
            for (int p = 0; p < players; p++) {
                if (ledger.isRetired("p" + p) || random.nextInt(3) != 0) {
                    continue;
                }
                String id = "p" + p;
                PlayAmount balance = ledger.balance(id);
                if (balance.compareTo(play(100)) < 0) {
                    continue;
                }
                long stakePlay = 100 + random.nextInt((int) Math.min(900, balance.mojos() / 1000 - 99));
                PlayAmount stake = play(stakePlay);
                int allowed = EconomyRules.maxLeverage(spec, interval, stake);
                int leverage = 2 + random.nextInt(allowed - 1);
                Fees fees = EconomyRules.fees(spec, leverage, stake);
                if (stake.plus(fees.total()).compareTo(balance) > 0) {
                    continue;
                }
                Instant betAt = Instant.ofEpochMilli(t - 30_000L);
                engine.placeBet(id, window, random.nextBoolean() ? Direction.UP : Direction.DOWN, new Leverage(leverage), stake,
                        fees, betAt);
                bettors.add(id);
                if (random.nextInt(10) == 0) { // changes its mind
                    engine.cancelBet(id, window, betAt);
                }
            }
            if (random.nextInt(25) == 0) { // the operator cancels the round
                GameEngine finalEngine = engine;
                for (var bet : new ArrayList<>(engine.betBook().betsFor(window.roundId()))) {
                    finalEngine.refundBet(bet, Instant.ofEpochMilli(t));
                }
                engine.betBook().clear(window.roundId());
            } else {
                double move = random.nextGaussian() * 0.003;
                double high = Math.max(0, move) + Math.abs(random.nextGaussian()) * 0.001;
                double low = Math.min(0, move) - Math.abs(random.nextGaussian()) * 0.001;
                engine.runRound(window, path(t, "1000.00", String.format("%.2f", 1000 * (1 + high)),
                        String.format("%.2f", 1000 * (1 + low)), String.format("%.2f", 1000 * (1 + move))));
            }
            t += SHORT_ROUND;
            if (economy) {
                long day = Math.floorDiv(t, 86_400_000L);
                if (day != lastSweepDay) { // the daily sweep of the fees of the first ten players (the "bots")
                    PlayAmount botFees = PlayAmount.ZERO;
                    for (int p = 0; p < 10; p++) {
                        botFees = botFees.plus(ledger.feesPaid("p" + p));
                    }
                    PlayAmount due = botFees.minus(ledger.botFeesSwept());
                    if (due.isPositive() && due.compareTo(ledger.herd()) <= 0) {
                        ledger.sweepHerdToTreasury(due, Instant.ofEpochMilli(t));
                    }
                    lastSweepDay = day;
                }
                if (!botRetired && r > rounds / 2 && ledger.balance("p1").compareTo(play(100)) < 0) {
                    ledger.retire("p1", Instant.ofEpochMilli(t));
                    botRetired = true;
                }
                if (r % 500 == 0 && ledger.economyActive()) {
                    assertThat(ledger.identityDelta()).as("round %d", r).isEqualTo(PlayAmount.ZERO);
                }
            }
            // a pack at the start of every new month, for everyone who is not retired
            for (int p = 0; p < players; p++) {
                if (!ledger.isRetired("p" + p)) {
                    ledger.ensureSeasonPack("p" + p, Instant.ofEpochMilli(t));
                }
            }
        }
        return t;
    }
}
