package crowdvsmarket.server.geoip;

import crowdvsmarket.server.util.LogThrottle;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link CountryLookup} over DB-IP's "IP to Country Lite" database (Р-81; docs/research/server_design.md §22).
 *
 * <p><b>Nothing waits for it.</b> The database is not there at start: a virtual thread ({@code geoip-refresh}) loads it
 * — downloads the file of the month, or reads the pre-placed one — and looks again about every 7 days ({@link
 * GeoIpUpdater}). Until the first load has finished, and when it has failed, {@link #countryOf} answers {@code null}, and a
 * feedback message is stored without a country; no request and no start-up ever waits for the network. The loaded database
 * is replaced in one step (a volatile field); a lookup that is running keeps the one it started with.
 *
 * <p><b>The address is not kept.</b> It is parsed ({@link IpAddresses}: no DNS, whatever the text is), looked up and
 * forgotten; nothing here logs it.
 *
 * <p>Configuration: {@code herd.geoip.enabled} (default {@code true}; {@code false} in the tests, which never touch the
 * network), {@code herd.geoip.url-template} (where the monthly file is, {@value DbIpDownloader#MONTH_TOKEN} stands for the
 * month), {@code herd.geoip.mmdb-path} (a file to use instead of downloading).
 */
@ApplicationScoped
public class DbIpCountryLookup implements CountryLookup {

    private static final Logger LOG = Logger.getLogger(DbIpCountryLookup.class);

    @ConfigProperty(name = "herd.geoip.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "herd.geoip.url-template", defaultValue = DbIpDownloader.DEFAULT_URL_TEMPLATE)
    String urlTemplate;

    /** An empty value is "not set" for an {@link Optional}. */
    @ConfigProperty(name = "herd.geoip.mmdb-path")
    Optional<String> mmdbPath = Optional.empty();

    private volatile CountryDatabase database;
    private volatile Thread refresher;
    private final LogThrottle lookupFailures = new LogThrottle(60_000L);

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            LOG.info("geoip: switched off (herd.geoip.enabled=false) — feedback is stored without a country");
            return;
        }
        GeoIpUpdater updater;
        try {
            String path = mmdbPath.map(String::strip).filter(p -> !p.isEmpty()).orElse(null);
            updater = path != null
                    ? new GeoIpUpdater(Path.of(path), Clock.systemUTC(), this::useDatabase)
                    : new GeoIpUpdater(new DbIpDownloader(urlTemplate), Clock.systemUTC(), this::useDatabase);
        } catch (RuntimeException e) {
            // a template or a path that is not one: said once, and the lookup stays off — it must not stop the server
            LOG.warnf("geoip: switched off — herd.geoip.url-template / herd.geoip.mmdb-path is not usable (%s)",
                    GeoIpUpdater.describe(e));
            return;
        }
        refresher = Thread.ofVirtual().name("geoip-refresh").start(() -> refreshForever(updater));
    }

    private void refreshForever(GeoIpUpdater updater) {
        while (!Thread.currentThread().isInterrupted()) {
            Duration next = updater.check();
            try {
                Thread.sleep(next);
            } catch (InterruptedException e) {
                return; // the application is stopping
            }
        }
    }

    @PreDestroy
    void stop() {
        Thread thread = refresher;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** The database to answer from from now on; the one before is dropped (it is in memory — nothing to close). */
    void useDatabase(CountryDatabase next) {
        database = next;
    }

    @Override
    public String countryOf(String ipAddress) {
        CountryDatabase current = database;
        if (current == null) {
            return null;
        }
        InetAddress address = IpAddresses.parseLiteral(ipAddress);
        if (address == null || IpAddresses.isNotPublic(address)) {
            return null;
        }
        try {
            return current.countryOf(address);
        } catch (IOException | RuntimeException e) {
            if (lookupFailures.allow("lookup", System.currentTimeMillis())) {
                // the class of the failure only: the reader's own messages name the address ("Error getting record for IP …")
                LOG.warnf("geoip: a lookup failed (%s) — the country is left unknown; further failures are not logged for a minute",
                        e.getClass().getSimpleName());
            }
            return null;
        }
    }
}
