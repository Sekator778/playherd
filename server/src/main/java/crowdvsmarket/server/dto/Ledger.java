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

    public record WithdrawalRequestDto(String requestId, String playerId, String amount,
                                         Enums.WithdrawalState state, long requestedAtMs) {
    }

    public record DepositRequestDto(String requestId, String playerId, String amount, Enums.DepositState state,
                                      long requestedAtMs) {
    }
}
