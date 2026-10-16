package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A bot's character: everything except "which way". How often it plays, on which markets, with how much and at what
 * leverage, and what it does when its strategy is silent. Pure: all randomness comes from the generator it is handed,
 * so the same inputs give the same answers.
 */
public interface Persona {

    /** Stable id such as {@code house-money}. */
    String id();

    NoSignalPolicy noSignalPolicy();

    /**
     * The markets this bot plays in this round: possibly none (it skips the round), at most the persona's own number,
     * drawn without replacement with a lean towards markets that moved lately.
     */
    List<MarketCandidate> pickMarkets(List<MarketCandidate> candidates, BotAccount account, RandomGenerator rng);

    /** The stake the persona wants on {@code market}, before the minimum and the balance are applied. */
    PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng);

    /** The leverage the persona wants for {@code stake}; the caller clamps it to {@code [2, market.maxLeverage(stake)]}. */
    int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng);

    /**
     * Stake and leverage inside the game's rules as a human player faces them: stake at least the market's minimum,
     * stake plus fees within the balance, leverage within the cap of that stake's tier. Empty when even the minimum
     * stake with its fees does not fit in the balance.
     */
    Optional<Sizing> size(MarketSnapshot market, BotAccount account, RandomGenerator rng);
}
