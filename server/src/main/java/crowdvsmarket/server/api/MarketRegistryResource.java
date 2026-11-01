package crowdvsmarket.server.api;

import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.prices.Instruments;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Comparator;
import java.util.List;

/**
 * FIXED INTERFACE O (docs/research/markets_architecture.md §7 п.5): {@code GET /api/markets/registry.json}
 * — the public asset registry. Every market ever listed, closed ones too, by oracle index: the number
 * a record carries on chain as its {@code asset} (BTC = 0) and what a reader needs to turn it back into
 * a market and to read the record's {@code priceCents} as a price ({@code price = priceCents /
 * 10^priceDecimals}). Indexes are append-only and never reused, so an entry never changes once it is
 * listed and the list only grows.
 *
 * <p>Public, no session — like the results and the records journal it is there so that anyone can check
 * the chain against the stand ({@code tools/verify-oracle} reads it). {@code no-store}: a verifier that
 * is handed a registry one entry short reports an asset "not in the registry", so a cached copy is worse
 * than the (small, cheap) read.
 */
@Path("/api/markets/registry.json")
public class MarketRegistryResource {

    @Inject
    MarketRegistry registry;

    /** The backup exchange of a market (D-76): the venue's name and what that venue calls the pair. */
    public record Backup(String source, String ref) {
    }

    /**
     * One registry entry, in the field names of the JSON. {@code source}/{@code sourceRef}: the market's primary exchange;
     * {@code backup}: the exchange a round moves to when the primary cannot price its start, {@code null} for a market that
     * has none. A round says which of the two it ran on ({@code venue} in the records journal and in its results.json).
     */
    public record Entry(int index, String id, String kind, String symbol, int priceDecimals, String source,
                        String sourceRef, long listedAtMs, Backup backup) {

        static Entry of(Market market) {
            return new Entry(market.oracleIndex(), market.id(), market.kind().name(), market.symbol(),
                    market.priceDecimals(), market.source(), market.sourceRef(), market.listedAtMs(),
                    Instruments.backupOf(market).map(b -> new Backup(b.source(), b.ref())).orElse(null));
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response registry() {
        List<Entry> entries = registry.all().stream()
                .sorted(Comparator.comparingInt(Market::oracleIndex))
                .map(Entry::of)
                .toList();
        return Response.ok(entries).header("Cache-Control", "no-store").build();
    }
}
