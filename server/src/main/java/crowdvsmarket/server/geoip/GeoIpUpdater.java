package crowdvsmarket.server.geoip;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * Keeps the country database current. One {@link #check} is one look at whether there is something newer to load; it
 * answers how long to wait before the next one. It has no thread and no clock of its own: {@link DbIpCountryLookup} runs it
 * on a virtual thread, the tests run it by hand.
 *
 * <p><b>Download mode</b> (no {@code herd.geoip.mmdb-path}). DB-IP publishes one file per month. A check wants the file of
 * the current month (UTC) and, where that is not published yet (404 — the first hours or days of a month), the file of the
 * month before, when nothing newer than that is loaded already. A month that is loaded is never fetched again, so the
 * check every {@link #RECHECK} (7 days) costs nothing in the weeks the current month is in hand and one small 404 in a
 * week that has not got it yet. When the current month is loaded the next look is in 7 days; when it is not (the older
 * month was loaded in its place, or nothing was) the next look is in {@link #RETRY}, an hour.
 *
 * <p><b>Pre-placed mode</b> ({@code herd.geoip.mmdb-path}). The file is used instead of downloading anything; it is read
 * again when its modification time or size changes, looked at every hour (a {@code stat}), so that an operator can replace
 * it without a restart.
 *
 * <p><b>Failure.</b> A check never throws: whatever goes wrong — no connection, a bad status, a file that is not a
 * database — is one WARN line for that attempt, the database that is loaded stays (or none, and the countries stay
 * unknown), and the answer is {@link #RETRY}.
 */
final class GeoIpUpdater {

    static final Duration RECHECK = Duration.ofDays(7);
    static final Duration RETRY = Duration.ofHours(1);

    private static final Logger LOG = Logger.getLogger(GeoIpUpdater.class);

    private final MonthFetcher fetcher;
    private final Path preplaced;
    private final Clock clock;
    private final Consumer<CountryDatabase> sink;
    private final Logger log;

    /** Download mode: the month of the database that is loaded, or {@code null}. */
    private YearMonth loadedMonth;
    /** Pre-placed mode: what the file looked like when it was loaded, or {@code null}. */
    private FileTime loadedStamp;
    private long loadedSize;

    /** Download mode. */
    GeoIpUpdater(MonthFetcher fetcher, Clock clock, Consumer<CountryDatabase> sink) {
        this(fetcher, null, clock, sink, LOG);
    }

    /** Pre-placed mode. */
    GeoIpUpdater(Path preplaced, Clock clock, Consumer<CountryDatabase> sink) {
        this(null, preplaced, clock, sink, LOG);
    }

    GeoIpUpdater(MonthFetcher fetcher, Path preplaced, Clock clock, Consumer<CountryDatabase> sink, Logger log) {
        this.fetcher = fetcher;
        this.preplaced = preplaced;
        this.clock = clock;
        this.sink = sink;
        this.log = log;
    }

    /** One look; returns the time to wait before the next. Never throws; an interrupt is left set on the thread. */
    Duration check() {
        try {
            return preplaced != null ? checkPreplaced() : checkDownload();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RETRY;
        } catch (IOException | RuntimeException e) {
            log.warnf("geoip: could not %s (%s) — %s, next attempt in %d minutes", preplaced != null ? "read " + preplaced
                            : "fetch the DB-IP country database", describe(e),
                    loadedMonth != null || loadedStamp != null ? "the database loaded earlier stays in use"
                            : "countries stay unknown for now", RETRY.toMinutes());
            return RETRY;
        }
    }

    private Duration checkDownload() throws IOException, InterruptedException {
        YearMonth current = YearMonth.now(clock);
        if (loadedMonth != null && !loadedMonth.isBefore(current)) {
            log.debugf("geoip: the database of %s is the newest there can be", loadedMonth);
            return RECHECK;
        }
        List<YearMonth> wanted = new ArrayList<>();
        for (YearMonth month : List.of(current, current.minusMonths(1))) {
            if (loadedMonth == null || month.isAfter(loadedMonth)) {
                wanted.add(month);
            }
        }
        for (YearMonth month : wanted) {
            Optional<Path> file = fetcher.fetch(month);
            if (file.isEmpty()) {
                log.debugf("geoip: no database of %s published (404)", month);
                continue;
            }
            load(file.get(), month);
            return month.equals(current) ? RECHECK : RETRY;
        }
        if (loadedMonth == null) {
            log.warnf("geoip: no DB-IP country database of %s or %s is published (404) — countries stay unknown for now, "
                    + "next attempt in %d minutes", current, current.minusMonths(1), RETRY.toMinutes());
        } else {
            log.debugf("geoip: the database of %s is not published yet, the one of %s stays in use", current, loadedMonth);
        }
        return RETRY;
    }

    /** Opens the unpacked file, hands the database on and deletes the file (the database is in memory). */
    private void load(Path file, YearMonth month) throws IOException {
        try {
            CountryDatabase database = CountryDatabase.open(file);
            sink.accept(database);
            loadedMonth = month;
            log.infof("geoip: loaded the DB-IP country database of %s (%s, built %s)", month, database.type(),
                    database.builtAt());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private Duration checkPreplaced() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(preplaced, BasicFileAttributes.class);
        if (loadedStamp != null && loadedStamp.equals(attributes.lastModifiedTime()) && loadedSize == attributes.size()) {
            return RETRY;
        }
        CountryDatabase database = CountryDatabase.open(preplaced);
        sink.accept(database);
        loadedStamp = attributes.lastModifiedTime();
        loadedSize = attributes.size();
        log.infof("geoip: loaded the country database %s (%s, built %s)", preplaced, database.type(), database.builtAt());
        return RETRY;
    }

    /** The exception in one short line: its class and the first 160 characters of its message. */
    static String describe(Throwable e) {
        String message = e.getMessage();
        String text = message == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": " + message;
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }
}
