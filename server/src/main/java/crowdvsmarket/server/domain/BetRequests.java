package crowdvsmarket.server.domain;

import crowdvsmarket.engine.round.Direction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The memory behind an idempotent {@code placeBet} (architecture plan, lmax_roadmap step 1: "{@code clientRequestId} in a bet"):
 * the bets that arrived with a client-chosen request id, per player and id, for {@value #WINDOW_MS} ms. A repeat of the same request
 * (the client retried after an error on the way back) finds its entry and is answered with the first answer instead of placing
 * another bet.
 *
 * <p>It is a part of the engine state, kept by {@code EventApplier} from {@code BetPlaced.clientRequestId} - live and in every
 * replay alike, so a rebuild restores it - and carried by the snapshot ({@code StateCodec}). What it holds is exactly what the event
 * says, nothing is derived from the clock of the machine: an entry leaves only when a later entry is added (older than the window
 * by the later event's {@code atMs}, or beyond {@value #MAX_ENTRIES} entries, oldest first), so the live state and a replay of the
 * same events are the same. Whether an entry still counts for a request is decided by the request's own time ({@link #find}).
 *
 * <p>Touched by the writer thread (commands) and by the snapshot / digest readers, which hold the gate; the methods are
 * synchronized for the latter.
 */
public final class BetRequests {

    /** How long a request id is remembered. */
    public static final long WINDOW_MS = 10 * 60_000L;

    /** The most entries kept; the oldest go first. */
    public static final int MAX_ENTRIES = 50_000;

    /** What was placed: the facts of the {@code BetPlaced} event the answer is rebuilt from. */
    public record Entry(String playerId, String requestId, long atMs, String roundId, Direction direction, int leverage,
                        long stakeMojos, Long feeHerdMojos, Long feeNetMojos, boolean fixed) {
    }

    private record Key(String playerId, String requestId) {
    }

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();

    /** The entry of {@code requestId} of the player that is still within the window at {@code nowMs}, or {@code null}. */
    public synchronized Entry find(String playerId, String requestId, long nowMs) {
        Entry e = entries.get(new Key(playerId, requestId));
        return e != null && nowMs - e.atMs() <= WINDOW_MS ? e : null;
    }

    /** Remembers a bet that came with a request id (a later one with the same player and id replaces the earlier). */
    public synchronized void remember(Entry entry) {
        Key key = new Key(entry.playerId(), entry.requestId());
        entries.remove(key); // re-inserted last: the order is the order of the events
        entries.put(key, entry);
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext();) {
            Entry oldest = it.next();
            if (entries.size() > MAX_ENTRIES || entry.atMs() - oldest.atMs() > WINDOW_MS) {
                it.remove();
            } else {
                break;
            }
        }
    }

    /** The entries in the order they were remembered (a snapshot writes them so). */
    public synchronized List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    /** Puts saved entries back, in the order {@link #all} gave them; the memory is fresh. */
    public synchronized void restore(List<Entry> saved) {
        entries.clear();
        for (Entry e : saved) {
            entries.put(new Key(e.playerId(), e.requestId()), e);
        }
    }

    public synchronized int size() {
        return entries.size();
    }
}
