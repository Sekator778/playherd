package crowdvsmarket.server.live;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Public, read-only Hyperliquid {@code /info} endpoint (docs/research/hl_liquidation_math.md §1,
 * §4/§8) — live {@code markPx} polling (mode=live) and the Р-23 candle fallback both go through
 * this one generic passthrough (the request/response shape varies by {@code "type"}, so a plain
 * {@link JsonNode} in, {@link JsonNode} out is simpler than one typed method per request kind).
 */
@RegisterRestClient(configKey = "hyperliquid")
public interface HyperliquidClient {

    @POST
    @Path("/info")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode info(JsonNode body);
}
