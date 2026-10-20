package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Small key-value store for facts that must survive a restart unchanged — chiefly the server's
 * very first boot time ({@code origin_ms}), which every round-window boundary and the replay price
 * clock are both aligned to ("boundaries aligned to the server start epoch" — restated on every
 * later boot from this table, not recomputed, or a restart would shift every round boundary and
 * desync in-flight bets/rounds from their original windows).
 */
@ApplicationScoped
public class ServerMetaRepository {

    private static final Logger LOG = Logger.getLogger(ServerMetaRepository.class);

    private static final String ORIGIN_MS_KEY = "origin_ms";
    private static final String ORACLE_SEQ_KEY = "oracle_seq";
    private static final String NEXT_PLAYER_INDEX_KEY = "next_player_index";
    private static final String DEPOSIT_POLL_SINCE_HEIGHT_KEY = "deposit_poll_since_height";
    private static final String WITHDRAWAL_NAMESPACE_KEY = "withdrawal_epoch_tag";

    /** The value of {@code withdrawal_epoch_tag} for a database whose withdrawals keep the engine's own ids
     *  ({@link #withdrawalNamespace}). */
    public static final String PLAIN_WITHDRAWAL_IDS = "plain";

    /** E2: the first player index of a new database is drawn from {@code [FIRST_INDEX_MIN, FIRST_INDEX_MAX)}. */
    public static final int FIRST_INDEX_MIN = 1 << 20;
    public static final int FIRST_INDEX_MAX = 1 << 30;

    /** The largest {@code seq} the counter is ever raised to: the {@code seq} travels as a JSON number
     *  to the public journal and its readers (JavaScript, Python), where 2^53 is where integers stop
     *  being exact — a floor beyond it can only be a corrupt answer, not a real chain. */
    public static final long MAX_ORACLE_SEQ = 1L << 53;

    @Inject
    Db db;

    /** Pins the first player index of a new database ({@code herd.players.first-index}); unset, it is random
     *  ({@link #randomFirstPlayerIndex}). For tests, which need the indexes to be small and predictable. */
    @ConfigProperty(name = "herd.players.first-index")
    OptionalInt pinnedFirstPlayerIndex = OptionalInt.empty();

    private final SecureRandom random = new SecureRandom();

    public Optional<String> get(String key) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = ?",
                rs -> rs.getString("value"), key)));
    }

    public void setIfAbsent(String key, String value) {
        db.tx(c -> db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                key, value));
    }

    /** The server's first-ever-boot epoch ms — inserted once, on this process's first startup
     *  against an empty {@code server_meta}, and read back unchanged on every later boot. */
    public long originMsOrInit(long nowIfAbsent) {
        setIfAbsent(ORIGIN_MS_KEY, Long.toString(nowIfAbsent));
        return Long.parseLong(get(ORIGIN_MS_KEY).orElseThrow());
    }

    /** Reserves the next {@code n} oracle sequence numbers atomically and returns the first one —
     *  {@code seq} in FIXED INTERFACE (3)'s {@code /v1/oracle/records}, monotonic across restarts. */
    public long reserveOracleSeqRange(int n) {
        return db.tx(c -> reserveOracleSeqRange(c, n));
    }

    /** {@link #reserveOracleSeqRange(int)} inside the caller's transaction: round settlement
     *  (docs/research/markets_architecture.md §4 п.3) reserves the seq in the very transaction that
     *  appends the {@code RoundResolved} event and inserts the oracle record, so a rolled-back
     *  settlement leaves no hole in the sequence. The row stays locked until that transaction
     *  ends. */
    public long reserveOracleSeqRange(Connection c, int n) throws SQLException {
        long current = lockOracleSeq(c);
        long first = current + 1;
        db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", Long.toString(current + n), ORACLE_SEQ_KEY);
        return first;
    }

    /** The highest floor {@link #raiseOracleSeqTo} has put into the counter in this process (0 before): the engine, which assigns the
     *  oracle sequence numbers (architecture plan F4), starts every number above it without asking the database. */
    private volatile long raisedOracleFloor;

    public long raisedOracleFloor() {
        return raisedOracleFloor;
    }

    /** The oracle counter as the database holds it: the last {@code seq} issued or raised to, 0 for a database that never issued one. */
    public long oracleSeq() {
        return get(ORACLE_SEQ_KEY).map(Long::parseLong).orElse(0L);
    }

    /** F4: the projection of a settlement keeps the counter at or above the {@code seq} its event carries (idempotent, never lowers). */
    public void projectOracleSeq(Connection c, long seq) throws SQLException {
        lockOracleSeq(c);
        db.update(c, "UPDATE server_meta SET value = ? WHERE key = ? AND value::BIGINT < ?", Long.toString(seq),
                ORACLE_SEQ_KEY, seq);
    }

    /** {@link #projectOracleSeq(Connection, long)} in a transaction of its own: a rebuild keeps the counter at or above the journal. */
    public void projectOracleSeq(long seq) {
        db.tx(c -> {
            projectOracleSeq(c, seq);
            return null;
        });
    }

    /** F4: after players were projected from the journal, the next player index is above every index in use (idempotent, never lowers). */
    public void raiseNextPlayerIndexAbove(int highestIndexInUse) {
        db.tx(c -> {
            db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, '0') ON CONFLICT (key) DO NOTHING",
                    NEXT_PLAYER_INDEX_KEY);
            return db.update(c, "UPDATE server_meta SET value = ? WHERE key = ? AND value::BIGINT <= ?",
                    Long.toString(highestIndexInUse + 1L), NEXT_PLAYER_INDEX_KEY, (long) highestIndexInUse);
        });
    }

    /** The oracle {@code seq} counter — the last {@code seq} handed out, 0 on a database that never
     *  issued one — read under a row lock that the caller's transaction keeps until it ends. */
    private long lockOracleSeq(Connection c) throws SQLException {
        db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, '0') ON CONFLICT (key) DO NOTHING",
                ORACLE_SEQ_KEY);
        return Long.parseLong(db.queryOne(c, "SELECT value FROM server_meta WHERE key = ? FOR UPDATE",
                rs -> rs.getString("value"), ORACLE_SEQ_KEY));
    }

    /**
     * The oracle seq floor (docs/research/markets_architecture.md §7 п.4): raises the counter so that
     * the next {@code seq} issued is above {@code floor}, and never lowers it — a floor at or below
     * the counter changes nothing. One oracle singleton lives on chain for good while a server
     * database can be new; a fresh database whose counter starts at 1 although the chain already
     * holds {@code seq} 1..1145 would publish records the chain service rejects as replays (or, worse,
     * ignores as already known). {@code OracleSeqGate} calls this with the chain service's
     * {@code maxKnownSeq}.
     *
     * <p>Own transaction, same row lock as {@link #reserveOracleSeqRange}: it waits for a settlement
     * transaction that is reserving a {@code seq} right now, so a raise never lands between that
     * transaction's read and write of the counter.
     *
     * @return the counter after the call: the last {@code seq} issued, so the next one is that + 1
     * @throws IllegalArgumentException a floor outside {@code 0..}{@link #MAX_ORACLE_SEQ}
     */
    public long raiseOracleSeqTo(long floor) {
        if (floor < 0 || floor > MAX_ORACLE_SEQ) {
            throw new IllegalArgumentException("oracle seq floor " + floor + " is outside 0.." + MAX_ORACLE_SEQ);
        }
        long[] before = new long[1];
        long after = db.tx(c -> {
            long current = lockOracleSeq(c);
            before[0] = current;
            if (floor <= current) {
                return current;
            }
            db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", Long.toString(floor), ORACLE_SEQ_KEY);
            return floor;
        });
        if (after > raisedOracleFloor) {
            raisedOracleFloor = after;
        }
        if (after > before[0]) {
            // Rare and worth knowing: a new database against an old chain, a chain bootstrap, another writer.
            LOG.infof("oracle seq counter raised from %d to %d — the next seq issued is %d", before[0], after,
                    after + 1);
        }
        return after;
    }

    /** Р-53: the next stable {@code playerIndex} a brand-new player row draws (chainsvc CAT
     *  deposit-address derivation, see {@code V4__player_index.sql}) — same atomic
     *  reserve-and-advance shape as {@link #reserveOracleSeqRange}, seeded by that migration to
     *  continue right after every already-backfilled index. {@code registerOrTouchPlayer}/{@code
     *  createBotPlayer} call this once per brand-new player, never for a returning one.
     *
     *  <p><b>E2 — a new database does not start at 0.</b> The chain service keeps its deposit registry for
     *  good while a database can be new, and an address derived from the same index in two databases is one
     *  address for two players. A counter that is still at 0 with no player row ever written (a database that
     *  never handed out an index — the migration's seed on an empty one) is therefore set to {@link
     *  #firstPlayerIndexOfANewDatabase()} first: random in {@code [2^20, 2^30)}, or {@code
     *  herd.players.first-index} when that is set. A counter that has moved, or a database with players, is
     *  never touched. */
    public int reserveNextPlayerIndex() {
        long next = db.tx(c -> {
            db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, '0') ON CONFLICT (key) DO NOTHING",
                    NEXT_PLAYER_INDEX_KEY);
            long current = Long.parseLong(db.queryOne(c, "SELECT value FROM server_meta WHERE key = ? FOR UPDATE",
                    rs -> rs.getString("value"), NEXT_PLAYER_INDEX_KEY));
            if (current == 0 && db.queryOne(c, "SELECT 1 FROM players LIMIT 1", rs -> Boolean.TRUE) == null) {
                current = firstPlayerIndexOfANewDatabase();
                LOG.infof("a database without players: the first player index is %d", current);
            }
            db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", Long.toString(current + 1),
                    NEXT_PLAYER_INDEX_KEY);
            return current;
        });
        return (int) next;
    }

    /** The pinned first index, or a random one ({@link #randomFirstPlayerIndex}). */
    int firstPlayerIndexOfANewDatabase() {
        return pinnedFirstPlayerIndex.isPresent() ? pinnedFirstPlayerIndex.getAsInt()
                : randomFirstPlayerIndex(random);
    }

    /** A random first player index: uniform in {@code [2^20, 2^30)} — about a billion values, so two databases
     *  that share one chain service start at the same index with a probability of about 10^-9 (their indexes
     *  meet only if the two starts lie within the number of players of each other), and the top of the {@code
     *  int} range stays far away. */
    public static int randomFirstPlayerIndex(SecureRandom random) {
        return FIRST_INDEX_MIN + random.nextInt(FIRST_INDEX_MAX - FIRST_INDEX_MIN);
    }

    /**
     * E1 — the namespace of this database's withdrawal request ids at the chain service. The chain service keeps
     * the state of every request id it ever saw, a database can be new, and the engine numbers its withdrawals
     * from {@code w-1}: the 11th withdrawal of a new database would be asked about as {@code w-11}, which the
     * chain service answers with the OLD {@code w-11}'s status — "confirmed", for a payout that never happened.
     * So every id sent to it is {@code "<engine id>-<tag>"}, the tag being 8 random lowercase hex digits drawn once
     * per database and kept here ({@code server_meta.withdrawal_epoch_tag}).
     *
     * <p>A database that already has withdrawal rows when this is first asked keeps the plain ids ({@link
     * #PLAIN_WITHDRAWAL_IDS}, stored as the decision): its requests in flight were submitted under their plain
     * ids, and the poller must go on asking about them under those. The decision is one statement — the check for
     * rows and the insert of the answer cannot be split by a concurrent first withdrawal — and is made once: what is
     * stored stays.
     *
     * @param newTag the tag to store when there is no withdrawal row yet; asked for whenever no namespace is stored
     * @return the tag, or {@link #PLAIN_WITHDRAWAL_IDS}
     */
    public String withdrawalNamespace(Supplier<String> newTag) {
        Optional<String> known = get(WITHDRAWAL_NAMESPACE_KEY);
        if (known.isPresent()) {
            return known.get();
        }
        String candidate = newTag.get();
        db.tx(c -> db.update(c, "INSERT INTO server_meta (key, value) SELECT ?, CASE WHEN EXISTS "
                        + "(SELECT 1 FROM withdrawal_requests) THEN ? ELSE ? END ON CONFLICT (key) DO NOTHING",
                WITHDRAWAL_NAMESPACE_KEY, PLAIN_WITHDRAWAL_IDS, candidate));
        String decided = get(WITHDRAWAL_NAMESPACE_KEY).orElseThrow();
        if (PLAIN_WITHDRAWAL_IDS.equals(decided)) {
            LOG.infof("this database already had withdrawals: their request ids go to the chain service as they are");
        } else {
            LOG.infof("the withdrawal request ids of this database go to the chain service as \"<id>-%s\"", decided);
        }
        return decided;
    }

    /** Р-53: the deposit poller's {@code sinceHeight} cursor (FIXED INTERFACE:
     *  {@code GET /v1/play/deposits?sinceHeight=N}) — 0 on a server that has never polled, exactly
     *  {@link #originMsOrInit}'s "insert once, read back unchanged" shape. {@code
     *  DepositPollerService} advances it to chainsvc's own reported {@code tipHeight} after a fully
     *  successful poll tick (see that class's javadoc for why "fully successful" matters here). */
    public long depositPollSinceHeightOrInit() {
        setIfAbsent(DEPOSIT_POLL_SINCE_HEIGHT_KEY, "0");
        return Long.parseLong(get(DEPOSIT_POLL_SINCE_HEIGHT_KEY).orElseThrow());
    }

    public void advanceDepositPollSinceHeight(long newSinceHeight) {
        db.tx(c -> db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?",
                Long.toString(newSinceHeight), DEPOSIT_POLL_SINCE_HEIGHT_KEY));
    }
}
