package crowdvsmarket.server.replay;

import crowdvsmarket.engine.market.Candle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads {@code replay/btc_1m.csv} (see its PROVENANCE.md) from the classpath into a plain
 * {@code List<Candle>}. Deliberately not {@code crowdvsmarket.engine.market.CandleSeries} — that
 * class tags a series with an {@link crowdvsmarket.engine.market.Interval}, and the engine's
 * {@code Interval} enum has only {@code HOURLY}/{@code DAILY} (no 1-minute value); rather than
 * either mistagging this 1-minute series or extending that enum (which would break every existing
 * exhaustive {@code switch} over it, e.g. {@code RoundDurations.forInterval}), this loader just
 * builds {@link Candle}s directly — the CSV column layout is identical to
 * {@code engine/src/test/resources/fixtures/*.csv}, so it reuses the exact same 8-column format,
 * just not that loader class.
 */
public final class CandleLoader {

    private CandleLoader() {
    }

    public static List<Candle> loadFromClasspath(String resourcePath) {
        try (InputStream in = CandleLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath resource not found: " + resourcePath);
            }
            return parse(in);
        } catch (IOException e) {
            throw new RuntimeException("failed to load replay candle fixture: " + resourcePath, e);
        }
    }

    private static List<Candle> parse(InputStream in) throws IOException {
        List<Candle> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException("empty replay CSV");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                if (cols.length != 8) {
                    throw new IllegalStateException("expected 8 CSV columns, got " + cols.length + ": " + line);
                }
                result.add(new Candle(
                        Long.parseLong(cols[0]), Long.parseLong(cols[1]),
                        new BigDecimal(cols[2]), new BigDecimal(cols[3]), new BigDecimal(cols[4]),
                        new BigDecimal(cols[5]), new BigDecimal(cols[6]), Long.parseLong(cols[7])));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("replay CSV had a header but no data rows");
        }
        return result;
    }
}
