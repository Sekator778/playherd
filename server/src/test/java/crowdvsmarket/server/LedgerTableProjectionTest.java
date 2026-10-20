package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.LedgerEntryType;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.persistence.LedgerEntryRepository;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Architecture plan F3: the ledger book keeps no history in memory; the {@code ledger_entries} table is a projection of the
 * journal and {@code myLedger} reads it. What that must keep: the answer is the one the in-memory list gave (same entries,
 * same order, same fields, same paging), the projection is written after the lock and is idempotent, a rebuild repairs any gap
 * (and fills an empty table from the journal), a write that fails does not stop the game and is visible to the operator, and the
 * memory of the hub does not hold the movements once they are written.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class LedgerTableProjectionTest extends LiveGameTestBase {

    @InjectSpy
    LedgerEntryRepository ledgerRowsSpy;
    @Inject
    EventStore eventStore;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    PlayerDirectory players;

    @AfterEach
    void resetSpy() {
        Mockito.reset(ledgerRowsSpy);
    }

    /** A market with two players who bet on its betting window, the running window settled, then the betting window settled
     *  (a win for one, a loss for the other): stakes, fees, a settlement and a starter pack per player in the journal. */
    private List<String> playSomeRounds(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 25, 1);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p1 = signUp(symbol.toLowerCase() + "1");
        String p2 = signUp(symbol.toLowerCase() + "2");
        gameService.placeBet(p1, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        gameService.placeBet(p2, bet(betting, Enums.Direction.DOWN, 3, "100.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "100.00", "110.00", "100.00", "110.00");
        scheduler.tick(betting.endMs());
        return List.of(p1, p2);
    }

    /** Every movement of the journal, by replaying it into a hub of its own whose ledger is listened to - what the in-memory
     *  list of the ledger book held before F3. */
    private List<LedgerEntry> replayedEntries() {
        EngineHub aside = new EngineHub();
        EventApplier applier = EventApplier.over(aside, new BetFeed());
        for (Market market : marketRegistry.all()) {
            aside.install(market);
        }
        List<LedgerEntry> all = new ArrayList<>();
        aside.ledger().addListener(all::add);
        for (GameEvent event : eventStore.loadAll()) {
            applier.apply(event);
        }
        return all;
    }

    /** The answer of {@code myLedger} as it was written before F3: a walk over the in-memory list, newest first. */
    private static Paging.Page<Ledger.LedgerEntryDto> oldAnswer(List<LedgerEntry> all, String playerId, Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        List<Ledger.LedgerEntryDto> filtered = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            LedgerEntry e = all.get(i);
            if (!e.playerId().equals(playerId) || e.atEpochMs() >= before) {
                continue;
            }
            filtered.add(new Ledger.LedgerEntryDto(e.sequence(), Enums.LedgerEntryType.valueOf(e.type().name()),
                    e.delta().toMoney().toPlainString(), e.roundId(), e.atEpochMs()));
            if (filtered.size() > limit) {
                break;
            }
        }
        boolean hasMore = filtered.size() > limit;
        return new Paging.Page<>(hasMore ? filtered.subList(0, limit) : filtered, hasMore);
    }

    private List<LedgerEntry> tableRows() {
        return db.read(c -> db.queryList(c, "SELECT seq, player_id, type, delta_mojos, round_id, at_ms FROM ledger_entries ORDER BY seq",
                rs -> new LedgerEntry(rs.getLong("seq"), rs.getString("player_id"), LedgerEntryType.valueOf(rs.getString("type")),
                        new PlayAmount(rs.getLong("delta_mojos")), rs.getString("round_id"), rs.getLong("at_ms"))));
    }

    @Test
    void myLedgerAnswersWhatTheInMemoryListAnsweredOnAReplayedJournal() {
        List<String> ids = playSomeRounds("T_LT_SAME", "LTS");

        List<LedgerEntry> reference = replayedEntries();
        assertThat(reference).as("the journal produced movements").hasSizeGreaterThan(6);
        Set<Long> beforeValues = new LinkedHashSet<>();
        beforeValues.add(null);
        for (LedgerEntry e : reference) {
            beforeValues.add(e.atEpochMs());
        }
        int compared = 0;
        for (String playerId : new LinkedHashSet<>(reference.stream().map(LedgerEntry::playerId).toList())) {
            for (Long before : beforeValues) {
                for (Integer limit : new Integer[] {null, 1, 2, 3, 200}) {
                    Paging.PageParams params = new Paging.PageParams(limit, before);
                    assertThat(gameService.myLedger(playerId, params))
                            .as("player %s before %s limit %s", playerId, before, limit)
                            .isEqualTo(oldAnswer(reference, playerId, params));
                    compared++;
                }
            }
        }
        assertThat(compared).isGreaterThan(50);
        assertThat(tableRows()).as("the table holds exactly the movements of the journal").isEqualTo(reference);
        assertThat(ids).allSatisfy(id -> assertThat(gameService.myLedger(id, new Paging.PageParams(null, null)).items())
                .extracting(Ledger.LedgerEntryDto::type).contains(Enums.LedgerEntryType.STARTER_PACK,
                        Enums.LedgerEntryType.SETTLEMENT));
    }

    @Test
    void aRebuildNeverWritesAMovementTwiceAndRepairsEveryGapAndAnEmptyTable() {
        playSomeRounds("T_LT_FIX", "LTF");
        gameService.flushLedgerRows();
        List<LedgerEntry> reference = replayedEntries();
        assertThat(tableRows()).isEqualTo(reference);

        rebuild.rebuild();
        rebuild.rebuild();
        gameService.flushLedgerRows();
        assertThat(tableRows()).as("two restarts add nothing").isEqualTo(reference);

        // a gap in the middle and the tail gone: what a crash or a failed write leaves
        long middle = reference.get(reference.size() / 2).sequence();
        long lastSeq = reference.get(reference.size() - 1).sequence();
        db.tx(c -> db.update(c, "DELETE FROM ledger_entries WHERE seq = ? OR seq >= ?", middle, lastSeq - 1));
        assertThat(tableRows()).hasSize(reference.size() - 3);
        rebuild.rebuild();
        gameService.flushLedgerRows();
        assertThat(tableRows()).as("the gaps are filled from the journal").isEqualTo(reference);

        // the first start after V16: no table content at all
        db.tx(c -> db.update(c, "DELETE FROM ledger_entries"));
        rebuild.rebuild();
        gameService.flushLedgerRows();
        assertThat(tableRows()).as("an empty table is filled from the journal").isEqualTo(reference);
    }

    @Test
    void aFailingWriteDoesNotStopTheGameAndTheTableCatchesUpWhenItEnds() {
        List<String> ids = playSomeRounds("T_LT_FAIL", "LTX");
        gameService.flushLedgerRows();
        assertThat(gameService.ledgerRowsStatus().pending()).isZero();

        doThrow(new IllegalStateException("injected: ledger_entries is not writable")).when(ledgerRowsSpy).insertBatch(anyList());
        Market market = marketRegistry.all().stream().filter(m -> m.id().equals("T_LT_FAIL")).findFirst().orElseThrow();
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(ids.get(0), bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        gameService.placeBet(ids.get(1), bet(betting, Enums.Direction.DOWN, 2, "100.000"), betting.startMs() - 1);

        assertThat(gameService.flushLedgerRows()).isFalse();
        GameService.LedgerRowsStatus failing = gameService.ledgerRowsStatus();
        assertThat(failing.pending()).as("the movements wait in memory").isGreaterThanOrEqualTo(4);
        assertThat(failing.failures()).isGreaterThan(0);
        assertThat(failing.lastError()).contains("injected");
        assertThat(rebuild.isReady()).as("the game goes on").isTrue();
        // a read of the ledger still answers (the table's own content), a further bet is still accepted
        assertThat(gameService.myLedger(ids.get(0), new Paging.PageParams(null, null)).items()).isNotEmpty();
        gameService.placeBet(ids.get(0), bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);

        Mockito.reset(ledgerRowsSpy);
        assertThat(gameService.flushLedgerRows()).isTrue();
        assertThat(gameService.ledgerRowsStatus().pending()).isZero();
        assertThat(gameService.ledgerRowsStatus().failures()).isZero();
        assertThat(tableRows()).as("in order, nothing lost, nothing twice").isEqualTo(replayedEntries());
    }

    @Test
    void theMemoryHoldsNoMovementOnceItIsWritten() {
        playSomeRounds("T_LT_MEM", "LTM");

        assertThat(gameService.flushLedgerRows()).isTrue();
        assertThat(hub.ledgerOutbox().size()).isZero();
        assertThatThrownBy(() -> hub.ledger().entries()).isInstanceOf(IllegalStateException.class);
        assertThat(hub.ledger().lastSequence()).as("the ledger did produce movements").isGreaterThan(6);
        rebuild.rebuild();
        // a rebuild of a journal the table already has collects nothing to write
        assertThat(hub.ledgerOutbox().size()).isZero();
    }
}
