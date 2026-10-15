package crowdvsmarket.server.dto;

import java.util.List;

/**
 * contract.ts §"Markets and categories" (Р-69, Р-70, Р-71; docs/research/markets_architecture.md §8):
 * the DTOs of the RPCs {@code markets}, {@code priceSeries}, {@code myPositions} and {@code
 * crowdOverview}, field for field under the names and in the order contract.ts declares them. A field
 * contract.ts types {@code T | null} (or {@code ?: T | null}) is always sent here — as JSON {@code null}
 * when it has no value, never left out.
 *
 * <p>Every price in these DTOs is a RAW price string of its market with exactly {@code
 * MarketDto.priceDecimals} fractional digits (Т-3), never an engine (lot) price; money in PLAY is a
 * string with three decimals, paper USD (the crowd's) one with two.
 */
public final class Markets {
    private Markets() {
    }

    /** A lobby panel with at least one active market; {@code marketCount} counts the active ones. */
    public record CategoryDto(String id, String title, int marketCount) {
    }

    /** {@code price}: raw, {@code priceDecimals} digits. {@code change24h}: relative (0.0098 = +0.98%),
     *  {@code null} when the source gives none. {@code stale}: the last tick is older than the source's
     *  staleness limit (betting stays open). {@code venue}: the exchange the shown price is from when it is not the
     *  market's primary one — {@code "kraken"} for a market whose primary has no fresh tick and whose backup has
     *  (Р-76) — {@code null} for the primary's price and for a market that has no backup. */
    public record MarketPriceDto(String price, long asOfMs, Double change24h, boolean stale, String venue) {

        /** The price of the market's primary exchange. */
        public MarketPriceDto(String price, long asOfMs, Double change24h, boolean stale) {
            this(price, asOfMs, change24h, stale, null);
        }
    }

    /** One round series of a market. {@code maxLeverage}: the market's leverage cap for this round length (economy stage A, Р-92;
     *  a bigger stake lowers it, see {@code BetDto.maxLeverageForStake}); {@code herdFeeBp}: {@code r_m}, the herd fee in basis
     *  points of the stake per unit of leverage ({@code fee = stake * min(1%, r_m * leverage)}). {@code upStake}/{@code downStake}: PLAY staked on each side of the
     *  round open for betting; {@code majorityDirection}: {@code null} for a tie, 0 vs 0 included. */
    public record MarketSeriesDto(Enums.Interval interval, long durationSec, String bettingRoundId,
                                    long bettingClosesAtMs, String runningRoundId, long runningEndsAtMs,
                                    int participantCount, String upStake, String downStake,
                                    Enums.Direction majorityDirection, Integer maxLeverage, String herdFeeBp) {

        public MarketSeriesDto(Enums.Interval interval, long durationSec, String bettingRoundId,
                                 long bettingClosesAtMs, String runningRoundId, long runningEndsAtMs,
                                 int participantCount, String upStake, String downStake,
                                 Enums.Direction majorityDirection) {
            this(interval, durationSec, bettingRoundId, bettingClosesAtMs, runningRoundId, runningEndsAtMs,
                    participantCount, upStake, downStake, majorityDirection, null, null);
        }
    }

    /** A market's own crowd account in card form. {@code returnPct = capital / 100000 - 1};
     *  {@code lastOutcome}: the crowd's last traded round, {@code null} before the first one. */
    public record MarketCrowdBriefDto(String capital, double returnPct, int roundsTraded, int roundsWon,
                                        Enums.CrowdOutcome lastOutcome) {
    }

    /**
     * One market of the lobby. {@code endsAtMs}: when the underlying market is scheduled to end (null
     * otherwise, also for a sports game, whose source "end date" is the kick-off — see {@code
     * eventStartsAtMs}); {@code closedAtMs}/{@code closeReason}: set once {@code status} is {@code
     * CLOSED}; {@code price}: null until the first tick; {@code series}: one entry per series, shortest
     * first — empty for a market whose rounds are not running (closed, or not yet seeded). {@code source}: the
     * market's primary exchange (or source); {@code backupSource}: the exchange a round moves to when the primary cannot
     * price its start (Р-76), {@code null} for a market that has none — and the {@code source} of each round
     * says which of the two it runs on.
     */
    public record MarketDto(String id, Enums.MarketKind kind, String category, String symbol, String title,
                              String subtitle, String iconUrl, int maxLeverage, int priceDecimals,
                              Enums.MarketStatus status, Long endsAtMs, Long eventStartsAtMs, Long closedAtMs,
                              String closeReason, int oracleIndex, String source, String backupSource,
                              MarketPriceDto price, List<MarketSeriesDto> series, MarketCrowdBriefDto crowd) {
    }

    /** The lobby in one call: active markets plus those closed within the last 10 minutes, in listing order. */
    public record MarketsDto(long serverTimeMs, List<CategoryDto> categories, List<MarketDto> markets) {
    }

    public record PriceTickDto(long atMs, String price) {
    }

    /** Ticks for the room's live chart, oldest first, at most 600. */
    public record PriceSeriesDto(String marketId, int priceDecimals, List<PriceTickDto> ticks) {
    }

    /**
     * One pending bet ({@code BETTING}) or open position ({@code RUNNING}) of the signed-in player.
     * {@code entryPrice}, {@code liquidationPrice} and {@code size} are {@code null} in {@code BETTING} and
     * while the opening price of a running round is not known yet; {@code size}: lots, 8 decimals.
     * {@code untilMs}: when the round starts ({@code BETTING}) or ends ({@code RUNNING}). {@code phase}
     * is only ever {@code BETTING} or {@code RUNNING}.
     */
    public record MyPositionDto(String marketId, String symbol, String roundId, Enums.Interval interval,
                                  long durationSec, Enums.RoundPhase phase, Enums.Direction direction, int leverage,
                                  String stake, String entryPrice, String liquidationPrice, String size,
                                  boolean liquidated, long untilMs) {
    }

    /** One row of the crowd overview; a closed market keeps its row. */
    public record CrowdOverviewRowDto(String marketId, String symbol, String title, Enums.MarketStatus status,
                                        String category, String capital, double returnPct, int roundsTraded,
                                        int roundsWon) {
    }

    /** All markets' crowd accounts side by side (Т-6). */
    public record CrowdOverviewDto(String totalCapital, String totalStartingCapital, double returnPct,
                                     List<CrowdOverviewRowDto> markets) {
    }
}
