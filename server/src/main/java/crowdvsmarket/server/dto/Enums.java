package crowdvsmarket.server.dto;

/**
 * Plain enums mirrored 1:1 from web/src/api/contract.ts. Kept in one file since each is a
 * one-liner; JSON serialization uses the Java constant name, which is spelled identically to
 * the TS union member in every case here (contract.ts §"Primitives"/"Errors").
 */
public final class Enums {
    private Enums() {
    }

    /** contract.ts `Interval` — mirrors {@code crowdvsmarket.engine.market.Interval}. */
    public enum Interval {
        HOURLY, DAILY
    }

    /** contract.ts `Direction` — mirrors {@code crowdvsmarket.engine.round.Direction}. */
    public enum Direction {
        UP, DOWN
    }

    /** contract.ts `RoundPhase`. */
    public enum RoundPhase {
        BETTING, RUNNING, SETTLED, CANCELLED
    }

    /** contract.ts `LedgerEntryType` — mirrors {@code crowdvsmarket.engine.ledger.LedgerEntryType}. */
    public enum LedgerEntryType {
        STARTER_PACK, STAKE_DEBIT, STAKE_REFUND, SETTLEMENT, DEPOSIT, WITHDRAWAL, WITHDRAWAL_REFUND
    }

    /** contract.ts `WithdrawalState`. */
    public enum WithdrawalState {
        REQUESTED, CONFIRMED, FAILED
    }

    /** contract.ts `DepositState`. */
    public enum DepositState {
        REQUESTED, CONFIRMED, FAILED
    }

    /** contract.ts `ApiErrorCode`. */
    public enum ApiErrorCode {
        NOT_SIGNED_IN,
        ROUND_NOT_OPEN_FOR_BETTING,
        STAKE_BELOW_MINIMUM,
        INSUFFICIENT_BALANCE,
        INVALID_LEVERAGE,
        WITHDRAWAL_EXCEEDS_BALANCE,
        CHALLENGE_EXPIRED_OR_UNKNOWN,
        SIGNATURE_INVALID,
        /** предложение — not in contract.ts's ApiErrorCode list, used for admin-token/route
         *  failures and unknown RPC method names, which contract.ts does not model (it only
         *  documents Api-method-level failures). */
        UNAUTHORIZED,
        NOT_FOUND,
        BAD_REQUEST
    }
}
