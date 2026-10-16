package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.BetResult;
import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.bots.port.BettingPort;
import crowdvsmarket.bots.port.BotStatePort;
import crowdvsmarket.bots.port.MarketDataPort;
import crowdvsmarket.engine.round.Direction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The thin shell around {@link BotBrain}: reads the three ports, asks the brain, places the intents. It holds no state,
 * so any number of runners (in any number of processes, each with its own share of the bots) can work side by side.
 */
public final class BotRunner {

    /** An intent and what the game said to it. */
    public record Placed(BetIntent intent, BetResult result) {
    }

    private final MarketDataPort marketData;
    private final BettingPort betting;
    private final BotStatePort state;
    private final BotBrain brain;

    public BotRunner(MarketDataPort marketData, BettingPort betting, BotStatePort state, BotBrain brain) {
        this.marketData = marketData;
        this.betting = betting;
        this.state = state;
        this.brain = brain;
    }

    /** One decision pass for {@code profile}; an unknown account does nothing. A refused bet is reported, never thrown. */
    public List<Placed> runOnce(BotProfile profile) {
        Optional<BotAccount> account = state.account(profile.botId());
        if (account.isEmpty()) {
            return List.of();
        }
        List<MarketSnapshot> markets = marketData.markets();
        Map<String, List<BarDto>> bars = new HashMap<>();
        Map<String, Optional<Direction>> majorities = new HashMap<>();
        for (MarketSnapshot m : markets) {
            if (m.open()) {
                bars.put(m.marketId(), marketData.bars(m.marketId(), BotBrain.HISTORY_BARS));
                majorities.put(m.marketId(), marketData.previousMajority(m.marketId()));
            }
        }
        return brain.decide(profile, account.get(), markets, bars, majorities).stream()
                .map(i -> new Placed(i, betting.place(i.toRequest())))
                .toList();
    }
}
