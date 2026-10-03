package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Kraken's public, read-only market data (no key; docs/research/primary_sources.md §2.3; read live 2026-10-03 at
 * https://api.kraken.com). Every answer is {@code {"error":[…],"result":{…}}}; an error such as
 * {@code ["EQuery:Unknown asset pair"]} comes with an HTTP 200 and no {@code result}.
 * <ul>
 *   <li>{@code GET /0/public/Ticker?pair=BTC/USD,ETH/USD} — several pairs in one call; per pair {@code a} (ask:
 *       {@code [price, whole lot volume, lot volume]}), {@code b} (bid, the same), {@code c} (last trade: price, lot
 *       volume), …; the result is keyed by the name the request used when it is in the {@code BTC/USD} form
 *       (the form of the WebSocket v2 symbols, which is what {@link KrakenPriceSource} asks in), by the pair's own key
 *       ({@code XXBTZUSD}) when asked by {@code altname} ({@code XBTUSD});
 *   <li>{@code GET /0/public/AssetPairs?pair=BTC/USD} — per pair {@code altname} ({@code XBTUSD}), {@code wsname}
 *       ({@code XBT/USD}, the name of WebSocket v1), {@code base}, {@code quote}, {@code status}; keyed like the ticker;
 *   <li>{@code GET /0/public/OHLC?pair=BTC/USD&interval=1&since=<unix seconds>} — rows
 *       {@code [time, open, high, low, close, vwap, volume, count]}, {@code time} the start of the minute, at most 720 of
 *       them, those after {@code since}; a minute without trades is a row with {@code count} 0 (open = high = low =
 *       close = the previous close, volume 0 — a filler, like Hyperliquid's {@code n = 0}); the last row is the minute in
 *       progress, and {@code result.last} is the time of the last committed one.
 * </ul>
 * The public endpoints' own rate limit is UNVERIFIED (https://docs.kraken.com/api/docs/guides/spot-rest-ratelimits,
 * read 2026-10-03, describes the call counter of an API key, 15 to 20 with a decay of 0.33 to 1 a second): the source
 * therefore reads no REST while its stream is healthy, one ticker call for all its pairs per poll when it is not, and
 * history one call per seed (the seeder spaces seeds a second apart) and per backfill (a cooldown a minute per pair).
 * Base URI: default of {@code herd.prices.kraken.url}.
 */
@RegisterRestClient(configKey = "herd-kraken", baseUri = SpotPricesConfig.DEFAULT_KRAKEN_URL)
public interface KrakenApi {

    @GET
    @Path("/0/public/Ticker")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode ticker(@QueryParam("pair") String pairs);

    @GET
    @Path("/0/public/AssetPairs")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode assetPairs(@QueryParam("pair") String pairs);

    @GET
    @Path("/0/public/OHLC")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode ohlc(@QueryParam("pair") String pair, @QueryParam("interval") int intervalMinutes,
                  @QueryParam("since") long sinceSeconds);
}
