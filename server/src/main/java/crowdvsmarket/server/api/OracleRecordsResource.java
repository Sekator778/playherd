package crowdvsmarket.server.api;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * FIXED INTERFACE O (docs/research/markets_architecture.md §7 п.6): {@code GET
 * /api/oracle/records?fromSeq=&limit=} — the journal of every oracle record this server queued, with
 * the round each belongs to, for the public verifier ({@code tools/verify-oracle}) and anyone else who
 * wants to hold the stand's rounds against the chain: {@code {firstSeq, records: [{seq, roundId,
 * marketId, asset, ts, priceCents, resultsHash, state, venue}]}}.
 *
 * <ul>
 *   <li>A thin read of the {@code oracle_records} table: ascending {@code seq}, the rows with
 *       {@code seq >= fromSeq} (default 0), at most {@code limit} of them (default {@value
 *       #DEFAULT_LIMIT}, at most {@value #MAX_LIMIT}: a larger value is capped at it — a client pages by
 *       the last {@code seq} it got plus one until a page comes back shorter than the limit it asked
 *       for, and a verifier asks for exactly {@value #MAX_LIMIT}).
 *   <li>{@code firstSeq}: the lowest {@code seq} this database holds, whatever {@code fromSeq} says;
 *       {@code null} while it holds none. A {@code seq} on chain below it was published before this
 *       database existed (an earlier database against the same singleton) — the reader checks it on
 *       chain only.
 *   <li>Every row is there, queued to the chain service or not yet: a record the stand has but the
 *       chain does not is information, not an error. {@code state} is what the round page's
 *       {@code oracleAnchor} says — {@code unknown} (not asked yet, or chainsvc does not know it),
 *       {@code queued}, {@code in_flight}, {@code confirmed}, {@code conflict}.
 *   <li>{@code asset} is the market's registry index ({@code GET /api/markets/registry.json}),
 *       {@code priceCents} the close price of the lot, times 100 (docs/research/markets_architecture.md
 *       §6), {@code resultsHash} the {@code 0x…} sha256 of that round's {@code results.json}.
 *   <li>{@code venue} (Р-76): the exchange the round ran on — {@code coinbase}, {@code kraken}, {@code hyperliquid} —
 *       and so the exchange {@code priceCents} is the close on, for a market that has a backup exchange ({@code
 *       results.json} then carries the same {@code "venue"} key, inside the hash); {@code null} for any other round.
 * </ul>
 *
 * <p>Public, no session, {@code no-store}. A malformed {@code fromSeq} or {@code limit} is a 400 —
 * never a 404: the verifier reads 404 on this path as "a stand that predates the journal" and falls
 * back to a BTC-only walk.
 */
@Path("/api/oracle/records")
public class OracleRecordsResource {

    static final int DEFAULT_LIMIT = 200;
    static final int MAX_LIMIT = 1000;

    @Inject
    OracleRecordRepository oracleRecords;

    /** One journal row, in the field names of the JSON (and of {@code tools/verify-oracle}). */
    public record Entry(long seq, String roundId, String marketId, int asset, long ts, long priceCents,
                        String resultsHash, String state, String venue) {

        static Entry of(OracleRecordRepository.JournalRow row) {
            return new Entry(row.seq(), row.roundId(), row.market(), row.assetIndex(), row.ts(), row.priceCents(),
                    row.resultsHash(), row.state(), row.venue());
        }
    }

    public record Body(Long firstSeq, List<Entry> records) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response records(@QueryParam("fromSeq") String fromSeq, @QueryParam("limit") String limit) {
        long from = fromSeq == null || fromSeq.isBlank() ? 0 : parse("fromSeq", fromSeq, 0);
        int count = limit == null || limit.isBlank() ? DEFAULT_LIMIT
                : (int) Math.min(parse("limit", limit, 1), MAX_LIMIT);
        OracleRecordRepository.JournalPage page = oracleRecords.journal(from, count);
        Body body = new Body(page.firstSeq(), page.rows().stream().map(Entry::of).toList());
        return Response.ok(body).header("Cache-Control", "no-store").build();
    }

    /** The query parameter as a long of at least {@code min}; anything else is a 400. */
    private static long parse(String name, String raw, long min) {
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be an integer, got '" + abbreviate(raw) + "'");
        }
        if (value < min) {
            throw ApiException.badRequest(name + " must be at least " + min + ", got " + value);
        }
        return value;
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 24 ? raw : raw.substring(0, 24) + "…";
    }
}
