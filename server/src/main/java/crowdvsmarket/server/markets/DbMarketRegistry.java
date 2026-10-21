package crowdvsmarket.server.markets;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The real {@link MarketRegistry} (docs/research/markets_architecture.md §3.1): the {@code markets}
 * table of {@code V7__markets.sql}, cached in memory. It replaces the {@link InMemoryMarketRegistry}
 * fallback ({@code @DefaultBean}).
 *
 * <ul>
 *   <li>The in-memory copy is an immutable snapshot swapped under {@link #writeLock}; every read
 *       ({@link #all}, {@link #byId}, …) is lock-free, so a caller holding the game engine's lock can
 *       always read the registry.
 *   <li>{@link #list} assigns the next {@code oracle_index} from the markets it holds in memory — the index is append-only and
 *       never reused, BTC keeps 0 — and is idempotent by id. The listing is a turn of the core writer ({@link WriterTurn}) that touches
 *       no database: it journals {@code MarketListed}, which carries the whole row, and updates the memory. The {@code markets} row is
 *       written by the caller once the event is durable ({@link #projectFromJournal}); a stop in between is repaired by the next
 *       rebuild, which writes every row the journal has and the table lacks. {@link #close} is the same with {@code MarketClosed}.
 *   <li>{@link #list} and {@link #close} notify the {@link MarketListener} AFTER the event is durable and the row written, outside
 *       {@link #writeLock}: the listener ({@code GameService}) takes the engine
 *       lock, and a thread that holds that lock may be about to read this registry.
 *   <li>{@link #reload} re-reads the table ({@code EngineRebuildService} calls it at every rebuild, and this bean at start). Nothing else
 *       loads it: a read before the first load answers 503 {@code ENGINE_NOT_READY}, and no command can reach the database through the
 *       registry. BTC is always present: a missing row is inserted — and one that was closed stays closed (the insert
 *       does nothing for an id that exists).
 *   <li>BTC can be closed only through {@link #closeReplaced}, by the catalog of the market that takes over its symbol.
 * </ul>
 */
@ApplicationScoped
public class DbMarketRegistry implements MarketRegistry {

    private static final Logger LOG = Logger.getLogger(DbMarketRegistry.class);

    @Inject
    Db db;

    @Inject
    EventStore eventStore;

    /** Guards writes (and the swap of {@link #markets}); never held while the listener runs. */
    private final Object writeLock = new Object();
    private volatile List<Market> markets;
    private volatile MarketListener listener;

    // ------------------------------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------------------------------

    @Override
    public List<Market> all() {
        return snapshot();
    }

    @Override
    public List<Market> active() {
        return snapshot().stream().filter(Market::isActive).toList();
    }

    @Override
    public Optional<Market> byId(String id) {
        return find(snapshot(), id);
    }

    @Override
    public Market require(String id) {
        return byId(id).orElseThrow(() -> ApiException.marketNotFound(id));
    }

    @Override
    public List<Category> categories() {
        List<Market> current = snapshot();
        return Category.ORDER.stream()
                .filter(c -> current.stream().anyMatch(m -> m.isActive() && m.category().equals(c.id())))
                .toList();
    }

    // ------------------------------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------------------------------

    @Override
    public Market list(NewMarket spec, long nowMs) {
        validate(spec);
        Optional<Market> known = find(snapshot(), spec.id());
        if (known.isPresent()) {
            return known.get(); // idempotent by id
        }
        // before the turn, the one thing the database decides: how it stores the values (jsonb text, numeric scales) - the event carries the
        // row exactly as the table will give it back
        MarketEconomy defaults = MarketEconomy.defaultsFor(spec.kind(), spec.symbol(), spec.maxLeverage());
        Stored stored = db.read(c -> db.queryOne(c, "SELECT CAST(? AS jsonb)::text AS meta, CAST(? AS NUMERIC(6, 4)) AS kappa, "
                + "CAST(? AS NUMERIC(8, 3)) AS fee, CAST(? AS NUMERIC(10, 6)) AS stress",
                rs -> new Stored(rs.getString("meta"), rs.getBigDecimal("kappa"), rs.getBigDecimal("fee"),
                        rs.getBigDecimal("stress")),
                Db.jsonb(parseMeta(spec.sourceMeta())), defaults.kappa(), defaults.herdFeeBp(), defaults.stressMove()));
        MarketEconomy economy = new MarketEconomy(defaults.capHourly(), defaults.capDaily(), stored.kappa(), stored.herdFeeBp(),
                stored.stressMove());
        // The event is journaled in the writer's turn, which takes the registry's lock inside it: nothing here holds the lock while it
        // waits for the writer.
        Inserted inserted = inTurn(t -> {
            synchronized (writeLock) {
                List<Market> before = snapshot();
                Optional<Market> already = find(before, spec.id());
                if (already.isPresent()) {
                    return new Inserted(already.get(), false);
                }
                // BTC owns 0; never hand it out. Append-only: the highest index ever given, closed markets included, plus one.
                int nextIndex = Math.max(1, before.stream().mapToInt(Market::oracleIndex).max().orElse(0) + 1);
                Market listed = new Market(spec.id(), spec.kind(), spec.category(), spec.symbol(), spec.title(), spec.subtitle(),
                        spec.iconUrl(), spec.source(), spec.sourceRef(), stored.meta(), spec.maxLeverage(), spec.lotExp(), nextIndex,
                        MarketStatus.ACTIVE, nowMs, null, null, spec.endsAtMs(), economy);
                t.record(listedEvent(listed), nowMs);
                markets = withMarket(before, listed);
                t.onRollback(() -> {
                    synchronized (writeLock) {
                        markets = withoutMarket(snapshot(), listed.id()); // the batch did not commit: the listing never happened
                    }
                });
                return new Inserted(listed, true);
            }
        });
        Market listed = inserted.market();
        if (!inserted.created()) {
            return listed;
        }
        // the event is durable: the row follows, before anything else hears of the market (an event whose row could not be written is
        // written by the next rebuild)
        writeRows(List.of(listedEvent(listed)));
        LOG.infof("market listed: %s (%s/%s) oracleIndex=%d lotExp=%d maxLeverage=%d", listed.id(), listed.kind(),
                listed.category(), listed.oracleIndex(), listed.lotExp(), listed.maxLeverage());
        MarketListener l = listener;
        if (l != null) {
            try {
                l.onListed(listed);
            } catch (RuntimeException e) {
                // The listing is committed and public whatever the listener makes of it: a failure there
                // must not turn into an exception for the caller (a catalog would abandon the rest of
                // its pass). The scheduler gives a running market without a series one.
                LOG.errorf(e, "the market listener failed for the listing of %s — the market stays listed",
                        listed.id());
            }
        }
        return listed;
    }

    private record Stored(String meta, java.math.BigDecimal kappa, java.math.BigDecimal herdFeeBp, java.math.BigDecimal stressMove) {
    }

    @Override
    public void close(String marketId, String reason, long nowMs) {
        if (MarketSpec.BTC_ID.equals(marketId)) {
            throw new IllegalArgumentException("BTC is always listed and cannot be closed, except when another market "
                    + "takes over its symbol (closeReplaced)");
        }
        closeMarket(marketId, reason, nowMs);
    }

    @Override
    public void closeReplaced(String marketId, String reason, long nowMs) {
        closeMarket(marketId, reason, nowMs);
    }

    private void closeMarket(String marketId, String reason, long nowMs) {
        Market closed = inTurn(t -> closeMarketEvent(t, marketId, reason, nowMs));
        if (closed == null) {
            return; // unknown or closed already
        }
        writeRows(List.of(new GameEvent.MarketClosed(marketId, reason, nowMs))); // the row follows the durable event
        announceClosed(closed, marketId, reason);
    }

    /** The {@code MarketClosed} event and the memory, in the writer's turn; {@code null} when there was nothing to close. */
    private Market closeMarketEvent(WriterTurn.Turn t, String marketId, String reason, long nowMs) {
        synchronized (writeLock) {
            List<Market> before = snapshot();
            Optional<Market> known = find(before, marketId);
            if (known.isEmpty() || !known.get().isActive()) {
                return null;
            }
            Market closed = known.get().closed(nowMs, reason);
            t.record(new GameEvent.MarketClosed(marketId, reason, nowMs), nowMs);
            markets = withMarket(before, closed);
            Market was = known.get();
            t.onRollback(() -> {
                synchronized (writeLock) {
                    markets = withMarket(snapshot(), was); // the batch did not commit: the market is still open
                }
            });
            return closed;
        }
    }

    /** Runs {@code work} as the registry's turn: on the core writer when the game has set it, else in place (a registry that is not part
     *  of the running game appends its events itself). */
    private <T> T inTurn(java.util.function.Function<WriterTurn.Turn, T> work) {
        WriterTurn t = turn;
        if (t != null) {
            return t.run(work);
        }
        List<Runnable> rollbacks = new ArrayList<>();
        T result = work.apply(new WriterTurn.Turn() {
            @Override
            public void record(GameEvent event, long atMs) {
                try {
                    eventStore.append(null, event, atMs);
                } catch (RuntimeException | Error e) {
                    rollbacks.forEach(Runnable::run);
                    throw e;
                }
            }

            @Override
            public void onRollback(Runnable action) {
                rollbacks.add(action);
            }
        });
        return result;
    }

    private static GameEvent.MarketListed listedEvent(Market listed) {
        return new GameEvent.MarketListed(listed.id(), listed.kind().name(), listed.category(), listed.symbol(),
                listed.title(), listed.subtitle(), listed.iconUrl(), listed.source(), listed.sourceRef(), listed.sourceMeta(),
                listed.maxLeverage(), listed.lotExp(), listed.oracleIndex(), listed.listedAtMs(), listed.endsAtMs(),
                listed.economy().capHourly(), listed.economy().capDaily(), listed.economy().kappa().toPlainString(),
                listed.economy().herdFeeBp().toPlainString(),
                listed.economy().stressMove() == null ? null : listed.economy().stressMove().toPlainString());
    }

    private void announceClosed(Market closed, String marketId, String reason) {
        LOG.infof("market closed: %s (%s)", marketId, reason);
        MarketListener l = listener;
        if (l != null) {
            try {
                l.onClosed(closed, reason);
            } catch (RuntimeException e) {
                // Same as for a listing: the close is committed. What the listener did not manage to
                // cancel is refunded by the engine rebuild its own failure handling starts (and by every
                // later one: a closed market's open rounds are cancelled at each rebuild).
                LOG.errorf(e, "the market listener failed for the closing of %s — the market stays closed",
                        closed.id());
            }
        }
    }

    @Override
    public void setListener(MarketListener listener) {
        this.listener = listener;
    }

    private volatile WriterTurn turn;

    @Override
    public void setWriterTurn(WriterTurn turn) {
        this.turn = turn;
    }

    /** Re-reads the {@code markets} table (inserting BTC if its row is missing) and replaces the
     *  in-memory copy. The table is read first and the copy swapped after, so a failed read leaves
     *  the registry as it was. */
    @Override
    public void reload() {
        synchronized (writeLock) {
            markets = List.copyOf(db.tx(this::loadAll));
        }
    }

    /**
     * F4: the {@code markets} table is a projection of the journal. Inserts the markets that {@code MarketListed} events list and the
     * table lacks (with the {@code oracle_index} and the economy parameters the event carries) and closes those that {@code
     * MarketClosed} events closed and the table still has active. BTC, the genesis row, is not in the journal. The cache is reloaded
     * by the caller ({@link #reload}) when this returns more than 0.
     */
    @Override
    public int projectFromJournal(List<GameEvent> events) {
        int written = writeRows(events);
        if (written > 0) {
            LOG.infof("the markets table was repaired from the journal: %d row change(s)", written);
        }
        return written;
    }

    /** Writes the {@code markets} rows that {@code events} (the journal's) say and the table lacks, idempotently; returns the number of
     *  row changes. */
    private int writeRows(List<GameEvent> events) {
        List<GameEvent> relevant = events.stream()
                .filter(e -> e instanceof GameEvent.MarketListed || e instanceof GameEvent.MarketClosed).toList();
        if (relevant.isEmpty()) {
            return 0;
        }
        synchronized (writeLock) {
            return db.tx(c -> {
                int n = 0;
                for (GameEvent event : relevant) {
                    if (event instanceof GameEvent.MarketListed e) {
                        n += db.update(c, """
                                INSERT INTO markets (id, kind, category, symbol, title, subtitle, icon_url, source, source_ref,
                                    source_meta, max_leverage, lot_exp, oracle_index, status, listed_at_ms, ends_at_ms,
                                    cap_hourly, cap_daily, kappa, herd_fee_bp, stress_move)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?)
                                ON CONFLICT DO NOTHING
                                """,
                                e.marketId(), e.kind(), e.category(), e.symbol(), e.title(), e.subtitle(), e.iconUrl(), e.source(),
                                e.sourceRef(), Db.jsonb(parseMeta(e.sourceMeta())), e.maxLeverage(), e.lotExp(), e.oracleIndex(),
                                e.listedAtMs(), e.endsAtMs(), e.capHourly(), e.capDaily(), new java.math.BigDecimal(e.kappa()),
                                new java.math.BigDecimal(e.herdFeeBp()),
                                e.stressMove() == null ? null : new java.math.BigDecimal(e.stressMove()));
                    } else if (event instanceof GameEvent.MarketClosed e) {
                        n += db.update(c, """
                                UPDATE markets SET status = 'CLOSED', closed_at_ms = ?, close_reason = ?
                                WHERE id = ? AND status = 'ACTIVE'
                                """, e.atMs(), e.reason(), e.marketId());
                    }
                }
                return n;
            });
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /** The markets held in memory. Never loads them (a command of the core writer reads the registry): until the first {@link #reload}
     *  nothing is known, and 503 {@code ENGINE_NOT_READY} says so. */
    private List<Market> snapshot() {
        List<Market> current = markets;
        if (current == null) {
            throw ApiException.engineNotReady();
        }
        return current;
    }

    /** Loads the markets at start, before the first rebuild, so that a reader that comes first finds them; a failure is logged and the
     *  rebuild (which reloads) tries again. */
    void onStart(@Observes @Priority(200) StartupEvent ev) {
        try {
            reload();
        } catch (RuntimeException e) {
            LOG.warnf("the markets could not be loaded at start - the first rebuild of the engine loads them: %s", e.toString());
        }
    }

    private static Optional<Market> find(List<Market> list, String id) {
        for (Market m : list) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** {@code list} with {@code market} appended, or replacing the entry of the same id. */
    private static List<Market> withMarket(List<Market> list, Market market) {
        List<Market> out = new ArrayList<>(list.size() + 1);
        boolean replaced = false;
        for (Market m : list) {
            if (m.id().equals(market.id())) {
                out.add(market);
                replaced = true;
            } else {
                out.add(m);
            }
        }
        if (!replaced) {
            out.add(market);
        }
        return List.copyOf(out);
    }

    /** {@code list} without the market of {@code id}. */
    private static List<Market> withoutMarket(List<Market> list, String id) {
        return list.stream().filter(m -> !m.id().equals(id)).toList();
    }

    private record Inserted(Market market, boolean created) {
    }

    private List<Market> loadAll(Connection c) throws SQLException {
        db.update(c, """
                INSERT INTO markets (id, kind, category, symbol, title, source, source_ref, max_leverage, lot_exp,
                    oracle_index, status, listed_at_ms, cap_hourly, cap_daily, kappa, herd_fee_bp, stress_move)
                VALUES ('BTC', 'PERP', 'crypto', 'BTC', 'Bitcoin', 'hyperliquid', 'BTC', 40, 0, 0, 'ACTIVE', 0,
                    1200, 500, 0.1, 0.92, 0.00817)
                ON CONFLICT (id) DO NOTHING
                """);
        return db.queryList(c, "SELECT * FROM markets ORDER BY oracle_index ASC", this::toMarket);
    }

    private Market toMarket(ResultSet rs) throws SQLException {
        return new Market(rs.getString("id"), MarketKind.valueOf(rs.getString("kind")), rs.getString("category"),
                rs.getString("symbol"), rs.getString("title"), rs.getString("subtitle"), rs.getString("icon_url"),
                rs.getString("source"), rs.getString("source_ref"), rs.getString("source_meta"),
                rs.getInt("max_leverage"), rs.getInt("lot_exp"), rs.getInt("oracle_index"),
                MarketStatus.valueOf(rs.getString("status")), rs.getLong("listed_at_ms"),
                (Long) rs.getObject("closed_at_ms"), rs.getString("close_reason"),
                (Long) rs.getObject("ends_at_ms"),
                new MarketEconomy(rs.getInt("cap_hourly"), rs.getInt("cap_daily"), rs.getBigDecimal("kappa"),
                        rs.getBigDecimal("herd_fee_bp"), rs.getBigDecimal("stress_move")));
    }

    private JsonNode parseMeta(String sourceMeta) {
        if (sourceMeta == null) {
            return null;
        }
        try {
            return db.json().readTree(sourceMeta);
        } catch (Exception e) {
            throw new IllegalArgumentException("sourceMeta is not valid JSON", e);
        }
    }

    /** What a catalog may hand over: everything {@code Market} documents, checked once here so a bad
     *  listing fails before it touches the table (the engine would reject the id/leverage later, at
     *  a worse moment). */
    /** The highest single cap a catalog may list a market with (Т-4). */
    static final int MAX_LISTED_LEVERAGE = 40;

    private static void validate(NewMarket spec) {
        MarketSpec.requireValidId(spec.id());
        if (MarketSpec.BTC_ID.equals(spec.id())) {
            throw new IllegalArgumentException("BTC is listed by the migration, not by a catalog");
        }
        if (spec.kind() == null || spec.category() == null || spec.symbol() == null || spec.title() == null
                || spec.source() == null || spec.sourceRef() == null) {
            throw new IllegalArgumentException("a listed market needs kind, category, symbol, title, source and "
                    + "sourceRef: " + spec);
        }
        if (Category.ORDER.stream().noneMatch(c -> c.id().equals(spec.category()))) {
            throw new IllegalArgumentException("unknown category " + spec.category());
        }
        if (spec.lotExp() < 0 || spec.lotExp() > Market.MAX_LOT_EXP) {
            throw new IllegalArgumentException("lotExp must be in [0, " + Market.MAX_LOT_EXP + "], got "
                    + spec.lotExp());
        }
        if (spec.maxLeverage() > MAX_LISTED_LEVERAGE) {
            // the market's own single cap (what bets placed before the economy change are settled with); the per-round-length
            // caps of economy stage A are the MarketEconomy columns and go up to Leverage.MAX
            throw new IllegalArgumentException(
                    "maxLeverage must be in [2, " + MAX_LISTED_LEVERAGE + "], got " + spec.maxLeverage());
        }
        MarketSpec.of(spec.id(), spec.maxLeverage()); // validates the leverage cap (2..40) and the rate
    }
}
