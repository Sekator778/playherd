package crowdvsmarket.server.geoip;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.prices.RecordingLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When {@link GeoIpUpdater} looks, what it asks for, what it loads and how long it waits — with a fetcher that plays DB-IP
 * (a month is published or not, or the transfer fails) and a clock the test moves. No network, no thread, no sleeping.
 */
class GeoIpUpdaterTest {

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T08:00:00Z"));
    private final FakeFetcher fetcher = new FakeFetcher();
    private final List<CountryDatabase> loaded = new ArrayList<>();
    private final RecordingLogger log = new RecordingLogger();

    @AfterEach
    void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    private GeoIpUpdater downloading() {
        return new GeoIpUpdater(fetcher, null, clock, loaded::add, log);
    }

    private GeoIpUpdater preplaced(Path file) {
        return new GeoIpUpdater(null, file, clock, loaded::add, log);
    }

    private String countryOfTheLatest() throws IOException {
        return loaded.get(loaded.size() - 1).countryOf(IpAddresses.parseLiteral("192.0.2.1"));
    }

    private List<String> filesInDir() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("fake-")).toList();
        }
    }

    /** DB-IP: {@code published} says, per month, the country its file has for 192.0.2.0/24, or the exception its transfer ends in. */
    private final class FakeFetcher implements MonthFetcher {
        final Map<YearMonth, Object> published = new HashMap<>();
        final List<YearMonth> asked = new ArrayList<>();

        @Override
        public Optional<Path> fetch(YearMonth month) throws IOException, InterruptedException {
            asked.add(month);
            Object what = published.get(month);
            if (what == null) {
                return Optional.empty();
            }
            if (what instanceof IOException e) {
                throw e;
            }
            if (what instanceof InterruptedException e) {
                throw e;
            }
            Path file = Files.createTempFile(dir, "fake-", ".mmdb");
            if ("junk".equals(what)) {
                Files.writeString(file, "not a database");
            } else {
                TinyMmdb.ipv6().country("192.0.2.0/24", (String) what).writeTo(file);
            }
            return Optional.of(file);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(String instant) {
            now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // --- download mode ---

    @Test
    void theFileOfTheCurrentMonthIsLoadedAndTheNextLookIsInAWeek() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), "DE");

        Duration next = downloading().check();

        assertThat(next).isEqualTo(Duration.ofDays(7));
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 10));
        assertThat(loaded).hasSize(1);
        assertThat(countryOfTheLatest()).isEqualTo("DE");
        assertThat(log.infos()).hasSize(1).first().asString().contains("2026-10").contains("DBIP-Country-Lite");
        assertThat(log.warnings()).isEmpty();
        assertThat(filesInDir()).as("the unpacked file is deleted once it is in memory").isEmpty();
    }

    @Test
    void theMonthBeforeIsLoadedWhenTheCurrentOneIsNotPublishedYetAndTheCurrentOneIsAskedAgainAfterAnHour() throws Exception {
        clock.set("2026-10-01T00:20:00Z");
        fetcher.published.put(YearMonth.of(2026, 9), "FR");
        GeoIpUpdater updater = downloading();

        Duration first = updater.check();

        assertThat(first).as("not the current month: look again soon").isEqualTo(Duration.ofHours(1));
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 9));
        assertThat(countryOfTheLatest()).isEqualTo("FR");

        // an hour later it is still not there: only the current month is asked — the one before is loaded already
        fetcher.asked.clear();
        Duration second = updater.check();

        assertThat(second).isEqualTo(Duration.ofHours(1));
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 10));
        assertThat(loaded).hasSize(1);
        assertThat(log.warnings()).as("something is loaded: a month that is late is no warning").isEmpty();

        // then it is published
        fetcher.published.put(YearMonth.of(2026, 10), "DE");
        fetcher.asked.clear();
        Duration third = updater.check();

        assertThat(third).isEqualTo(Duration.ofDays(7));
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 10));
        assertThat(loaded).hasSize(2);
        assertThat(countryOfTheLatest()).isEqualTo("DE");
    }

    @Test
    void aMonthThatIsLoadedIsNotFetchedAgain() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), "DE");
        GeoIpUpdater updater = downloading();
        updater.check();
        fetcher.asked.clear();

        clock.set("2026-10-18T08:00:00Z");
        Duration next = updater.check();

        assertThat(next).isEqualTo(Duration.ofDays(7));
        assertThat(fetcher.asked).as("nothing newer than October can exist in October").isEmpty();
        assertThat(loaded).hasSize(1);
    }

    @Test
    void whenTheMonthTurnsOnlyTheNewMonthIsAsked() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), "DE");
        GeoIpUpdater updater = downloading();
        updater.check();
        fetcher.asked.clear();
        fetcher.published.put(YearMonth.of(2026, 11), "JP");

        clock.set("2026-11-02T08:00:00Z");
        Duration next = updater.check();

        assertThat(next).isEqualTo(Duration.ofDays(7));
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 11));
        assertThat(loaded).hasSize(2);
        assertThat(countryOfTheLatest()).isEqualTo("JP");
    }

    @Test
    void theMonthIsTheUtcMonth() throws Exception {
        clock.set("2026-10-31T23:59:59Z");
        fetcher.published.put(YearMonth.of(2026, 10), "DE");
        fetcher.published.put(YearMonth.of(2026, 11), "JP");
        GeoIpUpdater updater = downloading();

        updater.check();
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 10));

        fetcher.asked.clear();
        clock.set("2026-11-01T00:00:01Z");
        updater.check();
        assertThat(fetcher.asked).containsExactly(YearMonth.of(2026, 11));
        assertThat(countryOfTheLatest()).isEqualTo("JP");
    }

    @Test
    void theMonthBeforeAYearEndIsDecember() throws Exception {
        clock.set("2027-01-01T00:30:00Z");
        fetcher.published.put(YearMonth.of(2026, 12), "FR");

        downloading().check();

        assertThat(fetcher.asked).containsExactly(YearMonth.of(2027, 1), YearMonth.of(2026, 12));
        assertThat(countryOfTheLatest()).isEqualTo("FR");
    }

    @Test
    void nothingPublishedIsOneWarningPerAttemptAndAnHourLater() throws Exception {
        GeoIpUpdater updater = downloading();

        Duration first = updater.check();
        Duration second = updater.check();

        assertThat(first).isEqualTo(Duration.ofHours(1));
        assertThat(second).isEqualTo(Duration.ofHours(1));
        assertThat(loaded).isEmpty();
        assertThat(log.warnings()).hasSize(2);
        assertThat(log.warnings().get(0)).contains("no DB-IP country database").contains("2026-10").contains("2026-09")
                .contains("countries stay unknown").contains("60 minutes");
    }

    @Test
    void aFailedTransferIsOneWarningAndTheDatabaseLoadedBeforeStaysInUse() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), "DE");
        GeoIpUpdater updater = downloading();
        updater.check();
        clock.set("2026-11-02T08:00:00Z");
        fetcher.published.put(YearMonth.of(2026, 11), new IOException("HTTP 500 from download.db-ip.com"));

        Duration next = updater.check();

        assertThat(next).isEqualTo(Duration.ofHours(1));
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("HTTP 500 from download.db-ip.com").contains("stays in use")
                .doesNotContain("unknown for now");
        assertThat(loaded).hasSize(1);
        assertThat(countryOfTheLatest()).isEqualTo("DE");
    }

    @Test
    void aFailedFirstTransferIsOneWarningAndCountriesStayUnknown() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), new java.net.ConnectException("Connection refused"));

        Duration next = downloading().check();

        assertThat(next).isEqualTo(Duration.ofHours(1));
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("ConnectException").contains("countries stay unknown for now");
        assertThat(loaded).isEmpty();
    }

    @Test
    void aFileThatIsNotADatabaseIsAWarningAndIsNotLoadedAndLeavesNothingBehind() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), "junk");

        Duration next = downloading().check();

        assertThat(next).isEqualTo(Duration.ofHours(1));
        assertThat(loaded).isEmpty();
        assertThat(log.warnings()).hasSize(1);
        assertThat(filesInDir()).isEmpty();
    }

    @Test
    void anInterruptStopsTheCheckAndStaysSetForTheCaller() throws Exception {
        fetcher.published.put(YearMonth.of(2026, 10), new InterruptedException("stopping"));

        downloading().check();

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(log.warnings()).as("stopping is not a failure").isEmpty();
        assertThat(loaded).isEmpty();
    }

    // --- pre-placed mode ---

    @Test
    void aPreplacedFileIsLoadedOnceAndReadAgainOnlyWhenItChanges() throws Exception {
        Path file = TinyMmdb.ipv6().country("192.0.2.0/24", "DE").writeTo(dir.resolve("placed.mmdb"));
        GeoIpUpdater updater = preplaced(file);

        Duration first = updater.check();
        Duration second = updater.check();

        assertThat(first).isEqualTo(Duration.ofHours(1));
        assertThat(second).isEqualTo(Duration.ofHours(1));
        assertThat(loaded).as("the second look found the file as it was").hasSize(1);
        assertThat(countryOfTheLatest()).isEqualTo("DE");
        assertThat(log.infos()).hasSize(1).first().asString().contains("placed.mmdb");

        // the operator replaces it
        TinyMmdb.ipv6().country("192.0.2.0/24", "FR").country("198.51.100.0/24", "FR").writeTo(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        updater.check();

        assertThat(loaded).hasSize(2);
        assertThat(countryOfTheLatest()).isEqualTo("FR");
        assertThat(fetcher.asked).as("a pre-placed file is never downloaded").isEmpty();
    }

    @Test
    void aPreplacedFileThatIsNotThereIsOneWarningPerAttemptAndIsLoadedOnceItAppears() throws Exception {
        Path file = dir.resolve("later.mmdb");
        GeoIpUpdater updater = preplaced(file);

        Duration first = updater.check();
        Duration second = updater.check();

        assertThat(first).isEqualTo(Duration.ofHours(1));
        assertThat(second).isEqualTo(Duration.ofHours(1));
        assertThat(log.warnings()).hasSize(2);
        assertThat(log.warnings().get(0)).contains("could not read").contains("later.mmdb").contains("NoSuchFileException");

        TinyMmdb.ipv6().country("192.0.2.0/24", "DE").writeTo(file);
        updater.check();

        assertThat(loaded).hasSize(1);
        assertThat(log.warnings()).hasSize(2);
    }

    @Test
    void aPreplacedFileThatIsNotADatabaseKeepsTheOneLoadedBefore() throws Exception {
        Path file = TinyMmdb.ipv6().country("192.0.2.0/24", "DE").writeTo(dir.resolve("placed.mmdb"));
        GeoIpUpdater updater = preplaced(file);
        updater.check();

        Files.writeString(file, "half-copied...");
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        updater.check();

        assertThat(loaded).hasSize(1);
        assertThat(countryOfTheLatest()).isEqualTo("DE");
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("stays in use");
    }
}
