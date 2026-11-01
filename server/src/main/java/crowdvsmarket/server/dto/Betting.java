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
     * {@code feeHerd}, {@code feeNetwork}, {@code totalDebit}, {@code maxLeverageForStake}: economy stage A (D-92), present
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

    /**
     * {@code mode} (D-106): absent or {@code PERCENTAGE} - the original bet with a leverage; {@code FIXED} - win 2 x stake, lose the
     * stake, a tie gives it back: no leverage ({@code leverage} is ignored), refused on {@code ODDS} markets.
     *
     * <p>{@code clientRequestId} (optional, 1-{@value #MAX_REQUEST_ID_LENGTH} characters of {@code A-Z a-z 0-9 - _}, a UUID in the web
     * app): the id of one press of the button. A repeat of the request with the same id from the same player within ten minutes places
     * no second bet and is answered with the first answer ({@code BetRequests}); the same id for a different bet is refused 400.
     * Absent: the call behaves as it always did.
     */
    public record PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake, Enums.BetMode mode,
                                  String clientRequestId) {

        public static final int MAX_REQUEST_ID_LENGTH = 64;

        public PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake, Enums.BetMode mode) {
            this(roundId, direction, leverage, stake, mode, null);
        }

        public PlaceBetRequest(String roundId, Enums.Direction direction, int leverage, String stake) {
            this(roundId, direction, leverage, stake, null, null);
        }

        /** Whether {@code id} is a usable request id: not empty, not longer than {@value #MAX_REQUEST_ID_LENGTH}, only letters, digits, {@code -} and {@code _}. */
        public static boolean validRequestId(String id) {
            if (id == null || id.isEmpty() || id.length() > MAX_REQUEST_ID_LENGTH) {
                return false;
            }
            for (int i = 0; i < id.length(); i++) {
                char c = id.charAt(i);
                boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
                if (!ok) {
                    return false;
                }
            }
            return true;
        }

        public boolean isFixed() {
            return mode == Enums.BetMode.FIXED;
        }
    }
}
