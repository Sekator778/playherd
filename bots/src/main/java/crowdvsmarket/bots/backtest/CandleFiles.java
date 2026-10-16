package crowdvsmarket.bots.backtest;

import crowdvsmarket.bots.api.BarDto;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Reads 1-minute candle files: one candle per line, {@code open_time_ms,open,high,low,close}; lines starting with
 * {@code #} (the downloader's source note) and blank lines are skipped. Sorted by time, duplicates keep the last line,
 * candles that fail {@link BarDto}'s consistency check are dropped (and counted in {@link Loaded#dropped}).
 */
public final class CandleFiles {

    private CandleFiles() {
    }

    /** The candles of a file and how many lines had to be dropped. */
    public record Loaded(List<BarDto> bars, int dropped) {
    }

    public static Loaded read(Path file) {
        TreeMap<Long, BarDto> byTime = new TreeMap<>();
        int dropped = 0;
        try {
            for (String line : Files.readAllLines(file)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] f = t.split(",");
                try {
                    BarDto b = new BarDto(Long.parseLong(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                            Double.parseDouble(f[3]), Double.parseDouble(f[4]));
                    byTime.put(b.openTimeMs(), b);
                } catch (RuntimeException e) {
                    dropped++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Loaded(new ArrayList<>(byTime.values()), dropped);
    }
}
