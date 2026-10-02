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

    /** The ledger and the markets' engines of one moment: what {@link #adopt} swaps in one step. An
     *  engine is built over the ledger of ITS state, so the two never mix. */
    private static final class State {
        final LedgerBook ledger = new LedgerBook();
        final ConcurrentHashMap<String, MarketEngines> markets = new ConcurrentHashMap<>();
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

    /** Takes over everything {@code built} holds — its ledger and every market's engines — in one
     *  volatile write: the swap that ends a rebuild, once the replay and the seeding of a hub built
     *  aside ({@code new EngineHub()} + {@link #ensureMarket} + the replay) have both succeeded.
     *  {@code built} must not be used afterwards: this hub and it would share one state. The caller
     *  holds the engine lock, so no reader of this hub is in the middle of a call. */
    public void adopt(EngineHub built) {
        state = built.state;
    }

    /** Creates the market's crowd account, two engines and comparison lines when it has none yet;
     *  a no-op for a market that already has them. */
    public MarketEngines ensureMarket(Market market) {
        return ensureMarket(market.spec());
    }

    private MarketEngines ensureMarket(MarketSpec spec) {
        State current = state;
        return current.markets.computeIfAbsent(spec.id(), id -> new MarketEngines(spec, current.ledger));
    }

    public LedgerBook ledger() {
        return state.ledger;
    }

    /** The market's engines, or empty when {@link #ensureMarket} never ran for it. */
    public Optional<MarketEngines> findMarket(String marketId) {
        return Optional.ofNullable(state.markets.get(marketId));
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
