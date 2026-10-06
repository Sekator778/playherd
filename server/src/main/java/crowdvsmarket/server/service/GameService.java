package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.game.PlayerRoundOutcome;
import crowdvsmarket.engine.game.RoundReport;
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
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.domain.WinStreak;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Crowd;
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
 * lock (the source may be asked over the network), then, under the write lock, runs ONE database
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
 * nothing here ever calls into {@code BotService}. The read model's cache monitor ({@code MarketViewService},
 * which reads the engine through {@link #readEngine}) is taken before the engine's read lock, and nothing here
 * ever calls into it either.
 */
@ApplicationScoped
public class GameService implements MarketListener {

    @Inject
    GameConfig config;
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
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    crowdvsmarket.server.persistence.ServerMetaRepository serverMeta;
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
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Used by {@code EngineRebuildService}: runs {@code action} holding this class's write lock, the
     *  exclusive access every mutating method below gets by holding it — the swap of a rebuilt state
     *  must never interleave with any other read or write of {@link EngineHub} through this class. Not
     *  gated by the engine being ready: this is how it becomes ready. */
    public void runExclusively(Runnable action) {
        engineLock.writeLock().lock();
        try {
            action.run();
        } finally {
            engineLock.writeLock().unlock();
        }
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
     * The small, explicit read API of the engine for the read model ({@code MarketViewService}, which
     * builds {@code markets}, {@code myPositions} and {@code crowdOverview}): runs {@code read} holding the
     * engine's READ lock, like every read in this class — 503 {@code ENGINE_NOT_READY} while the engine is
     * not ready. {@code read} must be a pure in-memory read: no database round trip, no network (nothing
     * that can block holds the lock a settlement is waiting for).
     */
    public <T> T readEngine(java.util.function.Supplier<T> read) {
        lockRead();
        try {
            return read.get();
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** The write lock of every call that changes the engine, taken only while it is ready: the first
     *  check answers at once, without queueing behind a rebuild; the second one counts (a recovery may
     *  have failed while this call waited). The caller unlocks as usual, once this has returned. */
    private void lockWrite() {
        requireReady();
        engineLock.writeLock().lock();
        if (!rebuildService.isReady()) {
            engineLock.writeLock().unlock();
            throw ApiException.engineNotReady();
        }
    }

    /** {@link #lockWrite} for the read lock, taken by every read that comes from the engine. */
    private void lockRead() {
        requireReady();
        engineLock.readLock().lock();
        if (!rebuildService.isReady()) {
            engineLock.readLock().unlock();
            throw ApiException.engineNotReady();
        }
    }

    /** Registers this service with the registry at startup, so a market listed or closed from now
     *  on reaches {@link #onListed}/{@link #onClosed}. A market that was listed before this runs is
     *  not lost: every rebuild creates the engines of every market in the registry, and the
     *  scheduler seeds any running market that still has no series ({@link #ensureSeries}). */
    void onStart(@Observes StartupEvent ev) {
        marketRegistry.setListener(this);
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

    private void persist(GameEvent event, long atMs) {
        db.tx(c -> {
            eventStore.append(c, event, atMs);
            return null;
        });
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
        lockWrite();
        try {
            Optional<String> existing = players.playerIdForWallet(walletAddress);
            String playerId;
            boolean isNew = existing.isEmpty();
            boolean packGranted = false;
            Instant now = Instant.ofEpochMilli(nowMs);
            if (isNew) {
                playerId = UUID.randomUUID().toString();
                String finalPlayerId = playerId;
                int playerIndex = serverMeta.reserveNextPlayerIndex();
                // The directory's cache gets the row only after the commit (PlayerDirectory's javadoc).
                players.cache(db.tx(c -> players.insert(c, finalPlayerId, walletAddress, null, false, null,
                        playerIndex, nowMs)));
                packGranted = hub.ledger().seasonPackDue(playerId, now); // a player nobody has seen: the first pack
                appendPlayerRegistered(playerId, nowMs);
            } else {
                playerId = existing.get();
                Season season = Season.of(now);
                boolean seasonAlreadyTouched = hub.ledger().seasonRecord(playerId, season).isPresent();
                if (!seasonAlreadyTouched) {
                    packGranted = hub.ledger().seasonPackDue(playerId, now);
                    appendPlayerRegistered(playerId, nowMs);
                }
            }
            return new PlayerTouchResult(summary(playerId), isNew, packGranted);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /** Shared by {@link #registerOrTouchPlayer} (new-player and season-rollover-touch cases) and
     *  {@link #createBotPlayer} — the one place a {@code PlayerRegistered} event is actually
     *  persisted+applied, so there is exactly one code path to reason about for task item 1's fix. */
    private void appendPlayerRegistered(String playerId, long nowMs) {
        GameEvent.PlayerRegistered event = new GameEvent.PlayerRegistered(playerId, nowMs);
        persist(event, nowMs);
        eventApplier.apply(event);
    }

    /** Used by {@code BotService} to create the 20 labelled bot players once (Р-46), and (with
     *  {@code isBot=false}) by nothing else — bots skip the wallet-signature flow entirely, they
     *  are server-side only. */
    public Identity.PlayerSummaryDto createBotPlayer(String nickname, String strategy, long nowMs) {
        lockWrite();
        try {
            String playerId = "bot-" + strategy.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID();
            String walletAddress = "txbot1" + playerId.replace("-", "").substring(0, Math.min(40, playerId.length()));
            int playerIndex = serverMeta.reserveNextPlayerIndex();
            players.cache(db.tx(c -> players.insert(c, playerId, walletAddress, nickname, true, strategy,
                    playerIndex, nowMs)));
            appendPlayerRegistered(playerId, nowMs);
            return summary(playerId);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    public Identity.PlayerSummaryDto summary(String playerId) {
        PlayerDirectory.Row row = players.byId(playerId)
                .orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
        PlayerIdentity identity = row.identity();
        return new Identity.PlayerSummaryDto(playerId, identity.nickname(), row.walletAddress(),
                identity.displayName(), row.isBot());
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
        lockWrite();
        try {
            players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
            db.tx(c -> {
                players.updateNickname(c, playerId, trimmed);
                return null;
            });
            players.cacheNickname(playerId, trimmed);
            return summary(playerId);
        } finally {
            engineLock.writeLock().unlock();
        }
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
        engineLock.writeLock().lock();
        try {
            if (!rebuildService.isReady()) {
                return;
            }
            ensureSeriesLocked(market, nowMs);
        } finally {
            engineLock.writeLock().unlock();
        }
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
        engineLock.writeLock().lock();
        try {
            if (!rebuildService.isReady()) {
                return;
            }
            try {
                hub.ensureMarket(market);
                cancellations.closeSeries(eventApplier, hub, market, "market closed: " + reason, nowMs);
            } catch (RuntimeException | Error e) {
                rebuildService.recoverAfterFailure(e, "market " + market.id(), nowMs); // always: completes a close
                throw e;
            }
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /** Whether any bet waits in {@code window}'s round. */
    public boolean hasPendingBets(RoundWindow window) {
        lockRead();
        try {
            return hasBetsLocked(window);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    private boolean hasBetsLocked(RoundWindow window) {
        return hub.findMarket(window.market())
                .map(m -> !m.engine(window.interval()).betBook().betsFor(window.roundId()).isEmpty())
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
        lockRead();
        try {
            RoundWindow window = from;
            while (nowMs - window.endMs() > stepMs && !hasBetsLocked(window)) {
                window = RoundClock.next(window);
            }
            return window;
        } finally {
            engineLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Betting (Р-8, Р-13, Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public Rounds.BetDto placeBet(String playerId, Betting.PlaceBetRequest req, long nowMs) {
        RoundWindow.RoundRef ref = parseRoundId(req.roundId());
        marketRegistry.require(ref.market()); // MARKET_NOT_FOUND without waiting for the lock
        lockWrite();
        try {
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
            // §4 п.6: a round that already has a result row was cancelled by the operator before it
            // started — the cancellation refunded and cleared its bets, so a bet accepted now would
            // sit in the bet book with its stake debited and never be settled or refunded.
            if (roundResults.exists(bettingWindow.roundId())) {
                throw ApiException.roundNotOpenForBetting(req.roundId());
            }
            // Only registered players bet. A bot tick that started before the player directory was
            // reset (test DB cleanup between classes) otherwise waited for this lock and then
            // placed bets for bots that no longer exist — the ledger's ensureSeasonPack below
            // would have given them a starter pack.
            if (players.byId(playerId).isEmpty()) {
                throw ApiException.notFound("unknown player " + playerId);
            }
            Leverage leverage;
            try {
                leverage = market.spec().leverage(req.leverage()); // 2..the market's cap (Т-4)
            } catch (IllegalArgumentException e) {
                throw ApiException.invalidLeverage(e.getMessage());
            }
            BigDecimal stakeDecimal;
            try {
                stakeDecimal = new BigDecimal(req.stake());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("invalid stake: " + req.stake());
            }
            PlayAmount stake;
            try {
                stake = PlayAmount.fromMoney(stakeDecimal);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest(e.getMessage());
            }

            LedgerBook ledger = hub.ledger();
            Instant now = Instant.ofEpochMilli(nowMs);
            ledger.ensureSeasonPack(playerId, now); // idempotent — also happens inside the event replay below

            if (stake.compareTo(LedgerBook.MIN_STAKE) < 0) {
                throw ApiException.stakeBelowMinimum("stake " + stake + " is below the minimum " + LedgerBook.MIN_STAKE);
            }
            GameEngine engine = hub.engine(market.id(), ref.interval());
            PlayAmount currentBalance = ledger.balance(playerId);
            Optional<Bet> existing = engine.betBook().betsFor(bettingWindow.roundId()).stream()
                    .filter(b -> b.playerId().equals(playerId)).findFirst();
            PlayAmount effectiveBalance = existing.map(b -> currentBalance.plus(b.stake())).orElse(currentBalance);
            if (stake.compareTo(effectiveBalance) > 0) {
                throw ApiException.insufficientBalance("stake " + stake + " exceeds balance " + effectiveBalance);
            }

            GameEvent.BetPlaced event = new GameEvent.BetPlaced(playerId, ref.interval(), bettingWindow.startMs(),
                    bettingWindow.endMs(), toEngine(req.direction()), req.leverage(), stake.mojos(), nowMs,
                    market.id());
            persist(event, nowMs);
            eventApplier.apply(event);
            return new Rounds.BetDto(playerId, bettingWindow.roundId(), req.direction(), req.leverage(),
                    stake.toMoney().toPlainString());
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /** Р-13: cancels the player's bet on a round open for betting and refunds it; no bet — nothing persisted (§4 п.18). */
    public void cancelBet(String playerId, String roundId, long nowMs) {
        RoundWindow.RoundRef ref = parseRoundId(roundId);
        marketRegistry.require(ref.market());
        lockWrite();
        try {
            RoundWindow bettingWindow = roundProgress.findNext(ref.market(), ref.interval())
                    .map(RoundClock::next)
                    .orElseThrow(() -> ApiException.roundNotOpenForBetting(roundId));
            // Same wave-2 exact-boundary fix as placeBet above — a bet must not be cancellable (and
            // its stake refunded) once its round has, by wall-clock time, already started.
            if (!bettingWindow.roundId().equals(ref.roundId()) || nowMs >= bettingWindow.startMs()) {
                throw ApiException.roundNotOpenForBetting(roundId);
            }
            if (!hub.hasBet(ref.market(), ref.interval(), bettingWindow.roundId(), playerId)) {
                return;
            }
            GameEvent.BetCancelled event = new GameEvent.BetCancelled(playerId, ref.interval(),
                    bettingWindow.startMs(), bettingWindow.endMs(), nowMs, ref.market());
            persist(event, nowMs);
            eventApplier.apply(event);
        } finally {
            engineLock.writeLock().unlock();
        }
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
     *   <li>Under the write lock: looks at {@code round_results} again (the round may have been
     *       resolved or cancelled while prices were fetched — then nothing happens and {@code null} is
     *       returned), takes the open from the round that closed there ({@link BoundaryPrices}, Р-40), then runs ONE
     *       database transaction: the {@code RoundResolved} event, the result and history rows and the oracle record,
     *       and the event applied to the engine inside it. A failure rolls it back and, if the event had been applied,
     *       rebuilds the in-memory state from the event log before it propagates (§4 п.13).
     * </ol>
     * A round nobody bet on stores a single open→close candle as its path (§4 п.7); one with bets
     * stores the source's path (at most 30 candles in live mode).
     *
     * @return the engine's report, or {@code null} when the round was already resolved or cancelled
     */
    public RoundReport resolveRound(RoundWindow window, long nowMs) throws PriceUnavailableException {
        requireReady(); // no point in asking the price source while the engine cannot take the result
        Market market = marketRegistry.require(window.market());
        RoundPriceData fetched = resolvePrices(market, window); // phase 1 — no lock

        lockWrite();
        try {
            if (roundResults.exists(window.roundId())) {
                return null;
            }
            hub.ensureMarket(market);
            RoundPriceData data = boundaries.forSettlement(window, fetched);
            BigDecimal open = data.open().setScale(2, RoundingMode.HALF_UP);
            BigDecimal close = data.close().setScale(2, RoundingMode.HALF_UP);
            boolean hasBets = hasBetsLocked(window);
            List<Candle> path = hasBets && !data.path().isEmpty() ? data.path()
                    : List.of(singleCandle(window, open, close));
            GameEvent.RoundResolved event = new GameEvent.RoundResolved(window.interval(), window.startMs(),
                    window.endMs(), open.toPlainString(), close.toPlainString(), EventApplier.fromCandles(path),
                    nowMs, market.id(), data.venue());
            AtomicBoolean touchedMemory = new AtomicBoolean();
            try {
                RoundReport report = db.tx(c -> {
                    eventStore.append(c, event, nowMs);
                    touchedMemory.set(true); // from here the engine may be ahead of the database
                    RoundReport r = (RoundReport) eventApplier.apply(event);
                    storeSettledResult(c, market, window, r, data.sourceTsMs(), data.venue());
                    return r;
                });
                // only once it has committed; the close is the close on the exchange the round ran on (Р-76)
                boundaries.settled(window, close.movePointRight(2).longValueExact(), data.venue());
                rebuildService.recovered(window.roundId());
                return report;
            } catch (RuntimeException | Error e) {
                if (touchedMemory.get()) {
                    rebuildService.recoverAfterFailure(e, window.roundId(), nowMs);
                }
                throw e;
            }
        } finally {
            engineLock.writeLock().unlock();
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
                                    long sourceTsMs, String venue) throws SQLException {
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
            Position position = Position.openFromMargin(outcome.playerId(), window.roundId(),
                    toEngine(toDto(outcome.direction())), new Leverage(outcome.leverage()),
                    outcome.stake().toMoney(), entryPrice, spec);
            BigDecimal pnl = outcome.payout().subtract(outcome.stake().toMoney());
            Boolean directionCorrect = directionCorrect(outcome.direction(), report.openPrice(), report.closePrice());

            positionDtos.add(new Rounds.PublicPositionDto(outcome.playerId(), window.roundId(), toDto(outcome.direction()),
                    outcome.leverage(), position.size().toPlainString(), entryRaw,
                    market.rawString(position.liquidationPrice()), pnl.toPlainString(),
                    outcome.stake().toMoney().toPlainString(), playerSummary, outcome.liquidated(),
                    PlayAmount.fromMoney(outcome.payout()).toMoney().toPlainString(), directionCorrect));

            hashEntries.add(new crowdvsmarket.server.util.ResultsHasher.PositionEntry(outcome.playerId(),
                    toDto(outcome.direction()).name(), outcome.leverage(), outcome.stake().mojos(),
                    outcome.liquidated() ? "LIQUIDATED" : "SETTLED", PlayAmount.fromMoney(outcome.payout()).mojos()));

            historyRows.add(new RoundResultRepository.PlayerHistoryRow(outcome.playerId(), toDto(outcome.direction()),
                    outcome.leverage(), outcome.stake().mojos(), outcome.liquidated(), directionCorrect,
                    PlayAmount.fromMoney(outcome.payout()).mojos()));
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
        enqueueOracleRecord(c, market, window.roundId(), settledAtMs, sourceTsMs, closeCents, canonical.hash());
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
                                     long priceCents, String resultsHash) throws SQLException {
        long seq = serverMeta.reserveOracleSeqRange(c, 1);
        String source = config.mode() == GameMode.REPLAY ? "replay" : "live";
        oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market.id(), market.oracleIndex(), roundId, seq,
                ts / 1000, priceCents, resultsHash, source, sourceTsMs / 1000, System.currentTimeMillis()));
    }

    /** {@code AdminResource}'s entry point: parses {@code roundId} and delegates to
     *  {@link #cancelRound(RoundWindow, String, long)} (which takes the write lock itself —
     *  {@link java.util.concurrent.locks.ReentrantReadWriteLock}'s write lock is reentrant, so
     *  this needs no locking of its own). */
    public Rounds.CancellationDto cancelRoundById(String roundId, String reason, long nowMs) {
        return cancelRound(windowFor(roundId), reason, nowMs);
    }

    /** Operator/automatic cancellation (Р-23, Р-32). {@code window} must be the currently running
     *  or currently open-for-betting round of its series; already-resolved rounds are rejected. */
    public Rounds.CancellationDto cancelRound(RoundWindow window, String reason, long nowMs) {
        Market market = marketRegistry.require(window.market());
        lockWrite();
        try {
            if (roundResults.exists(window.roundId())) {
                throw ApiException.badRequest("round " + window.roundId() + " is already resolved");
            }
            Optional<RoundWindow> running = roundProgress.findNext(market.id(), window.interval());
            if (running.isEmpty()
                    || (!window.equals(running.get()) && !window.equals(RoundClock.next(running.get())))) {
                throw ApiException.badRequest("not the current running or betting round for " + market.id() + "/"
                        + window.interval());
            }
            hub.ensureMarket(market);
            AtomicBoolean touchedMemory = new AtomicBoolean();
            try {
                // one transaction, like the settlement; never touches RoundProgress (the scheduler steps over)
                Rounds.CancellationDto cancelled =
                        cancellations.cancel(eventApplier, hub, market, window, reason, nowMs, touchedMemory);
                rebuildService.recovered(window.roundId());
                return cancelled;
            } catch (RuntimeException | Error e) {
                if (touchedMemory.get()) {
                    rebuildService.recoverAfterFailure(e, window.roundId(), nowMs);
                }
                throw e;
            }
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reads: rounds / round (Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    /** The series' running window — {@code RoundProgress}'s cursor — or a 4xx saying why there is
     *  none: the market is closed, or its rounds are not played here (mode=replay plays BTC only) or
     *  not yet (just listed). */
    private RoundWindow runningWindowOf(Market market, Interval interval) {
        return roundProgress.findNext(market.id(), interval).orElseThrow(() -> market.isActive()
                ? ApiException.notFound("market " + market.id() + " has no running " + interval + " series")
                : ApiException.marketClosed(market.id()));
    }

    /** The running and betting round of {@code marketIdOrNull}'s series ({@code null}: BTC). */
    public Rounds.RoundsPairDto roundsPair(Enums.Interval intervalDto, String marketIdOrNull,
                                           String viewerPlayerIdOrNull) {
        Market market = requireMarket(marketIdOrNull);
        Interval interval = toEngine(intervalDto);
        lockRead();
        try {
            hub.ensureMarket(market);
            RoundWindow runningWindow = runningWindowOf(market, interval);
            RoundWindow bettingWindow = RoundClock.next(runningWindow);
            Rounds.RoundDto running = roundResults.findRoundDto(runningWindow.roundId())
                    .orElseGet(() -> buildRunningDto(market, runningWindow, System.currentTimeMillis()));
            Rounds.RoundDto betting = roundResults.findRoundDto(bettingWindow.roundId())
                    .orElseGet(() -> buildBettingDto(market, bettingWindow, viewerPlayerIdOrNull));
            return new Rounds.RoundsPairDto(intervalDto, running, betting);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** {@code BTC-1h-<start>} is {@code 1h-<start>}: the id is made canonical before it is a key. */
    public Rounds.RoundDto round(String roundId, String viewerPlayerIdOrNull) {
        roundId = parseRoundId(roundId).roundId();
        Optional<Rounds.RoundDto> settledOrCancelled = roundResults.findRoundDto(roundId);
        if (settledOrCancelled.isPresent()) {
            return settledOrCancelled.get();
        }
        RoundWindow window = windowFor(roundId);
        Market market = marketRegistry.require(window.market());
        lockRead();
        try {
            hub.ensureMarket(market);
            Optional<RoundWindow> running = roundProgress.findNext(market.id(), window.interval());
            if (running.isPresent()) {
                if (window.equals(running.get())) {
                    return buildRunningDto(market, window, System.currentTimeMillis());
                }
                if (window.equals(RoundClock.next(running.get()))) {
                    return buildBettingDto(market, window, viewerPlayerIdOrNull);
                }
            }
            throw ApiException.notFound("unknown or not-yet-open round " + roundId);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** Р-62's small, capped, newest-first convenience embed on {@code RoundBetting.recentBets} —
     *  предложение, matches the number the web side's own mock already used. The full,
     *  cursor-paginated feed ({@code betFeed} RPC) has no such cap on total history, only on one
     *  call's page size ({@code BetFeed.PAGE_SIZE}). */
    private static final int RECENT_BETS_LIMIT = 14;

    private Rounds.RoundDto buildBettingDto(Market market, RoundWindow window, String viewerPlayerIdOrNull) {
        GameEngine engine = hub.engine(market.id(), window.interval());
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(window.roundId()));
        Rounds.BetDto myBet = viewerPlayerIdOrNull == null ? null : bets.stream()
                .filter(b -> b.playerId().equals(viewerPlayerIdOrNull))
                .findFirst()
                .map(b -> new Rounds.BetDto(b.playerId(), window.roundId(), toDto(b.direction()), b.leverage().value(),
                        b.stake().toMoney().toPlainString()))
                .orElse(null);
        LiveSplit split = buildLiveSplit(bets, market.spec());
        List<Rounds.LiveBetDto> recentBets = betFeedStore.latest(window.roundId(), RECENT_BETS_LIMIT).stream()
                .map(e -> new Rounds.LiveBetDto(summaryOrFallback(e.playerId()), e.direction(), e.leverage(),
                        e.stake(), e.atMs()))
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
    private LiveSplit buildLiveSplit(List<Bet> bets, MarketSpec spec) {
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
    private Rounds.RoundDto buildRunningDto(Market market, RoundWindow window, long nowMs) {
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
        GameEngine engine = hub.engine(market.id(), window.interval());
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(window.roundId()));
        List<Rounds.PublicPositionDto> positions = new ArrayList<>();
        BigDecimal upStake = ZERO_MONEY;
        BigDecimal downStake = ZERO_MONEY;
        List<Bet> upBets = new ArrayList<>();
        List<Bet> downBets = new ArrayList<>();
        for (Bet bet : bets) {
            Position position = Position.openFromMargin(bet.playerId(), window.roundId(), bet.direction(),
                    bet.leverage(), bet.stake().toMoney(), openPrice, spec);
            PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, pathSoFar);
            boolean liquidatedSoFar = outcome instanceof PositionSimulator.Outcome.Liquidated;
            BigDecimal pnl = liquidatedSoFar ? null
                    : ((PositionSimulator.Outcome.Settled) outcome).pnl();
            PlayerIdentity identity = identities.get(bet.playerId());
            Identity.PlayerSummaryDto playerSummary = identity == null
                    ? new Identity.PlayerSummaryDto(bet.playerId(), bet.playerId(), "", bet.playerId(), null)
                    : summary(bet.playerId());
            positions.add(new Rounds.PublicPositionDto(bet.playerId(), window.roundId(), toDto(bet.direction()),
                    bet.leverage().value(), position.size().toPlainString(), openRaw,
                    market.rawString(position.liquidationPrice()), pnl == null ? null : pnl.toPlainString(),
                    bet.stake().toMoney().toPlainString(), playerSummary, liquidatedSoFar, null, null));
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
            int leverage = weightedLeverage(majorityBets, spec);
            BigDecimal margin = hub.crowdAccount(market.id()).capital()
                    .multiply(CrowdAccount.MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_UP);
            Position crowdPosition = Position.openFromMargin("crowd", window.roundId(), majorityDirection,
                    new Leverage(leverage), margin, openPrice, spec);
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
        lockRead();
        try {
            crowdvsmarket.server.domain.BetFeed.Result result = betFeedStore.since(roundId, sinceSeq);
            List<Rounds.BetFeedEntryDto> entries = result.entries().stream()
                    .map(e -> new Rounds.BetFeedEntryDto(e.seq(), e.atMs(), e.playerDisplayName(), e.isBot(),
                            e.direction(), e.stake(), e.leverage()))
                    .toList();
            return new Rounds.BetFeedDto(roundId, market.id(), market.symbol(), entries, result.hasMore());
        } finally {
            engineLock.readLock().unlock();
        }
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

    public Betting.LiquidationPreviewDto previewLiquidation(Betting.LiquidationPreviewRequest req) {
        Market market = requireMarket(req.marketId());
        Leverage leverage;
        try {
            leverage = market.spec().leverage(req.leverage());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidLeverage(e.getMessage());
        }
        BigDecimal stakeMoney;
        try {
            stakeMoney = PlayAmount.fromMoney(new BigDecimal(req.stake())).toMoney();
        } catch (Exception e) {
            throw ApiException.badRequest("invalid stake: " + req.stake());
        }
        BigDecimal rawEntry;
        if (req.referencePrice() != null) {
            try {
                rawEntry = new BigDecimal(req.referencePrice());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("invalid referencePrice: " + req.referencePrice());
            }
        } else {
            rawEntry = currentPrice(market).raw();
        }
        BigDecimal entryPrice = market.toEngine(rawEntry);
        if (entryPrice.signum() <= 0) {
            throw ApiException.badRequest("referencePrice must be positive: " + req.referencePrice());
        }
        Position position = Position.openFromMargin("preview", "preview", toEngine(req.direction()), leverage,
                stakeMoney, entryPrice, market.spec());
        BigDecimal liqPrice = position.liquidationPrice();
        double pct = liqPrice.subtract(entryPrice).abs()
                .divide(entryPrice, MathContext.DECIMAL128).doubleValue();
        return new Betting.LiquidationPreviewDto(market.rawString(entryPrice), market.rawString(liqPrice), pct);
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
        lockRead();
        try {
            LedgerBook ledger = hub.ledger();
            PlayAmount balance;
            try {
                balance = ledger.balance(playerId);
            } catch (java.util.NoSuchElementException e) {
                balance = PlayAmount.ZERO;
            }
            return new Ledger.PlayerAccountDto(playerId, balance.toMoney().toPlainString(), Season.of(Instant.now()).id(),
                    inPlayStake(playerId).toMoney().toPlainString());
        } finally {
            engineLock.readLock().unlock();
        }
    }

    /** Bug fix: sum of {@code playerId}'s stakes across every not-yet-settled round of every
     *  running series (every market, both intervals) — the BETTING round (bet placed, round not
     *  started) and the RUNNING one (position opened, not yet resolved) each contribute at most one
     *  bet per series (Р-13; Т-5: one position per player per round of a market). It is the sum of
     *  what {@code myPositions} lists ({@link OpenBets}: one walk for both). */
    private PlayAmount inPlayStake(String playerId) {
        return openBets.stake(playerId);
    }

    private void ensurePlayerKnown(String playerId) {
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
    }

    public Ledger.SeasonRecordDto seasonRecord(String playerId, String seasonIdOrNull) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        lockRead();
        try {
            Optional<SeasonRecord> record = hub.ledger().seasonRecord(playerId, season);
            return record.map(sr -> new Ledger.SeasonRecordDto(sr.season().id(), sr.baselineBalance().toMoney().toPlainString(),
                            sr.deposited().toMoney().toPlainString(), sr.withdrawn().toMoney().toPlainString(),
                            sr.roundsPlayed(), sr.directionEligibleRounds(), sr.directionCorrectRounds()))
                    .orElseGet(() -> new Ledger.SeasonRecordDto(season.id(), "0.000", "0.000", "0.000", 0, 0, 0));
        } finally {
            engineLock.readLock().unlock();
        }
    }

    private static Season parseSeason(String id) {
        try {
            String[] parts = id.split("-");
            return new Season(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (Exception e) {
            throw ApiException.badRequest("malformed season id: " + id);
        }
    }

    public Paging.Page<Ledger.LedgerEntryDto> myLedger(String playerId, Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        lockRead();
        try {
            List<LedgerEntry> all = hub.ledger().entries();
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
            List<Ledger.LedgerEntryDto> page = hasMore ? filtered.subList(0, limit) : filtered;
            return new Paging.Page<>(page, hasMore);
        } finally {
            engineLock.readLock().unlock();
        }
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
        if (!config.withdrawalEnabled()) {
            throw ApiException.withdrawalUnavailable();
        }
        PlayAmount amt;
        try {
            amt = PlayAmount.fromMoney(new BigDecimal(amount));
        } catch (Exception e) {
            throw ApiException.badRequest("invalid amount: " + amount);
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
        String requestId;
        String toAddress;
        lockWrite();
        try {
            PlayAmount balance = hub.ledger().balance(playerId);
            if (amt.compareTo(balance) > 0) {
                throw ApiException.withdrawalExceedsBalance("withdrawal " + amt + " exceeds balance " + balance);
            }
            toAddress = players.byId(playerId)
                    .orElseThrow(() -> ApiException.notFound("unknown player " + playerId))
                    .walletAddress();
            withdrawalSubmitter.ensureNamespace(); // E1: before this database's first withdrawal row exists (§4 п.16)
            GameEvent.WithdrawalRequested event = new GameEvent.WithdrawalRequested(playerId, amt.mojos(), nowMs);
            persist(event, nowMs);
            // LedgerBook assigns its own "w-N" id inside requestWithdrawal (a single counter shared
            // with deposits' "d-N") — we reuse THAT id as our own external requestId rather than
            // minting a separate one, precisely so replaying this same event later (same call, same
            // position in the event order) reproduces the identical id deterministically; see
            // GameEvent.WithdrawalRequested's javadoc and EventApplier's WithdrawalRequested case.
            requestId = (String) eventApplier.apply(event);
            String finalRequestId = requestId;
            String finalToAddress = toAddress;
            db.tx(c -> {
                withdrawalDeposits.insertWithdrawal(c, finalRequestId, playerId, amt.mojos(), finalToAddress, nowMs);
                return null;
            });
        } finally {
            engineLock.writeLock().unlock();
        }
        withdrawalSubmitter.submit(requestId, toAddress, amt.mojos(), nowMs);
        return withdrawalDeposits.findWithdrawal(requestId).orElseThrow();
    }

    /** Р-53: {@code WithdrawalPollerService}'s entry point once {@code GET
     *  /v1/play/withdrawals/{requestId}} reports {@code state="confirmed"} — persists {@code
     *  WithdrawalConfirmedOnChain} (engine effect: closes out the request, same as the old {@code
     *  WithdrawalConfirmed} — the escrow debit already happened at request time) and makes {@code
     *  coinId}/{@code height} public in the player's own withdrawal view (task requirement). */
    public void confirmWithdrawalOnChain(String requestId, String coinId, long height, long nowMs) {
        lockWrite();
        try {
            if (!withdrawalAwaitingChain(requestId)) {
                return;
            }
            GameEvent.WithdrawalConfirmedOnChain event =
                    new GameEvent.WithdrawalConfirmedOnChain(requestId, coinId, height, nowMs);
            persist(event, nowMs);
            eventApplier.apply(event);
            db.tx(c -> {
                withdrawalDeposits.markWithdrawalConfirmedOnChain(c, requestId, coinId, height, nowMs);
                return null;
            });
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    /** Р-53: {@code WithdrawalPollerService}'s entry point once {@code GET
     *  /v1/play/withdrawals/{requestId}} reports {@code state="failed"} — reuses the pre-existing
     *  {@code WithdrawalFailed} event (its {@code LedgerBook.failWithdrawal} effect, crediting back
     *  the escrowed amount, needs no {@code coinId}/{@code height}), recording {@code reason} only
     *  in the read model. */
    public void failWithdrawalOnChain(String requestId, String reason, long nowMs) {
        lockWrite();
        try {
            if (!withdrawalAwaitingChain(requestId)) {
                return;
            }
            GameEvent.WithdrawalFailed event = new GameEvent.WithdrawalFailed(requestId, nowMs);
            persist(event, nowMs);
            eventApplier.apply(event);
            db.tx(c -> {
                withdrawalDeposits.markWithdrawalFailed(c, requestId, reason, nowMs);
                return null;
            });
        } finally {
            engineLock.writeLock().unlock();
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
        lockWrite();
        try {
            GameEvent.DepositConfirmedOnChain event =
                    new GameEvent.DepositConfirmedOnChain(playerId, playerIndex, amount.mojos(), coinId, height, nowMs);
            if (!db.tx(c -> {
                if (!depositConfirmations.tryReserve(c, coinId, playerId, playerIndex, amount.mojos(), height, nowMs)) {
                    return false;
                }
                eventStore.append(c, event, nowMs);
                return true;
            })) {
                return false;
            }
            try {
                eventApplier.apply(event);
            } catch (RuntimeException | Error e) {
                // committed and reserved: no later offer of the coin can credit it, so the rebuild must (it replays it)
                rebuildService.recoverAfterFailure(e, "deposit " + coinId, nowMs);
                throw e;
            }
            return true;
        } finally {
            engineLock.writeLock().unlock();
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
        lockWrite();
        try {
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
            persist(event, nowMs);
            crowdvsmarket.engine.ledger.LedgerCorrection correction =
                    (crowdvsmarket.engine.ledger.LedgerCorrection) eventApplier.apply(event);
            return toLedgerCorrectionDto(correction);
        } finally {
            engineLock.writeLock().unlock();
        }
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
        lockWrite();
        try {
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
            persist(event, nowMs);
            crowdvsmarket.engine.ledger.LedgerCorrection correction =
                    (crowdvsmarket.engine.ledger.LedgerCorrection) eventApplier.apply(event);
            return toLedgerCorrectionDto(correction);
        } finally {
            engineLock.writeLock().unlock();
        }
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
        lockRead();
        try {
            List<crowdvsmarket.engine.ledger.LedgerCorrection> all = hub.ledger().corrections();
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
        } finally {
            engineLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Crowd account (Р-5, Р-14, Р-17, Р-25, Р-26)
    // ---------------------------------------------------------------------------------------

    /** One market's crowd account (Т-6): its own capital, its own chart, its own "hold the asset" and
     *  "anti-crowd" lines. {@code null}: BTC. */
    public Crowd.CrowdAccountDto crowdAccount(String marketIdOrNull) {
        Market market = requireMarket(marketIdOrNull);
        lockRead();
        try {
            EngineHub.MarketEngines engines = hub.ensureMarket(market);
            List<CrowdAccount.RoundResult> history = engines.crowd().history();
            List<BigDecimal> hold = engines.comparison().holdHistory();
            List<BigDecimal> antiCrowd = engines.comparison().antiCrowdHistory();
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
            return new Crowd.CrowdAccountDto(market.id(), market.symbol(), engines.crowd().capital().toPlainString(),
                    points, seasonReturns);
        } finally {
            engineLock.readLock().unlock();
        }
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

    public Ratings.RatingsDto ratings(String seasonIdOrNull) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        lockRead();
        try {
            RatingBook ratingBook = new RatingBook(hub.ledger());
            Map<String, PlayerIdentity> identities = players.identitiesById();
            List<RatingBook.RichEntry> rich = ratingBook.richList(season, identities);
            List<RatingBook.ProphetEntry> prophets = ratingBook.prophetList(season, identities);
            List<Ratings.RichEntryDto> richDtos = new ArrayList<>();
            for (int i = 0; i < rich.size(); i++) {
                RatingBook.RichEntry e = rich.get(i);
                richDtos.add(new Ratings.RichEntryDto(i + 1, summary(e.playerId()), e.growth().toMoney().toPlainString(), e.roundsPlayed()));
            }
            List<Ratings.ProphetEntryDto> prophetDtos = new ArrayList<>();
            for (int i = 0; i < prophets.size(); i++) {
                RatingBook.ProphetEntry e = prophets.get(i);
                prophetDtos.add(new Ratings.ProphetEntryDto(i + 1, summary(e.playerId()), e.accuracy().doubleValue(),
                        e.eligibleRounds(), e.roundsPlayed()));
            }
            return new Ratings.RatingsDto(season.id(), RatingBook.DEFAULT_ROUNDS_THRESHOLD, richDtos, prophetDtos);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Profiles (Р-15, Р-29)
    // ---------------------------------------------------------------------------------------

    private static final int PROFILE_HISTORY_LIMIT = 100; // предложение — contract.ts leaves this unpaginated/uncapped

    public Identity.PublicProfileDto publicProfile(String playerId) {
        ensurePlayerKnown(playerId);
        Identity.PlayerSummaryDto player = summary(playerId);
        Ledger.SeasonRecordDto seasonRecord;
        lockRead();
        try {
            seasonRecord = hub.ledger().seasonRecord(playerId, Season.of(Instant.now()))
                    .map(sr -> new Ledger.SeasonRecordDto(sr.season().id(), sr.baselineBalance().toMoney().toPlainString(),
                            sr.deposited().toMoney().toPlainString(), sr.withdrawn().toMoney().toPlainString(),
                            sr.roundsPlayed(), sr.directionEligibleRounds(), sr.directionCorrectRounds()))
                    .orElse(null);
        } finally {
            engineLock.readLock().unlock();
        }
        List<Identity.ProfileRoundEntryDto> history = roundResults.playerHistory(playerId, PROFILE_HISTORY_LIMIT);
        // over every settled round of the player, not just the page above (Р-71)
        WinStreak.Result streak = roundResults.playerStreak(playerId);
        return new Identity.PublicProfileDto(player, seasonRecord, history,
                new Identity.StreakDto(streak.current(), streak.best()));
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
    // Home aggregate (Р-34)
    // ---------------------------------------------------------------------------------------

    private static final int HOME_TOP_N = 5; // предложение — not fixed by contract.ts

    /** {@code home} keeps describing BTC (docs/research/markets_architecture.md §8): its pairs, its crowd
     *  account; the other markets have their own calls. BTC is {@link #defaultMarket}: after its price moved to
     *  another source, the market that took it over. */
    public Home.HomeSummaryDto home(String viewerPlayerIdOrNull) {
        // Held for the whole aggregate (each call below re-acquires this SAME read lock, which is
        // reentrant) so home() sees one consistent snapshot across rounds/crowd/ratings, not four
        // independent ones a concurrent write could interleave between.
        lockRead();
        try {
            String bitcoin = defaultMarket().id();
            Rounds.RoundsPairDto hourly = roundsPair(Enums.Interval.HOURLY, bitcoin, viewerPlayerIdOrNull);
            Rounds.RoundsPairDto daily = roundsPair(Enums.Interval.DAILY, bitcoin, viewerPlayerIdOrNull);
            Crowd.CrowdAccountDto crowd = crowdAccount(bitcoin);
            Ratings.RatingsDto ratings = ratings(null);
            List<Ratings.RichEntryDto> topRich = ratings.rich().stream().limit(HOME_TOP_N).toList();
            List<Ratings.ProphetEntryDto> topProphets = ratings.prophets().stream().limit(HOME_TOP_N).toList();
            return new Home.HomeSummaryDto(hourly, daily, crowd, topRich, topProphets);
        } finally {
            engineLock.readLock().unlock();
        }
    }
}
