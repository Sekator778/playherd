package crowdvsmarket.server.jfr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * I-1..I-6 on the real application: with a JFR recording that enables the Herd events, a bet, a rejected bet, a settlement,
 * a rebuild and two RPC calls produce {@code herd.PlaceBet}, {@code herd.Settle}, {@code herd.Rebuild}, {@code herd.TxCommit},
 * {@code herd.Rpc}, {@code herd.Session} and {@code herd.LockHold} events with the fields the report reads. That the instrumented
 * code stores the same bytes is {@code ScriptedScenarioPayloadsTest}'s job.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class HerdJfrEventsTest extends LiveRpcTestBase {

    @Test
    void theHerdEventsAreEmittedWhenJfrRecords() throws Exception {
        Market market = list("T_JFR", MarketKind.PERP, Category.CRYPTO, "JFR", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        Session session = signIn("jfr");
        String p1 = session.playerId();
        String p2 = signUp("jfr2");

        List<RecordedEvent> events = record(() -> {
            try {
                ok(session, "currentPlayer");
                ok(rpc(null, "serverInfo"));
                gameService.placeBet(p1, bet(betting, Enums.Direction.UP, 10, "300.000"), betting.startMs() - 1);
                gameService.placeBet(p2, bet(betting, Enums.Direction.DOWN, 5, "200.000"), betting.startMs() - 1);
                assertThatThrownBy(() -> gameService.placeBet(p2, bet(betting, Enums.Direction.UP, 5, "1.000"),
                        betting.startMs() - 1)).isInstanceOf(ApiException.class);
                scheduler.tick(running.endMs());
                prices.script(market, betting, "2500.000", "2550.000", "2480.000", "2600.000");
                assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();
                assertThat(gameService.resolveRound(betting, betting.endMs())).isNull(); // already settled: skipped
                rebuild.rebuild(betting.endMs());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        List<RecordedEvent> bets = named(events, "herd.PlaceBet");
        assertThat(bets).extracting(e -> e.getString("outcome")).containsExactly("OK", "OK", "STAKE_BELOW_MINIMUM");
        assertThat(bets.get(1).getLong("roundBets")).as("bets of the round before this one").isEqualTo(1);
        assertThat(bets.get(0).getDuration()).isGreaterThanOrEqualTo(Duration.ofNanos(bets.get(0).getLong("persistNs")));
        assertThat(bets.get(0).getLong("persistNs")).isPositive();
        assertThat(bets.get(0).getLong("applyNs")).isPositive();

        List<RecordedEvent> settles = named(events, "herd.Settle");
        assertThat(settles).extracting(e -> e.getString("outcome")).contains("SETTLED", "SKIPPED");
        RecordedEvent settled = settles.stream().filter(e -> e.getString("outcome").equals("SETTLED")
                && e.getInt("bets") == 2).findFirst().orElseThrow();
        assertThat(settled.getLong("storeNs")).isPositive();
        assertThat(settled.getLong("appendNs")).isPositive();
        assertThat(settled.getLong("lockedNs")).isGreaterThanOrEqualTo(settled.getLong("storeNs"));

        List<RecordedEvent> rebuilds = named(events, "herd.Rebuild");
        assertThat(rebuilds).isNotEmpty();
        assertThat(rebuilds.get(rebuilds.size() - 1).getInt("events")).isPositive();
        assertThat(rebuilds.get(rebuilds.size() - 1).getLong("heapAfter")).isPositive();

        assertThat(named(events, "herd.TxCommit")).isNotEmpty().allSatisfy(e -> assertThat(e.getBoolean("committed")).isTrue());
        assertThat(named(events, "herd.TxCommit")).anySatisfy(e -> assertThat(e.getLong("commitNs")).isPositive());

        assertThat(named(events, "herd.Rpc")).extracting(e -> e.getString("method")).contains("currentPlayer", "serverInfo");
        assertThat(named(events, "herd.Rpc")).extracting(e -> e.getString("result")).contains("200");
        assertThat(named(events, "herd.Session")).anySatisfy(e -> assertThat(e.getBoolean("found")).isTrue());

        // the stack of a hold event names the GameService method that held the lock
        assertThat(named(events, "herd.LockHold")).anySatisfy(e -> {
            assertThat(e.getString("mode")).isEqualTo("write");
            assertThat(e.getStackTrace().getFrames().stream().map(RecordedFrame::getMethod)
                    .map(m -> m.getType().getName() + "." + m.getName()))
                    .contains("crowdvsmarket.server.service.GameService.placeBet");
        });
        assertThat(named(events, "herd.LockWait")).isNotEmpty();
    }

    private static List<RecordedEvent> named(List<RecordedEvent> events, String name) {
        return events.stream().filter(e -> e.getEventType().getName().equals(name)).toList();
    }

    private static List<RecordedEvent> record(Runnable body) throws Exception {
        Path file = Files.createTempFile("herd-events", ".jfr");
        try (Recording recording = new Recording()) {
            for (String name : List.of("herd.LockWait", "herd.LockHold", "herd.PlaceBet", "herd.TxCommit", "herd.Settle",
                    "herd.Rebuild", "herd.Rpc", "herd.Session")) {
                recording.enable(name).withThreshold(Duration.ZERO).withStackTrace();
            }
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
