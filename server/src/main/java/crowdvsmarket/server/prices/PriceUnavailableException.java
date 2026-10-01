package crowdvsmarket.server.prices;

/** No price for a round boundary, even after asking the source for history. The scheduler retries
 *  and cancels the round with a refund one hour after its end (Р-23). */
public class PriceUnavailableException extends RuntimeException {

    public PriceUnavailableException(String message) {
        super(message);
    }
}
