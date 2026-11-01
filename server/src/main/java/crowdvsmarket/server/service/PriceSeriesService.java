package crowdvsmarket.server.service;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.price.PriceService;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PricesConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code priceSeries(marketId, sinceMs?)} (contract.ts {@code Api.priceSeries}): the ticks of a market for
 * the room's live chart, oldest first, as raw price strings with the market's {@code priceDecimals} — the
 * last 15 minutes, or with {@code sinceMs} the ticks with {@code atMs > sinceMs} (what a chart that polls
 * asks for, with the {@code atMs} of the newest tick it has). At most {@link #MAX_POINTS} points: when more
 * exist they are thinned evenly over the whole requested range, the newest tick always kept
 * ({@link PriceThinning}). Memory only (the price buffer, {@link MarketPrices#series}); needs no engine, so
 * it is answered during a recovery too.
 *
 * <p><b>Exchanges (D-76).</b> The chart of a market with a backup exchange is the ticks of its <i>primary</i> exchange only.
 * The series has no field to say which exchange a tick is from (and changing {@code contract.ts} for it is not worth it),
 * and a line stitched from two exchanges would draw the basis between them as a jump that never happened on either.
 * While the primary is silent the chart has a gap, and the market's price ({@code markets}) is the backup's, saying so
 * in {@code price.venue}. What a round settles from is its own exchange's ticks, whichever that is.
 *
 * <p><b>Closed market.</b> It stops ticking: its "last 15 minutes" are the 15 minutes before it closed, so
 * that the room of a market that has just left still draws its final stretch (the web mock does the same).
 *
 * <p><b>{@code mode=replay}</b> (decision, the brief left it open): the replay has no ticks of its own —
 * only candles on an accelerated virtual clock — so its "own recent path" is the replay series SAMPLED, one
 * tick per whole second of wall-clock time, each carrying what {@code referencePrice("BTC")} would have said
 * at that second ({@link PriceService#replayReference}: the open of the source candle the virtual clock is
 * at; at the default speedup of 20 a candle lasts three seconds, so every price shows up three times in
 * a row — the chart is a step line, like the replay itself). BTC only; every other market is {@code
 * MARKET_NOT_FOUND} there, as it is not played. The look-back is capped at two hours of wall-clock time
 * (7 200 samples before thinning).
 */
@ApplicationScoped
public class PriceSeriesService {

    /** contract.ts: "at most 600 points". */
    static final int MAX_POINTS = 600;
    /** contract.ts: without {@code sinceMs}, "ticks of the last 15 minutes". */
    static final long DEFAULT_WINDOW_MS = 15 * 60_000L;
    /** The most replay mode samples ever look back, whatever {@code sinceMs} says. */
    static final long REPLAY_MAX_LOOKBACK_MS = 2 * 3_600_000L;
    private static final long REPLAY_SAMPLE_MS = 1_000L;

    private final MarketRegistry marketRegistry;
    private final MarketPrices marketPrices;
    private final GameConfig config;
    private final PricesConfig pricesConfig;
    private final PriceService priceService;

    @Inject
    public PriceSeriesService(MarketRegistry marketRegistry, MarketPrices marketPrices, GameConfig config,
                              PricesConfig pricesConfig, PriceService priceService) {
        this.marketRegistry = marketRegistry;
        this.marketPrices = marketPrices;
        this.config = config;
        this.pricesConfig = pricesConfig;
        this.priceService = priceService;
    }

    /**
     * @param sinceMs {@code null}: the last 15 minutes; otherwise the ticks strictly after it
     * @throws ApiException {@code MARKET_NOT_FOUND} for a market that was never listed — or, in {@code
     *         mode=replay}, is not BTC
     */
    public Markets.PriceSeriesDto priceSeries(String marketId, Long sinceMs, long nowMs) {
        Market market = marketRegistry.require(marketId);
        boolean replay = config.mode() != GameMode.LIVE;
        if (replay && !Market.BTC_ID.equals(market.id())) {
            throw ApiException.marketNotFound(marketId);
        }
        long closedAt = market.closedAtMs() == null || market.isActive() ? Long.MAX_VALUE : market.closedAtMs();
        long endMs = Math.min(nowMs, closedAt);
        long lookbackMs = replay ? Math.min(pricesConfig.retentionMs(), REPLAY_MAX_LOOKBACK_MS)
                : pricesConfig.retentionMs();
        long fromMs = sinceMs == null ? endMs - DEFAULT_WINDOW_MS : Math.min(sinceMs, Long.MAX_VALUE - 1) + 1;
        fromMs = Math.max(fromMs, endMs - lookbackMs); // the buffer holds no more than that anyway

        // A since at or after the end of the range has nothing after it — and in replay mode, where the ticks
        // are computed, not read, must not be walked towards (a since near Long.MAX_VALUE is a client's way of
        // asking for every second there ever was)
        List<PricePoint> points = fromMs > endMs ? List.of()
                : replay ? replayTicks(fromMs, endMs)
                : marketPrices.series(market.id(), fromMs, endMs + 1); // the buffer's range end is exclusive
        List<Markets.PriceTickDto> ticks = new ArrayList<>(Math.min(points.size(), MAX_POINTS));
        for (PricePoint point : PriceThinning.thin(points, MAX_POINTS)) {
            ticks.add(new Markets.PriceTickDto(point.atMs(), MarketMapper.rawPrice(market, point.raw())));
        }
        return new Markets.PriceSeriesDto(market.id(), market.priceDecimals(), ticks);
    }

    /** The replay series, one sample per whole second in {@code [fromMs, toMs]}. */
    private List<PricePoint> replayTicks(long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        long first = Math.floorDiv(fromMs + REPLAY_SAMPLE_MS - 1, REPLAY_SAMPLE_MS) * REPLAY_SAMPLE_MS;
        for (long at = first; at <= toMs; at += REPLAY_SAMPLE_MS) {
            out.add(new PricePoint(at, priceService.replayReference(at).price()));
        }
        return out;
    }
}
