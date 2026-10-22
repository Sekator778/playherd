package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.game.PlayerRoundOutcome;
import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.EconomyFigures;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.ledger.SeasonRecord;
import crowdvsmarket.engine.ledger.WithdrawalRequest;
import crowdvsmarket.engine.ledger.WithdrawalState;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.LiquidationParams;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.core.Command;
import crowdvsmarket.server.core.CoreWriter;
import crowdvsmarket.server.core.WriteContext;
import crowdvsmarket.server.domain.BetRequests;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.domain.WinStreak;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.auth.ApiKeys;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Bots;
import crowdvsmarket.server.dto.Crowd;
import crowdvsmarket.server.dto.Economy;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Home;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.PlayChain;
import crowdvsmarket.server.dto.Ratings;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketListener;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.WriterTurn;
import crowdvsmarket.server.jfr.HerdPlaceBet;
import crowdvsmarket.server.jfr.HerdSettle;
import crowdvsmarket.server.jfr.JfrClock;
import crowdvsmarket.server.jfr.TimedEngineLock;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.price.PriceService;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.RoundPriceData;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import crowdvsmarket.server.util.FailureLog;
import jakarta.annotation.PreDestroy;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The orchestrator behind every mutating {@code Api} method (web/src/api/contract.ts) and most
 * read ones — everything that is not purely auth/session plumbing ({@code AuthService}) or wiring
 * ({@code RpcResource}). None of {@link EngineHub}'s engine objects (or {@code BetBook}/
 * {@code CrowdAccount}, which are the same kind of plain mutable collections) are internally
 * thread-safe, and the scheduler (round resolution, bots) and HTTP request threads both call in
 * here concurrently — see {@link EngineHub}'s javadoc.
 *
 * <p><b>Locking (wave-2 MAJOR fix):</b> every method that mutates {@link EngineHub}'s state holds
 * {@link #engineLock}'s write lock for its whole body; every method that only reads it — including
 * {@link #home}/{@link #ratings} and any other read path, not just the two the bug report named —
 * holds the read lock. A {@link java.util.concurrent.locks.ReentrantReadWriteLock} was chosen over
 * a plain {@code synchronized} monitor (which is what every mutating method used to declare, before
 * this fix, with reads holding nothing at all — the actual bug: a read iterating {@code
 * LedgerBook.entries()}/{@code accounts} or a {@code BetBook} map while a concurrent bet/withdrawal/
 * round-resolution mutated the same {@code ArrayList}/{@code LinkedHashMap} could throw {@code
 * ArrayIndexOutOfBoundsException}/{@code ConcurrentModificationException}) because this service is
 * read-heavy relative to how often it mutates (every poll of {@code home}/{@code rounds}/
 * {@code ratings} from every connected client vs. one {@code placeBet}/round-resolution at a time):
 * a read/write lock lets those reads run concurrently with each other (never with a writer),
 * instead of a plain mutex serializing reads behind every other read too, which would otherwise
 * inflate p99 latency under exactly the concurrent-read load the bug report's test hammers.
 * {@code EngineRebuildService.rebuild()} — which replaces {@link EngineHub}'s whole state with a
 * freshly replayed one — takes the SAME write lock via {@link #runExclusively}, for the same reason a
 * rebuild used to run inside {@code synchronized(gameService)}: it must never interleave with any
 * other read or write here either.
 *
 * <p><b>The core writer (architecture plan F6):</b> since then no method here takes the write lock itself. Every call that
 * changes anything is a {@link Command} that {@link CoreWriter}'s one thread runs, holding the engine write lock for the
 * command's own work only, and never waits for the disk: the events of the commands of a batch go to the journal as ONE excerpt,
 * written and synced by a stage of its own (batches that wait share a sync), and the caller is answered after that sync (a
 * player never gets "OK" for a bet that is not durable). A batch that fails to commit answers 503 to its commands, with every
 * batch applied after it, and the engine is rebuilt from the journal. A rebuild, a snapshot and the ledger flush enter through
 * {@code writerGate} ({@link #enterGate}) and wait until the pipeline is drained, so they see the engine exactly as the journal
 * holds it.
 *
 * <p><b>The read views (architecture plan F8):</b> the hot reads - the lobby, a player's account, positions and open bets,
 * a round, the bet feed, the crowd accounts, the stake in play, what the scheduler asks of the bet books - do not touch the
 * engine at all: the writer builds {@link ReadViews immutable views} of each batch when it applies it and publishes them when the
 * batch is durable (before the caller is answered), and a read takes the latest through one volatile reference ({@link #snapshot}), with no lock. A read
 * therefore never sees what a batch has applied and not yet committed. The season records, the season boards (the ratings and the
 * ratings part of {@code home}), the weekly boards' retired filter, the ledger corrections, the economy flags, the circuit breaker and
 * the reconciliation are in the views too, and {@code home} reads all its parts from one snapshot. No read takes the engine's lock
 * any more; what still does is the writer's own work (a command, the ledger flush, a rebuild).
 * Inside a command no database query and no network call: whatever needs them is done before the command is submitted, or is
 * staged as a write of the batch's transaction ({@link WriteContext#work}).
 *
 * <p><b>Markets (Р-69, docs/research/markets_architecture.md):</b> every round id names its market
 * ({@code RoundWindow.parseRoundId}) and every path below works for any market; BTC is the default
 * wherever an argument is optional. Prices leave the server as the market's RAW price strings
 * ({@link Market#rawString}); the engine, {@code round_results} and the oracle record keep ENGINE
 * (lot) prices (Т-3). {@code mode=replay} plays BTC only, from {@link PriceService}; {@code
 * mode=live} plays every active market, from {@link MarketPrices}. This service is also the
 * registry's {@link MarketListener}: a listed market gets its engines and series, a closed one has
 * its open rounds cancelled with a refund.
 *
 * <p><b>Settlement is atomic (§4 п.3):</b> {@link #resolveRound} fetches prices BEFORE taking the
 * lock (the source may be asked over the network), then, on the core writer, runs ONE database
 * transaction — the {@code RoundResolved} event, the result and history rows, the oracle record —
 * and applies the event to the engine inside it. If anything throws, the transaction rolls back and — when
 * the event had been applied by then — the in-memory state is rebuilt from the event log before the exception
 * propagates, so memory and database can never disagree about a round. {@link #cancelRound} works the same way.
 *
 * <p><b>Fail-stop.</b> If that rebuild fails too, the engine is not ready ({@code
 * EngineRebuildService#isReady}) until one succeeds: every method that changes anything and every read
 * from the engine answers 503 {@code ENGINE_NOT_READY} (re-checked once the lock is held); what comes from
 * the database or needs no engine ({@link #cancellations}, a settled {@link #round}, {@link
 * #referencePrice}, ...) is answered as usual. The policy: {@code EngineRebuildService}'s javadoc.
 *
 * <p><b>Lock order</b> (no cycle, listings and closes may arrive at any moment): the {@code BotService}
 * monitor, then {@link #engineLock}, then — a rebuild's reload, under the engine lock — the registry's
 * write lock; the registry never holds its lock while it calls {@link #onListed}/{@link #onClosed}, and
 * nothing here ever calls into {@code BotService}. The read model's cache monitor ({@code MarketViewService}, which
 * reads the committed views and takes no engine lock) is taken before anything here, and nothing here ever calls into it.
 */
@ApplicationScoped
public class GameService implements MarketListener {

    @Inject
    GameConfig config;
    /** The output side (docs/research/lmax_roadmap.md step 5): told what changed, from the after-commit actions of the commands -
     *  a notice never goes out for a batch that did not commit. Never blocks, never throws. */
    @Inject
    crowdvsmarket.server.push.ChangePublisher publisher = crowdvsmarket.server.push.ChangePublisher.NONE;
    @Inject
    Db db;
    @Inject
    EngineHub hub;
    @Inject
    EventStore eventStore;
    @Inject
    EventApplier eventApplier;
    @Inject
    PlayerDirectory players;
    @Inject
    RoundProgress roundProgress;
    @Inject
    SeriesSeeder seeder;
    @Inject
    OpenBets openBets;
    @Inject
    RoundCancellations cancellations;
    @Inject
    DerivedRows derivedRows;
    @Inject
    BoundaryPrices boundaries;
    @Inject
    WithdrawalSubmitter withdrawalSubmitter;
    @Inject
    PriceService priceService;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    MarketPrices marketPrices;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    crowdvsmarket.server.persistence.LedgerEntryRepository ledgerRows;
    /** Serialises the writes of the ledger movements, so a batch that failed goes back in front of the next one and the
     *  table is filled in order. */
    private final java.util.concurrent.locks.ReentrantLock ledgerFlushLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile int ledgerFlushFailures;
    private volatile String ledgerFlushError;
    private volatile long ledgerFailingSinceMs;
    private final FailureLog ledgerFailureLog = new FailureLog(60_000L);
    /** Rounds whose {@code RoundResolved} event is committed and applied (the journal and the engine have them as
     *  resolved: {@link crowdvsmarket.server.domain.ClosedRounds}) but whose derived rows ({@code round_results},
     *  {@code player_round_history}, the oracle record) are not written yet. Entered under the write lock right after the
     *  event, left when the rows have committed. It guards nothing - whether a round is resolved is the engine's marker -
     *  it is what {@link #awaitStored} waits for, what the background retry works through ({@link #rowsFailed}), and what
     *  the rebuild leaves to that retry ({@link #repairSettledResult}). */
    private final ConcurrentHashMap<String, PendingRows> storing = new ConcurrentHashMap<>();
    /** The longest, in all, a read waits for the rows of the rounds being written ({@link #awaitStored}). */
    private static final long STORE_WAIT_MS = 5_000L;
    @ConfigProperty(name = "herd.rows-retry.base-ms", defaultValue = "1000")
    long rowsRetryBaseMs;
    @ConfigProperty(name = "herd.rows-retry.max-ms", defaultValue = "60000")
    long rowsRetryMaxMs;
    private final FailureLog rowsFailureLog = new FailureLog(60_000L);
    private ScheduledExecutorService rowsRetryExecutor;

    /** A resolved round whose derived rows are to be written: what {@link #writeRows} needs, and the state of the
     *  attempts so far. */
    private static final class PendingRows {
        final Market market;
        final RoundWindow window;
        final RoundReport report;
        final long sourceTsMs;
        final String venue;
        /** The oracle sequence number the {@code RoundResolved} event carries; {@code null} for an event written before F4. */
        final Long oracleSeq;
        final CompletableFuture<Void> stored = new CompletableFuture<>();
        final long sinceMs = System.currentTimeMillis();
        volatile int failures;
        volatile String lastError;

        PendingRows(Market market, RoundWindow window, RoundReport report, long sourceTsMs, String venue, Long oracleSeq) {
            this.oracleSeq = oracleSeq;
            this.market = market;
            this.window = window;
            this.report = report;
            this.sourceTsMs = sourceTsMs;
            this.venue = venue;
        }
    }

    /** A resolved round whose rows keep failing to be written: the operator's signal ({@code /api/admin/status}). */
    public record MissingRows(String roundId, int failures, long sinceMs, String lastError) {
    }

    /** Rounds that are resolved in the journal and whose result rows could not be written so far (each is being retried
     *  in the background). Empty when everything is written. */
    public List<MissingRows> missingRows() {
        List<MissingRows> out = new ArrayList<>();
        for (PendingRows p : storing.values()) {
            if (p.failures > 0) {
                out.add(new MissingRows(p.window.roundId(), p.failures, p.sinceMs, p.lastError));
            }
        }
        out.sort(java.util.Comparator.comparing(MissingRows::roundId));
        return out;
    }

    /** The ledger movements the {@code ledger_entries} table is behind by ({@code pending}), and how it fares: {@code failures}
     *  writes in a row that failed ({@code 0}: the last one went through), since when ({@code sinceMs}) and why ({@code lastError}).
     *  The operator's signal for the projection of the ledger history ({@code /api/admin/status}). */
    public record LedgerRowsStatus(int pending, int failures, long sinceMs, String lastError) {
    }

    /** What the projection of the ledger history is doing right now. */
    public LedgerRowsStatus ledgerRowsStatus() {
        int failures = ledgerFlushFailures;
        return new LedgerRowsStatus(hub.ledgerOutbox().size(), failures, failures > 0 ? ledgerFailingSinceMs : 0,
                failures > 0 ? ledgerFlushError : null);
    }

    /**
     * Writes the ledger movements the engine produced to the {@code ledger_entries} table - the projection of the journal
     * that {@link #myLedger} reads - <b>without the engine lock</b>: the lock is held only to take the collected movements
     * out of the hub's outbox (a free read lock means no operation is half way, so every movement taken is committed or
     * belongs to a hub that is gone). One transaction, idempotent ({@code ON CONFLICT DO NOTHING}). A write that fails
     * does not stop the game: the batch goes back in front of the outbox, the failure is an ERROR in the log (a stack
     * once, then a line a minute) and a {@link #ledgerRowsStatus}, and the next call - the scheduled one, a second later,
     * or a read of a ledger - tries again; a rebuild writes whatever a stop left missing. Does nothing while the engine is
     * not ready.
     *
     * @return {@code true} when nothing is left to write
     */
    public boolean flushLedgerRows() {
        if (!rebuildService.isReady()) {
            return false;
        }
        if (hub.ledgerOutbox().size() == 0) {
            return true; // the usual second: nothing to write, no lock taken
        }
        ledgerFlushLock.lock();
        try {
            crowdvsmarket.server.domain.LedgerOutbox outbox;
            List<LedgerEntry> batch;
            // the gate: between two batches of the core writer, never while one is applied in memory and not committed - a
            // movement written to the table for an event that then fails to commit would stay there, and the rebuild would
            // take its sequence number for a stored one
            enterGate();
            try {
                engineLock.readLock().lock();
                try {
                    outbox = hub.ledgerOutbox();
                    batch = outbox.drain();
                } finally {
                    engineLock.readLock().unlock();
                }
            } finally {
                writerGate.unlock();
            }
            if (batch.isEmpty()) {
                return outbox.size() == 0;
            }
            try {
                ledgerRows.insertBatch(batch);
            } catch (RuntimeException e) {
                outbox.requeueFront(batch);
                ledgerFlushFailed(e);
                return false;
            }
            if (ledgerFlushFailures > 0) {
                LOG.infof("the ledger history is written again after %d failed attempt(s)", ledgerFlushFailures);
                ledgerFailureLog.clear("ledger");
            }
            ledgerFlushFailures = 0;
            ledgerFlushError = null;
            return outbox.size() == 0;
        } finally {
            ledgerFlushLock.unlock();
        }
    }

    private void ledgerFlushFailed(RuntimeException cause) {
        if (ledgerFlushFailures == 0) {
            ledgerFailingSinceMs = System.currentTimeMillis();
        }
        ledgerFlushFailures++;
        ledgerFlushError = cause.toString();
        switch (ledgerFailureLog.next("ledger", System.currentTimeMillis())) {
            case STACK_TRACE -> LOG.errorf(cause, "the ledger history (ledger_entries) could not be written - the game goes on "
                    + "and the movements wait in memory; retrying every second");
            case LINE -> LOG.errorf("the ledger history still cannot be written (%d attempts, since %s): %s",
                    ledgerFlushFailures, java.time.Instant.ofEpochMilli(ledgerFailingSinceMs), cause);
            case NOTHING -> {
                // already said this minute
            }
        }
    }

    /** The delays of the background retry of a failed rows write ({@link #rowsFailed}): the first and the longest.
     *  For tests; the settings {@code herd.rows-retry.base-ms} and {@code herd.rows-retry.max-ms} otherwise. */
    public void setRowsRetryDelays(long baseMs, long maxMs) {
        rowsRetryBaseMs = baseMs;
        rowsRetryMaxMs = maxMs;
    }

    /** Whether the rows of the round are still to be written (by the retry or the writer of the settlement). */
    public boolean rowsPending(String roundId) {
        return storing.containsKey(roundId);
    }

    /** Whether the round is resolved or cancelled as of the last commit (the journal's word, not the table's), from the read views; {@code false}
     *  while there are none (a rebuild; whoever acts on the answer asks again on the writer). */
    public boolean isClosed(RoundWindow window) {
        ReadViews.Snapshot snapshot = views.current();
        return snapshot != null && snapshot.isClosed(window);
    }

    @PreDestroy
    synchronized void stopRowsRetry() {
        if (rowsRetryExecutor != null) {
            rowsRetryExecutor.shutdownNow();
        }
        stopWriter();
    }

    @Inject
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    crowdvsmarket.server.persistence.ServerMetaRepository serverMeta;
    @Inject
    crowdvsmarket.server.persistence.InviteRepository invites;
    @Inject
    ChainSvcClient chainSvc;
    @Inject
    crowdvsmarket.server.persistence.PlayerDepositAddressRepository depositAddresses;
    @Inject
    crowdvsmarket.server.persistence.DepositConfirmationRepository depositConfirmations;
    @Inject
    crowdvsmarket.server.domain.BetFeed betFeedStore;
    @Inject
    EngineRebuildService rebuildService;
    @Inject
    crowdvsmarket.server.persistence.RegistrationIpRepository registrationIps;

    /** Everything that was ever issued: 100 000 000 PLAY (docs/product/economy_implementation_plan.md, "Факт для всех этапов"). */
    static final PlayAmount ISSUED = PlayAmount.ofWholePlay(100_000_000);
    /** The circuit breaker's last reported state, to say a change once (plan B6). */
    private volatile boolean breakerWasOpen;

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Logger LOG = Logger.getLogger(GameService.class);
    /** A round that cannot be opened (the database down, say) is said once a minute, not every tick. */
    private final crowdvsmarket.server.util.LogThrottle venueLog = new crowdvsmarket.server.util.LogThrottle(60_000L);
    /** Р-53: минимум для настоящего on-chain вывода (предложение — не зафиксировано в
     *  docs/product/decisions.md; та же цифра, что {@code LedgerBook.MIN_STAKE}, но отдельная по
     *  смыслу константа: одно — минимальная ставка, другое — минимальный вывод, у них нет причины
     *  всегда совпадать, просто сегодня совпадают). Ниже {@code MIN_WITHDRAWAL} чейновая комиссия
     *  батч-спенда чейнсвика могла бы съесть непропорционально большую долю выведенной суммы. */
    private static final PlayAmount MIN_WITHDRAWAL = PlayAmount.ofWholePlay(100);

    /** See the class javadoc's "Locking" section. */
    private final java.util.concurrent.locks.ReadWriteLock engineLock =
            new TimedEngineLock();

    /** Used by {@code EngineRebuildService}: runs {@code action} holding this class's write lock, the
     *  exclusive access every mutating method below gets by holding it — the swap of a rebuilt state
     *  must never interleave with any other read or write of {@link EngineHub} through this class. Not
     *  gated by the engine being ready: this is how it becomes ready. */
    public void runExclusively(Runnable action) {
        // the ledger flush first (the order of the flush itself: its lock, then the writer's gate, then the engine's): a write
        // of the old state's movements that is under way ends before the action replaces that state, and the table it may
        // reset is not written meanwhile. The gate: the core writer is not half way through a batch (applied in memory, not
        // yet committed) when the state is replaced - the replacement would lose the batch's events or the journal get them
        // twice. Never called from a command (the writer thread holds the gate already, and taking the flush lock after it
        // would be the wrong order).
        ledgerFlushLock.lock();
        try {
            enterGate();
            try {
                engineLock.writeLock().lock();
                try {
                    action.run();
                } finally {
                    engineLock.writeLock().unlock();
                }
            } finally {
                writerGate.unlock();
            }
        } finally {
            ledgerFlushLock.unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The core writer (architecture plan F6)
    // ---------------------------------------------------------------------------------------

    /** The most commands one batch of the core writer takes ({@link CoreWriter}). A batch is one transaction, so this bounds
     *  how many callers one slow commit holds up, and how long the engine lock is held for the commands' own work. */
    @ConfigProperty(name = "herd.writer.max-batch", defaultValue = "256")
    int writerMaxBatch;
    /** How long the writer waits for more commands once it has more than one in hand and the batch is not full (milliseconds).
     *  0: it takes what is queued and goes - the commands that arrive while a batch commits are the next batch. */
    @ConfigProperty(name = "herd.writer.max-wait-ms", defaultValue = "0")
    long writerMaxWaitMs;
    /** The commands that may wait for the writer; a caller that finds the queue full waits for room. */
    @ConfigProperty(name = "herd.writer.queue-capacity", defaultValue = "10000")
    int writerQueueCapacity;

    /** How many batches the core writer's logic thread may have applied and not yet seen through the journal and the output stage
     *  ({@link CoreWriter}); when that many are in the pipeline it waits (back-pressure). */
    @ConfigProperty(name = "herd.writer.pipeline-depth", defaultValue = "8")
    int writerPipelineDepth;

    /** Held by the core writer's logic thread for its turn and by whoever replaces the engine's state, snapshots it or takes the ledger
     *  movements ({@link #enterGate}); see {@link CoreWriter}. Fair, so that the logic thread cannot starve those. Lock order: the ledger flush
     *  lock, this, the engine lock. */
    private final java.util.concurrent.locks.ReentrantLock writerGate = new java.util.concurrent.locks.ReentrantLock(true);

    /** Takes {@link #writerGate} and waits until the core writer's pipeline is drained: the engine is then exactly what the journal holds
     *  (see {@link CoreWriter#enterGate}). Release with {@code writerGate.unlock()}. */
    private void enterGate() {
        CoreWriter w = writer;
        if (w == null) {
            writerGate.lock();
        } else {
            w.enterGate();
        }
    }
    private volatile CoreWriter writer;

    private CoreWriter writer() {
        CoreWriter w = writer;
        if (w == null) {
            synchronized (this) {
                w = writer;
                if (w == null) {
                    w = new CoreWriter(new CoreWriter.Host() {
                        @Override
                        public java.util.concurrent.locks.ReentrantLock gate() {
                            return writerGate;
                        }

                        @Override
                        public void lockEngine() {
                            engineLock.writeLock().lock();
                        }

                        @Override
                        public void unlockEngine() {
                            engineLock.writeLock().unlock();
                        }

                        @Override
                        public boolean ready() {
                            return rebuildService.isReady();
                        }

                        @Override
                        public Object apply(GameEvent event) {
                            return eventApplier.apply(event);
                        }

                        @Override
                        public void recover(Throwable cause, String key, long nowMs) {
                            rebuildService.recoverAfterFailure(cause, key, nowMs);
                        }

                        @Override
                        public void stageDied(String thread, Throwable cause) {
                            // the container restarts (restart: unless-stopped) and the engine is rebuilt from the snapshot and the journal
                            io.quarkus.runtime.Quarkus.asyncExit(1);
                        }

                        @Override
                        public Object prepareViews(List<GameEvent> events) {
                            return prepareViewsOf(events);
                        }

                        @Override
                        public void publishViews(Object prepared, long version) {
                            publishPreparedViews(prepared, version);
                        }

                    }, eventStore, writerMaxBatch <= 0 ? 256 : writerMaxBatch, writerMaxWaitMs,
                            writerQueueCapacity <= 0 ? 10_000 : writerQueueCapacity,
                            writerPipelineDepth <= 0 ? CoreWriter.DEFAULT_PIPELINE_DEPTH : writerPipelineDepth);
                    writer = w;
                }
            }
        }
        return w;
    }

    void stopWriter() {
        CoreWriter w = writer;
        if (w != null) {
            w.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // The read views (architecture plan F8)
    // ---------------------------------------------------------------------------------------

    /** What the hot reads answer from: immutable views of the committed state, published by the core writer after each commit. */
    private final ReadViews views = new ReadViews();

    /** The read views, for the read model ({@code MarketViewService}) and for tests. */
    public ReadViews views() {
        return views;
    }

    /**
     * The latest committed views, with no lock: 503 {@code ENGINE_NOT_READY} while the engine cannot be trusted (the views
     * themselves hold committed state only, but a rebuild is about to replace them) or before the first publication. Whatever
     * is read from the snapshot is as of {@link ReadViews.Snapshot#version()}.
     */
    public ReadViews.Snapshot snapshot() {
        requireReady();
        ReadViews.Snapshot snapshot = views.current();
        if (snapshot == null) {
            throw ApiException.engineNotReady();
        }
        return snapshot;
    }

    /** Publishes the views of the engine as it is, with the journal's last event as their version: what a rebuild does before the
     *  engine is marked ready. Called holding the writer's gate (a rebuild does). */
    public void publishAllViews() {
        publishAllViews(eventStore.lastId());
    }

    /** {@link #publishAllViews()} with the version given (tests of plain objects have no journal). */
    public void publishAllViews(long version) {
        views.publishFull(hub, betFeedStore, roundProgress, version);
    }

    /** After a batch has been applied, on the logic thread: builds the next views from the engine as the batch left it and publishes
     *  nothing (they become visible when the batch is durable, {@link #publishPreparedViews}). Falls back to a full build, and when that
     *  fails too, to a marker that invalidates the views, so that reads answer 503 rather than something old. Never throws. */
    private Object prepareViewsOf(List<GameEvent> events) {
        if (!rebuildService.isReady()) {
            return null; // the engine cannot be trusted: the rebuild that makes it ready publishes the views in full
        }
        try {
            return views.prepareBatch(events, hub, betFeedStore, roundProgress);
        } catch (RuntimeException | Error e) {
            LOG.error("the read views could not be prepared after a batch - preparing them in full", e);
        }
        try {
            return views.prepareFull(hub, betFeedStore, roundProgress);
        } catch (RuntimeException | Error e) {
            LOG.error("the read views could not be prepared in full either - reads answer 503 until a rebuild publishes them", e);
            return ViewsInvalid.MARKER;
        }
    }

    /** The prepared views of a durable batch become visible, on the output stage. Never throws. */
    private void publishPreparedViews(Object prepared, long version) {
        try {
            if (prepared instanceof ReadViews.Prepared p) {
                views.publish(p, version);
            } else if (prepared == ViewsInvalid.MARKER) {
                views.invalidate();
            }
        } catch (RuntimeException | Error e) {
            LOG.error("the read views could not be published after the commit of a batch - reads answer 503 until a rebuild publishes them", e);
            views.invalidate();
        }
    }

    private enum ViewsInvalid { MARKER }

    /** What the core writer's pipeline has done so far, {@code null} before the first command. */
    public CoreWriter.Stats writerStats() {
        CoreWriter w = writer;
        return w == null ? null : w.stats();
    }

    /** The number of commands waiting for the core writer (0 before the first one). */
    public int writerQueued() {
        CoreWriter w = writer;
        return w == null ? 0 : w.queued();
    }

    /** Runs {@code command} on the core writer and returns its answer once the batch it was in has committed: 503
     *  {@code ENGINE_NOT_READY} while the engine cannot be trusted (checked here at once, and again by the writer), and for
     *  every command of a batch that failed to commit. Every changing call of this class goes through here. */
    private <T> T write(String key, long nowMs, Command<T> command) {
        requireReady();
        return writer().submit(command, true, key, nowMs);
    }

    private <T> T write(Command<T> command) {
        return write(null, System.currentTimeMillis(), command);
    }

    /** {@link #write} for a command that is meant to run, and decide for itself, while the engine is not ready. */
    private <T> T writeWhateverTheState(String key, long nowMs, Command<T> command) {
        return writer().submit(command, false, key, nowMs);
    }

    /** What a caller outside the game's own HTTP face wants of a failed batch: the failure itself, not "503, try again" -
     *  the scheduler counts a settlement that failed, the operator's cancel says why. */
    private static RuntimeException failureBehind(ApiException e) {
        if (e.code() == Enums.ApiErrorCode.ENGINE_NOT_READY && e.getCause() instanceof RuntimeException cause) {
            return cause;
        }
        return e;
    }

    /** 503 {@code ENGINE_NOT_READY} while the engine's memory cannot be trusted (class javadoc). */
    private void requireReady() {
        if (!rebuildService.isReady()) {
            throw ApiException.engineNotReady();
        }
    }

    /** {@link #requireReady} for the read model ({@code MarketViewService}), which may answer from a cache:
     *  a cached answer was built from memory that cannot be trusted any more, so it is not given either. */
    public void requireEngineReady() {
        requireReady();
    }

    /**
     * Waits, without any lock, until the derived rows of the rounds that {@link #resolveRound} has resolved and is still
     * writing are committed: a read of those rows (a round's result, a profile's history, "unseen results") then never
     * shows the balance of a settlement without its result. Returns at once when nothing is being written. The wait is
     * bounded: {@link #STORE_WAIT_MS} in all, not per round. When the rows are still not there then (the write is failing
     * and being retried), the read answers with what the tables hold: the round is not in the history, and its own result
     * answers 404 "not stored yet", while balances, which come from the engine, already show the settlement.
     */
    private void awaitStored() {
        if (storing.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STORE_WAIT_MS);
        for (PendingRows pending : List.copyOf(storing.values())) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                pending.stored.get(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException e) {
                return;
            }
        }
    }

    /** A cursor of {@link RoundProgress} moved. On the writer thread it is a command at work, and the views follow with the batch; on
     *  any other thread the views take the cursors as they are once the batch in flight (if any) is over. */
    private void cursorsMovedOutsideTheWriter() {
        CoreWriter w = writer;
        if (w != null && w.onPipelineThread()) {
            return;
        }
        enterGate();
        try {
            views.publishCursors(roundProgress);
        } finally {
            writerGate.unlock();
        }
    }

    /** Registers this service with the registry at startup, so a market listed or closed from now
     *  on reaches {@link #onListed}/{@link #onClosed}. A market that was listed before this runs is
     *  not lost: every rebuild creates the engines of every market in the registry, and the
     *  scheduler seeds any running market that still has no series ({@link #ensureSeries}). */
    void onStart(@Observes StartupEvent ev) {
        marketRegistry.setListener(this);
        // F8: a cursor moved outside the writer (the scheduler skips an empty window, steps over a round that is closed) reaches the views
        roundProgress.setChangeListener(this::cursorsMovedOutsideTheWriter);
        // the registry's journal writes (MarketListed, MarketClosed) take their turn on the core writer, in a batch like any command
        marketRegistry.setWriterTurn(new WriterTurn() {
            @Override
            public <T> T run(java.util.function.Function<Turn, T> work) {
                return writer().submit(ctx -> work.apply(new Turn() {
                    @Override
                    public void record(GameEvent event, long atMs) {
                        ctx.stage(event, atMs); // the engine has nothing to do with a listing; the listener makes the engines (when ready)
                    }

                    @Override
                    public void onRollback(Runnable action) {
                        ctx.onRollback(action);
                    }
                }), false, "market", System.currentTimeMillis());
            }
        });
    }

    // ---------------------------------------------------------------------------------------
    // Round id helpers
    // ---------------------------------------------------------------------------------------

    /** {@code RoundWindow.parseRoundId} with the failure mapped to a 400: BTC ids stay
     *  {@code "<code>-<startMs>"}, any other market's are {@code "<market>-<code>-<startMs>"}. */
    private static RoundWindow.RoundRef parseRoundId(String roundId) {
        try {
            return RoundWindow.parseRoundId(roundId);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("malformed roundId: " + roundId);
        }
    }

    private RoundWindow windowFor(String roundId) {
        RoundWindow.RoundRef ref = parseRoundId(roundId);
        long durationMs = config.roundDurations().forInterval(ref.interval());
        return new RoundWindow(ref.market(), ref.interval(), ref.startMs(), ref.startMs() + durationMs);
    }

    private static Enums.Interval toDto(Interval interval) {
        return Enums.Interval.valueOf(interval.name());
    }

    private static Interval toEngine(Enums.Interval interval) {
        return Interval.valueOf(interval.name());
    }

    private static Enums.Direction toDto(crowdvsmarket.engine.round.Direction d) {
        return Enums.Direction.valueOf(d.name());
    }

    /** The kind of a market as every round DTO carries it ({@code Rounds}' class javadoc). */
    private static Enums.MarketKind kindOf(Market market) {
        return Enums.MarketKind.valueOf(market.kind().name());
    }

    private static crowdvsmarket.engine.round.Direction toEngine(Enums.Direction d) {
        return crowdvsmarket.engine.round.Direction.valueOf(d.name());
    }

    // ---------------------------------------------------------------------------------------
    // Player onboarding (called by AuthService right after a verified sign-in)
    // ---------------------------------------------------------------------------------------

    /** Called at the end of every successful {@code authVerify} — both a brand-new player
     *  (Р-1/Р-4: identity key = the address signed with at registration) and a returning one
     *  (Р-1: "later логины must sign with the same address").
     *
     * <p><b>Task item 1 fix (live-verification finding, Р-53 follow-up, 2026-09-26):</b> this used
     * to unconditionally append a fresh {@code PlayerRegistered} event on EVERY sign-in, by design
     * (the previous revision of this javadoc said so explicitly) — which is exactly why
     * {@code game_events} showed two {@code PlayerRegistered} rows for the same {@code playerId}
     * (ids 8985/9048 on the live stand) after nothing more than a second, completely ordinary
     * sign-in. Now a returning player only gets a fresh event when it would actually change ledger
     * state: {@link LedgerBook#seasonRecord} tells us, without mutating anything, whether this
     * player has already touched {@code now}'s season — if so, {@code ensureSeasonPack} would be a
     * no-op anyway (see its own javadoc), so nothing is appended; a plain re-login within the same
     * season is now a true no-op in the log, not another row. A brand-new player, or a returning
     * one whose season genuinely rolled over since their last touch, still gets one — that is what
     * keeps the Р-2 "starter pack immediately at sign-in" behavior working (see {@link #account}'s
     * javadoc) and what {@code EngineRebuildService}'s replay needs to reproduce the exact same
     * starter-pack credit after a restart. Replay of the OLD, already-persisted duplicate rows is
     * unaffected and safe without any change here — {@code EventApplier}'s {@code PlayerRegistered}
     * case has always just called {@code ensureSeasonPack}, which was already idempotent per season
     * (applying the same-season duplicate a second time is a no-op); see
     * {@code PlayerRegistrationIdempotencyTest}.
     *
     * <p><b>{@code starterPackGranted}</b> (first tester pass, D1): whether THIS sign-in credited a {@code STARTER_PACK} — a
     * brand-new player's, or a returning player's first sign-in of a new season (calendar month: the pack is credited on top of the
     * balance they have). The web app tells the player about a pack when it was credited, so a returning player whose season rolled
     * over is told, and one who signs in twice in a month is told once. It is asked of the ledger ({@link
     * LedgerBook#seasonPackDue}, the one rule {@code ensureSeasonPack} itself follows) just BEFORE the event that credits the pack is
     * applied — afterwards the season is touched and the question has no answer any more — and only when that event is appended
     * at all; a sign-in that appends nothing credited nothing.
     *
     * @param isNewPlayer        this sign-in registered the player
     * @param starterPackGranted this sign-in credited a starter pack to the player */
    public record PlayerTouchResult(Identity.PlayerSummaryDto player, boolean isNewPlayer, boolean starterPackGranted) {
    }

    public PlayerTouchResult registerOrTouchPlayer(String walletAddress, long nowMs) {
        return registerOrTouchPlayer(walletAddress, nowMs, null);
    }

    /**
     * {@link #registerOrTouchPlayer(String, long)} from a known client address (economy stage B, plan B3): a NEW player is refused
     * ({@code 429 REGISTRATION_LIMIT}) when that address has already registered {@code herd.registration.max-per-ip-per-day} new players
     * in the last 24 hours. Players that exist already sign in as ever. The address is kept only as a salted hash
     * ({@code RegistrationIpRepository}). {@code null} or {@code "unknown"} (no peer address could be read) is not limited.
     */
    public PlayerTouchResult registerOrTouchPlayer(String walletAddress, long nowMs, String clientIp) {
        return registerOrTouchPlayer(walletAddress, nowMs, clientIp, null, null);
    }

    /** The player's own invite code and the number of players who registered through it (bots excluded); assigns the code on first use. */
    public crowdvsmarket.server.dto.Auth.InviteDto myInvite(String playerId) {
        return new crowdvsmarket.server.dto.Auth.InviteDto(invites.ensureCode(playerId), invites.referredCount(playerId));
    }

    /**
     * {@link #registerOrTouchPlayer(String, long, String)} with what the client kept from the landing URL: {@code referralCode}
     * (another player's invite code) and {@code source} (a channel tag). Both are used only when this call registers a NEW player,
     * once, in the transaction that inserts the row; an unknown or malformed code, the player's own, or a malformed tag records
     * nothing for that part, and the sign-in goes on regardless. A returning player's call never changes them.
     */
    public PlayerTouchResult registerOrTouchPlayer(String walletAddress, long nowMs, String clientIp, String referralCode,
                                                   String source) {
        requireReady();
        // What a NEW player needs from the database and from no engine state is done here, before the writer: the writer's
        // turn is for the engine and the one transaction of its batch. A wallet seen in the directory is a returning player;
        // the writer asks again, so a second sign-in of a wallet that registers in the meantime is a returning one there.
        NewPlayerPrep prep = null;
        if (players.playerIdForWallet(walletAddress).isEmpty()) {
            prep = prepareNewPlayer(clientIp, referralCode, source, nowMs);
        }
        final NewPlayerPrep newPlayer = prep;
        boolean registeredHere = false;
        try {
            Registration r = write("sign-in", nowMs, ctx -> {
                Optional<String> existing = players.playerIdForWallet(walletAddress);
                String playerId;
                boolean isNew = existing.isEmpty();
                boolean packGranted = false;
                Instant now = Instant.ofEpochMilli(nowMs);
                if (isNew) {
                    if (newPlayer == null) {
                        throw new IllegalStateException("no preparation for the new player " + walletAddress);
                    }
                    playerId = newPlayer.playerId();
                    GameEvent.PlayerRegistered registered = GameEvent.PlayerRegistered.registration(playerId, nowMs, walletAddress,
                            newPlayer.playerIndex(), null, PlayerDirectory.KIND_HUMAN, null, null, null);
                    ctx.touch();
                    // The directory has the row at once (the next command of the batch sees the wallet); a batch that fails
                    // to commit is followed by a rebuild, which reloads the directory from the table. The row itself (the
                    // projection of the event) is written in the batch's transaction.
                    players.cache(PlayerDirectory.rowOf(registered, null, null));
                    packGranted = hub.ledger().seasonPackAmount(playerId, now).isPositive(); // a player nobody has seen: the first pack
                    ctx.record(registered, nowMs);
                } else {
                    playerId = existing.get();
                    Season season = Season.of(now);
                    boolean seasonAlreadyTouched = hub.ledger().seasonRecord(playerId, season).isPresent();
                    if (!seasonAlreadyTouched) {
                        packGranted = hub.ledger().seasonPackAmount(playerId, now).isPositive(); // a top-up of nothing is no pack
                        ctx.record(new GameEvent.PlayerRegistered(playerId, nowMs), nowMs);
                    }
                }
                return new Registration(playerId, isNew, packGranted);
            });
            registeredHere = r.isNew();
            return new PlayerTouchResult(summary(r.playerId()), r.isNew(), r.starterPackGranted());
        } finally {
            if (newPlayer != null && !registeredHere) {
                // the sign-in did not register the player prepared for it (the wallet was registered meanwhile, or the batch did not
                // commit): what was written for the registration is taken back, so that no place of the address is spent on it
                newPlayer.forget(registrationIps, invites, nowMs);
            }
        }
    }

    private record Registration(String playerId, boolean isNew, boolean starterPackGranted) {
    }

    /**
     * What a new player's registration needs from the database, found before the writer's turn: the id the player will have, the hash of the
     * address (when the address is limited), the player index reserved for the player, and the referrer. Р-107: what the registration
     * records that is NOT in the journal - the place spent on the address ({@code registration_ips}), the invite code, the referrer and
     * the channel ({@code player_sidecar}) - is already written when this record exists, in one transaction, before the command: a crash
     * leaves a place spent and a sidecar row nothing refers to, never a registered player without them.
     */
    private record NewPlayerPrep(String playerId, String ipHash, int playerIndex, String referrerId) {

        void forget(crowdvsmarket.server.persistence.RegistrationIpRepository registrationIps,
                    crowdvsmarket.server.persistence.InviteRepository invites, long nowMs) {
            try {
                if (ipHash != null) {
                    registrationIps.forget(ipHash, nowMs);
                }
                invites.forget(playerId);
            } catch (RuntimeException e) {
                LOG.warnf(e, "could not take back the registration prepared for %s - a place of the address stays spent", playerId);
            }
        }
    }

    private final Object registrationLimitMonitor = new Object();

    private NewPlayerPrep prepareNewPlayer(String clientIp, String referralCode, String source, long nowMs) {
        String ipHash = null;
        if (clientIp != null && !clientIp.isBlank() && !"unknown".equals(clientIp)) {
            ipHash = registrationIps.hash(clientIp);
        }
        String playerId = UUID.randomUUID().toString();
        String referrerId = referralCode == null ? null : invites.playerIdForCode(referralCode).orElse(null);
        int perDay = config.registrationMaxPerIpPerDay();
        String hash = ipHash;
        // the count of the address and the place spent on it are one step: simultaneous sign-ups from one address cannot all pass the last place
        synchronized (registrationLimitMonitor) {
            if (hash != null && registrationIps.countInWindow(hash, nowMs) >= perDay) {
                throw ApiException.registrationLimit("this network has already registered " + perDay
                        + " new player" + (perDay == 1 ? "" : "s") + " in the last 24 hours - no more new players can sign "
                        + "up from it until then (a player who already signed up can still sign in)");
            }
            int playerIndex = serverMeta.reserveNextPlayerIndex();
            db.tx(c -> {
                invites.recordAtRegistration(c, playerId, referrerId, source);
                if (hash != null) {
                    registrationIps.record(c, hash, nowMs);
                }
                return null;
            });
            return new NewPlayerPrep(playerId, hash, playerIndex, referrerId);
        }
    }

    /** Used by {@code BotService} to create the 20 labelled bot players once (Р-46), and (with
     *  {@code isBot=false}) by nothing else — bots skip the wallet-signature flow entirely, they
     *  are server-side only. */
    public Identity.PlayerSummaryDto createBotPlayer(String nickname, String strategy, long nowMs) {
        return createBotPlayer(nickname, strategy, nowMs, null);
    }

    /**
     * {@link #createBotPlayer(String, String, long)} that takes the place of the retired bot {@code replacesPlayerId} (economy stage B,
     * plan B4): in the same transaction the retired bot's row is marked replaced, so one retired bot is replaced once. The new bot's pack
     * comes from TREASURY, like any pack.
     */
    public Identity.PlayerSummaryDto createBotPlayer(String nickname, String strategy, long nowMs, String replacesPlayerId) {
        requireReady();
        String playerId = "bot-" + strategy.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID();
        String walletAddress = "txbot1" + playerId.replace("-", "").substring(0, Math.min(40, playerId.length()));
        int playerIndex = serverMeta.reserveNextPlayerIndex(); // before the writer's turn: no database round trip in it
        write("bot", nowMs, ctx -> {
            GameEvent.PlayerRegistered registered = GameEvent.PlayerRegistered.registration(playerId, nowMs, walletAddress,
                    playerIndex, nickname, PlayerDirectory.KIND_HOUSE_BOT, strategy, null, replacesPlayerId);
            ctx.touch();
            players.cache(PlayerDirectory.rowOf(registered, null, null));
            if (replacesPlayerId != null) {
                players.cacheReplaced(replacesPlayerId);
            }
            ctx.record(registered, nowMs);
            return null;
        });
        return summary(playerId);
    }

    // ---------------------------------------------------------------------------------------
    // API bots (Р-106): bots of outside developers, owned by a person, played through the API with a key
    // ---------------------------------------------------------------------------------------

    /** The most API bots one person may own (Р-106). Revoking a key does not free a place: a bot is never deleted. */
    public static final int MAX_API_BOTS_PER_OWNER = 3;

    /** The season each API bot was last checked against, so a key call costs the engine lock only once per bot and season. */
    private final ConcurrentHashMap<String, String> apiBotSeasonTouched = new ConcurrentHashMap<>();

    private Bots.ApiBotDto toDto(PlayerDirectory.Row row) {
        return new Bots.ApiBotDto(row.playerId(), row.nickname(), row.keyHint(), row.keyHash() != null, row.createdAtMs());
    }

    private PlayerDirectory.Row requireOwnedApiBot(String ownerId, String botId) {
        return players.byId(botId == null ? "" : botId)
                .filter(r -> r.isApiBot() && ownerId.equals(r.ownerId()))
                .orElseThrow(() -> ApiException.notFound("unknown bot " + botId));
    }

    /**
     * Creates an API bot for {@code ownerId}: its own player (the {@code PlayerRegistered} event credits the normal starter pack, like any
     * player's) marked {@code kind=API_BOT} with {@code is_bot} left false, and its first key. At most {@value #MAX_API_BOTS_PER_OWNER} per
     * owner, counted under the engine lock so two simultaneous calls cannot both take the last place. The key is returned in full, once.
     */
    public Bots.ApiBotKeyDto createApiBot(String ownerId, String name, long nowMs) {
        String nickname = name == null ? "" : name.strip();
        if (!NICKNAME_PATTERN.matcher(nickname).matches()) {
            throw ApiException.invalidNickname("bot name must be 3-24 characters: letters, digits, space, '_', '.', '-' only");
        }
        requireReady();
        requireApiBotPlace(ownerId); // a first look, so that only a call that can pass reserves a player index
        String playerId = "api-" + UUID.randomUUID();
        String walletAddress = "txapi1" + playerId.replace("-", "").substring(3);
        int playerIndex = serverMeta.reserveNextPlayerIndex(); // before the writer's turn: no database round trip in it
        String key = ApiKeys.generate();
        String hash = ApiKeys.hash(key);
        String hint = ApiKeys.hint(key);
        // Р-107: the key is not in the journal, so it is written first (player_sidecar; the projector copies it into the players row with
        // the registration): a crash leaves a key nothing refers to, never a registered bot without its key
        db.tx(c -> {
            players.updateKey(c, playerId, hash, hint);
            return null;
        });
        PlayerDirectory.Row row;
        try {
            row = write("api bot", nowMs, ctx -> {
                requireApiBotPlace(ownerId); // the decision, on the state of the writer: two simultaneous calls cannot both take the last place
                GameEvent.PlayerRegistered registered = GameEvent.PlayerRegistered.registration(playerId, nowMs, walletAddress,
                        playerIndex, nickname, PlayerDirectory.KIND_API_BOT, null, ownerId, null);
                PlayerDirectory.Row created = PlayerDirectory.rowOf(registered, hash, hint);
                ctx.touch();
                players.cache(created);
                ctx.record(registered, nowMs);
                ctx.afterCommit(() -> apiBotSeasonTouched.put(playerId, Season.of(Instant.ofEpochMilli(nowMs)).id()));
                return created;
            });
        } catch (RuntimeException | Error e) {
            players.forgetKey(playerId); // the bot was not registered: the key is taken back
            throw e;
        }
        return new Bots.ApiBotKeyDto(toDto(row), key);
    }

    /** The owner exists, is a person, and has a place left for one more API bot. */
    private void requireApiBotPlace(String ownerId) {
        PlayerDirectory.Row owner = players.byId(ownerId).orElseThrow(() -> ApiException.notFound("unknown player " + ownerId));
        if (owner.isBot() || owner.isApiBot()) {
            throw ApiException.apiCallNotAllowed("createBot");
        }
        if (players.apiBotsOf(ownerId).size() >= MAX_API_BOTS_PER_OWNER) {
            throw ApiException.botLimitReached("you already have " + MAX_API_BOTS_PER_OWNER
                    + " API bots - that is the most one person can create");
        }
    }

    /** The API bots {@code ownerId} owns, oldest first. */
    public List<Bots.ApiBotDto> myApiBots(String ownerId) {
        return players.apiBotsOf(ownerId).stream().map(this::toDto).toList();
    }

    /** A new key for the bot; the old one stops opening it at once. Returned in full, once. */
    public Bots.ApiBotKeyDto regenerateApiKey(String ownerId, String botId) {
        requireReady();
        requireOwnedApiBot(ownerId, botId);
        String key = ApiKeys.generate();
        String hash = ApiKeys.hash(key);
        String hint = ApiKeys.hint(key);
        // no event, nothing for the writer: a key is a database write (player_sidecar and, once projected, the players row), done
        // synchronously, and the cache follows the commit
        db.tx(c -> {
            players.updateKey(c, botId, hash, hint);
            return null;
        });
        players.cacheKey(botId, hash, hint);
        return new Bots.ApiBotKeyDto(toDto(players.byId(botId).orElseThrow()), key);
    }

    /** Revokes the bot's key: nothing opens the bot until a new key is generated. Idempotent. */
    public Bots.ApiBotDto revokeApiKey(String ownerId, String botId) {
        requireReady();
        requireOwnedApiBot(ownerId, botId);
        db.tx(c -> {
            players.updateKey(c, botId, null, null);
            return null;
        });
        players.cacheKey(botId, null, null);
        return toDto(players.byId(botId).orElseThrow());
    }

    /** The API bot a key opens: the SHA-256 of the key is looked up; empty for an unknown or revoked key. */
    public Optional<PlayerDirectory.Row> apiBotForKey(String key) {
        if (!ApiKeys.wellFormed(key)) {
            return Optional.empty();
        }
        return players.byKeyHash(ApiKeys.hash(key)).filter(PlayerDirectory.Row::isApiBot);
    }

    /**
     * The first call of an API bot in a season credits that season's starter pack, the way a person's first sign-in of a season does
     * (the same {@code PlayerRegistered} event, the same no-op when the season is already touched). Free after the first call of a
     * season: the check is remembered in memory.
     */
    public void touchApiBot(String playerId, long nowMs) {
        Season season = Season.of(Instant.ofEpochMilli(nowMs));
        if (season.id().equals(apiBotSeasonTouched.get(playerId))) {
            return;
        }
        write("api bot touch", nowMs, ctx -> {
            if (hub.ledger().seasonRecord(playerId, season).isEmpty()) {
                ctx.record(new GameEvent.PlayerRegistered(playerId, nowMs), nowMs);
            }
            ctx.afterCommit(() -> apiBotSeasonTouched.put(playerId, season.id()));
            return null;
        });
    }

    /** A player's PLAY balance, empty for a player the ledger does not know (a bot's view of its own account, {@code bots.ServerBotPorts}). */
    public Optional<PlayAmount> balanceOf(String playerId) {
        return snapshot().player(playerId).map(ReadViews.PlayerView::balance);
    }

    /** The ids of the rounds (running and open for betting, every market and interval) the player has a bet in that has not settled yet. */
    public java.util.Set<String> openBetRoundIds(String playerId) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (OpenBets.Entry entry : OpenBets.of(snapshot(), playerId)) {
            ids.add(entry.window().roundId());
        }
        return ids;
    }

    /**
     * Writes {@code botStrategy} (the bots module's {@code catalogId}) to the bot's {@code players} row and cache. Like a nickname,
     * it is not part of the event log: a bot's strategy has no effect on ledger or crowd-account maths.
     */
    public void assignBotStrategy(String playerId, String botStrategy) {
        write(ctx -> {
            players.byId(playerId).filter(PlayerDirectory.Row::isBot)
                    .orElseThrow(() -> ApiException.notFound("unknown bot " + playerId));
            GameEvent.BotStrategyAssigned event = new GameEvent.BotStrategyAssigned(playerId, botStrategy, System.currentTimeMillis());
            ctx.touch();
            players.cacheBotStrategy(playerId, botStrategy);
            ctx.record(event, event.atMs());
            return null;
        });
    }

    /** A bot's new name, written the way {@link #setNickname} writes a player's but without the players' allow-list: a bot's name
     *  carries its strategy in parentheses ({@code BotService} relabels a bot moved to another strategy). */
    public void renameBot(String playerId, String nickname) {
        write(ctx -> {
            players.byId(playerId).filter(PlayerDirectory.Row::isBot)
                    .orElseThrow(() -> ApiException.notFound("unknown bot " + playerId));
            GameEvent.NicknameChanged event = new GameEvent.NicknameChanged(playerId, nickname, System.currentTimeMillis());
            ctx.touch();
            players.cacheNickname(playerId, nickname);
            ctx.record(event, event.atMs());
            return null;
        });
    }

    public Identity.PlayerSummaryDto summary(String playerId) {
        PlayerDirectory.Row row = players.byId(playerId)
                .orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
        PlayerIdentity identity = row.identity();
        return new Identity.PlayerSummaryDto(playerId, identity.nickname(), row.walletAddress(),
                identity.displayName(), row.displaysAsBot());
    }

    /** Wave-1 finding: trimmed, 3–24 chars, allow-list {@code [A-Za-z0-9 _.-]} — the allow-list
     *  itself already rejects control characters (including NUL) and anything outside that set,
     *  since {@link java.util.regex.Matcher#matches()} requires the *whole* (trimmed) string to be
     *  built only from these characters. */
    private static final java.util.regex.Pattern NICKNAME_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9 _.-]{3,24}");

    /** Nicknames are not unique, so there is nothing to check-then-write; what this changes — the
     *  {@code players} row and the directory's cache — is what sign-in, bot creation and a rebuild's
     *  reload change under the engine write lock, and this takes it too (it used to be {@code
     *  synchronized} on the service's own monitor, which excludes only another {@code setNickname}: a
     *  reload between its UPDATE and its commit left the cache with the old nickname for good). The
     *  cache gets the nickname after the commit. */
    public Identity.PlayerSummaryDto setNickname(String playerId, String nickname) {
        String trimmed = nickname == null ? "" : nickname.strip();
        if (!NICKNAME_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.invalidNickname(
                    "nickname must be 3-24 characters: letters, digits, space, '_', '.', '-' only");
        }
        return write(ctx -> {
            players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
            GameEvent.NicknameChanged event = new GameEvent.NicknameChanged(playerId, trimmed, System.currentTimeMillis());
            ctx.touch();
            players.cacheNickname(playerId, trimmed);
            ctx.record(event, event.atMs());
            return summary(playerId);
        });
    }

    // ---------------------------------------------------------------------------------------
    // Markets: lookup and series lifecycle (Р-69, docs/research/markets_architecture.md §3.1, §4)
    // ---------------------------------------------------------------------------------------

    /** The market named by an optional market argument; absent means BTC (contract.ts), see {@link #defaultMarket}. */
    private Market requireMarket(String marketIdOrNull) {
        return marketIdOrNull == null ? defaultMarket() : marketRegistry.require(marketIdOrNull);
    }

    /**
     * What "no market" means — the bitcoin pair (contract.ts: {@code marketId} omitted → BTC; {@code home},
     * {@code btcReferencePrice}). Normally the registry's {@code BTC} row. When that row was closed because its price
     * moved to another source ("Moved to a new price source: Coinbase", {@code MarketRegistry#closeReplaced}), it is
     * the active crypto market of symbol {@code BTC} that took over — a closed market has no running rounds and no
     * price, and an omitted market must not mean "nothing" for as long as the game runs. With no such market (a replaced
     * BTC whose successor is not listed yet, an operator who closed it) the closed row itself, as before: its own answers
     * ({@code MARKET_CLOSED}) say what happened.
     */
    private Market defaultMarket() {
        Market btc = marketRegistry.require(Market.BTC_ID);
        if (btc.isActive()) {
            return btc;
        }
        return marketRegistry.active().stream()
                .filter(m -> Category.CRYPTO.equals(m.category()) && btc.symbol().equals(m.symbol())).findFirst()
                .orElse(btc);
    }

    /** Whether the market's rounds are played on this server at all: every market in {@code
     *  mode=live}, only BTC in {@code mode=replay}, whatever the registry holds — the replay series
     *  is BTC's (§3.2 п.7, Т-12). */
    public boolean runs(Market market) {
        return config.mode() == GameMode.LIVE || Market.BTC_ID.equals(market.id());
    }

    /** The markets whose rounds are played right now: active and {@link #runs}. What the scheduler
     *  and the bots iterate. */
    public List<Market> runningMarkets() {
        return marketRegistry.active().stream().filter(this::runs).toList();
    }

    /**
     * Opens the rounds that have started (Р-76, "a round runs entirely on one exchange"): for every running market that has a
     * backup exchange, the running window of each series that has no exchange yet is given the one it runs on — the primary
     * when it has a live price at the round's start, else the backup when it has; with none live on either side, whichever
     * can price the start from the history, carry or pin it holds, the primary first; else none yet and the round waits; the
     * choice is written once and never revisited ({@link MarketPrices#openRound}). The rounds that start together (both series at a
     * shared boundary) are decided on the same data and agree. {@code mode=live} only. No lock is taken here: the choice is read
     * off the price rings and written to the database, and the engine's lock must never wait for either; one market's
     * failure (the database, say) never stops the others, and a round that is not opened now is opened at the next tick or,
     * at the latest, when it is settled.
     */
    public void openRounds(long nowMs) {
        if (config.mode() != GameMode.LIVE) {
            return;
        }
        for (Market market : runningMarkets()) {
            if (!Instruments.hasBackup(market)) {
                continue;
            }
            for (Interval interval : Interval.values()) {
                Optional<RoundWindow> running = roundProgress.findNext(market.id(), interval);
                if (running.isEmpty() || nowMs < running.get().startMs()) {
                    continue;
                }
                try {
                    marketPrices.openRound(market, running.get(), nowMs);
                } catch (RuntimeException e) {
                    if (venueLog.allow(market.id() + "/" + interval, nowMs)) {
                        LOG.warnf("could not open %s: %s", running.get().roundId(), e.toString());
                    }
                }
            }
        }
    }

    /**
     * The price half of letting a round go (Р-77): whether the exchange of {@code market} that {@code window} was <i>not</i>
     * decided on is alive now ({@link MarketPrices#otherVenueLive}). Only a market that has a backup exchange has another
     * exchange, and only {@code mode=live} has exchanges at all. Reads the price rings and, for a round that is not in
     * memory, the table of decisions; takes no lock, so it is for the scheduler and never for code under the engine's lock.
     */
    public boolean otherVenueLive(Market market, RoundWindow window, long nowMs) {
        if (config.mode() != GameMode.LIVE || !Instruments.hasBackup(market)) {
            return false;
        }
        return marketPrices.otherVenueLive(market, window, nowMs);
    }

    /**
     * The instrument the round of {@code market} is priced on (Р-76, Р-79): the exchange it was decided to run on — the market's
     * shadow when that is its backup, the market itself for its own exchange — and the market itself for a round that has no
     * decision (and for a market without a backup, and in replay mode, which has no exchanges). What the scheduler reads the wait
     * of a round that cannot be priced from ({@code PricesConfig#releaseAfterMs}). Memory only: the decisions are loaded at start.
     */
    public Market instrumentOf(Market market, RoundWindow window) {
        if (config.mode() != GameMode.LIVE || !Instruments.hasBackup(market)) {
            return market;
        }
        return Instruments.runningOn(market, marketPrices.decidedVenue(market, window));
    }

    /** Gives {@code market} its engines and, when its rounds are played, a series per interval
     *  seeded at the window containing "now" (§4 п.2) — never at the calendar's first window, which
     *  would make the scheduler walk every window since the origin, and never behind a window that
     *  still holds a bet ({@link SeriesSeeder}). Idempotent: a series that already has a cursor keeps
     *  it. Called for a newly listed market, and by the scheduler (with its own clock, {@code nowMs})
     *  for a running market that has no series (a listing notification that came before this service
     *  was the registry's listener, or was lost). A no-op while the engine is not ready — before the
     *  first rebuild has finished the calendar has no origin yet: the rebuild creates the engines of
     *  every market in the registry and seeds the running ones, and a listing must not throw out of
     *  {@code MarketRegistry#list}, whose caller has committed it. */
    public void ensureSeries(Market market, long nowMs) {
        writeWhateverTheState("series " + market.id(), nowMs, ctx -> {
            if (!rebuildService.isReady()) {
                return null;
            }
            ensureSeriesLocked(market, nowMs);
            return null;
        });
    }

    private void ensureSeriesLocked(Market market, long nowMs) {
        hub.ensureMarket(market);
        // The registry's current word, not the caller's snapshot: the scheduler may hand over a market
        // that was closed (and its series stopped by onClosed) a moment ago.
        Market current = marketRegistry.byId(market.id()).orElse(market);
        if (!current.isActive() || !runs(current)) {
            return;
        }
        for (Interval interval : Interval.values()) {
            if (!roundProgress.isSeeded(market.id(), interval)) {
                roundProgress.seed(market.id(), interval, seeder.seedFor(hub, current, interval, nowMs));
            }
        }
    }

    /** {@link MarketListener}: a market was listed — create its engines and seed its series. Called
     *  outside the registry's lock, so taking the engine lock here cannot deadlock against a thread
     *  that holds the engine lock and reads the registry. */
    @Override
    public void onListed(Market market) {
        ensureSeries(market, System.currentTimeMillis());
    }

    /** {@link MarketListener}: a market was closed — cancel its betting round and its running round
     *  with a refund (reason {@code "market closed: <reason>"}, listed publicly like every
     *  cancellation) and stop its series ({@link RoundCancellations#closeSeries}). A no-op while the
     *  engine is not ready, and a failure half-way starts a rebuild: either way the rebuild cancels,
     *  with a refund, every open round of every market that is closed in the database. */
    @Override
    public void onClosed(Market market, String reason) {
        long nowMs = System.currentTimeMillis();
        writeWhateverTheState("market " + market.id(), nowMs, ctx -> {
            if (!rebuildService.isReady()) {
                return null;
            }
            hub.ensureMarket(market);
            ctx.touch(); // a failure from here on rebuilds, and the rebuild cancels, with a refund, every open round of every closed market
            cancellations.closeSeries(ctx, hub, market, "market closed: " + reason, nowMs);
            return null;
        });
        derivedRows.awaitIdle(STORE_WAIT_MS); // the cancelled rounds' result rows are written off the writer; the closer waits for them
    }

    /** Whether any bet waits in {@code window}'s round. */
    public boolean hasPendingBets(RoundWindow window) {
        return snapshot().betCount(window.roundId()) > 0;
    }

    private boolean hasBetsLocked(RoundWindow window) {
        return hub.findMarket(window.market())
                .map(m -> m.engine(window.interval()).betBook().count(window.roundId()) > 0)
                .orElse(false);
    }

    /**
     * The catch-up rule of docs/research/markets_architecture.md §4 п.5, for the scheduler: the first
     * window at or after {@code from} that must still be resolved or cancelled. A window with bets
     * always is. An EMPTY window that is overdue by more than one step (its betting round is over too)
     * is not worth resolving — no event, no result, no oracle record — so the walk goes on to the next
     * one; the result is {@code from} itself in the normal case of a round that has just ended.
     */
    public RoundWindow firstWindowToResolve(RoundWindow from, long nowMs) {
        long stepMs = from.endMs() - from.startMs();
        ReadViews.Snapshot snapshot = snapshot();
        RoundWindow window = from;
        while (nowMs - window.endMs() > stepMs && snapshot.betCount(window.roundId()) == 0) {
            window = RoundClock.next(window);
        }
        return window;
    }

    // ---------------------------------------------------------------------------------------
    // Betting (Р-8, Р-13, Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public Rounds.BetDto placeBet(String playerId, Betting.PlaceBetRequest req, long nowMs) {
        RoundWindow.RoundRef ref = parseRoundId(req.roundId());
        marketRegistry.require(ref.market()); // MARKET_NOT_FOUND without waiting for the writer
        final String requestId = req.clientRequestId();
        if (requestId != null && !Betting.PlaceBetRequest.validRequestId(requestId)) {
            throw ApiException.badRequest("invalid clientRequestId: 1-" + Betting.PlaceBetRequest.MAX_REQUEST_ID_LENGTH
                    + " characters of A-Z a-z 0-9 - _");
        }
        HerdPlaceBet jfr = new HerdPlaceBet(); // I-2: phases of the call, the clock is read only while JFR records
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.outcome = "ERROR";
        }
        try {
            Rounds.BetDto dto = write("bet", nowMs, ctx -> {
                jfr.waitNs = ctx.queueWaitNanos();
                long t;
                if (requestId != null) {
                    // A repeat of a request the writer has already placed (the client did not get the answer): the first answer
                    // again, nothing placed. Checked before anything else - the round may be closed by now, the first bet stands.
                    BetRequests.Entry seen = hub.betRequests().find(playerId, requestId, nowMs);
                    if (seen != null) {
                        return repeatedBet(seen, ref, req);
                    }
                }
                // Read again under the lock: a close announced while this call waited for it wins.
                Market market = marketRegistry.require(ref.market());
                if (!market.isActive()) {
                    throw ApiException.marketClosed(market.id());
                }
                hub.ensureMarket(market);
                RoundWindow bettingWindow = roundProgress.findNext(market.id(), ref.interval())
                        .map(RoundClock::next)
                        .orElseThrow(() -> ApiException.roundNotOpenForBetting(req.roundId()));
                // Bug fix (wave-2 MINOR finding): reject strictly by wall-clock time against the
                // window's own startMs, not only by roundId equality against RoundProgress's cursor.
                // RoundProgress only advances when RoundSchedulerService's tick (every
                // herd.scheduler.tick-ms, ~1s) actually resolves the previous round — so for up to that
                // ~1s after a round has, by definition, already started (now >= bettingWindow.startMs()),
                // the cursor could still name this same roundId as "open for betting" and let a late bet
                // slip in. Comparing nowMs (the request's own arrival time, not a re-read of
                // System.currentTimeMillis() here) closes that gap regardless of scheduler timing.
                if (!bettingWindow.roundId().equals(ref.roundId()) || nowMs >= bettingWindow.startMs()) {
                    throw ApiException.roundNotOpenForBetting(req.roundId());
                }
                // §4 п.6: a round that is already closed (the engine's marker) was cancelled by the operator before it
                // started — the cancellation refunded and cleared its bets, so a bet accepted now would
                // sit in the bet book with its stake debited and never be settled or refunded.
                t = JfrClock.nanos(rec);
                if (hub.closedRounds().isClosed(bettingWindow)) {
                    throw ApiException.roundNotOpenForBetting(req.roundId());
                }
                jfr.existsNs = JfrClock.since(rec, t);
                // Only registered players bet. A bot tick that started before the player directory was
                // reset (test DB cleanup between classes) otherwise waited for this lock and then
                // placed bets for bots that no longer exist — the ledger's ensureSeasonPack below
                // would have given them a starter pack.
                if (players.byId(playerId).isEmpty()) {
                    throw ApiException.notFound("unknown player " + playerId);
                }
                BigDecimal stakeDecimal = clientDecimal(req.stake(), "stake");
                PlayAmount stake;
                try {
                    stake = PlayAmount.fromMoney(stakeDecimal);
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest(e.getMessage());
                }
                // Economy stage A (Р-92): 2..the cap of this market for this round length (plan A2), lowered by the size of the
                // stake (plan A3); the message names the highest leverage allowed for this stake. The fees follow the leverage (A4).
                MarketSpec spec = market.spec();
                // Р-106: the fixed-payout mode has no leverage; it is for every market but the odds markets (rules point 4)
                boolean fixed = req.isFixed();
                if (fixed && market.kind() == crowdvsmarket.server.markets.MarketKind.ODDS) {
                    throw ApiException.badRequest("the win-or-lose (fixed payout) mode is not available on odds markets - "
                            + market.id() + " takes percentage-mode bets only");
                }
                Fees fees;
                if (fixed) {
                    fees = EconomyRules.fixedFees(stake);
                } else {
                    try {
                        EconomyRules.requireLeverageAllowed(spec, ref.interval(), req.leverage(), stake);
                        new Leverage(req.leverage());
                    } catch (IllegalArgumentException e) {
                        throw ApiException.invalidLeverage(e.getMessage());
                    }
                    fees = EconomyRules.fees(spec, ref.interval(), req.leverage(), stake);
                }

                LedgerBook ledger = hub.ledger();
                Instant now = Instant.ofEpochMilli(nowMs);
                if (ledger.isRetired(playerId)) {
                    throw ApiException.badRequest("player " + playerId + " is retired");
                }
                // Economy stage B (plan B6): with TREASURY more than 10% below its 30-day maximum a new bet takes at most cap/4
                boolean breaker = ledger.circuitBreakerOpen(now);
                noteBreaker(breaker, ledger.treasury(), () -> ledger.treasuryMax30d(now));
                if (breaker && !fixed) { // the breaker lowers the leverage cap; a fixed-payout bet has none
                    int allowedNow = Math.min(EconomyRules.maxLeverage(spec, ref.interval(), stake),
                            EconomyLimits.breakerMaxLeverage(spec.capFor(ref.interval())));
                    if (req.leverage() > allowedNow) {
                        throw ApiException.invalidLeverage("leverage must be in [" + Leverage.MIN + ", " + allowedNow + "] for now on "
                                + spec.id() + ": the treasury is below 90% of its 30-day maximum, new bets take at most a quarter of the "
                                + "usual cap while it recovers (bets already open are paid as ever), got " + req.leverage());
                    }
                }
                ledger.ensureSeasonPack(playerId, now); // idempotent — also happens inside the event replay below

                if (stake.compareTo(LedgerBook.MIN_STAKE) < 0) {
                    throw ApiException.stakeBelowMinimum("stake " + stake + " is below the minimum " + LedgerBook.MIN_STAKE);
                }
                GameEngine engine = hub.engine(market.id(), ref.interval());
                PlayAmount currentBalance = ledger.balance(playerId);
                Optional<Bet> existing = engine.betBook().find(bettingWindow.roundId(), playerId);
                // a bet being replaced is refunded first, its fees too (a legacy bet has none)
                PlayAmount effectiveBalance = existing
                        .map(b -> currentBalance.plus(b.stake()).plus(b.isLegacy() ? PlayAmount.ZERO : b.fees().total()))
                        .orElse(currentBalance);
                PlayAmount totalDebit = stake.plus(fees.total());
                if (totalDebit.compareTo(effectiveBalance) > 0) {
                    throw ApiException.insufficientBalance("stake " + stake + " plus fees " + fees.total()
                            + " exceeds balance " + effectiveBalance);
                }

                t = JfrClock.nanos(rec);
                if (fixed) {
                    requireFixedExposureRoom(market, ref.interval(), bettingWindow, playerId, toEngine(req.direction()), stake);
                } else {
                    requireExposureRoom(market, ref.interval(), bettingWindow, playerId, toEngine(req.direction()), req.leverage(), stake);
                }
                jfr.exposureNs = JfrClock.since(rec, t);
                jfr.roundBets = rec ? engine.betBook().count(bettingWindow.roundId()) : 0;
                GameEvent.BetPlaced event;
                if (fixed) {
                    event = GameEvent.BetPlaced.fixed(playerId, ref.interval(), bettingWindow.startMs(), bettingWindow.endMs(),
                            toEngine(req.direction()), stake.mojos(), nowMs, market.id(), fees.herd().mojos(), fees.network().mojos());
                } else {
                    LiquidationParams liquidation = LiquidationParams.of(spec, ref.interval());
                    event = new GameEvent.BetPlaced(playerId, ref.interval(), bettingWindow.startMs(),
                            bettingWindow.endMs(), toEngine(req.direction()), req.leverage(), stake.mojos(), nowMs,
                            market.id(), fees.herd().mojos(), fees.network().mojos(), liquidation.cap(),
                            liquidation.kappa().toPlainString());
                }
                if (requestId != null) {
                    event = event.withClientRequestId(requestId); // the writer's memory of the id is rebuilt from this field
                }
                t = JfrClock.nanos(rec);
                ctx.record(event, nowMs);
                jfr.applyNs = JfrClock.since(rec, t);
                ctx.afterCommit(() -> jfr.persistNs = ctx.commitNanos());
                ctx.afterCommit(() -> publisher.betChanged(market.id(), bettingWindow.roundId(), playerId));
                return betDto(playerId, bettingWindow.roundId(), req.direction(),
                        fixed ? Bet.FIXED_LEVERAGE.value() : req.leverage(), stake, fees, spec, ref.interval(),
                        fixed ? crowdvsmarket.engine.round.BetMode.FIXED : crowdvsmarket.engine.round.BetMode.PERCENTAGE);
            });
            jfr.outcome = "OK";
            return dto;
        } catch (ApiException e) {
            jfr.outcome = e.code().name();
            throw e;
        } finally {
            if (rec) {
                jfr.end();
                jfr.commit();
            }
        }
    }

    /** The answer to a repeated {@code placeBet} ({@code clientRequestId}): the first answer, rebuilt from what the journal event says;
     *  a request that differs from the first one under the same id is refused. */
    private Rounds.BetDto repeatedBet(BetRequests.Entry seen, RoundWindow.RoundRef ref, Betting.PlaceBetRequest req) {
        boolean same = ref.roundId().equals(seen.roundId())
                && toEngine(req.direction()) == seen.direction()
                && req.isFixed() == seen.fixed()
                && (seen.fixed() || req.leverage() == seen.leverage());
        if (same) {
            try {
                same = PlayAmount.fromMoney(clientDecimal(req.stake(), "stake")).mojos() == seen.stakeMojos();
            } catch (RuntimeException e) {
                same = false;
            }
        }
        if (!same) {
            throw ApiException.badRequest("clientRequestId was already used for a different bet");
        }
        MarketSpec spec = marketRegistry.require(ref.market()).spec();
        Fees fees = seen.feeHerdMojos() == null ? null
                : new Fees(new PlayAmount(seen.feeHerdMojos()), new PlayAmount(seen.feeNetMojos()));
        return betDto(seen.playerId(), seen.roundId(), toDto(seen.direction()), seen.leverage(), new PlayAmount(seen.stakeMojos()),
                fees, spec, ref.interval(),
                seen.fixed() ? crowdvsmarket.engine.round.BetMode.FIXED : crowdvsmarket.engine.round.BetMode.PERCENTAGE);
    }

    /** A bet as the client sees it: with the fees charged and the highest leverage allowed for its stake when it was
     *  placed under the economy rules ({@code fees} {@code null} for a legacy bet: those fields are then {@code null}). */
    private static Rounds.BetDto betDto(String playerId, String roundId, Enums.Direction direction, int leverage,
                                        PlayAmount stake, Fees fees, MarketSpec spec, Interval interval,
                                        crowdvsmarket.engine.round.BetMode mode) {
        boolean fixed = mode == crowdvsmarket.engine.round.BetMode.FIXED;
        return new Rounds.BetDto(playerId, roundId, direction, leverage, stake.toMoney().toPlainString(),
                fees == null ? null : fees.herd().toMoney().toPlainString(),
                fees == null ? null : fees.network().toMoney().toPlainString(),
                fees == null || fixed ? null : EconomyRules.maxLeverage(spec, interval, stake),
                Enums.BetMode.wire(mode));
    }

    /** Р-13: cancels the player's bet on a round open for betting and refunds it; no bet — nothing persisted (§4 п.18). */
    public void cancelBet(String playerId, String roundId, long nowMs) {
        RoundWindow.RoundRef ref = parseRoundId(roundId);
        marketRegistry.require(ref.market());
        write("cancel bet", nowMs, ctx -> {
            RoundWindow bettingWindow = roundProgress.findNext(ref.market(), ref.interval())
                    .map(RoundClock::next)
                    .orElseThrow(() -> ApiException.roundNotOpenForBetting(roundId));
            // Same wave-2 exact-boundary fix as placeBet above — a bet must not be cancellable (and
            // its stake refunded) once its round has, by wall-clock time, already started.
            if (!bettingWindow.roundId().equals(ref.roundId()) || nowMs >= bettingWindow.startMs()) {
                throw ApiException.roundNotOpenForBetting(roundId);
            }
            if (!hub.hasBet(ref.market(), ref.interval(), bettingWindow.roundId(), playerId)) {
                return null;
            }
            GameEvent.BetCancelled event = new GameEvent.BetCancelled(playerId, ref.interval(),
                    bettingWindow.startMs(), bettingWindow.endMs(), nowMs, ref.market());
            ctx.record(event, nowMs);
            ctx.afterCommit(() -> publisher.betChanged(ref.market(), bettingWindow.roundId(), playerId));
            return null;
        });
    }

    // ---------------------------------------------------------------------------------------
    // Round resolution — called only by RoundSchedulerService (and the admin cancel path below)
    // ---------------------------------------------------------------------------------------

    /**
     * Resolves {@code window} (of any market) now: two phases, §4 п.3.
     * <ol>
     *   <li>Without any lock, asks the price source for the window's open, close and path — in
     *       {@code mode=live} that may go to the network, so it must never run under the engine
     *       lock. {@link PriceUnavailableException} propagates: the scheduler retries, and cancels the
     *       round one hour after its end (Р-23). So does a price it cannot be settled on (an open or
     *       close that is not positive, too long a path: {@link RoundPriceChecks}), found out here
     *       and not inside the settlement transaction.
     *   <li>As a command of the core writer: looks again whether the round was resolved or cancelled while prices were
     *       fetched (the engine's marker, {@link crowdvsmarket.server.domain.ClosedRounds} - then nothing happens and
     *       {@code null} is returned), takes the open from the round that closed there ({@link BoundaryPrices}, Р-40),
     *       applies the {@code RoundResolved} event to the engine, moves the close to the boundary prices and the series'
     *       cursor on (so the next round takes bets at once, behind this event in the journal). The event is written in
     *       the transaction of the batch the command is in, and the call returns after that commit. A batch that does not
     *       commit rebuilds the in-memory state from the event log (§4 п.13); the scheduler then sees the failure that
     *       made it fail and counts the attempt.
     *   <li>Without the lock: ONE transaction with the result and history rows and the oracle record, projections of that
     *       event ({@link #storeAfterTheLock}). A failure of that write does not fail the call: it is retried in the
     *       background with a growing delay ({@link #rowsFailed}), and the rebuild writes what a crash left
     *       ({@link #repairSettledResult}). docs/research/settle_outside_lock_2026-10-18.md.
     * </ol>
     * A round nobody bet on stores a single open→close candle as its path (§4 п.7); one with bets
     * stores the source's path (at most 30 candles in live mode).
     *
     * @return the engine's report, or {@code null} when the round was already resolved or cancelled
     */
    public RoundReport resolveRound(RoundWindow window, long nowMs) throws PriceUnavailableException {
        requireReady(); // no point in asking the price source while the engine cannot take the result
        Market market = marketRegistry.require(window.market());
        HerdSettle jfr = new HerdSettle(); // I-4: phases of the call, the clock is read only while JFR records
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.outcome = "ERROR";
        }
        try {
            RoundPriceData fetched = resolvePrices(market, window); // phase 1 — no lock

            Applied applied;
            try {
                applied = write("round " + window.roundId(), nowMs, ctx -> {
                    long locked = JfrClock.nanos(rec);
                    jfr.waitNs = ctx.queueWaitNanos();
                    try {
                        return journalAndApplyLocked(ctx, market, window, fetched, nowMs, jfr, rec, locked);
                    } finally {
                        jfr.lockedNs = JfrClock.since(rec, locked);
                    }
                });
            } catch (ApiException e) {
                throw failureBehind(e); // a batch that did not commit: the scheduler counts the failed attempt
            }
            if (applied == null) {
                return null;
            }
            // from here on the round is resolved for the game: the derived rows follow without any lock
            storeAfterTheLock(applied, jfr, rec);
            return applied.report();
        } finally {
            if (rec) {
                jfr.end();
                jfr.commit();
            }
        }
    }

    /** What phase 2 of {@link #resolveRound} hands to phase 3: the engine's report and the rows still to be written. */
    private record Applied(RoundReport report, PendingRows pending) {
    }

    /**
     * Phase 2 of {@link #resolveRound}, <b>run by the core writer</b>: the part that must be serialised with every
     * other change of the engine — the {@code RoundResolved} event applied to the engine and staged for the journal (it
     * is written with the batch; a batch that does not commit rebuilds the in-memory state from the journal, §4 п.13).
     * The result rows, the history and the oracle record are projections of that event; they are written by
     * {@link #storeAfterTheLock}. The round is entered in {@link #storing} at once (and taken out again if the batch
     * does not commit), so that nothing can resolve or cancel it a second time in between.
     *
     * @return what phase 3 needs, or {@code null} when the round was already resolved or cancelled
     */
    private Applied journalAndApplyLocked(WriteContext ctx, Market market, RoundWindow window, RoundPriceData fetched,
                                          long nowMs, HerdSettle jfr, boolean rec, long lockedAtNanos) {
        long t = lockedAtNanos;
        // already resolved or cancelled: the engine's marker, which the journal sets (live and in a replay)
        boolean alreadyDone = hub.closedRounds().isClosed(window);
        jfr.existsNs = JfrClock.since(rec, t);
        if (alreadyDone) {
            jfr.outcome = "SKIPPED";
            return null;
        }
        hub.ensureMarket(market);
        RoundPriceData data = boundaries.forSettlement(window, fetched);
        BigDecimal open = data.open().setScale(2, RoundingMode.HALF_UP);
        BigDecimal close = data.close().setScale(2, RoundingMode.HALF_UP);
        boolean hasBets = hasBetsLocked(window);
        List<Candle> path = hasBets && !data.path().isEmpty() ? data.path()
                : List.of(singleCandle(window, open, close));
        // F4: the engine assigns the oracle sequence number: above everything issued (the hub's counter, which replaying the journal
        // restores) and above the floor the chain service demanded (OracleSeqGate) - memory only, no database in the writer's turn.
        // Applying the event advances the counter, so the next settlement of the same batch gets the next number, and a batch that
        // fails leaves no hole (the rebuilt counter is the journal's).
        long oracleSeq = Math.max(hub.oracleSeq(), serverMeta.raisedOracleFloor()) + 1;
        GameEvent.RoundResolved event = new GameEvent.RoundResolved(window.interval(), window.startMs(),
                window.endMs(), open.toPlainString(), close.toPlainString(), EventApplier.fromCandles(path),
                nowMs, market.id(), data.venue(), oracleSeq);
        t = JfrClock.nanos(rec);
        RoundReport report = (RoundReport) ctx.record(event, nowMs); // applied now, in the journal with the batch
        jfr.applyNs = JfrClock.since(rec, t);
        jfr.bets = report.playerOutcomes().size();
        ctx.afterCommit(() -> jfr.appendNs = ctx.commitNanos()); // the journal write is the batch's commit
        List<String> bettors = report.playerOutcomes().stream().map(o -> o.playerId()).toList();
        ctx.afterCommit(() -> publisher.roundSettled(market.id(), window.roundId(), bettors));
        PendingRows pending = new PendingRows(market, window, report, data.sourceTsMs(), data.venue(), oracleSeq);
        storing.put(window.roundId(), pending);
        // a batch that does not commit takes the round back (the rebuild finds it unresolved): nobody waits for its rows
        ctx.onRollback(() -> {
            storing.remove(window.roundId(), pending);
            pending.stored.complete(null);
        });
        // the round is resolved in the engine: the close is the close on the exchange the round ran on (Р-76), the next
        // round of the series opens at it, and the series' cursor leaves the round, so the next round takes bets right away
        // (its bets are behind this event in the same batch or a later one, never ahead of it in the journal)
        boundaries.settled(window, close.movePointRight(2).longValueExact(), data.venue());
        roundProgress.advance(market.id(), window.interval(), window);
        return new Applied(report, pending);
    }

    /**
     * Phase 3 of {@link #resolveRound}, <b>without the engine lock</b>: ONE transaction with the result row, the
     * per-player history rows and the oracle record of the round the journal has already resolved. The round is resolved
     * for the game whether or not this succeeds; a failure leaves the journal ahead of these rows, which is exactly what a
     * crash at this point leaves. It is logged as an error and retried in the background ({@link #rowsFailed}); the call
     * returns the report as usual and the game goes on.
     */
    private void storeAfterTheLock(Applied applied, HerdSettle jfr, boolean rec) {
        PendingRows pending = applied.pending();
        try {
            long t0 = JfrClock.nanos(rec);
            writeRows(pending, jfr);
            jfr.storeNs = JfrClock.since(rec, t0);
            rowsWritten(pending);
        } catch (RuntimeException e) {
            rowsFailed(pending, e);
        } catch (Error e) {
            rowsFailed(pending, e);
            throw e;
        }
        jfr.outcome = "SETTLED";
    }

    /** One attempt: the rows in ONE transaction, unless an earlier attempt's commit got through after all. */
    private void writeRows(PendingRows p, HerdSettle jfr) {
        if (p.failures > 0 && roundResults.exists(p.window.roundId())) {
            return;
        }
        db.tx(c -> {
            storeSettledResult(c, p.market, p.window, p.report, p.sourceTsMs, p.venue, p.oracleSeq, jfr);
            return null;
        });
    }

    private void rowsWritten(PendingRows p) {
        String roundId = p.window.roundId();
        storing.remove(roundId, p);
        p.stored.complete(null);
        rebuildService.recovered(roundId);
        rowsFailureLog.clear(roundId);
    }

    /** The rows of a resolved round could not be written. The round stays resolved (journal and engine), the game goes
     *  on, the failure is an ERROR in the log (a full stack once, then a line a minute) and a row in
     *  {@link #missingRows}, and the write is tried again after 1 s, 2 s, 4 s ... up to a minute, for as long as it takes
     *  (or until the rebuild at the next start writes them). Readers that wait for the rows give up after
     *  {@link #STORE_WAIT_MS} ({@link #awaitStored}). */
    private void rowsFailed(PendingRows p, Throwable cause) {
        String roundId = p.window.roundId();
        p.failures = p.failures + 1;
        p.lastError = cause.toString();
        storing.putIfAbsent(roundId, p);
        switch (rowsFailureLog.next(roundId, System.currentTimeMillis())) {
            case STACK_TRACE -> LOG.errorf(cause, "the result rows of round %s (result, history, oracle record) could not "
                    + "be written - the round is resolved in the journal and the game goes on; retrying", roundId);
            case LINE -> LOG.errorf("the result rows of round %s still cannot be written (%d attempts, since %s): %s",
                    roundId, p.failures, java.time.Instant.ofEpochMilli(p.sinceMs), cause);
            case NOTHING -> {
                // already said this minute
            }
        }
        long delayMs = Math.min(rowsRetryMaxMs, rowsRetryBaseMs << Math.min(p.failures - 1, 20));
        scheduleRowsRetry(p, delayMs);
    }

    private synchronized void scheduleRowsRetry(PendingRows p, long delayMs) {
        if (rowsRetryExecutor == null) {
            rowsRetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "herd-rows-retry");
                t.setDaemon(true);
                return t;
            });
        }
        try {
            rowsRetryExecutor.schedule(() -> retryRows(p), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // shutting down: the rebuild at the next start writes them
        }
    }

    private void retryRows(PendingRows p) {
        if (storing.get(p.window.roundId()) != p) {
            return;
        }
        try {
            writeRows(p, new HerdSettle());
            rowsWritten(p);
            LOG.infof("the result rows of round %s are written after %d failed attempt(s)", p.window.roundId(),
                    p.failures);
        } catch (RuntimeException e) {
            rowsFailed(p, e);
        }
    }

    /**
     * The restart/rebuild half of the settlement: writes the result row, the history and the oracle record of a round
     * whose {@code RoundResolved} event is in the journal and has none ({@code EngineStateBuilder}, which replayed the
     * event and so has {@code report}) - every such round, not only the last. A round whose rows are being written or
     * retried by this process is left alone, and so is one that has its rows. A write that fails does not fail the
     * rebuild: the round goes to the background retry like a failure of the live write ({@link #rowsFailed}). The price
     * reading's own timestamp is not in the event: the open time of the last candle of its path stands for it in the oracle
     * record's {@code source_ts}.
     *
     * @return whether rows were written
     */
    public boolean repairSettledResult(GameEvent.RoundResolved event, RoundReport report) {
        RoundWindow window = report.window();
        if (storing.containsKey(window.roundId()) || roundResults.exists(window.roundId())) {
            return false;
        }
        Market market = marketRegistry.require(event.market());
        long sourceTsMs = event.path().isEmpty() ? event.atMs() : event.path().get(event.path().size() - 1).openTimeMs();
        LOG.warnf("round %s is resolved in the journal but has no result row (the process stopped, or the write failed, "
                + "after the event was committed) - writing the result row, the history and the oracle record from the "
                + "event", window.roundId());
        PendingRows pending = new PendingRows(market, window, report, sourceTsMs, event.venue(), event.oracleSeq());
        try {
            db.tx(c -> {
                storeSettledResult(c, market, window, report, sourceTsMs, event.venue(), event.oracleSeq(), new HerdSettle());
                return null;
            });
            return true;
        } catch (RuntimeException e) {
            rowsFailed(pending, e);
            return false;
        }
    }

    /** Phase 1 of {@link #resolveRound}: the settlement data of a finished window, in engine (lot)
     *  prices, checked before anything is locked ({@link RoundPriceChecks}). */
    private RoundPriceData resolvePrices(Market market, RoundWindow window) {
        if (config.mode() == GameMode.LIVE) {
            return RoundPriceChecks.requireUsable(window, marketPrices.resolve(market, window), true);
        }
        if (!Market.BTC_ID.equals(market.id())) {
            throw new IllegalStateException("mode=replay plays BTC only, cannot resolve " + window.roundId());
        }
        RoundPrices prices = priceService.replayPrices();
        List<Candle> path = prices.path(window);
        Candle last = path.get(path.size() - 1);
        return RoundPriceChecks.requireUsable(window, new RoundPriceData(prices.openPrice(window), last.close(), path,
                last.openTimeMs()), false);
    }

    /** One candle from {@code open} to {@code close}: the whole path of a round nobody bet on. Also the
     *  path of a running round with no tick yet inside it (package-private: the read model's too). */
    static Candle singleCandle(RoundWindow window, BigDecimal open, BigDecimal close) {
        return new Candle(window.startMs(), window.endMs() - 1, open, open.max(close), open.min(close), close,
                BigDecimal.ZERO, 0);
    }

    /** Inside {@link #resolveRound}'s transaction: the settled result, the per-player history rows
     *  and the oracle record. All prices in the DTOs are the market's raw strings; the price columns
     *  and the oracle record keep engine (lot) prices times 100 (§6). {@code venue}: the exchange the round ran on
     *  (Р-76) — in the canonical results JSON, so inside the hash that goes on chain, and in the result row; the oracle
     *  record's price is the close on that exchange; {@code null} for a market with one exchange: nothing changes for it. */
    private void storeSettledResult(Connection c, Market market, RoundWindow window, RoundReport report,
                                    long sourceTsMs, String venue, Long oracleSeq, HerdSettle jfr) throws SQLException {
        MarketSpec spec = market.spec();
        Map<String, PlayerIdentity> identities = players.identitiesById();
        List<Rounds.PublicPositionDto> positionDtos = new ArrayList<>();
        List<crowdvsmarket.server.util.ResultsHasher.PositionEntry> hashEntries = new ArrayList<>();
        List<RoundResultRepository.PlayerHistoryRow> historyRows = new ArrayList<>();

        BigDecimal entryPrice = report.openPrice();
        String entryRaw = market.rawString(entryPrice);
        for (PlayerRoundOutcome outcome : report.playerOutcomes()) {
            PlayerIdentity identity = identities.get(outcome.playerId());
            Identity.PlayerSummaryDto playerSummary = identity == null
                    ? new Identity.PlayerSummaryDto(outcome.playerId(), outcome.playerId(), "", outcome.playerId(), null)
                    : summary(outcome.playerId());
            boolean fixed = outcome.mode() == crowdvsmarket.engine.round.BetMode.FIXED;
            Position position = fixed ? null : Position.openFromMargin(outcome.playerId(), window.roundId(),
                    toEngine(toDto(outcome.direction())), new Leverage(outcome.leverage()),
                    outcome.stake().toMoney(), entryPrice,
                    crowdvsmarket.engine.round.Bet.specFor(outcome.fees() == null, outcome.liquidation(), spec, window.interval()));
            BigDecimal pnl = outcome.payout().subtract(outcome.stake().toMoney());
            Boolean directionCorrect = directionCorrect(outcome.direction(), report.openPrice(), report.closePrice());

            // a fixed-payout position has no size and no liquidation price (Р-106)
            positionDtos.add(new Rounds.PublicPositionDto(outcome.playerId(), window.roundId(), toDto(outcome.direction()),
                    outcome.leverage(), fixed ? "0.00000000" : position.size().toPlainString(), entryRaw,
                    fixed ? null : market.rawString(position.liquidationPrice()), pnl.toPlainString(),
                    outcome.stake().toMoney().toPlainString(), playerSummary, outcome.liquidated(),
                    PlayAmount.fromMoney(outcome.payout()).toMoney().toPlainString(), directionCorrect,
                    Enums.BetMode.wire(outcome.mode())));

            hashEntries.add(new crowdvsmarket.server.util.ResultsHasher.PositionEntry(outcome.playerId(),
                    toDto(outcome.direction()).name(), outcome.leverage(), outcome.stake().mojos(),
                    outcome.liquidated() ? "LIQUIDATED" : "SETTLED", PlayAmount.fromMoney(outcome.payout()).mojos(),
                    fixed ? "FIXED" : null));

            historyRows.add(new RoundResultRepository.PlayerHistoryRow(outcome.playerId(), toDto(outcome.direction()),
                    outcome.leverage(), outcome.stake().mojos(), outcome.liquidated(), directionCorrect,
                    PlayAmount.fromMoney(outcome.payout()).mojos(),
                    outcome.fees() == null ? null : outcome.fees().total().mojos(), outcome.mode().name()));
        }

        CrowdAccount.RoundResult crowd = report.crowdResult();
        Rounds.CrowdRoundResultDto crowdDto = new Rounds.CrowdRoundResultDto(window.roundId(), crowd.traded(),
                crowd.direction() == null ? null : toDto(crowd.direction()), crowd.leverage(),
                crowd.margin() == null ? null : crowd.margin().toPlainString(),
                crowd.entryPrice() == null ? null : market.rawString(crowd.entryPrice()), crowd.liquidated(),
                crowd.capitalBefore().toPlainString(), crowd.capitalAfter().toPlainString());

        crowdvsmarket.server.util.ResultsHasher.CrowdEntry crowdHashEntry = !crowd.traded() ? null
                : new crowdvsmarket.server.util.ResultsHasher.CrowdEntry(toDto(crowd.direction()).name(), crowd.leverage(),
                        crowd.margin().movePointRight(2).longValueExact(),
                        crowd.capitalAfter().subtract(crowd.capitalBefore()).movePointRight(2).longValueExact());

        long openCents = report.openPrice().movePointRight(2).longValueExact();
        long closeCents = report.closePrice().movePointRight(2).longValueExact();
        var canonical = crowdvsmarket.server.util.ResultsHasher.build(market.id(), window.roundId(),
                window.interval().name(), "SETTLED", openCents, closeCents, hashEntries, crowdHashEntry, venue);

        long settledAtMs = window.endMs() - 1;
        roundResults.insertSettled(c, market.id(), window.roundId(), window.interval(), window.startMs(),
                window.endMs(), openCents, closeCents, positionDtos, crowdDto, canonical.hash(), canonical.json(),
                settledAtMs, historyRows, venue);
        boolean rec = jfr.isEnabled();
        long t = JfrClock.nanos(rec);
        enqueueOracleRecord(c, market, window.roundId(), settledAtMs, sourceTsMs, closeCents, canonical.hash(), oracleSeq);
        jfr.oracleNs = JfrClock.since(rec, t);
    }

    private static Boolean directionCorrect(crowdvsmarket.engine.round.Direction direction, BigDecimal openPrice,
                                              BigDecimal closePrice) {
        int cmp = closePrice.compareTo(openPrice);
        if (cmp == 0) {
            return null;
        }
        boolean actualUp = cmp > 0;
        return (direction == crowdvsmarket.engine.round.Direction.UP) == actualUp;
    }

    /** Р-40/41/47, Т-9: queues one oracle record for this round's boundary — one per round of every
     *  market, one shared {@code seq} — tagged with the game's own mode as the record's {@code source}
     *  (replay test-mode publishes for real too, Р-47) and with the market and its registry index.
     *  Part of the settlement transaction: the seq is reserved and the row inserted together with the
     *  event and the result, so a rolled-back settlement leaves no hole in the sequence.
     *  {@code OraclePublisherService} does the actual best-effort chainsvc POST on its own schedule, so
     *  a slow/unavailable chainsvc never blocks round resolution (the row just sits with
     *  {@code chainsvc_queued=false} until it succeeds).
     *
     *  @param ts        wall-clock publish time (round settlement instant), ms
     *  @param sourceTsMs the underlying price reading's own timestamp — the real historical
     *                     instant in replay mode (Р-47: "в announcement помечено, что цена из
     *                     повтора истории"), the tick the close price was read from in live mode
     *  @param priceCents engine (lot) close price times 100 */
    private void enqueueOracleRecord(Connection c, Market market, String roundId, long ts, long sourceTsMs,
                                     long priceCents, String resultsHash, Long oracleSeq) throws SQLException {
        // F4: the sequence number was assigned by the engine and is in the event; the counter in server_meta only follows it. An event
        // written before F4 has none, and takes the next number from the counter as it always did.
        long seq;
        if (oracleSeq != null) {
            seq = oracleSeq;
            serverMeta.projectOracleSeq(c, seq);
        } else {
            seq = serverMeta.reserveOracleSeqRange(c, 1);
        }
        String source = config.mode() == GameMode.REPLAY ? "replay" : "live";
        oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market.id(), market.oracleIndex(), roundId, seq,
                ts / 1000, priceCents, resultsHash, source, sourceTsMs / 1000, System.currentTimeMillis()));
    }

    /** {@code AdminResource}'s entry point: parses {@code roundId} and delegates to
     *  {@link #cancelRound(RoundWindow, String, long)} (which runs it on the core writer). */
    public Rounds.CancellationDto cancelRoundById(String roundId, String reason, long nowMs) {
        return cancelRound(windowFor(roundId), reason, nowMs);
    }

    /** Operator/automatic cancellation (Р-23, Р-32). {@code window} must be the currently running
     *  or currently open-for-betting round of its series; already-resolved rounds are rejected. */
    public Rounds.CancellationDto cancelRound(RoundWindow window, String reason, long nowMs) {
        Market market = marketRegistry.require(window.market());
        try {
            Rounds.CancellationDto done = write("cancel " + window.roundId(), nowMs, ctx -> {
                if (hub.closedRounds().isClosed(window)) {
                    throw ApiException.badRequest("round " + window.roundId() + " is already resolved");
                }
                Optional<RoundWindow> running = roundProgress.findNext(market.id(), window.interval());
                if (running.isEmpty()
                        || (!window.equals(running.get()) && !window.equals(RoundClock.next(running.get())))) {
                    throw ApiException.badRequest("not the current running or betting round for " + market.id() + "/"
                            + window.interval());
                }
                hub.ensureMarket(market);
                // the event goes with the batch, its result row follows (DerivedRows); never touches RoundProgress (the scheduler steps over)
                Rounds.CancellationDto cancelled = cancellations.cancel(ctx, hub, market, window, reason, nowMs);
                ctx.afterCommit(() -> rebuildService.recovered(window.roundId()));
                return cancelled;
            });
            derivedRows.awaitIdle(STORE_WAIT_MS); // the operator's answer comes with the cancelled round's result row (written off the writer)
            return done;
        } catch (ApiException e) {
            throw failureBehind(e); // a batch that did not commit: the operator is told why
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reads: rounds / round (Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    /** The series' running window — {@code RoundProgress}'s cursor — or a 4xx saying why there is
     *  none: the market is closed, or its rounds are not played here (mode=replay plays BTC only) or
     *  not yet (just listed). */
    private RoundWindow runningWindowOf(Market market, Interval interval, ReadViews.Snapshot snapshot) {
        return snapshot.cursor(market.id(), interval).orElseThrow(() -> market.isActive()
                ? ApiException.notFound("market " + market.id() + " has no running " + interval + " series")
                : ApiException.marketClosed(market.id()));
    }

    /** The running and betting round of {@code marketIdOrNull}'s series ({@code null}: BTC). */
    public Rounds.RoundsPairDto roundsPair(Enums.Interval intervalDto, String marketIdOrNull,
                                           String viewerPlayerIdOrNull) {
        requireMarket(marketIdOrNull);
        awaitStored();
        return roundsPair(intervalDto, marketIdOrNull, viewerPlayerIdOrNull, snapshot());
    }

    private Rounds.RoundsPairDto roundsPair(Enums.Interval intervalDto, String marketIdOrNull, String viewerPlayerIdOrNull,
                                            ReadViews.Snapshot snapshot) {
        Market market = requireMarket(marketIdOrNull);
        Interval interval = toEngine(intervalDto);
        RoundWindow runningWindow = runningWindowOf(market, interval, snapshot);
        RoundWindow bettingWindow = RoundClock.next(runningWindow);
        Rounds.RoundDto running = roundResults.findRoundDto(runningWindow.roundId())
                .orElseGet(() -> buildRunningDto(market, runningWindow, System.currentTimeMillis(), snapshot));
        Rounds.RoundDto betting = roundResults.findRoundDto(bettingWindow.roundId())
                .orElseGet(() -> buildBettingDto(market, bettingWindow, viewerPlayerIdOrNull, snapshot));
        return new Rounds.RoundsPairDto(intervalDto, running, betting);
    }

    /** {@code BTC-1h-<start>} is {@code 1h-<start>}: the id is made canonical before it is a key. */
    public Rounds.RoundDto round(String roundId, String viewerPlayerIdOrNull) {
        roundId = parseRoundId(roundId).roundId();
        awaitStored();
        Optional<Rounds.RoundDto> settledOrCancelled = roundResults.findRoundDto(roundId);
        if (settledOrCancelled.isPresent()) {
            return settledOrCancelled.get();
        }
        RoundWindow window = windowFor(roundId);
        Market market = marketRegistry.require(window.market());
        ReadViews.Snapshot snapshot = snapshot();
        if (snapshot.isClosed(window)) {
            // resolved in the journal, no row after the bounded wait (awaitStored): it is being retried
            throw ApiException.notFound("the result of round " + roundId + " is not stored yet");
        }
        Optional<RoundWindow> running = snapshot.cursor(market.id(), window.interval());
        if (running.isPresent()) {
            if (window.equals(running.get())) {
                return buildRunningDto(market, window, System.currentTimeMillis(), snapshot);
            }
            if (window.equals(RoundClock.next(running.get()))) {
                return buildBettingDto(market, window, viewerPlayerIdOrNull, snapshot);
            }
        }
        throw ApiException.notFound("unknown or not-yet-open round " + roundId);
    }

    /** Р-62's small, capped, newest-first convenience embed on {@code RoundBetting.recentBets} —
     *  предложение, matches the number the web side's own mock already used. The full,
     *  cursor-paginated feed ({@code betFeed} RPC) has no such cap on total history, only on one
     *  call's page size ({@code BetFeed.PAGE_SIZE}). */
    private static final int RECENT_BETS_LIMIT = 14;

    private Rounds.RoundDto buildBettingDto(Market market, RoundWindow window, String viewerPlayerIdOrNull,
                                            ReadViews.Snapshot snapshot) {
        List<Bet> bets = snapshot.betsFor(window.roundId());
        Rounds.BetDto myBet = viewerPlayerIdOrNull == null ? null : bets.stream()
                .filter(b -> b.playerId().equals(viewerPlayerIdOrNull))
                .findFirst()
                .map(b -> betDto(b.playerId(), window.roundId(), toDto(b.direction()), b.leverage().value(), b.stake(),
                        b.fees(), market.spec(), window.interval(), b.mode()))
                .orElse(null);
        LiveSplit split = buildLiveSplit(bets, market.spec(), window.interval());
        List<Rounds.LiveBetDto> recentBets = snapshot.feed().latest(window.roundId(), RECENT_BETS_LIMIT).stream()
                .map(e -> new Rounds.LiveBetDto(summaryOrFallback(e.playerId()), e.direction(), e.leverage(),
                        e.stake(), e.atMs(), e.mode()))
                .toList();
        return new Rounds.RoundBetting(window.roundId(), new Rounds.RoundWindowDto(toDto(window.interval()),
                window.startMs(), window.endMs()), market.id(), market.symbol(), kindOf(market),
                market.priceDecimals(), sourceOf(market, window), bets.size(), myBet, split.upStake(), split.downStake(),
                split.upCount(), split.downCount(), split.majorityDirection(), split.majorityLeverage(), recentBets);
    }

    /** Defensive fallback for a {@code playerId} the {@link PlayerDirectory} cache has no row for
     *  (should not happen in practice — same "should not happen" situation as
     *  {@link #storeSettledResult}'s identical fallback for the exact same reason: every bet is
     *  preceded by that player's own registration). */
    private Identity.PlayerSummaryDto summaryOrFallback(String playerId) {
        return players.byId(playerId).isPresent() ? summary(playerId)
                : new Identity.PlayerSummaryDto(playerId, playerId, "", playerId, null);
    }

    private record LiveSplit(String upStake, String downStake, int upCount, int downCount,
                               Enums.Direction majorityDirection, Integer majorityLeverage) {
    }

    /** Р-62: the crowd split visible live during {@code BETTING} — same majority/weighted-leverage
     *  math as {@link #buildRunningDto}'s {@code crowdPreview} (and {@code CrowdAccount}'s own, at
     *  actual settlement), just computed one phase earlier, straight from the bets still pending in
     *  {@code BetBook} (no entry price exists yet, so no margin/liquidation preview here — only the
     *  split and the majority side's direction/leverage). */
    private LiveSplit buildLiveSplit(List<Bet> bets, MarketSpec marketSpec, Interval interval) {
        // under the economy-stage-A rules the crowd leverage is clamped to the cap of this round length
        MarketSpec spec = bets.stream().anyMatch(b -> !b.isLegacy()) ? marketSpec.forInterval(interval) : marketSpec;
        BigDecimal upStake = PlayAmount.ZERO.toMoney();
        BigDecimal downStake = PlayAmount.ZERO.toMoney();
        int upCount = 0;
        int downCount = 0;
        List<Bet> upBets = new ArrayList<>();
        List<Bet> downBets = new ArrayList<>();
        for (Bet bet : bets) {
            BigDecimal stake = bet.stake().toMoney();
            if (bet.direction() == crowdvsmarket.engine.round.Direction.UP) {
                upStake = upStake.add(stake);
                upCount++;
                upBets.add(bet);
            } else {
                downStake = downStake.add(stake);
                downCount++;
                downBets.add(bet);
            }
        }
        int cmp = upStake.compareTo(downStake);
        Enums.Direction majorityDirection = null;
        Integer majorityLeverage = null;
        if (cmp != 0) {
            List<Bet> majorityBets = cmp > 0 ? upBets : downBets;
            majorityDirection = cmp > 0 ? Enums.Direction.UP : Enums.Direction.DOWN;
            majorityLeverage = weightedLeverage(majorityBets, spec);
        }
        return new LiveSplit(upStake.toPlainString(), downStake.toPlainString(), upCount, downCount,
                majorityDirection, majorityLeverage);
    }

    /** Предложение: a live preview of the currently-running round — revealed positions/crowd entry
     *  (Р-22) computed from the bets still sitting in the engine's {@code BetBook} plus the price
     *  path so far, WITHOUT mutating {@code LedgerBook}/{@code CrowdAccount} (those only actually
     *  change at real settlement, via {@link #resolveRound}). See
     *  docs/research/round_engine_design.md's own note that "RUNNING" is not a stored engine
     *  state.
     *
     *  <p>Prices come from {@link #soFar} — memory only, so this is safe under the read lock (the open is the close of
     *  the round that ended where this one started, if any: Р-40). When the open is not known yet (the market was
     *  listed during this window, or the tick buffer is still empty after a restart) there is nothing to derive an
     *  entry price from: the DTO then carries {@code openPrice = null} (contract.ts: "waiting for the opening price";
     *  never a zero, never the latest tick), no positions and no crowd position, instead of failing {@code rounds}. */
    private Rounds.RoundDto buildRunningDto(Market market, RoundWindow window, long nowMs, ReadViews.Snapshot snapshot) {
        Rounds.RoundWindowDto windowDto = new Rounds.RoundWindowDto(toDto(window.interval()), window.startMs(),
                window.endMs());
        Optional<RoundPriceData> soFar = soFar(market, window, nowMs);
        if (soFar.isEmpty()) {
            return new Rounds.RoundRunning(window.roundId(), windowDto, market.id(), market.symbol(), kindOf(market),
                    market.priceDecimals(), sourceOf(market, window), null, List.of(), null);
        }
        MarketSpec spec = market.spec();
        BigDecimal openPrice = soFar.get().open();
        List<Candle> pathSoFar = soFar.get().path().isEmpty()
                ? List.of(singleCandle(window, openPrice, openPrice)) : soFar.get().path();
        String openRaw = market.rawString(openPrice);

        Map<String, PlayerIdentity> identities = players.identitiesById();
        List<Bet> bets = snapshot.betsFor(window.roundId());
        List<Rounds.PublicPositionDto> positions = new ArrayList<>();
        BigDecimal upStake = ZERO_MONEY;
        BigDecimal downStake = ZERO_MONEY;
        List<Bet> upBets = new ArrayList<>();
        List<Bet> downBets = new ArrayList<>();
        for (Bet bet : bets) {
            PlayerIdentity identity = identities.get(bet.playerId());
            Identity.PlayerSummaryDto playerSummary = identity == null
                    ? new Identity.PlayerSummaryDto(bet.playerId(), bet.playerId(), "", bet.playerId(), null)
                    : summary(bet.playerId());
            if (bet.isFixed()) { // Р-106: no position to simulate - the bet is won or lost by the close against the open; nothing to report mid-round
                positions.add(new Rounds.PublicPositionDto(bet.playerId(), window.roundId(), toDto(bet.direction()),
                        bet.leverage().value(), "0.00000000", openRaw, null, null, bet.stake().toMoney().toPlainString(),
                        playerSummary, false, null, null, Enums.BetMode.FIXED));
            } else {
                Position position = Position.openFromMargin(bet.playerId(), window.roundId(), bet.direction(),
                        bet.leverage(), bet.stake().toMoney(), openPrice, bet.specFor(spec, window.interval()));
                PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, pathSoFar);
                boolean liquidatedSoFar = outcome instanceof PositionSimulator.Outcome.Liquidated;
                BigDecimal pnl = liquidatedSoFar ? null
                        : ((PositionSimulator.Outcome.Settled) outcome).pnl();
                positions.add(new Rounds.PublicPositionDto(bet.playerId(), window.roundId(), toDto(bet.direction()),
                        bet.leverage().value(), position.size().toPlainString(), openRaw,
                        market.rawString(position.liquidationPrice()), pnl == null ? null : pnl.toPlainString(),
                        bet.stake().toMoney().toPlainString(), playerSummary, liquidatedSoFar, null, null));
            }
            BigDecimal stake = bet.stake().toMoney();
            if (bet.direction() == crowdvsmarket.engine.round.Direction.UP) {
                upStake = upStake.add(stake);
                upBets.add(bet);
            } else {
                downStake = downStake.add(stake);
                downBets.add(bet);
            }
        }

        Rounds.CrowdRoundPositionDto crowdPreview = null;
        int cmp = upStake.compareTo(downStake);
        if (cmp != 0) {
            crowdvsmarket.engine.round.Direction majorityDirection = cmp > 0
                    ? crowdvsmarket.engine.round.Direction.UP : crowdvsmarket.engine.round.Direction.DOWN;
            List<Bet> majorityBets = cmp > 0 ? upBets : downBets;
            // the crowd account runs under the economy rules of the round length when any bet of the round does
            MarketSpec crowdSpec = bets.stream().anyMatch(b -> !b.isLegacy()) ? spec.forInterval(window.interval()) : spec;
            int leverage = weightedLeverage(majorityBets, crowdSpec);
            BigDecimal margin = snapshot.crowd(market.id()).orElse(ReadViews.CrowdView.FRESH).capital()
                    .multiply(CrowdAccount.MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_UP);
            Position crowdPosition = Position.openFromMargin("crowd", window.roundId(), majorityDirection,
                    new Leverage(leverage), margin, openPrice, crowdSpec);
            crowdPreview = new Rounds.CrowdRoundPositionDto(window.roundId(), toDto(majorityDirection), leverage,
                    margin.toPlainString(), openRaw, market.rawString(crowdPosition.liquidationPrice()));
        }

        return new Rounds.RoundRunning(window.roundId(), windowDto, market.id(), market.symbol(), kindOf(market),
                market.priceDecimals(), sourceOf(market, window), openRaw, positions, crowdPreview);
    }

    /**
     * The {@code source} a round's DTO names: the exchange the round runs on (Р-76) once it is decided — for a market
     * with a backup exchange the primary until then — and the market's own source for any other market. Memory only.
     */
    private String sourceOf(Market market, RoundWindow window) {
        if (config.mode() != GameMode.LIVE) {
            return market.source();
        }
        return marketPrices.decidedVenue(market, window).orElse(market.source());
    }

    /** Open price and path so far of a running window, in engine (lot) prices; empty when the open price is not
     *  known yet. {@code mode=live}: {@link MarketPrices#soFar} (memory only, no network) with {@link
     *  BoundaryPrices#forRunning}'s open (Р-40). {@code mode=replay}: the replay series, BTC only. Public: {@code
     *  MarketViewService} reads a player's running positions off the same prices. */
    public Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs) {
        if (config.mode() == GameMode.LIVE) {
            return boundaries.forRunning(market, window, marketPrices.soFar(market, window, nowMs),
                    marketPrices.decidedVenue(market, window));
        }
        if (!Market.BTC_ID.equals(market.id())) {
            return Optional.empty();
        }
        RoundPrices prices = priceService.replayPrices();
        BigDecimal open = prices.openPrice(window);
        long pathEndMs = Math.min(nowMs, window.endMs());
        List<Candle> path = pathEndMs <= window.startMs() ? List.of()
                : prices.path(new RoundWindow(market.id(), window.interval(), window.startMs(), pathEndMs));
        if (path.isEmpty()) {
            path = List.of(prices.path(window).get(0));
        }
        Candle last = path.get(path.size() - 1);
        return Optional.of(new RoundPriceData(open, last.close(), path, last.openTimeMs()));
    }

    /** Stake-weighted average leverage of one side's bets, HALF_UP, clamped to 2..the market's cap
     *  — the crowd account's own rule (Р-14). */
    private static int weightedLeverage(List<Bet> sideBets, MarketSpec spec) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Bet bet : sideBets) {
            BigDecimal stake = bet.stake().toMoney();
            weightedSum = weightedSum.add(BigDecimal.valueOf(bet.leverage().value()).multiply(stake));
            totalStake = totalStake.add(stake);
        }
        BigDecimal average = weightedSum.divide(totalStake, MathContext.DECIMAL128);
        int rounded = average.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return spec.clampLeverage(rounded);
    }

    // ---------------------------------------------------------------------------------------
    // Bet feed (Р-62) — public, no session required, same visibility as the round itself
    // ---------------------------------------------------------------------------------------

    /** {@code sinceSeqOrNull}: {@code null}/omitted for the first page, otherwise the last call's
     *  last entry's {@code seq} to keep paging forward (see {@code BetFeedDto}'s javadoc). Reads
     *  {@link crowdvsmarket.server.domain.BetFeed}, an {@link EngineHub}-adjacent in-memory
     *  structure rebuilt by event replay exactly like {@code LedgerBook.corrections()} (see
     *  {@link #ledgerCorrections}) — same read-lock treatment for the same reason: it must not be
     *  read mid-rebuild. */
    public Rounds.BetFeedDto betFeed(String roundId, Long sinceSeqOrNull) {
        long sinceSeq = sinceSeqOrNull == null ? 0L : sinceSeqOrNull;
        RoundWindow.RoundRef ref = parseRoundId(roundId);
        roundId = ref.roundId(); // canonical, like round() and placeBet: the feed is keyed by it
        Market market = marketRegistry.require(ref.market());
        crowdvsmarket.server.domain.BetFeed.Result result = snapshot().feed().since(roundId, sinceSeq);
        List<Rounds.BetFeedEntryDto> entries = result.entries().stream()
                .map(e -> new Rounds.BetFeedEntryDto(e.seq(), e.atMs(), e.playerDisplayName(), e.isBot(),
                        e.direction(), e.stake(), e.leverage(), e.mode()))
                .toList();
        return new Rounds.BetFeedDto(roundId, market.id(), market.symbol(), entries, result.hasMore());
    }

    // ---------------------------------------------------------------------------------------
    // Liquidation preview / reference price (Р-7, Р-33)
    // ---------------------------------------------------------------------------------------

    /** The market's latest quote — the raw price and when it was read: the replay series in {@code
     *  mode=replay} (BTC only), the latest tick in {@code mode=live} — of the primary exchange while it is fresh, of
     *  the backup's when that is (the quote says which, Р-76); empty when there is none. Memory
     *  only. Public: the lobby ({@code MarketViewService}) shows the same price. */
    public Optional<PriceQuote> latestQuote(Market market, long nowMs) {
        if (config.mode() == GameMode.LIVE) {
            return marketPrices.latest(market);
        }
        if (!Market.BTC_ID.equals(market.id())) {
            return Optional.empty();
        }
        PriceService.Reference ref = priceService.replayReference(nowMs);
        return Optional.of(new PriceQuote(ref.price(), ref.asOfMs(), null, false));
    }

    /** The market's current raw price and when it was read ({@link #latestQuote}).
     *
     *  @throws ApiException {@code PRICE_UNAVAILABLE} when there is none */
    private PriceQuote currentPrice(Market market) {
        return latestQuote(market, System.currentTimeMillis()).orElseThrow(() -> ApiException.priceUnavailable(
                config.mode() == GameMode.LIVE ? "no price for market " + market.id() + " yet"
                        : "market " + market.id() + " has no price in replay mode"));
    }

    /** {@code referencePrice(marketId)} (§8): the market's current price as a raw string with its
     *  {@code priceDecimals}. */
    public Betting.MarketReferenceDto referencePrice(String marketId) {
        Market market = marketRegistry.require(marketId);
        PriceQuote quote = currentPrice(market);
        return new Betting.MarketReferenceDto(
                quote.raw().setScale(market.priceDecimals(), RoundingMode.HALF_UP).toPlainString(),
                quote.asOfMs());
    }

    /** The BTC alias of {@link #referencePrice}, kept for callers that predate markets ({@link #defaultMarket}). */
    public Betting.MarketReferenceDto btcReferencePrice() {
        return referencePrice(defaultMarket().id());
    }

    /** A number a client sent as text: plain decimal notation, at most 40 characters. */
    private static final java.util.regex.Pattern CLIENT_DECIMAL = java.util.regex.Pattern.compile("-?\\d{1,20}(\\.\\d{1,18})?");

    /**
     * Parses a number a client sent (a stake, an amount, a reference price). Only plain decimal notation is taken: {@code new BigDecimal}
     * also takes an exponent ("1e100000000"), and the first {@code setScale} of such a value computes a power of ten with that many
     * digits - minutes of CPU and hundreds of MB on the thread that asked, which for a bet is the core writer's one logic thread
     * (security review 2026-10-22). Refused here, before anything is computed; the checks after it (sign, precision) are unchanged.
     */
    static BigDecimal clientDecimal(String text, String field) {
        if (text == null || text.length() > 40 || !CLIENT_DECIMAL.matcher(text).matches()) {
            throw ApiException.badRequest("invalid " + field + ": " + shortened(text));
        }
        return new BigDecimal(text);
    }

    /** A client's text for an error message: at most 40 characters of it. */
    private static String shortened(String text) {
        return text == null || text.length() <= 40 ? String.valueOf(text) : text.substring(0, 40) + "...";
    }

    public Betting.LiquidationPreviewDto previewLiquidation(Betting.LiquidationPreviewRequest req) {
        Market market = requireMarket(req.marketId());
        MarketSpec spec = market.spec();
        Interval interval = req.interval() == null ? null : Interval.valueOf(req.interval().name());
        BigDecimal stakeMoney;
        try {
            stakeMoney = PlayAmount.fromMoney(clientDecimal(req.stake(), "stake")).toMoney();
        } catch (Exception e) {
            throw ApiException.badRequest("invalid stake: " + shortened(req.stake()));
        }
        Leverage leverage;
        try {
            if (interval == null) {
                leverage = spec.leverage(req.leverage()); // no round length named: the original single-cap preview
            } else {
                EconomyRules.requireLeverageAllowed(spec, interval, req.leverage(), PlayAmount.fromMoney(stakeMoney));
                leverage = new Leverage(req.leverage());
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidLeverage(e.getMessage());
        }
        BigDecimal rawEntry;
        if (req.referencePrice() != null) {
            rawEntry = clientDecimal(req.referencePrice(), "referencePrice");
        } else {
            rawEntry = currentPrice(market).raw();
        }
        BigDecimal entryPrice = market.toEngine(rawEntry);
        if (entryPrice.signum() <= 0) {
            throw ApiException.badRequest("referencePrice must be positive: " + req.referencePrice());
        }
        Position position = Position.openFromMargin("preview", "preview", toEngine(req.direction()), leverage,
                stakeMoney, entryPrice, interval == null ? spec : spec.forInterval(interval));
        BigDecimal liqPrice = position.liquidationPrice();
        double pct = liqPrice.subtract(entryPrice).abs()
                .divide(entryPrice, MathContext.DECIMAL128).doubleValue();
        if (interval == null) {
            return new Betting.LiquidationPreviewDto(market.rawString(entryPrice), market.rawString(liqPrice), pct);
        }
        PlayAmount stake = PlayAmount.fromMoney(stakeMoney);
        Fees fees = EconomyRules.fees(spec, interval, req.leverage(), stake);
        return new Betting.LiquidationPreviewDto(market.rawString(entryPrice), market.rawString(liqPrice), pct,
                fees.herd().toMoney().toPlainString(), fees.network().toMoney().toPlainString(),
                stake.plus(fees.total()).toMoney().toPlainString(), EconomyRules.maxLeverage(spec, interval, stake));
    }

    // ---------------------------------------------------------------------------------------
    // Player account / ledger (Р-2, Р-3, Р-6, Р-12, Р-16)
    // ---------------------------------------------------------------------------------------

    /** Предложение: {@code currentSeason} is reported as wall-clock "now"'s season, not
     *  {@code LedgerBook}'s own (unexposed) {@code PlayerAccount.currentSeason()} field — accurate
     *  as long as the player has touched the ledger at least once since the season rolled over,
     *  which every sign-in still guarantees ({@link #registerOrTouchPlayer} touches
     *  {@code ensureSeasonPack} — via a persisted event only when the touch is not already a
     *  no-op, see its javadoc for the task item 1 fix — so this is exact for any realistic
     *  session). */
    public Ledger.PlayerAccountDto account(String playerId) {
        ensurePlayerKnown(playerId);
        ReadViews.Snapshot snapshot = snapshot();
        Optional<ReadViews.PlayerView> player = snapshot.player(playerId);
        PlayAmount balance = player.map(ReadViews.PlayerView::balance).orElse(PlayAmount.ZERO);
        PlayAmount withdrawable = player.map(ReadViews.PlayerView::withdrawable).orElse(PlayAmount.ZERO);
        // the stake in play is the sum of what myPositions lists (OpenBets: one walk for both)
        return new Ledger.PlayerAccountDto(playerId, balance.toMoney().toPlainString(), Season.of(Instant.now()).id(),
                OpenBets.stake(snapshot, playerId).toMoney().toPlainString(), withdrawable.toMoney().toPlainString());
    }

    private void ensurePlayerKnown(String playerId) {
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
    }

    public Ledger.SeasonRecordDto seasonRecord(String playerId, String seasonIdOrNull) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        return snapshot().seasonRecord(playerId, season).map(GameService::toSeasonRecordDto)
                .orElseGet(() -> new Ledger.SeasonRecordDto(season.id(), "0.000", "0.000", "0.000", 0, 0, 0));
    }

    private static Ledger.SeasonRecordDto toSeasonRecordDto(SeasonRecord sr) {
        return new Ledger.SeasonRecordDto(sr.season().id(), sr.baselineBalance().toMoney().toPlainString(),
                sr.deposited().toMoney().toPlainString(), sr.withdrawn().toMoney().toPlainString(),
                sr.roundsPlayed(), sr.directionEligibleRounds(), sr.directionCorrectRounds());
    }

    private static Season parseSeason(String id) {
        try {
            String[] parts = id.split("-");
            return new Season(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            throw ApiException.badRequest("malformed season id: " + id);
        }
    }

    /**
     * A player's ledger, newest movement first. Read from the {@code ledger_entries} table (the ledger book keeps no history
     * of its own, architecture plan F3), after the movements the engine has produced so far were written to it, so a movement
     * the caller has seen the effect of is in the answer: when that write fails the table's own content is answered and the
     * failure is the operator's signal ({@link #ledgerRowsStatus}).
     */
    public Paging.Page<Ledger.LedgerEntryDto> myLedger(String playerId, Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        requireReady();
        flushLedgerRows();
        List<LedgerEntry> rows = ledgerRows.newestFirst(playerId, before, limit + 1);
        List<Ledger.LedgerEntryDto> filtered = new ArrayList<>();
        for (LedgerEntry e : rows) {
            filtered.add(new Ledger.LedgerEntryDto(e.sequence(), Enums.LedgerEntryType.valueOf(e.type().name()),
                    e.delta().toMoney().toPlainString(), e.roundId(), e.atEpochMs()));
        }
        boolean hasMore = filtered.size() > limit;
        List<Ledger.LedgerEntryDto> page = hasMore ? filtered.subList(0, limit) : filtered;
        return new Paging.Page<>(page, hasMore);
    }

    // ---------------------------------------------------------------------------------------
    // Withdraw / deposit (Р-6/Р-53) — real on-chain PLAY, against chainsvc's FIXED INTERFACE
    // (POST /v1/play/withdrawals, GET /v1/play/withdrawals/{requestId},
    // POST /v1/play/deposit-address, GET /v1/play/deposits — docs/research/server_design.md).
    //
    // History: task item 1 (BLOCKER security fix, docs/reviews/2026-09-26-independent-critique.md)
    // found both auto-confirmed immediately with zero on-chain check — requestDeposit credited any
    // self-reported amount (live-reproduced: 5,000,000 fake PLAY minted in ten seconds) and
    // requestWithdrawal falsely claimed a CAT left the game — and disabled both behind
    // herd.deposit.enabled/herd.withdrawal.enabled (GameConfig) until real chain bridging existed.
    // Р-53 (Alex, 2026-09-26, docs/product/decisions.md) is that real bridging, built against the
    // FIXED INTERFACE above: requestWithdrawal now escrows + submits to chainsvc for real, and
    // deposits are detected automatically by DepositPollerService once a player sends PLAY to their
    // own myDepositAddress() — see WithdrawalPollerService/DepositPollerService for the two
    // scheduled halves of this. The self-report requestDeposit RPC is PERMANENTLY retired (see its
    // own javadoc below) rather than re-enabled by herd.deposit.enabled, which now gates only the
    // real address/poller path — turning real deposits on must never simultaneously reopen the
    // self-report fraud vector task item 1 closed. See docs/research/server_design.md "Real PLAY
    // withdraw/deposit" for the full reasoning and the operator reversal path (reverseDeposit below)
    // for the deposits that were fraudulently confirmed before task item 1's fix.
    // ---------------------------------------------------------------------------------------

    /**
     * Validates (positive, {@code >=} {@link #MIN_WITHDRAWAL}, {@code <=} balance), escrows the
     * amount (same {@code WithdrawalRequested} event/engine effect as before Р-53 — the debit is
     * immediate and unconditional, exactly as it always was), then submits the request to chainsvc.
     * The destination address is always {@code playerId}'s own registered wallet address (Р-57:
     * player identity IS the address they signed in with) — there is no "withdraw to a different
     * address" input, so a compromised session can never redirect a withdrawal to an attacker's
     * address; contract.ts's {@code requestWithdrawal(amount)} shape (no address parameter) needs
     * no change for this.
     *
     * <p>The chainsvc submission itself is best-effort and never throws back to the caller: if it
     * fails (chainsvc down, timeout, …) the request stays {@code REQUESTED}/not-yet-submitted, and
     * {@link crowdvsmarket.server.scheduler.WithdrawalPollerService} retries submission on its next
     * tick (the FIXED INTERFACE documents the submit endpoint as "idempotent by requestId" for
     * exactly this reason — a retry after an actually-successful-but-unobserved attempt is always
     * safe). The same poller also polls {@code GET /v1/play/withdrawals/{requestId}} for every
     * already-submitted, not-yet-terminal request and drives it to
     * {@link #confirmWithdrawalOnChain}/{@link #failWithdrawalOnChain} — this method itself never
     * blocks on, or even waits for, chain confirmation (task: "never block the game on chain
     * confirmation", the same discipline {@code OraclePublisherService} already follows).
     */
    public Ledger.WithdrawalRequestDto requestWithdrawal(String playerId, String amount, long nowMs) {
        requireReady();
        // Р-106: an API bot's PLAY stays in the game - the starter packs it was given must not leave the treasury through it
        if (players.byId(playerId).map(PlayerDirectory.Row::isApiBot).orElse(false)) {
            throw ApiException.botWithdrawalNotAllowed();
        }
        if (!config.withdrawalEnabled()) {
            throw ApiException.withdrawalUnavailable();
        }
        PlayAmount amt;
        try {
            amt = PlayAmount.fromMoney(clientDecimal(amount, "amount"));
        } catch (Exception e) {
            throw ApiException.badRequest("invalid amount: " + shortened(amount));
        }
        // Wave-2 CRITICAL security fix: requestWithdrawal("-5000.000") used to mint PLAY — a
        // negative amount is a debit that LedgerBook.requestWithdrawal's debit(amount) negates
        // before applying, so it silently became a credit once auto-confirmed. This is the actual
        // enforcement point (the one place a brand-new withdrawal command is decided, before it is
        // ever turned into a persisted GameEvent) — see LedgerBook.requestWithdrawal's own comment
        // for why the engine's shared live/replay method deliberately does not duplicate this check.
        if (!amt.isPositive()) {
            throw ApiException.invalidAmount("withdrawal amount must be positive: " + amount);
        }
        // Р-53: below-minimum is a NEW application-layer guard (like the positive-amount check
        // above), not an engine-level one — see LedgerBook.requestWithdrawal's own comment on why a
        // brand-new command's validation belongs here, never inside the method replay also calls.
        if (amt.compareTo(MIN_WITHDRAWAL) < 0) {
            throw ApiException.withdrawalBelowMinimum(
                    "withdrawal " + amt + " is below the minimum " + MIN_WITHDRAWAL);
        }
        withdrawalSubmitter.ensureNamespace(); // E1: before this database's first withdrawal row exists (§4 п.16); not in the writer's turn
        Requested requested = write("withdrawal", nowMs, ctx -> {
            PlayAmount balance = hub.ledger().balance(playerId);
            if (amt.compareTo(balance) > 0) {
                throw ApiException.withdrawalExceedsBalance("withdrawal " + amt + " exceeds balance " + balance);
            }
            // Economy stage B (plan B2): the starter packs are play money and cannot leave the game; only what is above them
            PlayAmount withdrawable = hub.ledger().withdrawable(playerId);
            if (amt.compareTo(withdrawable) > 0) {
                throw ApiException.withdrawalExceedsBalance("withdrawal " + amt + " exceeds the amount you can withdraw, "
                        + withdrawable + ": starter packs (" + hub.ledger().packCredited(playerId) + " credited to you so far) "
                        + "stay in the game, only winnings and deposits above them can be withdrawn");
            }
            String toAddress = players.byId(playerId)
                    .orElseThrow(() -> ApiException.notFound("unknown player " + playerId))
                    .walletAddress();
            GameEvent.WithdrawalRequested event = new GameEvent.WithdrawalRequested(playerId, amt.mojos(), nowMs);
            // LedgerBook assigns its own "w-N" id inside requestWithdrawal (a single counter shared
            // with deposits' "d-N") — we reuse THAT id as our own external requestId rather than
            // minting a separate one, precisely so replaying this same event later (same call, same
            // position in the event order) reproduces the identical id deterministically; see
            // GameEvent.WithdrawalRequested's javadoc and EventApplier's WithdrawalRequested case.
            String requestId = (String) ctx.record(event, nowMs);
            ctx.afterCommit(() -> publisher.changed(crowdvsmarket.server.push.PushTopics.player(playerId)));
            return new Requested(requestId, toAddress);
        });
        // Р-107: the row is written here, by the caller, once the request's event is durable - never in the writer's turn (a stop in between
        // leaves the request escrowed without its row, which the rebuild writes from the engine, SidecarReconciler.withdrawalRows)
        withdrawalDeposits.ensureWithdrawal(requested.requestId(), playerId, amt.mojos(), requested.toAddress(), nowMs);
        withdrawalSubmitter.submit(requested.requestId(), requested.toAddress(), amt.mojos(), nowMs);
        return withdrawalDeposits.findWithdrawal(requested.requestId()).orElseThrow();
    }

    private record Requested(String requestId, String toAddress) {
    }

    /** Р-53: {@code WithdrawalPollerService}'s entry point once {@code GET
     *  /v1/play/withdrawals/{requestId}} reports {@code state="confirmed"} — persists {@code
     *  WithdrawalConfirmedOnChain} (engine effect: closes out the request, same as the old {@code
     *  WithdrawalConfirmed} — the escrow debit already happened at request time) and makes {@code
     *  coinId}/{@code height} public in the player's own withdrawal view (task requirement). */
    public void confirmWithdrawalOnChain(String requestId, String coinId, long height, long nowMs) {
        boolean confirmed = write("withdrawal confirmed", nowMs, ctx -> {
            Optional<WithdrawalRequest> current = hub.ledger().withdrawal(requestId);
            if (current.isPresent() && current.get().state() == WithdrawalState.CONFIRMED) {
                return true; // recorded by an earlier call (or by the journal before this one): only its row may still be behind
            }
            if (!withdrawalAwaitingChain(requestId)) {
                return false;
            }
            GameEvent.WithdrawalConfirmedOnChain event =
                    new GameEvent.WithdrawalConfirmedOnChain(requestId, coinId, height, nowMs);
            ctx.record(event, nowMs);
            return true;
        });
        if (confirmed) {
            // Р-107: the row follows the durable event, written by the caller (idempotent; a call that fails here is offered again by the
            // poller and finds the event recorded already)
            withdrawalDeposits.markWithdrawalConfirmedOnChain(requestId, coinId, height, nowMs);
        }
    }

    /** Р-53: {@code WithdrawalPollerService}'s entry point once {@code GET
     *  /v1/play/withdrawals/{requestId}} reports {@code state="failed"} — reuses the pre-existing
     *  {@code WithdrawalFailed} event (its {@code LedgerBook.failWithdrawal} effect, crediting back
     *  the escrowed amount, needs no {@code coinId}/{@code height}), recording {@code reason} only
     *  in the read model. */
    public void failWithdrawalOnChain(String requestId, String reason, long nowMs) {
        boolean failed = write("withdrawal failed", nowMs, ctx -> {
            Optional<WithdrawalRequest> current = hub.ledger().withdrawal(requestId);
            if (current.isPresent() && current.get().state() == WithdrawalState.FAILED) {
                return true; // recorded already: only its row may still be behind
            }
            if (!withdrawalAwaitingChain(requestId)) {
                return false;
            }
            GameEvent.WithdrawalFailed event = new GameEvent.WithdrawalFailed(requestId, nowMs);
            ctx.record(event, nowMs);
            hub.ledger().withdrawal(requestId).ifPresent(w -> ctx.afterCommit(
                    () -> publisher.changed(crowdvsmarket.server.push.PushTopics.player(w.playerId())))); // the escrow is credited back
            return true;
        });
        if (failed) {
            withdrawalDeposits.markWithdrawalFailed(requestId, reason, nowMs); // as in confirmWithdrawalOnChain
        }
    }

    /** Idempotence guard for the two on-chain entry points above, checked under the write lock
     *  before anything is persisted: two poll passes (overlapping ticks, or a tick and a manual
     *  poll) can both read the same row as pending before either takes the lock. Without it the
     *  second pass persisted a second terminal event whose apply then threw ("already
     *  CONFIRMED") — and every later event-log replay threw on it too. Deposits get the same
     *  guarantee from {@code depositConfirmations.tryReserve}. */
    private boolean withdrawalAwaitingChain(String requestId) {
        Optional<WithdrawalRequest> current = hub.ledger().withdrawal(requestId);
        if (current.isEmpty()) {
            LOG.warnf("chain status for unknown withdrawal %s ignored", requestId);
            return false;
        }
        if (current.get().state() != WithdrawalState.REQUESTED) {
            LOG.debugf("withdrawal %s already %s — duplicate chain status ignored", requestId, current.get().state());
            return false;
        }
        return true;
    }

    /** Предложение: {@code requestId} is otherwise a small guessable sequential counter
     *  ({@code LedgerBook}'s own {@code "w-N"}, see the id-reuse note on the event) — restricting
     *  the lookup to the requesting player, even though contract.ts does not name this method
     *  {@code myWithdrawal}, avoids leaking every player's withdrawal history by counting up. */
    public Ledger.WithdrawalRequestDto withdrawal(String requestingPlayerId, String requestId) {
        Ledger.WithdrawalRequestDto dto = withdrawalDeposits.findWithdrawal(requestId)
                .orElseThrow(() -> ApiException.notFound("unknown withdrawal request " + requestId));
        if (!dto.playerId().equals(requestingPlayerId)) {
            throw ApiException.notFound("unknown withdrawal request " + requestId);
        }
        return dto;
    }

    /**
     * Р-53: PERMANENTLY retired — see the class-level "Withdraw / deposit" note above for why
     * {@code herd.deposit.enabled} must never re-enable this specific RPC. Self-reporting "I sent
     * PLAY, please credit me" with zero on-chain proof was exactly task item 1's fraud vector
     * (live-reproduced: 5,000,000 fake PLAY minted in ten seconds via this method); real deposits
     * are now detected automatically by {@link crowdvsmarket.server.scheduler.DepositPollerService}
     * once a player sends PLAY to their own {@link #myDepositAddress}. contract.ts still declares
     * {@code requestDeposit(amount)} and {@code web/} still calls it (neither touched by this
     * change) — every call now simply gets the same {@code DEPOSIT_VERIFICATION_UNAVAILABLE}/503 a
     * disabled server always gave, unconditionally, explaining the real path to use instead.
     */
    public Ledger.DepositRequestDto requestDeposit(String playerId, String amount, long nowMs) {
        throw ApiException.depositVerificationUnavailable();
    }

    /** Same ownership note as {@link #withdrawal}. Still live (unlike {@link #requestDeposit}) —
     *  needed to look up the pre-Р-53 self-reported rows an operator may still need to review/
     *  reverse ({@link #reverseDeposit}). */
    public Ledger.DepositRequestDto deposit(String requestingPlayerId, String requestId) {
        Ledger.DepositRequestDto dto = withdrawalDeposits.findDeposit(requestId)
                .orElseThrow(() -> ApiException.notFound("unknown deposit request " + requestId));
        if (!dto.playerId().equals(requestingPlayerId)) {
            throw ApiException.notFound("unknown deposit request " + requestId);
        }
        return dto;
    }

    /**
     * Р-53: {@code myDepositAddress()} — this player's chainsvc-derived PLAY CAT deposit address,
     * cached ({@link crowdvsmarket.server.persistence.PlayerDepositAddressRepository}) after the
     * first successful {@code POST /v1/play/deposit-address} call, and never re-derived after that
     * (the address is deterministic given the player's stable {@code playerIndex} — see
     * {@code V4__player_index.sql}). Gated by {@code herd.deposit.enabled} like the rest of the
     * real deposit path (the deposit poller only runs while it is on, so handing out an address
     * nothing will ever watch would be misleading).
     *
     * <p>Two first calls at once both find nothing cached and both ask chainsvc (they get the same
     * address); the row is written with {@code INSERT … ON CONFLICT DO NOTHING} and read back, so both
     * return the stored address and the second is not an error ({@link
     * crowdvsmarket.server.persistence.PlayerDepositAddressRepository#insertOnce}).
     */
    public PlayChain.DepositAddressDto myDepositAddress(String playerId) {
        if (!config.depositEnabled()) {
            throw ApiException.depositVerificationUnavailable();
        }
        Optional<crowdvsmarket.server.persistence.PlayerDepositAddressRepository.Row> cached =
                depositAddresses.find(playerId);
        if (cached.isPresent()) {
            return new PlayChain.DepositAddressDto(cached.get().address());
        }
        int playerIndex = players.byId(playerId)
                .orElseThrow(() -> ApiException.notFound("unknown player " + playerId))
                .playerIndex();
        ChainSvcClient.PlayDepositAddress remote = chainSvc.playDepositAddress(playerIndex);
        if (remote == null) {
            throw ApiException.chainServiceUnavailable();
        }
        long now = System.currentTimeMillis();
        crowdvsmarket.server.persistence.PlayerDepositAddressRepository.Row stored = depositAddresses.insertOnce(
                playerId, remote.address(), remote.innerPuzzleHash(), remote.catPuzzleHash(), now);
        return new PlayChain.DepositAddressDto(stored.address());
    }

    /**
     * Р-53: {@link crowdvsmarket.server.scheduler.DepositPollerService}'s entry point for one chainsvc-reported
     * deposit coin. Under the engine's write lock (readiness checked with it held) the coin is reserved in {@code
     * deposit_confirmations} and the {@code DepositConfirmedOnChain} event appended in ONE transaction; the balance
     * is credited once it has committed. A refused call (503) changed nothing, so the poller offers the coin
     * again; a coin is never credited twice ({@code coin_id} is the primary key); a crash before the apply is
     * healed by the next rebuild (§4 п.18).
     *
     * @return {@code true} if this call is the one that credited the deposit; {@code false} if
     *         {@code coinId} was already credited by an earlier call (not an error — chainsvc can
     *         legitimately re-report the same coin across overlapping {@code sinceHeight} windows)
     */
    public boolean creditOnChainDeposit(String playerId, int playerIndex, PlayAmount amount, String coinId,
                                         long height, long nowMs) {
        requireReady();
        GameEvent.DepositConfirmedOnChain event =
                new GameEvent.DepositConfirmedOnChain(playerId, playerIndex, amount.mojos(), coinId, height, nowMs);
        // Р-107: the reservation (Postgres) is committed first, here, before the command - the writer's turn never touches the database -
        // and then the event is journaled by an ordinary command: a crash between the two leaves a reserved coin whose event is missing,
        // which the rebuild finds and appends (SidecarReconciler.depositEvents); the other order could credit a coin twice
        if (!depositConfirmations.tryReserve(coinId, playerId, playerIndex, amount.mojos(), height, nowMs)) {
            return false;
        }
        try {
            write("deposit " + coinId, nowMs, ctx -> {
                ctx.record(event, nowMs);
                ctx.afterCommit(() -> publisher.changed(crowdvsmarket.server.push.PushTopics.player(playerId))); // the deposit is committed
                return null;
            });
        } catch (ApiException e) {
            if (e.code() == Enums.ApiErrorCode.ENGINE_NOT_READY && e.getCause() == null) {
                releaseReservation(coinId); // the command was refused and never ran: the coin is offered again when the engine is ready
            }
            // a batch that did not commit (the cause is attached) has had the engine rebuilt behind it, and that rebuild appends the
            // event of the reserved coin
            throw e;
        }
        return true;
    }

    /** Takes the reservation of {@code coinId} back when its event is not in the journal - decided holding the writer's gate, so that no
     *  rebuild is half way through appending it (a reserved coin without an event is the rebuild's to credit). */
    private void releaseReservation(String coinId) {
        enterGate();
        try {
            boolean credited = eventStore.loadOfTypes(List.of("DepositConfirmedOnChain")).stream()
                    .anyMatch(e -> ((GameEvent.DepositConfirmedOnChain) e).coinId().equals(coinId));
            if (!credited) {
                depositConfirmations.release(coinId);
            }
        } catch (RuntimeException e) {
            LOG.warnf(e, "the reservation of deposit coin %s could not be taken back - the next rebuild credits it", coinId);
        } finally {
            writerGate.unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Operator ledger corrections (wave-2 security fix, Р-32/Р-43 pattern) — AdminResource only.
    // ---------------------------------------------------------------------------------------

    /** {@code AdminResource}'s entry point for correcting a withdrawal request's already-recorded
     *  (bogus) effect — e.g. the {@code requestWithdrawal("-5000.000")} exploit — without altering
     *  or deleting the original event. Reads {@code requestId}'s CURRENT live amount/timestamp
     *  ({@code hub.ledger()}, never restarted since the original request, so it is always present)
     *  and captures both directly into the new event, so the correction's own later replay never
     *  needs this lookup again — see {@code LedgerBook#reverseWithdrawalEffect}'s javadoc. */
    public Ledger.LedgerCorrectionDto reverseWithdrawal(String requestId, String reason, long nowMs) {
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isBlank()) {
            throw ApiException.badRequest("correction reason must not be blank");
        }
        return write("reverse withdrawal", nowMs, ctx -> {
            LedgerBook ledger = hub.ledger();
            crowdvsmarket.engine.ledger.WithdrawalRequest request = ledger.withdrawal(requestId)
                    .orElseThrow(() -> ApiException.notFound("unknown withdrawal request " + requestId));
            boolean alreadyReversed = ledger.corrections().stream()
                    .anyMatch(c -> requestId.equals(c.relatedRequestId()));
            if (alreadyReversed) {
                throw ApiException.badRequest("withdrawal " + requestId + " was already reversed");
            }
            Season originalSeason = Season.of(Instant.ofEpochMilli(request.requestedAtEpochMs()));

            GameEvent.WithdrawalReversed event = new GameEvent.WithdrawalReversed(request.playerId(),
                    request.amount().mojos(), originalSeason.id(), trimmedReason, requestId, nowMs);
            crowdvsmarket.engine.ledger.LedgerCorrection correction =
                    (crowdvsmarket.engine.ledger.LedgerCorrection) ctx.record(event, nowMs);
            ctx.afterCommit(() -> publisher.changed(crowdvsmarket.server.push.PushTopics.player(request.playerId())));
            return toLedgerCorrectionDto(correction);
        });
    }

    /** Task item 1 (BLOCKER security fix): {@code AdminResource}'s entry point for correcting a
     *  deposit request's already-recorded (fraudulent) effect — e.g. the self-reported deposits
     *  {@code d-2}/{@code d-4}/{@code d-7}/{@code d-10}, auto-confirmed with zero on-chain
     *  verification before {@link #requestDeposit} started rejecting new deposits outright. Exact
     *  mirror of {@link #reverseWithdrawal} — see that method's javadoc and
     *  {@code LedgerBook#reverseDepositEffect}'s. */
    public Ledger.LedgerCorrectionDto reverseDeposit(String requestId, String reason, long nowMs) {
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isBlank()) {
            throw ApiException.badRequest("correction reason must not be blank");
        }
        return write("reverse deposit", nowMs, ctx -> {
            LedgerBook ledger = hub.ledger();
            crowdvsmarket.engine.ledger.DepositRequest request = ledger.deposit(requestId)
                    .orElseThrow(() -> ApiException.notFound("unknown deposit request " + requestId));
            boolean alreadyReversed = ledger.corrections().stream()
                    .anyMatch(c -> requestId.equals(c.relatedRequestId()));
            if (alreadyReversed) {
                throw ApiException.badRequest("deposit " + requestId + " was already reversed");
            }
            Season originalSeason = Season.of(Instant.ofEpochMilli(request.requestedAtEpochMs()));

            GameEvent.DepositReversed event = new GameEvent.DepositReversed(request.playerId(),
                    request.amount().mojos(), originalSeason.id(), trimmedReason, requestId, nowMs);
            crowdvsmarket.engine.ledger.LedgerCorrection correction =
                    (crowdvsmarket.engine.ledger.LedgerCorrection) ctx.record(event, nowMs);
            ctx.afterCommit(() -> publisher.changed(crowdvsmarket.server.push.PushTopics.player(request.playerId())));
            return toLedgerCorrectionDto(correction);
        });
    }

    private static Ledger.LedgerCorrectionDto toLedgerCorrectionDto(crowdvsmarket.engine.ledger.LedgerCorrection c) {
        return new Ledger.LedgerCorrectionDto(c.correctionId(), c.playerId(), c.relatedRequestId(),
                c.delta().toMoney().toPlainString(), c.reason(), c.atEpochMs());
    }

    /** The public audit log every operator ledger correction is listed in (wave-2 security fix,
     *  Р-32/Р-43 pattern: same "public, with reason" requirement as {@link #cancellations}) — no
     *  session required, same as {@code cancellations}. In-memory only (like {@link #myLedger}'s
     *  {@code LedgerEntry} log and {@link #crowdAccount}'s history): {@code LedgerCorrection}s are
     *  rebuilt by the normal event replay, same as everything else {@link EngineHub} holds, so no
     *  separate read-model table is needed. */
    public Paging.Page<Ledger.LedgerCorrectionDto> ledgerCorrections(Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        List<crowdvsmarket.engine.ledger.LedgerCorrection> all = snapshot().corrections();
        List<Ledger.LedgerCorrectionDto> filtered = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            crowdvsmarket.engine.ledger.LedgerCorrection c = all.get(i);
            if (c.atEpochMs() >= before) {
                continue;
            }
            filtered.add(toLedgerCorrectionDto(c));
            if (filtered.size() > limit) {
                break;
            }
        }
        boolean hasMore = filtered.size() > limit;
        List<Ledger.LedgerCorrectionDto> page = hasMore ? filtered.subList(0, limit) : filtered;
        return new Paging.Page<>(page, hasMore);
    }

    // ---------------------------------------------------------------------------------------
    // Crowd account (Р-5, Р-14, Р-17, Р-25, Р-26)
    // ---------------------------------------------------------------------------------------

    /** One market's crowd account (Т-6): its own capital, its own chart, its own "hold the asset" and
     *  "anti-crowd" lines. {@code null}: BTC. */
    public Crowd.CrowdAccountDto crowdAccount(String marketIdOrNull) {
        return crowdAccount(marketIdOrNull, snapshot());
    }

    private Crowd.CrowdAccountDto crowdAccount(String marketIdOrNull, ReadViews.Snapshot snapshot) {
        Market market = requireMarket(marketIdOrNull);
        ReadViews.CrowdView crowd = snapshot.crowd(market.id()).orElse(ReadViews.CrowdView.FRESH);
        List<CrowdAccount.RoundResult> history = crowd.history();
        List<BigDecimal> hold = crowd.hold();
        List<BigDecimal> antiCrowd = crowd.antiCrowd();
        List<Crowd.CrowdHistoryPointDto> points = new ArrayList<>();
        String previousSeason = null;
        for (int i = 0; i < history.size(); i++) {
            CrowdAccount.RoundResult r = history.get(i);
            RoundWindow w = windowFor(r.roundId());
            long atMs = w.endMs() - 1;
            String thisSeason = Season.of(Instant.ofEpochMilli(atMs)).id();
            String boundary = (i > 0 && !thisSeason.equals(previousSeason)) ? thisSeason : null;
            previousSeason = thisSeason;
            points.add(new Crowd.CrowdHistoryPointDto(r.roundId(), atMs, boundary, r.capitalAfter().toPlainString(),
                    hold.get(i).toPlainString(), antiCrowd.get(i).toPlainString()));
        }
        List<Crowd.SeasonReturnDto> seasonReturns = seasonReturns(points);
        return new Crowd.CrowdAccountDto(market.id(), market.symbol(), crowd.capital().toPlainString(), points, seasonReturns);
    }

    /** Р-25: "рядом доходность за каждый сезон" — one entry per season the continuous crowd curve
     *  has ever passed through; a season's return is measured against the curve's own capital at
     *  the moment that season began (the previous season's last point, or
     *  {@link CrowdAccount#STARTING_CAPITAL} for the very first season — the curve is never reset,
     *  per Р-25, so there is no per-season "starting capital" baked into the engine itself). */
    private static List<Crowd.SeasonReturnDto> seasonReturns(List<Crowd.CrowdHistoryPointDto> points) {
        List<Crowd.SeasonReturnDto> out = new ArrayList<>();
        if (points.isEmpty()) {
            return out;
        }
        BigDecimal baseline = CrowdAccount.STARTING_CAPITAL;
        String currentSeason = Season.of(Instant.ofEpochMilli(points.get(0).atMs())).id();
        BigDecimal lastCapitalInSeason = baseline;
        for (Crowd.CrowdHistoryPointDto p : points) {
            String season = Season.of(Instant.ofEpochMilli(p.atMs())).id();
            if (!season.equals(currentSeason)) {
                out.add(seasonReturn(currentSeason, baseline, lastCapitalInSeason));
                baseline = lastCapitalInSeason;
                currentSeason = season;
            }
            lastCapitalInSeason = new BigDecimal(p.crowdCapital());
        }
        out.add(seasonReturn(currentSeason, baseline, lastCapitalInSeason));
        return out;
    }

    private static Crowd.SeasonReturnDto seasonReturn(String season, BigDecimal startCapital, BigDecimal endCapital) {
        double pct = endCapital.subtract(startCapital).divide(startCapital, MathContext.DECIMAL128).doubleValue();
        return new Crowd.SeasonReturnDto(season, pct);
    }

    // ---------------------------------------------------------------------------------------
    // Ratings (Р-9, Р-18, Р-28, Р-30)
    // ---------------------------------------------------------------------------------------

    /** The people's boards (and the house's bots, as before): the API bots of Р-106 are not in them, they have {@link #botRatings}. */
    public Ratings.RatingsDto ratings(String seasonIdOrNull) {
        return ratings(seasonIdOrNull, false);
    }

    /** Р-106: the boards of the API bots only - the same season, the same threshold of rounds and the same two rankings (Р-28). */
    public Ratings.RatingsDto botRatings(String seasonIdOrNull) {
        return ratings(seasonIdOrNull, true);
    }

    private Ratings.RatingsDto ratings(String seasonIdOrNull, boolean apiBots) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        return ratings(season, apiBots, snapshot(), Integer.MAX_VALUE);
    }

    /**
     * The boards of {@code season} from the committed views: the rows of the season's sorted boards that belong to this board (the
     * people and the house's bots, or the API bots), the first {@code limit} of each with their ranks.
     */
    private Ratings.RatingsDto ratings(Season season, boolean apiBots, ReadViews.Snapshot snapshot, int limit) {
        List<Ratings.RichEntryDto> richDtos = new ArrayList<>();
        for (SeasonBoards.RichRow row : snapshot.richBoard(season)) {
            if (richDtos.size() >= limit) {
                break;
            }
            RatingBook.RichEntry e = row.entry();
            if (onBoard(e.playerId(), apiBots)) {
                richDtos.add(new Ratings.RichEntryDto(richDtos.size() + 1, summary(e.playerId()), e.growth().toMoney().toPlainString(),
                        e.roundsPlayed()));
            }
        }
        List<Ratings.ProphetEntryDto> prophetDtos = new ArrayList<>();
        for (SeasonBoards.ProphetRow row : snapshot.prophetBoard(season)) {
            if (prophetDtos.size() >= limit) {
                break;
            }
            RatingBook.ProphetEntry e = row.entry();
            if (onBoard(e.playerId(), apiBots)) {
                prophetDtos.add(new Ratings.ProphetEntryDto(prophetDtos.size() + 1, summary(e.playerId()), e.accuracy().doubleValue(),
                        e.eligibleRounds(), e.roundsPlayed()));
            }
        }
        return new Ratings.RatingsDto(season.id(), RatingBook.DEFAULT_ROUNDS_THRESHOLD, richDtos, prophetDtos);
    }

    /** Whether the player belongs to the people's boards ({@code apiBots} false) or to the API bots'. */
    private boolean onBoard(String playerId, boolean apiBots) {
        return players.byId(playerId).map(PlayerDirectory.Row::isApiBot).orElse(false) == apiBots;
    }

    /** The people's "This week" boards (the house's bots with them, as in {@link #ratings}); the API bots have {@link #botWeeklyRatings}. */
    public Ratings.WeeklyRatingsDto weeklyRatings(long nowMs) {
        return weeklyRatings(nowMs, false);
    }

    /** The API bots' "This week" boards: same window, same minimum of rounds, same rules. */
    public Ratings.WeeklyRatingsDto botWeeklyRatings(long nowMs) {
        return weeklyRatings(nowMs, true);
    }

    private Ratings.WeeklyRatingsDto weeklyRatings(long nowMs, boolean apiBots) {
        awaitStored();
        long weekStart = crowdvsmarket.server.domain.WeeklyBoards.weekStartMs(nowMs);
        List<crowdvsmarket.server.domain.WeeklyBoards.Totals> totals = new ArrayList<>(roundResults.weeklyTotals(weekStart, nowMs));
        ReadViews.Snapshot snapshot = snapshot();
        // a retired bot is in no list (as in the season's); the API bots have a board of their own
        totals.removeIf(t -> snapshot.isRetired(t.playerId()) || !onBoard(t.playerId(), apiBots));
        int threshold = RatingBook.DEFAULT_ROUNDS_THRESHOLD;
        List<Ratings.RichEntryDto> richDtos = new ArrayList<>();
        List<crowdvsmarket.server.domain.WeeklyBoards.Rich> rich = crowdvsmarket.server.domain.WeeklyBoards.rich(totals, threshold);
        for (int i = 0; i < rich.size(); i++) {
            var e = rich.get(i);
            richDtos.add(new Ratings.RichEntryDto(i + 1, summary(e.playerId()),
                    BigDecimal.valueOf(e.netMojos(), 3).toPlainString(), e.rounds()));
        }
        List<Ratings.ProphetEntryDto> prophetDtos = new ArrayList<>();
        List<crowdvsmarket.server.domain.WeeklyBoards.Prophet> prophets = crowdvsmarket.server.domain.WeeklyBoards.prophets(totals, threshold);
        for (int i = 0; i < prophets.size(); i++) {
            var e = prophets.get(i);
            prophetDtos.add(new Ratings.ProphetEntryDto(i + 1, summary(e.playerId()), e.accuracy(), e.eligible(), e.rounds()));
        }
        return new Ratings.WeeklyRatingsDto(weekStart, threshold, richDtos, prophetDtos);
    }

    // ---------------------------------------------------------------------------------------
    // Profiles (Р-15, Р-29)
    // ---------------------------------------------------------------------------------------

    private static final int PROFILE_HISTORY_LIMIT = 100; // предложение — contract.ts leaves this unpaginated/uncapped

    /**
     * The profile as {@code viewerId} (null: not signed in) may see it (Р-90): the player's own profile is complete; anyone else's shows only
     * the wins (net above the stake, not liquidated) - never a loss, a liquidation or a flat round. A retired bot is not found at all.
     */
    public Identity.PublicProfileDto publicProfile(String playerId, String viewerId) {
        Identity.PublicProfileDto full = publicProfile(playerId);
        if (playerId.equals(viewerId)) {
            return full;
        }
        if (players.byId(playerId).map(PlayerDirectory.Row::isRetired).orElse(false)) {
            throw ApiException.notFound("unknown player " + playerId);
        }
        List<Identity.ProfileRoundEntryDto> wins = full.history().stream()
                .filter(h -> !h.liquidated() && new BigDecimal(h.payout()).compareTo(new BigDecimal(h.stake())) > 0)
                .toList();
        return new Identity.PublicProfileDto(full.player(), full.seasonRecord(), wins, full.streak(), full.playDays());
    }

    public Identity.PublicProfileDto publicProfile(String playerId) {
        ensurePlayerKnown(playerId);
        awaitStored();
        Identity.PlayerSummaryDto player = summary(playerId);
        Ledger.SeasonRecordDto seasonRecord = snapshot().seasonRecord(playerId, Season.of(Instant.now()))
                .map(GameService::toSeasonRecordDto).orElse(null);
        List<Identity.ProfileRoundEntryDto> history = roundResults.playerHistory(playerId, PROFILE_HISTORY_LIMIT);
        // over every settled round of the player, not just the page above (Р-71)
        WinStreak.Result streak = roundResults.playerStreak(playerId);
        crowdvsmarket.server.domain.PlayDays.Result playDays = crowdvsmarket.server.domain.PlayDays.of(
                roundResults.playDays(playerId), crowdvsmarket.server.domain.PlayDays.dayOf(System.currentTimeMillis()));
        return new Identity.PublicProfileDto(player, seasonRecord, history,
                new Identity.StreakDto(streak.current(), streak.best()),
                new Identity.PlayDaysDto(playDays.current(), playDays.best()));
    }

    // ---------------------------------------------------------------------------------------
    // "While you were away" (Р-62 cross-check): a player offline at settlement never otherwise
    // sees the result moment (docs/product/design/result-moment.md) — these two let the app catch
    // up on return. Both are plain reads/writes of the player_round_history read model (like
    // {@link #cancellations}), not {@link EngineHub} state, so neither needs engineLock.
    // ---------------------------------------------------------------------------------------

    /** предложение — no source fixes a number: bounds a first-load backlog after a long absence
     *  (a player who never opens the app for weeks should not suddenly get flooded with every
     *  result moment at once); newest-first ordering (see {@code RoundResultRepository
     *  #unseenResults}'s javadoc) means anything beyond this limit is simply older and shows up on
     *  a later call instead, once these are acked — never lost. */
    private static final int UNSEEN_RESULTS_LIMIT = 20;

    public List<Identity.ProfileRoundEntryDto> myUnseenResults(String playerId) {
        awaitStored();
        return roundResults.unseenResults(playerId, UNSEEN_RESULTS_LIMIT);
    }

    /** Idempotent (see {@code RoundResultRepository.ackResults}'s javadoc) — a client can safely
     *  re-ack ids it already acked, or ids from a stale local cache. */
    public void ackResults(String playerId, List<String> roundIds, long nowMs) {
        roundResults.ackResults(playerId, roundIds == null ? List.of() : roundIds, nowMs);
    }

    // ---------------------------------------------------------------------------------------
    // Cancellations log (Р-23, Р-32)
    // ---------------------------------------------------------------------------------------

    public Paging.Page<Rounds.CancellationDto> cancellations(Paging.PageParams params) {
        return roundResults.listCancellations(params);
    }

    // ---------------------------------------------------------------------------------------
    // Economy stage B (Р-92, docs/product/economy_implementation_plan.md B1-B7)
    // ---------------------------------------------------------------------------------------

    /**
     * B1: writes the {@link GameEvent.EconomyCutover} event once, when the log has none and {@code herd.economy.treasury-opening-play}
     * is set (greater than 0). The event stores the opening total, so a replay opens TREASURY with the same number. On the stand's
     * first start with this version: TREASURY = 99 998 100 PLAY minus everything the players hold (balances and open stakes) at that
     * moment, HERD = 0; every bet placed before keeps its original rules.
     *
     * @return whether the event was written now
     * @throws IllegalStateException when the players hold more than the opening total (nothing is written)
     */
    public boolean ensureEconomyCutover(long nowMs) {
        long openingPlay = config.economyTreasuryOpeningPlay();
        if (openingPlay <= 0) {
            return false;
        }
        return write("economy cutover", nowMs, ctx -> {
            LedgerBook ledger = hub.ledger();
            if (ledger.economyActive()) {
                return false;
            }
            PlayAmount opening = PlayAmount.ofWholePlay(openingPlay);
            PlayAmount held = ledger.playersTotal().plus(ledger.openStakes());
            if (held.compareTo(opening) > 0) {
                throw new IllegalStateException("the economy cutover is refused: the players hold " + held
                        + ", more than the opening total " + opening + " (herd.economy.treasury-opening-play)");
            }
            GameEvent.EconomyCutover event = new GameEvent.EconomyCutover(opening.mojos(), nowMs);
            ctx.record(event, nowMs);
            LOG.infof("economy cutover: opening total %s, players hold %s (balances %s, open stakes %s) -> TREASURY %s, HERD 0",
                    opening, held, ledger.playersTotal(), ledger.openStakes(), ledger.treasury());
            return true;
        });
    }

    /** Whether the economy has been cut over (the TREASURY and HERD accounts exist). */
    public boolean economyActive() {
        return snapshot().economy().economyActive();
    }

    /** B6: whether TREASURY is below 90% of its 30-day maximum now. Said in the log when it changes. */
    public boolean circuitBreakerOpen(long nowMs) {
        EconomyFigures economy = snapshot().economy();
        boolean open = economy.circuitBreakerOpen(Instant.ofEpochMilli(nowMs));
        noteBreaker(open, economy.treasury(), () -> economy.treasuryMax30d(Instant.ofEpochMilli(nowMs)));
        return open;
    }

    /** Logs the circuit breaker opening and closing, once each (the numbers stay in the log, the status shows the flag only). */
    private void noteBreaker(boolean open, PlayAmount treasury, java.util.function.Supplier<PlayAmount> max30d) {
        if (open == breakerWasOpen) {
            return;
        }
        breakerWasOpen = open;
        if (open) {
            LOG.warnf("CIRCUIT BREAKER OPEN: TREASURY %s is below 90%% of its 30-day maximum %s - new bets take at most "
                    + "a quarter of the usual leverage cap until it recovers", treasury, max30d.get());
        } else {
            LOG.infof("circuit breaker closed: TREASURY %s is back above 90%% of its 30-day maximum %s",
                    treasury, max30d.get());
        }
    }

    /**
     * B5: the bet must fit under the treasury limits - the open notional of its side of its round of its market, and (crypto) the
     * bucket of all crypto markets. Nothing is checked before the cutover. The rejection names the room that is left.
     */
    private void requireExposureRoom(Market market, Interval interval, RoundWindow window, String playerId,
                                     crowdvsmarket.engine.round.Direction direction, int leverage, PlayAmount stake) {
        LedgerBook ledger = hub.ledger();
        if (!ledger.economyActive()) {
            return;
        }
        BigDecimal newNotional = stake.toMoney().multiply(BigDecimal.valueOf(leverage));
        BigDecimal stress = market.economy().stressFor(interval, market.kind());
        boolean crypto = Category.CRYPTO.equals(market.category());
        BigDecimal open = openNotional(market.id(), interval, window, direction, playerId);
        BigDecimal bucketRisk = null;
        if (crypto) {
            bucketRisk = BigDecimal.ZERO;
            for (Market other : marketRegistry.active()) {
                if (!Category.CRYPTO.equals(other.category()) || hub.findMarket(other.id()).isEmpty()) {
                    continue;
                }
                BigDecimal notional = other.id().equals(market.id()) ? open
                        : openNotional(other.id(), interval, window, direction, null);
                bucketRisk = bucketRisk.add(notional.multiply(other.economy().stressFor(interval, other.kind())));
            }
        }
        EconomyLimits.Exposure exposure = EconomyLimits.checkExposure(ledger.treasury(), stress, open, newNotional, bucketRisk);
        if (!exposure.ok()) {
            BigDecimal maxStake = leverage > 0
                    ? exposure.roomNotional().divide(BigDecimal.valueOf(leverage), 3, RoundingMode.DOWN) : BigDecimal.ZERO;
            throw ApiException.exposureLimitExceeded("this bet is over the limit the treasury sets on " + market.id() + " "
                    + (direction == crowdvsmarket.engine.round.Direction.UP ? "UP" : "DOWN") + " in this round"
                    + (exposure.bucketLimited() ? " (the crypto markets share one limit)" : "") + ": the notional of your bet is "
                    + newNotional.stripTrailingZeros().toPlainString() + " PLAY (stake x leverage), the room left is "
                    + exposure.roomNotional().stripTrailingZeros().toPlainString() + " PLAY of notional - at x" + leverage
                    + " a stake of at most " + maxStake.stripTrailingZeros().toPlainString() + " PLAY. Try a lower leverage, a "
                    + "smaller stake or the other side.");
        }
    }

    /**
     * Р-106 (rules point 5): a fixed-payout bet must fit under the treasury limit of its mode - the stakes of the fixed bets on its side of
     * its round of its market (0.1% of TREASURY), and (crypto) of all crypto markets together (0.4%). Nothing is checked before the cutover,
     * like {@link #requireExposureRoom}; the error is the same kind, naming the room left in PLAY. O(1) in the bets of the round.
     */
    private void requireFixedExposureRoom(Market market, Interval interval, RoundWindow window, String playerId,
                                          crowdvsmarket.engine.round.Direction direction, PlayAmount stake) {
        LedgerBook ledger = hub.ledger();
        if (!ledger.economyActive()) {
            return;
        }
        BigDecimal open = openFixedStake(market.id(), interval, window, direction, playerId);
        BigDecimal bucketOpen = null;
        if (Category.CRYPTO.equals(market.category())) {
            bucketOpen = BigDecimal.ZERO;
            for (Market other : marketRegistry.active()) {
                if (!Category.CRYPTO.equals(other.category()) || hub.findMarket(other.id()).isEmpty()) {
                    continue;
                }
                bucketOpen = bucketOpen.add(other.id().equals(market.id()) ? open
                        : openFixedStake(other.id(), interval, window, direction, null));
            }
        }
        EconomyLimits.FixedExposure exposure = EconomyLimits.checkFixedExposure(ledger.treasury(), open, stake.toMoney(), bucketOpen);
        if (!exposure.ok()) {
            throw ApiException.exposureLimitExceeded("this bet is over the limit the treasury sets on win-or-lose bets on " + market.id()
                    + " " + (direction == crowdvsmarket.engine.round.Direction.UP ? "UP" : "DOWN") + " in this round"
                    + (exposure.bucketLimited() ? " (the crypto markets share one limit)" : "") + ": your stake is "
                    + stake.toMoney().stripTrailingZeros().toPlainString() + " PLAY, the room left is "
                    + exposure.roomStake().stripTrailingZeros().toPlainString() + " PLAY of stake. Try a smaller stake or the other side.");
        }
    }

    /** The stakes (PLAY) of the fixed-payout bets open on one side of a round of a market, without {@code exceptPlayer}'s bet. */
    private BigDecimal openFixedStake(String marketId, Interval interval, RoundWindow window,
                                      crowdvsmarket.engine.round.Direction direction, String exceptPlayer) {
        String roundId = new RoundWindow(marketId, interval, window.startMs(), window.endMs()).roundId();
        return hub.engine(marketId, interval).betBook().openFixedStake(roundId, direction, exceptPlayer);
    }

    /** The notional (stake x leverage, PLAY) open on one side of a round of a market, without {@code exceptPlayer}'s bet. */
    private BigDecimal openNotional(String marketId, Interval interval, RoundWindow window,
                                    crowdvsmarket.engine.round.Direction direction, String exceptPlayer) {
        String roundId = new RoundWindow(marketId, interval, window.startMs(), window.endMs()).roundId();
        return hub.engine(marketId, interval).betBook().openNotional(roundId, direction, exceptPlayer);
    }

    /**
     * B4: retires every live bot whose balance is below {@code herd.bots.retire-below-play} and that has no open bet (its stakes would
     * come back to an account that no longer plays). Each retirement is a {@link GameEvent.BotRetired} (balance to TREASURY) and the
     * {@code players} row's {@code retired_at_ms}, in one transaction. Nothing is said anywhere public. Only once the economy is active.
     *
     * @return the ids of the bots retired now
     */
    public List<String> retireBotsBelowThreshold(long nowMs) {
        return write("retire bots", nowMs, ctx -> {
            LedgerBook ledger = hub.ledger();
            if (!ledger.economyActive()) {
                return List.of();
            }
            PlayAmount threshold = PlayAmount.ofWholePlay(config.botsRetireBelowPlay());
            List<String> retired = new ArrayList<>();
            for (PlayerDirectory.Row bot : players.bots()) {
                if (bot.isRetired() || ledger.isRetired(bot.playerId())) {
                    continue;
                }
                PlayAmount balance;
                try {
                    balance = ledger.balance(bot.playerId());
                } catch (java.util.NoSuchElementException e) {
                    continue;
                }
                if (balance.compareTo(threshold) >= 0 || openBets.stake(bot.playerId()).isPositive()) {
                    continue;
                }
                GameEvent.BotRetired event = new GameEvent.BotRetired(bot.playerId(), nowMs);
                ctx.record(event, nowMs);
                players.cacheRetired(bot.playerId(), nowMs);
                retired.add(bot.playerId());
                LOG.debugf("bot %s retired with %s", bot.playerId(), balance);
            }
            return retired;
        });
    }

    /**
     * B4: once a UTC day, moves the fees the bots paid since the last sweep from HERD to TREASURY ({@link GameEvent.HerdSweptToTreasury}).
     * The fees of the people stay in HERD. Nothing is written when the day has already been swept or there is nothing to move.
     *
     * @return the amount moved (zero when nothing was)
     */
    public PlayAmount sweepBotFeesIfDue(long nowMs) {
        return write("sweep bot fees", nowMs, ctx -> {
            LedgerBook ledger = hub.ledger();
            if (!ledger.economyActive()) {
                return PlayAmount.ZERO;
            }
            long today = Math.floorDiv(nowMs, 86_400_000L);
            if (ledger.lastSweepAtMs() >= 0 && today <= Math.floorDiv(ledger.lastSweepAtMs(), 86_400_000L)) {
                return PlayAmount.ZERO;
            }
            PlayAmount botFees = PlayAmount.ZERO;
            for (PlayerDirectory.Row bot : players.bots()) {
                botFees = botFees.plus(ledger.feesPaid(bot.playerId()));
            }
            PlayAmount amount = botFees.minus(ledger.botFeesSwept());
            if (amount.compareTo(ledger.herd()) > 0) {
                amount = ledger.herd();
            }
            if (!amount.isPositive()) {
                return PlayAmount.ZERO;
            }
            GameEvent.HerdSweptToTreasury event = new GameEvent.HerdSweptToTreasury(amount.mojos(), nowMs);
            ctx.record(event, nowMs);
            LOG.infof("the fees the bots paid, %s, moved from HERD to TREASURY", amount);
            return amount;
        });
    }

    /** The nicknames in use, bots included, retired ones too: a generated name must be new. */
    public java.util.Set<String> nicknamesInUse() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PlayerDirectory.Row row : players.all()) {
            if (row.nickname() != null) {
                names.add(row.nickname());
            }
        }
        return names;
    }

    /**
     * B7: the books against the chain. Says WARN in the log when they do not agree: when {@code TREASURY + HERD + players + open
     * stakes} is not the opening total plus the deposits minus the withdrawals since the cutover, or when it is further from the treasury
     * coin on chain than pending withdrawals and fresh deposits explain. Needs the chain service for {@code onChainTreasury} only.
     */
    public Economy.ReconciliationDto reconciliation(long nowMs) {
        crowdvsmarket.server.chain.ChainSvcClient.PlayTreasury chain = null;
        try {
            chain = chainSvc.playTreasury();
        } catch (RuntimeException e) {
            LOG.debugf(e, "the treasury coin could not be read from the chain service");
        }
        EconomyFigures ledger = snapshot().economy();
        PlayAmount ledgerTotal = ledger.treasury().plus(ledger.herd()).plus(ledger.playersTotal()).plus(ledger.openStakes());
        PlayAmount expected = ledger.openingTotal().plus(ledger.externalFlowSinceCutover());
        PlayAmount bookDelta = ledgerTotal.minus(expected);
        boolean active = ledger.economyActive();
        boolean booksBalanced = !active || bookDelta.mojos() == 0;
        PlayAmount onChain = chain == null ? null : new PlayAmount(chain.amountMojos());
        PlayAmount chainDelta = onChain == null || !active ? null : ledgerTotal.minus(onChain);
        Boolean chainConsistent = chainDelta == null ? null
                : chainDelta.compareTo(ledger.withdrawalsPending().negate()) >= 0
                        && chainDelta.compareTo(ledger.depositedSinceCutover()) <= 0;
        boolean breaker = ledger.circuitBreakerOpen(Instant.ofEpochMilli(nowMs));
        if (!booksBalanced) {
            LOG.warnf("RECONCILIATION MISMATCH: TREASURY %s + HERD %s + players %s + open stakes %s = %s, expected %s "
                    + "(opening %s + deposits minus withdrawals since the cutover %s): off by %s", ledger.treasury(), ledger.herd(),
                    ledger.playersTotal(), ledger.openStakes(), ledgerTotal, expected, ledger.openingTotal(),
                    ledger.externalFlowSinceCutover(), bookDelta);
        }
        if (Boolean.FALSE.equals(chainConsistent)) {
            LOG.warnf("RECONCILIATION MISMATCH: the books hold %s, the treasury coin on chain %s: the difference %s is outside "
                    + "[-pending withdrawals %s, +deposits since the cutover %s]", ledgerTotal, onChain, chainDelta,
                    ledger.withdrawalsPending(), ledger.depositedSinceCutover());
        }
        return new Economy.ReconciliationDto(active, money(ISSUED), onChain == null ? null : money(onChain),
                money(ledger.treasury()), money(ledger.herd()), money(ledger.playersTotal()), money(ledger.openStakes()),
                money(ledger.withdrawnConfirmed()), money(ledger.withdrawalsPending()), money(ledger.depositedTotal()),
                money(ledger.openingTotal()), money(expected), money(ledgerTotal), money(bookDelta),
                chainDelta == null ? null : money(chainDelta), booksBalanced, chainConsistent, breaker, nowMs);
    }

    private static final long SUPPLY_CACHE_MS = 10_000;
    private volatile Economy.SupplyDto supplyCache;

    /**
     * C5: the public supply block (HERD never on its own, Р-90). Cached for {@value #SUPPLY_CACHE_MS} ms: it is public, so a poll must not
     * hit the chain service or repeat the reconciliation WARN each time.
     */
    public Economy.SupplyDto supply(long nowMs) {
        Economy.SupplyDto cached = supplyCache;
        if (cached != null && nowMs >= cached.atMs() && nowMs - cached.atMs() < SUPPLY_CACHE_MS) {
            return cached;
        }
        String coinId = null;
        try {
            crowdvsmarket.server.chain.ChainSvcClient.PlayTreasury chain = chainSvc.playTreasury();
            coinId = chain == null ? null : chain.coinId();
        } catch (RuntimeException e) {
            LOG.debugf(e, "the treasury coin could not be read from the chain service");
        }
        Economy.ReconciliationDto r = reconciliation(nowMs);
        java.math.BigDecimal held = new java.math.BigDecimal(r.players()).add(new java.math.BigDecimal(r.openStakes()));
        java.math.BigDecimal reserve = new java.math.BigDecimal(r.treasury()).add(new java.math.BigDecimal(r.herd()));
        Economy.SupplyDto dto = new Economy.SupplyDto(r.economyActive(), r.issued(), r.onChainTreasury(), coinId,
                held.toPlainString(), reserve.toPlainString(), r.withdrawnConfirmed(), r.booksBalanced(), r.chainConsistent(),
                r.circuitBreakerOpen(), nowMs);
        supplyCache = dto;
        return dto;
    }

    private static String money(PlayAmount amount) {
        return amount.toMoney().toPlainString();
    }

    // ---------------------------------------------------------------------------------------
    // Home aggregate (Р-34)
    // ---------------------------------------------------------------------------------------

    private static final int HOME_TOP_N = 5; // предложение — not fixed by contract.ts

    /** {@code home} keeps describing BTC (docs/research/markets_architecture.md §8): its pairs, its crowd
     *  account; the other markets have their own calls. BTC is {@link #defaultMarket}: after its price moved to
     *  another source, the market that took it over. */
    public Home.HomeSummaryDto home(String viewerPlayerIdOrNull) {
        // F8: every part reads the same committed snapshot, with no lock - the rounds, the crowd account and the boards are of one version
        awaitStored();
        ReadViews.Snapshot snapshot = snapshot();
        String bitcoin = defaultMarket().id();
        Rounds.RoundsPairDto hourly = roundsPair(Enums.Interval.HOURLY, bitcoin, viewerPlayerIdOrNull, snapshot);
        Rounds.RoundsPairDto daily = roundsPair(Enums.Interval.DAILY, bitcoin, viewerPlayerIdOrNull, snapshot);
        Crowd.CrowdAccountDto crowd = crowdAccount(bitcoin, snapshot);
        Ratings.RatingsDto ratings = ratings(Season.of(Instant.now()), false, snapshot, HOME_TOP_N);
        List<Ratings.RichEntryDto> topRich = ratings.rich();
        List<Ratings.ProphetEntryDto> topProphets = ratings.prophets();
        return new Home.HomeSummaryDto(hourly, daily, crowd, topRich, topProphets);
    }
}
