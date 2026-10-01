package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Hyperliquid's public, read-only {@code POST /info} (docs/research/hl_multi_asset_live.md §3–§5)
 * for the price pipeline and the catalog: {@code metaAndAssetCtxs} (all prices and the asset
 * parameters of one dex in one call, weight 20) and {@code candleSnapshot} (history). Like the
 * older {@code live.HyperliquidClient} it is one generic passthrough, because the request and the
 * response change shape with {@code "type"}. The base URI is the default of the config key
 * {@code herd.prices.hyperliquid.url}; {@link PriceHttpClients} builds the client with it and with
 * the pipeline's timeouts.
 */
@RegisterRestClient(configKey = "herd-hyperliquid-info", baseUri = PricesConfig.DEFAULT_HYPERLIQUID_URL)
public interface HyperliquidInfoApi {

    @POST
    @Path("/info")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode info(JsonNode body);
}
