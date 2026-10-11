package crowdvsmarket.server.geoip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CountryDatabase} over MMDB files written by {@link TinyMmdb} and read by the real {@code com.maxmind.db.Reader}: the
 * layout is DB-IP's (a record with a continent and a country with {@code iso_code}, {@code geoname_id}, {@code
 * is_in_european_union} and {@code names}), so reading the code means skipping everything else, as on the real file. No
 * network, no real database.
 */
class CountryDatabaseTest {

    @TempDir
    Path dir;

    private CountryDatabase open(TinyMmdb mmdb) throws IOException {
        return CountryDatabase.open(mmdb.writeTo(dir.resolve("tiny.mmdb")));
    }

    private static String country(CountryDatabase database, String ip) throws IOException {
        return database.countryOf(IpAddresses.parseLiteral(ip));
    }

    private static TinyMmdb sample() {
        return TinyMmdb.ipv6()
                .country("192.0.2.0/24", "DE")
                .country("198.51.100.0/25", "FR")
                .country("203.0.113.0/24", "JP")
                .country("2001:db8::/32", "BR")
                .country("2001:db9::/48", "NZ");
    }

    @Test
    void anIpv4AddressIsFoundInItsNetworkAndNowhereElse() throws IOException {
        CountryDatabase database = open(sample());

        assertThat(country(database, "192.0.2.0")).isEqualTo("DE");
        assertThat(country(database, "192.0.2.77")).isEqualTo("DE");
        assertThat(country(database, "192.0.2.255")).isEqualTo("DE");
        assertThat(country(database, "198.51.100.1")).isEqualTo("FR");
        assertThat(country(database, "198.51.100.127")).isEqualTo("FR");
        assertThat(country(database, "198.51.100.128")).as("the second half of the /24 is not in the /25").isNull();
        assertThat(country(database, "203.0.113.200")).isEqualTo("JP");
        assertThat(country(database, "192.0.3.1")).isNull();
        assertThat(country(database, "8.8.8.8")).isNull();
    }

    @Test
    void anIpv6AddressIsFoundInItsNetwork() throws IOException {
        CountryDatabase database = open(sample());

        assertThat(country(database, "2001:db8::1")).isEqualTo("BR");
        assertThat(country(database, "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff")).isEqualTo("BR");
        assertThat(country(database, "2001:db9:0:1::5")).isEqualTo("NZ");
        assertThat(country(database, "2001:db9:1::1")).as("outside the /48").isNull();
        assertThat(country(database, "2001:dba::1")).isNull();
        assertThat(country(database, "2606:4700::1111")).isNull();
    }

    @Test
    void anIpv4MappedIpv6AddressIsTheIpv4OneForAnIpv6Database() throws IOException {
        CountryDatabase database = open(sample());

        assertThat(country(database, "::ffff:192.0.2.77")).isEqualTo("DE");
        assertThat(country(database, "::ffff:c000:24d")).isEqualTo("DE");
    }

    @Test
    void anIpv4OnlyDatabaseAnswersIpv4AndLeavesIpv6AlonePlainlyUnknown() throws IOException {
        CountryDatabase database = open(TinyMmdb.ipv4().country("192.0.2.0/24", "DE").country("10.0.0.0/8", "FR"));

        assertThat(country(database, "192.0.2.9")).isEqualTo("DE");
        assertThat(country(database, "10.1.2.3")).isEqualTo("FR");
        assertThat(country(database, "8.8.8.8")).isNull();
        assertThat(country(database, "2001:db8::1")).as("128 bits are not read as 32").isNull();
        assertThat(country(database, "::")).isNull();
    }

    @Test
    void aCodeThatIsNotACountryIsUnknown() throws IOException {
        CountryDatabase database = open(TinyMmdb.ipv6()
                .country("192.0.2.0/24", "ZZ")
                .country("198.51.100.0/24", "XX")
                .country("203.0.113.0/24", "de")
                .country("203.0.114.0/24", "DEU")
                .country("203.0.115.0/24", "D")
                .country("203.0.116.0/24", "")
                .country("203.0.117.0/24", "1A")
                .country("203.0.118.0/24", "AQ"));

        assertThat(country(database, "192.0.2.1")).as("DB-IP's code for no country").isNull();
        assertThat(country(database, "198.51.100.1")).isNull();
        assertThat(country(database, "203.0.113.1")).as("lower case").isNull();
        assertThat(country(database, "203.0.114.1")).as("three letters").isNull();
        assertThat(country(database, "203.0.115.1")).isNull();
        assertThat(country(database, "203.0.116.1")).isNull();
        assertThat(country(database, "203.0.117.1")).isNull();
        assertThat(country(database, "203.0.118.1")).as("Antarctica is a code like any other").isEqualTo("AQ");
    }

    @Test
    void aRecordWithoutACountryIsUnknown() throws IOException {
        CountryDatabase database = open(TinyMmdb.ipv6().continentOnly("192.0.2.0/24").country("203.0.113.0/24", "JP"));

        assertThat(country(database, "192.0.2.1")).isNull();
        assertThat(country(database, "203.0.113.1")).isEqualTo("JP");
    }

    @Test
    void normalizeKeepsTwoCapitalsOnly() {
        assertThat(CountryDatabase.normalize("DE")).isEqualTo("DE");
        assertThat(CountryDatabase.normalize("XK")).as("Kosovo is in DB-IP's file").isEqualTo("XK");
        assertThat(CountryDatabase.normalize(null)).isNull();
        assertThat(CountryDatabase.normalize("")).isNull();
        assertThat(CountryDatabase.normalize("ZZ")).isNull();
        assertThat(CountryDatabase.normalize("XX")).isNull();
        assertThat(CountryDatabase.normalize("de")).isNull();
        assertThat(CountryDatabase.normalize("dE")).isNull();
        assertThat(CountryDatabase.normalize("DEU")).isNull();
        assertThat(CountryDatabase.normalize("ÄÖ")).isNull();
        assertThat(CountryDatabase.normalize("D1")).isNull();
    }

    @Test
    void theMetadataIsThere() throws IOException {
        CountryDatabase database = open(sample());

        assertThat(database.type()).isEqualTo("DBIP-Country-Lite");
        assertThat(database.builtAt()).isEqualTo(Instant.ofEpochSecond(1_790_000_000L));
    }

    @Test
    void theFileMayBeDeletedOnceItIsOpenBecauseTheDatabaseIsInMemory() throws IOException {
        Path file = sample().writeTo(dir.resolve("gone.mmdb"));
        CountryDatabase database = CountryDatabase.open(file);

        Files.delete(file);

        assertThat(country(database, "192.0.2.77")).isEqualTo("DE");
        assertThat(country(database, "2001:db8::1")).isEqualTo("BR");
    }

    @Test
    void aFileThatIsNotAnMmdbOrNotThereIsAnIoException() throws IOException {
        Path junk = Files.writeString(dir.resolve("junk.mmdb"), "this is not a database, it is a sentence");
        Path empty = Files.write(dir.resolve("empty.mmdb"), new byte[0]);
        byte[] whole = sample().build();
        Path cut = Files.write(dir.resolve("cut.mmdb"), java.util.Arrays.copyOf(whole, whole.length / 2));

        assertThatThrownBy(() -> CountryDatabase.open(junk)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CountryDatabase.open(empty)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CountryDatabase.open(cut)).as("the metadata is at the end of the file")
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CountryDatabase.open(dir.resolve("missing.mmdb"))).isInstanceOf(IOException.class);
    }

    @Test
    void theDatabaseIsSafeToShareBetweenThreads() throws Exception {
        CountryDatabase database = open(sample());
        InetAddress inGermany = IpAddresses.parseLiteral("192.0.2.77");
        InetAddress inBrazil = IpAddresses.parseLiteral("2001:db8::1");
        java.util.concurrent.atomic.AtomicInteger wrong = new java.util.concurrent.atomic.AtomicInteger();

        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    try {
                        if (!"DE".equals(database.countryOf(inGermany)) || !"BR".equals(database.countryOf(inBrazil))) {
                            wrong.incrementAndGet();
                        }
                    } catch (IOException e) {
                        wrong.incrementAndGet();
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(wrong).hasValue(0);
    }
}
