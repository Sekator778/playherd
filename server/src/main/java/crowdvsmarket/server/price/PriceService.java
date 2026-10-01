package crowdvsmarket.server.price;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.replay.CandleLoader;
import crowdvsmarket.server.replay.ReplayRoundPrices;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;

/** The price source of {@code herd.mode=replay} (Р-46): the accelerated replay of
 *  {@code replay/btc_1m.csv}, BTC only, kept for tests and for working without a network
 *  (docs/research/markets_architecture.md §3.2 п.7, Т-12). In {@code herd.mode=live} the prices come
 *  from {@code crowdvsmarket.server.prices.MarketPrices} (Р-70) and this class is not used. */
@ApplicationScoped
public class PriceService {

    @Inject
    GameConfig config;

    @Inject
    RoundCalendar roundCalendar;

    private volatile List<Candle> replayCandles;

    @PostConstruct
    void init() {
        if (config.mode() == GameMode.REPLAY) {
            replayCandles = CandleLoader.loadFromClasspath("/replay/btc_1m.csv");
        }
    }

    /** The replay series of BTC, laid out on the server's round calendar.
     *  @throws IllegalStateException outside {@code mode=replay} */
    public RoundPrices replayPrices() {
        return new ReplayRoundPrices(candles(), config.replaySpeedup(), roundCalendar.originMs());
    }

    public record Reference(BigDecimal price, long asOfMs) {
    }

    /** The replay BTC reference price "right now" — the open of the source candle the virtual clock is
     *  at. */
    public Reference replayReference(long nowMs) {
        ReplayRoundPrices prices = new ReplayRoundPrices(candles(), config.replaySpeedup(), roundCalendar.originMs());
        return new Reference(prices.priceAt(nowMs), nowMs);
    }

    private List<Candle> candles() {
        List<Candle> candles = replayCandles;
        if (candles == null) {
            throw new IllegalStateException("replay prices exist only in herd.mode=replay");
        }
        return candles;
    }
}
