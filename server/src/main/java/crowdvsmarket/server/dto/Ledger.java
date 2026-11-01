package crowdvsmarket.server.dto;

/** contract.ts §"Player account / ledger". */
public final class Ledger {
    private Ledger() {
    }

    /** {@code inPlay}: additive (bug fix) — the player's total stake currently sitting in bets
     *  that have not settled yet (the BETTING and RUNNING rounds of both series, D-19: at most one
     *  bet per player per round, D-13) — money already debited from {@code balance} but not yet
     *  won or lost. */
    public record PlayerAccountDto(String playerId, String balance, String currentSeason, String inPlay,
                                 String withdrawable) {
    }

    public record SeasonRecordDto(String season, String baselineBalance, String deposited, String withdrawn,
                                    int roundsPlayed, int directionEligibleRounds, int directionCorrectRounds) {
    }

    public record LedgerEntryDto(long sequence, Enums.LedgerEntryType type, String delta, String roundId,
                                   long atMs) {
    }

    /** D-53: {@code toAddress}/{@code chainState}/{@code coinId}/{@code height}/{@code
     *  failureReason} are additive (not in contract.ts, same "additive" situation documented
     *  elsewhere in this file/{@code Enums}) — real on-chain fields, {@code null} for the
     *  pre-D-53 rows this server auto-confirmed before real chain tracking existed. {@code
     *  chainState} is chainsvc's own vocabulary ({@code "submitted"} — this server's own "the
     *  POST was accepted, not yet polled" placeholder — {@code "queued"|"in_flight"|"confirmed"|
     *  "failed"} once actually polled); {@code coinId}/{@code height} are only ever non-null once
     *  the withdrawal is on-chain-confirmed (task: "confirmed... with coinId/height, public in the
     *  player's ledger" — surfaced here since {@code GameService#withdrawal} is the player's own
     *  ledger view of their withdrawal request).
     *
     * <p><b>Task item 3 (D-53 live-verification follow-up, 2026-09-26):</b> {@code coinId} is meant
     * to be THIS request's own payout coin (the one actually sent to {@code toAddress}), not
     * chainsvc's internal bookkeeping coin — this server passes through whatever chainsvc reports
     * as-is and does not itself compute or validate it. A direct read of the chain side found
     * {@code chain/c2-svc/src/play/withdrawal_publisher.rs}'s {@code advance_in_flight} currently
     * records the SAME {@code coin_id} — the shared treasury batch's own change coin — for
     * every request confirmed together in that batch, not each request's individual payout coin;
     * that is a chain-side bug for the chain agent to fix in parallel (task: "it will be corrected
     * on the chain side"), not something addressable from {@code server/}. {@code coinsetVerifyHint}
     * is additive here regardless of that fix: once {@code coinId} is non-null, it gives the player
     * a ready-to-run, no-trust-required way to independently confirm that coin on the public
     * {@code coinset.org} light-wallet API — the same pattern {@code tools/verify-oracle/
     * verify_oracle.py} already uses for the oracle (see {@link #coinsetVerifyHint}). */
    public record WithdrawalRequestDto(String requestId, String playerId, String amount,
                                         Enums.WithdrawalState state, long requestedAtMs, String toAddress,
                                         String chainState, String coinId, Long height, String failureReason,
                                         String coinsetVerifyHint) {
    }

    /** Task item 3: the public, no-account-needed {@code coinset.org} testnet11 endpoint
     *  ({@code POST /get_coin_record_by_name}, body {@code {"name": "<coinId>"}}) a player can run
     *  themselves to verify a withdrawal's payout coin, without trusting this server — same base
     *  URL {@code tools/verify-oracle/verify_oracle.py} and {@code docs/research/
     *  week1_chain_access.md} §3 already document, and the same "testnet11" this project's only
     *  network is already hardcoded as elsewhere (e.g. {@code AuthService}'s own sign-in message).
     *  Hardcoded rather than read from {@code GameConfig.oracleNetwork()} to keep this DTO layer
     *  free of a config dependency for a purely presentational string — revisit if this project
     *  ever targets more than one network. {@code coinId} already carries chainsvc's own
     *  {@code "0x"} prefix (see e.g. {@code ChainSvcClientImplTest}'s {@code "0xcoin"} fixtures), so
     *  it is interpolated as-is. {@code null} until {@code coinId} itself is known. */
    public static String coinsetVerifyHint(String coinId) {
        return coinId == null ? null
                : "POST https://testnet11.api.coinset.org/get_coin_record_by_name {\"name\":\"" + coinId + "\"}";
    }

    public record DepositRequestDto(String requestId, String playerId, String amount, Enums.DepositState state,
                                      long requestedAtMs) {
    }

    /** Wave-2 security fix (D-32/D-43 pattern; extended wave-3, task item 1, to deposits) —
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
