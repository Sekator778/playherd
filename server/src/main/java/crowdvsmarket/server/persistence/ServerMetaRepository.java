package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
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

    /** The largest {@code seq} the counter is ever raised to: the {@code seq} travels as a JSON number
     *  to the public journal and its readers (JavaScript, Python), where 2^53 is where integers stop
     *  being exact — a floor beyond it can only be a corrupt answer, not a real chain. */
    public static final long MAX_ORACLE_SEQ = 1L << 53;

    @Inject
    Db db;

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
     *  createBotPlayer} call this once per brand-new player, never for a returning one. */
    public int reserveNextPlayerIndex() {
        long next = db.tx(c -> {
            db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, '0') ON CONFLICT (key) DO NOTHING",
                    NEXT_PLAYER_INDEX_KEY);
            long current = Long.parseLong(db.queryOne(c, "SELECT value FROM server_meta WHERE key = ? FOR UPDATE",
                    rs -> rs.getString("value"), NEXT_PLAYER_INDEX_KEY));
            db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", Long.toString(current + 1),
                    NEXT_PLAYER_INDEX_KEY);
            return current;
        });
        return (int) next;
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
