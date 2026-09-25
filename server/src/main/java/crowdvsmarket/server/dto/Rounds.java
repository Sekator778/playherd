package crowdvsmarket.server.dto;

import java.util.List;

/**
 * contract.ts §"Rounds". {@code RoundDto} is a discriminated union in TS, tagged by its own
 * {@code phase} field — each Java record below carries that same literal-valued field, so plain
 * (non-polymorphic) Jackson serialization of whichever concrete record a handler returns already
 * produces the exact same shape the TS union member expects; no {@code @JsonTypeInfo} needed
 * since the server only ever serializes these, never deserializes them from a client.
 */
public final class Rounds {
    private Rounds() {
    }

    public record RoundWindowDto(Enums.Interval interval, long startMs, long endMs) {
    }

    public record BetDto(String playerId, String roundId, Enums.Direction direction, int leverage, String stake) {
    }

    public record PositionDto(String playerId, String roundId, Enums.Direction direction, int leverage,
                                String size, String entryPrice, String liquidationPrice, String pnl) {
    }

    /** {@code stake}: additive (bug fix, contract.ts's already-declared optional
     *  {@code PositionDto.stake} — see its comment there) — the original PLAY stake this position
     *  was opened with ({@code Bet.stake}/{@code PlayerRoundOutcome.stake}), exact, so a client
     *  never has to lossily re-derive it to show a position's net PLAY change
     *  ({@code payout - stake}). */
    public record PublicPositionDto(String playerId, String roundId, Enums.Direction direction, int leverage,
                                      String size, String entryPrice, String liquidationPrice, String pnl,
                                      String stake, Identity.PlayerSummaryDto player, boolean liquidated,
                                      String payout, Boolean directionCorrect) {
    }

    public record CrowdRoundPositionDto(String roundId, Enums.Direction direction, int leverage, String margin,
                                          String entryPrice, String liquidationPrice) {
    }

    public record CrowdRoundResultDto(String roundId, boolean traded, Enums.Direction direction, Integer leverage,
                                        String margin, String entryPrice, boolean liquidated, String capitalBefore,
                                        String capitalAfter) {
    }

    public record CancellationDto(String roundId, String reason, long cancelledAtMs, int refundedBetCount) {
    }

    /** One sealed marker so service code can pattern-match; not itself serialized (each variant
     *  record below carries its own {@code phase} literal, see class javadoc). */
    public sealed interface RoundDto permits RoundBetting, RoundRunning, RoundSettled, RoundCancelled {
        String roundId();
    }

    public record RoundBetting(String roundId, RoundWindowDto window, String asset, String phase,
                                 int participantCount, BetDto myBet) implements RoundDto {
        public RoundBetting(String roundId, RoundWindowDto window, int participantCount, BetDto myBet) {
            this(roundId, window, "BTC", "BETTING", participantCount, myBet);
        }
    }

    public record RoundRunning(String roundId, RoundWindowDto window, String asset, String phase, String openPrice,
                                 List<PublicPositionDto> positions, CrowdRoundPositionDto crowd) implements RoundDto {
        public RoundRunning(String roundId, RoundWindowDto window, String openPrice,
                             List<PublicPositionDto> positions, CrowdRoundPositionDto crowd) {
            this(roundId, window, "BTC", "RUNNING", openPrice, positions, crowd);
        }
    }

    public record RoundSettled(String roundId, RoundWindowDto window, String asset, String phase, String openPrice,
                                 String closePrice, List<PublicPositionDto> positions, CrowdRoundResultDto crowd,
                                 String resultsHash) implements RoundDto {
        public RoundSettled(String roundId, RoundWindowDto window, String openPrice, String closePrice,
                             List<PublicPositionDto> positions, CrowdRoundResultDto crowd, String resultsHash) {
            this(roundId, window, "BTC", "SETTLED", openPrice, closePrice, positions, crowd, resultsHash);
        }
    }

    public record RoundCancelled(String roundId, RoundWindowDto window, String asset, String phase,
                                   CancellationDto cancellation) implements RoundDto {
        public RoundCancelled(String roundId, RoundWindowDto window, CancellationDto cancellation) {
            this(roundId, window, "BTC", "CANCELLED", cancellation);
        }
    }

    public record RoundsPairDto(Enums.Interval interval, RoundDto running, RoundDto betting) {
    }
}
