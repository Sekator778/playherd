package crowdvsmarket.server.prices;

/** No price for a round boundary, even after asking the source for history. The scheduler retries
 *  and cancels the round with a refund when the minutes of its source are over (D-79; D-77 sooner, where a market's other
 *  exchange is alive) and at the latest one hour after its end (D-23); a round nobody bet on is skipped at once. */
public class PriceUnavailableException extends RuntimeException {

    public PriceUnavailableException(String message) {
        super(message);
    }
}
