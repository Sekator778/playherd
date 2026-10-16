package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.MarketSnapshot;

/**
 * An open market a persona may pick, with how sharply it moved in the last few minutes ({@code recentMove}, the absolute
 * relative change of the close over the last 5 candles; 0 when the history is short). Personas favour moving markets
 * (bot_behavior_sources.md B3, "All That Glitters").
 */
public record MarketCandidate(MarketSnapshot market, double recentMove) {
}
