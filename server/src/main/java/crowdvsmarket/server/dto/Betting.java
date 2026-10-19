package crowdvsmarket.server.dto;

/** contract.ts §"Liquidation preview". */
public final class Betting {
    private Betting() {
    }

    public record MarketReferenceDto(String price, long asOfMs) {
    }

    /** {@code marketId} omitted/null: BTC (contract.ts). {@code referencePrice} is a raw price of that
     *  market. */
    public record LiquidationPreviewRequest(String marketId, Enums.Direction direction, int leverage, String stake,
                                              String referencePrice, Enums.Interval interval) {

        public LiquidationPreviewRequest(String marketId, Enums.Direction direction, int leverage, String stake,
                                           String referencePrice) {
            this(marketId, direction, leverage, stake, referencePrice, null);
        }
    }

    /**
     * {@code feeHerd}, {@code feeNetwork}, {@code totalDebit}, {@code maxLeverageForStake}: economy stage A (Р-92), present
     * only when the request named an {@code interval} (the rules depend on the round length): the fees the bet would be
     * charged, stake plus both fees (what leaves the balance), and the highest leverage allowed for this stake. With an
     * interval the preview also uses the maintenance rate {@code κ / cap} of that round length and rejects a leverage above
     * what the stake allows ({@code INVALID_LEVERAGE}, the message names the maximum).
     */
    public record LiquidationPreviewDto(String entryPriceUsed, String liquidationPrice, double percentToLiquidation,
                                          String feeHerd, String feeNetwork, String totalDebit,
                                          Integer maxLeverageForStake) {

        public LiquidationPreviewDto(String entryPriceUsed, String liquidationPrice, double percentToLiquidation) {
            this(entryPriceUsed, liquidationPrice, percentToLiquidation, null, null, null, null);
        }
    }

    /** The payout mode of today: the payout follows the stake, the leverage and the size of the price move (Р-106 (2)). */
    public static final String MODE_PERCENT = "PERCENT";

    /**
     * {@code mode} (Р-106): how the bet pays. Absent or {@code null} means {@value #MODE_PERCENT}, today's mode, so every client written
     * before the field existed keeps working as it is. A second mode (fixed win/lose) is being designed; until it exists the server
     * answers any other value with 400 {@code BAD_REQUEST} instead of ignoring it, so a client that asks for a mode this server does
     * not know never gets a bet in a mode it did not choose.
     */
    public record PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake, String mode) {

        public PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake) {
            this(roundId, direction, leverage, stake, null);
        }
    }
}
