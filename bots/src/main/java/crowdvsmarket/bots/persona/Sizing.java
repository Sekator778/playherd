package crowdvsmarket.bots.persona;

import crowdvsmarket.engine.ledger.PlayAmount;

/** A stake and a leverage a persona settled on for one bet (already within the market's terms and the balance). */
public record Sizing(PlayAmount stake, int leverage) {
}
