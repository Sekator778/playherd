package crowdvsmarket.server.geoip;

/**
 * The country a visitor's IP address belongs to (D-81). Used for one purpose: a feedback message is stored with the
 * country of its sender, for statistics — the address itself is not kept.
 *
 * <p>The implementation never throws and never waits for anything outside the process: no network, no DNS. An answer is
 * "unknown" ({@code null}) whenever there is nothing better to say.
 */
public interface CountryLookup {

    /**
     * The ISO 3166-1 alpha-2 code, upper case ({@code "DE"}), of the country {@code ipAddress} is in; {@code null} for an
     * address that is private, loopback or otherwise not public, for one the database does not know (or knows as
     * "unknown"), for text that is not an IP address, for {@code null}, and while no database is loaded (the lookup is
     * switched off, or its file has not arrived yet).
     */
    String countryOf(String ipAddress);
}
