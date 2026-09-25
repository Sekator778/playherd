package crowdvsmarket.server.live;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * mode=live price source: builds the round's candle path from {@link LivePriceSampler}'s tick
 * buffer, one degenerate (open=high=low=close) {@link Candle} per tick — sufficient for
 * {@code PositionSimulator}, which only reads {@code low}/{@code high} for the liquidation check
 * and the last candle's {@code close} for settlement. Р-23 fallback: if the buffer has no ticks at
 * all for the window (e.g. server just started, or Hyperliquid was unreachable the whole time),
 * falls back once to a single Hyperliquid {@code candleSnapshot} 1-minute candle covering
 * {@code window.startMs()}; if that also fails, throws — the caller ({@code RoundSchedulerService})
 * catches this, retries on later ticks, and only cancels the round (Р-23: "если за 1 час цену
 * получить не удалось") once {@code window.endMs() + 1h} has passed with still no price.
 */
public final class LiveRoundPrices implements RoundPrices {

    private static final Logger LOG = Logger.getLogger(LiveRoundPrices.class);

    private final LivePriceSampler sampler;
    private final HyperliquidClient hyperliquid;

    public LiveRoundPrices(LivePriceSampler sampler, HyperliquidClient hyperliquid) {
        this.sampler = sampler;
        this.hyperliquid = hyperliquid;
    }

    @Override
    public List<Candle> path(RoundWindow window) {
        List<LivePriceSampler.Tick> ticks = sampler.ticksIn(window.startMs(), window.endMs());
        if (!ticks.isEmpty()) {
            List<Candle> path = new ArrayList<>(ticks.size());
            for (int i = 0; i < ticks.size(); i++) {
                LivePriceSampler.Tick t = ticks.get(i);
                long closeAt = i + 1 < ticks.size() ? ticks.get(i + 1).atMs() : t.atMs() + 1;
                path.add(new Candle(t.atMs(), closeAt, t.price(), t.price(), t.price(), t.price(), BigDecimal.ZERO, 0));
            }
            return path;
        }
        Candle fallback = fetchFallbackCandle(window.startMs());
        if (fallback == null) {
            throw new IllegalStateException("no live price available yet for round " + window.roundId());
        }
        return List.of(fallback);
    }

    @Override
    public BigDecimal openPrice(RoundWindow window) {
        return path(window).get(0).open();
    }

    @Override
    public BigDecimal closePrice(RoundWindow window) {
        List<Candle> path = path(window);
        return path.get(path.size() - 1).close();
    }

    /** Р-23: "берём минутную свечу Hyperliquid за этот момент из истории (candleSnapshot)". */
    private Candle fetchFallbackCandle(long atMs) {
        try {
            JsonNode req = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("type", "candleSnapshot")
                    .set("req", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                            .put("coin", "BTC").put("interval", "1m")
                            .put("startTime", atMs - 60_000L).put("endTime", atMs + 60_000L));
            JsonNode response = hyperliquid.info(req);
            if (response == null || !response.isArray() || response.isEmpty()) {
                return null;
            }
            JsonNode row = response.get(0);
            return new Candle(row.path("t").asLong(), row.path("T").asLong(), new BigDecimal(row.path("o").asText()),
                    new BigDecimal(row.path("h").asText()), new BigDecimal(row.path("l").asText()),
                    new BigDecimal(row.path("c").asText()), new BigDecimal(row.path("v").asText("0")),
                    row.path("n").asLong(0));
        } catch (Exception e) {
            LOG.warnf(e, "candleSnapshot fallback failed for atMs=%d", atMs);
            return null;
        }
    }
}
