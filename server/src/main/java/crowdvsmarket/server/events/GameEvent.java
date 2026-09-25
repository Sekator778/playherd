package crowdvsmarket.server.events;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import java.util.List;

/**
 * Everything persisted to {@code game_events} (append-only) in order to deterministically rebuild
 * the in-memory {@code GameEngine}/{@code LedgerBook}/{@code CrowdAccount} state on startup — see
 * docs/research/server_design.md "Persistence". Each variant carries exactly what its matching
 * {@code EventApplier} method needs to replay the original engine call byte-for-byte, including
 * the {@code atMs} timestamp the live call used (never re-derived from wall-clock at replay time).
 *
 * <p>Read-model tables ({@code round_results}, {@code players}, …) are NOT rebuilt from this log
 * — they are written directly, once, when first produced, and read as-is; only the in-memory
 * engine needs replaying.
 */
public sealed interface GameEvent {

    /** Grants a first-activity-in-season starter pack immediately at sign-in (Р-2), rather than
     *  waiting for the player's first bet — {@code LedgerBook.ensureSeasonPack} is idempotent
     *  per season, so this is safe to also happen implicitly again later via a bet/withdrawal. */
    record PlayerRegistered(String playerId, long atMs) implements GameEvent {
    }

    record BetPlaced(String playerId, Interval interval, long windowStart, long windowEnd, Direction direction,
                       int leverage, long stakeMojos, long atMs) implements GameEvent {
    }

    record BetCancelled(String playerId, Interval interval, long windowStart, long windowEnd, long atMs)
            implements GameEvent {
    }

    /** One OHLCV candle, decimal fields as exact strings (no float round-trip) — mirrors
     *  {@code crowdvsmarket.engine.market.Candle}'s constructor arguments. */
    record CandleData(long openTimeMs, long closeTimeMs, String open, String high, String low, String close,
                        String volume, long trades) {
    }

    /** The exact price path {@code GameEngine.runRound} was called with, captured so replay is
     *  independent of the (possibly wall-clock-driven, e.g. live-mode) price source that produced
     *  it originally. */
    record RoundResolved(Interval interval, long windowStart, long windowEnd, String openPrice, String closePrice,
                           List<CandleData> path, long atMs) implements GameEvent {
    }

    /** Operator/automatic cancellation (Р-23, Р-32): refunds every bet still pending for the
     *  round. */
    record RoundCancelled(Interval interval, long windowStart, long windowEnd, String reason, long atMs)
            implements GameEvent {
    }

    /** No {@code requestId} field on purpose: {@code LedgerBook.requestWithdrawal} assigns its own
     *  ({@code "w-N"}, a counter shared with deposits' {@code "d-N"}) — replaying this same event
     *  in the same position in the log reproduces the identical id deterministically, so the id is
     *  an output of applying the event, not an input to it (see {@code GameService.requestWithdrawal}). */
    record WithdrawalRequested(String playerId, long amountMojos, long atMs) implements GameEvent {
    }

    record WithdrawalConfirmed(String requestId, long atMs) implements GameEvent {
    }

    record WithdrawalFailed(String requestId, long atMs) implements GameEvent {
    }

    /** Same id-reuse note as {@link WithdrawalRequested}. */
    record DepositRequested(String playerId, long amountMojos, long atMs) implements GameEvent {
    }

    record DepositConfirmed(String requestId, long atMs) implements GameEvent {
    }

    record DepositFailed(String requestId, long atMs) implements GameEvent {
    }
}
