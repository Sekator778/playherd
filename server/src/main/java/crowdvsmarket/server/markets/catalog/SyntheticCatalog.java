package crowdvsmarket.server.markets.catalog;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.HistorySeeder;
import crowdvsmarket.server.prices.SyntheticParams;
import crowdvsmarket.server.prices.SyntheticPriceSource;
import io.quarkus.arc.Unremovable;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * The offline catalog ({@code herd.prices.source=synthetic}, markets_architecture.md T-12): lists the
 * configured PERP names ({@code herd.markets.crypto|stocks|indices|commodities}) with the built-in
 * {@link SyntheticParams parameter table} (BTC 40x at 84000, ETH 25x at 2700, … unknown names 10x at
 * 100) — with no {@code herd.markets.crypto} configured, the six crypto markets the list had before the crypto markets
 * moved to their venues ({@link CatalogConfig#syntheticPerpNames}): the walk keeps its own ids ({@code ETH}, never
 * {@code spot_ETH}) and has nothing to do with the Coinbase and Hyperliquid spot lists — and
 * {@code herd.markets.politics.count} + {@code herd.markets.sports.count} synthetic ODDS
 * markets {@code PMSYN1…} (politics first, then sports) whose probabilities start between 0.3 and
 * 0.7. No HTTP. The start price goes into {@code sourceMeta} as {@code {"start":"…"}}, which
 * {@link SyntheticPriceSource} walks from. BTC already exists (the V7 migration) and is left as it is.
 *
 * <p>Runs only with {@code herd.mode=live} and {@code herd.prices.source=synthetic}; listing is
 * idempotent, so running every minute costs nothing once everything is listed.
 */
@ApplicationScoped
@Unremovable
public class SyntheticCatalog {

    private static final Logger LOG = Logger.getLogger(SyntheticCatalog.class);

    static final String EVERY = "${herd.markets.synthetic.catalog-every:1m}";
    static final String JOB = "catalog-synthetic";
    static final String ID_PREFIX = "PMSYN";

    private static final Pattern LEGAL_ID = Pattern.compile("[A-Za-z0-9_]{1,32}");

    private final MarketRegistry registry;
    private final CatalogConfig config;
    private final HistorySeeder seeder;
    private final LongSupplier clock;

    @Inject
    public SyntheticCatalog(MarketRegistry registry, CatalogConfig config, HistorySeeder seeder) {
        this(registry, config, seeder, System::currentTimeMillis);
    }

    SyntheticCatalog(MarketRegistry registry, CatalogConfig config, LongSupplier clock) {
        this(registry, config, HistorySeeder.disabled(), clock);
    }

    SyntheticCatalog(MarketRegistry registry, CatalogConfig config, HistorySeeder seeder, LongSupplier clock) {
        this.registry = registry;
        this.config = config;
        this.seeder = seeder;
        this.clock = clock;
    }

    @Scheduled(every = EVERY, delayed = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = JOB)
    void run() {
        if (config.syntheticSource()) {
            sync(clock.getAsLong());
        }
    }

    /** Lists whatever is not listed yet; returns how many markets this pass added. */
    int sync(long nowMs) {
        int added = 0;
        for (CatalogConfig.PerpName wanted : config.syntheticPerpNames()) {
            String id = MarketNames.idOf(wanted.name());
            if (!LEGAL_ID.matcher(id).matches() || registry.byId(id).isPresent()) {
                continue;
            }
            String symbol = MarketNames.symbolOf(wanted.name());
            SyntheticParams.Param param = SyntheticParams.forSymbol(symbol);
            if (list(new NewMarket(id, MarketKind.PERP, wanted.category(), symbol, MarketNames.title(symbol), null,
                    null, SyntheticPriceSource.NAME, wanted.name(), startMeta(param.start()),
                    param.maxLeverage(), Market.lotExpFor(param.start()), null), nowMs)) {
                added++;
            }
        }
        int politics = config.politicsCount();
        int total = politics + config.sportsCount();
        for (int n = 1; n <= total; n++) {
            String id = ID_PREFIX + n;
            if (registry.byId(id).isPresent()) {
                continue;
            }
            boolean isPolitics = n <= politics;
            BigDecimal start = SyntheticPriceSource.oddsStart(id);
            String kind = isPolitics ? "politics" : "sports";
            if (list(new NewMarket(id, MarketKind.ODDS, isPolitics ? Category.POLITICS : Category.SPORTS,
                    (isPolitics ? "SYNP" : "SYNS") + (isPolitics ? n : n - politics),
                    "Synthetic " + kind + " market " + (isPolitics ? n : n - politics), "Yes \u00b7 synthetic " + kind,
                    null, SyntheticPriceSource.NAME, id, startMeta(start), config.oddsMaxLeverage(),
                    Market.ODDS_LOT_EXP, null), nowMs)) {
                added++;
            }
        }
        if (added > 0) {
            LOG.infof("listed %d synthetic market(s)", added);
        }
        return added;
    }

    /**
     * One listing; a registry that refuses it costs that market this pass, the next run tries again.
     * Called without any lock held: the registry's listener takes the game engine's write lock on this
     * thread, while the game service reads prices under its read lock.
     */
    private boolean list(NewMarket market, long nowMs) {
        Market listed;
        try {
            listed = registry.list(market, nowMs);
        } catch (RuntimeException e) {
            LOG.warnf("could not list synthetic market %s, will try again: %s", market.id(), e.toString());
            return false;
        }
        seeder.seed(listed); // the walk of its last minutes, so that its first round has an opening price
        return true;
    }

    private static String startMeta(BigDecimal start) {
        return JsonNodeFactory.instance.objectNode().put("start", start.toPlainString()).toString();
    }
}
