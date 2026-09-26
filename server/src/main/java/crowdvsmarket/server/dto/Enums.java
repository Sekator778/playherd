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

    /** contract.ts `LedgerEntryType` — mirrors {@code crowdvsmarket.engine.ledger.LedgerEntryType}.
     *  {@code OPERATOR_CORRECTION} (wave-2 security fix) is additive, not in contract.ts's union —
     *  same situation as {@code ApiErrorCode.INVALID_NICKNAME}/{@code RATE_LIMITED} below. */
    public enum LedgerEntryType {
        STARTER_PACK, STAKE_DEBIT, STAKE_REFUND, SETTLEMENT, DEPOSIT, WITHDRAWAL, WITHDRAWAL_REFUND,
        OPERATOR_CORRECTION
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
        BAD_REQUEST,
        /** предложение (wave-1 finding, additive — same "not in contract.ts" situation as
         *  {@code UNAUTHORIZED} above) — {@code setNickname}'s dedicated validation failure, kept
         *  distinct from the generic {@code BAD_REQUEST} so a client can show a specific message. */
        INVALID_NICKNAME,
        /** предложение (task item 2: per-IP rate limit on authChallenge/authVerify/placeBet,
         *  Р-38 VPS stand-in) — same "additive, not in contract.ts" situation as above. */
        RATE_LIMITED,
        /** предложение (wave-2 security fix) — {@code requestWithdrawal}/{@code requestDeposit}'s
         *  dedicated rejection for a zero/negative/otherwise non-positive amount, kept distinct
         *  from the generic {@code BAD_REQUEST} (parse failure) so a client can tell "not a number"
         *  apart from "a number, but not a valid one here". Same "additive" situation as above. */
        INVALID_AMOUNT
    }
}
