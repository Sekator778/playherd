package crowdvsmarket.server.testsupport;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.persistence.Db;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Test-only canonical digest of the game state (architecture plan F4; the production {@code StateDigest} of F5 follows the same
 * idea): a text of sorted, one-fact-per-line sections, hashed with SHA-256. Two states with the same digest are the same state as
 * far as these sections go, so "live == replayed" is one {@code assertThat(...).isEqualTo(...)} and a mismatch can be diffed from
 * the canonical text.
 *
 * <ul>
 *   <li>{@link #engine}: the part that lives in the {@link EngineHub} and uses nothing else - the ledger (every account's balance
 *       and the house accounts and counters), per market and round length the open bets, the crowd account and the comparison
 *       lines, and the newest closed round. Written against the hub's API as it was before F4 on purpose, so the same method
 *       digests a hub of the old code.
 *   <li>{@link #projections}: the tables that are projections of the journal since F4 - {@code players} (without the API key and
 *       the referral columns, which are outside the journal by design) and {@code markets} - and the two counters of
 *       {@code server_meta} that follow the journal.
 * </ul>
 */
public final class StateDigest {

    private StateDigest() {
    }

    /** The canonical text of the engine part. */
    public static String engine(EngineHub hub) {
        StringBuilder out = new StringBuilder();
        LedgerBook ledger = hub.ledger();
        List<String> players = new ArrayList<>(ledger.playerIds());
        players.sort(Comparator.naturalOrder());
        for (String id : players) {
            out.append("account ").append(id).append(" balance=").append(ledger.balance(id).mojos())
                    .append(" pack=").append(ledger.packCredited(id).mojos())
                    .append(" fees=").append(ledger.feesPaid(id).mojos())
                    .append(" withdrawable=").append(ledger.withdrawable(id).mojos())
                    .append(" retired=").append(ledger.isRetired(id)).append('\n');
        }
        out.append("ledger economyActive=").append(ledger.economyActive())
                .append(" treasury=").append(ledger.treasury().mojos())
                .append(" herd=").append(ledger.herd().mojos())
                .append(" openStakes=").append(ledger.openStakes().mojos())
                .append(" playersTotal=").append(ledger.playersTotal().mojos())
                .append(" openingTotal=").append(ledger.openingTotal().mojos())
                .append(" deposited=").append(ledger.depositedTotal().mojos())
                .append(" withdrawnConfirmed=").append(ledger.withdrawnConfirmed().mojos())
                .append(" withdrawalsPending=").append(ledger.withdrawalsPending().mojos())
                .append(" botFeesSwept=").append(ledger.botFeesSwept().mojos())
                .append(" lastSweepAtMs=").append(ledger.lastSweepAtMs())
                .append(" cutoverAtMs=").append(ledger.cutoverAtMs())
                .append(" lastSequence=").append(ledger.lastSequence()).append('\n');
        List<String> markets = new ArrayList<>(hub.marketIds());
        markets.sort(Comparator.naturalOrder());
        for (String market : markets) {
            out.append("crowd ").append(market).append(" capital=").append(hub.crowdAccount(market).capital().toPlainString())
                    .append(" rounds=").append(hub.crowdAccount(market).history().size()).append('\n');
            hub.crowdAccount(market).history().forEach(r -> out.append("  crowdRound ").append(r).append('\n'));
            out.append("comparison ").append(market).append(" hold=").append(hub.comparison(market).holdHistory())
                    .append(" anti=").append(hub.comparison(market).antiCrowdHistory()).append('\n');
            for (Interval interval : Interval.values()) {
                out.append("series ").append(market).append('/').append(interval).append(" latestClosedEnd=")
                        .append(hub.closedRounds().latestEnd(market, interval).orElse(-1L)).append('\n');
                for (RoundWindow window : hub.betWindows().of(market, interval)) {
                    List<Bet> bets = new ArrayList<>(hub.engine(market, interval).betBook().betsFor(window.roundId()));
                    bets.sort(Comparator.comparing(Bet::playerId));
                    for (Bet bet : bets) {
                        out.append("  bet ").append(window.roundId()).append(' ').append(bet).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    /** The canonical text of the projected tables and the counters that follow the journal. */
    public static String projections(Db db, EngineHub hub) {
        StringBuilder out = new StringBuilder();
        out.append(db.read(c -> db.queryList(c,
                "SELECT player_id, wallet_address, COALESCE(nickname, '~') AS nickname, is_bot, COALESCE(bot_strategy, '~') AS strategy, "
                        + "player_index, created_at_ms, COALESCE(retired_at_ms, -1) AS retired, replaced, kind, "
                        + "COALESCE(owner_player_id, '~') AS owner FROM players ORDER BY player_id",
                rs -> "player " + rs.getString("player_id") + " " + rs.getString("wallet_address") + " nick=" + rs.getString("nickname")
                        + " bot=" + rs.getBoolean("is_bot") + " strategy=" + rs.getString("strategy") + " index=" + rs.getInt("player_index")
                        + " created=" + rs.getLong("created_at_ms") + " retired=" + rs.getLong("retired") + " replaced="
                        + rs.getBoolean("replaced") + " kind=" + rs.getString("kind") + " owner=" + rs.getString("owner") + "\n"))
                .stream().reduce("", String::concat));
        out.append(db.read(c -> db.queryList(c,
                "SELECT id, kind, category, symbol, title, COALESCE(subtitle, '~') AS subtitle, COALESCE(icon_url, '~') AS icon, "
                        + "source, source_ref, COALESCE(source_meta::text, '~') AS meta, max_leverage, lot_exp, oracle_index, status, "
                        + "listed_at_ms, COALESCE(closed_at_ms, -1) AS closed, COALESCE(close_reason, '~') AS reason, "
                        + "COALESCE(ends_at_ms, -1) AS ends, cap_hourly, cap_daily, kappa::text AS kappa, herd_fee_bp::text AS fee, "
                        + "COALESCE(stress_move::text, '~') AS stress FROM markets ORDER BY id",
                rs -> "market " + rs.getString("id") + " " + rs.getString("kind") + " " + rs.getString("category") + " "
                        + rs.getString("symbol") + " title=" + rs.getString("title") + " sub=" + rs.getString("subtitle")
                        + " icon=" + rs.getString("icon") + " src=" + rs.getString("source") + "/" + rs.getString("source_ref")
                        + " meta=" + rs.getString("meta") + " lev=" + rs.getInt("max_leverage") + " lot=" + rs.getInt("lot_exp")
                        + " oracleIndex=" + rs.getInt("oracle_index") + " " + rs.getString("status") + " listed="
                        + rs.getLong("listed_at_ms") + " closed=" + rs.getLong("closed") + " reason=" + rs.getString("reason")
                        + " ends=" + rs.getLong("ends") + " caps=" + rs.getInt("cap_hourly") + "/" + rs.getInt("cap_daily")
                        + " kappa=" + rs.getString("kappa") + " fee=" + rs.getString("fee") + " stress=" + rs.getString("stress") + "\n"))
                .stream().reduce("", String::concat));
        out.append("counter next_player_index=").append(serverMeta(db, "next_player_index")).append('\n');
        out.append("counter oracle_seq=").append(serverMeta(db, "oracle_seq")).append(" hub.oracleSeq=").append(hub.oracleSeq())
                .append('\n');
        return out.toString();
    }

    private static String serverMeta(Db db, String key) {
        String value = db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = ?", rs -> rs.getString("value"), key));
        return value == null ? "-" : value;
    }

    /** Everything: the engine part followed by the projections. */
    public static String canonical(Db db, EngineHub hub) {
        return engine(hub) + projections(db, hub);
    }

    public static String sha256(String canonicalText) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalText.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
