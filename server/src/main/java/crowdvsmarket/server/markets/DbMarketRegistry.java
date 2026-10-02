package crowdvsmarket.server.markets;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
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
 *   <li>{@link #list} assigns the next {@code oracle_index} inside a transaction that holds a table
 *       lock — the index is append-only and never reused, BTC keeps 0 — and is idempotent by id.
 *   <li>{@link #list} and {@link #close} notify the {@link MarketListener} AFTER the transaction
 *       committed and outside {@link #writeLock}: the listener ({@code GameService}) takes the engine
 *       lock, and a thread that holds that lock may be about to read this registry.
 *   <li>{@link #reload} re-reads the table ({@code EngineRebuildService} calls it at every rebuild).
 *       BTC is always present: a missing row is inserted.
 * </ul>
 */
@ApplicationScoped
public class DbMarketRegistry implements MarketRegistry {

    private static final Logger LOG = Logger.getLogger(DbMarketRegistry.class);

    @Inject
    Db db;

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
        Market listed;
        synchronized (writeLock) {
            Optional<Market> known = find(snapshot(), spec.id());
            if (known.isPresent()) {
                return known.get();
            }
            Inserted inserted = db.tx(c -> insertNext(c, spec, nowMs));
            listed = inserted.market();
            markets = withMarket(snapshot(), listed);
            if (!inserted.created()) {
                return listed; // another process listed it between our snapshot and the table lock
            }
        }
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

    @Override
    public void close(String marketId, String reason, long nowMs) {
        if (MarketSpec.BTC_ID.equals(marketId)) {
            throw new IllegalArgumentException("BTC is always listed and cannot be closed");
        }
        Market closed;
        synchronized (writeLock) {
            Optional<Market> known = find(snapshot(), marketId);
            if (known.isEmpty() || !known.get().isActive()) {
                return;
            }
            int updated = db.tx(c -> db.update(c, """
                    UPDATE markets SET status = 'CLOSED', closed_at_ms = ?, close_reason = ?
                    WHERE id = ? AND status = 'ACTIVE'
                    """, nowMs, reason, marketId));
            closed = known.get().closed(nowMs, reason);
            markets = withMarket(snapshot(), closed);
            if (updated == 0) {
                return; // closed by another process already; its own listener announced it
            }
        }
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

    /** Re-reads the {@code markets} table (inserting BTC if its row is missing) and replaces the
     *  in-memory copy. The table is read first and the copy swapped after, so a failed read leaves
     *  the registry as it was. */
    @Override
    public void reload() {
        synchronized (writeLock) {
            markets = List.copyOf(db.tx(this::loadAll));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private List<Market> snapshot() {
        List<Market> current = markets;
        if (current == null) {
            reload();
            current = markets;
        }
        return current;
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

    private record Inserted(Market market, boolean created) {
    }

    private Inserted insertNext(Connection c, NewMarket spec, long nowMs) throws SQLException {
        // Writers queue on this lock, so MAX+1 below is race-free; readers are not blocked.
        db.update(c, "LOCK TABLE markets IN SHARE ROW EXCLUSIVE MODE");
        Market existing = db.queryOne(c, "SELECT * FROM markets WHERE id = ?", this::toMarket, spec.id());
        if (existing != null) {
            return new Inserted(existing, false);
        }
        // BTC owns 0 (V7); never hand it out, even if the BTC row were missing right now.
        Integer max = db.queryOne(c, "SELECT MAX(oracle_index) AS m FROM markets",
                rs -> (Integer) rs.getObject("m"));
        int nextIndex = Math.max(1, (max == null ? 0 : max) + 1);
        JsonNode meta = parseMeta(spec.sourceMeta());
        db.update(c, """
                INSERT INTO markets (id, kind, category, symbol, title, subtitle, icon_url, source, source_ref,
                    source_meta, max_leverage, lot_exp, oracle_index, status, listed_at_ms, ends_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                spec.id(), spec.kind().name(), spec.category(), spec.symbol(), spec.title(), spec.subtitle(),
                spec.iconUrl(), spec.source(), spec.sourceRef(), Db.jsonb(meta), spec.maxLeverage(), spec.lotExp(),
                nextIndex, nowMs, spec.endsAtMs());
        // Read the row back: what the caller gets is exactly what a reload will give (jsonb
        // normalizes the text of sourceMeta).
        return new Inserted(db.queryOne(c, "SELECT * FROM markets WHERE id = ?", this::toMarket, spec.id()), true);
    }

    private List<Market> loadAll(Connection c) throws SQLException {
        db.update(c, """
                INSERT INTO markets (id, kind, category, symbol, title, source, source_ref, max_leverage, lot_exp,
                    oracle_index, status, listed_at_ms)
                VALUES ('BTC', 'PERP', 'crypto', 'BTC', 'Bitcoin', 'hyperliquid', 'BTC', 40, 0, 0, 'ACTIVE', 0)
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
                (Long) rs.getObject("ends_at_ms"));
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
        MarketSpec.of(spec.id(), spec.maxLeverage()); // validates the leverage cap (2..40) and the rate
    }
}
