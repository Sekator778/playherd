package crowdvsmarket.server.bench;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fabricator's events are the server's bytes (docs/research/load_bench_design_2026-10-17.md §4.3, step 3): the scripted
 * scenario of {@code ScriptedScenarioPayloadsTest} is run by the real {@code GameService}; each event it stored is then built
 * again by {@link BenchWorld}'s factories from the same inputs (player, window, direction, leverage, stake, instants, prices; the
 * fees and the liquidation parameters are recomputed, not copied), applied to the fabricator's own engine, written through the
 * fabricator's {@code COPY} into a scratch table and read back as {@code payload::text}. The rows must be identical to the
 * real ones: event type, payload text, instant, schema version. The fabricator's engine must also end with the real balances.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class FabricatedBytesTest extends LiveGameTestBase {

    record Row(String type, String payload, long atMs, int schemaVersion) {
    }

    @Test
    void theFabricatedRowsAreTheRowsTheServerWrote() throws Exception {
        long firstEventId = db.read(c -> db.queryOne(c, "SELECT COALESCE(MAX(id), 0) AS n FROM game_events", rs -> rs.getLong("n")));
        Market market = list("T_FAB", MarketKind.PERP, Category.CRYPTO, "FAB", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        long at = betting.startMs() - 1;

        List<String> players = new ArrayList<>();
        for (String label : List.of("alpha", "beta", "gamma")) {
            players.add(gameService.registerOrTouchPlayer("txch1fabricated" + label + "0".repeat(40), at).player().playerId());
        }
        gameService.placeBet(players.get(0), bet(betting, Enums.Direction.UP, 10, "300.000"), at);
        gameService.placeBet(players.get(1), bet(betting, Enums.Direction.DOWN, 5, "200.000"), at);
        gameService.placeBet(players.get(2), bet(betting, Enums.Direction.UP, 2, "100.000"), at);
        gameService.placeBet(players.get(1), bet(betting, Enums.Direction.UP, 4, "250.000"), at);
        gameService.cancelBet(players.get(2), betting.roundId(), at);
        gameService.placeBet(players.get(2), bet(betting, Enums.Direction.DOWN, 3, "120.500"), at);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "2500.000", "2550.000", "2480.000", "2600.000");
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();
        RoundWindow next = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(players.get(0), bet(next, Enums.Direction.DOWN, 7, "100.001"), next.startMs() - 1);
        gameService.cancelRoundById(next.roundId(), "scripted scenario", next.startMs() - 1);

        // what the server stored: this scenario's hourly events of the market and the players, and the cutover
        List<Row> real = new ArrayList<>();
        for (Row r : readRows("game_events", firstEventId)) {
            boolean daily = r.payload().contains("\"interval\": \"DAILY\"");
            if (!daily && (r.payload().contains(market.id()) || players.stream().anyMatch(r.payload()::contains))) {
                real.add(r);
            }
        }
        List<Row> cutover = readRows("game_events", 0).stream().filter(r -> r.type().equals("EconomyCutover")).toList();
        assertThat(real).extracting(Row::type).contains("PlayerRegistered", "BetPlaced", "BetCancelled", "RoundResolved",
                "RoundCancelled");

        // the same events, built from their inputs by the fabricator
        BenchWorld world = new BenchWorld();
        for (Market m : registry.all()) {
            world.install(m);
        }
        List<Row> fabricated = new ArrayList<>();
        BenchWorld.Sink sink = (type, json, atMs) -> fabricated.add(new Row(type, json, atMs, 2));
        for (Row r : cutover) {
            GameEvent.EconomyCutover e = (GameEvent.EconomyCutover) world.decode(r.type(), r.payload());
            world.emit(BenchWorld.cutover(e.openingTotalMojos(), e.atMs()), r.atMs(), sink);
        }
        int cutovers = fabricated.size();
        for (Row r : real) {
            GameEvent real1 = world.decode(r.type(), r.payload());
            GameEvent built = switch (real1) {
                case GameEvent.PlayerRegistered e -> e.isRegistration()
                        ? BenchWorld.registeredPlayer(e.playerId(), e.atMs(), e.walletAddress(), e.playerIndex(), e.displayName(),
                                e.kind(), e.botStrategy(), e.ownerPlayerId(), e.replacesPlayerId())
                        : BenchWorld.registered(e.playerId(), e.atMs());
                case GameEvent.BetPlaced e -> BenchWorld.bet(world.markets.get(e.market()),
                        window(e.market(), e.interval(), e.windowStart(), e.windowEnd()), e.playerId(), e.direction(),
                        e.leverage(), e.stakeMojos(), e.atMs());
                case GameEvent.BetCancelled e -> BenchWorld.cancel(world.markets.get(e.market()),
                        window(e.market(), e.interval(), e.windowStart(), e.windowEnd()), e.playerId(), e.atMs());
                case GameEvent.RoundResolved e -> BenchWorld.resolved(world.markets.get(e.market()),
                        window(e.market(), e.interval(), e.windowStart(), e.windowEnd()), e.openPrice(), e.closePrice(),
                        EventApplier.toCandles(e.path()), e.atMs(), e.venue(), e.oracleSeq());
                case GameEvent.RoundCancelled e -> BenchWorld.cancelled(world.markets.get(e.market()),
                        window(e.market(), e.interval(), e.windowStart(), e.windowEnd()), e.reason(), e.atMs());
                // listings and closings are the registry's, not a bet or a round: carried over as they are
                case GameEvent.MarketListed e -> e;
                case GameEvent.MarketClosed e -> e;
                default -> throw new IllegalStateException("unexpected " + r.type());
            };
            world.emit(built, r.atMs(), sink);
        }

        // through the fabricator's COPY, into a scratch table, read back as jsonb text
        List<Row> back = db.tx(c -> {
            try {
                db.update(c, "CREATE TEMP TABLE fab_events (event_type TEXT, payload JSONB, at_ms BIGINT, schema_version INT) ON COMMIT DROP");
                try (BenchJournal.Copy copy = new BenchJournal.Copy(c,
                        "COPY fab_events (event_type, payload, at_ms, schema_version) FROM STDIN")) {
                    for (Row r : fabricated) {
                        copy.row(r.type(), r.payload(), r.atMs(), r.schemaVersion());
                    }
                }
                return db.queryList(c, "SELECT event_type, payload::text AS p, at_ms, schema_version FROM fab_events",
                        rs -> new Row(rs.getString("event_type"), rs.getString("p"), rs.getLong("at_ms"), rs.getInt("schema_version")));
            } catch (java.io.IOException e) {
                throw new java.sql.SQLException(e);
            }
        });
        List<Row> expected = new ArrayList<>(cutover);
        expected.addAll(real);
        assertThat(back).hasSameSizeAs(expected).hasSize(cutovers + real.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(back.get(i)).as("row %d (%s)", i, expected.get(i).type()).isEqualTo(expected.get(i));
        }

        // the plain Jackson of the fabricator writes what the server's own mapper writes (before jsonb normalises it)
        for (Row r : real) {
            GameEvent e = world.decode(r.type(), r.payload());
            assertThat(world.mapper.writeValueAsString(e)).isEqualTo(db.json().writeValueAsString(e));
        }

        // and the fabricator's engine ended where the server's did
        for (String p : players) {
            assertThat(world.hub.ledger().balance(p)).as("balance of %s", p).isEqualTo(hub.ledger().balance(p));
        }
        assertThat(world.hub.ledger().playersTotal()).isEqualTo(hub.ledger().playersTotal());
        assertThat(world.hub.ledger().openStakes()).isEqualTo(hub.ledger().openStakes());
        assertThat(world.hub.ledger().treasury()).isEqualTo(hub.ledger().treasury());
    }

    private static RoundWindow window(String market, Interval interval, long start, long end) {
        return new RoundWindow(market, interval, start, end);
    }

    private List<Row> readRows(String table, long afterId) {
        return db.read(c -> db.queryList(c,
                "SELECT event_type, payload::text AS p, at_ms, schema_version FROM " + table + " WHERE id > ? ORDER BY id",
                rs -> new Row(rs.getString("event_type"), rs.getString("p"), rs.getLong("at_ms"), rs.getInt("schema_version")),
                afterId));
    }
}
