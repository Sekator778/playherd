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

    public record PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake) {
    }
}
