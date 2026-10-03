package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The exchange each round of a market with a backup runs on — what Р-76 ("a round runs entirely on one exchange") fixes:
 * decided <b>once</b>, when the round opens, and never changed.
 *
 * <p>This class holds the decisions and writes them; {@link LiveMarketPrices} makes them (it has the tick rings the choice
 * is read from). A decision is a row of {@code round_venues} ({@link RoundVenueStore}), written once
 * ({@link #decide}: {@code INSERT … ON CONFLICT DO NOTHING}, then the stored row is read back, so that whoever wrote first
 * decided, whatever a second caller thought), and kept in memory next to it. The recent rows are loaded at start
 * ({@link #load}), so a restart does not change the exchange of a round that is running or waiting to be settled; a round
 * older than what was loaded is found in the table ({@link #venueOfRound}).
 *
 * <p>Two rounds of one market that start at the same moment — the two series share their boundaries — decide on the same
 * data and so agree. Should one of them be decided later than the other (a start that no exchange could price at once and
 * history had to heal), it takes what the other decided: {@link #decidedForStart}.
 *
 * <p>{@link #decided} and {@link #decidedForStart} are memory only — the read paths under the engine's lock use them;
 * {@link #venueOfRound} and {@link #decide} may go to the database and are for the scheduler and the settlement.
 */
@ApplicationScoped
@Unremovable
public class RoundVenues {

    private static final Logger LOG = Logger.getLogger(RoundVenues.class);

    /** A round's decisions are kept in memory this long past the buffer's retention (a round is settled within an hour of its end). */
    static final long KEEP_EXTRA_MS = 3_600_000L;
    /** The in-memory decisions are pruned once in this many {@link #decide} calls. */
    private static final int PRUNE_EVERY = 256;

    private record Entry(String venue, long startMs) {
    }

    private final RoundVenueStore store;
    private final long retentionMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Entry> byRound = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entry> byStart = new ConcurrentHashMap<>();
    private final AtomicInteger decisions = new AtomicInteger();

    @Inject
    public RoundVenues(RoundVenueStore store, PricesConfig config) {
        this(store, config.retentionMs(), System::currentTimeMillis);
    }

    RoundVenues(RoundVenueStore store, long retentionMs, LongSupplier clock) {
        this.store = store;
        this.retentionMs = retentionMs;
        this.clock = clock;
    }

    /** A holder of decisions with no database behind it — for tests and for what is built by hand. */
    public static RoundVenues inMemory(long retentionMs, LongSupplier clock) {
        return new RoundVenues(new RoundVenueStore() {
            private final ConcurrentHashMap<String, Decision> rows = new ConcurrentHashMap<>();

            @Override
            public Decision insertOnce(Decision decision) {
                return rows.computeIfAbsent(decision.roundId(), id -> decision);
            }

            @Override
            public Optional<Decision> find(String roundId) {
                return Optional.ofNullable(rows.get(roundId));
            }

            @Override
            public List<Decision> since(long decidedAtMs) {
                return rows.values().stream().filter(d -> d.decidedAtMs() >= decidedAtMs).toList();
            }
        }, retentionMs, clock);
    }

    /** Loads the decisions of the last {@code retention + 1 h} at start (live mode only: nothing decides in another). */
    void onStart(@Observes StartupEvent event) {
        try {
            load();
        } catch (RuntimeException e) {
            // not fatal: decide() reads the stored row back, so a decision made before still wins; only the display of a running round waits
            LOG.warnf("could not load the round venues, starting empty: %s", e.toString());
        }
    }

    /** Reads the recent decisions from the store into memory; returns how many. */
    public int load() {
        List<RoundVenueStore.Decision> rows = store.since(clock.getAsLong() - retentionMs - KEEP_EXTRA_MS);
        for (RoundVenueStore.Decision row : rows) {
            remember(row);
        }
        LOG.infof("round venues loaded: %d decision(s)", rows.size());
        return rows.size();
    }

    /** The venue decided for the round — memory only. Empty while nothing is decided (or the round has no venue to choose). */
    public Optional<String> decided(String roundId) {
        Entry entry = byRound.get(roundId);
        return entry == null ? Optional.empty() : Optional.of(entry.venue());
    }

    /** The venue another round of the market that starts at {@code startMs} was decided on — memory only. */
    public Optional<String> decidedForStart(String marketId, long startMs) {
        Entry entry = byStart.get(startKey(marketId, startMs));
        return entry == null ? Optional.empty() : Optional.of(entry.venue());
    }

    /** {@link #decided}, and for a round that is not in memory the table's row (which is then remembered). May block on the database. */
    public Optional<String> venueOfRound(String roundId) {
        Optional<String> known = decided(roundId);
        if (known.isPresent()) {
            return known;
        }
        Optional<RoundVenueStore.Decision> row = store.find(roundId);
        row.ifPresent(this::remember);
        return row.map(RoundVenueStore.Decision::venue);
    }

    /**
     * Decides the round: writes {@code venue} unless the round has a row already, and returns the venue the table holds
     * for the round — {@code venue} when this call decided, the earlier one when another had. May block on the database.
     *
     * @param startMs the start of the round's window, for {@link #decidedForStart}
     */
    public String decide(String roundId, String marketId, long startMs, String venue, long nowMs) {
        RoundVenueStore.Decision stored = store.insertOnce(new RoundVenueStore.Decision(roundId, marketId, venue, nowMs));
        Entry entry = new Entry(stored.venue(), startMs);
        byRound.put(roundId, entry);
        byStart.putIfAbsent(startKey(marketId, startMs), entry);
        if (!stored.venue().equals(venue)) {
            LOG.infof("round %s was decided on %s before this call wanted %s: it stays", roundId, stored.venue(), venue);
        } else {
            LOG.infof("round %s runs on %s (decided %d ms after it opened)", roundId, venue, nowMs - startMs);
        }
        if (decisions.incrementAndGet() % PRUNE_EVERY == 0) {
            prune(nowMs);
        }
        return stored.venue();
    }

    private void remember(RoundVenueStore.Decision row) {
        long startMs;
        try {
            startMs = RoundWindow.parseRoundId(row.roundId()).startMs();
        } catch (IllegalArgumentException e) {
            return; // a row whose id is no round id: nothing to key the start by, and nothing asks for it
        }
        Entry entry = new Entry(row.venue(), startMs);
        byRound.put(row.roundId(), entry);
        byStart.putIfAbsent(startKey(row.marketId(), startMs), entry);
    }

    /** Forgets the decisions of rounds that started more than {@code 2 × retention + 1 h} before {@code nowMs}. */
    void prune(long nowMs) {
        long cutoff = nowMs - 2 * retentionMs - KEEP_EXTRA_MS;
        byRound.values().removeIf(e -> e.startMs() < cutoff);
        byStart.values().removeIf(e -> e.startMs() < cutoff);
    }

    /** How many rounds are decided in memory — for tests and diagnostics. */
    int size() {
        return byRound.size();
    }

    private static String startKey(String marketId, long startMs) {
        return marketId + '@' + startMs;
    }
}
