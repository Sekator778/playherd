package crowdvsmarket.server.push;

/** Where one client's notices go. Implementations never block: a notice that cannot be taken now is refused. */
public interface PushSink {

    /** Hands {@code text} to the connection; {@code false} when its send buffer is full (the notice is dropped, the client resyncs). */
    boolean offer(String text);

    /** Closes the connection (a slow, dead or over-limit client). Idempotent. */
    void close(int code, String reason);
}
