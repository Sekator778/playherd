package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Polymarket's Gamma catalog (docs/research/polymarket_data_api.md §2) — what the markets are, not
 * what they cost right now (Gamma's prices are cached for 300 s; the CLOB has the live ones). Only
 * the keyset listings are used, the old offset ones are deprecated. The answers are returned as
 * trees: several fields are JSON <i>inside</i> strings and many are optional, so
 * {@code markets.catalog.GammaMarket} reads them defensively. Base URI: default of
 * {@code herd.prices.polymarket.gamma-url}.
 */
@RegisterRestClient(configKey = "herd-polymarket-gamma", baseUri = PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL)
public interface PolymarketGammaApi {

    /** {@code GET /markets/keyset?tag_id=…&closed=…&order=volume24hr&ascending=false&limit=…} → {@code {"markets":[…],"next_cursor":…}}. */
    @GET
    @Path("/markets/keyset")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode markets(@QueryParam("tag_id") String tagId, @QueryParam("closed") boolean closed,
                     @QueryParam("order") String order, @QueryParam("ascending") boolean ascending,
                     @QueryParam("limit") int limit);

    /**
     * {@code GET /markets/keyset?clob_token_ids=a&clob_token_ids=b&closed=…&limit=…}: the markets of
     * given CLOB tokens. Measured 2026-10-02 (polymarket_data_api.md §13): without {@code closed} —
     * and with {@code closed=false} — the answer holds only the markets that are not closed yet; a
     * closed market comes back only for {@code closed=true}. Asking for both is how a listed market
     * that closed at the source is found.
     */
    @GET
    @Path("/markets/keyset")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode marketsByTokens(@QueryParam("clob_token_ids") List<String> tokenIds, @QueryParam("closed") boolean closed,
                             @QueryParam("limit") int limit);

    /** {@code GET /events/keyset?tag_id=…&live=…&closed=…&order=…&ascending=…&limit=…} → {@code {"events":[…],…}}; an event carries all its markets. */
    @GET
    @Path("/events/keyset")
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode events(@QueryParam("tag_id") String tagId, @QueryParam("live") boolean live,
                    @QueryParam("closed") boolean closed, @QueryParam("order") String order,
                    @QueryParam("ascending") boolean ascending, @QueryParam("limit") int limit);
}
