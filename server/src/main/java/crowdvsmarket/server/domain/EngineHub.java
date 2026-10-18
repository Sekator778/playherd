package crowdvsmarket.server.domain;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.server.markets.Market;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live, in-memory engine state: ONE shared {@link LedgerBook} — a player has one PLAY
 * balance behind a bet on any market (Т-5: "Один баланс PLAY на игрока") and the ratings/seasons
 * are common to every market and series (Т-7, Р-30) — and, per market (docs/research/
 * markets_architecture.md §4 п.1, Т-6): one {@link CrowdAccount} (its own $100 000 and chart,
 * Р-17/Р-25), the market's two {@link GameEngine}s (HOURLY and DAILY, each with its own private
 * {@code BetBook}, per round_engine_design.md §1: "construct one GameEngine per series" — both
 * share that crowd account and the ledger) and the market's {@link ComparisonAccounts} (the
 * "hold the asset" and "anti-crowd" lines, Р-26).
 *
 * <p>A market gets its engines through {@link #ensureMarket}: for every market in the registry
 * (closed ones too — their history replays) at a rebuild, and for a newly listed one at listing
 * time. BTC is always present ({@link #resetEmpty}). A rebuild fills a hub of its own and hands it
 * over with {@link #adopt}.
 *
 * <p>Every mutating call in this codebase goes through {@code GameService}, which synchronizes on
 * this instance for the whole of one logical operation (persist event(s) + mutate this state +
 * update read models) — none of {@code LedgerBook}/{@code CrowdAccount}/{@code GameEngine} is
 * internally thread-safe (round_engine_design.md §3: "Однопоточный... функциональное ядро").
 */
@ApplicationScoped
public final class EngineHub {

    /** Everything one market owns; see the class javadoc. */
    public static final class MarketEngines {

        private final MarketSpec spec;
        private final CrowdAccount crowd;
        private final GameEngine hourly;
        private final GameEngine daily;
        private final ComparisonAccounts comparison;

        MarketEngines(MarketSpec spec, LedgerBook ledger) {
            this.spec = spec;
            this.crowd = new CrowdAccount(CrowdAccount.STARTING_CAPITAL, new PaperTradingAdapter(), spec);
            this.hourly = new GameEngine(ledger, crowd);
            this.daily = new GameEngine(ledger, crowd);
            this.comparison = new ComparisonAccounts(spec);
        }

        public MarketSpec spec() {
            return spec;
        }

        public CrowdAccount crowd() {
            return crowd;
        }

        public GameEngine engine(Interval interval) {
            return interval == Interval.HOURLY ? hourly : daily;
        }

        public ComparisonAccounts comparison() {
            return comparison;
        }
    }

    /** The ledger, the markets' engines and the index of the windows that hold bets, of one moment: what
     *  {@link #adopt} swaps in one step. An engine is built over the ledger of ITS state, so the two never
     *  mix, and the index describes the bet books of the engines beside it. */
    private static final class State {
        final LedgerBook ledger = new LedgerBook();
        final ConcurrentHashMap<String, MarketEngines> markets = new ConcurrentHashMap<>();
        final BetWindows betWindows = new BetWindows();
        final ClosedRounds closedRounds = new ClosedRounds();
    }

    private volatile State state;

    public EngineHub() {
        resetEmpty();
    }

    /** Discards all in-memory state and starts a brand-new ledger with BTC's engines only. A rebuild
     *  no longer does this to the live hub (it builds a new hub aside and {@link #adopt}s it, so a
     *  replay that fails half-way leaves nothing half-built behind); it is what a hub starts with. */
    public void resetEmpty() {
        state = new State();
        ensureMarket(MarketSpec.BTC);
    }

    /** Takes over everything {@code built} holds — its ledger, every market's engines and the index of
     *  the windows with bets — in one volatile write: the swap that ends a rebuild, once the replay and
     *  the seeding of a hub built aside ({@code new EngineHub()} + {@link #ensureMarket} + the replay)
     *  have both succeeded. {@code built} must not be used afterwards: this hub and it would share one
     *  state. The caller holds the engine lock, so no reader of this hub is in the middle of a call. */
    public void adopt(EngineHub built) {
        state = built.state;
    }

    /** Creates the market's crowd account, two engines and comparison lines when it has none yet;
     *  a no-op for a market that already has them. */
    public MarketEngines ensureMarket(Market market) {
        return ensureMarket(market.spec());
    }

    /**
     * Gives the market fresh engines built from its registry row, replacing any it has — for a hub that is being built aside
     * and is still empty ({@code EngineStateBuilder}): {@link #resetEmpty} seeds BTC from the engine's constant, and the BTC row of
     * the registry (the economy parameters in the database) is what must rule. Never call it on a hub that holds bets.
     */
    public MarketEngines install(Market market) {
        State current = state;
        MarketSpec spec = market.spec();
        return current.markets.compute(spec.id(), (id, old) -> new MarketEngines(spec, current.ledger));
    }

    private MarketEngines ensureMarket(MarketSpec spec) {
        State current = state;
        return current.markets.computeIfAbsent(spec.id(), id -> new MarketEngines(spec, current.ledger));
    }

    public LedgerBook ledger() {
        return state.ledger;
    }

    /** The windows that may still hold a bet ({@link BetWindows}); kept by {@code EventApplier}. */
    public BetWindows betWindows() {
        return state.betWindows;
    }

    /** The rounds that are resolved or cancelled ({@link ClosedRounds}); kept by {@code EventApplier}. */
    public ClosedRounds closedRounds() {
        return state.closedRounds;
    }

    /** The market's engines, or empty when {@link #ensureMarket} never ran for it. */
    public Optional<MarketEngines> findMarket(String marketId) {
        return Optional.ofNullable(state.markets.get(marketId));
    }

    /** Whether {@code playerId} has a bet on round {@code roundId} of {@code marketId}'s {@code interval} series. */
    public boolean hasBet(String marketId, Interval interval, String roundId, String playerId) {
        return findMarket(marketId).map(m -> m.engine(interval).betBook().betsFor(roundId).stream()
                .anyMatch(b -> b.playerId().equals(playerId))).orElse(false);
    }

    /** @throws IllegalStateException when the market has no engines — a programming error for a
     *          caller that already resolved the market through the registry. */
    public MarketEngines market(String marketId) {
        return findMarket(marketId).orElseThrow(
                () -> new IllegalStateException("no engines for market " + marketId + " (ensureMarket not called)"));
    }

    public GameEngine engine(String marketId, Interval interval) {
        return market(marketId).engine(interval);
    }

    public CrowdAccount crowdAccount(String marketId) {
        return market(marketId).crowd();
    }

    public ComparisonAccounts comparison(String marketId) {
        return market(marketId).comparison();
    }

    public List<String> marketIds() {
        return List.copyOf(state.markets.keySet());
    }
}
