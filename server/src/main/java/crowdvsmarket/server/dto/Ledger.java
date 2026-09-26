package crowdvsmarket.server.dto;

/** contract.ts §"Player account / ledger". */
public final class Ledger {
    private Ledger() {
    }

    /** {@code inPlay}: additive (bug fix) — the player's total stake currently sitting in bets
     *  that have not settled yet (the BETTING and RUNNING rounds of both series, Р-19: at most one
     *  bet per player per round, Р-13) — money already debited from {@code balance} but not yet
     *  won or lost. */
    public record PlayerAccountDto(String playerId, String balance, String currentSeason, String inPlay) {
    }

    public record SeasonRecordDto(String season, String baselineBalance, String deposited, String withdrawn,
                                    int roundsPlayed, int directionEligibleRounds, int directionCorrectRounds) {
    }

    public record LedgerEntryDto(long sequence, Enums.LedgerEntryType type, String delta, String roundId,
                                   long atMs) {
    }

    /** Р-53: {@code toAddress}/{@code chainState}/{@code coinId}/{@code height}/{@code
     *  failureReason} are additive (not in contract.ts, same "additive" situation documented
     *  elsewhere in this file/{@code Enums}) — real on-chain fields, {@code null} for the
     *  pre-Р-53 rows this server auto-confirmed before real chain tracking existed. {@code
     *  chainState} is chainsvc's own vocabulary ({@code "submitted"} — this server's own "the
     *  POST was accepted, not yet polled" placeholder — {@code "queued"|"in_flight"|"confirmed"|
     *  "failed"} once actually polled); {@code coinId}/{@code height} are only ever non-null once
     *  the withdrawal is on-chain-confirmed (task: "confirmed... with coinId/height, public in the
     *  player's ledger" — surfaced here since {@code GameService#withdrawal} is the player's own
     *  ledger view of their withdrawal request). */
    public record WithdrawalRequestDto(String requestId, String playerId, String amount,
                                         Enums.WithdrawalState state, long requestedAtMs, String toAddress,
                                         String chainState, String coinId, Long height, String failureReason) {
    }

    public record DepositRequestDto(String requestId, String playerId, String amount, Enums.DepositState state,
                                      long requestedAtMs) {
    }

    /** Wave-2 security fix (Р-32/Р-43 pattern; extended wave-3, task item 1, to deposits) —
     *  additive, not in contract.ts: one public, reasoned operator ledger correction (see
     *  {@code GameService#reverseWithdrawal}/{@code #reverseDeposit} / {@code AdminResource}),
     *  listed the same way {@code CancellationDto} lists a round cancellation. {@code delta}
     *  carries its own sign (a correction can be a credit or a debit). {@code relatedRequestId} is
     *  either a withdrawal's {@code "w-N"} or a deposit's {@code "d-N"} id — the prefix
     *  disambiguates which. */
    public record LedgerCorrectionDto(String correctionId, String playerId, String relatedRequestId,
                                        String delta, String reason, long atMs) {
    }
}
