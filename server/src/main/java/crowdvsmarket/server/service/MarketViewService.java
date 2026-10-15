package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.RoundPriceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read model of the markets (docs/research/markets_architecture.md §8; docs/research/server_design.md
 * §20): the answers of the RPCs {@code markets}, {@code myPositions} and {@code crowdOverview}, built from
 * memory only — the market registry, the engine's bet books and crowd accounts, {@code RoundProgress}, the
 * price buffer — and never from the database or the network. It sits beside {@code GameService} instead of in
 * it, and reads the engine through its small read API ({@link GameService#readEngine}: the engine's READ
 * lock, 503 {@code ENGINE_NOT_READY} while the memory cannot be trusted).
 *
 * <p><b>{@code markets} is polled</b> every 2–3 s by every open lobby, so the assembled answer is kept for
 * {@link #CACHE_MS} (about a second) and handed to everybody who asks meanwhile; only {@code serverTimeMs}
 * is the caller's own. What is expensive to count — a crowd account's rounds traded and won — is counted
 * once per round ({@link CrowdStatsMemo}), not once per call. One rebuild of the answer takes the read lock
 * once; callers that arrive during it wait for it instead of building their own. A cached answer is never
 * given while the engine is not ready.
 *
 * <p><b>What is listed.</b> The markets that are played on this server — all of them in {@code mode=live},
 * BTC alone in {@code mode=replay} (whatever else the registry holds) — that are active, plus those closed
 * within the last {@link #CLOSED_LISTED_MS} (10 minutes), in listing order; the categories are the non-empty
 * ones in {@link Category#ORDER}, counted by ACTIVE markets. A market whose rounds are not running — closed,
 * or just listed and not yet seeded — has an empty {@code series}.
 */
@ApplicationScoped
public class MarketViewService {

    /** How long the assembled {@code markets} answer is reused (the brief: "about a second"). */
    static final long CACHE_MS = 1_000L;
    /** A closed market stays in {@code markets} this long, so that its card can say why it left (contract.ts). */
    static final long CLOSED_LISTED_MS = 600_000L;

    private final GameService gameService;
    private final GameConfig config;
    private final MarketRegistry marketRegistry;
    private final EngineHub hub;
    private final RoundProgress roundProgress;
    private final OpenBets openBets;
    private final CrowdStatsMemo crowdStats = new CrowdStatsMemo();

    private record Cached(long builtAtMs, Markets.MarketsDto dto) {
    }

    private final Object marketsLock = new Object();
    private volatile Cached marketsCache;

    @Inject
    public MarketViewService(GameService gameService, GameConfig config, MarketRegistry marketRegistry,
                             EngineHub hub, RoundProgress roundProgress, OpenBets openBets) {
        this.gameService = gameService;
        this.config = config;
        this.marketRegistry = marketRegistry;
        this.hub = hub;
        this.roundProgress = roundProgress;
        this.openBets = openBets;
    }

    // ---------------------------------------------------------------------------------------
    // markets
    // ---------------------------------------------------------------------------------------

    /** The lobby as of {@code nowMs}; see the class javadoc for what is listed and how it is cached. */
    public Markets.MarketsDto markets(long nowMs) {
        gameService.requireEngineReady();
        Markets.MarketsDto cached = fresh(nowMs);
        if (cached != null) {
            return cached;
        }
        synchronized (marketsLock) {
            cached = fresh(nowMs);
            if (cached != null) {
                return cached; // somebody else rebuilt it while this call waited
            }
            Markets.MarketsDto built = gameService.readEngine(() -> assembleMarkets(nowMs));
            marketsCache = new Cached(nowMs, built);
            return built;
        }
    }

    /** Drops the cached {@code markets} answer: the next call assembles a new one. For tests, and for an
     *  operator action that wants to be seen at once. */
    public void invalidate() {
        marketsCache = null;
    }

    /** The cached answer re-stamped with the caller's time, or {@code null} when there is none young enough. */
    private Markets.MarketsDto fresh(long nowMs) {
        Cached cached = marketsCache;
        if (cached == null || Math.abs(nowMs - cached.builtAtMs()) >= CACHE_MS) {
            return null;
        }
        return new Markets.MarketsDto(nowMs, cached.dto().categories(), cached.dto().markets());
    }

    private Markets.MarketsDto assembleMarkets(long nowMs) {
        List<Interval> intervals = intervalsShortestFirst();
        Map<String, Integer> activeByCategory = new HashMap<>();
        List<Markets.MarketDto> shown = new ArrayList<>();
        for (Market market : playedMarkets()) {
            if (market.isActive()) {
                activeByCategory.merge(market.category(), 1, Integer::sum);
            }
            if (market.isActive() || closedRecently(market, nowMs)) {
                shown.add(toDto(market, intervals, nowMs));
            }
        }
        List<Markets.CategoryDto> categories = Category.ORDER.stream()
                .filter(c -> activeByCategory.containsKey(c.id()))
                .map(c -> new Markets.CategoryDto(c.id(), c.title(), activeByCategory.get(c.id())))
                .toList();
        return new Markets.MarketsDto(nowMs, categories, List.copyOf(shown));
    }

    /** The registry's markets that are played here, in listing order: all in live mode, BTC in replay mode. */
    private List<Market> playedMarkets() {
        return marketRegistry.all().stream().filter(gameService::runs).toList();
    }

    private static boolean closedRecently(Market market, long nowMs) {
        return market.closedAtMs() != null && nowMs - market.closedAtMs() <= CLOSED_LISTED_MS;
    }

    /** The two series, the shorter first — their names are historical ({@code Interval}), their lengths are what counts. */
    private List<Interval> intervalsShortestFirst() {
        return Arrays.stream(Interval.values())
                .sorted(Comparator.comparingLong(i -> config.roundDurations().forInterval(i)))
                .toList();
    }

    private Markets.MarketDto toDto(Market market, List<Interval> intervals, long nowMs) {
        Optional<EngineHub.MarketEngines> engines = hub.findMarket(market.id());
        Markets.MarketPriceDto price = gameService.latestQuote(market, nowMs)
                .map(quote -> MarketMapper.price(market, quote)).orElse(null);
        return new Markets.MarketDto(market.id(), Enums.MarketKind.valueOf(market.kind().name()), market.category(),
                market.symbol(), market.title(), market.subtitle(), market.iconUrl(), market.maxLeverage(),
                market.priceDecimals(), Enums.MarketStatus.valueOf(market.status().name()), market.endsAtMs(),
                MarketMapper.eventStartsAtMs(market), market.closedAtMs(), market.closeReason(),
                market.oracleIndex(), market.source(),
                Instruments.backupOf(market).map(Instruments.Backup::source).orElse(null), price,
                series(market, engines, intervals), crowdBrief(market, engines));
    }

    private List<Markets.MarketSeriesDto> series(Market market, Optional<EngineHub.MarketEngines> engines,
                                                 List<Interval> intervals) {
        if (engines.isEmpty()) {
            return List.of();
        }
        List<Markets.MarketSeriesDto> out = new ArrayList<>();
        for (Interval interval : intervals) {
            Optional<RoundWindow> cursor = roundProgress.findNext(market.id(), interval);
            if (cursor.isEmpty()) {
                continue; // closed, not seeded yet, or not played here
            }
            RoundWindow running = cursor.get();
            RoundWindow betting = RoundClock.next(running);
            MarketMapper.Split split = MarketMapper.split(
                    engines.get().engine(interval).betBook().betsFor(betting.roundId()));
            out.add(new Markets.MarketSeriesDto(Enums.Interval.valueOf(interval.name()),
                    (running.endMs() - running.startMs()) / 1000L, betting.roundId(), betting.startMs(),
                    running.roundId(), running.endMs(), split.participantCount(),
                    split.upStake().toMoney().toPlainString(), split.downStake().toMoney().toPlainString(),
                    split.majorityDirection(), market.economy().capFor(interval),
                    market.economy().herdFeeBp().stripTrailingZeros().toPlainString()));
        }
        return out;
    }

    private Markets.MarketCrowdBriefDto crowdBrief(Market market, Optional<EngineHub.MarketEngines> engines) {
        if (engines.isEmpty()) {
            return new Markets.MarketCrowdBriefDto(CrowdAccount.STARTING_CAPITAL.toPlainString(), 0.0, 0, 0, null);
        }
        CrowdAccount crowd = engines.get().crowd();
        CrowdStatsMemo.Stats stats = crowdStats.of(market.id(), crowd);
        BigDecimal capital = crowd.capital();
        return new Markets.MarketCrowdBriefDto(capital.toPlainString(), MarketMapper.returnPct(capital),
                stats.roundsTraded(), stats.roundsWon(), stats.lastOutcome());
    }

    // ---------------------------------------------------------------------------------------
    // myPositions
    // ---------------------------------------------------------------------------------------

    /**
     * Every pending bet ({@code BETTING}) and every open position ({@code RUNNING}) of the player across all
     * markets and both series ({@link OpenBets}: the same walk that makes {@code PlayerAccountDto.inPlay} —
     * so that is the sum of the stakes listed here), soonest-ending first (the web keeps this order). A
     * running position's {@code entryPrice}, {@code liquidationPrice}, {@code size} and {@code liquidated}
     * come from the prices so far of its round ({@link GameService#soFar}: memory only); while the opening
     * price is not known they are {@code null} and {@code liquidated} is false.
     */
    public List<Markets.MyPositionDto> myPositions(String playerId, long nowMs) {
        return gameService.readEngine(() -> positionsOf(playerId, nowMs));
    }

    private List<Markets.MyPositionDto> positionsOf(String playerId, long nowMs) {
        List<Markets.MyPositionDto> out = new ArrayList<>();
        for (OpenBets.Entry entry : openBets.of(playerId)) {
            Optional<Market> market = marketRegistry.byId(entry.marketId());
            if (market.isEmpty()) {
                continue; // markets are never deleted: cannot happen, and nothing to label it with
            }
            out.add(position(market.get(), entry, nowMs));
        }
        out.sort(Comparator.comparingLong(Markets.MyPositionDto::untilMs)
                .thenComparing(Markets.MyPositionDto::marketId)
                .thenComparing(Markets.MyPositionDto::interval)
                .thenComparing(Markets.MyPositionDto::phase, Comparator.reverseOrder())); // RUNNING before BETTING
        return out;
    }

    private Markets.MyPositionDto position(Market market, OpenBets.Entry entry, long nowMs) {
        RoundWindow window = entry.window();
        boolean running = entry.phase() == Enums.RoundPhase.RUNNING;
        String entryPrice = null;
        String liquidationPrice = null;
        String size = null;
        boolean liquidated = false;
        if (running) {
            Optional<RoundPriceData> prices = gameService.soFar(market, window, nowMs);
            if (prices.isPresent()) {
                BigDecimal open = prices.get().open();
                List<Candle> path = prices.get().path().isEmpty()
                        ? List.of(GameService.singleCandle(window, open, open)) : prices.get().path();
                Position position = Position.openFromMargin(entry.bet().playerId(), window.roundId(),
                        entry.bet().direction(), entry.bet().leverage(), entry.bet().stake().toMoney(), open,
                        entry.bet().specFor(market.spec(), window.interval()));
                entryPrice = market.rawString(open);
                liquidationPrice = market.rawString(position.liquidationPrice());
                size = position.size().toPlainString();
                liquidated = PositionSimulator.simulate(position, path) instanceof PositionSimulator.Outcome.Liquidated;
            }
        }
        return new Markets.MyPositionDto(market.id(), market.symbol(), window.roundId(),
                Enums.Interval.valueOf(entry.interval().name()), (window.endMs() - window.startMs()) / 1000L,
                entry.phase(), Enums.Direction.valueOf(entry.bet().direction().name()),
                entry.bet().leverage().value(), entry.bet().stake().toMoney().toPlainString(), entryPrice,
                liquidationPrice, size, liquidated, running ? window.endMs() : window.startMs());
    }

    // ---------------------------------------------------------------------------------------
    // crowdOverview
    // ---------------------------------------------------------------------------------------

    /**
     * Every played market's crowd account side by side (Т-6): a row per market, listing order, and the totals
     * over them. A closed market keeps its row — its account and its history stay (the engine keeps the
     * account of every market in the registry), only {@code markets()} forgets it after 10 minutes.
     */
    public Markets.CrowdOverviewDto crowdOverview() {
        return gameService.readEngine(this::assembleOverview);
    }

    private Markets.CrowdOverviewDto assembleOverview() {
        List<Markets.CrowdOverviewRowDto> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalStarting = BigDecimal.ZERO;
        for (Market market : playedMarkets()) {
            CrowdAccount crowd = hub.findMarket(market.id()).map(EngineHub.MarketEngines::crowd).orElse(null);
            BigDecimal capital = crowd == null ? CrowdAccount.STARTING_CAPITAL : crowd.capital();
            CrowdStatsMemo.Stats stats = crowd == null ? CrowdStatsMemo.Stats.NONE : crowdStats.of(market.id(), crowd);
            rows.add(new Markets.CrowdOverviewRowDto(market.id(), market.symbol(), market.title(),
                    Enums.MarketStatus.valueOf(market.status().name()), market.category(), capital.toPlainString(),
                    MarketMapper.returnPct(capital), stats.roundsTraded(), stats.roundsWon()));
            total = total.add(capital);
            totalStarting = totalStarting.add(CrowdAccount.STARTING_CAPITAL);
        }
        double returnPct = rows.isEmpty() ? 0.0 : MarketMapper.returnPct(total, totalStarting);
        return new Markets.CrowdOverviewDto(total.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                totalStarting.setScale(2, RoundingMode.HALF_UP).toPlainString(), returnPct, List.copyOf(rows));
    }
}
