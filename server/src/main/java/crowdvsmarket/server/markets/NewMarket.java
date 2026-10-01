package crowdvsmarket.server.markets;

/** What a catalog hands to {@link MarketRegistry#list}: a market without the fields the registry
 *  assigns itself (oracle index, status, listing time). */
public record NewMarket(
        String id,
        MarketKind kind,
        String category,
        String symbol,
        String title,
        String subtitle,
        String iconUrl,
        String source,
        String sourceRef,
        String sourceMeta,
        int maxLeverage,
        int lotExp,
        Long endsAtMs) {
}
