package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.Dec;
import crowdvsmarket.server.prices.HyperliquidInfoApi;
import crowdvsmarket.server.prices.HyperliquidPriceSource;
import crowdvsmarket.server.prices.PriceHttpClients;
import io.quarkus.arc.Unremovable;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Lists the PERP markets of the configuration ({@code herd.markets.crypto|stocks|indices|
 * commodities}) from Hyperliquid's own parameters (docs/research/markets_architecture.md §3.3):
 * for every configured coin name that the registry does not know, one {@code metaAndAssetCtxs}
 * answer per dex gives {@code maxLeverage}, {@code szDecimals}, {@code marginTableId} and the
 * current {@code markPx}, from which the market is made — leverage {@code min(40, maxLeverage)}
 * (Т-4), lot exponent {@link Market#lotExpFor} of the current price (Т-3, fixed from then on), id
 * the coin name with {@code ':'} turned into {@code '_'}, symbol the part after the dex prefix.
 *
 * <p>A name the dex does not list, a delisted one, one on a dex the price pipeline does not poll,
 * or one whose id would not be a legal market id is <b>known bad</b>: warned about once and not
 * asked for again until restart. Anything transient (the source unreachable, no usable price) is
 * simply tried again at the next run, every minute, until every configured name is listed or known
 * bad; after that a run costs nothing (no request). BTC is in the registry from the V7 migration
 * and is never listed again.
 *
 * <p>Runs only with {@code herd.mode=live} and {@code herd.prices.source=live}, on its own
 * {@code @Scheduled(concurrentExecution = SKIP)}.
 */
@ApplicationScoped
@Unremovable
public class HyperliquidCatalog {

    private static final Logger LOG = Logger.getLogger(HyperliquidCatalog.class);

    static final String EVERY = "${herd.markets.hyperliquid.catalog-every:1m}";
    static final String JOB = "catalog-hyperliquid";
    /** Т-4: no market lets a player take more than 40x, whatever the perp allows. */
    static final int LEVERAGE_CAP = 40;

    private static final Pattern LEGAL_ID = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private final MarketRegistry registry;
    private final CatalogConfig config;
    private final Supplier<HyperliquidInfoApi> api;
    private final LongSupplier clock;
    private final Set<String> knownBad = ConcurrentHashMap.newKeySet();

    @Inject
    public HyperliquidCatalog(MarketRegistry registry, CatalogConfig config, PriceHttpClients clients) {
        this(registry, config, clients.hyperliquid(), System::currentTimeMillis);
    }

    HyperliquidCatalog(MarketRegistry registry, CatalogConfig config, Supplier<HyperliquidInfoApi> api,
                       LongSupplier clock) {
        this.registry = registry;
        this.config = config;
        this.api = api;
        this.clock = clock;
    }

    @Scheduled(every = EVERY, delayed = "3s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = JOB)
    void run() {
        if (config.liveSources()) {
            sync(clock.getAsLong());
        }
    }

    /** What the answer says about one asset. */
    record Asset(int maxLeverage, int szDecimals, Integer marginTableId, boolean delisted, BigDecimal markPx) {
    }

    /**
     * One pass over the configured names; returns how many are still waiting for a later pass.
     */
    int sync(long nowMs) {
        Map<String, List<CatalogConfig.PerpName>> byDex = new LinkedHashMap<>();
        for (CatalogConfig.PerpName wanted : config.perpNames()) {
            if (registry.byId(MarketNames.idOf(wanted.name())).isEmpty() && !knownBad.contains(wanted.name())) {
                byDex.computeIfAbsent(HyperliquidPriceSource.dexOf(wanted.name()), d -> new ArrayList<>()).add(wanted);
            }
        }
        int pending = 0;
        for (Map.Entry<String, List<CatalogConfig.PerpName>> group : byDex.entrySet()) {
            String dex = group.getKey();
            if (!HyperliquidPriceSource.DEXES.contains(dex)) {
                for (CatalogConfig.PerpName wanted : group.getValue()) {
                    markBad(wanted.name(), "dex '" + dex + "' is not one the price pipeline polls");
                }
                continue;
            }
            Map<String, Asset> assets;
            try {
                assets = readAssets(api.get().info(HyperliquidPriceSource.metaAndAssetCtxsRequest(dex)));
            } catch (Exception e) {
                LOG.warnf("Hyperliquid meta of dex '%s' unavailable, %d market(s) wait for the next run: %s", dex,
                        group.getValue().size(), e.toString());
                pending += group.getValue().size();
                continue;
            }
            for (CatalogConfig.PerpName wanted : group.getValue()) {
                pending += list(wanted, assets.get(wanted.name()), nowMs);
            }
        }
        return pending;
    }

    /** Lists one name or marks it known bad; returns 1 when it has to be tried again. */
    private int list(CatalogConfig.PerpName wanted, Asset asset, long nowMs) {
        String name = wanted.name();
        String id = MarketNames.idOf(name);
        if (!LEGAL_ID.matcher(id).matches()) {
            markBad(name, "'" + id + "' is not a legal market id");
            return 0;
        }
        if (asset == null) {
            markBad(name, "not in the dex's universe");
            return 0;
        }
        if (asset.delisted()) {
            markBad(name, "delisted");
            return 0;
        }
        if (asset.maxLeverage() < 2) {
            markBad(name, "max leverage " + asset.maxLeverage() + " is below the 2x a market needs");
            return 0;
        }
        if (asset.markPx() == null || asset.markPx().signum() <= 0) {
            LOG.warnf("%s has no usable markPx yet, will try again", name);
            return 1;
        }
        String symbol = MarketNames.symbolOf(name);
        ObjectNode meta = JsonNodeFactory.instance.objectNode().put("dex", HyperliquidPriceSource.dexOf(name))
                .put("szDecimals", asset.szDecimals());
        if (asset.marginTableId() != null) {
            meta.put("marginTableId", asset.marginTableId());
        }
        NewMarket market = new NewMarket(id, MarketKind.PERP, wanted.category(), symbol, MarketNames.title(symbol),
                null, null, HyperliquidPriceSource.NAME, name, meta.toString(),
                Math.min(LEVERAGE_CAP, asset.maxLeverage()), Market.lotExpFor(asset.markPx()), null);
        try {
            Market listed = registry.list(market, nowMs);
            LOG.infof("listed %s (%s, %dx, lot exponent %d) at markPx %s", listed.id(), wanted.category(),
                    listed.maxLeverage(), listed.lotExp(), asset.markPx().toPlainString());
            return 0;
        } catch (RuntimeException e) {
            LOG.warnf("could not list %s, will try again: %s", name, e.toString());
            return 1;
        }
    }

    private void markBad(String name, String why) {
        if (knownBad.add(name)) {
            LOG.warnf("not listing Hyperliquid market %s: %s", name, why);
        }
    }

    /** {@code [meta, ctxs]} → asset by coin name (delisted ones included, flagged). */
    static Map<String, Asset> readAssets(JsonNode response) {
        Map<String, Asset> out = new HashMap<>();
        if (response == null || !response.isArray() || response.size() < 2) {
            throw new IllegalStateException("unexpected answer to metaAndAssetCtxs: " + response);
        }
        JsonNode universe = response.get(0).path("universe");
        JsonNode ctxs = response.get(1);
        int n = Math.min(universe.size(), ctxs.size());
        for (int i = 0; i < n; i++) {
            JsonNode u = universe.get(i);
            String name = u.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            JsonNode table = u.path("marginTableId");
            out.putIfAbsent(name, new Asset(u.path("maxLeverage").asInt(0), u.path("szDecimals").asInt(0),
                    table.isNumber() ? table.asInt() : null, u.path("isDelisted").asBoolean(false),
                    Dec.of(ctxs.get(i).path("markPx"))));
        }
        return out;
    }
}
