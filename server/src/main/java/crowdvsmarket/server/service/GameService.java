package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.game.PlayerRoundOutcome;
import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.ledger.SeasonRecord;
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
 * ({@code RpcResource}). Every mutating method is {@code synchronized} on this singleton: none of
 * {@link EngineHub}'s engine objects are internally thread-safe, and the scheduler
 * (round resolution, bots) and HTTP request threads both call in here concurrently — see
 * {@link EngineHub}'s javadoc.
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
    ChainSvcClient chainSvc;
    @Inject
    crowdvsmarket.server.persistence.ServerMetaRepository serverMeta;

    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

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
     *  (Р-1: "later логины must sign with the same address"). Always re-touches
     *  {@code ensureSeasonPack} via a fresh {@code PlayerRegistered} event, even for a returning
     *  player: that is what keeps {@code LedgerBook}'s own (otherwise unexposed) notion of "the
     *  player's current season" accurate without this server needing its own copy of it — see
     *  {@link #account}'s javadoc. Idempotent to call every login: a season that has not rolled
     *  over since the last touch is a no-op inside {@code ensureSeasonPack} itself. */
    public record PlayerTouchResult(Identity.PlayerSummaryDto player, boolean isNewPlayer) {
    }

    public synchronized PlayerTouchResult registerOrTouchPlayer(String walletAddress, long nowMs) {
        Optional<String> existing = players.playerIdForWallet(walletAddress);
        String playerId;
        boolean isNew = existing.isEmpty();
        if (isNew) {
            playerId = UUID.randomUUID().toString();
            String finalPlayerId = playerId;
            db.tx(c -> {
                players.insert(c, finalPlayerId, walletAddress, null, false, null, nowMs);
                return null;
            });
        } else {
            playerId = existing.get();
        }
        GameEvent.PlayerRegistered event = new GameEvent.PlayerRegistered(playerId, nowMs);
        persist(event, nowMs);
        eventApplier.apply(event);
        return new PlayerTouchResult(summary(playerId), isNew);
    }

    /** Used by {@code BotService} to create the 20 labelled bot players once (Р-46), and (with
     *  {@code isBot=false}) by nothing else — bots skip the wallet-signature flow entirely, they
     *  are server-side only. */
    public synchronized Identity.PlayerSummaryDto createBotPlayer(String nickname, String strategy, long nowMs) {
        String playerId = "bot-" + strategy.toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID();
        String walletAddress = "txbot1" + playerId.replace("-", "").substring(0, Math.min(40, playerId.length()));
        db.tx(c -> {
            players.insert(c, playerId, walletAddress, nickname, true, strategy, nowMs);
            return null;
        });
        persist(new GameEvent.PlayerRegistered(playerId, nowMs), nowMs);
        eventApplier.apply(new GameEvent.PlayerRegistered(playerId, nowMs));
        return summary(playerId);
    }

    public Identity.PlayerSummaryDto summary(String playerId) {
        PlayerDirectory.Row row = players.byId(playerId)
                .orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
        PlayerIdentity identity = row.identity();
        return new Identity.PlayerSummaryDto(playerId, identity.nickname(), row.walletAddress(),
                identity.displayName(), row.isBot());
    }

    public synchronized Identity.PlayerSummaryDto setNickname(String playerId, String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw ApiException.badRequest("nickname must not be blank");
        }
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
        db.tx(c -> {
            players.updateNickname(c, playerId, nickname.strip());
            return null;
        });
        return summary(playerId);
    }

    // ---------------------------------------------------------------------------------------
    // Betting (Р-8, Р-13, Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public synchronized Rounds.BetDto placeBet(String playerId, Betting.PlaceBetRequest req, long nowMs) {
        RoundIdParts parts = parseRoundId(req.roundId());
        RoundWindow bettingWindow = roundProgress.bettingWindow(parts.interval());
        if (!bettingWindow.roundId().equals(req.roundId())) {
            throw ApiException.roundNotOpenForBetting(req.roundId());
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
    }

    public synchronized void cancelBet(String playerId, String roundId, long nowMs) {
        RoundIdParts parts = parseRoundId(roundId);
        RoundWindow bettingWindow = roundProgress.bettingWindow(parts.interval());
        if (!bettingWindow.roundId().equals(roundId)) {
            throw ApiException.roundNotOpenForBetting(roundId);
        }
        GameEvent.BetCancelled event = new GameEvent.BetCancelled(playerId, parts.interval(), bettingWindow.startMs(),
                bettingWindow.endMs(), nowMs);
        persist(event, nowMs);
        eventApplier.apply(event);
    }

    // ---------------------------------------------------------------------------------------
    // Round resolution — called only by RoundSchedulerService (and the admin cancel path below)
    // ---------------------------------------------------------------------------------------

    /** Resolves {@code window} now: fetches its price path from {@code priceService}, runs the
     *  engine, hashes and stores the result. Idempotent guard ({@code round_results.exists}) is
     *  the caller's job (RoundSchedulerService), so this always actually resolves. */
    public synchronized RoundReport resolveRound(RoundWindow window, long nowMs) {
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
                    pnl.toPlainString(), playerSummary, outcome.liquidated(), PlayAmount.fromMoney(outcome.payout()).toMoney().toPlainString(),
                    directionCorrect));

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
     *  {@link #cancelRound(RoundWindow, String, long)}. */
    public synchronized Rounds.CancellationDto cancelRoundById(String roundId, String reason, long nowMs) {
        return cancelRound(windowFor(roundId), reason, nowMs);
    }

    /** Operator/automatic cancellation (Р-23, Р-32). {@code window} must be the currently running
     *  or currently open-for-betting round of its series; already-resolved rounds are rejected. */
    public synchronized Rounds.CancellationDto cancelRound(RoundWindow window, String reason, long nowMs) {
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
    }

    // ---------------------------------------------------------------------------------------
    // Reads: rounds / round (Р-19, Р-22)
    // ---------------------------------------------------------------------------------------

    public Rounds.RoundsPairDto roundsPair(Enums.Interval intervalDto, String viewerPlayerIdOrNull) {
        crowdvsmarket.engine.market.Interval interval = toEngine(intervalDto);
        RoundWindow runningWindow = roundProgress.next(interval);
        RoundWindow bettingWindow = roundProgress.bettingWindow(interval);
        Rounds.RoundDto running = roundResults.findRoundDto(runningWindow.roundId())
                .orElseGet(() -> buildRunningDto(runningWindow, System.currentTimeMillis()));
        Rounds.RoundDto betting = roundResults.findRoundDto(bettingWindow.roundId())
                .orElseGet(() -> buildBettingDto(bettingWindow, viewerPlayerIdOrNull));
        return new Rounds.RoundsPairDto(intervalDto, running, betting);
    }

    public Rounds.RoundDto round(String roundId, String viewerPlayerIdOrNull) {
        Optional<Rounds.RoundDto> settledOrCancelled = roundResults.findRoundDto(roundId);
        if (settledOrCancelled.isPresent()) {
            return settledOrCancelled.get();
        }
        RoundWindow window = windowFor(roundId);
        RoundWindow running = roundProgress.next(window.interval());
        RoundWindow betting = roundProgress.bettingWindow(window.interval());
        if (window.equals(running)) {
            return buildRunningDto(window, System.currentTimeMillis());
        }
        if (window.equals(betting)) {
            return buildBettingDto(window, viewerPlayerIdOrNull);
        }
        throw ApiException.notFound("unknown or not-yet-open round " + roundId);
    }

    private Rounds.RoundDto buildBettingDto(RoundWindow window, String viewerPlayerIdOrNull) {
        GameEngine engine = hub.engine(window.interval());
        List<Bet> bets = new ArrayList<>(engine.betBook().betsFor(window.roundId()));
        Rounds.BetDto myBet = viewerPlayerIdOrNull == null ? null : bets.stream()
                .filter(b -> b.playerId().equals(viewerPlayerIdOrNull))
                .findFirst()
                .map(b -> new Rounds.BetDto(b.playerId(), window.roundId(), toDto(b.direction()), b.leverage().value(),
                        b.stake().toMoney().toPlainString()))
                .orElse(null);
        return new Rounds.RoundBetting(window.roundId(), new Rounds.RoundWindowDto(toDto(window.interval()),
                window.startMs(), window.endMs()), bets.size(), myBet);
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
                    playerSummary, liquidatedSoFar, null, null));
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
     *  which every sign-in already guarantees ({@link #registerOrTouchPlayer} always re-runs
     *  {@code ensureSeasonPack}, see its javadoc), so this is exact for any realistic session. */
    public Ledger.PlayerAccountDto account(String playerId) {
        LedgerBook ledger = hub.ledger();
        ensurePlayerKnown(playerId);
        PlayAmount balance;
        try {
            balance = ledger.balance(playerId);
        } catch (java.util.NoSuchElementException e) {
            balance = PlayAmount.ZERO;
        }
        return new Ledger.PlayerAccountDto(playerId, balance.toMoney().toPlainString(), Season.of(Instant.now()).id());
    }

    private void ensurePlayerKnown(String playerId) {
        players.byId(playerId).orElseThrow(() -> ApiException.notFound("unknown player " + playerId));
    }

    public Ledger.SeasonRecordDto seasonRecord(String playerId, String seasonIdOrNull) {
        Season season = seasonIdOrNull == null ? Season.of(Instant.now()) : parseSeason(seasonIdOrNull);
        Optional<SeasonRecord> record = hub.ledger().seasonRecord(playerId, season);
        return record.map(sr -> new Ledger.SeasonRecordDto(sr.season().id(), sr.baselineBalance().toMoney().toPlainString(),
                        sr.deposited().toMoney().toPlainString(), sr.withdrawn().toMoney().toPlainString(),
                        sr.roundsPlayed(), sr.directionEligibleRounds(), sr.directionCorrectRounds()))
                .orElseGet(() -> new Ledger.SeasonRecordDto(season.id(), "0.000", "0.000", "0.000", 0, 0, 0));
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
    }

    // ---------------------------------------------------------------------------------------
    // Withdraw / deposit (Р-6) — OnChainWalletPort stub: predложение, auto-confirmed immediately.
    // Real CAT bridging is out of scope (no such endpoint in FIXED INTERFACE 3); see
    // docs/research/server_design.md.
    // ---------------------------------------------------------------------------------------

    public synchronized Ledger.WithdrawalRequestDto requestWithdrawal(String playerId, String amount, long nowMs) {
        PlayAmount amt;
        try {
            amt = PlayAmount.fromMoney(new BigDecimal(amount));
        } catch (Exception e) {
            throw ApiException.badRequest("invalid amount: " + amount);
        }
        PlayAmount balance = hub.ledger().balance(playerId);
        if (amt.compareTo(balance) > 0) {
            throw ApiException.withdrawalExceedsBalance("withdrawal " + amt + " exceeds balance " + balance);
        }
        GameEvent.WithdrawalRequested event = new GameEvent.WithdrawalRequested(playerId, amt.mojos(), nowMs);
        persist(event, nowMs);
        // LedgerBook assigns its own "w-N" id inside requestWithdrawal (a single counter shared
        // with deposits' "d-N") — we reuse THAT id as our own external requestId rather than
        // minting a separate one, precisely so replaying this same event later (same call, same
        // position in the event order) reproduces the identical id deterministically; see
        // GameEvent.WithdrawalRequested's javadoc and EventApplier's WithdrawalRequested case.
        String requestId = (String) eventApplier.apply(event);
        db.tx(c -> {
            withdrawalDeposits.insertWithdrawal(c, requestId, playerId, amt.mojos(), nowMs);
            return null;
        });
        confirmWithdrawalNow(requestId, nowMs);
        return withdrawalDeposits.findWithdrawal(requestId).orElseThrow();
    }

    private void confirmWithdrawalNow(String requestId, long nowMs) {
        GameEvent.WithdrawalConfirmed event = new GameEvent.WithdrawalConfirmed(requestId, nowMs);
        persist(event, nowMs);
        eventApplier.apply(event);
        db.tx(c -> {
            withdrawalDeposits.updateWithdrawalState(c, requestId, Enums.WithdrawalState.CONFIRMED);
            return null;
        });
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

    public synchronized Ledger.DepositRequestDto requestDeposit(String playerId, String amount, long nowMs) {
        PlayAmount amt;
        try {
            amt = PlayAmount.fromMoney(new BigDecimal(amount));
        } catch (Exception e) {
            throw ApiException.badRequest("invalid amount: " + amount);
        }
        GameEvent.DepositRequested event = new GameEvent.DepositRequested(playerId, amt.mojos(), nowMs);
        persist(event, nowMs);
        String requestId = (String) eventApplier.apply(event); // same LedgerBook-id reuse as requestWithdrawal
        db.tx(c -> {
            withdrawalDeposits.insertDeposit(c, requestId, playerId, amt.mojos(), nowMs);
            return null;
        });
        confirmDepositNow(requestId, nowMs);
        return withdrawalDeposits.findDeposit(requestId).orElseThrow();
    }

    private void confirmDepositNow(String requestId, long nowMs) {
        GameEvent.DepositConfirmed event = new GameEvent.DepositConfirmed(requestId, nowMs);
        persist(event, nowMs);
        eventApplier.apply(event);
        db.tx(c -> {
            withdrawalDeposits.updateDepositState(c, requestId, Enums.DepositState.CONFIRMED);
            return null;
        });
    }

    /** Same ownership note as {@link #withdrawal}. */
    public Ledger.DepositRequestDto deposit(String requestingPlayerId, String requestId) {
        Ledger.DepositRequestDto dto = withdrawalDeposits.findDeposit(requestId)
                .orElseThrow(() -> ApiException.notFound("unknown deposit request " + requestId));
        if (!dto.playerId().equals(requestingPlayerId)) {
            throw ApiException.notFound("unknown deposit request " + requestId);
        }
        return dto;
    }

    // ---------------------------------------------------------------------------------------
    // Crowd account (Р-5, Р-14, Р-17, Р-25, Р-26)
    // ---------------------------------------------------------------------------------------

    public Crowd.CrowdAccountDto crowdAccount() {
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
    }

    // ---------------------------------------------------------------------------------------
    // Profiles (Р-15, Р-29)
    // ---------------------------------------------------------------------------------------

    private static final int PROFILE_HISTORY_LIMIT = 100; // предложение — contract.ts leaves this unpaginated/uncapped

    public Identity.PublicProfileDto publicProfile(String playerId) {
        ensurePlayerKnown(playerId);
        Identity.PlayerSummaryDto player = summary(playerId);
        Ledger.SeasonRecordDto seasonRecord = hub.ledger().seasonRecord(playerId, Season.of(Instant.now()))
                .map(sr -> new Ledger.SeasonRecordDto(sr.season().id(), sr.baselineBalance().toMoney().toPlainString(),
                        sr.deposited().toMoney().toPlainString(), sr.withdrawn().toMoney().toPlainString(),
                        sr.roundsPlayed(), sr.directionEligibleRounds(), sr.directionCorrectRounds()))
                .orElse(null);
        List<Identity.ProfileRoundEntryDto> history = roundResults.playerHistory(playerId, PROFILE_HISTORY_LIMIT);
        return new Identity.PublicProfileDto(player, seasonRecord, history);
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
        Rounds.RoundsPairDto hourly = roundsPair(Enums.Interval.HOURLY, viewerPlayerIdOrNull);
        Rounds.RoundsPairDto daily = roundsPair(Enums.Interval.DAILY, viewerPlayerIdOrNull);
        Crowd.CrowdAccountDto crowd = crowdAccount();
        Ratings.RatingsDto ratings = ratings(null);
        List<Ratings.RichEntryDto> topRich = ratings.rich().stream().limit(HOME_TOP_N).toList();
        List<Ratings.ProphetEntryDto> topProphets = ratings.prophets().stream().limit(HOME_TOP_N).toList();
        return new Home.HomeSummaryDto(hourly, daily, crowd, topRich, topProphets);
    }

    // ---------------------------------------------------------------------------------------
    // serverInfo() — additive method
    // ---------------------------------------------------------------------------------------

    public ServerInfo.ServerInfoDto serverInfo() {
        ChainSvcClient.OracleStatus status = chainSvc.oracleStatus();
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
