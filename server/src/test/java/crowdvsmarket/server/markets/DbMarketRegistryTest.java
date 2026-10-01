package crowdvsmarket.server.markets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DbMarketRegistry} (docs/research/markets_architecture.md §3.1): persistence in the
 * {@code markets} table, append-only oracle indexes (BTC = 0, never reused, idempotent by id), and the
 * listener being told AFTER the transaction and outside the registry's locks. A second registry
 * instance over the same database stands in for "the process after a restart" (or another process);
 * each test lists markets with ids of its own and removes them again.
 */
@QuarkusTest
class DbMarketRegistryTest {

    @Inject
    Db db;
    @Inject
    DbMarketRegistry live;

    private static final String PREFIX = "RT_";

    private DbMarketRegistry registry(MarketListener listener) {
        DbMarketRegistry registry = new DbMarketRegistry();
        registry.db = db;
        registry.setListener(listener);
        registry.reload();
        return registry;
    }

    @AfterEach
    void removeTestMarkets() {
        db.tx(c -> {
            db.update(c, "DELETE FROM markets WHERE id LIKE '" + PREFIX + "%'");
            return null;
        });
        live.reload(); // the application's own copy, in case a test went through it
    }

    private static NewMarket perp(String id, String category, int maxLeverage, int lotExp) {
        return new NewMarket(PREFIX + id, MarketKind.PERP, category, id, "Perp " + id, null, null, "hyperliquid",
                "xyz:" + id, null, maxLeverage, lotExp, null);
    }

    private static NewMarket odds(String id) {
        return new NewMarket(PREFIX + id, MarketKind.ODDS, Category.POLITICS, "GER", "Will Germany win?",
                "Yes · UEFA", "https://example.invalid/icon.png", "polymarket", "7123456789", "{\"conditionId\":\"0xabc\"}",
                10, Market.ODDS_LOT_EXP, 1_800_000_000_000L);
    }

    private static final class Recording implements MarketListener {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onListed(Market market) {
            events.add("listed " + market.id());
        }

        @Override
        public void onClosed(Market market, String reason) {
            events.add("closed " + market.id() + " " + reason);
        }
    }

    private Map<String, Object> row(String id) {
        return db.read(c -> db.queryOne(c, "SELECT * FROM markets WHERE id = ?", rs -> Map.<String, Object>ofEntries(
                Map.entry("kind", rs.getString("kind")), Map.entry("category", rs.getString("category")),
                Map.entry("symbol", rs.getString("symbol")), Map.entry("title", rs.getString("title")),
                Map.entry("oracle_index", rs.getInt("oracle_index")), Map.entry("status", rs.getString("status")),
                Map.entry("listed_at_ms", rs.getLong("listed_at_ms")),
                Map.entry("max_leverage", rs.getInt("max_leverage")), Map.entry("lot_exp", rs.getInt("lot_exp")),
                Map.entry("source", rs.getString("source")), Map.entry("source_ref", rs.getString("source_ref"))), id));
    }

    @Test
    void btcIsAlwaysListedWithIndexZero() {
        DbMarketRegistry registry = registry(null);

        Market btc = registry.require("BTC");

        assertThat(btc.oracleIndex()).isZero();
        assertThat(btc.kind()).isEqualTo(MarketKind.PERP);
        assertThat(btc.category()).isEqualTo(Category.CRYPTO);
        assertThat(btc.maxLeverage()).isEqualTo(40);
        assertThat(btc.lotExp()).isZero();
        assertThat(btc.isActive()).isTrue();
        assertThat(btc.spec()).isEqualTo(crowdvsmarket.engine.market.MarketSpec.BTC);
        assertThat(registry.all().get(0).id()).isEqualTo("BTC");
    }

    @Test
    void aMissingBtcRowIsInsertedAgainOnReload() {
        db.tx(c -> db.update(c, "DELETE FROM markets WHERE id = 'BTC'"));

        DbMarketRegistry registry = registry(null);

        assertThat(registry.byId("BTC")).isPresent();
        assertThat(registry.require("BTC").oracleIndex()).isZero();
        assertThat(row("BTC")).containsEntry("status", "ACTIVE");
    }

    @Test
    void listAssignsTheNextOracleIndexPersistsAndSurvivesARestart() {
        DbMarketRegistry registry = registry(null);

        Market a = registry.list(perp("A", Category.CRYPTO, 25, 1), 1_000L);
        Market b = registry.list(odds("B"), 2_000L);

        assertThat(a.oracleIndex()).isEqualTo(1);
        assertThat(b.oracleIndex()).isEqualTo(2);
        assertThat(a.status()).isEqualTo(MarketStatus.ACTIVE);
        assertThat(a.listedAtMs()).isEqualTo(1_000L);
        assertThat(row(a.id())).containsEntry("oracle_index", 1).containsEntry("status", "ACTIVE")
                .containsEntry("kind", "PERP").containsEntry("category", "crypto").containsEntry("max_leverage", 25)
                .containsEntry("lot_exp", 1).containsEntry("listed_at_ms", 1_000L).containsEntry("symbol", "A")
                .containsEntry("source", "hyperliquid").containsEntry("source_ref", "xyz:A");

        // "restart": a new registry reads the table and finds exactly the same markets, in listing order
        DbMarketRegistry afterRestart = registry(null);
        assertThat(afterRestart.all()).extracting(Market::id).containsExactly("BTC", a.id(), b.id());
        assertThat(afterRestart.require(a.id())).isEqualTo(a);
        assertThat(afterRestart.require(b.id())).isEqualTo(b);
        assertThat(afterRestart.require(b.id()).endsAtMs()).isEqualTo(1_800_000_000_000L);
        assertThat(afterRestart.require(b.id()).priceDecimals()).isEqualTo(6);
    }

    @Test
    void listIsIdempotentByIdAndNotifiesOnlyTheFirstTime() {
        Recording listener = new Recording();
        DbMarketRegistry registry = registry(listener);

        Market first = registry.list(perp("A", Category.CRYPTO, 25, 1), 1_000L);
        Market again = registry.list(new NewMarket(PREFIX + "A", MarketKind.PERP, Category.STOCKS, "OTHER", "Changed",
                null, null, "hyperliquid", "xyz:OTHER", null, 10, 2, null), 9_000L);

        assertThat(again).isEqualTo(first);
        assertThat(again.title()).isEqualTo("Perp A");
        assertThat(again.oracleIndex()).isEqualTo(1);
        assertThat(listener.events).containsExactly("listed " + PREFIX + "A");
        assertThat(registry(null).all()).hasSize(2); // BTC + A, no second row
    }

    @Test
    void anOracleIndexIsNeverReusedEvenAfterAMarketClosed() {
        DbMarketRegistry registry = registry(null);
        Market a = registry.list(perp("A", Category.CRYPTO, 25, 1), 1_000L);
        registry.close(a.id(), "gone", 2_000L);

        Market b = registry.list(perp("B", Category.CRYPTO, 25, 1), 3_000L);

        assertThat(a.oracleIndex()).isEqualTo(1);
        assertThat(b.oracleIndex()).isEqualTo(2);
    }

    @Test
    void closePersistsStatusTimeAndReasonAndNotifies() {
        Recording listener = new Recording();
        DbMarketRegistry registry = registry(listener);
        Market a = registry.list(odds("A"), 1_000L);
        listener.events.clear();

        registry.close(a.id(), "closed at the source", 5_000L);

        Market closed = registry.require(a.id());
        assertThat(closed.status()).isEqualTo(MarketStatus.CLOSED);
        assertThat(closed.closedAtMs()).isEqualTo(5_000L);
        assertThat(closed.closeReason()).isEqualTo("closed at the source");
        assertThat(closed.oracleIndex()).isEqualTo(a.oracleIndex());
        assertThat(registry.active()).extracting(Market::id).doesNotContain(a.id());
        assertThat(registry.all()).extracting(Market::id).contains(a.id()); // history stays readable
        assertThat(listener.events).containsExactly("closed " + a.id() + " closed at the source");

        // durable
        Market reloaded = registry(null).require(a.id());
        assertThat(reloaded.status()).isEqualTo(MarketStatus.CLOSED);
        assertThat(reloaded.closedAtMs()).isEqualTo(5_000L);
        assertThat(reloaded.closeReason()).isEqualTo("closed at the source");
    }

    @Test
    void closingTwiceOrAnUnknownMarketIsANoOp() {
        Recording listener = new Recording();
        DbMarketRegistry registry = registry(listener);
        Market a = registry.list(perp("A", Category.CRYPTO, 25, 1), 1_000L);
        registry.close(a.id(), "first", 2_000L);
        listener.events.clear();

        registry.close(a.id(), "second", 3_000L);
        registry.close("RT_NOPE", "unknown", 3_000L);

        assertThat(listener.events).isEmpty();
        assertThat(registry.require(a.id()).closeReason()).isEqualTo("first");
        assertThat(registry.require(a.id()).closedAtMs()).isEqualTo(2_000L);
    }

    @Test
    void btcCannotBeClosed() {
        DbMarketRegistry registry = registry(null);

        assertThatThrownBy(() -> registry.close("BTC", "no", 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.require("BTC").isActive()).isTrue();
    }

    @Test
    void theListenerIsToldAfterTheCommitAndOutsideTheRegistrysLocks() throws Exception {
        AtomicBoolean checked = new AtomicBoolean();
        List<String> findings = new CopyOnWriteArrayList<>();
        DbMarketRegistry[] holder = new DbMarketRegistry[1];
        MarketListener probing = new MarketListener() {
            @Override
            public void onListed(Market market) {
                if (!market.id().equals(PREFIX + "PROBE") || !checked.compareAndSet(false, true)) {
                    return;
                }
                // 1. the transaction has committed: another connection sees the row
                Object status = row(market.id()) == null ? null : row(market.id()).get("status");
                findings.add("row visible from another connection: " + status);
                // 2. no registry lock is held: another thread can list a market while we are in here
                Thread other = new Thread(() -> holder[0].list(perp("OTHER", Category.CRYPTO, 10, 0), 2_000L));
                other.start();
                try {
                    other.join(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                findings.add(other.isAlive() ? "another thread was blocked" : "another thread was not blocked");
                // 3. the registry can be read from here
                findings.add("readable: " + holder[0].byId(market.id()).isPresent());
            }

            @Override
            public void onClosed(Market market, String reason) {
                // not used
            }
        };
        holder[0] = registry(probing);

        holder[0].list(perp("PROBE", Category.CRYPTO, 25, 1), 1_000L);

        assertThat(findings).containsExactly("row visible from another connection: ACTIVE",
                "another thread was not blocked", "readable: true");
    }

    @Test
    void categoriesAreTheOrderedOnesWithAnActiveMarket() {
        DbMarketRegistry registry = registry(null);
        assertThat(registry.categories()).extracting(Category::id).containsExactly(Category.CRYPTO);

        registry.list(odds("P"), 1_000L);
        Market stock = registry.list(perp("S", Category.STOCKS, 10, 1), 1_000L);
        // display order, not listing order
        assertThat(registry.categories()).extracting(Category::id)
                .containsExactly(Category.CRYPTO, Category.POLITICS, Category.STOCKS);

        registry.close(stock.id(), "delisted", 2_000L);
        assertThat(registry.categories()).extracting(Category::id).containsExactly(Category.CRYPTO, Category.POLITICS);
    }

    @Test
    void aBadListingIsRejectedBeforeItTouchesTheTable() {
        DbMarketRegistry registry = registry(null);
        int before = registry.all().size();

        assertThatThrownBy(() -> registry.list(new NewMarket("bad-id", MarketKind.PERP, Category.CRYPTO, "X", "X", null,
                null, "synthetic", "X", null, 10, 0, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(new NewMarket(PREFIX + "C", MarketKind.PERP, "weather", "X", "X", null,
                null, "synthetic", "X", null, 10, 0, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(perp("L", Category.CRYPTO, 10, 9), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(perp("L", Category.CRYPTO, 41, 0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(perp("L", Category.CRYPTO, 1, 0), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(new NewMarket("BTC", MarketKind.PERP, Category.CRYPTO, "BTC", "Bitcoin",
                null, null, "hyperliquid", "BTC", null, 40, 0, null), 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.list(new NewMarket(PREFIX + "M", MarketKind.PERP, Category.CRYPTO, "X", "X",
                null, null, "synthetic", "X", "{not json", 10, 0, null), 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(registry.all()).hasSize(before);
        assertThat(registry(null).all()).hasSize(before);
    }

    @Test
    void sourceMetaIsStoredAsJsonAndReadBack() throws Exception {
        DbMarketRegistry registry = registry(null);

        Market market = registry.list(odds("J"), 1_000L);

        JsonNode meta = db.json().readTree(registry(null).require(market.id()).sourceMeta());
        assertThat(meta.path("conditionId").asText()).isEqualTo("0xabc");
        String storedType = db.read(c -> db.queryOne(c, "SELECT pg_typeof(source_meta)::text AS t FROM markets "
                + "WHERE id = ?", rs -> rs.getString("t"), market.id()));
        assertThat(storedType).isEqualTo("jsonb");
        assertThat(registry(null).require(market.id()).iconUrl()).isEqualTo("https://example.invalid/icon.png");
        assertThat(registry(null).require(market.id()).subtitle()).isEqualTo("Yes · UEFA");
    }

    @Test
    void anUnknownMarketIsMarketNotFound() {
        DbMarketRegistry registry = registry(null);

        assertThat(registry.byId("RT_NOPE")).isEmpty();
        assertThatThrownBy(() -> registry.require("RT_NOPE"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.MARKET_NOT_FOUND);
                    assertThat(e.httpStatus()).isEqualTo(404);
                });
    }

    @Test
    void concurrentListingsGetDistinctConsecutiveIndexes() throws Exception {
        // two registries over one table = two processes: the table lock serializes the MAX+1
        DbMarketRegistry one = registry(null);
        DbMarketRegistry two = registry(null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Market> listed = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    DbMarketRegistry target = n % 2 == 0 ? one : two;
                    listed.add(target.list(perp("C" + n, Category.CRYPTO, 10, 0), 1_000L + n));
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        Set<Integer> indexes = new TreeSet<>();
        listed.forEach(m -> indexes.add(m.oracleIndex()));
        assertThat(indexes).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void reloadPicksUpWhatAnotherInstanceWrote() {
        DbMarketRegistry reader = registry(null);
        DbMarketRegistry writer = registry(null);
        Market written = writer.list(perp("W", Category.CRYPTO, 25, 1), 1_000L);
        assertThat(reader.byId(written.id())).isEmpty(); // a snapshot, until it is reloaded

        reader.reload();

        assertThat(reader.byId(written.id())).contains(written);
    }
}
