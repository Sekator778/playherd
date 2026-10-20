package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.StateDigest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F4: the journal is self-contained and applying an event is pure. A game is played through the real service
 * (people, a house bot, an API bot, nicknames, a strategy, two markets, one of them closed with a bet in it, rounds settled and
 * cancelled), then
 * <ul>
 *   <li>the journal is replayed by the applier alone into a fresh hub - no table, no directory - and the engine state digest equals
 *       the live one (applying reads nothing outside the hub);
 *   <li>the {@code players} and {@code markets} tables and the two counters are wiped and a rebuild runs, with the journal as its only
 *       input - the digest of the whole (engine state, both tables, counters) equals the live one.
 * </ul>
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class JournalSelfContainedTest extends LiveGameTestBase {

    @Inject
    EventStore eventStore;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    PlayerDirectory players;

    private record World(String human1, String human2, String houseBot, String apiBot) {
    }

    private World play(String suffix) {
        long now = System.currentTimeMillis();
        Market main = list("T_F4_" + suffix, MarketKind.PERP, Category.CRYPTO, "FA" + suffix, 25, 1);
        Market closing = list("T_F4C_" + suffix, MarketKind.PERP, Category.CRYPTO, "FC" + suffix, 25, 1);
        prices.setPrice(main, "100.00");
        prices.setPrice(closing, "50.00");
        alignTo(main, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        alignTo(closing, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(main.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(main.id(), Interval.HOURLY);
        RoundWindow closingBetting = roundProgress.bettingWindow(closing.id(), Interval.HOURLY);

        String p1 = signUp("f4a" + suffix);
        String p2 = signUp("f4b" + suffix);
        gameService.setNickname(p1, "Alice F4 " + suffix);
        String bot = gameService.createBotPlayer("Bot F4 " + suffix, "TREND", now).playerId();
        gameService.assignBotStrategy(bot, "trend-follower");
        gameService.renameBot(bot, "Bot F4 relabelled " + suffix);
        String api = gameService.createApiBot(p1, "Api F4 " + suffix, now).bot().playerId();

        gameService.placeBet(p1, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        gameService.placeBet(p2, bet(betting, Enums.Direction.DOWN, 3, "100.000"), betting.startMs() - 1);
        gameService.placeBet(bot, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        gameService.placeBet(p2, bet(closingBetting, Enums.Direction.UP, 2, "100.000"), closingBetting.startMs() - 1);

        scheduler.tick(running.endMs());
        prices.script(main, betting, "100.00", "110.00", "100.00", "110.00");
        scheduler.tick(betting.endMs());
        // a bet on the betting window of a market that is then closed: the open round is cancelled with a refund
        registry.close(closing.id(), "F4 test close", System.currentTimeMillis());
        return new World(p1, p2, bot, api);
    }

    @Test
    void applyingTheJournalAloneGivesTheLiveEngineState() {
        World world = play("PURE");
        List<GameEvent> events = eventStore.loadAll();

        EngineHub aside = new EngineHub();
        EventApplier applier = EventApplier.over(aside, new BetFeed());
        for (Market market : marketRegistry.all()) {
            aside.install(market);
        }
        for (GameEvent event : events) {
            applier.apply(event);
        }

        assertThat(StateDigest.engine(aside)).isEqualTo(StateDigest.engine(hub));
        assertThat(StateDigest.sha256(StateDigest.engine(aside))).isEqualTo(StateDigest.sha256(StateDigest.engine(hub)));
        assertThat(aside.oracleSeq()).as("the counter is the highest sequence number in the journal").isEqualTo(hub.oracleSeq());
        assertThat(aside.oracleSeq()).isGreaterThan(0);
        // the roster is filled by the events alone - no table was read
        assertThat(aside.roster().find(world.human1()).orElseThrow().displayName()).isEqualTo("Alice F4 PURE");
        assertThat(aside.roster().find(world.houseBot()).orElseThrow().nickname()).isEqualTo("Bot F4 relabelled PURE");
        assertThat(aside.roster().find(world.houseBot()).orElseThrow().botStrategy()).isEqualTo("trend-follower");
        assertThat(aside.roster().find(world.houseBot()).orElseThrow().displaysAsBot()).isTrue();
        assertThat(aside.roster().find(world.apiBot()).orElseThrow().ownerPlayerId()).isEqualTo(world.human1());
        assertThat(aside.roster().find(world.apiBot()).orElseThrow().displaysAsBot()).isTrue();
        assertThat(aside.roster().find(world.human2()).orElseThrow().displaysAsBot()).isFalse();
    }

    @Test
    void aJournalReplayedIntoEmptyProjectionsReproducesTheLiveState() {
        World world = play("FULL");
        String live = StateDigest.canonical(db, hub);
        assertThat(live).contains("player " + world.human1()).contains("kind=API_BOT").contains("market T_F4C_FULL")
                .contains("CLOSED");
        String liveHash = StateDigest.sha256(live);

        // the empty database: what the journal projects is gone, the journal stays
        db.tx(c -> {
            db.update(c, "TRUNCATE TABLE players CASCADE");
            db.update(c, "DELETE FROM markets WHERE id <> 'BTC'");
            db.update(c, "UPDATE markets SET status = 'ACTIVE', closed_at_ms = NULL, close_reason = NULL WHERE id = 'BTC'");
            db.update(c, "UPDATE server_meta SET value = '0' WHERE key IN ('next_player_index', 'oracle_seq')");
            return null;
        });
        assertThat(StateDigest.projections(db, hub)).doesNotContain("player " + world.human1());

        rebuild.rebuild();

        String replayed = StateDigest.canonical(db, hub);
        assertThat(replayed).isEqualTo(live);
        assertThat(StateDigest.sha256(replayed)).isEqualTo(liveHash);
        // the one thing that is not in the journal on purpose: the key of an API bot (it is in the table, and the table was wiped)
        assertThat(players.byId(world.apiBot()).orElseThrow().keyHash()).isNull();
        assertThat(players.byId(world.apiBot()).orElseThrow().ownerId()).isEqualTo(world.human1());
        // and a second rebuild changes nothing
        rebuild.rebuild();
        assertThat(StateDigest.canonical(db, hub)).isEqualTo(live);
    }

    @Test
    void theJournalHoldsTheMarketsThePlayersAndTheSequenceNumbers() {
        World world = play("EVT");
        List<GameEvent> events = eventStore.loadAll();

        GameEvent.MarketListed listed = events.stream().filter(e -> e instanceof GameEvent.MarketListed l
                && l.marketId().equals("T_F4_EVT")).map(e -> (GameEvent.MarketListed) e).findFirst().orElseThrow();
        assertThat(listed.oracleIndex()).isEqualTo(marketRegistry.require("T_F4_EVT").oracleIndex());
        assertThat(listed.capHourly()).isEqualTo(marketRegistry.require("T_F4_EVT").economy().capHourly());
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOfSatisfying(GameEvent.MarketClosed.class,
                closed -> assertThat(closed.marketId()).isEqualTo("T_F4C_EVT")));

        GameEvent.PlayerRegistered registration = events.stream().filter(e -> e instanceof GameEvent.PlayerRegistered r
                && r.isRegistration() && r.playerId().equals(world.apiBot())).map(e -> (GameEvent.PlayerRegistered) e)
                .findFirst().orElseThrow();
        assertThat(registration.kind()).isEqualTo(PlayerDirectory.KIND_API_BOT);
        assertThat(registration.ownerPlayerId()).isEqualTo(world.human1());
        assertThat(registration.walletAddress()).startsWith("txapi1");
        assertThat(registration.playerIndex()).isEqualTo(players.byId(world.apiBot()).orElseThrow().playerIndex());
        assertThat(registration.toString()).as("no credential in the journal").doesNotContain("hash").doesNotContain("hint");

        List<GameEvent.RoundResolved> resolved = events.stream().filter(e -> e instanceof GameEvent.RoundResolved)
                .map(e -> (GameEvent.RoundResolved) e).toList();
        assertThat(resolved).isNotEmpty().allSatisfy(r -> assertThat(r.oracleSeq()).isNotNull());
        for (int i = 1; i < resolved.size(); i++) {
            assertThat(resolved.get(i).oracleSeq()).as("strictly increasing, in journal order")
                    .isGreaterThan(resolved.get(i - 1).oracleSeq());
        }
        for (GameEvent.RoundResolved r : resolved) {
            String roundId = new RoundWindow(r.market(), r.interval(), r.windowStart(), r.windowEnd()).roundId();
            Long seq = db.read(c -> db.queryOne(c, "SELECT seq FROM oracle_records WHERE round_id = ?", rs -> rs.getLong("seq"), roundId));
            assertThat(seq).as("the oracle record of %s carries the number of the event", roundId).isEqualTo(r.oracleSeq());
        }
    }
}
