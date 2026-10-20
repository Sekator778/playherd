package crowdvsmarket.server.push;

/**
 * What {@link PushHub} is tuned by ({@code herd.push.*}, {@link PushConfig}).
 *
 * @param enabled              {@code false}: the endpoint refuses the upgrade (404) and nothing is published
 * @param coalesceMs           at most one notice per topic per this many ms
 * @param maxConnections       open sockets on the whole server
 * @param maxPerIp             open sockets per client address
 * @param maxPerPlayer         open sockets per signed-in player (one player, many tabs and devices)
 * @param maxTopics            topics one socket may hold
 * @param maxPendingSends      notices handed to a socket and not yet written; past it a notice is dropped for that client
 * @param maxDroppedNotices    notices dropped for one client in a row before it is disconnected
 * @param maxAgeSec            a socket is closed after this long (it reconnects; a session that has ended is not served for ever)
 * @param clientMessagesPerSec what one socket may send to the server, per second (a burst of ten times it)
 */
public record PushSettings(boolean enabled, long coalesceMs, int maxConnections, int maxPerIp, int maxPerPlayer, int maxTopics,
                           int maxPendingSends, int maxDroppedNotices, long maxAgeSec, double clientMessagesPerSec) {

    public static PushSettings defaults() {
        return new PushSettings(true, 200, 5000, 20, 6, 40, 64, 200, 3600, 5);
    }
}
