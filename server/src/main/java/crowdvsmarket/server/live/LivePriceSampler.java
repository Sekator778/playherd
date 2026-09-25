package crowdvsmarket.server.live;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * mode=live: polls Hyperliquid {@code markPx} every 2 s (docs/research/hl_liquidation_math.md §4,
 * "this field reads our publisher... for margining/liquidations/uPnL") and keeps an in-memory tick
 * buffer {@link LiveRoundPrices} builds degenerate one-price candles from. Предложение: the buffer
 * is in-memory only (not persisted) — a restart mid-live-round loses its accumulated ticks; since
 * mode=replay is the default and only tested path for the local stand (Р-46), this is an accepted
 * gap for mode=live, noted in docs/research/server_design.md rather than solved here.
 */
@ApplicationScoped
public class LivePriceSampler {

    private static final Logger LOG = Logger.getLogger(LivePriceSampler.class);
    private static final int MAX_TICKS = 4 * 3600 / 2; // ~4h at one tick/2s, generous for a DAILY round

    public record Tick(long atMs, BigDecimal price) {
    }

    @Inject
    GameConfig config;

    @RestClient
    HyperliquidClient hyperliquid;

    private final Deque<Tick> ticks = new ArrayDeque<>();

    @Scheduled(every = "2s")
    void poll() {
        if (config.mode() != GameMode.LIVE) {
            return;
        }
        try {
            JsonNode body = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("type", "metaAndAssetCtxs");
            JsonNode response = hyperliquid.info(body);
            BigDecimal markPx = extractBtcMarkPx(response);
            if (markPx != null) {
                addTick(new Tick(System.currentTimeMillis(), markPx));
            }
        } catch (Exception e) {
            LOG.warnf(e, "live markPx poll failed (never blocks the game)");
        }
    }

    public static BigDecimal extractBtcMarkPx(JsonNode response) {
        if (response == null || !response.isArray() || response.size() < 2) {
            return null;
        }
        JsonNode universe = response.get(0).path("universe");
        JsonNode assetCtxs = response.get(1);
        for (int i = 0; i < universe.size(); i++) {
            if ("BTC".equals(universe.get(i).path("name").asText())) {
                JsonNode markPx = assetCtxs.get(i).path("markPx");
                return markPx.isMissingNode() ? null : new BigDecimal(markPx.asText());
            }
        }
        return null;
    }

    synchronized void addTick(Tick tick) {
        ticks.addLast(tick);
        while (ticks.size() > MAX_TICKS) {
            ticks.removeFirst();
        }
    }

    /** Ticks with {@code fromMs <= atMs < toMs}, oldest first. */
    public synchronized List<Tick> ticksIn(long fromMs, long toMs) {
        List<Tick> out = new ArrayList<>();
        for (Tick t : ticks) {
            if (t.atMs() >= fromMs && t.atMs() < toMs) {
                out.add(t);
            }
        }
        return out;
    }
}
