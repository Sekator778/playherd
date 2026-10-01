package crowdvsmarket.server.markets;

/** Implemented by the game core: creates the market's engines when it is listed, cancels its open
 *  rounds with a refund when it closes. Called on the catalog's thread, outside any engine lock. */
public interface MarketListener {

    void onListed(Market market);

    void onClosed(Market market, String reason);
}
