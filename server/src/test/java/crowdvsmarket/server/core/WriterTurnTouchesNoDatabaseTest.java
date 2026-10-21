package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.RoundVenues;
import crowdvsmarket.server.service.DerivedRows;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The turn of the core writer touches no database (docs/research/lmax_roadmap.md step 3; the rule is {@link IoGuard}, which fails any game
 * test that breaks it). What used to be a database access inside the turn, and what replaced it:
 * <ul>
 *   <li>seeding the series of a listed market read {@code round_results}: it reads the engine's marker of the closed rounds now, and the
 *       table only in a rebuild;
 *   <li>the cancellation of a round looked the round's venue up and wrote the result row: the row (and the lookup) is a job of
 *       {@link DerivedRows}, on a thread of its own, repaired by the rebuild when it is lost;
 *   <li>a market's listing and closing wrote the {@code markets} row and the event in one transaction: the registry journals the event in
 *       the turn from its memory, and the row follows, written by the caller.
 * </ul>
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class WriterTurnTouchesNoDatabaseTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @InjectSpy
    RoundResultRepository rowsSpy;
    @InjectSpy
    RoundVenues venuesSpy;
    @InjectSpy
    EventStore eventStoreSpy;
    @Inject
    DerivedRows derivedRows;

    @AfterEach
    void healTheSpies() {
        Mockito.reset(rowsSpy, venuesSpy, eventStoreSpy);
    }

    private static RoundWindow nextOf(Market market, RoundWindow running) {
        return crowdvsmarket.engine.round.RoundClock.next(running);
    }

    @Test
    void seedingASeriesReadsNoTableInTheTurnButARebuildDoes() {
        Market market = list("T_WT_SEED", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTS", 10, 0);

        assertThat(roundProgress.findNext(market.id(), HOURLY)).as("listed and seeded").isPresent();
        verify(rowsSpy, never()).latestWindowEnd(eq(market.id()), any(Interval.class));

        rebuild.rebuild();

        verify(rowsSpy, atLeastOnce()).latestWindowEnd(eq(market.id()), any(Interval.class));
    }

    @Test
    void aCancelledRoundsRowIsWrittenOffTheWriterBeforeTheCallerIsAnswered() throws Exception {
        Market market = list("T_WT_ROW", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTR", 10, 0);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = nextOf(market, running);
        String player = signUp("wtrow");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        List<String> writeThreads = new CopyOnWriteArrayList<>();
        List<String> venueThreads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            writeThreads.add(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(rowsSpy).insertCancelled(any(), anyString(), anyString(), any(Interval.class), anyLong(), anyLong(), anyString(),
                anyLong(), anyInt(), anyString(), anyString(), any());
        doAnswer(inv -> {
            venueThreads.add(Thread.currentThread().getName());
            return inv.callRealMethod();
        }).when(venuesSpy).venueOfRound(anyString());

        Rounds.CancellationDto cancelled = gameService.cancelRound(betting, "operator: test", betting.startMs() + 1_000L);

        assertThat(cancelled.refundedBetCount()).isEqualTo(1);
        assertThat(roundResults.exists(betting.roundId())).as("the operator's answer comes with the row").isTrue();
        assertThat(writeThreads).containsExactly("herd-derived-rows");
        assertThat(venueThreads).as("the venue lookup is not in the writer's turn").doesNotContain(IoGuard.WRITER_THREAD);
        assertThat(derivedRows.status().pending()).isZero();
    }

    @Test
    void aRowThatCouldNotBeWrittenIsDroppedAndTheNextRebuildWritesIt() throws Exception {
        Market market = list("T_WT_LOST", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTL", 10, 0);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = nextOf(market, running);
        String player = signUp("wtlost");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        long droppedBefore = derivedRows.status().dropped();
        doThrow(new IllegalStateException("db flapping while writing the row")).when(rowsSpy).insertCancelled(any(), anyString(),
                anyString(), any(Interval.class), anyLong(), anyLong(), anyString(), anyLong(), anyInt(), anyString(), anyString(),
                any());

        gameService.cancelRound(betting, "operator: test", betting.startMs() + 1_000L);

        assertThat(roundResults.exists(betting.roundId())).as("the event is in the journal, the row is not").isFalse();
        assertThat(derivedRows.status().dropped()).isEqualTo(droppedBefore + 1);
        assertThat(derivedRows.status().lastError()).contains("db flapping");
        assertThat(gameService.account(player).inPlay()).as("the stake was refunded all the same").isEqualTo("0.000");

        Mockito.reset(rowsSpy);
        rebuild.rebuild();

        assertThat(roundResults.exists(betting.roundId())).as("written from the event by the rebuild").isTrue();
        Rounds.RoundCancelled row = (Rounds.RoundCancelled) roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(row.cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(row.cancellation().reason()).isEqualTo("operator: test");
    }

    @Test
    void aRowWrittenTwiceIsWrittenOnce() {
        Market market = list("T_WT_TWICE", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTT", 10, 0);
        RoundWindow betting = nextOf(market, roundProgress.next(market.id(), HOURLY));
        gameService.cancelRound(betting, "operator: test", betting.startMs() + 1_000L);
        assertThat(roundResults.exists(betting.roundId())).isTrue();

        // the rebuild that found the row missing and the job that was still queued both write it: the second leaves the first alone
        db.tx(c -> {
            roundResults.insertCancelled(c, market.id(), betting.roundId(), HOURLY, betting.startMs(), betting.endMs(), "again", 1L, 0,
                    "hash", "{}", null);
            return null;
        });

        Rounds.RoundCancelled row = (Rounds.RoundCancelled) roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(row.cancellation().reason()).isEqualTo("operator: test");
    }

    @Test
    void aListingJournalsTheRowAsTheTableGivesItBackAndTheTableHasTheRow() {
        Market listed = listWithBackup("T_WT_LIST", "WTLI", "kraken", "WTLI/USD");

        List<GameEvent> events = eventStoreSpy.loadOfTypes(List.of("MarketListed"));
        GameEvent.MarketListed event = events.stream().map(e -> (GameEvent.MarketListed) e)
                .filter(e -> e.marketId().equals(listed.id())).findFirst().orElseThrow();
        String metaInTable = db.read(c -> db.queryOne(c, "SELECT source_meta::text AS m FROM markets WHERE id = ?",
                rs -> rs.getString("m"), listed.id()));
        java.math.BigDecimal kappaInTable = db.read(c -> db.queryOne(c, "SELECT kappa FROM markets WHERE id = ?",
                rs -> rs.getBigDecimal("kappa"), listed.id()));
        int indexInTable = db.read(c -> db.queryOne(c, "SELECT oracle_index FROM markets WHERE id = ?",
                rs -> rs.getInt("oracle_index"), listed.id()));

        assertThat(event.sourceMeta()).as("jsonb text, as the table stores it").isEqualTo(metaInTable);
        assertThat(new java.math.BigDecimal(event.kappa())).isEqualByComparingTo(kappaInTable);
        assertThat(event.oracleIndex()).isEqualTo(indexInTable).isEqualTo(listed.oracleIndex());
        assertThat(registry.byId(listed.id())).contains(listed);

        registry.reload();
        assertThat(registry.byId(listed.id())).as("what a reload gives is what the listing returned").contains(listed);
    }

    @Test
    void aClosingJournalsTheEventAndClosesTheRow() {
        Market listed = list("T_WT_CLOSE", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTC", 10, 0);

        registry.close(listed.id(), "closed by the test", System.currentTimeMillis());

        assertThat(registry.byId(listed.id()).orElseThrow().isActive()).isFalse();
        String status = db.read(c -> db.queryOne(c, "SELECT status FROM markets WHERE id = ?", rs -> rs.getString(1), listed.id()));
        assertThat(status).isEqualTo("CLOSED");
        assertThat(eventStoreSpy.loadOfTypes(List.of("MarketClosed")).stream().map(e -> (GameEvent.MarketClosed) e)
                .anyMatch(e -> e.marketId().equals(listed.id()) && e.reason().equals("closed by the test"))).isTrue();
        assertThat(roundProgress.findNext(listed.id(), HOURLY)).as("its series stopped").isEmpty();
    }

    @Test
    void aMarketRowThatWasNeverWrittenIsWrittenByTheRebuildFromTheJournal() {
        Market listed = list("T_WT_ROWLOST", crowdvsmarket.server.markets.MarketKind.PERP, "crypto", "WTRL", 10, 0);
        db.tx(c -> db.update(c, "DELETE FROM markets WHERE id = ?", listed.id())); // a stop after the event, before the row

        rebuild.rebuild();

        assertThat(registry.byId(listed.id())).as("the row came back from the MarketListed event").contains(listed);
    }
}
