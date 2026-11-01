package crowdvsmarket.server.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.auth.SessionService;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A journal made by the fabricator (seed, history, hot round) is accepted by the server (docs/research/load_bench_design_2026-10-17.md
 * §4.1-4.3): the engine rebuild replays it without an error and reaches the totals the fabricator's own engine reached, the
 * seeded session cookies authenticate, the next new player gets the next index, the hot round settles live with all its bets,
 * and every call is idempotent.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class FabricatedJournalRebuildTest extends LiveGameTestBase {

    @Inject
    crowdvsmarket.server.journal.Journal journal;
    @Inject
    SessionService sessions;
    @Inject
    PlayerDirectory directory;

    private Map<String, String> run(String mode, String... kv) {
        BenchJournal.Args args = BenchJournal.Args.of(kv);
        Map<String, String> result = db.tx(c -> {
            try {
                return BenchJournal.run(c, mode, args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        journal.reimportFromTableForTests(); // D-107: the fabricator fills the table, as an old server's; the first start imports it into the journal
        return result;
    }

    private long eventRows() {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events", rs -> rs.getLong("n")));
    }

    @Test
    void theServerRebuildsFromAFabricatedJournalAndSettlesItsHotRound() throws Exception {
        Market a = list("T_FA", MarketKind.PERP, Category.CRYPTO, "FAA", 25, 1);
        Market b = list("T_FB", MarketKind.PERP, Category.CRYPTO, "FAB", 25, 1);
        prices.setPrice(a, "2500.000");
        prices.setPrice(b, "100.000");
        String[] round = {"hourly-sec", "60", "daily-sec", "120"};
        long now = System.currentTimeMillis();

        Map<String, String> seeded = run("seed", "players", "300", "seed", "7", "now-ms", Long.toString(now),
                "hourly-sec", "60", "daily-sec", "120");
        assertThat(seeded.get("players")).isEqualTo("300");
        long afterSeed = eventRows();
        assertThat(run("seed", "players", "300", "seed", "7", "now-ms", Long.toString(now)).get("result"))
                .startsWith("already seeded");
        assertThat(eventRows()).isEqualTo(afterSeed);
        assertThatThrownBy(() -> run("seed", "players", "301", "seed", "7")).hasMessageContaining("--reset");

        Map<String, String> history = run("history", "events", "3000", "bets-per-round", "10", "seed", "3", "now-ms",
                Long.toString(now), "hourly-sec", "60", "daily-sec", "120");
        assertThat(Long.parseLong(history.get("events_written"))).isBetween(2500L, 3600L);
        long afterHistory = eventRows();
        assertThat(afterHistory).isEqualTo(afterSeed + Long.parseLong(history.get("events_written")));
        assertThat(run("history", "events", "3000", "bets-per-round", "10", "seed", "3", "hourly-sec", "60", "daily-sec", "120")
                .get("result")).startsWith("already fabricated");
        assertThat(eventRows()).isEqualTo(afterHistory);

        Map<String, String> hot = run("hotround", "bets", "100", "seed", "5", "market", "T_FA", "lead-sec", "20", "now-ms",
                Long.toString(now), "hourly-sec", "60", "daily-sec", "120");
        assertThat(hot.get("bets")).isEqualTo("100");
        RoundWindow hotWindow = new RoundWindow("T_FA", Interval.HOURLY, Long.parseLong(hot.get("window_start_ms")),
                Long.parseLong(hot.get("window_end_ms")));
        assertThat(hotWindow.roundId()).isEqualTo(hot.get("round_id"));

        // the server rebuilds from it
        rebuild.rebuild(now);
        assertThat(rebuild.isReady()).isTrue();
        assertThat(directory.all()).hasSize(300);
        assertThat(hub.ledger().playerIds()).hasSize(300);
        assertThat(Integer.toString(hub.ledger().playerIds().size())).isEqualTo(hot.get("ledger_players"));
        assertThat(Long.toString(hub.ledger().playersTotal().mojos())).isEqualTo(hot.get("players_total_mojos"));
        assertThat(Long.toString(hub.ledger().openStakes().mojos())).isEqualTo(hot.get("open_stakes_mojos"));
        assertThat(hub.engine("T_FA", Interval.HOURLY).betBook().betsFor(hotWindow.roundId())).hasSize(100);
        Map<String, String> verified = run("verify");
        assertThat(verified.get("ledger_players")).isEqualTo("300");
        assertThat(verified.get("players_total_mojos")).isEqualTo(hot.get("players_total_mojos"));

        // seeded sessions are real sessions; the next new player continues the index
        for (int i : new int[] {0, 17, 299}) {
            assertThat(sessions.playerIdFor(BenchJournal.sessionId(7, i))).contains(BenchJournal.playerId(7, i));
        }
        assertThat(directory.byId(BenchJournal.playerId(7, 299)).orElseThrow().playerIndex()).isEqualTo((1 << 20) + 299);
        String fresh = gameService.registerOrTouchPlayer("txch1freshplayer" + "0".repeat(40), now).player().playerId();
        assertThat(directory.byId(fresh).orElseThrow().playerIndex()).isEqualTo((1 << 20) + 300);

        // the hot round settles live, with all its bets
        prices.script(a, hotWindow, "2500.000", "2510.000", "2490.000", "2520.000");
        RoundReport report = gameService.resolveRound(hotWindow, hotWindow.endMs());
        assertThat(report).isNotNull();
        assertThat(report.playerOutcomes()).hasSize(100);
    }
}
