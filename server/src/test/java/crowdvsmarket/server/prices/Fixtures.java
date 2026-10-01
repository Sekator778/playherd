package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The canned source answers under {@code src/test/resources/fixtures/}, trimmed from the live
 * samples saved on 2026-10-01 (see docs/research/hl_multi_asset_live.md and
 * polymarket_data_api.md). No test of the price pipeline touches the network.
 */
public final class Fixtures {

    /** 2026-10-01T19:45:00Z — five minutes after the sports samples were captured. */
    public static final long NOW = 1_790_883_900_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {
    }

    /** {@code json("hyperliquid/meta_and_asset_ctxs_main.json")}. */
    public static JsonNode json(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + path);
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A tick store over an in-memory snapshot table, with the stand's retention and the given clock. */
    public static TickStore tickStore(java.util.function.LongSupplier clock) {
        return new TickStore(new FakeSnapshotStore(), PricesConfig.defaults().retentionMs(), clock);
    }

    public static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
