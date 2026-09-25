package crowdvsmarket.server.price;

import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.live.HyperliquidClient;
import crowdvsmarket.server.live.LivePriceSampler;
import crowdvsmarket.server.live.LiveRoundPrices;
import crowdvsmarket.server.replay.CandleLoader;
import crowdvsmarket.server.replay.ReplayRoundPrices;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Picks the {@link RoundPrices} implementation for the configured {@code herd.mode} (Р-46) and
 *  exposes a "reference price right now" for {@code btcReferencePrice()}/{@code
 *  previewLiquidation()}, which is not tied to any particular round window. */
@ApplicationScoped
public class PriceService {

    @Inject
    GameConfig config;

    @Inject
    RoundCalendar roundCalendar;

    @Inject
    LivePriceSampler livePriceSampler;

    @RestClient
    HyperliquidClient hyperliquid;

    private volatile java.util.List<crowdvsmarket.engine.market.Candle> replayCandles;

    @PostConstruct
    void init() {
        if (config.mode() == GameMode.REPLAY) {
            replayCandles = CandleLoader.loadFromClasspath("/replay/btc_1m.csv");
        }
    }

    public RoundPrices roundPrices() {
        return switch (config.mode()) {
            case REPLAY -> new ReplayRoundPrices(replayCandles, config.replaySpeedup(), roundCalendar.originMs());
            case LIVE -> new LiveRoundPrices(livePriceSampler, hyperliquid);
        };
    }

    public record Reference(BigDecimal price, long asOfMs) {
    }

    public Reference currentReference(long nowMs) {
        if (config.mode() == GameMode.REPLAY) {
            ReplayRoundPrices prices = new ReplayRoundPrices(replayCandles, config.replaySpeedup(), roundCalendar.originMs());
            return new Reference(prices.priceAt(nowMs), nowMs);
        }
        var ticks = livePriceSampler.ticksIn(nowMs - 10_000L, nowMs + 1);
        if (!ticks.isEmpty()) {
            var last = ticks.get(ticks.size() - 1);
            return new Reference(last.price(), last.atMs());
        }
        // No recent tick buffered (e.g. server just started) — do one synchronous live read.
        BigDecimal markPx = LivePriceSampler.extractBtcMarkPx(hyperliquid.info(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "metaAndAssetCtxs")));
        if (markPx == null) {
            throw new IllegalStateException("no live BTC price available");
        }
        return new Reference(markPx, nowMs);
    }
}
