package crowdvsmarket.server.markets;

/**
 * What a market's price is (docs/research/markets_architecture.md T-2). {@code PERP}: the USD price of an
 * asset, whatever the venue — the mark price of a Hyperliquid perpetual, the mid of the best bid and ask of a
 * Coinbase or a Hyperliquid spot market (the name {@code PERP} is from the first; the market's
 * {@code source} says which it is). {@code ODDS}: the probability (0..1) of one outcome of a Polymarket market.
 * Round mechanics are the same for both.
 */
public enum MarketKind {
    PERP,
    ODDS
}
