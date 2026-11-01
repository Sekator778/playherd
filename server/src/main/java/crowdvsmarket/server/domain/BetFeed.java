package crowdvsmarket.server.domain;

import crowdvsmarket.server.dto.Enums;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-62: an append-only, in-memory "who bet what" activity feed per round, backing the public
 * {@code betFeed} RPC (visible live during {@code BETTING}, like Twitch Predictions). The single
 * writer is {@code EventApplier}'s {@code BetPlaced} case — exactly like {@link EngineHub}/
 * {@code ComparisonAccounts}, this is rebuilt from scratch by replaying {@code game_events} at
 * startup ({@code EngineRebuildService#rebuild}, which calls {@link #reset()} first), so nothing
 * here is itself persisted: every entry is fully reconstructible from its {@code BetPlaced} event.
 *
 * <p>Capped two ways so neither an unusually bet-heavy round nor an unboundedly long-running
 * process can grow this without bound: at most {@link #MAX_ENTRIES_PER_ROUND} entries kept per
 * round (oldest evicted first — a live feed only cares about recent activity) and at most
 * {@link #MAX_ROUNDS_TRACKED} rounds tracked at all (oldest round evicted first, by insertion
 * order — i.e. the order rounds first got a bet, not last-bet recency).
 *
 * <p>Not thread-safe on its own merits (mirrors every other piece of {@link EngineHub}-adjacent
 * state in this codebase) — every mutation happens already holding {@code GameService}'s
 * {@code engineLock} write lock (via {@code EventApplier}, called either from a {@code GameService}
 * mutating method or from {@code EngineRebuildService.rebuild()}'s {@code runExclusively} block),
 * and every read ({@code GameService.betFeed}) holds that same lock's read side — see those
 * classes' javadoc. The {@code synchronized} methods here are a cheap extra guard, not the
 * primary safety mechanism.
 */
@ApplicationScoped
public final class BetFeed {

    /** Per-round retention cap (proposal — no source fixes a number) — generous for the 20-bot/
     *  few-human local stand (D-46) while still bounding memory for a process that runs for weeks. */
    public static final int MAX_ENTRIES_PER_ROUND = 120;
    /** How many rounds' feeds are kept at all — bounds total memory across a long-running process
     *  (settled rounds' feeds are of shrinking interest; {@code round_results}/
     *  {@code player_round_history} remain the durable historical record regardless). */
    public static final int MAX_ROUNDS_TRACKED = 500;
    /** Max entries returned by one {@code betFeed} call — {@code hasMore} tells the caller to page
     *  again immediately instead of waiting for the next poll tick. */
    public static final int PAGE_SIZE = 50;

    /** {@code playerId} is carried only so {@link #latest} can look up a full
     *  {@code Identity.PlayerSummaryDto} for {@code RoundBetting.recentBets}' {@code LiveBetDto}
     *  (nested identity) — the dedicated {@code betFeed} RPC's own {@code BetFeedEntryDto} shape
     *  flattens to just {@code playerDisplayName}/{@code isBot} and never exposes it. */
    public record Entry(long seq, long atMs, String playerId, String playerDisplayName, boolean isBot,
                          Enums.Direction direction, String stake, int leverage, Enums.BetMode mode) {
    }

    public record Result(List<Entry> entries, boolean hasMore) {
    }

    private final AtomicLong seqCounter = new AtomicLong(0);
    private final Map<String, Deque<Entry>> byRound = new LinkedHashMap<>();

    public synchronized void reset() {
        seqCounter.set(0);
        byRound.clear();
    }

    /** Takes over everything {@code built} holds — its rows and its sequence counter: the swap that
     *  ends a rebuild, whose replay filled a feed of its own ({@code new BetFeed()}) while this one
     *  went on serving. {@code built} must not be used afterwards. */
    public void adopt(BetFeed built) {
        // Both monitors, `built`'s first: nothing else ever holds both, so the order cannot deadlock.
        synchronized (built) {
            synchronized (this) {
                seqCounter.set(built.seqCounter.get());
                byRound.clear();
                byRound.putAll(built.byRound);
            }
        }
    }

    /** Appends one entry for {@code roundId} — called only from {@code EventApplier}'s
     *  {@code BetPlaced} case, live or replayed, so a bet edit (D-13: a new bet by the same player
     *  replacing their still-pending one) shows as a new feed row too, never rewriting/removing the
     *  earlier one. */
    public void record(String roundId, String playerId, String playerDisplayName, boolean isBot,
                       Enums.Direction direction, String stake, int leverage, long atMs) {
        record(roundId, playerId, playerDisplayName, isBot, direction, stake, leverage, atMs, null);
    }

    /** {@link #record(String, String, String, boolean, Enums.Direction, String, int, long)} with the mode of the bet (D-106). */
    public synchronized void record(String roundId, String playerId, String playerDisplayName, boolean isBot,
                                     Enums.Direction direction, String stake, int leverage, long atMs, Enums.BetMode mode) {
        Deque<Entry> entries = byRound.computeIfAbsent(roundId, r -> new ArrayDeque<>());
        entries.addLast(new Entry(seqCounter.incrementAndGet(), atMs, playerId, playerDisplayName, isBot, direction,
                stake, leverage, mode));
        while (entries.size() > MAX_ENTRIES_PER_ROUND) {
            entries.removeFirst();
        }
        while (byRound.size() > MAX_ROUNDS_TRACKED) {
            var oldest = byRound.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Entries for {@code roundId} with {@code seq > sinceSeq} (pass {@code 0} for the very first
     *  page), oldest first, capped to {@link #PAGE_SIZE}. An unknown/never-bet-on round returns an
     *  empty, {@code hasMore=false} result rather than an error — same "empty, not an error"
     *  convention as this codebase's other paginated reads. */
    /** The feed of one moment: the counter and, per round in the order they were first recorded, its entries oldest first
     *  (architecture plan F5: a snapshot). */
    public record State(long seq, Map<String, List<Entry>> rounds) {
    }

    public synchronized State state() {
        Map<String, List<Entry>> rounds = new LinkedHashMap<>();
        byRound.forEach((roundId, entries) -> rounds.put(roundId, List.copyOf(entries)));
        return new State(seqCounter.get(), rounds);
    }

    /** Puts a saved state into this (fresh) feed. */
    public synchronized void restore(State state) {
        seqCounter.set(state.seq());
        byRound.clear();
        state.rounds().forEach((roundId, entries) -> byRound.put(roundId, new ArrayDeque<>(entries)));
    }

    public synchronized Result since(String roundId, long sinceSeq) {
        Deque<Entry> entries = byRound.get(roundId);
        return entries == null ? new Result(List.of(), false) : pageSince(entries, sinceSeq);
    }

    private static Result pageSince(Iterable<Entry> entries, long sinceSeq) {
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > sinceSeq) {
                matched.add(e);
            }
        }
        boolean hasMore = matched.size() > PAGE_SIZE;
        List<Entry> page = hasMore ? matched.subList(0, PAGE_SIZE) : matched;
        return new Result(List.copyOf(page), hasMore);
    }

    /** The newest {@code limit} entries for {@code roundId}, most-recent-first — backs
     *  {@code RoundBetting.recentBets}' small convenience embed (distinct from {@link #since}'s
     *  oldest-first, cursor-paginated feed: reading from the tail directly is correct here
     *  regardless of how many total entries a busy round has accumulated, where {@code since}'s own
     *  "oldest {@code PAGE_SIZE} matching seq > sinceSeq" logic would instead return the wrong end
     *  of a long feed). Empty for an unknown/never-bet-on round. */
    public synchronized List<Entry> latest(String roundId, int limit) {
        Deque<Entry> entries = byRound.get(roundId);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return newestFirst(entries.descendingIterator(), Math.min(limit, entries.size()), limit);
    }

    private static List<Entry> newestFirst(Iterator<Entry> descending, int sizeHint, int limit) {
        List<Entry> out = new ArrayList<>(sizeHint);
        while (descending.hasNext() && out.size() < limit) {
            out.add(descending.next());
        }
        return out;
    }

    /** The entries of one round, oldest first, as an immutable copy (what the read views keep, architecture plan F8). */
    public synchronized List<Entry> entriesOf(String roundId) {
        Deque<Entry> entries = byRound.get(roundId);
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Every round's entries as an immutable {@link Rows}: the full set the read views publish when a rebuild has swapped the feed. */
    public synchronized Rows rows() {
        Map<String, List<Entry>> all = new LinkedHashMap<>();
        byRound.forEach((roundId, entries) -> all.put(roundId, List.copyOf(entries)));
        return new Rows(all);
    }

    /**
     * An immutable picture of the feed, the same reads as the feed itself ({@link #since}, {@link #latest}) with no monitor: what
     * the core writer publishes after a commit, so a read of the feed never sees a row of a batch that has not committed.
     * {@link #with} builds the next picture from this one and the rounds a batch touched, under the same cap of rounds.
     */
    public static final class Rows {

        public static final Rows EMPTY = new Rows(new LinkedHashMap<>());

        private final Map<String, List<Entry>> byRound;

        private Rows(Map<String, List<Entry>> byRound) {
            this.byRound = byRound;
        }

        public Result since(String roundId, long sinceSeq) {
            List<Entry> entries = byRound.get(roundId);
            return entries == null ? new Result(List.of(), false) : pageSince(entries, sinceSeq);
        }

        public List<Entry> latest(String roundId, int limit) {
            List<Entry> entries = byRound.get(roundId);
            if (entries == null || entries.isEmpty()) {
                return List.of();
            }
            return newestFirst(new ReverseIterator(entries), Math.min(limit, entries.size()), limit);
        }

        /** These rows with {@code touched} rounds' entries replaced (a new round goes last, like in the feed) and the oldest
         *  rounds dropped beyond {@link #MAX_ROUNDS_TRACKED}. */
        public Rows with(Map<String, List<Entry>> touched) {
            if (touched.isEmpty()) {
                return this;
            }
            Map<String, List<Entry>> next = new LinkedHashMap<>(byRound);
            next.putAll(touched);
            while (next.size() > MAX_ROUNDS_TRACKED) {
                var oldest = next.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            return new Rows(next);
        }

        public int rounds() {
            return byRound.size();
        }

        /** The rounds and their entries, in the order of the feed (for tests that compare two pictures). */
        public Map<String, List<Entry>> asMap() {
            return java.util.Collections.unmodifiableMap(byRound);
        }

        private static final class ReverseIterator implements Iterator<Entry> {
            private final List<Entry> list;
            private int next;

            ReverseIterator(List<Entry> list) {
                this.list = list;
                this.next = list.size() - 1;
            }

            @Override
            public boolean hasNext() {
                return next >= 0;
            }

            @Override
            public Entry next() {
                return list.get(next--);
            }
        }
    }
}
