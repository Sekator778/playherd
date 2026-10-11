package crowdvsmarket.server.geoip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DbIpDownloader} against a server on the loopback interface that plays DB-IP: the monthly file is {@code
 * dbip-country-lite-YYYY-MM.mmdb.gz}, a month that is not published answers 404 (checked on download.db-ip.com
 * 2026-10-11: {@code 2026-11} and {@code 2026-13} are 404 with an HTML body, {@code 2026-10} and {@code 2026-09} are 200).
 * Nothing leaves the machine.
 */
class DbIpDownloaderTest {

    /** What the fake server answers for a path. */
    private record Reply(int status, byte[] body, boolean stallAfterHeaders) {
        static Reply ok(byte[] body) {
            return new Reply(200, body, false);
        }

        static Reply status(int status) {
            return new Reply(status, "<html>no</html>".getBytes(StandardCharsets.UTF_8), false);
        }
    }

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requested.add(exchange.getRequestURI().getPath());
            Reply reply = replies.getOrDefault(exchange.getRequestURI().getPath(), Reply.status(404));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(reply.status(), reply.body().length);
            OutputStream out = exchange.getResponseBody();
            if (reply.stallAfterHeaders()) {
                out.write(reply.body(), 0, Math.min(10, reply.body().length));
                out.flush();
                Thread.sleep(60_000); // until the test stops the server
            }
            out.write(reply.body());
            out.close();
        } catch (IOException | InterruptedException e) {
            // the client gave up
        } finally {
            exchange.close();
        }
    }

    private String template() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/free/dbip-country-lite-YYYY-MM.mmdb.gz";
    }

    private DbIpDownloader downloader() {
        return new DbIpDownloader(template(), tempDir, Duration.ofSeconds(20), DbIpDownloader.DEFAULT_MAX_UNPACKED_BYTES);
    }

    private static byte[] gzip(byte[] plain) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(plain);
        }
        return bytes.toByteArray();
    }

    private List<String> filesInTempDir() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Test
    void theUrlIsTheTemplateWithTheMonthInIt() {
        assertThat(new DbIpDownloader(DbIpDownloader.DEFAULT_URL_TEMPLATE).urlFor(YearMonth.of(2026, 10)))
                .hasToString("https://download.db-ip.com/free/dbip-country-lite-2026-10.mmdb.gz");
        assertThat(new DbIpDownloader(DbIpDownloader.DEFAULT_URL_TEMPLATE).urlFor(YearMonth.of(2027, 1)))
                .hasToString("https://download.db-ip.com/free/dbip-country-lite-2027-01.mmdb.gz");
    }

    @Test
    void aTemplateThatIsNotAUrlIsRefusedAtOnce() {
        assertThatThrownBy(() -> new DbIpDownloader("http://exa mple.com/YYYY-MM"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFileOfTheMonthIsDownloadedAndUnpacked() throws Exception {
        byte[] mmdb = TinyMmdb.ipv6().country("192.0.2.0/24", "DE").build();
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", Reply.ok(gzip(mmdb)));

        Optional<Path> file = downloader().fetch(YearMonth.of(2026, 10));

        assertThat(file).isPresent();
        assertThat(Files.readAllBytes(file.get())).isEqualTo(mmdb);
        assertThat(requested).containsExactly("/free/dbip-country-lite-2026-10.mmdb.gz");
        assertThat(CountryDatabase.open(file.get()).countryOf(IpAddresses.parseLiteral("192.0.2.1"))).isEqualTo("DE");
    }

    @Test
    void aMonthThatIsNotPublishedIsEmptyAndLeavesNothingBehind() throws Exception {
        Optional<Path> file = downloader().fetch(YearMonth.of(2026, 11));

        assertThat(file).isEmpty();
        assertThat(requested).containsExactly("/free/dbip-country-lite-2026-11.mmdb.gz");
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void anotherStatusIsAnErrorNotAMissingMonth() throws Exception {
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", Reply.status(500));
        replies.put("/free/dbip-country-lite-2026-09.mmdb.gz", Reply.status(403));

        assertThatThrownBy(() -> downloader().fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 500");
        assertThatThrownBy(() -> downloader().fetch(YearMonth.of(2026, 9))).isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 403");
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void whatIsNotGzipIsAnErrorAndLeavesNothingBehind() throws Exception {
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", Reply.ok("<html>maintenance</html>".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> downloader().fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class);
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void aTruncatedGzipStreamIsAnErrorAndLeavesNothingBehind() throws Exception {
        byte[] packed = gzip(TinyMmdb.ipv6().country("192.0.2.0/24", "DE").build());
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", Reply.ok(java.util.Arrays.copyOf(packed, packed.length - 20)));

        assertThatThrownBy(() -> downloader().fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class);
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void aFileThatUnpacksToMoreThanTheCapIsRefused() throws Exception {
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", Reply.ok(gzip(new byte[3 * 1024 * 1024])));
        DbIpDownloader capped = new DbIpDownloader(template(), tempDir, Duration.ofSeconds(20), 1024 * 1024);

        assertThatThrownBy(() -> capped.fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class)
                .hasMessageContaining("larger than 1 MB");
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void aServerThatStallsAfterTheHeadersIsGivenUpOnAtTheDeadline() throws Exception {
        byte[] packed = gzip(new byte[200_000]);
        replies.put("/free/dbip-country-lite-2026-10.mmdb.gz", new Reply(200, packed, true));
        DbIpDownloader impatient = new DbIpDownloader(template(), tempDir, Duration.ofMillis(800),
                DbIpDownloader.DEFAULT_MAX_UNPACKED_BYTES);

        long start = System.nanoTime();
        assertThatThrownBy(() -> impatient.fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class)
                .hasMessageContaining("no complete answer").hasMessageContaining("800 ms");
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).as("gave up at the deadline, not when the server did").isBetween(700L, 10_000L);
        assertThat(filesInTempDir()).isEmpty();
    }

    @Test
    void aServerThatIsNotThereIsAnError() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        DbIpDownloader nobodyHome = new DbIpDownloader("http://127.0.0.1:" + closedPort + "/dbip-YYYY-MM.mmdb.gz", tempDir,
                Duration.ofSeconds(20), 1L << 20);

        assertThatThrownBy(() -> nobodyHome.fetch(YearMonth.of(2026, 10))).isInstanceOf(IOException.class);
        assertThat(filesInTempDir()).isEmpty();
    }
}
