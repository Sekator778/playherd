package crowdvsmarket.server.geoip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.testsupport.LogCapture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DbIpCountryLookup#countryOf}: the text of an address in, an ISO code or {@code null} out — and never an exception.
 * The database comes from {@link TinyMmdb}; what loads it (the thread, the download) is {@code GeoIpUpdaterTest} and the
 * end-to-end tests.
 */
class DbIpCountryLookupTest {

    @TempDir
    Path dir;

    private DbIpCountryLookup lookupWith(TinyMmdb mmdb) throws IOException {
        DbIpCountryLookup lookup = new DbIpCountryLookup();
        lookup.useDatabase(CountryDatabase.open(mmdb.writeTo(dir.resolve("lookup.mmdb"))));
        return lookup;
    }

    @Test
    void untilADatabaseIsLoadedEveryAnswerIsUnknown() {
        DbIpCountryLookup lookup = new DbIpCountryLookup();

        assertThat(lookup.countryOf("192.0.2.1")).isNull();
        assertThat(lookup.countryOf("2001:db8::1")).isNull();
        assertThat(lookup.countryOf(null)).isNull();
    }

    @Test
    void anAddressOfTheDatabaseGetsItsCountryInEveryTextForm() throws IOException {
        DbIpCountryLookup lookup = lookupWith(TinyMmdb.ipv6()
                .country("192.0.2.0/24", "DE").country("2001:db8::/32", "BR"));

        assertThat(lookup.countryOf("192.0.2.77")).isEqualTo("DE");
        assertThat(lookup.countryOf(" 192.0.2.77 ")).isEqualTo("DE");
        assertThat(lookup.countryOf("::ffff:192.0.2.77")).isEqualTo("DE");
        assertThat(lookup.countryOf("2001:db8::1")).isEqualTo("BR");
        assertThat(lookup.countryOf("[2001:db8::1]")).isEqualTo("BR");
        assertThat(lookup.countryOf("8.8.8.8")).as("not in the database").isNull();
    }

    @Test
    void anAddressThatIsNotPublicIsUnknownWhateverTheDatabaseSaysAboutIt() throws IOException {
        DbIpCountryLookup lookup = lookupWith(TinyMmdb.ipv6()
                .country("10.0.0.0/8", "DE").country("127.0.0.0/8", "FR").country("100.64.0.0/10", "JP")
                .country("192.168.0.0/16", "BR").country("169.254.0.0/16", "NZ").country("fc00::/7", "AQ")
                .country("::1/128", "DK"));

        assertThat(lookup.countryOf("10.1.2.3")).isNull();
        assertThat(lookup.countryOf("127.0.0.1")).isNull();
        assertThat(lookup.countryOf("100.64.0.9")).isNull();
        assertThat(lookup.countryOf("192.168.1.1")).isNull();
        assertThat(lookup.countryOf("169.254.1.1")).isNull();
        assertThat(lookup.countryOf("fd00::1")).isNull();
        assertThat(lookup.countryOf("::1")).isNull();
    }

    @Test
    void textThatIsNotAnAddressIsUnknown() throws IOException {
        DbIpCountryLookup lookup = lookupWith(TinyMmdb.ipv6().country("192.0.2.0/24", "DE"));

        assertThat(lookup.countryOf("unknown")).as("what RpcResource#clientIp says when a request has no peer").isNull();
        assertThat(lookup.countryOf("localhost")).isNull();
        assertThat(lookup.countryOf("example.com")).isNull();
        assertThat(lookup.countryOf("")).isNull();
        assertThat(lookup.countryOf("192.0.2.0/24")).isNull();
        assertThat(lookup.countryOf(null)).isNull();
    }

    @Test
    void aLookupThatFailsInsideTheDatabaseIsUnknownAndNeverThrows() {
        CountryDatabase damaged = mock(CountryDatabase.class);
        try {
            when(damaged.countryOf(any())).thenThrow(new IOException("the file is damaged at this place"))
                    .thenThrow(new IllegalStateException("and now otherwise"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        DbIpCountryLookup lookup = new DbIpCountryLookup();
        lookup.useDatabase(damaged);

        assertThat(lookup.countryOf("192.0.2.1")).isNull();
        assertThat(lookup.countryOf("192.0.2.1")).isNull();
    }

    @Test
    void aFailedLookupSaysSoInTheLogWithoutTheAddress() throws IOException {
        CountryDatabase damaged = mock(CountryDatabase.class);
        // the reader's own messages name the address
        when(damaged.countryOf(any())).thenThrow(new IOException("Error getting record for IP /203.0.113.9 -  bad data"));
        DbIpCountryLookup lookup = new DbIpCountryLookup();
        lookup.useDatabase(damaged);

        List<String> logged;
        try (LogCapture log = LogCapture.start()) {
            assertThat(lookup.countryOf("203.0.113.9")).isNull();
            assertThat(lookup.countryOf("203.0.113.10")).isNull();
            logged = List.copyOf(log.lines());
        }

        assertThat(logged).as("said once for the two failures").hasSize(1);
        assertThat(logged.get(0)).contains("a lookup failed").contains("IOException");
        assertThat(logged).noneMatch(line -> line.contains("203.0.113"));
    }

    @Test
    void aNewDatabaseReplacesTheOldOneAtOnce() throws IOException {
        DbIpCountryLookup lookup = lookupWith(TinyMmdb.ipv6().country("192.0.2.0/24", "DE"));
        assertThat(lookup.countryOf("192.0.2.1")).isEqualTo("DE");

        lookup.useDatabase(CountryDatabase.open(TinyMmdb.ipv6().country("192.0.2.0/24", "FR")
                .writeTo(dir.resolve("next.mmdb"))));

        assertThat(lookup.countryOf("192.0.2.1")).isEqualTo("FR");
    }
}
