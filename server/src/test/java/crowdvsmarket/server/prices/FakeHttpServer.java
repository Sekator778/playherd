package crowdvsmarket.server.prices;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A throw-away HTTP server on the loopback interface (the JDK's own) that the REST clients of the
 * price pipeline can be pointed at: records every request — method, path with query, headers, body —
 * and answers with what the test scripted. The tests of the clients never leave the machine.
 */
public final class FakeHttpServer implements AutoCloseable {

    public record Request(String method, String pathAndQuery, String contentType, String accept, String body) {
    }

    public record Reply(int status, String body, long delayMs) {

        public static Reply json(String body) {
            return new Reply(200, body, 0);
        }

        public static Reply slow(String body, long delayMs) {
            return new Reply(200, body, delayMs);
        }
    }

    private final HttpServer server;
    public final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, Reply> handler = r -> new Reply(404, "{}", 0);

    public FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reply(Function<Request, Reply> handler) {
        this.handler = handler;
    }

    public List<Request> requests() {
        return new ArrayList<>(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().getRawPath()
                + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Request request = new Request(exchange.getRequestMethod(), uri,
                exchange.getRequestHeaders().getFirst("Content-Type"), exchange.getRequestHeaders().getFirst("Accept"),
                body);
        requests.add(request);
        Reply reply = handler.apply(request);
        try {
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // the client gave up (a timeout test): nothing to answer
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService executor) {
            executor.shutdownNow();
        }
    }
}
