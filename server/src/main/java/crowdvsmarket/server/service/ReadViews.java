package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.ClosedRounds;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.GameEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * The read side of the game (architecture plan F8, docs/research/lmax_roadmap.md §3): immutable views of the engine's state that
 * the core writer publishes <b>after a batch has committed</b>, and that every hot read takes through one {@code volatile}
 * reference, with no lock. A read therefore never sees what a batch has applied and not yet committed (the engine itself is ahead
 * of the journal for the length of a commit; the views are not), and it never waits for the writer.
 *
 * <p>A {@link Snapshot} carries a {@linkplain Snapshot#version() version}: the id of the last event of the journal it includes.
 * It holds
 * <ul>
 *   <li>per round that has bets: the bets, in the order of the bet book, and by player ({@link RoundBets}) - the lobby's split,
 *       a player's open positions and the stake in play, a round's crowd split and its list of positions;
 *   <li>per market: its crowd account ({@link CrowdView}) - capital, history, the comparison lines and the counters the lobby shows;
 *   <li>per player: the PLAY balance and what may be withdrawn ({@link PlayerView});
 *   <li>the bet feed ({@link BetFeed.Rows}) and the cursors of the running series ({@link RoundProgress}) as of the commit;
 *   <li>the set of rounds that are resolved or cancelled in the journal ({@link ClosedRounds}; a structure of its own, updated after
 *       each commit, so it can be a moment ahead of the rest of the snapshot, never ahead of the journal).
 * </ul>
 *
 * <p><b>Publication.</b> {@link #publishBatch} runs on the writer thread, holding the writer's gate, once the batch is durable and
 * before any caller is answered (a caller that has been answered finds its own change in the next read). It builds the next
 * snapshot from the previous one and the engine: only the rounds, the markets' crowds and the players that the batch's events
 * touched are read again, the rest is shared with the previous snapshot (players are kept in {@value #PLAYER_SHARDS} shards,
 * copy-on-write, so one batch copies the shards of the players it touched). {@link #publishFull} builds everything from the
 * engine; it is what a rebuild publishes before the engine is marked ready, and what follows a command that does its own
 * transactions. The engine is read without its lock: the writer is the only thread that changes it, and the writer's gate keeps
 * a rebuild out.
 *
 * <p>The cursors of the series ({@link RoundProgress}) are moved by the settlement inside a batch, and by the scheduler outside of
 * one (a skipped empty window, a cancelled round): the first reach the views with the batch, the second through {@link
 * #publishCursors}.
 */
public final class ReadViews {

    /** Shards of the players' table: a batch copies the shards of the players it touched, not the table. */
    static final int PLAYER_SHARDS = 1024;

    /** A player's PLAY balance and what of it may be withdrawn; a player the ledger does not know has no view. */
    public record PlayerView(PlayAmount balance, PlayAmount withdrawable) {
    }

    /** The bets of one round, in the order of the bet book, and by player. */
    public record RoundBets(String roundId, List<Bet> bets, Map<String, Bet> byPlayer) {

        static RoundBets of(String roundId, Collection<Bet> bets) {
            Map<String, Bet> byPlayer = new LinkedHashMap<>();
            for (Bet bet : bets) {
                byPlayer.put(bet.playerId(), bet);
            }
            return new RoundBets(roundId, List.copyOf(bets), Map.copyOf(byPlayer));
        }

        public Optional<Bet> find(String playerId) {
            return Optional.ofNullable(byPlayer.get(playerId));
        }
    }

    /** What the lobby shows about a crowd account: rounds it traded, rounds it won, how the last traded round ended. */
    public record CrowdStats(int roundsTraded, int roundsWon, Enums.CrowdOutcome lastOutcome) {

        public static final CrowdStats NONE = new CrowdStats(0, 0, null);
    }

    /** One market's crowd account as of the commit: capital, history and the two comparison lines (index by index with it). */
    public record CrowdView(BigDecimal capital, List<CrowdAccount.RoundResult> history, List<BigDecimal> hold,
                            List<BigDecimal> antiCrowd, CrowdStats stats) {

        /** The account of a market whose engines exist and have not played a round. */
        static final CrowdView FRESH = new CrowdView(CrowdAccount.STARTING_CAPITAL, List.of(), List.of(), List.of(),
                CrowdStats.NONE);
    }

    /** The table of players, in shards that are replaced as a whole. */
    private static final class Players {

        static final Players EMPTY = new Players(emptyShards());

        private final Map<String, PlayerView>[] shards;

        private Players(Map<String, PlayerView>[] shards) {
            this.shards = shards;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, PlayerView>[] emptyShards() {
            Map<String, PlayerView>[] shards = new Map[PLAYER_SHARDS];
            for (int i = 0; i < shards.length; i++) {
                shards[i] = Map.of();
            }
            return shards;
        }

        private static int shardOf(String playerId) {
            return Math.floorMod(playerId.hashCode(), PLAYER_SHARDS);
        }

        PlayerView get(String playerId) {
            return shards[shardOf(playerId)].get(playerId);
        }

        int size() {
            int n = 0;
            for (Map<String, PlayerView> shard : shards) {
                n += shard.size();
            }
            return n;
        }

        /** These players with {@code changed} set and {@code gone} removed; only the shards they are in are copied. */
        Players with(Map<String, PlayerView> changed, Set<String> gone) {
            if (changed.isEmpty() && gone.isEmpty()) {
                return this;
            }
            Map<String, PlayerView>[] next = shards.clone();
            Map<Integer, Map<String, PlayerView>> copies = new HashMap<>();
            for (String id : gone) {
                copies.computeIfAbsent(shardOf(id), i -> new HashMap<>(shards[i])).remove(id);
            }
            changed.forEach((id, view) -> copies.computeIfAbsent(shardOf(id), i -> new HashMap<>(shards[i])).put(id, view));
            copies.forEach((i, copy) -> next[i] = Map.copyOf(copy));
            return new Players(next);
        }

        static Players of(Map<String, PlayerView> all) {
            return EMPTY.with(all, Set.of());
        }
    }

    /** One consistent picture of the committed state; see the class comment. */
    public static final class Snapshot {

        private final long version;
        private final Map<String, CrowdView> crowds;
        private final Map<String, RoundBets> rounds;
        private final Map<RoundProgress.SeriesKey, RoundWindow> cursors;
        private final BetFeed.Rows feed;
        private final ClosedRounds closed;
        private final Players players;

        private Snapshot(long version, Map<String, CrowdView> crowds, Map<String, RoundBets> rounds,
                         Map<RoundProgress.SeriesKey, RoundWindow> cursors, BetFeed.Rows feed, ClosedRounds closed,
                         Players players) {
            this.version = version;
            this.crowds = crowds;
            this.rounds = rounds;
            this.cursors = cursors;
            this.feed = feed;
            this.closed = closed;
            this.players = players;
        }

        /** The id of the last event of the journal this snapshot includes. */
        public long version() {
            return version;
        }

        /** The crowd account of the market, empty when the market has no engines (yet). */
        public Optional<CrowdView> crowd(String marketId) {
            return Optional.ofNullable(crowds.get(marketId));
        }

        /** Whether the market has engines. */
        public boolean hasMarket(String marketId) {
            return crowds.containsKey(marketId);
        }

        /** The bets of the round, in the order of the bet book; empty for a round without bets. */
        public List<Bet> betsFor(String roundId) {
            RoundBets round = rounds.get(roundId);
            return round == null ? List.of() : round.bets();
        }

        /** The player's bet in the round. */
        public Optional<Bet> find(String roundId, String playerId) {
            RoundBets round = rounds.get(roundId);
            return round == null ? Optional.empty() : round.find(playerId);
        }

        public int betCount(String roundId) {
            RoundBets round = rounds.get(roundId);
            return round == null ? 0 : round.bets().size();
        }

        /** The running window of the series as of the commit, empty for a series that is not seeded. */
        public Optional<RoundWindow> cursor(String marketId, Interval interval) {
            return Optional.ofNullable(cursors.get(new RoundProgress.SeriesKey(marketId, interval)));
        }

        public Collection<RoundProgress.SeriesKey> seededSeries() {
            return cursors.keySet();
        }

        /** The player's balance and withdrawable amount, empty for a player the ledger does not know. */
        public Optional<PlayerView> player(String playerId) {
            return Optional.ofNullable(players.get(playerId));
        }

        public BetFeed.Rows feed() {
            return feed;
        }

        /** Whether the round is resolved or cancelled in the journal. */
        public boolean isClosed(RoundWindow window) {
            return closed.isClosed(window);
        }

        /** The number of rounds with bets (for tests and the log). */
        public int roundsWithBets() {
            return rounds.size();
        }

        /** The number of players with a view (for tests and the log). */
        public int playerCount() {
            return players.size();
        }

        // what a test compares when it asks whether an incremental publication equals a full one

        Map<String, RoundBets> roundsByIdForTest() {
            return rounds;
        }

        Map<String, CrowdView> crowdsForTest() {
            return crowds;
        }

        Map<String, PlayerView> playersForTest() {
            Map<String, PlayerView> all = new HashMap<>();
            for (Map<String, PlayerView> shard : players.shards) {
                all.putAll(shard);
            }
            return all;
        }

        Map<RoundProgress.SeriesKey, RoundWindow> cursorsForTest() {
            return cursors;
        }

        Snapshot withCursors(Map<RoundProgress.SeriesKey, RoundWindow> next) {
            return new Snapshot(version, crowds, rounds, next, feed, closed, players);
        }
    }

    private volatile Snapshot current;

    /** The latest published snapshot, {@code null} before the first one (the engine is not ready then) and after {@link #invalidate}. */
    public Snapshot current() {
        return current;
    }

    /** Forgets the snapshot: reads answer 503 until the next full publication. For a publication that failed. */
    public synchronized void invalidate() {
        current = null;
    }

    // ---------------------------------------------------------------------------------------
    // Publication
    // ---------------------------------------------------------------------------------------

    /** Builds the whole snapshot from the engine and publishes it with {@code version}. */
    public synchronized Snapshot publishFull(EngineHub hub, BetFeed feed, RoundProgress progress, long version) {
        Map<String, RoundBets> rounds = new HashMap<>();
        Map<String, CrowdView> crowds = new HashMap<>();
        for (String marketId : hub.marketIds()) {
            hub.findMarket(marketId).ifPresent(engines -> {
                crowds.put(marketId, crowdOf(engines));
                for (Interval interval : Interval.values()) {
                    GameEngine engine = engines.engine(interval);
                    for (String roundId : engine.betBook().roundIds()) {
                        rounds.put(roundId, RoundBets.of(roundId, engine.betBook().betsFor(roundId)));
                    }
                }
            });
        }
        Snapshot snapshot = new Snapshot(version, Map.copyOf(crowds), Map.copyOf(rounds), cursorsOf(progress), feed.rows(),
                hub.closedRounds().copy(), Players.of(allPlayers(hub.ledger())));
        current = snapshot;
        return snapshot;
    }

    /** Moves the cursors of the series to where {@code progress} has them now, nothing else: for a cursor moved outside a batch. */
    public synchronized void publishCursors(RoundProgress progress) {
        Snapshot old = current;
        if (old != null) {
            current = old.withCursors(cursorsOf(progress));
        }
    }

    /**
     * Publishes the snapshot that follows a committed batch. {@code events} are the batch's events, in order; {@code version} is the
     * id of the last event of the journal now, or a negative number when the batch wrote none (the version stays). Without a
     * previous snapshot this is a full publication.
     */
    public synchronized Snapshot publishBatch(List<GameEvent> events, EngineHub hub, BetFeed feed, RoundProgress progress,
                                              long version) {
        Snapshot old = current;
        if (old == null) {
            return publishFull(hub, feed, progress, Math.max(version, 0));
        }
        Touched touched = new Touched(old, hub);
        for (GameEvent event : events) {
            touched.add(event);
        }

        Map<String, RoundBets> rounds = old.rounds;
        if (!touched.rounds.isEmpty()) {
            rounds = new HashMap<>(old.rounds);
            for (RoundRef ref : touched.rounds.values()) {
                Collection<Bet> bets = hub.findMarket(ref.market()).map(m -> m.engine(ref.interval()).betBook()
                        .betsFor(ref.roundId())).orElse(List.of());
                if (bets.isEmpty()) {
                    rounds.remove(ref.roundId());
                } else {
                    rounds.put(ref.roundId(), RoundBets.of(ref.roundId(), bets));
                }
            }
        }

        Set<String> crowdMarkets = new LinkedHashSet<>(touched.crowds);
        for (String marketId : hub.marketIds()) {
            if (!old.crowds.containsKey(marketId)) {
                crowdMarkets.add(marketId); // engines created without an event of their own (a listing, a first series)
            }
        }
        Map<String, CrowdView> crowds = old.crowds;
        if (!crowdMarkets.isEmpty()) {
            crowds = new HashMap<>(old.crowds);
            for (String marketId : crowdMarkets) {
                Optional<EngineHub.MarketEngines> engines = hub.findMarket(marketId);
                if (engines.isPresent()) {
                    crowds.put(marketId, crowdOf(engines.get()));
                }
            }
        }

        BetFeed.Rows rows = old.feed;
        if (!touched.feedRounds.isEmpty()) {
            Map<String, List<BetFeed.Entry>> entries = new LinkedHashMap<>();
            for (String roundId : touched.feedRounds) {
                entries.put(roundId, feed.entriesOf(roundId));
            }
            rows = rows.with(entries);
        }

        for (RoundWindow window : touched.closedWindows) {
            old.closed.closed(window);
        }

        Players players = old.players;
        LedgerBook ledger = hub.ledger();
        if (touched.allPlayers) {
            players = Players.of(allPlayers(ledger));
        } else if (!touched.players.isEmpty()) {
            Map<String, PlayerView> changed = new HashMap<>();
            Set<String> gone = new HashSet<>();
            for (String playerId : touched.players) {
                PlayerView view = playerOf(ledger, playerId);
                if (view == null) {
                    gone.add(playerId);
                } else {
                    changed.put(playerId, view);
                }
            }
            players = players.with(changed, gone);
        }

        Snapshot snapshot = new Snapshot(version < 0 ? old.version : version, crowds, rounds, cursorsOf(progress), rows,
                old.closed, players);
        current = snapshot;
        return snapshot;
    }

    // ---------------------------------------------------------------------------------------
    // What a batch touched
    // ---------------------------------------------------------------------------------------

    private record RoundRef(String market, Interval interval, String roundId) {
    }

    /**
     * What the events of a batch changed in the engine: the rounds whose bets changed, the markets whose crowd account moved, the
     * players whose balance moved, the windows that closed. Exhaustive over the events ({@link GameEvent} is sealed): a new kind of
     * event does not compile until it says what it touches here.
     */
    private static final class Touched {

        final Map<String, RoundRef> rounds = new LinkedHashMap<>();
        final Set<String> crowds = new LinkedHashSet<>();
        final Set<String> players = new LinkedHashSet<>();
        final Set<String> feedRounds = new LinkedHashSet<>();
        final List<RoundWindow> closedWindows = new ArrayList<>();
        boolean allPlayers;

        private final Snapshot old;
        private final EngineHub hub;

        Touched(Snapshot old, EngineHub hub) {
            this.old = old;
            this.hub = hub;
        }

        void add(GameEvent event) {
            switch (event) {
                case GameEvent.PlayerRegistered e -> players.add(e.playerId());
                case GameEvent.BetPlaced e -> {
                    RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                    rounds.put(window.roundId(), new RoundRef(e.market(), e.interval(), window.roundId()));
                    feedRounds.add(window.roundId());
                    players.add(e.playerId());
                }
                case GameEvent.BetCancelled e -> {
                    RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                    rounds.put(window.roundId(), new RoundRef(e.market(), e.interval(), window.roundId()));
                    players.add(e.playerId());
                }
                case GameEvent.RoundResolved e -> {
                    RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                    roundEnded(window, e.market(), e.interval());
                    crowds.add(e.market());
                }
                case GameEvent.RoundCancelled e -> {
                    RoundWindow window = new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd());
                    roundEnded(window, e.market(), e.interval());
                }
                case GameEvent.WithdrawalRequested e -> players.add(e.playerId());
                case GameEvent.WithdrawalConfirmed e -> ofWithdrawal(e.requestId());
                case GameEvent.WithdrawalFailed e -> ofWithdrawal(e.requestId());
                case GameEvent.DepositRequested e -> players.add(e.playerId());
                case GameEvent.DepositConfirmed e -> ofDeposit(e.requestId());
                case GameEvent.DepositFailed e -> ofDeposit(e.requestId());
                case GameEvent.WithdrawalReversed e -> players.add(e.playerId());
                case GameEvent.DepositReversed e -> players.add(e.playerId());
                case GameEvent.DepositConfirmedOnChain e -> players.add(e.playerId());
                case GameEvent.WithdrawalConfirmedOnChain e -> ofWithdrawal(e.requestId());
                case GameEvent.EconomyCutover e -> allPlayers = true; // what may be withdrawn changes for everybody
                case GameEvent.BotRetired e -> players.add(e.playerId());
                case GameEvent.HerdSweptToTreasury e -> {
                    // the house accounts only
                }
                case GameEvent.NicknameChanged e -> {
                    // not part of the views
                }
                case GameEvent.BotStrategyAssigned e -> {
                    // not part of the views
                }
                case GameEvent.MarketListed e -> crowds.add(e.marketId());
                case GameEvent.MarketClosed e -> {
                    // no engine effect: the rounds it left open are cancelled by their own events
                }
            }
        }

        private void roundEnded(RoundWindow window, String market, Interval interval) {
            rounds.put(window.roundId(), new RoundRef(market, interval, window.roundId()));
            RoundBets before = old.rounds.get(window.roundId());
            if (before != null) {
                players.addAll(before.byPlayer().keySet()); // paid out or refunded
            }
            closedWindows.add(window);
        }

        private void ofWithdrawal(String requestId) {
            hub.ledger().withdrawal(requestId).ifPresent(r -> players.add(r.playerId()));
        }

        private void ofDeposit(String requestId) {
            hub.ledger().deposit(requestId).ifPresent(r -> players.add(r.playerId()));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Builders
    // ---------------------------------------------------------------------------------------

    private static Map<RoundProgress.SeriesKey, RoundWindow> cursorsOf(RoundProgress progress) {
        Map<RoundProgress.SeriesKey, RoundWindow> cursors = new HashMap<>();
        for (RoundProgress.SeriesKey series : progress.seededSeries()) {
            progress.findNext(series.marketId(), series.interval()).ifPresent(w -> cursors.put(series, w));
        }
        return Map.copyOf(cursors);
    }

    private static CrowdView crowdOf(EngineHub.MarketEngines engines) {
        CrowdAccount crowd = engines.crowd();
        List<CrowdAccount.RoundResult> history = crowd.history();
        return new CrowdView(crowd.capital(), history, List.copyOf(engines.comparison().holdHistory()),
                List.copyOf(engines.comparison().antiCrowdHistory()), statsOf(history));
    }

    /** Rounds traded, rounds won and how the last traded round ended, counted over the whole history. */
    static CrowdStats statsOf(List<CrowdAccount.RoundResult> history) {
        int traded = 0;
        int won = 0;
        Enums.CrowdOutcome last = null;
        for (CrowdAccount.RoundResult round : history) {
            if (!round.traded()) {
                continue;
            }
            traded++;
            int change = round.capitalAfter().compareTo(round.capitalBefore());
            boolean roundWon = !round.liquidated() && change > 0;
            if (roundWon) {
                won++;
            }
            last = round.liquidated() ? Enums.CrowdOutcome.LIQUIDATED
                    : roundWon ? Enums.CrowdOutcome.WIN
                    : change == 0 ? Enums.CrowdOutcome.FLAT : Enums.CrowdOutcome.LOSS;
        }
        return new CrowdStats(traded, won, last);
    }

    private static Map<String, PlayerView> allPlayers(LedgerBook ledger) {
        Map<String, PlayerView> all = new HashMap<>();
        for (String playerId : ledger.playerIds()) {
            PlayerView view = playerOf(ledger, playerId);
            if (view != null) {
                all.put(playerId, view);
            }
        }
        return all;
    }

    private static PlayerView playerOf(LedgerBook ledger, String playerId) {
        try {
            return new PlayerView(ledger.balance(playerId), ledger.withdrawable(playerId));
        } catch (NoSuchElementException e) {
            return null;
        }
    }
}
