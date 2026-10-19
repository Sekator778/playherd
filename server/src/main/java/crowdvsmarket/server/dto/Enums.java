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

    /** contract.ts `MarketKind` — mirrors {@code crowdvsmarket.server.markets.MarketKind}. */
    public enum MarketKind {
        PERP, ODDS
    }

    /** contract.ts `MarketDto.status` and the status of a {@code CrowdOverviewDto} row — mirrors
     *  {@code crowdvsmarket.server.markets.MarketStatus}. */
    public enum MarketStatus {
        ACTIVE, CLOSED
    }

    /** contract.ts `MarketCrowdBriefDto.lastOutcome`: how the crowd's last traded round ended. {@code FLAT}: the
     *  round ended with exactly the capital it started with and was not liquidated (the price closed where it
     *  opened) — traded, neither won nor lost. */
    public enum CrowdOutcome {
        WIN, LOSS, LIQUIDATED, FLAT
    }

    /** contract.ts `LedgerEntryType` — mirrors {@code crowdvsmarket.engine.ledger.LedgerEntryType}.
     *  {@code OPERATOR_CORRECTION} (wave-2 security fix): a signed correction booked by the operator. */
    public enum LedgerEntryType {
        STARTER_PACK, STAKE_DEBIT, STAKE_REFUND, SETTLEMENT, FEE_HERD, FEE_NETWORK, FEE_HERD_REFUND,
        FEE_NETWORK_REFUND, DEPOSIT, WITHDRAWAL, WITHDRAWAL_REFUND, OPERATOR_CORRECTION
    }

    /** contract.ts `WithdrawalState`. */
    public enum WithdrawalState {
        REQUESTED, CONFIRMED, FAILED
    }

    /**
     * contract.ts `WithdrawalChainState` — the words of {@code withdrawal_requests.chain_state}, which {@code
     * WithdrawalRequestDto.chainState} hands to the web client: {@code submitted} is this server's own ("the POST
     * was accepted, not yet polled", {@code WithdrawalDepositRepository#markWithdrawalSubmitted}), the others are
     * chainsvc's own {@code state} for a withdrawal ({@code queued}, {@code in_flight}, {@code confirmed},
     * {@code failed}), passed on as they come. On the wire they are lower case ({@link #wire()}); the enum exists
     * so that the vocabulary is written down once in Java and a test can hold it against contract.ts.
     */
    public enum WithdrawalChainState {
        SUBMITTED, QUEUED, IN_FLIGHT, CONFIRMED, FAILED;

        /** The word as it is stored and sent: {@code in_flight}, {@code confirmed}, … */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
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
        /** HTTP 404 — no such player, round or request (contract.ts names it). */
        NOT_FOUND,
        BAD_REQUEST,
        /** предложение (wave-1 finding; contract.ts names it) — {@code setNickname}'s dedicated validation
         *  failure, kept distinct from the generic {@code BAD_REQUEST} so a client can show a specific
         *  message. 400. */
        INVALID_NICKNAME,
        /** предложение (task item 2: per-IP rate limit on authChallenge/authVerify/placeBet,
         *  Р-38 VPS stand-in) — same "additive, not in contract.ts" situation as above. */
        RATE_LIMITED,
        /** предложение (wave-2 security fix) — {@code requestWithdrawal}/{@code requestDeposit}'s
         *  dedicated rejection for a zero/negative/otherwise non-positive amount, kept distinct
         *  from the generic {@code BAD_REQUEST} (parse failure) so a client can tell "not a number"
         *  apart from "a number, but not a valid one here". Same "additive" situation as above. */
        INVALID_AMOUNT,
        /** Task item 1 (BLOCKER security fix): {@code requestDeposit} — no real on-chain
         *  CAT-deposit verifier exists yet (Р-6 decision pending with Alex), so deposits are
         *  rejected outright rather than crediting an unverified self-reported amount. Same
         *  "additive" situation as the values above. */
        DEPOSIT_VERIFICATION_UNAVAILABLE,
        /** Same task item, same reasoning, for {@code requestWithdrawal} (the task's "prefer
         *  disabling withdrawal too" guidance — it auto-confirms with no chain call, which would
         *  otherwise falsely claim a CAT left the game). */
        WITHDRAWAL_UNAVAILABLE,
        /** Р-53: {@code requestWithdrawal} rejects an amount below the (предложение, documented in
         *  {@code GameService}) minimum withdrawal — additive, same situation as {@code
         *  INVALID_AMOUNT} above. */
        WITHDRAWAL_BELOW_MINIMUM,
        /** Р-53: {@code myDepositAddress} could not reach chainsvc to derive/cache a brand-new
         *  player's deposit address (never thrown once already cached — see {@code
         *  PlayerDepositAddressRepository}). 503, like {@code DEPOSIT_VERIFICATION_UNAVAILABLE}/
         *  {@code WITHDRAWAL_UNAVAILABLE} above — additive. */
        CHAIN_SERVICE_UNAVAILABLE,
        /** docs/research/markets_architecture.md §8 (Р-69): a market id, or a round id that names
         *  one, that this server never listed. 404. */
        MARKET_NOT_FOUND,
        /** The market is {@code CLOSED} and takes no new bets. 409. */
        MARKET_CLOSED,
        /** No price for the market right now (nothing ticked yet / source down). 503. */
        PRICE_UNAVAILABLE,
        /** The engine is being rebuilt from the event log after a failed settlement and does not
         *  answer until that has succeeded (contract.ts names it: the web client keeps what is on
         *  screen and retries). 503, nothing was changed. */
        ENGINE_NOT_READY,
        /** Economy stage B (plan B3): the IP address already registered the most new players a day allows. 429. Additive: the web
         *  client reads it as {@code OTHER} and shows the server's message. */
        REGISTRATION_LIMIT,
        /** Economy stage B (plan B5): the bet would take the open notional of its (market, side, round), or of the crypto bucket,
         *  over the treasury limit; the message names the room that is left. 422. Additive, like {@link #REGISTRATION_LIMIT}. */
        EXPOSURE_LIMIT_EXCEEDED,
        /** Р-106: the {@code Authorization: Bearer} key is unknown, malformed or revoked. 401. (Named by contract.ts' union, like the
         *  three below; the developers' page lists them.) */
        API_KEY_INVALID,
        /** Р-106: the call is not one an API key may make (creating bots, withdrawals, profile changes, ...). 403. */
        API_CALL_NOT_ALLOWED,
        /** Р-106: the person already owns the most API bots allowed. 409. */
        BOT_LIMIT_REACHED,
        /** Р-106: an API bot's PLAY cannot be withdrawn to a wallet. 403. */
        BOT_WITHDRAWAL_NOT_ALLOWED
    }
}
