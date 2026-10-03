package crowdvsmarket.server.prices;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A WebSocket server on the loopback interface (RFC 6455, the little of it a test needs: the opening handshake, text
 * frames in both directions, a message split into frames, ping, close) — so that {@link JdkWebSocketTransport} is
 * tested against a real socket and the JDK's real client, never the network. One client at a time; what the client
 * sent is in {@link #received}.
 */
final class LocalWebSocketServer implements AutoCloseable {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket server;
    private final Thread acceptor;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final CountDownLatch gone = new CountDownLatch(1);
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile long handshakeDelayMs;
    /** The client closed its connection while the (delayed) handshake was still unanswered. */
    volatile boolean leftDuringHandshake;
    /** The text messages the client sent, in order. */
    final List<String> received = new CopyOnWriteArrayList<>();
    /** How many ping frames the client sent. */
    volatile int pings;
    volatile boolean clientClosed;

    LocalWebSocketServer() throws IOException {
        server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::serve, "local-websocket-server");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    URI uri() {
        return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/ws");
    }

    /** Waits until a client has completed the handshake. */
    boolean awaitClient(long timeoutMs) throws InterruptedException {
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** The server answers the next opening handshake this long after it has read the request: a slow server. */
    void delayHandshake(long millis) {
        handshakeDelayMs = millis;
    }

    /** Waits until the client's connection is over — it closed, was dropped or aborted; false if it is still open. */
    boolean awaitClientGone(long timeoutMs) throws InterruptedException {
        return gone.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void serve() {
        try {
            Socket client = server.accept();
            socket = client;
            InputStream in = client.getInputStream();
            OutputStream output = client.getOutputStream();
            String request;
            try {
                request = readHeaders(in);
            } catch (IOException e) { // the client left before it had even finished its request
                leftDuringHandshake = true;
                return;
            }
            if (handshakeDelayMs > 0 && clientLeftWithin(client, in, handshakeDelayMs)) {
                leftDuringHandshake = true;
                return; // the client called the handshake off: nothing to answer
            }
            String key = null;
            for (String line : request.split("\r\n")) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();
            out = output;
            connected.countDown();
            readFrames(in);
        } catch (Exception e) {
            // closed by the test
        } finally {
            gone.countDown(); // whatever ended it: the client closed, was dropped, aborted — or never came
        }
    }

    /**
     * Waits {@code millis} for the client to say or do anything: true when it closed its end (or reset the connection) in the
     * meantime, false when the time was up with the client still there. A client sends nothing between its request and our answer.
     */
    private static boolean clientLeftWithin(Socket client, InputStream in, long millis) throws IOException {
        client.setSoTimeout((int) millis);
        try {
            return in.read() < 0;
        } catch (SocketTimeoutException e) {
            return false;
        } catch (SocketException e) { // reset by the peer
            return true;
        } finally {
            client.setSoTimeout(0);
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed in the handshake");
            }
            bytes.write(b);
            String text = bytes.toString(StandardCharsets.ISO_8859_1);
            if (text.endsWith("\r\n\r\n")) {
                return text;
            }
        }
    }

    private void readFrames(InputStream in) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return;
            }
            int b1 = in.read();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            long length = b1 & 0x7f;
            if (length == 126) {
                length = (in.read() << 8) | in.read();
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | in.read();
                }
            }
            byte[] mask = in.readNBytes(4); // a client always masks
            byte[] payload = in.readNBytes((int) length);
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
            switch (opcode) {
                case 0x1, 0x0 -> {
                    message.write(payload);
                    if (fin) {
                        received.add(message.toString(StandardCharsets.UTF_8));
                        message.reset();
                    }
                }
                case 0x9 -> {
                    pings++;
                    write(0xA, payload, true); // pong
                }
                case 0x8 -> {
                    clientClosed = true;
                    write(0x8, payload, true); // answer the closing handshake
                    return;
                }
                default -> {
                    // pong, binary: not used
                }
            }
        }
    }

    /** The server sends one text message, in one frame. */
    void send(String text) throws IOException {
        write(0x1, text.getBytes(StandardCharsets.UTF_8), true);
    }

    /** The server sends one text message split into frames of the given parts (a text frame, then continuations). */
    void sendFragmented(String... parts) throws IOException {
        for (int i = 0; i < parts.length; i++) {
            write(i == 0 ? 0x1 : 0x0, parts[i].getBytes(StandardCharsets.UTF_8), i == parts.length - 1);
        }
    }

    /** The server closes the connection with a close frame. */
    void closeWith(int code, String reason) throws IOException {
        byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + reasonBytes.length];
        payload[0] = (byte) (code >> 8);
        payload[1] = (byte) code;
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
        write(0x8, payload, true);
    }

    /** The server drops the TCP connection without a word. */
    void drop() throws IOException {
        Socket s = socket;
        if (s != null) {
            s.close();
        }
    }

    private synchronized void write(int opcode, byte[] payload, boolean fin) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("no client");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((fin ? 0x80 : 0) | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length <= 0xffff) {
            frame.write(126);
            frame.write(payload.length >> 8);
            frame.write(payload.length);
        } else {
            frame.write(127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) payload.length >> (8 * i)) & 0xff));
            }
        }
        frame.write(payload); // a server never masks
        o.write(frame.toByteArray());
        o.flush();
    }

    @Override
    public void close() throws IOException {
        Socket s = socket;
        if (s != null) {
            s.close();
        }
        server.close();
    }
}
