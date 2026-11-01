package crowdvsmarket.server.service;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PriceBar;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code candles(marketId, minutes?)} (contract.ts {@code Api.candles}): the finished 1-minute candles of a market, oldest
 * first - the read-model the bots read their rules' history from, and any client that wants a candle chart. Built from the
 * live ticks the price feed already delivers ({@code TickStore}, {@code MinuteBars}), kept for three hours per market in a
 * fixed ring: a request is a copy of at most {@link #MAX_MINUTES} bars from memory, never the engine's lock.
 *
 * <p><b>Exchanges (D-76).</b> Like {@code priceSeries}, the candles of a market with a backup exchange are those of its
 * <i>primary</i> exchange only; while the primary is silent there are holes, not the backup's bars stitched in.
 *
 * <p><b>Only finished minutes.</b> The minute in progress is not in the answer; a minute without a tick has no candle (the
 * list can have holes - consecutive candles are not always one minute apart). After a restart the ring is rebuilt from the
 * stored price snapshots ({@code TickStore#restore}), so the history comes back.
 *
 * <p>A closed market stops: its candles end with the last minute before it closed. {@code mode=replay} has no ticks of its
 * own (see {@code PriceSeriesService}): the answer for BTC is empty, every other market is {@code MARKET_NOT_FOUND}.
 */
@ApplicationScoped
public class CandleService {

    /** The default look-back of the RPC: two hours, what the longest bot rule needs and then some. */
    public static final int DEFAULT_MINUTES = 120;
    /** The most minutes one call returns: what the ring keeps, less the minute in progress. */
    public static final int MAX_MINUTES = 179;

    private final MarketRegistry marketRegistry;
    private final MarketPrices marketPrices;
    private final GameConfig config;

    @Inject
    public CandleService(MarketRegistry marketRegistry, MarketPrices marketPrices, GameConfig config) {
        this.marketRegistry = marketRegistry;
        this.marketPrices = marketPrices;
        this.config = config;
    }

    /**
     * @param minutes {@code null}: {@link #DEFAULT_MINUTES}; otherwise clamped to {@code 1..}{@link #MAX_MINUTES}
     * @throws ApiException {@code MARKET_NOT_FOUND} for a market that was never listed - or, in {@code mode=replay}, is not BTC
     */
    public Markets.CandlesDto candles(String marketId, Integer minutes, long nowMs) {
        Market market = marketRegistry.require(marketId);
        boolean replay = config.mode() != GameMode.LIVE;
        if (replay && !Market.BTC_ID.equals(market.id())) {
            throw ApiException.marketNotFound(marketId);
        }
        int asked = minutes == null ? DEFAULT_MINUTES : Math.max(1, Math.min(minutes, MAX_MINUTES));
        long closedAt = market.closedAtMs() == null || market.isActive() ? Long.MAX_VALUE : market.closedAtMs();
        long endMs = Math.min(nowMs, closedAt);
        List<PriceBar> bars = replay ? List.of() : marketPrices.bars(market.id(), asked, endMs);
        List<Markets.CandleDto> out = new ArrayList<>(bars.size());
        for (PriceBar bar : bars) {
            out.add(new Markets.CandleDto(bar.openTimeMs(), MarketMapper.rawPrice(market, bar.open()),
                    MarketMapper.rawPrice(market, bar.high()), MarketMapper.rawPrice(market, bar.low()),
                    MarketMapper.rawPrice(market, bar.close())));
        }
        return new Markets.CandlesDto(market.id(), market.priceDecimals(), out);
    }
}
