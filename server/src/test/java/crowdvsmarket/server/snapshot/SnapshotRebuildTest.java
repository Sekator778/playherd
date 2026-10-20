package crowdvsmarket.server.snapshot;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LogCapture;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F5 against the real application and its database: the state a restart builds from a snapshot and the journal
 * after it is the live state, which is the state a replay of the whole journal builds; a snapshot that cannot be trusted is left
 * aside with a log line; a snapshot that stands on rows the database lacks is left aside; the projections are repaired as before;
 * the nightly check compares a replay from zero with the newest snapshot and says so when they differ.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SnapshotRebuildTest extends LiveGameTestBase {

    @Inject
    SnapshotService snapshots;
    @Inject
    EngineSnapshotRepository repository;
    @Inject
    EventStore eventStore;
    @Inject
    BetFeed betFeed;
    @Inject
    PlayerDirectory players;
    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @BeforeEach
    void noSnapshotsFromEarlierTests() {
        db.tx(c -> db.update(c, "DELETE FROM engine_snapshots"));
    }

    private String liveDigest() {
        return StateDigest.of(hub, betFeed);
    }

    /** People, a house bot, two markets, bets on a betting window, a settled round and a cancelled one. */
    private void play(String suffix) {
        long now = System.currentTimeMillis();
        Market main = list("T_SN_" + suffix, MarketKind.PERP, Category.CRYPTO, "SA" + suffix, 25, 1);
        prices.setPrice(main, "100.00");
        alignTo(main, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(main.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(main.id(), Interval.HOURLY);
        String p1 = signUp("sna" + suffix);
        String p2 = signUp("snb" + suffix);
        gameService.setNickname(p1, "Snap Alice " + suffix);
        String bot = gameService.createBotPlayer("Bot SN " + suffix, "TREND", now).playerId();
        gameService.placeBet(p1, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        gameService.placeBet(p2, bet(betting, Enums.Direction.DOWN, 3, "100.000"), betting.startMs() - 1);
        gameService.placeBet(bot, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(main, betting, "100.00", "110.00", "100.00", "110.00");
        scheduler.tick(betting.endMs());
    }

    /** What happens after a snapshot: a new market, a new person, bets that stay open, a nickname. */
    private void playMore(String suffix) {
        Market later = list("T_SM_" + suffix, MarketKind.PERP, Category.CRYPTO, "SM" + suffix, 25, 1);
        prices.setPrice(later, "50.00");
        alignTo(later, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(later.id(), Interval.HOURLY);
        String p3 = signUp("snc" + suffix);
        gameService.setNickname(p3, "Late " + suffix);
        gameService.placeBet(p3, bet(betting, Enums.Direction.UP, 4, "300.000"), betting.startMs() - 1);
    }

    private long eventsAfter(long seq) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE id > ?", rs -> rs.getLong("n"), seq));
    }

    @Test
    void theStateBuiltFromASnapshotAndTheTailIsTheLiveStateWhichIsTheStateOfAFullReplay() {
        play("A");
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        assertThat(taken.seq()).isEqualTo(eventStore.lastId());
        playMore("A");
        String live = liveDigest();

        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).as("the rebuild started from the snapshot").isEqualTo(taken.seq());
        assertThat((long) rebuild.lastRebuild().eventsReplayed()).as("and replayed the tail only").isEqualTo(eventsAfter(taken.seq()))
                .isLessThan(eventsAfter(0));
        assertThat(liveDigest()).as("snapshot + tail == live").isEqualTo(live);

        db.tx(c -> db.update(c, "DELETE FROM engine_snapshots"));
        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat((long) rebuild.lastRebuild().eventsReplayed()).isEqualTo(eventsAfter(0));
        assertThat(liveDigest()).as("full replay == live").isEqualTo(live);
    }

    @Test
    void aSnapshotWithNothingAfterItIsAFullState() {
        play("B");
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        String live = liveDigest();

        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).isEqualTo(taken.seq());
        assertThat(rebuild.lastRebuild().eventsReplayed()).isZero();
        assertThat(liveDigest()).isEqualTo(live);
    }

    @Test
    void aSnapshotThatCannotBeTrustedIsIgnoredWithALogLineAndNeverLoaded() {
        play("C");
        String live = liveDigest();
        // each corruption: what it does to the row, and what the log says
        List<String[]> corruptions = List.of(
                new String[] {"UPDATE engine_snapshots SET state_sha256 = repeat('0', 64)", "hashes to"},
                new String[] {"UPDATE engine_snapshots SET format_version = 99", "format version 99"},
                new String[] {"UPDATE engine_snapshots SET body = decode('deadbeef', 'hex')", "cannot be decompressed"});
        for (String[] corruption : corruptions) {
            db.tx(c -> db.update(c, "DELETE FROM engine_snapshots"));
            EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
            db.tx(c -> db.update(c, corruption[0]));
            try (LogCapture log = LogCapture.start()) {
                rebuild.rebuild();

                assertThat(log.lines()).as(corruption[0]).anySatisfy(line -> assertThat(line)
                        .contains("engine snapshot " + taken.seq() + " ignored").contains(corruption[1]));
            }
            assertThat(rebuild.lastRebuild().snapshotSeq()).as(corruption[0] + ": the whole journal was replayed").isZero();
            assertThat(liveDigest()).as(corruption[0]).isEqualTo(live);
        }
    }

    @Test
    void aSnapshotThatDoesNotDecodeIntoTheRegistryIsIgnored() {
        play("D");
        String live = liveDigest();
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        // a snapshot whose text names a market the registry does not have, hash and all
        String text = StateCodec.encode(hub, betFeed).replace("\nmarket\tT_SN_D\n", "\nmarket\tT_NOT_LISTED\n");
        repository.put(taken.seq(), StateCodec.FORMAT_VERSION, taken.takenAtMs(), StateDigest.sha256(text), SnapshotService.gzip(text));
        try (LogCapture log = LogCapture.start()) {
            rebuild.rebuild();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).contains("engine snapshot " + taken.seq() + " ignored")
                    .contains("T_NOT_LISTED"));
        }
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat(liveDigest()).isEqualTo(live);
    }

    @Test
    void aSnapshotAheadOfTheJournalIsIgnored() {
        play("E");
        String live = liveDigest();
        String text = StateCodec.encode(hub, betFeed);
        long beyond = eventStore.lastId() + 1_000;
        repository.put(beyond, StateCodec.FORMAT_VERSION, System.currentTimeMillis(), StateDigest.sha256(text), SnapshotService.gzip(text));
        try (LogCapture log = LogCapture.start()) {
            rebuild.rebuild();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).contains("engine snapshot " + beyond + " ignored")
                    .contains("journal ends"));
        }
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat(liveDigest()).isEqualTo(live);
    }

    @Test
    void aSnapshotStandingOnMissingLedgerRowsOrResultRowsFallsBackToAnOlderOneOrToAFullReplay() {
        play("F");
        EngineSnapshotRepository.Meta older = snapshots.take(System.currentTimeMillis()).join();
        playMore("F");
        play("F2");
        EngineSnapshotRepository.Meta newer = snapshots.take(System.currentTimeMillis()).join();
        assertThat(newer.seq()).isGreaterThan(older.seq());
        String live = liveDigest();

        // a round that was resolved between the two snapshots lost its result row: the newer snapshot cannot be used (the repair of the
        // row needs the round's report, which only a replay of it gives), the older one stands before that round
        String roundId = db.read(c -> db.queryOne(c, "SELECT round_id FROM round_results WHERE status = 'SETTLED' "
                + "AND round_id LIKE 'T_SN_F2%' ORDER BY window_end_ms DESC LIMIT 1", rs -> rs.getString("round_id")));
        assertThat(roundId).isNotNull();
        db.tx(c -> { // a settlement whose rows were never written: none of the three
            db.update(c, "DELETE FROM round_results WHERE round_id = ?", roundId);
            db.update(c, "DELETE FROM player_round_history WHERE round_id = ?", roundId);
            db.update(c, "DELETE FROM oracle_records WHERE round_id = ?", roundId);
            return null;
        });
        try (LogCapture log = LogCapture.start()) {
            rebuild.rebuild();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).contains("engine snapshot " + newer.seq() + " ignored")
                    .contains("no result row"));
        }
        assertThat(rebuild.lastRebuild().snapshotSeq()).as("the older snapshot stands before the round").isEqualTo(older.seq());
        assertThat(roundResults.exists(roundId)).as("and the tail replay repaired the row").isTrue();
        assertThat(liveDigest()).isEqualTo(live);

        // ledger rows missing below the older snapshot's last movement: no snapshot can be used, the replay writes them
        long lastMovement = hub.ledger().lastSequence();
        assertThat(lastMovement).isGreaterThan(3);
        db.tx(c -> db.update(c, "DELETE FROM ledger_entries WHERE seq = 2"));
        try (LogCapture log = LogCapture.start()) {
            rebuild.rebuild();

            assertThat(log.lines()).anySatisfy(line -> assertThat(line).contains("ignored").contains("ledger_entries"));
        }
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        gameService.flushLedgerRows();
        long rewritten = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM ledger_entries WHERE seq = 2", rs -> rs.getLong("n")));
        assertThat(rewritten).as("the full replay wrote the movement again").isEqualTo(1L);
        assertThat(liveDigest()).isEqualTo(live);
    }

    @Test
    void theProjectionsAreRepairedFromTheJournalAlsoWhenTheStateStartsFromASnapshot() {
        play("G");
        snapshots.take(System.currentTimeMillis()).join();
        playMore("G");
        String live = liveDigest();
        String projections = crowdvsmarket.server.testsupport.StateDigest.projections(db, hub);
        String engineLive = crowdvsmarket.server.testsupport.StateDigest.engine(hub);
        // the tables the journal projects are gone, the journal and the snapshot stay
        db.tx(c -> {
            db.update(c, "TRUNCATE TABLE players CASCADE");
            db.update(c, "DELETE FROM markets WHERE id <> 'BTC'");
            db.update(c, "UPDATE server_meta SET value = '0' WHERE key IN ('next_player_index', 'oracle_seq')");
            return null;
        });

        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).as("the snapshot was used").isPositive();
        assertThat(crowdvsmarket.server.testsupport.StateDigest.projections(db, hub)).isEqualTo(projections);
        assertThat(crowdvsmarket.server.testsupport.StateDigest.engine(hub)).isEqualTo(engineLive);
        assertThat(liveDigest()).isEqualTo(live);
    }

    @Test
    void onlyTheNewestSnapshotsAreKept() {
        play("H");
        for (int i = 0; i < 5; i++) {
            snapshots.take(System.currentTimeMillis() + i).join();
            playMore("H" + i);
        }
        List<EngineSnapshotRepository.Meta> stored = snapshots.stored();
        assertThat(stored).hasSize(3);
        assertThat(stored.stream().map(EngineSnapshotRepository.Meta::seq).toList())
                .isSortedAccordingTo(java.util.Comparator.<Long>reverseOrder());
        // the oldest two went, not the newest
        assertThat(stored.get(0).seq()).isGreaterThan(stored.get(2).seq());
        snapshots.take(System.currentTimeMillis()).join();
        assertThat(snapshots.stored()).hasSize(3);
    }

    @Test
    void theCheckReplaysTheJournalFromZeroAndAgreesWithTheSnapshot() {
        play("I");
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        playMore("I"); // the journal is longer than the snapshot: only up to its seq is replayed

        SnapshotService.Check check = snapshots.check(System.currentTimeMillis());

        assertThat(check.ok()).as(check.note()).isTrue();
        assertThat(check.seq()).isEqualTo(taken.seq());
        assertThat(check.replayedSha256()).isEqualTo(check.expectedSha256());
        assertThat(snapshots.status().lastCheck()).isEqualTo(check);
    }

    @Test
    void aSnapshotThatDiffersFromTheReplayIsAnErrorInTheLogAndInTheStatus() {
        play("J");
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        // a snapshot that verifies (its hash is its own) but is not what the journal builds: one balance off
        String text = StateCodec.encode(hub, betFeed);
        String tampered = java.util.regex.Pattern.compile("(\\naccount\\t[^\\t]+\\t)(-?\\d+)").matcher(text)
                .replaceFirst(m -> m.group(1) + "123456789");
        assertThat(tampered).isNotEqualTo(text);
        repository.put(taken.seq(), StateCodec.FORMAT_VERSION, taken.takenAtMs(), StateDigest.sha256(tampered), SnapshotService.gzip(tampered));
        try (LogCapture log = LogCapture.start()) {
            SnapshotService.Check check = snapshots.check(System.currentTimeMillis());

            assertThat(check.ok()).isFalse();
            assertThat(check.expectedSha256()).isNotEqualTo(check.replayedSha256());
            assertThat(log.lines()).anySatisfy(line -> assertThat(line).contains("ENGINE SNAPSHOT MISMATCH").contains("" + taken.seq()));
        }
        assertThat(snapshots.status().lastCheck().ok()).isFalse();
    }

    @Test
    void theCheckHasNothingToSayWithoutASnapshot() {
        SnapshotService.Check check = snapshots.check(System.currentTimeMillis());
        assertThat(check.ok()).isNull();
        assertThat(check.note()).contains("no snapshot");
    }

    @Test
    void theOperatorsStatusShowsTheSnapshots() {
        play("K");
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        rebuild.rebuild();
        snapshots.check(System.currentTimeMillis());

        var response = given().header("Authorization", "Bearer " + operatorToken).get("/api/admin/status");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        assertThat(response.jsonPath().getBoolean("snapshots.enabled")).isTrue();
        assertThat(response.jsonPath().getInt("snapshots.stored")).isEqualTo(1);
        assertThat(response.jsonPath().getLong("snapshots.lastSeq")).isEqualTo(taken.seq());
        assertThat(response.jsonPath().getLong("snapshots.journalLastId")).isEqualTo(eventStore.lastId());
        assertThat(response.jsonPath().getBoolean("snapshots.lastCheck.ok")).isTrue();
        assertThat(response.jsonPath().getLong("snapshots.lastRebuild.snapshotSeq")).isEqualTo(taken.seq());
    }

    @Test
    void aSnapshotIsDueByCountOrByTimeWhenThereIsAnythingNew() {
        assertThat(SnapshotService.due(50_000, 0, 50_000, 60)).as("by count").isTrue();
        assertThat(SnapshotService.due(49_999, 59 * 60_000L, 50_000, 60)).as("neither").isFalse();
        assertThat(SnapshotService.due(1, 60 * 60_000L, 50_000, 60)).as("by time").isTrue();
        assertThat(SnapshotService.due(0, 24 * 60 * 60_000L, 50_000, 60)).as("by time, but nothing new").isFalse();
    }
}
