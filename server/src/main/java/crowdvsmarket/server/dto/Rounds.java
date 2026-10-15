package crowdvsmarket.server.dto;

import java.util.List;

/**
 * contract.ts §"Rounds". {@code RoundDto} is a discriminated union in TS, tagged by its own
 * {@code phase} field — each Java record below carries that same literal-valued field, so plain
 * (non-polymorphic) Jackson serialization of whichever concrete record a handler returns already
 * produces the exact same shape the TS union member expects; no {@code @JsonTypeInfo} needed
 * since the server only ever serializes these, never deserializes them from a client.
 *
 * <p>Every round carries the facts of its market that a round page needs to print the round's prices and
 * to name their source without {@code markets()} — contract.ts {@code RoundBase}: {@code marketId}, {@code
 * asset} (the market's symbol), {@code kind}, {@code priceDecimals} and {@code source}. A market leaves
 * {@code markets()} ten minutes after it closes, its rounds stay readable for good, so these come from the
 * market registry, which keeps every market it ever listed. They sit after {@code asset} in every record,
 * in the order contract.ts has them.
 */
public final class Rounds {
    private Rounds() {
    }

    public record RoundWindowDto(Enums.Interval interval, long startMs, long endMs) {
    }

    /**
     * {@code feeHerd}, {@code feeNetwork}, {@code maxLeverageForStake}: economy stage A (Р-92) — the two fees charged on top
     * of {@code stake} when the bet was placed (PLAY, scale 3) and the highest leverage the market allows for this stake in
     * this round length (cap, lowered by the stake tier). All {@code null} for a bet placed before the economy change.
     */
    public record BetDto(String playerId, String roundId, Enums.Direction direction, int leverage, String stake,
                           String feeHerd, String feeNetwork, Integer maxLeverageForStake) {

        public BetDto(String playerId, String roundId, Enums.Direction direction, int leverage, String stake) {
            this(playerId, roundId, direction, leverage, stake, null, null, null);
        }
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

    /** {@code marketId}/{@code asset}: the round's market and its symbol, so that the public cancellations log names
     *  the pair and not only the round id (contract.ts: optional there, always sent here). */
    public record CancellationDto(String roundId, String marketId, String asset, String reason, long cancelledAtMs,
                                    int refundedBetCount) {
    }

    /** Additive (Р-62, web-side counterpart already in contract.ts): one row of
     *  {@code RoundBetting.recentBets} — a small, capped, newest-first convenience embed of the
     *  live bet feed (see {@code BetFeedEntryDto}/{@code betFeed} RPC below for the full,
     *  cursor-paginated version of the same underlying {@code BetFeed} data). Same identity/
     *  direction/leverage/stake shape as a settled position already exposes
     *  ({@code PublicPositionDto}), just for a bet that hasn't reached round start yet. */
    public record LiveBetDto(Identity.PlayerSummaryDto player, Enums.Direction direction, int leverage, String stake,
                               long atMs) {
    }

    /** Additive (Р-62): one row of the public, paginated "who bet what" live feed
     *  ({@code betFeed} RPC) — a new entry is appended every time a player places OR edits a bet
     *  (Р-13's "modify before start" shows as a new feed row too, same as a Twitch Predictions
     *  viewer changing their vote never erases the earlier row). {@code playerDisplayName}/
     *  {@code isBot} are flattened out of {@code PlayerSummaryDto} on purpose — nicknames are
     *  already public per Р-15/Р-29, and a feed row has no other use for the rest of that DTO
     *  (wallet address, playerId). {@code seq} is this feed's own monotonic counter (distinct from
     *  {@code oracle_records.seq}), used for {@code betFeed(roundId, sinceSeq)} pagination — unlike
     *  {@link LiveBetDto}'s small newest-first embed, this is the full, cursor-paginated history. */
    public record BetFeedEntryDto(long seq, long atMs, String playerDisplayName, boolean isBot,
                                    Enums.Direction direction, String stake, int leverage) {
    }

    /** Additive (Р-62). {@code entries}: oldest-first among those with {@code seq > sinceSeq},
     *  capped at {@code BetFeed.PAGE_SIZE} per call. {@code hasMore=true} means more already-placed
     *  entries exist beyond this page — call again immediately with this page's last entry's
     *  {@code seq} rather than waiting for the next poll tick. */
    public record BetFeedDto(String roundId, String marketId, String asset, List<BetFeedEntryDto> entries,
                               boolean hasMore) {
    }

    /** Additive (task item 1c, FIXED INTERFACE b/c): optional per-round anchor into chainsvc's own
     *  oracle publication (Р-40/41/47) — {@code state} one of
     *  {@code "queued"|"in_flight"|"confirmed"|"unknown"} (chainsvc's FIXED INTERFACE (b) vocabulary,
     *  see {@code ChainSvcClient.OracleRecordState}); {@code height} only non-null once
     *  {@code state} is {@code "confirmed"}. Web will pick this up later (not edited here). */
    public record OracleAnchorDto(long seq, String state, Long height) {
    }

    /** One sealed marker so service code can pattern-match; not itself serialized (each variant
     *  record below carries its own {@code phase} literal, see class javadoc). */
    public sealed interface RoundDto permits RoundBetting, RoundRunning, RoundSettled, RoundCancelled {
        String roundId();
    }

    /** {@code upStake}/{@code downStake}/{@code recentBets} match the shape already added to
     *  contract.ts by the web side (Р-62, "Расклад толпы... видны вживую... как в Twitch
     *  Predictions", replacing Р-22's "count only" during BETTING) — {@code upCount}/
     *  {@code downCount}/{@code majorityDirection}/{@code majorityLeverage} are this server's
     *  additive extension of the same live aggregate (the task's full spec: "upStake, downStake,
     *  upCount, downCount, participants [= participantCount], and the crowd's current majority/
     *  leverage preview"). {@code majorityDirection}/{@code majorityLeverage} are both {@code null}
     *  only for a tie (including 0 vs 0) — same tie rule and same stake-weighted-average leverage
     *  calc (Р-14) as {@link CrowdRoundPositionDto}/{@code CrowdAccount}'s own majority-side
     *  computation, just previewed one phase earlier, before the round has actually opened a
     *  position. */
    public record RoundBetting(String roundId, RoundWindowDto window, String marketId, String asset,
                                 Enums.MarketKind kind, int priceDecimals, String source, String phase,
                                 int participantCount, BetDto myBet, String upStake, String downStake, int upCount,
                                 int downCount, Enums.Direction majorityDirection, Integer majorityLeverage,
                                 List<LiveBetDto> recentBets) implements RoundDto {
        public RoundBetting(String roundId, RoundWindowDto window, String marketId, String asset,
                             Enums.MarketKind kind, int priceDecimals, String source,
                             int participantCount, BetDto myBet, String upStake, String downStake, int upCount,
                             int downCount, Enums.Direction majorityDirection, Integer majorityLeverage,
                             List<LiveBetDto> recentBets) {
            this(roundId, window, marketId, asset, kind, priceDecimals, source, Enums.RoundPhase.BETTING.name(),
                    participantCount, myBet, upStake, downStake, upCount, downCount, majorityDirection,
                    majorityLeverage, recentBets);
        }
    }

    /** {@code openPrice} and every price in {@code positions}/{@code crowd} are raw strings of the
     *  market (priceDecimals digits). {@code openPrice} is {@code null} while the opening price is not
     *  known yet (the market just listed, the tick buffer still empty after a restart, a source
     *  outage across the boundary) — web/src/api/contract.ts: "show waiting for the opening price,
     *  never a zero" — and then {@code positions} is empty and {@code crowd} null too: nothing can be
     *  derived from an entry price that does not exist yet. */
    public record RoundRunning(String roundId, RoundWindowDto window, String marketId, String asset,
                                 Enums.MarketKind kind, int priceDecimals, String source, String phase,
                                 String openPrice, List<PublicPositionDto> positions, CrowdRoundPositionDto crowd)
            implements RoundDto {
        public RoundRunning(String roundId, RoundWindowDto window, String marketId, String asset,
                             Enums.MarketKind kind, int priceDecimals, String source, String openPrice,
                             List<PublicPositionDto> positions, CrowdRoundPositionDto crowd) {
            this(roundId, window, marketId, asset, kind, priceDecimals, source, Enums.RoundPhase.RUNNING.name(),
                    openPrice, positions, crowd);
        }
    }

    public record RoundSettled(String roundId, RoundWindowDto window, String marketId, String asset,
                                 Enums.MarketKind kind, int priceDecimals, String source, String phase,
                                 String openPrice, String closePrice, List<PublicPositionDto> positions,
                                 CrowdRoundResultDto crowd, String resultsHash, OracleAnchorDto oracleAnchor)
            implements RoundDto {
        public RoundSettled(String roundId, RoundWindowDto window, String marketId, String asset,
                             Enums.MarketKind kind, int priceDecimals, String source, String openPrice,
                             String closePrice, List<PublicPositionDto> positions, CrowdRoundResultDto crowd,
                             String resultsHash) {
            this(roundId, window, marketId, asset, kind, priceDecimals, source, Enums.RoundPhase.SETTLED.name(),
                    openPrice, closePrice, positions, crowd, resultsHash, null);
        }
    }

    /** {@code resultsHash}: the hash of the canonical results JSON stored with the cancellation (its {@code status}
     *  is {@code CANCELLED}, no positions, no crowd) — {@code GET /api/rounds/{id}/results.json} serves the JSON it
     *  covers, so a cancelled round can be verified like a settled one. Always sent for a stored round. */
    public record RoundCancelled(String roundId, RoundWindowDto window, String marketId, String asset,
                                   Enums.MarketKind kind, int priceDecimals, String source, String phase,
                                   CancellationDto cancellation, String resultsHash, OracleAnchorDto oracleAnchor)
            implements RoundDto {
        public RoundCancelled(String roundId, RoundWindowDto window, String marketId, String asset,
                               Enums.MarketKind kind, int priceDecimals, String source,
                               CancellationDto cancellation, String resultsHash) {
            this(roundId, window, marketId, asset, kind, priceDecimals, source, Enums.RoundPhase.CANCELLED.name(),
                    cancellation, resultsHash, null);
        }
    }

    public record RoundsPairDto(Enums.Interval interval, RoundDto running, RoundDto betting) {
    }
}
