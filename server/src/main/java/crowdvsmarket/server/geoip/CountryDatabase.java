package crowdvsmarket.server.geoip;

import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/**
 * One MMDB file with a country for every network it knows, read with {@code com.maxmind.db.Reader}. DB-IP's "IP to Country
 * Lite" follows the layout of MaxMind's GeoIP2 Country: a record is a map with {@code continent} and {@code country}, and
 * the country has {@code iso_code} (ISO 3166-1 alpha-2), {@code geoname_id}, {@code is_in_european_union} and {@code
 * names} (https://db-ip.com/db/format/ip-to-country-lite/mmdb.html, read 2026-10-11; checked against the file of
 * 2026-10: {@code ip_version} 6, 24-bit records, 710 834 networks). Only {@code country.iso_code} is read; the rest of
 * the record is skipped by the reader.
 *
 * <p><b>In memory.</b> The file is read whole when the database is opened ({@link Reader.FileMode#MEMORY}, 8 MB) and not
 * mapped: nothing is kept open, the file may be deleted or replaced the moment {@link #open} returns, and a database that
 * has been swapped out needs no closing — it is simply dropped. A {@code Reader} is safe to share between threads.
 */
public final class CountryDatabase {

    /** What DB-IP puts where a network has no country ({@code ZZ}: five networks of the file of 2026-10); not a country. */
    private static final Set<String> NOT_A_COUNTRY = Set.of("ZZ", "XX");

    /** {@code country} of a record. Public: the reader makes it by reflection. */
    public record Country(@MaxMindDbParameter(name = "iso_code") String isoCode) {
        @MaxMindDbConstructor
        public Country {
        }
    }

    /** The part of a record that is read. Public: the reader makes it by reflection. */
    public record CountryRecord(@MaxMindDbParameter(name = "country") Country country) {
        @MaxMindDbConstructor
        public CountryRecord {
        }
    }

    private final Reader reader;
    private final boolean ipv4Only;
    private final String type;
    private final Instant builtAt;

    private CountryDatabase(Reader reader) {
        this.reader = reader;
        this.ipv4Only = reader.getMetadata().ipVersion() == 4;
        this.type = reader.getMetadata().databaseType();
        this.builtAt = reader.getMetadata().buildTime();
    }

    /** Reads {@code file} into memory and checks it is an MMDB. @throws IOException not readable, or not an MMDB file */
    public static CountryDatabase open(Path file) throws IOException {
        return new CountryDatabase(new Reader(file.toFile(), Reader.FileMode.MEMORY));
    }

    /**
     * The country of {@code address}: ISO 3166-1 alpha-2, upper case; {@code null} when the database has no record for it, the
     * record has no country, or the code is not a country's (see {@link #normalize}).
     *
     * @throws IOException the file is damaged at the place the address leads to
     */
    public String countryOf(InetAddress address) throws IOException {
        if (ipv4Only && address instanceof Inet6Address) {
            return null; // the reader would read its 128 bits as 32
        }
        CountryRecord record = reader.get(address, CountryRecord.class);
        return record == null || record.country() == null ? null : normalize(record.country().isoCode());
    }

    /** {@code code} if it is two ASCII capitals and not one DB-IP uses for "unknown" ({@code ZZ}), else {@code null}. */
    static String normalize(String code) {
        if (code == null || code.length() != 2) {
            return null;
        }
        for (int i = 0; i < 2; i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') {
                return null;
            }
        }
        return NOT_A_COUNTRY.contains(code) ? null : code;
    }

    /** The {@code database_type} of the file: {@code DBIP-Country-Lite} for DB-IP's. */
    public String type() {
        return type;
    }

    /** When the file was built (the {@code build_epoch} of its metadata). */
    public Instant builtAt() {
        return builtAt;
    }
}
