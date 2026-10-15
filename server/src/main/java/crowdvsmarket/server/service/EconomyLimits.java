package crowdvsmarket.server.service;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The treasury limits of economy stage B, as pure functions (docs/product/economy_implementation_plan.md B5, B6;
 * docs/research/treasury_solvency.md §4.3, §6):
 *
 * <ul>
 *   <li><b>Exposure per market (B5).</b> The open notional ({@code stake x leverage}) of one side of one round of one market may not
 *       exceed {@code 0.25% x TREASURY / stress_move(market, round length)}: if the price moves by the stress move (the 99.9th
 *       percentile of a round) against the treasury, it loses at most 0.25% of itself.
 *   <li><b>The crypto bucket (B5).</b> Crypto markets move together, so one side of one round of all of them together may carry
 *       {@code sum(notional x stress_move)} of at most {@code 1% x TREASURY}.
 *   <li><b>The circuit breaker (B6).</b> With TREASURY below 90% of its 30-day maximum new bets take a leverage of at most a quarter of
 *       the market's cap (never less than {@link Leverage#MIN}).
 * </ul>
 */
public final class EconomyLimits {

    /** Share of TREASURY one side of one round of one market may put at risk at the stress move: 0.25%. */
    public static final BigDecimal MARKET_SHARE = new BigDecimal("0.0025");
    /** Share of TREASURY one side of one round of all crypto markets together may put at risk at their stress moves: 1%. */
    public static final BigDecimal CRYPTO_BUCKET_SHARE = new BigDecimal("0.01");
    /** With the circuit breaker open a bet may take at most {@code cap / BREAKER_DIVISOR}. */
    public static final int BREAKER_DIVISOR = 4;

    private EconomyLimits() {
    }

    /** The open notional (PLAY) one side of one round of a market may carry: {@code 0.25% x treasury / stress}. */
    public static BigDecimal marketLimit(PlayAmount treasury, BigDecimal stress) {
        return treasury.toMoney().multiply(MARKET_SHARE).divide(stress, 3, RoundingMode.DOWN);
    }

    /** The risk at the stress moves (PLAY) one side of one round of the crypto bucket may carry: {@code 1% x treasury}. */
    public static BigDecimal cryptoBucketLimit(PlayAmount treasury) {
        return treasury.toMoney().multiply(CRYPTO_BUCKET_SHARE).setScale(3, RoundingMode.DOWN);
    }

    /** The highest leverage a new bet may take while the circuit breaker is open (plan B6). */
    public static int breakerMaxLeverage(int cap) {
        return Math.max(Leverage.MIN, cap / BREAKER_DIVISOR);
    }

    /**
     * The result of the exposure check of one bet.
     *
     * @param ok          the bet fits
     * @param roomNotional the open notional (PLAY) that can still be added to this side of this round of this market, at least 0 -
     *                    the smaller of the market's own room and the crypto bucket's
     * @param limitNotional the market's own limit
     * @param bucketLimited the room is the crypto bucket's, not the market's
     */
    public record Exposure(boolean ok, BigDecimal roomNotional, BigDecimal limitNotional, boolean bucketLimited) {
    }

    /**
     * @param treasury        TREASURY now
     * @param stress          the stress move of this market and round length (a fraction of price)
     * @param openNotional    the notional already open on this side of this round of this market, without the bet being replaced
     * @param newNotional     the notional of the bet
     * @param bucketRisk      {@code sum(notional x stress)} already open on this side of this round over the crypto markets (this one
     *                        included, the bet being replaced not), or {@code null} when the market is not a crypto market
     */
    public static Exposure checkExposure(PlayAmount treasury, BigDecimal stress, BigDecimal openNotional,
            BigDecimal newNotional, BigDecimal bucketRisk) {
        BigDecimal limit = marketLimit(treasury, stress);
        BigDecimal room = limit.subtract(openNotional).max(BigDecimal.ZERO);
        boolean bucketLimited = false;
        if (bucketRisk != null) {
            BigDecimal bucketRoom = cryptoBucketLimit(treasury).subtract(bucketRisk).max(BigDecimal.ZERO)
                    .divide(stress, 3, RoundingMode.DOWN);
            if (bucketRoom.compareTo(room) < 0) {
                room = bucketRoom;
                bucketLimited = true;
            }
        }
        return new Exposure(newNotional.compareTo(room) <= 0, room, limit, bucketLimited);
    }
}
