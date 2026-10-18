package crowdvsmarket.server.jfr;

/** Clock reads for the phases of the Herd JFR events: nothing is read while the event is not being recorded. */
public final class JfrClock {

    private JfrClock() {
    }

    /** {@code System.nanoTime()} when {@code recording}, else 0. */
    public static long nanos(boolean recording) {
        return recording ? System.nanoTime() : 0L;
    }

    /** The time since {@code startNanos}, or 0 when the event is not being recorded. */
    public static long since(boolean recording, long startNanos) {
        return recording ? System.nanoTime() - startNanos : 0L;
    }
}
