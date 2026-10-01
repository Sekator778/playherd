package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * The Polymarket data API's price history (docs/research/polymarket_data_api.md §4):
 * {@code GET /v2/prices-history?token_id=…&start=…&end=…&bucket_seconds=60} answers
 * {@code {"data":[{"timestamp":<unix s>,"price":0.525,"resolution_seconds":60},…],"pagination":
 * {"has_more":false,"next_cursor":null}}}; the last entry is a terminal tick
 * ({@code resolution_seconds: 0}). An explicit window is at most 15 days. Base URI: default of
 * {@code herd.prices.polymarket.data-url}.
 */
@RegisterRestClient(configKey = "herd-polymarket-data", baseUri = PricesConfig.DEFAULT_POLYMARKET_DATA_URL)
public interface PolymarketDataApi {

    @GET
    @Path("/v2/prices-history")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode pricesHistory(@QueryParam("token_id") String tokenId, @QueryParam("start") long startSeconds,
                           @QueryParam("end") long endSeconds, @QueryParam("bucket_seconds") int bucketSeconds,
                           @QueryParam("cursor") String cursor);
}
