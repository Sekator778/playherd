package crowdvsmarket.server.markets;

import java.util.List;

/** A lobby panel (D-69, T-1). {@link #ORDER} is the display order. */
public record Category(String id, String title) {

    public static final String CRYPTO = "crypto";
    public static final String POLITICS = "politics";
    public static final String SPORTS = "sports";
    public static final String STOCKS = "stocks";
    public static final String INDICES = "indices";
    public static final String COMMODITIES = "commodities";

    public static final List<Category> ORDER = List.of(
            new Category(CRYPTO, "Crypto"),
            new Category(POLITICS, "Politics"),
            new Category(SPORTS, "Sports"),
            new Category(STOCKS, "Stocks"),
            new Category(INDICES, "Indices"),
            new Category(COMMODITIES, "Commodities"));
}
