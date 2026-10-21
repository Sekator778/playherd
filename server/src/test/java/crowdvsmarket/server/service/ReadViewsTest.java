package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.ledger.SeasonRecord;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.season.Season;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read views (architecture plan F8) built from plain objects: a real hub, the applier a rebuild replays with, a feed and
 * round progress. The property the incremental publication stands on: after every batch, the snapshot built from the previous
 * one and the batch's events is exactly the snapshot built from the engine in full - for every kind of event the scenario
 * has - while what the batch did not touch is the very same object as before.
 */
class ReadViewsTest {

    private static final long START = 1_700_000_000_000L;
    private static final long END = START + 3_600_000L;
    private static final long DAY_END = START + 86_400_000L;

    private EngineHub hub;
    private BetFeed feed;
    private RoundProgress progress;
    private EventApplier applier;
    private ReadViews views;
    private long version;
    /** What the applier answered for the events of the last batch (the ids of the requests). */
    private final List<Object> results = new ArrayList<>();

    private static Market market(String id, int lotExp, int oracleIndex) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, 25, lotExp, oracleIndex,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    @BeforeEach
    void anEngineAndAFirstPublication() {
        hub = new EngineHub();
        hub.ensureMarket(market("ETH", 1, 1));
        feed = new BetFeed();
        progress = new RoundProgress();
        applier = EventApplier.over(hub, feed);
        views = new ReadViews();
        applier.apply(new GameEvent.PlayerRegistered("p1", START - 1_000));
        applier.apply(new GameEvent.PlayerRegistered("p2", START - 1_000));
        applier.apply(new GameEvent.PlayerRegistered("p3", START - 1_000));
        views.publishFull(hub, feed, progress, version);
        progress.seed("ETH", Interval.HOURLY, new RoundWindow("ETH", Interval.HOURLY, START - 3_600_000L, START));
        views.publishCursors(progress);
    }

    private static GameEvent.CandleData candle(String open, String high, String low, String close) {
        return new GameEvent.CandleData(START, END - 1, open, high, low, close, "0", 0);
    }

    private static GameEvent.BetPlaced bet(String player, String market, Interval interval, Direction direction, int leverage,
                                           long wholePlay) {
        long end = interval == Interval.HOURLY ? END : DAY_END;
        return new GameEvent.BetPlaced(player, interval, START, end, direction, leverage,
                PlayAmount.ofWholePlay(wholePlay).mojos(), START - 1, market);
    }

    /** The batch: applied to the engine, published incrementally; the result is compared with a full publication. */
    private ReadViews.Snapshot commit(GameEvent... events) {
        List<GameEvent> batch = List.of(events);
        results.clear();
        for (GameEvent event : batch) {
            results.add(applier.apply(event));
        }
        version += batch.size();
        ReadViews.Snapshot incremental = views.publishBatch(batch, hub, feed, progress, version);
        ReadViews.Snapshot full = new ReadViews().publishFull(hub, feed, progress, version);
        assertSame(full, incremental, batch);
        assertTheSnapshotAnswersWhatTheLedgerComputes(incremental);
        return incremental;
    }

    private static void assertSame(ReadViews.Snapshot full, ReadViews.Snapshot incremental, List<GameEvent> batch) {
        String after = "after " + batch.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertThat(incremental.version()).as("version " + after).isEqualTo(full.version());
        assertThat(incremental.roundsByIdForTest()).as("rounds " + after).isEqualTo(full.roundsByIdForTest());
        assertThat(incremental.crowdsForTest()).as("crowds " + after).isEqualTo(full.crowdsForTest());
        assertThat(incremental.playersForTest()).as("players " + after).isEqualTo(full.playersForTest());
        assertThat(incremental.cursorsForTest()).as("cursors " + after).isEqualTo(full.cursorsForTest());
        assertThat(incremental.feed().asMap()).as("feed " + after).isEqualTo(full.feed().asMap());
        assertThat(incremental.boardsForTest()).as("season boards " + after).isEqualTo(full.boardsForTest());
        assertThat(incremental.correctionsForTest()).as("corrections " + after).isEqualTo(full.correctionsForTest());
        assertThat(incremental.economyForTest()).as("economy " + after).isEqualTo(full.economyForTest());
    }

    /**
     * What the locked reads used to compute from the ledger on the same state, against what the snapshot answers: the season boards
     * ({@link RatingBook} walks the whole ledger), a player's season record, the corrections, the house figures and the breaker.
     */
    private void assertTheSnapshotAnswersWhatTheLedgerComputes(ReadViews.Snapshot snapshot) {
        LedgerBook ledger = hub.ledger();
        RatingBook book = new RatingBook(ledger);
        Season season = Season.of(Instant.ofEpochMilli(START));
        for (Season s : List.of(new Season(2023, 10), season, new Season(2023, 12), new Season(2024, 1))) {
            Season checked = s;
            assertThat(snapshot.richBoard(s).stream().map(r -> r.entry().playerId() + " " + r.entry().growth() + " "
                    + r.entry().roundsPlayed()).toList()).as("rich " + checked)
                    .isEqualTo(book.richList(s, Map.of()).stream().map(e -> e.playerId() + " " + e.growth() + " "
                            + e.roundsPlayed()).toList());
            assertThat(snapshot.prophetBoard(s).stream().map(r -> r.entry().playerId() + " " + r.entry().accuracy() + " "
                    + r.entry().eligibleRounds() + " " + r.entry().roundsPlayed()).toList()).as("prophets " + checked)
                    .isEqualTo(book.prophetList(s, Map.of()).stream().map(e -> e.playerId() + " " + e.accuracy() + " "
                            + e.eligibleRounds() + " " + e.roundsPlayed()).toList());
            for (String playerId : ledger.playerIds()) {
                assertThat(snapshot.seasonRecord(playerId, s)).as(playerId + " " + checked).isEqualTo(ledger.seasonRecord(playerId, s));
            }
        }
        assertThat(snapshot.seasonRecord("ghost", season)).isEmpty();
        assertThat(snapshot.corrections()).isEqualTo(ledger.corrections());
        for (String playerId : ledger.playerIds()) {
            assertThat(snapshot.isRetired(playerId)).isEqualTo(ledger.isRetired(playerId));
        }
        assertThat(snapshot.economy().economyActive()).isEqualTo(ledger.economyActive());
        assertThat(snapshot.economy().treasury()).isEqualTo(ledger.treasury());
        assertThat(snapshot.economy().herd()).isEqualTo(ledger.herd());
        assertThat(snapshot.economy().openStakes()).isEqualTo(ledger.openStakes());
        assertThat(snapshot.economy().playersTotal()).isEqualTo(ledger.playersTotal());
        assertThat(snapshot.economy().openingTotal()).isEqualTo(ledger.openingTotal());
        assertThat(snapshot.economy().externalFlowSinceCutover()).isEqualTo(ledger.externalFlowSinceCutover());
        assertThat(snapshot.economy().depositedTotal()).isEqualTo(ledger.depositedTotal());
        assertThat(snapshot.economy().depositedSinceCutover()).isEqualTo(ledger.depositedSinceCutover());
        assertThat(snapshot.economy().withdrawnConfirmed()).isEqualTo(ledger.withdrawnConfirmed());
        assertThat(snapshot.economy().withdrawalsPending()).isEqualTo(ledger.withdrawalsPending());
        for (long day = -2; day < 70; day += 7) {
            Instant now = Instant.ofEpochMilli(START + day * 86_400_000L);
            assertThat(snapshot.economy().circuitBreakerOpen(now)).as("breaker at " + now).isEqualTo(ledger.circuitBreakerOpen(now));
            assertThat(snapshot.economy().treasuryMax30d(now)).isEqualTo(ledger.treasuryMax30d(now));
        }
    }

    @Test
    void everyKindOfEventLeavesTheIncrementalSnapshotEqualToTheFullOne() {
        RoundWindow ethHour = new RoundWindow("ETH", Interval.HOURLY, START, END);

        commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 5, 300), bet("p2", "ETH", Interval.HOURLY, Direction.DOWN, 3, 200));
        commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 7, 400)); // a replacement
        commit(bet("p3", "BTC", Interval.DAILY, Direction.UP, 4, 150));
        commit(new GameEvent.BetCancelled("p2", Interval.HOURLY, START, END, START, "ETH"));
        commit(new GameEvent.WithdrawalRequested("p1", PlayAmount.ofWholePlay(500).mojos(), START));
        String withdrawal = (String) results.get(0);
        commit(new GameEvent.WithdrawalConfirmed(withdrawal, START));
        commit(new GameEvent.DepositRequested("p2", PlayAmount.ofWholePlay(250).mojos(), START));
        String deposit = (String) results.get(0);
        commit(new GameEvent.DepositConfirmed(deposit, START));
        commit(new GameEvent.WithdrawalRequested("p3", PlayAmount.ofWholePlay(100).mojos(), START));
        commit(new GameEvent.WithdrawalFailed((String) results.get(0), START));
        commit(new GameEvent.WithdrawalReversed("p1", PlayAmount.ofWholePlay(500).mojos(), Season.of(Instant.ofEpochMilli(START)).id(),
                "test", withdrawal, START));
        commit(new GameEvent.DepositReversed("p2", PlayAmount.ofWholePlay(250).mojos(), Season.of(Instant.ofEpochMilli(START)).id(),
                "test", deposit, START));
        commit(new GameEvent.NicknameChanged("p1", "nick", START));
        commit(new GameEvent.EconomyCutover(PlayAmount.ofWholePlay(100_000).mojos(), START));
        commit(new GameEvent.DepositConfirmedOnChain("p3", 3, PlayAmount.ofWholePlay(100).mojos(), "coin-1", 10L, START));

        ReadViews.Snapshot beforeResolve = views.current();
        assertThat(beforeResolve.betCount(ethHour.roundId())).isEqualTo(1);
        assertThat(beforeResolve.find(ethHour.roundId(), "p1")).isPresent();

        ReadViews.Snapshot resolved = commit(new GameEvent.RoundResolved(Interval.HOURLY, START, END, "25000.00", "24000.00",
                List.of(candle("25000.00", "25100.00", "24000.00", "24000.00")), END, "ETH"));
        assertThat(resolved.betCount(ethHour.roundId())).as("the round's bets are paid out").isZero();
        assertThat(resolved.isClosed(ethHour)).isTrue();
        assertThat(resolved.crowd("ETH").orElseThrow().history()).hasSize(1);
        assertThat(resolved.player("p1").orElseThrow().balance()).isEqualTo(hub.ledger().balance("p1"));

        ReadViews.Snapshot cancelled = commit(new GameEvent.RoundCancelled(Interval.DAILY, START, DAY_END, "test", DAY_END, "BTC"));
        assertThat(cancelled.betCount("1d-" + START)).isZero();
        assertThat(cancelled.player("p3").orElseThrow().balance()).isEqualTo(hub.ledger().balance("p3"));

        ReadViews.Snapshot listed = commit(new GameEvent.MarketListed("SOL", "PERP", "crypto", "SOL", "SOL", null, null,
                "synthetic", "SOL", null, 10, 0, 3, START, null, 10, 10, "0.5", "10", null));
        assertThat(listed.hasMarket("SOL")).as("a listing creates engines").isTrue();
        commit(new GameEvent.MarketClosed("SOL", "test", START));
        commit(new GameEvent.HerdSweptToTreasury(0L, START));
        ReadViews.Snapshot afterCorrections = commit(new GameEvent.BotRetired("p3", START));
        assertThat(afterCorrections.isRetired("p3")).isTrue();
        assertThat(afterCorrections.corrections()).hasSize(2);
    }

    /**
     * A random game over a few months: players bet in and out of rounds, win and lose by chance (so ranks tie and untie), a bot is
     * retired, one withdraws, a month turns. After every batch the incremental snapshot equals the full one, and the boards equal
     * what a walk over the ledger gives (see {@link #commit}).
     */
    @Test
    void aRandomGameKeepsTheBoardsAndTheLedgerReadsEqualToTheLedgersOwnAnswers() {
        Random random = new Random(20261021L);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String id = "r" + i;
            ids.add(id);
            commit(new GameEvent.PlayerRegistered(id, START - 1_000));
        }
        commit(new GameEvent.EconomyCutover(PlayAmount.ofWholePlay(1_000_000).mojos(), START - 500));
        long hour = 3_600_000L;
        boolean sawABoard = false;
        for (int round = 0; round < 40; round++) {
            long start = round < 25 ? START + round * hour : START + 20L * 86_400_000L + (round - 25) * hour; // the month turns
            long end = start + hour;
            List<GameEvent> bets = new ArrayList<>();
            for (String id : ids) {
                if (random.nextInt(10) < 8 && !hub.ledger().isRetired(id)) {
                    bets.add(new GameEvent.BetPlaced(id, Interval.HOURLY, start, end,
                            random.nextBoolean() ? Direction.UP : Direction.DOWN, 2 + random.nextInt(5),
                            PlayAmount.ofWholePlay(100 + 10L * random.nextInt(10)).mojos(), start - 1, "ETH"));
                }
            }
            if (!bets.isEmpty()) {
                commit(bets.toArray(new GameEvent[0]));
            }
            if (random.nextInt(8) == 0 && !bets.isEmpty()) {
                GameEvent.BetPlaced first = (GameEvent.BetPlaced) bets.get(0);
                commit(new GameEvent.BetCancelled(first.playerId(), Interval.HOURLY, start, end, start, "ETH"));
            }
            int move = random.nextInt(5); // 0 down, 1 flat, 2.. up
            String close = move == 0 ? "24000.00" : move == 1 ? "25000.00" : "26000.00";
            ReadViews.Snapshot snapshot = commit(new GameEvent.RoundResolved(Interval.HOURLY, start, end, "25000.00", close,
                    List.of(new GameEvent.CandleData(start, end - 1, "25000.00", "26100.00", "24000.00", close, "0", 0)), end, "ETH"));
            sawABoard |= !snapshot.richBoard(Season.of(Instant.ofEpochMilli(start))).isEmpty();
            if (round == 15) {
                commit(new GameEvent.BotRetired("r3", end));
            }
            if (round == 20) {
                commit(new GameEvent.WithdrawalRequested("r5", PlayAmount.ofWholePlay(50).mojos(), end));
            }
        }
        assertThat(sawABoard).as("the game was long enough for somebody to pass the threshold of rounds").isTrue();
    }

    @Test
    void whatABatchDidNotTouchIsTheSameObjectAsBefore() {
        commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 5, 300), bet("p2", "ETH", Interval.HOURLY, Direction.DOWN, 3, 200),
                bet("p3", "BTC", Interval.DAILY, Direction.UP, 4, 150));
        ReadViews.Snapshot before = views.current();

        ReadViews.Snapshot after = commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 6, 350));

        assertThat(after.version()).isGreaterThan(before.version());
        assertThat(after.player("p2").orElseThrow()).as("p2 has not moved").isSameAs(before.player("p2").orElseThrow());
        assertThat(after.player("p3").orElseThrow()).isSameAs(before.player("p3").orElseThrow());
        assertThat(after.player("p1").orElseThrow()).as("p1 has").isNotSameAs(before.player("p1").orElseThrow());
        assertThat(after.roundsByIdForTest().get("1d-" + START)).as("the other round").isSameAs(before.roundsByIdForTest().get("1d-" + START));
        assertThat(after.crowdsForTest().get("ETH")).as("no round was resolved, no crowd moved")
                .isSameAs(before.crowdsForTest().get("ETH"));
    }

    @Test
    void aSnapshotIsNotChangedByTheBatchesAfterIt() {
        ReadViews.Snapshot first = commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 5, 300));
        PlayAmount balance = first.player("p1").orElseThrow().balance();
        List<?> bets = new ArrayList<>(first.betsFor("ETH-1h-" + START));

        commit(bet("p1", "ETH", Interval.HOURLY, Direction.DOWN, 5, 500), bet("p2", "ETH", Interval.HOURLY, Direction.UP, 2, 100));
        commit(new GameEvent.RoundResolved(Interval.HOURLY, START, END, "25000.00", "24000.00",
                List.of(candle("25000.00", "25100.00", "24000.00", "24000.00")), END, "ETH"));

        assertThat(first.player("p1").orElseThrow().balance()).isEqualTo(balance);
        assertThat(first.betsFor("ETH-1h-" + START)).isEqualTo(bets);
        assertThat(first.betCount("ETH-1h-" + START)).isEqualTo(1);
    }

    @Test
    void theFeedAndTheCursorsFollowTheBatchesAndTheCursorsMoveOutsideOneToo() {
        ReadViews.Snapshot first = commit(bet("p1", "ETH", Interval.HOURLY, Direction.UP, 5, 300));
        assertThat(first.feed().latest("ETH-1h-" + START, 10)).hasSize(1);
        assertThat(first.cursor("ETH", Interval.HOURLY)).contains(new RoundWindow("ETH", Interval.HOURLY, START - 3_600_000L, START));

        progress.advance("ETH", Interval.HOURLY); // the scheduler steps over a window
        assertThat(views.current().cursor("ETH", Interval.HOURLY)).as("not before the views are told").contains(
                new RoundWindow("ETH", Interval.HOURLY, START - 3_600_000L, START));
        views.publishCursors(progress);

        assertThat(views.current().cursor("ETH", Interval.HOURLY)).contains(new RoundWindow("ETH", Interval.HOURLY, START, END));
        assertThat(views.current().version()).as("same commit, same version").isEqualTo(first.version());
        assertThat(views.current().betsFor("ETH-1h-" + START)).hasSize(1);
    }

    @Test
    void aSnapshotKnowsTheLedgersPlayersAndNobodyElse() {
        ReadViews.Snapshot snapshot = views.current();

        assertThat(snapshot.player("p1")).isPresent();
        assertThat(snapshot.player("ghost")).isEmpty();
        assertThat(snapshot.playerCount()).isEqualTo(3);
        assertThat(snapshot.player("p1").orElseThrow().balance()).isEqualTo(PlayAmount.ofWholePlay(10_000));
    }

    @Test
    void theCrowdsStatsAreTheOnesTheLobbyShows() {
        commit(bet("p1", "ETH", Interval.HOURLY, Direction.DOWN, 20, 300));
        ReadViews.Snapshot snapshot = commit(new GameEvent.RoundResolved(Interval.HOURLY, START, END, "25000.00", "24000.00",
                List.of(candle("25000.00", "25100.00", "24000.00", "24000.00")), END, "ETH"));

        ReadViews.CrowdView crowd = snapshot.crowd("ETH").orElseThrow();
        assertThat(crowd.stats().roundsTraded()).isEqualTo(1);
        assertThat(crowd.stats().roundsWon()).isEqualTo(1);
        assertThat(crowd.stats().lastOutcome()).isEqualTo(Enums.CrowdOutcome.WIN);
        assertThat(crowd.hold()).hasSameSizeAs(crowd.history());
        assertThat(crowd.antiCrowd()).hasSameSizeAs(crowd.history());
    }
}
