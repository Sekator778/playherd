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
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.player.PlayerIdentity;
import crowdvsmarket.engine.rating.RatingBook;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.PositionSizing;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.engine.season.Season;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.ComparisonAccounts;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
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
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.price.PriceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * {@code EngineRebuildService.rebuild()} — which discards and fully repopulates {@link EngineHub}
 * from scratch — takes the SAME write lock via {@link #runExclusively}, for the same reason a
 * rebuild used to run inside {@code synchronized(gameService)}: it must never interleave with any
 * other read or write here either.
 */
@ApplicationScoped
public class GameService {

    @Inject
    GameConfig config;
    @Inject
    Db db;
    @Inject
    EngineHub hub;
    @Inject
    ComparisonAccounts comparisonAccounts;
    @Inject
    EventStore eventStore;
    @Inject
    EventApplier eventApplier;
    @Inject
    PlayerDirectory players;
    @Inject
    RoundProgress roundProgress;
    @Inject
    PriceService priceService;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    crowdvsmarket.server.chain.OracleStatusCache oracleStatusCache;
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

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final Logger LOG = Logger.getLogger(GameService.class);
    /** Р-53: минимум для настоящего on-chain вывода (предложение — не зафиксировано в
     *  docs/product/decisions.md; та же цифра, что {@code LedgerBook.MIN_STAKE}, но отдельная по
     *  смыслу константа: одно — минимальная ставка, другое — минимальный вывод, у них нет причины
     *  всегда совпадать, просто сегодня совпадают). Ниже {@code MIN_WITHDRAWAL} чейновая комиссия
     *  батч-спенда чейнсвика могла бы съесть непропорционально большую долю выведенной суммы. */
    private static final PlayAmount MIN_WITHDRAWAL = PlayAmount.ofWholePlay(100);

    /** See the class javadoc's "Locking" section. */
    private final java.util.concurrent.locks.ReadWriteLock engineLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    /** Used only by {@code EngineRebuildService.rebuild()}: runs {@code action} holding this
     *  class's write lock, the same exclusive-access guarantee every mutating method below gets by
     *  holding it themselves — a rebuild's {@code hub.resetEmpty()} + full event replay must never
     *  interleave with any other read or write of {@link EngineHub} through this class. */
    public void runExclusively(Runnable action) {
        engineLock.writeLock().lock();
        try {
            action.run();
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Round id helpers
    // ---------------------------------------------------------------------------------------

    private record RoundIdParts(crowdvsmarket.engine.market.Interval interval, long startMs) {
    }

    private RoundIdParts parseRoundId(String roundId) {
        int dash = roundId.indexOf('-');
        if (dash < 0) {
            throw ApiException.badRequest("malformed roundId: " + roundId);
        }
        String code = roundId.substring(0, dash);
        crowdvsmarket.engine.market.Interval interval = switch (code) {
            case "1h" -> crowdvsmarket.engine.market.Interval.HOURLY;
            case "1d" -> crowdvsmarket.engine.market.Interval.DAILY;
            default -> throw ApiException.badRequest("malformed roundId: " + roundId);
        };
        long startMs;
        try {
            startMs = Long.parseLong(roundId.substring(dash + 1));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("malformed roundId: " + roundId);
        }
        return new RoundIdParts(interval, startMs);
    }

    private RoundWindow windowFor(String roundId) {
        RoundIdParts parts = parseRoundId(roundId);
        long durationMs = config.roundDurations().forInterval(parts.interval());
        return new RoundWindow(parts.interval(), parts.startMs(), parts.startMs() + durationMs);
    }

    private static Enums.Interval toDto(crowdvsmarket.engine.market.Interval interval) {
        return Enums.Interval.valueOf(interval.name());
    }

    private static crowdvsmarket.engine.market.Interval toEngine(Enums.Interval interval) {
        return crowdvsmarket.engine.market.Interval.valueOf(interval.name());
    }

    private static Enums.Direction toDto(crowdvsmarket.engine.round.Direction d) {
        return Enums.Direction.valueOf(d.name());
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
     * {@code PlayerRegistrationIdempotencyTest}. */
    public record PlayerTouchResult(Identity.PlayerSummaryDto player, boolean isNewPlayer) {
    }

    public PlayerTouchResult registerOrTouchPlayer(String walletAddress, long nowMs) {
        engineLock.writeLock().lock();
        try {
            Optional<String> existing = players.playerIdForWallet(walletAddress);
            String playerId;
            boolean isNew = existing.isEmpty();
            if (isNew) {
                playerId = UUID.randomUUID().toString();
                String finalPlayerId = playerId;
                int playerIndex = serverMeta.reserveNextPlayerIndex();
                db.tx(c -> {
                    players.insert(c, finalPlayerId, walletAddress, null, false, null, playerIndex, nowMs);
                    return null;
                });
                appendPlayerRegistered(playerId, nowMs);
            } else {
                playerId = existing.get();
                Season season = Season.of(Instant.ofEpochMilli(nowMs));
                boolean seasonAlreadyTouched = hub.ledger().seasonRecord(playerId, season).isPresent();
                if (!seasonAlreadyTouched) {
                    appendPlayerRegistered(playerId, nowMs);
                }
            }
            return new PlayerTouchResult(summary(playerId), isNew);
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
        engineLock.writeLock().lock();
        try {
            String playerId = "bot-" + strategy.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID();
            String walletAddress = "txbot1" + playerId.replace("-", "").substring(0, Math.min(40, playerId.length()));
            int playerIndex = serverMeta.reserveNextPlayerIndex();
            db.tx(c -> {
                players.insert(c, playerId, walletAddress, nickname, true, strategy, playerIndex, nowMs);
                return null;
            });
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

    public synchronized Identity.PlayerSummaryDto setNickname(String playerId, String nickname) {
        String trimmed = nickname == null ? "" : nickname.strip();
        if (!NICKNAME_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.invalidNickname(
                    "nickname must be 3-24 characters: letters, digits, space, '_', '.', '-' only");
        }
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
        db.tx(c -> {
            players.updateNickname(c, playerId, trimmed);
            return null;
        });
        return summary(playerId);
    }

    // ---------------------------------------------------------------------------------------
    // Betting (Р-8, Р-13, Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public Rounds.BetDto placeBet(String playerId, Betting.PlaceBetRequest req, long nowMs) {
        RoundIdParts parts = parseRoundId(req.roundId());
        engineLock.writeLock().lock();
        try {
            RoundWindow bettingWindow = roundProgress.bettingWindow(parts.interval());
            // Bug fix (wave-2 MINOR finding): reject strictly by wall-clock time against the
            // window's own startMs, not only by roundId equality against RoundProgress's cursor.
            // RoundProgress only advances when RoundSchedulerService's tick (every
            // herd.scheduler.tick-ms, ~1s) actually resolves the previous round — so for up to that
            // ~1s after a round has, by definition, already started (now >= bettingWindow.startMs()),
            // the cursor could still name this same roundId as "open for betting" and let a late bet
            // slip in. Comparing nowMs (the request's own arrival time, not a re-read of
            // System.currentTimeMillis() here) closes that gap regardless of scheduler timing.
            if (!bettingWindow.roundId().equals(req.roundId()) || nowMs >= bettingWindow.startMs()) {
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
                leverage = new Leverage(req.leverage());
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
            GameEngine engine = hub.engine(parts.interval());
            PlayAmount currentBalance = ledger.balance(playerId);
            Optional<Bet> existing = engine.betBook().betsFor(req.roundId()).stream()
                    .filter(b -> b.playerId().equals(playerId)).findFirst();
            PlayAmount effectiveBalance = existing.map(b -> currentBalance.plus(b.stake())).orElse(currentBalance);
            if (stake.compareTo(effectiveBalance) > 0) {
                throw ApiException.insufficientBalance("stake " + stake + " exceeds balance " + effectiveBalance);
            }

            GameEvent.BetPlaced event = new GameEvent.BetPlaced(playerId, parts.interval(), bettingWindow.startMs(),
                    bettingWindow.endMs(), toEngine(req.direction()), req.leverage(), stake.mojos(), nowMs);
            persist(event, nowMs);
            eventApplier.apply(event);
            return new Rounds.BetDto(playerId, req.roundId(), req.direction(), req.leverage(), stake.toMoney().toPlainString());
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    public void cancelBet(String playerId, String roundId, long nowMs) {
        RoundIdParts parts = parseRoundId(roundId);
        engineLock.writeLock().lock();
        try {
            RoundWindow bettingWindow = roundProgress.bettingWindow(parts.interval());
            // Same wave-2 exact-boundary fix as placeBet above — a bet must not be cancellable (and
            // its stake refunded) once its round has, by wall-clock time, already started.
            if (!bettingWindow.roundId().equals(roundId) || nowMs >= bettingWindow.startMs()) {
                throw ApiException.roundNotOpenForBetting(roundId);
            }
            GameEvent.BetCancelled event = new GameEvent.BetCancelled(playerId, parts.interval(), bettingWindow.startMs(),
                    bettingWindow.endMs(), nowMs);
            persist(event, nowMs);
            eventApplier.apply(event);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Round resolution — called only by RoundSchedulerService (and the admin cancel path below)
    // ---------------------------------------------------------------------------------------

    /** Resolves {@code window} now: fetches its price path from {@code priceService}, runs the
     *  engine, hashes and stores the result. Idempotent guard ({@code round_results.exists}) is
     *  the caller's job (RoundSchedulerService), so this always actually resolves. */
    public RoundReport resolveRound(RoundWindow window, long nowMs) {
        engineLock.writeLock().lock();
        try {
            RoundPrices prices = priceService.roundPrices();
            BigDecimal openPrice = prices.openPrice(window);
            BigDecimal closePrice = prices.closePrice(window);
            List<Candle> path = prices.path(window);

            GameEvent.RoundResolved event = new GameEvent.RoundResolved(window.interval(), window.startMs(),
                    window.endMs(), openPrice.toPlainString(), closePrice.toPlainString(),
                    EventApplier.fromCandles(path), nowMs);
            persist(event, nowMs);
            RoundReport report = (RoundReport) eventApplier.apply(event);

            long sourceTsMs = path.get(path.size() - 1).openTimeMs();
            storeSettledResult(window, report, sourceTsMs);
            return report;
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    private void storeSettledResult(RoundWindow window, RoundReport report, long sourceTsMs) {
        Map<String, PlayerIdentity> identities = players.identitiesById();
        List<Rounds.PublicPositionDto> positionDtos = new ArrayList<>();
        List<crowdvsmarket.server.util.ResultsHasher.PositionEntry> hashEntries = new ArrayList<>();
        List<RoundResultRepository.PlayerHistoryRow> historyRows = new ArrayList<>();

        for (PlayerRoundOutcome outcome : report.playerOutcomes()) {
            PlayerIdentity identity = identities.get(outcome.playerId());
            Identity.PlayerSummaryDto playerSummary = identity == null
                    ? new Identity.PlayerSummaryDto(outcome.playerId(), outcome.playerId(), "", outcome.playerId(), null)
                    : summary(outcome.playerId());
            BigDecimal entryPrice = report.openPrice();
            BigDecimal size = PositionSizing.sizeForMargin(outcome.stake().toMoney(), new Leverage(outcome.leverage()), entryPrice);
            BigDecimal liquidationPrice = LiquidationCalculator.liquidationPrice(size, entryPrice,
                    new Leverage(outcome.leverage()), toEngine(toDto(outcome.direction())).toSide());
            BigDecimal pnl = outcome.payout().subtract(outcome.stake().toMoney());
            Boolean directionCorrect = directionCorrect(outcome.direction(), report.openPrice(), report.closePrice());

            positionDtos.add(new Rounds.PublicPositionDto(outcome.playerId(), window.roundId(), toDto(outcome.direction()),
                    outcome.leverage(), size.toPlainString(), entryPrice.toPlainString(), liquidationPrice.toPlainString(),
                    pnl.toPlainString(), outcome.stake().toMoney().toPlainString(), playerSummary, outcome.liquidated(),
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
                crowd.entryPrice() == null ? null : crowd.entryPrice().toPlainString(), crowd.liquidated(),
                crowd.capitalBefore().toPlainString(), crowd.capitalAfter().toPlainString());

        crowdvsmarket.server.util.ResultsHasher.CrowdEntry crowdHashEntry = !crowd.traded() ? null
                : new crowdvsmarket.server.util.ResultsHasher.CrowdEntry(toDto(crowd.direction()).name(), crowd.leverage(),
                        crowd.margin().movePointRight(2).longValueExact(),
                        crowd.capitalAfter().subtract(crowd.capitalBefore()).movePointRight(2).longValueExact());

        long openCents = report.openPrice().movePointRight(2).longValueExact();
        long closeCents = report.closePrice().movePointRight(2).longValueExact();
        var canonical = crowdvsmarket.server.util.ResultsHasher.build(window.roundId(), window.interval().name(),
                "SETTLED", openCents, closeCents, hashEntries, crowdHashEntry);

        long settledAtMs = window.endMs() - 1;
        db.tx(c -> {
            roundResults.insertSettled(c, window.roundId(), window.interval(), window.startMs(), window.endMs(),
                    openCents, closeCents, positionDtos, crowdDto, canonical.hash(), canonical.json(), settledAtMs,
                    historyRows);
            return null;
        });

        enqueueOracleRecord(window.roundId(), settledAtMs, sourceTsMs, closeCents, canonical.hash());
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

    /** Р-40/41/47: queues one oracle record for this round's boundary, tagged with the game's
     *  own mode as the record's {@code source} (replay test-mode publishes for real too, Р-47).
     *  Never throws to the caller — {@code OraclePublisherService} does the actual best-effort
     *  chainsvc POST on its own schedule, so a slow/unavailable chainsvc never blocks round
     *  resolution (the row just sits with {@code chainsvc_queued=false} until it succeeds).
     *
     *  @param ts        wall-clock publish time (round settlement instant), ms
     *  @param sourceTsMs the underlying price reading's own timestamp — the real historical
     *                     instant in replay mode (Р-47: "в announcement помечено, что цена из
     *                     повтора истории"), equal to {@code ts} in live mode */
    private void enqueueOracleRecord(String roundId, long ts, long sourceTsMs, long priceCents, String resultsHash) {
        try {
            long seq = serverMeta.reserveOracleSeqRange(1);
            String source = config.mode() == crowdvsmarket.server.config.GameMode.REPLAY ? "replay" : "live";
            oracleRecords.insert(roundId, seq, ts / 1000, priceCents, resultsHash, source, sourceTsMs / 1000,
                    System.currentTimeMillis());
        } catch (Exception e) {
            org.jboss.logging.Logger.getLogger(GameService.class)
                    .warnf(e, "failed to enqueue oracle record for round %s (never blocks the game)", roundId);
        }
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
        engineLock.writeLock().lock();
        try {
            if (roundResults.exists(window.roundId())) {
                throw ApiException.badRequest("round " + window.roundId() + " is already resolved");
            }
            RoundWindow running = roundProgress.next(window.interval());
            RoundWindow betting = roundProgress.bettingWindow(window.interval());
            if (!window.equals(running) && !window.equals(betting)) {
                throw ApiException.badRequest("not the current running or betting round for " + window.interval());
            }

            int refundedBetCount = hub.engine(window.interval()).betBook().betsFor(window.roundId()).size();

            GameEvent.RoundCancelled event = new GameEvent.RoundCancelled(window.interval(), window.startMs(),
                    window.endMs(), reason, nowMs);
            persist(event, nowMs);
            eventApplier.apply(event); // refunds every pending bet and clears the round's BetBook entry

            var canonical = crowdvsmarket.server.util.ResultsHasher.build(window.roundId(), window.interval().name(),
                    "CANCELLED", null, null, List.of(), null);
            db.tx(c -> {
                roundResults.insertCancelled(c, window.roundId(), window.interval(), window.startMs(), window.endMs(),
                        reason, nowMs, refundedBetCount, canonical.hash(), canonical.json());
                return null;
            });
            return new Rounds.CancellationDto(window.roundId(), reason, nowMs, refundedBetCount);
        } finally {
            engineLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Reads: rounds / round (Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public Rounds.RoundsPairDto roundsPair(Enums.Interval intervalDto, String viewerPlayerIdOrNull) {
        crowdvsmarket.engine.market.Interval interval = toEngine(intervalDto);
        engineLock.readLock().lock();
        try {
            RoundWindow runningWindow = roundProgress.next(interval);
            RoundWindow bettingWindow = roundProgress.bettingWindow(interval);
            Rounds.RoundDto running = roundResults.findRoundDto(runningWindow.roundId())
                    .orElseGet(() -> buildRunningDto(runningWindow, System.currentTimeMillis()));
            Rounds.RoundDto betting = roundResults.findRoundDto(bettingWindow.roundId())
                    .orElseGet(() -> buildBettingDto(bettingWindow, viewerPlayerIdOrNull));
            return new Rounds.RoundsPairDto(intervalDto, running, betting);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    public Rounds.RoundDto round(String roundId, String viewerPlayerIdOrNull) {
        Optional<Rounds.RoundDto> settledOrCancelled = roundResults.findRoundDto(roundId);
        if (settledOrCancelled.isPresent()) {
            return settledOrCancelled.get();
        }
        RoundWindow window = windowFor(roundId);
        engineLock.readLock().lock();
        try {
            RoundWindow running = roundProgress.next(window.interval());
            RoundWindow betting = roundProgress.bettingWindow(window.interval());
            if (window.equals(running)) {
                return buildRunningDto(window, System.currentTimeMillis());
            }
            if (window.equals(betting)) {
                return buildBettingDto(window, viewerPlayerIdOrNull);
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

    private Rounds.RoundDto buildBettingDto(RoundWindow window, String viewerPlayerIdOrNull) {
        GameEngine engine = hub.engine(window.interval());
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(window.roundId()));
        Rounds.BetDto myBet = viewerPlayerIdOrNull == null ? null : bets.stream()
                .filter(b -> b.playerId().equals(viewerPlayerIdOrNull))
                .findFirst()
                .map(b -> new Rounds.BetDto(b.playerId(), window.roundId(), toDto(b.direction()), b.leverage().value(),
                        b.stake().toMoney().toPlainString()))
                .orElse(null);
        LiveSplit split = buildLiveSplit(bets);
        List<Rounds.LiveBetDto> recentBets = betFeedStore.latest(window.roundId(), RECENT_BETS_LIMIT).stream()
                .map(e -> new Rounds.LiveBetDto(summaryOrFallback(e.playerId()), e.direction(), e.leverage(),
                        e.stake(), e.atMs()))
                .toList();
        return new Rounds.RoundBetting(window.roundId(), new Rounds.RoundWindowDto(toDto(window.interval()),
                window.startMs(), window.endMs()), bets.size(), myBet, split.upStake(), split.downStake(),
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
    private LiveSplit buildLiveSplit(List<Bet> bets) {
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
            majorityLeverage = weightedLeverage(majorityBets);
        }
        return new LiveSplit(upStake.toPlainString(), downStake.toPlainString(), upCount, downCount,
                majorityDirection, majorityLeverage);
    }

    /** Predложение: a live preview of the currently-running round — revealed positions/crowd entry
     *  (Р-22) computed from the bets still sitting in the engine's {@code BetBook} plus the price
     *  path so far, WITHOUT mutating {@code LedgerBook}/{@code CrowdAccount} (those only actually
     *  change at real settlement, via {@link #resolveRound}). See
     *  docs/research/round_engine_design.md's own note that "RUNNING" is not a stored engine
     *  state. */
    private Rounds.RoundDto buildRunningDto(RoundWindow window, long nowMs) {
        RoundPrices prices = priceService.roundPrices();
        BigDecimal openPrice = prices.openPrice(window);
        long pathEndMs = Math.min(nowMs, window.endMs());
        List<Candle> pathSoFar = pathEndMs <= window.startMs() ? List.of()
                : prices.path(new RoundWindow(window.interval(), window.startMs(), pathEndMs));
        if (pathSoFar.isEmpty()) {
            pathSoFar = List.of(prices.path(window).get(0));
        }

        Map<String, PlayerIdentity> identities = players.identitiesById();
        GameEngine engine = hub.engine(window.interval());
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(window.roundId()));
        List<Rounds.PublicPositionDto> positions = new ArrayList<>();
        BigDecimal upStake = ZERO_MONEY;
        BigDecimal downStake = ZERO_MONEY;
        List<Bet> upBets = new ArrayList<>();
        List<Bet> downBets = new ArrayList<>();
        for (Bet bet : bets) {
            Position position = Position.openFromMargin(bet.playerId(), window.roundId(), bet.direction(),
                    bet.leverage(), bet.stake().toMoney(), openPrice);
            PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, pathSoFar);
            boolean liquidatedSoFar = outcome instanceof PositionSimulator.Outcome.Liquidated;
            BigDecimal pnl = liquidatedSoFar ? null
                    : ((PositionSimulator.Outcome.Settled) outcome).pnl();
            PlayerIdentity identity = identities.get(bet.playerId());
            Identity.PlayerSummaryDto playerSummary = identity == null
                    ? new Identity.PlayerSummaryDto(bet.playerId(), bet.playerId(), "", bet.playerId(), null)
                    : summary(bet.playerId());
            positions.add(new Rounds.PublicPositionDto(bet.playerId(), window.roundId(), toDto(bet.direction()),
                    bet.leverage().value(), position.size().toPlainString(), openPrice.toPlainString(),
                    position.liquidationPrice().toPlainString(), pnl == null ? null : pnl.toPlainString(),
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
            int leverage = weightedLeverage(majorityBets);
            BigDecimal margin = hub.crowdAccount().capital()
                    .multiply(CrowdAccount.MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal liqPrice = LiquidationCalculator.liquidationPrice(
                    PositionSizing.sizeForMargin(margin, new Leverage(leverage), openPrice), openPrice,
                    new Leverage(leverage), majorityDirection.toSide());
            crowdPreview = new Rounds.CrowdRoundPositionDto(window.roundId(), toDto(majorityDirection), leverage,
                    margin.toPlainString(), openPrice.toPlainString(), liqPrice.toPlainString());
        }

        return new Rounds.RoundRunning(window.roundId(), new Rounds.RoundWindowDto(toDto(window.interval()),
                window.startMs(), window.endMs()), openPrice.toPlainString(), positions, crowdPreview);
    }

    private static int weightedLeverage(List<Bet> sideBets) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Bet bet : sideBets) {
            BigDecimal stake = bet.stake().toMoney();
            weightedSum = weightedSum.add(BigDecimal.valueOf(bet.leverage().value()).multiply(stake));
            totalStake = totalStake.add(stake);
        }
        BigDecimal average = weightedSum.divide(totalStake, MathContext.DECIMAL128);
        int rounded = average.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(Leverage.MIN, Math.min(Leverage.MAX, rounded));
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
        engineLock.readLock().lock();
        try {
            crowdvsmarket.server.domain.BetFeed.Result result = betFeedStore.since(roundId, sinceSeq);
            List<Rounds.BetFeedEntryDto> entries = result.entries().stream()
                    .map(e -> new Rounds.BetFeedEntryDto(e.seq(), e.atMs(), e.playerDisplayName(), e.isBot(),
                            e.direction(), e.stake(), e.leverage()))
                    .toList();
            return new Rounds.BetFeedDto(roundId, entries, result.hasMore());
        } finally {
            engineLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Liquidation preview / reference price (Р-7, Р-33)
    // ---------------------------------------------------------------------------------------

    public Betting.MarketReferenceDto btcReferencePrice() {
        PriceService.Reference ref = priceService.currentReference(System.currentTimeMillis());
        return new Betting.MarketReferenceDto(ref.price().setScale(2, RoundingMode.HALF_UP).toPlainString(), ref.asOfMs());
    }

    public Betting.LiquidationPreviewDto previewLiquidation(Betting.LiquidationPreviewRequest req) {
        Leverage leverage;
        try {
            leverage = new Leverage(req.leverage());
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidLeverage(e.getMessage());
        }
        BigDecimal stakeMoney;
        try {
            stakeMoney = PlayAmount.fromMoney(new BigDecimal(req.stake())).toMoney();
        } catch (Exception e) {
            throw ApiException.badRequest("invalid stake: " + req.stake());
        }
        BigDecimal entryPrice = req.referencePrice() != null ? new BigDecimal(req.referencePrice())
                : priceService.currentReference(System.currentTimeMillis()).price();
        entryPrice = entryPrice.setScale(2, RoundingMode.HALF_UP);
        BigDecimal size = PositionSizing.sizeForMargin(stakeMoney, leverage, entryPrice);
        BigDecimal liqPrice = LiquidationCalculator.liquidationPrice(size, entryPrice, leverage, toEngine(req.direction()).toSide());
        double pct = liqPrice.subtract(entryPrice).abs()
                .divide(entryPrice, MathContext.DECIMAL128).doubleValue();
        return new Betting.LiquidationPreviewDto(entryPrice.toPlainString(), liqPrice.toPlainString(), pct);
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
        engineLock.readLock().lock();
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

    /** Bug fix: sum of {@code playerId}'s stakes across every not-yet-settled round of both
     *  series — the BETTING round (bet placed, round not started) and the RUNNING one (position
     *  opened, not yet resolved) each contribute at most one bet (Р-13). */
    private PlayAmount inPlayStake(String playerId) {
        PlayAmount total = PlayAmount.ZERO;
        for (crowdvsmarket.engine.market.Interval interval : crowdvsmarket.engine.market.Interval.values()) {
            GameEngine engine = hub.engine(interval);
            total = total.plus(stakeInRound(engine, roundProgress.next(interval).roundId(), playerId));
            total = total.plus(stakeInRound(engine, roundProgress.bettingWindow(interval).roundId(), playerId));
        }
        return total;
    }

    private static PlayAmount stakeInRound(GameEngine engine, String roundId, String playerId) {
        return engine.betBook().betsFor(roundId).stream()
                .filter(b -> b.playerId().equals(playerId))
                .map(Bet::stake)
                .findFirst()
                .orElse(PlayAmount.ZERO);
    }

    private void ensurePlayerKnown(String playerId) {
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
    }

    public Ledger.SeasonRecordDto seasonRecord(String playerId, String seasonIdOrNull) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        engineLock.readLock().lock();
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
        engineLock.readLock().lock();
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
        engineLock.writeLock().lock();
        try {
            PlayAmount balance = hub.ledger().balance(playerId);
            if (amt.compareTo(balance) > 0) {
                throw ApiException.withdrawalExceedsBalance("withdrawal " + amt + " exceeds balance " + balance);
            }
            toAddress = players.byId(playerId)
                    .orElseThrow(() -> ApiException.notFound("unknown player " + playerId))
                    .walletAddress();
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
        submitWithdrawalToChain(requestId, toAddress, amt.mojos(), nowMs);
        return withdrawalDeposits.findWithdrawal(requestId).orElseThrow();
    }

    /** Best-effort {@code POST /v1/play/withdrawals} — outside {@link #engineLock} on purpose (a
     *  network call has no business holding the in-memory engine lock), and never lets a failure
     *  propagate: the escrow debit already happened and must stand regardless, per the class-level
     *  javadoc's "never block the game on chain confirmation". {@code
     *  WithdrawalPollerService.retrySubmissions} is the safety net for a failure here. */
    private void submitWithdrawalToChain(String requestId, String toAddress, long amountMojos, long nowMs) {
        try {
            ChainSvcClient.PlayWithdrawalSubmitResult result =
                    chainSvc.submitPlayWithdrawal(requestId, toAddress, amountMojos);
            if (result != null && result.accepted()) {
                withdrawalDeposits.markWithdrawalSubmitted(requestId, nowMs);
            }
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc withdrawal submit failed for %s — poller will retry", requestId);
        }
    }

    /** Р-53: {@code WithdrawalPollerService}'s entry point once {@code GET
     *  /v1/play/withdrawals/{requestId}} reports {@code state="confirmed"} — persists {@code
     *  WithdrawalConfirmedOnChain} (engine effect: closes out the request, same as the old {@code
     *  WithdrawalConfirmed} — the escrow debit already happened at request time) and makes {@code
     *  coinId}/{@code height} public in the player's own withdrawal view (task requirement). */
    public void confirmWithdrawalOnChain(String requestId, String coinId, long height, long nowMs) {
        engineLock.writeLock().lock();
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
        engineLock.writeLock().lock();
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
        db.tx(c -> {
            depositAddresses.insert(c, playerId, remote.address(), remote.innerPuzzleHash(), remote.catPuzzleHash(), now);
            return null;
        });
        return new PlayChain.DepositAddressDto(remote.address());
    }

    /**
     * Р-53: {@link crowdvsmarket.server.scheduler.DepositPollerService}'s entry point for one
     * chainsvc-reported deposit coin. Reserves {@code coinId} in {@code deposit_confirmations}
     * FIRST, crediting the balance only if that reservation is the one that actually claimed it —
     * see that repository's javadoc for why this ordering (rather than crediting first) is the
     * deliberate, documented trade-off for a ledger that treats "never mint extra PLAY" as the
     * paramount invariant (task items 1/wave-2's exact class of CRITICAL bug): a crash between the
     * two steps below can strand a coin reserved-but-uncredited (fixable by hand, an operator can
     * look for a {@code deposit_confirmations} row with no matching {@code DEPOSIT} ledger entry),
     * never double-credited.
     *
     * @return {@code true} if this call is the one that credited the deposit; {@code false} if
     *         {@code coinId} was already credited by an earlier call (not an error — chainsvc can
     *         legitimately re-report the same coin across overlapping {@code sinceHeight} windows)
     */
    public boolean creditOnChainDeposit(String playerId, int playerIndex, PlayAmount amount, String coinId,
                                         long height, long nowMs) {
        boolean reserved = depositConfirmations.tryReserve(coinId, playerId, playerIndex, amount.mojos(), height, nowMs);
        if (!reserved) {
            return false;
        }
        engineLock.writeLock().lock();
        try {
            GameEvent.DepositConfirmedOnChain event =
                    new GameEvent.DepositConfirmedOnChain(playerId, playerIndex, amount.mojos(), coinId, height, nowMs);
            persist(event, nowMs);
            eventApplier.apply(event);
        } finally {
            engineLock.writeLock().unlock();
        }
        return true;
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
        engineLock.writeLock().lock();
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
        engineLock.writeLock().lock();
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
        engineLock.readLock().lock();
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

    public Crowd.CrowdAccountDto crowdAccount() {
        engineLock.readLock().lock();
        try {
            List<CrowdAccount.RoundResult> history = hub.crowdAccount().history();
            List<BigDecimal> btcHold = comparisonAccounts.btcHoldHistory();
            List<BigDecimal> antiCrowd = comparisonAccounts.antiCrowdHistory();
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
                        btcHold.get(i).toPlainString(), antiCrowd.get(i).toPlainString()));
            }
            List<Crowd.SeasonReturnDto> seasonReturns = seasonReturns(points);
            return new Crowd.CrowdAccountDto(hub.crowdAccount().capital().toPlainString(), points, seasonReturns);
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
        engineLock.readLock().lock();
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
        engineLock.readLock().lock();
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
        return new Identity.PublicProfileDto(player, seasonRecord, history);
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

    public Home.HomeSummaryDto home(String viewerPlayerIdOrNull) {
        // Held for the whole aggregate (each call below re-acquires this SAME read lock, which is
        // reentrant) so home() sees one consistent snapshot across rounds/crowd/ratings, not four
        // independent ones a concurrent write could interleave between.
        engineLock.readLock().lock();
        try {
            Rounds.RoundsPairDto hourly = roundsPair(Enums.Interval.HOURLY, viewerPlayerIdOrNull);
            Rounds.RoundsPairDto daily = roundsPair(Enums.Interval.DAILY, viewerPlayerIdOrNull);
            Crowd.CrowdAccountDto crowd = crowdAccount();
            Ratings.RatingsDto ratings = ratings(null);
            List<Ratings.RichEntryDto> topRich = ratings.rich().stream().limit(HOME_TOP_N).toList();
            List<Ratings.ProphetEntryDto> topProphets = ratings.prophets().stream().limit(HOME_TOP_N).toList();
            return new Home.HomeSummaryDto(hourly, daily, crowd, topRich, topProphets);
        } finally {
            engineLock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------
    // serverInfo() — additive method
    // ---------------------------------------------------------------------------------------

    public ServerInfo.ServerInfoDto serverInfo() {
        // Bug fix: never call the chainsvc client synchronously here — read the background-
        // refreshed cache instead (OracleStatusCache's own javadoc has the full story).
        ChainSvcClient.OracleStatus status = oracleStatusCache.get();
        int pendingRecords = status != null ? status.pendingRecords() : oracleRecords.pendingCount();
        ServerInfo.OracleInfoDto oracle = new ServerInfo.OracleInfoDto(config.oracleNetwork(), config.oracleLauncherId(),
                status == null ? null : status.lastConfirmedSeq(), status == null ? null : status.lastConfirmedHeight(),
                pendingRecords, status == null ? null : status.operatorBalanceMojos());

        ServerInfo.ReplayInfoDto replay = null;
        if (config.mode() == crowdvsmarket.server.config.GameMode.REPLAY) {
            List<Candle> candles = CandleLoaderCache.get();
            replay = new ServerInfo.ReplayInfoDto(Instant.ofEpochMilli(candles.get(0).openTimeMs()).toString(),
                    Instant.ofEpochMilli(candles.get(candles.size() - 1).closeTimeMs()).toString(), config.replaySpeedup());
        }
        ServerInfo.RoundDurationsDto durationsSec = new ServerInfo.RoundDurationsDto(
                config.roundDurations().forInterval(crowdvsmarket.engine.market.Interval.HOURLY) / 1000,
                config.roundDurations().forInterval(crowdvsmarket.engine.market.Interval.DAILY) / 1000);
        return new ServerInfo.ServerInfoDto(config.mode().name().toLowerCase(java.util.Locale.ROOT), replay,
                durationsSec, config.botsCount(), oracle, System.currentTimeMillis());
    }

    /** Предложение: tiny lazy cache so {@code serverInfo()} does not re-parse the 5 129-row CSV on
     *  every call — {@code PriceService} already loads (and owns) the authoritative copy used for
     *  actual round pricing; this is only for the two ISO timestamps shown in {@code serverInfo}. */
    private static final class CandleLoaderCache {
        private static volatile List<Candle> cached;

        static List<Candle> get() {
            List<Candle> c = cached;
            if (c == null) {
                c = crowdvsmarket.server.replay.CandleLoader.loadFromClasspath("/replay/btc_1m.csv");
                cached = c;
            }
            return c;
        }
    }
}
