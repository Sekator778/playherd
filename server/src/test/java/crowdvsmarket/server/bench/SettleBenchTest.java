package crowdvsmarket.server.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * Settlement of a hot round: how long {@code resolveRound} holds the engine lock and how long it takes in all
 * (docs/research/settle_outside_lock_2026-10-18.md §7). A bench, not a gate: it runs only with
 * {@code -Dherd.bench.settle=true} ({@code -Dherd.bench.bets=10000}, {@code -Dherd.bench.repeats=3}); the numbers
 * come from the {@code herd.Settle} JFR event of each call and from the clock around the call.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SettleBenchTest extends LiveGameTestBase {

    @Test
    void settlementOfAHotRound() throws Exception {
        assumeTrue(Boolean.getBoolean("herd.bench.settle"), "bench: run with -Dherd.bench.settle=true");
        int bets = Integer.getInteger("herd.bench.bets", 10_000);
        int repeats = Integer.getInteger("herd.bench.repeats", 3);
        long now = System.currentTimeMillis();
        List<Market> markets = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            Market m = list("T_SB" + i, MarketKind.PERP, Category.CRYPTO, "SB" + i, 25, 1);
            prices.setPrice(m, "2500.000");
            markets.add(m);
        }
        String[] base = {"hourly-sec", "60", "daily-sec", "120"};
        run("seed", "players", Integer.toString(bets), "seed", "7", "now-ms", Long.toString(now), base[0], base[1], base[2], base[3]);
        List<RoundWindow> windows = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            Map<String, String> hot = run("hotround", "bets", Integer.toString(bets), "seed", Integer.toString(5 + i),
                    "market", markets.get(i).id(), "lead-sec", "20", "now-ms", Long.toString(now), base[0], base[1], base[2], base[3]);
            windows.add(new RoundWindow(markets.get(i).id(), Interval.HOURLY, Long.parseLong(hot.get("window_start_ms")),
                    Long.parseLong(hot.get("window_end_ms"))));
        }
        rebuild.rebuild(now);
        for (int i = 0; i < repeats; i++) {
            prices.script(markets.get(i), windows.get(i), "2500.000", "2510.000", "2490.000", "2520.000");
            // the series' cursor stands on the hot round, as it does when the scheduler is about to resolve it
            roundProgress.seed(windows.get(i).market(), Interval.HOURLY, windows.get(i));
        }

        for (int i = 0; i < repeats; i++) {
            RoundWindow w = windows.get(i);
            long[] wall = new long[1];
            long[] cursorMovedNs = {-1};
            List<RecordedEvent> events = record(() -> {
                long t = System.nanoTime();
                // "the next round accepts bets" is the moment the series' cursor leaves the window: a watcher thread
                // polls it, as the bets' own check does (GameService#placeBet reads the same cursor)
                Thread watcher = new Thread(() -> {
                    while (cursorMovedNs[0] < 0 && System.nanoTime() - t < 120_000_000_000L) {
                        if (!w.equals(roundProgress.findNext(w.market(), w.interval()).orElse(null))) {
                            cursorMovedNs[0] = System.nanoTime() - t;
                        }
                    }
                });
                watcher.start();
                try {
                    RoundReport report = gameService.resolveRound(w, w.endMs());
                    assertThat(report.playerOutcomes()).hasSize(bets);
                    // what the scheduler does when resolveRound returns (a no-op when the cursor has moved already)
                    roundProgress.advance(w.market(), w.interval(), w);
                    watcher.join();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                wall[0] = System.nanoTime() - t;
            });
            RecordedEvent s = events.stream().filter(e -> e.getEventType().getName().equals("herd.Settle")
                    && e.getString("outcome").equals("SETTLED")).findFirst().orElseThrow();
            System.out.printf("SETTLE-BENCH run=%d bets=%d total_ms=%.1f locked_ms=%.1f store_ms=%.1f apply_ms=%.1f append_ms=%.1f oracle_ms=%.1f next_round_open_ms=%.1f%n",
                    i + 1, bets, wall[0] / 1e6, s.getLong("lockedNs") / 1e6, s.getLong("storeNs") / 1e6,
                    s.getLong("applyNs") / 1e6, s.getLong("appendNs") / 1e6, s.getLong("oracleNs") / 1e6, cursorMovedNs[0] / 1e6);
            assertThat(roundResults.exists(w.roundId())).isTrue();
        }
    }

    private Map<String, String> run(String mode, String... kv) {
        BenchJournal.Args args = BenchJournal.Args.of(kv);
        return db.tx(c -> {
            try {
                return BenchJournal.run(c, mode, args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static List<RecordedEvent> record(Runnable body) throws Exception {
        Path file = Files.createTempFile("herd-settle-bench", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("herd.Settle").withThreshold(Duration.ZERO);
            recording.start();
            body.run();
            recording.stop();
            recording.dump(file);
        }
        try {
            return RecordingFile.readAllEvents(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
