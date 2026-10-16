package crowdvsmarket.server.bots;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.server.persistence.Db;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the bots did per UTC day, for the operator's status page (math review of the bots, condition 6: watch the bots' treasury flow
 * and their retirements). Read from what the server stores anyway - the bets in {@code game_events}, the settled rounds in
 * {@code player_round_history}, the {@code players} rows - so nothing new is written, nothing is lost by a restart and nothing
 * runs on the betting path; one query per figure, over the last few days only.
 *
 * <p>Per day: {@code bets} placed by bots and the {@code staked} PLAY and the fees on them ({@code fees}: herd plus network, which the
 * daily sweep moves from HERD to TREASURY); {@code pnl} of the bets that <i>settled</i> that day, {@code payout - stake} summed over
 * the bots (before fees; negative = the treasury gained); {@code retirements}; {@code botsCreated} - new bots, the first ones and
 * the replacements of retired ones together, since a replacement is not marked as such in the table - and {@code starterPacks}, the
 * PLAY the treasury paid them: a new bot starts from zero and is topped up to the pack ({@link LedgerBook#STARTER_PACK}). The top-ups of
 * a new season for bots that are already playing are not in it.
 */
@ApplicationScoped
public class BotMetrics {

    private static final long DAY_MS = 86_400_000L;

    /** One UTC day; money as PLAY strings with three decimals. */
    public record Day(String day, long bets, String staked, String fees, String pnl, int retirements, int botsCreated,
                      String starterPacks) {
    }

    @Inject
    Db db;

    /** The last {@code days} UTC days up to and including the day of {@code nowMs}, oldest first (days without bots' activity are zeros). */
    public List<Day> lastDays(long nowMs, int days) {
        long today = Math.floorDiv(nowMs, DAY_MS);
        long from = (today - days + 1) * DAY_MS;
        Map<Long, long[]> bets = new HashMap<>(); // bets, staked, fees (mojos)
        Map<Long, Long> pnl = new HashMap<>();
        Map<Long, Integer> retired = new HashMap<>();
        Map<Long, Integer> created = new HashMap<>();
        db.read(c -> {
            db.queryList(c, """
                    SELECT e.at_ms / 86400000 AS day, COUNT(*) AS bets,
                           COALESCE(SUM((e.payload->>'stakeMojos')::bigint), 0) AS staked,
                           COALESCE(SUM(COALESCE((e.payload->>'feeHerdMojos')::bigint, 0)
                                      + COALESCE((e.payload->>'feeNetMojos')::bigint, 0)), 0) AS fees
                    FROM game_events e JOIN players p ON p.player_id = e.payload->>'playerId' AND p.is_bot
                    WHERE e.event_type = 'BetPlaced' AND e.at_ms >= ? GROUP BY 1
                    """,
                    rs -> bets.put(rs.getLong("day"), new long[] {rs.getLong("bets"), rs.getLong("staked"), rs.getLong("fees")}),
                    from);
            db.queryList(c, """
                    SELECT h.settled_at_ms / 86400000 AS day, COALESCE(SUM(h.payout_mojos - h.stake_mojos), 0) AS pnl
                    FROM player_round_history h JOIN players p ON p.player_id = h.player_id AND p.is_bot
                    WHERE h.settled_at_ms >= ? GROUP BY 1
                    """,
                    rs -> pnl.put(rs.getLong("day"), rs.getLong("pnl")), from);
            db.queryList(c, "SELECT retired_at_ms / 86400000 AS day, COUNT(*) AS n FROM players "
                            + "WHERE is_bot AND retired_at_ms >= ? GROUP BY 1",
                    rs -> retired.put(rs.getLong("day"), rs.getInt("n")), from);
            db.queryList(c, "SELECT created_at_ms / 86400000 AS day, COUNT(*) AS n FROM players "
                            + "WHERE is_bot AND created_at_ms >= ? GROUP BY 1",
                    rs -> created.put(rs.getLong("day"), rs.getInt("n")), from);
            return null;
        });
        List<Day> out = new ArrayList<>();
        for (long day = today - days + 1; day <= today; day++) {
            long[] b = bets.getOrDefault(day, new long[3]);
            int newBots = created.getOrDefault(day, 0);
            out.add(new Day(LocalDate.ofInstant(Instant.ofEpochMilli(day * DAY_MS), ZoneOffset.UTC).toString(), b[0],
                    play(b[1]), play(b[2]), play(pnl.getOrDefault(day, 0L)), retired.getOrDefault(day, 0), newBots,
                    play(LedgerBook.STARTER_PACK.mojos() * newBots)));
        }
        return out;
    }

    private static String play(long mojos) {
        return new PlayAmount(mojos).toMoney().toPlainString();
    }
}
