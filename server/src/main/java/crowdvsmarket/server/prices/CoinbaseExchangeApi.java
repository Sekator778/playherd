package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Coinbase Exchange's public, read-only market data (no key; docs/research/primary_sources.md §2.3; read live
 * 2026-10-03 at https://api.exchange.coinbase.com):
 * <ul>
 *   <li>{@code GET /products/{id}/ticker} answers {@code {"ask":"84631.95","bid":"84631.94","price":"84631.94",
 *       "time":"2026-10-03T11:06:37.683238042Z","trade_id":…,"size":"0.00000028","volume":"6184.6","rfq_volume":"114.3"}}
 *       — the best bid and ask now, and the price and time of the last trade;
 *   <li>{@code GET /products/{id}/candles?granularity=60&start=&end=} answers the rows {@code [time, low, high, open,
 *       close, volume]}, newest first, {@code time} the start of the minute in epoch seconds, at most 300 rows; a minute
 *       without trades has no row; {@code start} and {@code end} (ISO 8601, or epoch seconds) are the start times of the
 *       first and the last candle asked for, both inclusive; the candle of the minute in progress is among the rows.
 * </ul>
 * The limit is 10 requests a second per IP (https://docs.cdp.coinbase.com/exchange/rest-api/rate-limits, read
 * 2026-10-03). Base URI: default of {@code herd.prices.coinbase.url}.
 */
@RegisterRestClient(configKey = "herd-coinbase-exchange", baseUri = SpotPricesConfig.DEFAULT_COINBASE_URL)
public interface CoinbaseExchangeApi {

    @GET
    @Path("/products/{product}/ticker")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode ticker(@PathParam("product") String product);

    @GET
    @Path("/products/{product}/candles")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode candles(@PathParam("product") String product, @QueryParam("granularity") int granularitySeconds,
                     @QueryParam("start") String start, @QueryParam("end") String end);
}
