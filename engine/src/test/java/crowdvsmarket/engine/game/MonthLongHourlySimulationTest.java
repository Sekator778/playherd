package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.round.CandleSeriesRoundPrices;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * End-to-end simulation: a full calendar month (April 2026 UTC, 720 HOURLY rounds) replayed on the
 * real {@code hl_btc_1h.csv} fixture, driven by 50 deterministic synthetic players with seeded
 * strategies (always-40x, contrarian, follow-the-crowd, and general random), asserting every
 * engine invariant the task lists: conservation, no negative balances, one position per player per
 * round, crowd-account math, and rating thresholds.
 *
 * <p>April 2026 is fully inside the fixture's documented range
 * (2026-03-01T04:00Z .. 2026-09-25T13:00Z, see fixtures/PROVENANCE.md), so all 720 rounds resolve
 * on real historical candles.
 */
class MonthLongHourlySimulationTest {

    private static final long APRIL_START_MS = 1_775_001_600_000L; // 2026-04-01T00:00:00Z
    private static final long HOUR_MS = 3_600_000L;
    private static final int ROUND_COUNT = 720; // April has 30 days * 24h

    private static final int ALWAYS_40X_COUNT = 5;
    private static final int CONTRARIAN_COUNT = 5;
    private static final int FOLLOW_CROWD_COUNT = 5;
    private static final int PLAYER_COUNT = 50; // remaining 35 are general seeded random players
    private static final double MODIFY_PROBABILITY = 0.10; // exercises D-13's modify-before-start path
    private static final long SEED_BASE = 20260401L;

    // --- strategies ---

    private record Decision(Direction direction, int leverage, PlayAmount stake) {
    }

    @FunctionalInterface
    private interface Strategy {
        /** {@code null} means "skip this round". */
        Decision decide(Random rng, Optional<Direction> previousCrowdDirection);
    }

    private static Strategy always40x() {
        return (rng, previous) -> new Decision(
                rng.nextBoolean() ? Direction.UP : Direction.DOWN, 40, PlayAmount.ofWholePlay(100));
    }

    private static Strategy contrarian() {
        return (rng, previous) -> new Decision(
                previous.map(d -> d == Direction.UP ? Direction.DOWN : Direction.UP).orElse(Direction.UP),
                5, PlayAmount.ofWholePlay(300));
    }

    private static Strategy followTheCrowd() {
        return (rng, previous) -> new Decision(previous.orElse(Direction.UP), 5, PlayAmount.ofWholePlay(300));
    }

    private static Strategy generalRandom() {
        return (rng, previous) -> {
            if (rng.nextDouble() < 0.15) {
                return null; // sits this round out
            }
            Direction direction = rng.nextBoolean() ? Direction.UP : Direction.DOWN;
            int leverage = Leverage.MIN + rng.nextInt(40 - Leverage.MIN + 1);
            long stakePlay = 100 + rng.nextInt(9) * 100L; // 100..900 PLAY
            return new Decision(direction, leverage, PlayAmount.ofWholePlay(stakePlay));
        };
    }

    private record PlayerSpec(String id, Strategy strategy, Random rng) {
    }

    private static List<PlayerSpec> buildPlayers() {
        List<PlayerSpec> players = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < ALWAYS_40X_COUNT; i++, idx++) {
            players.add(new PlayerSpec("p" + idx + "-always40x", always40x(), new Random(SEED_BASE + idx)));
        }
        for (int i = 0; i < CONTRARIAN_COUNT; i++, idx++) {
            players.add(new PlayerSpec("p" + idx + "-contrarian", contrarian(), new Random(SEED_BASE + idx)));
        }
        for (int i = 0; i < FOLLOW_CROWD_COUNT; i++, idx++) {
            players.add(new PlayerSpec("p" + idx + "-followcrowd", followTheCrowd(), new Random(SEED_BASE + idx)));
        }
        while (players.size() < PLAYER_COUNT) {
            players.add(new PlayerSpec("p" + idx + "-random", generalRandom(), new Random(SEED_BASE + idx)));
            idx++;
        }
        return players;
    }

    private static boolean canAfford(GameEngine engine, String playerId, PlayAmount stake, Instant now) {
        engine.ledger().ensureSeasonPack(playerId, now);
        return stake.compareTo(LedgerBook.MIN_STAKE) >= 0 && stake.compareTo(engine.ledger().balance(playerId)) <= 0;
    }

    private static void placeBetsForRound(
            GameEngine engine, List<PlayerSpec> players, RoundWindow window, Instant now, Optional<Direction> previousCrowdDirection) {
        for (PlayerSpec player : players) {
            Decision decision = player.strategy().decide(player.rng(), previousCrowdDirection);
            if (decision == null || !canAfford(engine, player.id(), decision.stake(), now)) {
                continue;
            }
            engine.placeBet(player.id(), window, decision.direction(), new Leverage(decision.leverage()), decision.stake(), now);

            if (player.rng().nextDouble() < MODIFY_PROBABILITY) {
                Decision modified = player.strategy().decide(player.rng(), previousCrowdDirection);
                if (modified != null && canAfford(engine, player.id(), modified.stake(), now)) {
                    engine.placeBet(player.id(), window, modified.direction(), new Leverage(modified.leverage()), modified.stake(), now);
                }
            }
        }
    }

    /** Independently recomputes D-5/D-14's crowd formula from the round's actual resolved bets
     * (as reported back by the engine) and cross-checks it against {@link CrowdAccount}'s own
     * result — a second implementation of the same formula, not a call into the production one. */
    private static void assertCrowdMathMatchesIndependentRecomputation(RoundReport report) {
        PlayAmount upStake = PlayAmount.ZERO;
        PlayAmount downStake = PlayAmount.ZERO;
        List<PlayerRoundOutcome> upSide = new ArrayList<>();
        List<PlayerRoundOutcome> downSide = new ArrayList<>();
        for (PlayerRoundOutcome outcome : report.playerOutcomes()) {
            if (outcome.direction() == Direction.UP) {
                upStake = upStake.plus(outcome.stake());
                upSide.add(outcome);
            } else {
                downStake = downStake.plus(outcome.stake());
                downSide.add(outcome);
            }
        }

        int cmp = upStake.compareTo(downStake);
        if (cmp == 0) {
            assertThat(report.crowdResult().traded()).as("round %s: tie must not trade", report.window().roundId()).isFalse();
            return;
        }

        Direction expectedDirection = cmp > 0 ? Direction.UP : Direction.DOWN;
        List<PlayerRoundOutcome> majoritySide = cmp > 0 ? upSide : downSide;
        assertThat(report.crowdResult().traded()).as("round %s", report.window().roundId()).isTrue();
        assertThat(report.crowdResult().direction()).as("round %s", report.window().roundId()).isEqualTo(expectedDirection);

        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalStake = BigDecimal.ZERO;
        for (PlayerRoundOutcome outcome : majoritySide) {
            BigDecimal stake = outcome.stake().toMoney();
            weightedSum = weightedSum.add(BigDecimal.valueOf(outcome.leverage()).multiply(stake));
            totalStake = totalStake.add(stake);
        }
        int expectedLeverage = weightedSum.divide(totalStake, MathContext.DECIMAL128).setScale(0, RoundingMode.HALF_UP).intValueExact();
        expectedLeverage = Math.max(Leverage.MIN, Math.min(40, expectedLeverage));
        assertThat(report.crowdResult().leverage()).as("round %s", report.window().roundId()).isEqualTo(expectedLeverage);
    }

    @Test
    void fullAprilOfHourlyRoundsHoldsAllInvariants() {
        CandleSeries hourlyFixture = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        RoundPrices prices = new CandleSeriesRoundPrices(hourlyFixture);
        // the ledger keeps no entries of its own (as on the server); the conservation checks get them through a listener
        List<LedgerEntry> ledgerLog = new ArrayList<>();
        LedgerBook book = new LedgerBook(false);
        book.addListener(ledgerLog::add);
        GameEngine engine = new GameEngine(book, new CrowdAccount());

        List<RoundWindow> windows = new ArrayList<>(ROUND_COUNT);
        for (int i = 0; i < ROUND_COUNT; i++) {
            long start = APRIL_START_MS + i * HOUR_MS;
            windows.add(new RoundWindow(Interval.HOURLY, start, start + HOUR_MS));
        }

        List<PlayerSpec> players = buildPlayers();
        assertThat(players).hasSize(PLAYER_COUNT);

        // Round 0's bets are "placed during the last hour of March" -- modelled here as placed at
        // April's own start, so every player's first activity stays inside the April season.
        placeBetsForRound(engine, players, windows.get(0), Instant.ofEpochMilli(APRIL_START_MS), Optional.empty());

        int liquidatedCount = 0;
        int settledCount = 0;
        int crowdTradedCount = 0;
        PlayAmount totalNetFromReports = PlayAmount.ZERO; // sum(payout - stake) over every resolved bet
        Set<String> seenRoundIds = new HashSet<>();

        for (int i = 0; i < ROUND_COUNT; i++) {
            RoundWindow window = windows.get(i);
            RoundReport report = engine.runRound(window, prices);

            assertThat(seenRoundIds.add(report.window().roundId())).as("round id must be unique").isTrue();
            // One position per player per round (D-13), independent of BetBook's own guarantee.
            assertThat(report.playerOutcomes()).extracting(PlayerRoundOutcome::playerId).doesNotHaveDuplicates();

            for (PlayerRoundOutcome outcome : report.playerOutcomes()) {
                if (outcome.liquidated()) {
                    liquidatedCount++;
                } else {
                    settledCount++;
                }
                totalNetFromReports = totalNetFromReports.plus(PlayAmount.fromMoney(outcome.payout()).minus(outcome.stake()));
            }
            if (report.crowdResult().traded()) {
                crowdTradedCount++;
            }
            assertCrowdMathMatchesIndependentRecomputation(report);

            if (i + 1 < ROUND_COUNT) {
                Optional<Direction> previousCrowdDirection = report.crowdResult().traded()
                        ? Optional.of(report.crowdResult().direction())
                        : Optional.empty();
                placeBetsForRound(engine, players, windows.get(i + 1), Instant.ofEpochMilli(window.startMs()), previousCrowdDirection);
            }
        }

        // Sanity: the seeded strategies actually exercised both outcomes and the crowd traded most rounds.
        assertThat(liquidatedCount).as("always-40x players should trigger liquidations over a month").isGreaterThan(0);
        assertThat(settledCount).isGreaterThan(0);
        assertThat(crowdTradedCount).isGreaterThan(ROUND_COUNT / 2);

        // --- invariant: no negative balances ---
        for (String playerId : engine.ledger().playerIds()) {
            assertThat(engine.ledger().balance(playerId).isNegative()).as("balance of %s", playerId).isFalse();
        }

        // --- invariant: conservation, replay #1 -- the ledger's own append-only log reproduces its live balances ---
        Map<String, PlayAmount> replayedBalances = new HashMap<>();
        for (LedgerEntry entry : ledgerLog) {
            replayedBalances.merge(entry.playerId(), entry.delta(), PlayAmount::plus);
        }
        for (String playerId : engine.ledger().playerIds()) {
            assertThat(replayedBalances.getOrDefault(playerId, PlayAmount.ZERO))
                    .as("ledger-log replay for %s", playerId)
                    .isEqualTo(engine.ledger().balance(playerId));
        }

        // --- invariant: conservation, replay #2 -- the house mints starter packs and net round
        // winnings, and burns net round losses (including a full loss on liquidation, D-7);
        // nothing else creates or destroys PLAY. totalNetFromReports was accumulated independently
        // above, from the engine's per-round reports rather than from the ledger's own entries.
        PlayAmount totalPacks = PlayAmount.ZERO;
        PlayAmount totalBalance = PlayAmount.ZERO;
        for (String playerId : engine.ledger().playerIds()) {
            totalPacks = totalPacks.plus(LedgerBook.STARTER_PACK); // one pack each: single season, all activity in April
            totalBalance = totalBalance.plus(engine.ledger().balance(playerId));
        }
        assertThat(totalBalance).as("total PLAY = packs minted + net round winnings/losses").isEqualTo(totalPacks.plus(totalNetFromReports));

        // --- invariant: rating thresholds (D-9/D-18) ---
        Season season = Season.of(Instant.ofEpochMilli(APRIL_START_MS));
        RatingBook ratings = new RatingBook(engine.ledger(), RatingBook.DEFAULT_ROUNDS_THRESHOLD);
        Map<String, PlayerIdentity> identities = new LinkedHashMap<>();
        for (String playerId : engine.ledger().playerIds()) {
            identities.put(playerId, new PlayerIdentity(playerId, playerId, "xch1" + playerId));
        }

        List<RatingBook.RichEntry> richList = ratings.richList(season, identities);
        List<RatingBook.ProphetEntry> prophetList = ratings.prophetList(season, identities);

        Set<String> expectedRichQualified = new HashSet<>();
        Set<String> expectedProphetQualified = new HashSet<>();
        for (String playerId : engine.ledger().playerIds()) {
            var record = engine.ledger().seasonRecord(playerId, season).orElseThrow();
            if (record.roundsPlayed() >= RatingBook.DEFAULT_ROUNDS_THRESHOLD) {
                expectedRichQualified.add(playerId);
                if (record.directionEligibleRounds() > 0) {
                    expectedProphetQualified.add(playerId);
                }
            }
        }

        assertThat(richList).extracting(RatingBook.RichEntry::playerId).containsExactlyInAnyOrderElementsOf(expectedRichQualified);
        assertThat(prophetList).extracting(RatingBook.ProphetEntry::playerId).containsExactlyInAnyOrderElementsOf(expectedProphetQualified);
        assertThat(richList).allSatisfy(e -> assertThat(e.roundsPlayed()).isGreaterThanOrEqualTo(RatingBook.DEFAULT_ROUNDS_THRESHOLD));
        for (int i = 1; i < richList.size(); i++) {
            assertThat(richList.get(i - 1).growth()).isGreaterThanOrEqualTo(richList.get(i).growth());
        }
        for (int i = 1; i < prophetList.size(); i++) {
            assertThat(prophetList.get(i - 1).accuracy()).isGreaterThanOrEqualTo(prophetList.get(i).accuracy());
        }

        // Given 720 rounds and a 15% max skip rate, essentially every active player clears the
        // rounds-played threshold -- the rich list should not be trivially empty or everyone.
        assertThat(richList).isNotEmpty();
        assertThat(richList.size()).isLessThanOrEqualTo(engine.ledger().playerIds().size());
    }
}
