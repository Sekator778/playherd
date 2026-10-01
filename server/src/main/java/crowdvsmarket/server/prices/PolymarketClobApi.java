package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The Polymarket CLOB's public batch endpoints (docs/research/polymarket_data_api.md §3.1): live
 * midpoints of many tokens in one call, and best bid/ask. Bodies and answers are plain JSON:
 * {@code POST /midpoints} takes {@code [{"token_id":…},…]} and answers {@code {"<token>":"0.145"}}
 * (strings; a closed token is absent), {@code POST /prices} takes {@code [{"token_id":…,"side":
 * "BUY"},…]} and answers {@code {"<token>":{"BUY":"0.14","SELL":"0.15"}}}. Measured 2026-10-01:
 * {@code BUY} is the best <i>bid</i> and {@code SELL} the best <i>ask</i> — the docs say the
 * opposite (§3.3). Base URI: default of {@code herd.prices.polymarket.clob-url}.
 */
@RegisterRestClient(configKey = "herd-polymarket-clob", baseUri = PricesConfig.DEFAULT_POLYMARKET_CLOB_URL)
public interface PolymarketClobApi {

    @POST
    @Path("/midpoints")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode midpoints(JsonNode tokens);

    @POST
    @Path("/prices")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode prices(JsonNode tokenSides);
}
