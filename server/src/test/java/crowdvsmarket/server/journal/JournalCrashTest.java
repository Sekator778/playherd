package crowdvsmarket.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Bots;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.snapshot.StateDigest;
import crowdvsmarket.server.testsupport.CleanDatabasePerTestClass;
import crowdvsmarket.server.testsupport.JournalAsyncProfile;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The journal is the truth and {@code game_events} its copy (Р-107), with the production setting: the game does not wait for the copy.
 * Each test is a crash or a failure at one place between the player's request and the table:
 * <ul>
 *   <li>the journal's append fails before the sync (the callers get 503, the engine is rebuilt, nothing of the batch survives, the retry works);
 *   <li>the batch is synced and the process dies before the projection (the restart replays it: no accepted command is lost; the copy
 *       gets the rows once);
 *   <li>the projector dies in the middle of a batch (the transaction left nothing; the retry writes the rows once);
 *   <li>the projector is gone for a while (the game goes on; a restart resumes where the table ends);
 *   <li>what is not in the journal (invite, referrer, API key) is readable at once, before any projection.
 * </ul>
 */
@QuarkusTest
@TestProfile(JournalAsyncProfile.class)
class JournalCrashTest extends LiveGameTestBase {

    @Inject
    Journal journal;
    @Inject
    JournalProjector projector;
    @Inject
    EventStore eventStore;
    @Inject
    BetFeed betFeed;
    @Inject
    PlayerDirectory players;

    @BeforeEach
    void emptyGame() {
        projector.restartForTests();
        projector.beforeCommitForTests(null);
        journal.store().hook(null);
        CleanDatabasePerTestClass.resetDatabaseAndMemory();
        awaitCopy();
    }

    @AfterEach
    void healEverything() {
        projector.beforeCommitForTests(null);
        journal.store().hook(null);
        projector.restartForTests();
        CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    private void awaitCopy() {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(20))
                .until(() -> projector.status().lag() == 0 && projector.status().running());
    }

    private long tableRows() {
        return num("SELECT COUNT(*) AS n FROM game_events");
    }

    private long distinctTableIds() {
        return num("SELECT COUNT(DISTINCT id) AS n FROM game_events");
    }

    private List<Long> journalSeqs() {
        List<Long> seqs = new ArrayList<>();
        journal.store().replay(0, Long.MAX_VALUE, r -> seqs.add(r.seq()));
        return seqs;
    }

    private List<Long> tableIds() {
        return db.read(c -> db.queryList(c, "SELECT id FROM game_events ORDER BY id", rs -> rs.getLong("id")));
    }


    private long num(String sql, Object... params) {
        Long v = db.read(c -> db.queryOne(c, sql, rs -> rs.getLong(1), params));
        return v == null ? 0L : v;
    }

    private String str(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getString(1), params));
    }

    private String digest() {
        return StateDigest.of(hub, betFeed);
    }

    // ---------------------------------------------------------------------------------------

    @Test
    void theCopyHasTheJournalsIdsAndRowsOnceTheProjectorHasCaughtUp() {
        for (int i = 0; i < 6; i++) {
            signUp("copy" + i);
        }
        awaitCopy();
        List<JournalRecord> records = new ArrayList<>();
        journal.store().replay(0, Long.MAX_VALUE, records::add);
        assertThat(records).hasSizeGreaterThanOrEqualTo(6);
        assertThat(tableIds()).isEqualTo(records.stream().map(JournalRecord::seq).toList());
        List<Object[]> rows = db.read(c -> db.queryList(c,
                "SELECT id, event_type, payload::text AS p, at_ms, schema_version, market, player_id FROM game_events ORDER BY id",
                rs -> new Object[] {rs.getLong("id"), rs.getString("event_type"), rs.getString("p"), rs.getLong("at_ms"),
                        rs.getInt("schema_version"), rs.getString("market"), rs.getString("player_id")}));
        for (int i = 0; i < records.size(); i++) {
            JournalRecord r = records.get(i);
            Object[] row = rows.get(i);
            assertThat(row[1]).isEqualTo(r.type());
            assertThat(parse((String) row[2])).as("payload of %d", r.seq()).isEqualTo(parse(r.payload()));
            assertThat(row[3]).isEqualTo(r.atMs());
            assertThat(row[4]).isEqualTo(r.schemaVersion());
            assertThat(row[5]).isEqualTo(r.market());
            assertThat(row[6]).isEqualTo(r.playerId());
        }
        assertThat(num("SELECT COUNT(*) AS n FROM players")).isEqualTo(6L);
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return db.json().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAppendThatFailsBeforeTheSyncAnswers503LeavesNothingAndTheRetryWorks() {
        String keeper = signUp("keeper");
        awaitCopy();
        String before = digest();
        long seqBefore = journal.lastSeq();
        String wallet = "txch1live-crash-" + UUID.randomUUID().toString().replace("-", "");
        AtomicBoolean armed = new AtomicBoolean(true);
        journal.store().hook(p -> {
            if (p == ChronicleJournalStore.Point.AFTER_WRITE && armed.get()) {
                throw new IllegalStateException("injected: died after the write, before the sync");
            }
        });

        assertThatThrownBy(() -> gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY));

        armed.set(false);
        journal.store().hook(null);
        assertThat(journal.lastSeq()).as("nothing became durable").isEqualTo(seqBefore);
        assertThat(players.playerIdForWallet(wallet)).as("the player of the failed batch is not known").isEmpty();
        assertThat(digest()).as("the engine was rebuilt from the journal: the state before the batch").isEqualTo(before);

        // the retry is a registration like the first one, once
        String player = gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()).player().playerId();
        awaitCopy();
        assertThat(players.playerIdForWallet(wallet)).contains(player);
        long registrations = num("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'PlayerRegistered' AND payload->>'walletAddress' = ?", wallet);
        assertThat(registrations).isEqualTo(1);
        assertThat(journalSeqs()).as("the numbers of the failed batch are spent, never reused, never delivered").doesNotHaveDuplicates();
        assertThat(tableIds()).isEqualTo(journalSeqs());
        assertThat(gameService.account(keeper).balance()).isNotBlank();

        // and a rebuild from the journal reproduces the state, the failed batch not in it
        String live = digest();
        rebuild.rebuild();
        assertThat(digest()).isEqualTo(live);
        assertThat(players.playerIdForWallet(wallet)).contains(player);
    }

    @Test
    void aBatchThatIsSyncedAndNotYetProjectedSurvivesTheRestartAndTheCopyGetsItOnce() {
        projector.stopForTests(); // the process "dies" after the journal's sync: the copy has not seen the batch
        String player = signUp("synced");
        String live = digest();
        long journalEnd = journal.lastSeq();
        assertThat(tableRows()).as("the copy is behind").isLessThan(journalSeqs().size());
        assertThat(num("SELECT COUNT(*) AS n FROM players WHERE player_id = ?", player))
                .as("the players row is a projection too: not there yet").isZero();

        // the restart: the engine is rebuilt from the journal alone; the rebuild first brings the copy up to the journal's end
        rebuild.rebuild();
        assertThat(digest()).as("no accepted command is lost").isEqualTo(live);
        assertThat(players.playerIdForWallet(wallet(player))).isPresent();
        assertThat(num("SELECT COUNT(*) AS n FROM players WHERE player_id = ?", player))
                .isEqualTo(1L);
        assertThat(tableIds()).isEqualTo(journalSeqs());

        projector.restartForTests();
        awaitCopy();
        assertThat(distinctTableIds()).isEqualTo(tableRows());
        assertThat(tableIds()).as("each row once, no duplicate, nothing missing").isEqualTo(journalSeqs());
        assertThat(journal.lastSeq()).isEqualTo(journalEnd);
    }

    @Test
    void aProjectorBatchThatDiesInTheMiddleLeavesNothingAndIsWrittenOnceOnTheRetry() {
        awaitCopy();
        long rowsBefore = tableRows();
        AtomicBoolean armed = new AtomicBoolean(true);
        projector.beforeCommitForTests(() -> {
            if (armed.getAndSet(false)) {
                throw new IllegalStateException("injected: the projector died after the rows were written, before the commit");
            }
        });
        String a = signUp("mid-a");
        String b = signUp("mid-b");

        await().atMost(Duration.ofSeconds(20)).until(() -> projector.status().failures() > 0 || projector.status().lag() == 0);
        awaitCopy(); // the retry (after the back-off) writes the batch
        projector.beforeCommitForTests(null);
        assertThat(armed.get()).as("the failure was injected").isFalse();
        assertThat(tableIds()).as("the failed transaction left no row; the retry wrote each once").isEqualTo(journalSeqs());
        assertThat(tableRows()).isGreaterThan(rowsBefore);
        assertThat(players.byId(a)).isPresent();
        assertThat(players.byId(b)).isPresent();
        assertThat(num("SELECT COUNT(*) AS n FROM players WHERE player_id IN (?, ?)", a, b))
                .isEqualTo(2L);
        assertThat(projector.status().failures()).as("the status is clean again").isZero();
    }

    @Test
    void theGameGoesOnWithoutTheProjectorAndItsRestartResumesWhereTheTableEnds() {
        awaitCopy();
        projector.stopForTests();
        long tableAtStop = tableRows();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(signUp("gone" + i)); // the game answers although the copy does not move
        }
        for (String id : ids) {
            assertThat(gameService.account(id).balance()).isNotBlank();
        }
        assertThat(tableRows()).isEqualTo(tableAtStop);
        assertThat(projector.status().running()).isFalse();
        assertThat(projector.status().lag()).as("the lag is visible in the status").isGreaterThanOrEqualTo(5);

        projector.restartForTests();
        awaitCopy();
        assertThat(tableIds()).isEqualTo(journalSeqs());
        assertThat(projector.status().lag()).isZero();
    }

    @Test
    void aRebuildSeesANicknameTheProjectorHasNotWrittenYet() {
        String player = signUp("nickrebuild");
        awaitCopy();
        projector.stopForTests();
        gameService.setNickname(player, "Brand New");
        assertThat(str("SELECT nickname AS n FROM players WHERE player_id = ?", player))
                .as("the table is behind the journal").isNotEqualTo("Brand New");

        rebuild.rebuild();

        assertThat(gameService.summary(player).nickname()).as("the directory agrees with the journal").isEqualTo("Brand New");
        assertThat(str("SELECT nickname AS n FROM players WHERE player_id = ?", player))
                .as("and the rebuild brought the table up first").isEqualTo("Brand New");
        projector.restartForTests();
        awaitCopy();
        assertThat(tableIds()).isEqualTo(journalSeqs());
    }

    @Test
    void whatIsNotInTheJournalIsReadableBeforeAnyProjection() {
        awaitCopy();
        projector.stopForTests();
        long now = System.currentTimeMillis();
        String inviter = signUp("inviter");
        String code = gameService.myInvite(inviter).code();
        assertThat(code).as("the invite code is in player_sidecar at once").isNotBlank();
        String invited = gameService.registerOrTouchPlayer("txch1live-inv-" + UUID.randomUUID().toString().replace("-", ""), now,
                "203.0.113.7", code, "tg").player().playerId();
        assertThat(gameService.myInvite(inviter).referredCount()).isEqualTo(1);
        assertThat(invited).isNotEqualTo(inviter);

        Bots.ApiBotKeyDto bot = gameService.createApiBot(inviter, "Crash Bot", now);
        assertThat(gameService.apiBotForKey(bot.apiKey())).as("the key opens the bot before the row is projected").isPresent();
        Bots.ApiBotKeyDto rotated = gameService.regenerateApiKey(inviter, bot.bot().playerId());
        assertThat(gameService.apiBotForKey(bot.apiKey())).isEmpty();
        assertThat(gameService.apiBotForKey(rotated.apiKey())).isPresent();
        assertThat(rows("SELECT COUNT(*) AS n FROM players WHERE player_id = '" + bot.bot().playerId() + "'")).isZero();

        projector.restartForTests();
        awaitCopy();
        // the registration is projected with the sidecar's columns: the key of the rotation, the referrer, the code
        String hash = str("SELECT api_key_hash FROM players WHERE player_id = ?", bot.bot().playerId());
        assertThat(hash).isEqualTo(str("SELECT api_key_hash FROM player_sidecar WHERE player_id = ?", bot.bot().playerId())).isNotNull();
        assertThat(str("SELECT referred_by FROM players WHERE player_id = ?", invited)).isEqualTo(inviter);
        assertThat(str("SELECT invite_code AS r FROM players WHERE player_id = ?", inviter))
                .isEqualTo(code);

        // a revoked key stays revoked in the copy too
        gameService.revokeApiKey(inviter, bot.bot().playerId());
        assertThat(gameService.apiBotForKey(rotated.apiKey())).isEmpty();
        assertThat(str("SELECT api_key_hash FROM players WHERE player_id = ?", bot.bot().playerId())).isNull();
    }

    private long rows(String sql) {
        return num(sql);
    }

    private String wallet(String playerId) {
        return players.byId(playerId).orElseThrow().walletAddress();
    }

    @Test
    void aRegistrationThatDoesNotHappenGivesBackThePlaceItSpent() {
        awaitCopy();
        String ip = "198.51.100." + (int) (Math.random() * 200 + 1);
        long now = System.currentTimeMillis();
        String wallet = "txch1live-place-" + UUID.randomUUID().toString().replace("-", "");
        journal.store().hook(p -> {
            if (p == ChronicleJournalStore.Point.AFTER_WRITE) {
                throw new IllegalStateException("injected");
            }
        });
        assertThatThrownBy(() -> gameService.registerOrTouchPlayer(wallet, now, ip, null, "tg")).isInstanceOf(ApiException.class);
        journal.store().hook(null);
        assertThat(rows("SELECT COUNT(*) AS n FROM registration_ips")).as("the place of the address is given back").isZero();
        assertThat(rows("SELECT COUNT(*) AS n FROM player_sidecar WHERE source = 'tg'")).isZero();
    }

    @Test
    void aReservedDepositCoinWithoutItsEventGetsItFromTheRebuild() {
        awaitCopy();
        String player = signUp("depositor");
        int index = players.byId(player).orElseThrow().playerIndex();
        String before = gameService.account(player).balance();
        long now = System.currentTimeMillis();
        // the process died between the reservation of the coin (committed) and the append of its event
        db.tx(c -> db.update(c, "INSERT INTO deposit_confirmations (coin_id, player_id, player_index, amount_mojos, height, credited_at_ms) "
                + "VALUES (?, ?, ?, ?, ?, ?)", "0xlostcoin" + UUID.randomUUID(), player, index, 5_000L, 123L, now));

        rebuild.rebuild();

        assertThat(gameService.account(player).balance()).as("the credit that the crash delayed").isNotEqualTo(before);
        long credits = eventStore.loadOfTypes(List.of("DepositConfirmedOnChain")).size();
        assertThat(credits).isEqualTo(1);
        rebuild.rebuild();
        assertThat(eventStore.loadOfTypes(List.of("DepositConfirmedOnChain"))).as("the second rebuild finds the event and adds none").hasSize(1);
    }

    @Test
    void aCancelledRoundWhoseRowWasNeverWrittenGetsItFromTheRebuild() {
        awaitCopy();
        Market market = list("T_JCRASH", MarketKind.PERP, Category.CRYPTO, "JCR", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String player = signUp("canceller");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "100.000"), betting.startMs() - 1);
        String balanceWithBet = gameService.account(player).balance();

        gameService.cancelRound(betting, "test cancel", System.currentTimeMillis());
        assertThat(roundResults.exists(betting.roundId())).isTrue();
        // the process died after the RoundCancelled event was durable and before its result row was written
        db.tx(c -> db.update(c, "DELETE FROM round_results WHERE round_id = ?", betting.roundId()));
        String live = digest();

        rebuild.rebuild();

        assertThat(roundResults.exists(betting.roundId())).as("the row is written again from the journal").isTrue();
        assertThat(num("SELECT refunded_bet_count FROM round_results WHERE round_id = ?", betting.roundId())).isEqualTo(1L);
        assertThat(digest()).isEqualTo(live);
        assertThat(gameService.account(player).balance()).isNotEqualTo(balanceWithBet);
    }

    @Test
    void theCopyKeepsUpWithABetLoadWhileTheJournalIsTheOnlyThingTheWriterWaitsFor() {
        Market market = list("T_JLOAD", MarketKind.PERP, Category.CRYPTO, "JLD", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ps.add(signUp("load" + i));
        }
        for (int round = 0; round < 10; round++) {
            for (String p : ps) {
                gameService.placeBet(p, bet(betting, round % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN, 5, "100.000"),
                        betting.startMs() - 1);
            }
        }
        awaitCopy();
        assertThat(tableIds()).isEqualTo(journalSeqs());
        assertThat(rows("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced'")).isEqualTo(200L);
        Set<Long> distinct = Set.copyOf(tableIds());
        assertThat(distinct).hasSize(tableIds().size());
    }
}
