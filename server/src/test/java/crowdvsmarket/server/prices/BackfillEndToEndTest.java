package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A round whose market has no ticks at all — it was listed after the round began, or the server was
 * down — is rebuilt from the real sources' history: the actual 1-minute candles and the actual
 * 60-second buckets saved on 2026-10-01, through the real source classes, into the settlement data.
 */
class BackfillEndToEndTest {

    @Test
    void aHyperliquidRoundIsRebuiltFromOneMinuteCandlesAndKeepsTheMinutesRealExtremes() {
        // fixture: BTC 17:59, 18:00, 18:01 UTC of 2026-10-01; the round is the 60 s from 18:00:00 to 18:01:00
        long start = 1_790_877_600_000L;
        long end = start + 60_000L;
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        PriceSources sources = new PriceSources(List.of(new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> end + 2_000)));
        AtomicLong now = new AtomicLong(end + 2_000);
        TickStore store = Fixtures.tickStore(now::get);
        LiveMarketPrices prices = new LiveMarketPrices(store, sources, PricesConfig.defaults(), now::get);
        Market btc = TestMarkets.btc();

        RoundPriceData data = prices.resolve(btc, new RoundWindow("BTC", Interval.HOURLY, start, end));

        // one request, for the hole: from 75 s before the window (and 30 minutes of lookback) to 1 ms before its end,
        // BTC, 1m: the candle that starts at the end holds trades after the round (R3)
        assertThat(api.info.requests).hasSize(1);
        assertThat(api.info.requests.get(0).get("req").get("coin").asText()).isEqualTo("BTC");
        assertThat(api.info.requests.get(0).get("req").get("interval").asText()).isEqualTo("1m");
        assertThat(api.info.requests.get(0).get("req").get("startTime").asLong()).isEqualTo(start - 75_000 - 30 * 60_000L);
        assertThat(api.info.requests.get(0).get("req").get("endTime").asLong()).isEqualTo(end - 1);

        // P(start) is the 17:59 candle's close (1 ms before the start); P(end) is the 18:00 candle's close, the last
        // trade of the round's minute (84776), NOT the open of the 18:01 candle that starts at the end (84777)
        assertThat(data.open()).isEqualByComparingTo("84871.00");
        assertThat(data.close()).isEqualByComparingTo("84776.00");
        assertThat(data.sourceTsMs()).isEqualTo(end - 1);
        List<Candle> path = data.path();
        assertThat(path).hasSize(30);
        // the 18:00 candle had high 84883 and low 84743: both are in the path
        assertThat(path.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow()).isEqualByComparingTo("84883.00");
        assertThat(path.stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow()).isEqualByComparingTo("84743.00");
        assertThat(path.get(0).open()).isEqualByComparingTo(data.open());
        assertThat(path.get(29).close()).isEqualByComparingTo(data.close());
        for (int i = 1; i < path.size(); i++) {
            assertThat(path.get(i).open()).isEqualByComparingTo(path.get(i - 1).close());
        }
    }

    @Test
    void aQuietAssetsRoundSettlesAfterTwoMinutesOnTheCarriedForwardLastClose() {
        // the same fixture, but the round runs 18:00:00 - 18:10:00 and the asset last traded at 18:01:59:
        // its answer has no candle for the 8 minutes after that (hl_multi_asset_live.md §4.3)
        long start = 1_790_877_600_000L;
        long end = start + 600_000L;
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        AtomicLong now = new AtomicLong(end + 1_000); // the scheduler settles a second after the end
        PriceSources sources = new PriceSources(List.of(new HyperliquidPriceSource(() -> api, PricesConfig.defaults(),
                now::get)));
        LiveMarketPrices prices = new LiveMarketPrices(Fixtures.tickStore(now::get), sources, PricesConfig.defaults(),
                now::get);
        RoundWindow window = new RoundWindow("BTC", Interval.HOURLY, start, end);
        Market btc = TestMarkets.btc();

        // 1 s after the end the quiet tail may still fill: no carry-forward yet, the round is unavailable
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> prices.resolve(btc, window))
                .isInstanceOf(PriceUnavailableException.class);
        assertThat(api.info.requests).hasSize(1);

        // retries inside the cooldown (a minute per market) do not ask again ...
        now.set(end + 50_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> prices.resolve(btc, window))
                .isInstanceOf(PriceUnavailableException.class);
        assertThat(api.info.requests).hasSize(1);

        // ... and 2 minutes after the end, once the cooldown has passed, the tail is final and is carried
        now.set(end + 125_000);
        RoundPriceData data = prices.resolve(btc, window);

        assertThat(api.info.requests).hasSize(2);
        assertThat(data.open()).isEqualByComparingTo("84871.00"); // the 18:00 candle's open
        assertThat(data.close()).isEqualByComparingTo("84792.00"); // the last close, carried to the end
        assertThat(data.sourceTsMs()).isEqualTo(end - 1); // a point 1 ms before the aligned end
        assertThat(data.path()).hasSize(30);
        assertThat(data.path().stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("84736.00"); // the 18:01 candle's real low
        assertThat(data.path().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("84883.00"); // the 18:00 candle's real high
        // from 18:02 on the path is flat at the carried price
        for (Candle c : data.path().subList(7, 30)) {
            assertThat(c.high()).isEqualByComparingTo("84792.00");
            assertThat(c.low()).isEqualByComparingTo("84792.00");
        }
    }

    /** The round [19:30:00, 19:34:00] of 2026-10-01: {@code {"data":[…]}} with the buckets at the given seconds after its start. */
    private static JsonNode buckets(Object... secondsAndPrice) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < secondsAndPrice.length; i += 2) {
            sb.append(i == 0 ? "" : ",").append("{\"timestamp\":")
                    .append(1_790_883_000L + ((Number) secondsAndPrice[i]).longValue()).append(",\"price\":")
                    .append(secondsAndPrice[i + 1]).append(",\"resolution_seconds\":60}");
        }
        return Fixtures.parse(sb.append("]}").toString());
    }

    private RoundPriceData settlePolymarketRound(JsonNode history) {
        long start = 1_790_883_000_000L;
        long end = start + 240_000L;
        FakeApis.Data data = new FakeApis.Data();
        data.history.handler = call -> history;
        PolymarketPriceSource source = new PolymarketPriceSource(() -> new FakeApis.Clob(), () -> data,
                new PolymarketLiveState(), PricesConfig.defaults(), () -> end);
        AtomicLong now = new AtomicLong(end + 5_000);
        LiveMarketPrices prices = new LiveMarketPrices(Fixtures.tickStore(now::get), new PriceSources(List.of(source)),
                PricesConfig.defaults(), now::get);
        return prices.resolve(TestMarkets.odds("PM4678642", "tok"), new RoundWindow("PM4678642", Interval.HOURLY, start, end));
    }

    @Test
    void aPolymarketRoundSettlesAcrossAMissingMinuteBucket() {
        // 120 s between two buckets happens in real series (55 of 1 436 in one day, polymarket_data_api.md §4.2):
        // the bucket at +120 s is missing, the 90 s gap limit of the feed would make the round wait for its refund
        RoundPriceData data = settlePolymarketRound(buckets(0, 0.695, 60, 0.715, 180, 0.705, 240, 0.725));

        assertThat(data.open()).isEqualByComparingTo("6950.00");
        assertThat(data.close()).isEqualByComparingTo("7250.00");
        assertThat(data.path()).hasSize(30);
        // 0.715 holds from +60 s to +180 s: the path has candles flat at that price, and no price that was never seen
        assertThat(data.path().stream().anyMatch(c -> c.low().compareTo(new BigDecimal("7150.00")) == 0
                && c.high().compareTo(new BigDecimal("7150.00")) == 0)).isTrue();
        assertThat(data.path().stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("6950.00");
        assertThat(data.path().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("7250.00");
    }

    @Test
    void aPolymarketRoundSettlesWhenTheBucketAtTheRoundEndIsMissing() {
        // the bucket stamped exactly at the end is not there: the one before it is 60 s old, the feed's limit is 30 s
        RoundPriceData data = settlePolymarketRound(buckets(0, 0.695, 60, 0.715, 120, 0.705, 180, 0.715));

        assertThat(data.open()).isEqualByComparingTo("6950.00");
        assertThat(data.close()).isEqualByComparingTo("7150.00"); // the +180 s price, carried to the end
        assertThat(data.sourceTsMs()).isEqualTo(1_790_883_240_000L);
    }

    @Test
    void aPolymarketRoundStillWaitsWhenTheNewestBucketsAreMoreThanAMinuteLate() {
        // the data API's closed buckets trail by minutes: with the last one 90 s before the end there is no
        // price at the end yet — the scheduler retries, the buckets arrive
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> settlePolymarketRound(buckets(0, 0.695, 60, 0.715, 120, 0.705, 150, 0.7)))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void aPolymarketRoundIsRebuiltFromSixtySecondBuckets() {
        // fixture: buckets 17:49 .. 17:55 and a terminal tick; the round is the 4 minutes 1790883000 .. 1790883240
        long start = 1_790_883_000_000L;
        long end = start + 240_000L;
        FakeApis.Data data = new FakeApis.Data();
        data.history.handler = call -> Fixtures.json("polymarket/data_prices_history.json");
        PolymarketPriceSource source = new PolymarketPriceSource(() -> new FakeApis.Clob(), () -> data,
                new PolymarketLiveState(), PricesConfig.defaults(), () -> end);
        AtomicLong now = new AtomicLong(end + 5_000);
        TickStore store = Fixtures.tickStore(now::get);
        LiveMarketPrices prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), PricesConfig.defaults(),
                now::get);
        Market odds = TestMarkets.odds("PM4678642", "tok");

        RoundPriceData result = prices.resolve(odds, new RoundWindow("PM4678642", Interval.HOURLY, start, end));

        assertThat(data.history.requests).hasSize(1);
        assertThat(data.history.requests.get(0).tokenId()).isEqualTo("tok");
        assertThat(data.history.requests.get(0).bucketSeconds()).isEqualTo(60);
        // buckets: 3000 s 0.695 (the open: a bucket exactly at the start), 3060 0.715, 3120 0.705, 3180 0.715, 3240 0.715 (the close)
        assertThat(result.open()).isEqualByComparingTo("6950.00"); // lot exponent 4: 0.6950 x 10^4
        assertThat(result.close()).isEqualByComparingTo("7150.00");
        assertThat(result.path()).hasSize(30);
        assertThat(result.path().stream().map(Candle::low).min(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("6950.00");
        assertThat(result.path().stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow())
                .isEqualByComparingTo("7150.00");
        assertThat(result.path().stream().anyMatch(c -> c.low().compareTo(new BigDecimal("7050.00")) == 0)).isTrue();
    }
}
