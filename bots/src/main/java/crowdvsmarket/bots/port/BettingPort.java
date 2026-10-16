package crowdvsmarket.bots.port;

import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.bots.api.BetResult;

/** Where bets go. Refusals come back as {@link BetResult#refused}, they are never thrown. */
public interface BettingPort {

    BetResult place(BetRequest request);
}
