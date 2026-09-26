package crowdvsmarket.server.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Small key-value store for facts that must survive a restart unchanged — chiefly the server's
 * very first boot time ({@code origin_ms}), which every round-window boundary and the replay price
 * clock are both aligned to ("boundaries aligned to the server start epoch" — restated on every
 * later boot from this table, not recomputed, or a restart would shift every round boundary and
 * desync in-flight bets/rounds from their original windows).
 */
@ApplicationScoped
public class ServerMetaRepository {

    private static final String ORIGIN_MS_KEY = "origin_ms";
    private static final String ORACLE_SEQ_KEY = "oracle_seq";
    private static final String NEXT_PLAYER_INDEX_KEY = "next_player_index";
    private static final String DEPOSIT_POLL_SINCE_HEIGHT_KEY = "deposit_poll_since_height";

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
        return db.tx(c -> {
            db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, '0') ON CONFLICT (key) DO NOTHING",
                    ORACLE_SEQ_KEY);
            long current = Long.parseLong(db.queryOne(c, "SELECT value FROM server_meta WHERE key = ? FOR UPDATE",
                    rs -> rs.getString("value"), ORACLE_SEQ_KEY));
            long first = current + 1;
            db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", Long.toString(current + n), ORACLE_SEQ_KEY);
            return first;
        });
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
