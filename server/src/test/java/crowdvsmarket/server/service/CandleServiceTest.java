package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.PriceBar;
import crowdvsmarket.server.testsupport.PlainMarketRegistry;
import crowdvsmarket.server.testsupport.ScriptedMarketPrices;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code candles(marketId, minutes?)} with plain objects: the default and the clamps, raw strings with the market's decimals, a closed market and replay mode. */
class CandleServiceTest {

    private static final long M = 60_000L;
    private static final long NOW = 1_790_880_000_000L / M * M + 30_000L;

    private PlainMarketRegistry registry;
    private ScriptedMarketPrices prices;
    private GameConfig config;
    private CandleService service;
    private Market btc;

    @BeforeEach
    void plainObjects() {
        registry = new PlainMarketRegistry();
        prices = new ScriptedMarketPrices();
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        service = new CandleService(registry, prices, config);
        btc = registry.require("BTC");
    }

    private static List<PriceBar> minutes(int count) {
        List<PriceBar> out = new ArrayList<>();
        long current = Math.floorDiv(NOW, M);
        for (int i = count; i >= 0; i--) { // the last one is the minute in progress
            long m = current - i;
            out.add(new PriceBar(m * M, new BigDecimal("84000"), new BigDecimal("84010.555"), new BigDecimal("83990"), new BigDecimal("84005.5")));
        }
        return out;
    }

    @Test
    void defaultsToTwoHoursAndReturnsRawStringsWithTheMarketsDecimals() {
        prices.setBars(btc, minutes(200));

        Markets.CandlesDto answer = service.candles("BTC", null, NOW);

        assertThat(answer.marketId()).isEqualTo("BTC");
        assertThat(answer.priceDecimals()).isEqualTo(2);
        assertThat(answer.candles()).hasSize(120);
        assertThat(answer.candles().get(119).openTimeMs()).isEqualTo(Math.floorDiv(NOW, M) * M - M);
        assertThat(answer.candles().get(0)).isEqualTo(new Markets.CandleDto(Math.floorDiv(NOW, M) * M - 120 * M, "84000.00", "84010.56",
                "83990.00", "84005.50"));
    }

    @Test
    void theNumberOfMinutesIsClamped() {
        prices.setBars(btc, minutes(200));
        assertThat(service.candles("BTC", 0, NOW).candles()).hasSize(1);
        assertThat(service.candles("BTC", -5, NOW).candles()).hasSize(1);
        assertThat(service.candles("BTC", 30, NOW).candles()).hasSize(30);
        assertThat(service.candles("BTC", 100_000, NOW).candles()).hasSize(CandleService.MAX_MINUTES);
    }

    @Test
    void anUnknownMarketIsNotFound() {
        assertThatThrownBy(() -> service.candles("NOPE", null, NOW)).isInstanceOf(ApiException.class);
    }

    @Test
    void aMarketWithoutCandlesGivesAnEmptyListNotAnError() {
        assertThat(service.candles("BTC", null, NOW).candles()).isEmpty();
    }

    @Test
    void replayModeHasNoTicksOfItsOwnSoBtcIsEmpty() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        prices.setBars(btc, minutes(200));
        assertThat(service.candles("BTC", null, NOW).candles()).isEmpty();
    }
}
