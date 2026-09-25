package crowdvsmarket.server.dto;

/** contract.ts §"Player account / ledger". */
public final class Ledger {
    private Ledger() {
    }

    public record PlayerAccountDto(String playerId, String balance, String currentSeason) {
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
