package crowdvsmarket.server.geoip;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the DB-IP "IP to Country Lite" file of a month and unpacks it into a temporary file.
 *
 * <p><b>The address.</b> {@code https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.mmdb.gz} — the file of the month
 * in UTC, gzip, 4.1 MB packed and 8.3 MB unpacked for 2026-10. Checked 2026-10-11 with {@code curl}: {@code 2026-10} and
 * {@code 2026-09} answer {@code 200} ({@code application/octet-stream}, no redirect), a month that is not published yet
 * ({@code 2026-11}) and a month that does not exist ({@code 2026-13}) answer {@code 404} with an HTML body. The page
 * https://db-ip.com/db/download/ip-to-country-lite says the database is updated monthly and licensed CC BY 4.0
 * (attribution: "IP Geolocation by DB-IP", a link to https://db-ip.com on pages that use the data). The template is
 * configuration ({@code herd.geoip.url-template}); {@value #MONTH_TOKEN} in it stands for the month.
 *
 * <p><b>Bounded.</b> The whole transfer has a deadline (a server that sends the headers and then stalls must not hold the
 * refresh for ever), and the unpacked size is capped (a gzip stream can be a thousand times its size). A failed transfer
 * leaves nothing in the temporary directory.
 */
final class DbIpDownloader implements MonthFetcher {

    /** The part of the template that is replaced by {@code YYYY-MM}. */
    static final String MONTH_TOKEN = "YYYY-MM";

    static final String DEFAULT_URL_TEMPLATE = "https://download.db-ip.com/free/dbip-country-lite-" + MONTH_TOKEN
            + ".mmdb.gz";

    /** The file of 2026-10 is 8.3 MB unpacked; the cap is a bound on a mistake, not an estimate. */
    static final long DEFAULT_MAX_UNPACKED_BYTES = 128L << 20;
    static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(5);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HEADERS_TIMEOUT = Duration.ofSeconds(60);
    private static final String USER_AGENT = "herd-server (country database refresh)";

    private final String urlTemplate;
    private final Path tempDir;
    private final Duration deadline;
    private final long maxUnpackedBytes;

    DbIpDownloader(String urlTemplate) {
        this(urlTemplate, null, DEFAULT_DEADLINE, DEFAULT_MAX_UNPACKED_BYTES);
    }

    /** @param tempDir where the unpacked file goes; {@code null}: the JVM's temporary directory */
    DbIpDownloader(String urlTemplate, Path tempDir, Duration deadline, long maxUnpackedBytes) {
        this.urlTemplate = urlTemplate;
        this.tempDir = tempDir;
        this.deadline = deadline;
        this.maxUnpackedBytes = maxUnpackedBytes;
        urlFor(YearMonth.of(2000, 1)); // a template that is not a URI fails here, not at the first transfer
    }

    URI urlFor(YearMonth month) {
        return URI.create(urlTemplate.replace(MONTH_TOKEN, month.toString()));
    }

    @Override
    public Optional<Path> fetch(YearMonth month) throws IOException, InterruptedException {
        URI uri = urlFor(month);
        Path target = tempDir == null ? Files.createTempFile("herd-geoip-", ".mmdb")
                : Files.createTempFile(tempDir, "herd-geoip-", ".mmdb");
        boolean keep = false;
        try {
            boolean found = withinDeadline(uri, target);
            keep = found;
            return found ? Optional.of(target) : Optional.empty();
        } finally {
            if (!keep) {
                Files.deleteIfExists(target);
            }
        }
    }

    /** The transfer on a thread of its own, so that a stalled one can be abandoned; its file is {@code target}, owned by the
     *  caller, which deletes it when this does not return {@code true}. */
    private boolean withinDeadline(URI uri, Path target) throws IOException, InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> transfer = executor.submit(() -> download(uri, target));
            try {
                return transfer.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                transfer.cancel(true);
                throw new IOException("no complete answer from " + uri.getHost() + " within "
                        + (deadline.toSeconds() >= 1 ? deadline.toSeconds() + " s" : deadline.toMillis() + " ms"));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new IOException(cause);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** {@code true}: the unpacked file is in {@code target}; {@code false}: the server has no file for the month. */
    private boolean download(URI uri, Path target) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(HEADERS_TIMEOUT).header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 404) {
                    return false;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status + " from " + uri.getHost());
                }
                try (InputStream unpacked = new GZIPInputStream(body); OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int n;
                    while ((n = unpacked.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("the transfer was abandoned");
                        }
                        total += n;
                        if (total > maxUnpackedBytes) {
                            throw new IOException("the unpacked file is larger than " + (maxUnpackedBytes >> 20)
                                    + " MB — not the country database");
                        }
                        out.write(buffer, 0, n);
                    }
                }
                return true;
            }
        }
    }
}
