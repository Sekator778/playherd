package crowdvsmarket.engine.market;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An ordered series of {@link Candle}s loaded from one of the CSV fixtures in
 * {@code engine/src/test/resources/fixtures/} (see PROVENANCE.md there for how they were fetched).
 * Plain CSV parsing only, no runtime dependency (task requirement).
 */
public final class CandleSeries {

    private final Interval interval;
    private final List<Candle> candles;

    private CandleSeries(Interval interval, List<Candle> candles) {
        this.interval = interval;
        this.candles = List.copyOf(candles);
    }

    /**
     * Loads a candle series from a classpath CSV resource (columns:
     * {@code open_time_ms,close_time_ms,open,high,low,close,volume,trades}, header row required).
     *
     * @param resourcePath absolute classpath path, e.g. {@code "/fixtures/hl_btc_1d.csv"}
     */
    public static CandleSeries loadFromClasspath(String resourcePath, Interval interval) {
        try (InputStream in = CandleSeries.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath resource not found: " + resourcePath);
            }
            return new CandleSeries(interval, parse(in));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load candle fixture: " + resourcePath, e);
        }
    }

    private static List<Candle> parse(InputStream in) throws IOException {
        List<Candle> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalStateException("empty CSV fixture");
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
                        Long.parseLong(cols[0]),
                        Long.parseLong(cols[1]),
                        new BigDecimal(cols[2]),
                        new BigDecimal(cols[3]),
                        new BigDecimal(cols[4]),
                        new BigDecimal(cols[5]),
                        new BigDecimal(cols[6]),
                        Long.parseLong(cols[7])));
            }
        }
        return result;
    }

    public Interval interval() {
        return interval;
    }

    /** Candles in ascending {@code openTimeMs} order, exactly as they appear in the CSV fixture. */
    public List<Candle> candles() {
        return candles;
    }

    public int size() {
        return candles.size();
    }
}
