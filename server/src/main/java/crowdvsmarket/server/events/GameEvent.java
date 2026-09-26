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
     *  per season, so this is safe to also happen implicitly again later via a bet/withdrawal, and
     *  tolerates being applied twice for the same season without granting a second starter pack
     *  (relied on by replay of any already-persisted duplicate row, see
     *  {@code GameService#registerOrTouchPlayer}'s task item 1 fix javadoc). Since that fix,
     *  {@code GameService} only actually appends one of these for a brand-new player or a genuine
     *  season rollover — not on every plain re-login — but a historical duplicate from before the
     *  fix must still replay safely, which is exactly what this idempotency already gives for free. */
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

    /** Wave-2 security fix (Р-32/Р-43 pattern): an operator correction that reverses the balance/
     *  season effect a withdrawal request originally applied (e.g. the {@code
     *  requestWithdrawal("-5000.000")} exploit) — {@code GameService#reverseWithdrawal}. Fully
     *  self-contained on purpose: {@code originalAmountMojos}/{@code originalSeasonId} are captured
     *  once, at command time, from the request's own already-recorded values, rather than being
     *  re-derived from {@code relatedWithdrawalRequestId} on replay (see
     *  {@code crowdvsmarket.engine.ledger.LedgerBook#reverseWithdrawalEffect}'s javadoc).
     *  {@code relatedWithdrawalRequestId} is carried only for the public audit trail. */
    record WithdrawalReversed(String playerId, long originalAmountMojos, String originalSeasonId, String reason,
                                String relatedWithdrawalRequestId, long atMs) implements GameEvent {
    }

    /** Task item 1 (wave-3 security fix, Р-6 deposit-verification gap): an operator correction
     *  that reverses the balance/season effect a deposit request originally applied — e.g. the
     *  fake self-reported deposits {@code d-2}/{@code d-4}/{@code d-7}/{@code d-10} accepted before
     *  {@code GameService#requestDeposit} started rejecting new deposits with
     *  {@code DEPOSIT_VERIFICATION_UNAVAILABLE} (no real on-chain CAT-deposit verifier exists yet,
     *  Р-6 decision pending with Alex). Exactly {@link WithdrawalReversed}'s shape/reasoning,
     *  mirrored for deposits — see {@code crowdvsmarket.engine.ledger.LedgerBook
     *  #reverseDepositEffect}'s javadoc. */
    record DepositReversed(String playerId, long originalAmountMojos, String originalSeasonId, String reason,
                             String relatedDepositRequestId, long atMs) implements GameEvent {
    }

    /** Р-53: a real, chain-confirmed PLAY CAT deposit detected by {@code DepositPollerService}
     *  ({@code GET /v1/play/deposits}) — unlike {@link DepositRequested}/{@link DepositConfirmed}
     *  (the self-report two-step, permanently retired at the RPC level but kept here only so
     *  already-persisted historical rows keep replaying), there is no separate request: the coin's
     *  existence on chain, at the player's known deposit address, IS the request and the
     *  confirmation in one, so this single event carries both. {@code coinId} is this deposit's own
     *  natural idempotency key — replay safety is enforced one layer up, by {@code
     *  DepositPollerService} only ever emitting this event the FIRST time it successfully reserves
     *  a given {@code coinId} in {@code deposit_confirmations} (a plain read-model table, not
     *  itself replayed); once emitted, ordinary event-log replay re-applies it exactly once, like
     *  every other event here. {@code playerIndex} is carried for audit/debugging only — {@code
     *  EventApplier}'s case resolves the credit by {@code playerId}, already resolved once at
     *  poll time. */
    record DepositConfirmedOnChain(String playerId, int playerIndex, long amountMojos, String coinId, long height,
                                     long atMs) implements GameEvent {
    }

    /** Р-53: the real on-chain counterpart of {@link WithdrawalConfirmed} (kept only for replaying
     *  pre-Р-53 auto-confirmed rows) — carries the CAT coin's own {@code coinId}/{@code height},
     *  which the task requires be "public in the player's ledger" once confirmed. The engine-side
     *  effect is identical to {@code WithdrawalConfirmed} (the escrow debit already happened at
     *  {@link WithdrawalRequested} time; confirming just closes out the request) — {@code coinId}/
     *  {@code height} are read-model-only, {@code LedgerBook} itself has no use for them. */
    record WithdrawalConfirmedOnChain(String requestId, String coinId, long height, long atMs)
            implements GameEvent {
    }
}
