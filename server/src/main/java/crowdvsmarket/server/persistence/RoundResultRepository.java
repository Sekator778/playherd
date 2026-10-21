package crowdvsmarket.server.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.domain.WinStreak;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Read model for settled/cancelled rounds — see {@code V1__init.sql}'s comment on {@code
 *  round_results}/{@code player_round_history} and {@code V7__markets.sql} for the {@code market}
 *  column. Never replayed; written once, in the same transaction as the {@code RoundResolved}/
 *  {@code RoundCancelled} event (docs/research/markets_architecture.md §4 п.3), by {@code
 *  GameService}.
 *
 *  <p>{@code open_price_cents}/{@code close_price_cents} are the market's ENGINE (lot) price times
 *  100 (§6); every DTO built here converts them back to the market's raw price string
 *  ({@link Market#rawString}). */
@ApplicationScoped
public class RoundResultRepository {

    @Inject
    Db db;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    MarketRegistry markets;

    /** Latest {@code window_end_ms} ever resolved or cancelled for the series — the seed for
     *  {@code RoundProgress} at startup (its next-to-resolve window starts exactly there). */
    public Optional<Long> latestWindowEnd(String marketId, Interval interval) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT MAX(window_end_ms) AS m FROM round_results WHERE market = ? AND interval = ?",
                rs -> (Long) rs.getObject("m"), marketId, interval.name())));
    }

    /** One settled round's closing price, keyed by where its window ends — what {@link
     *  #settledCloses} hands to {@code BoundaryPrices}. {@code closeCents} is the engine (lot) price
     *  times 100, like the column it comes from. {@code venue}: the exchange the round ran on (V9), {@code
     *  null} for a round of a market without a backup — the next round opens at the close of a round on the
     *  same venue only (Р-76). */
    public record SettledClose(long windowEndMs, long closeCents, String venue) {

        /** A round that ran on no particular venue. */
        public SettledClose(long windowEndMs, long closeCents) {
            this(windowEndMs, closeCents, null);
        }
    }

    /**
     * The closing prices of the SETTLED rounds of one series whose window starts at or after {@code
     * fromStartMs}, oldest end first (cancelled rounds have no close and are not listed). {@code
     * BoundaryPrices} reads them when a state is built: the open of a round is the close of the round that
     * ended where it starts (Р-40), and that must not depend on what the price pipeline still remembers.
     * Answered from the {@code (market, interval, window_start_ms)} index.
     */
    public List<SettledClose> settledCloses(String marketId, Interval interval, long fromStartMs) {
        return db.read(c -> db.queryList(c, """
                SELECT window_end_ms, close_price_cents, venue FROM round_results
                WHERE market = ? AND interval = ? AND status = 'SETTLED' AND window_start_ms >= ?
                ORDER BY window_end_ms, round_id
                """,
                rs -> new SettledClose(rs.getLong("window_end_ms"), rs.getLong("close_price_cents"),
                        rs.getString("venue")),
                marketId, interval.name(), fromStartMs));
    }

    /** The ids of all rounds that have a row: what a rebuild compares the journal's resolved rounds with
     *  ({@code EngineStateBuilder}) to find those whose rows were never written. */
    public java.util.Set<String> allRoundIds() {
        return new java.util.HashSet<>(db.read(c -> db.queryList(c, "SELECT round_id FROM round_results",
                rs -> rs.getString("round_id"))));
    }

    public boolean exists(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT 1 FROM round_results WHERE round_id = ?",
                rs -> Boolean.TRUE, roundId)) != null;
    }

    public void insertSettled(Connection c, String marketId, String roundId, Interval interval, long windowStart,
                                long windowEnd, long openPriceCents, long closePriceCents,
                                List<Rounds.PublicPositionDto> positions, Rounds.CrowdRoundResultDto crowd,
                                String resultsHash, String canonicalJson, long settledAtMs,
                                List<PlayerHistoryRow> perPlayerHistory, String venue) throws SQLException {
        db.update(c, """
                INSERT INTO round_results (round_id, market, interval, window_start_ms, window_end_ms, status,
                    open_price_cents, close_price_cents, positions_json, crowd_json, results_hash,
                    canonical_json, settled_at_ms, created_at_ms, venue)
                VALUES (?, ?, ?, ?, ?, 'SETTLED', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                roundId, marketId, interval.name(), windowStart, windowEnd, openPriceCents, closePriceCents,
                Db.jsonb(positions), Db.jsonb(crowd), resultsHash, canonicalJson, settledAtMs, settledAtMs, venue);
        for (PlayerHistoryRow row : perPlayerHistory) {
            db.update(c, """
                    INSERT INTO player_round_history (round_id, player_id, market, interval, settled_at_ms,
                        direction, leverage, stake_mojos, liquidated, direction_correct, payout_mojos, fees_mojos, mode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    roundId, row.playerId(), marketId, interval.name(), settledAtMs, row.direction().name(),
                    row.leverage(), row.stakeMojos(), row.liquidated(), row.directionCorrect(), row.payoutMojos(), row.feesMojos(), row.mode());
        }
    }

    public record PlayerHistoryRow(String playerId, Enums.Direction direction, int leverage, long stakeMojos,
                                     boolean liquidated, Boolean directionCorrect, long payoutMojos,
                                     Long feesMojos, String mode) {

        public PlayerHistoryRow(String playerId, Enums.Direction direction, int leverage, long stakeMojos,
                                boolean liquidated, Boolean directionCorrect, long payoutMojos, Long feesMojos) {
            this(playerId, direction, leverage, stakeMojos, liquidated, directionCorrect, payoutMojos, feesMojos, "PERCENTAGE");
        }
    }

    public void insertCancelled(Connection c, String marketId, String roundId, Interval interval, long windowStart,
                                  long windowEnd, String reason, long cancelledAtMs, int refundedBetCount,
                                  String resultsHash, String canonicalJson, String venue) throws SQLException {
        db.update(c, """
                INSERT INTO round_results (round_id, market, interval, window_start_ms, window_end_ms, status,
                    cancelled_at_ms, cancellation_reason, refunded_bet_count, results_hash, canonical_json,
                    created_at_ms, venue)
                VALUES (?, ?, ?, ?, ?, 'CANCELLED', ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (round_id) DO NOTHING
                """,
                roundId, marketId, interval.name(), windowStart, windowEnd, cancelledAtMs, reason, refundedBetCount,
                resultsHash, canonicalJson, cancelledAtMs, venue);
    }

    public Optional<Rounds.RoundDto> findRoundDto(String roundId) {
        Rounds.RoundDto dto = db.read(c -> db.queryOne(c,
                "SELECT * FROM round_results WHERE round_id = ?", this::toRoundDto, roundId));
        return dto == null ? Optional.empty() : Optional.of(attachOracleAnchor(dto));
    }

    /** Task item 1c: the optional {@code oracleAnchor} on a settled/cancelled round's DTO — a
     *  separate lookup against {@code oracle_records} (not a SQL join in {@link #toRoundDto}),
     *  since it is best-effort/eventually-consistent bookkeeping owned by a different repository,
     *  not part of this read model's own row. Returns {@code dto} unchanged (no anchor) if this
     *  round never got an oracle record at all. */
    private Rounds.RoundDto attachOracleAnchor(Rounds.RoundDto dto) {
        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId(dto.roundId()).orElse(null);
        if (anchor == null) {
            return dto;
        }
        return switch (dto) {
            case Rounds.RoundSettled s -> new Rounds.RoundSettled(s.roundId(), s.window(), s.marketId(), s.asset(),
                    s.kind(), s.priceDecimals(), s.source(), s.phase(), s.openPrice(), s.closePrice(), s.positions(),
                    s.crowd(), s.resultsHash(), anchor);
            case Rounds.RoundCancelled c -> new Rounds.RoundCancelled(c.roundId(), c.window(), c.marketId(),
                    c.asset(), c.kind(), c.priceDecimals(), c.source(), c.phase(), c.cancellation(),
                    c.resultsHash(), anchor);
            default -> dto; // toRoundDto never returns RoundBetting/RoundRunning
        };
    }

    /** The previous settled round's crowd-majority direction for the series — used by the
     *  {@code CONTRARIAN}/{@code FOLLOW_MAJORITY} bot strategies (Р-46), per market (Т-8). Empty if
     *  no settled round exists yet, or the most recent one was a tie/no-bets (crowd did not
     *  trade). */
    public Optional<Enums.Direction> lastMajorityDirection(String marketId, Interval interval) {
        Rounds.CrowdRoundResultDto crowd = db.read(c -> db.queryOne(c, """
                SELECT crowd_json FROM round_results WHERE market = ? AND interval = ? AND status = 'SETTLED'
                ORDER BY settled_at_ms DESC LIMIT 1
                """,
                rs -> db.readJson(rs, "crowd_json", Rounds.CrowdRoundResultDto.class), marketId, interval.name()));
        return crowd != null && crowd.traded() ? Optional.of(crowd.direction()) : Optional.empty();
    }

    public Optional<String> canonicalJson(String roundId) {
        return Optional.ofNullable(db.read(c -> db.queryOne(c,
                "SELECT canonical_json FROM round_results WHERE round_id = ?",
                rs -> rs.getString("canonical_json"), roundId)));
    }

    public Paging.Page<Rounds.CancellationDto> listCancellations(Paging.PageParams params) {
        long before = params.before() == null ? Long.MAX_VALUE : params.before();
        int limit = params.limitOrDefault();
        List<Rounds.CancellationDto> items = db.read(c -> db.queryList(c, """
                SELECT round_id, market, cancellation_reason, cancelled_at_ms, refunded_bet_count
                FROM round_results WHERE status = 'CANCELLED' AND cancelled_at_ms < ?
                ORDER BY cancelled_at_ms DESC LIMIT ?
                """,
                rs -> cancellationOf(rs, rs.getString("round_id")),
                before, limit + 1));
        boolean hasMore = items.size() > limit;
        return new Paging.Page<>(hasMore ? items.subList(0, limit) : items, hasMore);
    }

    /** Р-62 "while you were away": this player's own settled positions not yet acknowledged
     *  ({@link #ackResults}) — newest first, same convention as {@link #playerHistory} and as
     *  contract.ts's own doc comment on {@code Api.unseenResults} already fixes it (the client,
     *  {@code fx/useAwayReplay.ts}, reverses the list itself to actually PLAY the result moments
     *  oldest-first, chaining each WIN's balance animation backwards from the player's current real
     *  balance). {@code limit} bounds a first-load backlog after a long absence — the newest ones
     *  are shown/acked first, so a backlog beyond {@code limit} just surfaces (oldest remaining
     *  first, since fewer are left) on the next call, never lost. */
    public List<Identity.ProfileRoundEntryDto> unseenResults(String playerId, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT h.round_id, h.market, h.interval, h.settled_at_ms, h.direction, h.leverage, h.stake_mojos,
                       h.liquidated, h.direction_correct, h.payout_mojos, h.fees_mojos, h.mode,
                       r.window_end_ms - r.window_start_ms AS duration_ms
                FROM player_round_history h LEFT JOIN round_results r ON r.round_id = h.round_id
                WHERE h.player_id = ? AND h.acked_at_ms IS NULL
                ORDER BY h.settled_at_ms DESC LIMIT ?
                """,
                this::toHistoryEntry, playerId, limit));
    }

    /** Marks each of {@code roundIds} acknowledged for {@code playerId} (idempotent: a round already
     *  acked, or one this player never had a settled position in, is silently a no-op — {@code
     *  ackResults} never needs to fail just because the client's local list is stale/out of sync
     *  with the server's). Scoped by {@code player_id} in the WHERE clause so a player can only ever
     *  ack their OWN rows, never another player's, regardless of what round ids are passed in. */
    public void ackResults(String playerId, List<String> roundIds, long nowMs) {
        if (roundIds.isEmpty()) {
            return;
        }
        db.tx(c -> {
            for (String roundId : roundIds) {
                db.update(c, """
                        UPDATE player_round_history SET acked_at_ms = ?
                        WHERE player_id = ? AND round_id = ? AND acked_at_ms IS NULL
                        """,
                        nowMs, playerId, roundId);
            }
            return null;
        });
    }

    public List<Identity.ProfileRoundEntryDto> playerHistory(String playerId, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT h.round_id, h.market, h.interval, h.settled_at_ms, h.direction, h.leverage, h.stake_mojos,
                       h.liquidated, h.direction_correct, h.payout_mojos, h.fees_mojos, h.mode,
                       r.window_end_ms - r.window_start_ms AS duration_ms
                FROM player_round_history h LEFT JOIN round_results r ON r.round_id = h.round_id
                WHERE h.player_id = ? ORDER BY h.settled_at_ms DESC LIMIT ?
                """,
                this::toHistoryEntry, playerId, limit));
    }

    /**
     * The win streak of {@code playerId} over ALL their settled rounds, newest first (Р-71, {@link
     * WinStreak} has the rule): one pass over two columns, the newest round first — the order within one
     * instant (rounds of several markets end together) is fixed by market and round id, so that the answer
     * does not change from call to call. Flat rounds are left out in SQL: they are transparent to a streak.
     * Cancelled rounds have no row here.
     */
    public WinStreak.Result playerStreak(String playerId) {
        WinStreak.Counter counter = new WinStreak.Counter();
        db.read(c -> db.queryList(c, """
                SELECT stake_mojos, payout_mojos FROM player_round_history
                WHERE player_id = ? AND payout_mojos <> stake_mojos
                ORDER BY settled_at_ms DESC, market DESC, round_id DESC
                """,
                rs -> {
                    counter.add(rs.getLong("stake_mojos"), rs.getLong("payout_mojos"));
                    return Boolean.TRUE;
                }, playerId));
        return counter.result();
    }

    /**
     * Every player's settled rounds with {@code fromMs <= settled_at_ms <= toMs}, summed per player (the "This week" boards, {@link
     * crowdvsmarket.server.domain.WeeklyBoards}): how many, the sum of {@code payout - stake - fees} in mojos (a row without recorded
     * fees counts none), and the direction calls - {@code eligible} are the rounds with a defined call (not flat), {@code correct}
     * the right ones. Cancelled rounds have no row here.
     */
    public List<crowdvsmarket.server.domain.WeeklyBoards.Totals> weeklyTotals(long fromMs, long toMs) {
        return db.read(c -> db.queryList(c, """
                SELECT player_id, COUNT(*) AS rounds,
                       SUM(payout_mojos - stake_mojos - COALESCE(fees_mojos, 0)) AS net,
                       COUNT(direction_correct) AS eligible,
                       COALESCE(SUM(CASE WHEN direction_correct THEN 1 ELSE 0 END), 0) AS correct
                FROM player_round_history
                WHERE settled_at_ms >= ? AND settled_at_ms <= ?
                GROUP BY player_id
                """,
                rs -> new crowdvsmarket.server.domain.WeeklyBoards.Totals(rs.getString("player_id"), rs.getInt("rounds"),
                        rs.getLong("net"), rs.getInt("eligible"), rs.getInt("correct")),
                fromMs, toMs));
    }

    /** The UTC day numbers (epoch milliseconds / 86 400 000) on which the player had a round settle, newest first: what "played N days
     *  in a row" ({@link crowdvsmarket.server.domain.PlayDays}) is counted from. */
    public List<Long> playDays(String playerId) {
        return db.read(c -> db.queryList(c, """
                SELECT DISTINCT settled_at_ms / 86400000 AS day FROM player_round_history
                WHERE player_id = ? ORDER BY day DESC
                """,
                rs -> rs.getLong("day"), playerId));
    }

    /**
     * The player's latest settled rounds that were not flat ({@code payout <> stake}), newest first, as {@code {stake, payout}} in
     * mojos: what a bot reads its last result and its streak from. At most {@code limit}.
     */
    public List<long[]> recentOutcomes(String playerId, int limit) {
        return db.read(c -> db.queryList(c, """
                SELECT stake_mojos, payout_mojos FROM player_round_history
                WHERE player_id = ? AND payout_mojos <> stake_mojos
                ORDER BY settled_at_ms DESC, market DESC, round_id DESC LIMIT ?
                """,
                rs -> new long[] {rs.getLong("stake_mojos"), rs.getLong("payout_mojos")}, playerId, limit));
    }

    private Identity.ProfileRoundEntryDto toHistoryEntry(java.sql.ResultSet rs) throws SQLException {
        String marketId = rs.getString("market");
        long durationMs = rs.getLong("duration_ms");
        // LEFT JOIN: a history row always has its round's result row (both are written in one
        // transaction), so null cannot happen — but "unknown" is better told than guessed.
        Long durationSec = rs.wasNull() ? null : durationMs / 1000L;
        Market market = market(marketId);
        Long feesMojos = (Long) rs.getObject("fees_mojos");
        return new Identity.ProfileRoundEntryDto(rs.getString("round_id"),
                Enums.Interval.valueOf(rs.getString("interval")), marketId, market.symbol(), durationSec,
                kindOf(market), rs.getLong("settled_at_ms"), Enums.Direction.valueOf(rs.getString("direction")),
                rs.getInt("leverage"), java.math.BigDecimal.valueOf(rs.getLong("stake_mojos"), 3).toPlainString(),
                rs.getBoolean("liquidated"), (Boolean) rs.getObject("direction_correct"),
                java.math.BigDecimal.valueOf(rs.getLong("payout_mojos"), 3).toPlainString(),
                feesMojos == null ? null : java.math.BigDecimal.valueOf(feesMojos, 3).toPlainString(),
                "FIXED".equals(rs.getString("mode")) ? Enums.BetMode.FIXED : null);
    }

    /** Markets are never deleted, so a row's market is always in the registry; its absence means
     *  the registry and the read model disagree, which must not be papered over. */
    private Market market(String marketId) {
        return markets.byId(marketId).orElseThrow(() -> new IllegalStateException(
                "round_results/player_round_history names market " + marketId + " that the registry does not know"));
    }

    private Rounds.RoundDto toRoundDto(java.sql.ResultSet rs) throws SQLException {
        String roundId = rs.getString("round_id");
        Market market = market(rs.getString("market"));
        Enums.Interval interval = Enums.Interval.valueOf(rs.getString("interval"));
        Rounds.RoundWindowDto window = new Rounds.RoundWindowDto(interval, rs.getLong("window_start_ms"),
                rs.getLong("window_end_ms"));
        String status = rs.getString("status");
        // a round of a market with a backup exchange says which exchange it ran on; any other, the market's own source
        String venue = rs.getString("venue");
        String source = venue != null ? venue : market.source();
        if ("CANCELLED".equals(status)) {
            return new Rounds.RoundCancelled(roundId, window, market.id(), market.symbol(), kindOf(market),
                    market.priceDecimals(), source, cancellationOf(rs, roundId), rs.getString("results_hash"));
        }
        List<Rounds.PublicPositionDto> positions = db.readJson(rs, "positions_json",
                new TypeReference<List<Rounds.PublicPositionDto>>() {
                });
        Rounds.CrowdRoundResultDto crowd = db.readJson(rs, "crowd_json", Rounds.CrowdRoundResultDto.class);
        String openPrice = market.rawString(java.math.BigDecimal.valueOf(rs.getLong("open_price_cents"), 2));
        String closePrice = market.rawString(java.math.BigDecimal.valueOf(rs.getLong("close_price_cents"), 2));
        return new Rounds.RoundSettled(roundId, window, market.id(), market.symbol(), kindOf(market),
                market.priceDecimals(), source, openPrice, closePrice, positions, crowd,
                rs.getString("results_hash"));
    }

    /** The cancellation of the row {@code rs} is on, named by its market (the log of cancellations lists the pair,
     *  not only the round id). */
    private Rounds.CancellationDto cancellationOf(java.sql.ResultSet rs, String roundId) throws SQLException {
        Market market = market(rs.getString("market"));
        return new Rounds.CancellationDto(roundId, market.id(), market.symbol(), rs.getString("cancellation_reason"),
                rs.getLong("cancelled_at_ms"), rs.getInt("refunded_bet_count"));
    }

    private static Enums.MarketKind kindOf(Market market) {
        return Enums.MarketKind.valueOf(market.kind().name());
    }
}
