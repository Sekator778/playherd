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
                                              String referencePrice) {
    }

    public record LiquidationPreviewDto(String entryPriceUsed, String liquidationPrice, double percentToLiquidation) {
    }

    public record PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake) {
    }
}
