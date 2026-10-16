package crowdvsmarket.bots.persona;

/** What a persona does on a market where its strategy is silent: sit the round out, or bet on a fallback signal. */
public enum NoSignalPolicy {
    /** Skip the market this round. */
    SKIP,
    /** Bet on the previous majority when known, else on a coin flip. */
    FALLBACK
}
