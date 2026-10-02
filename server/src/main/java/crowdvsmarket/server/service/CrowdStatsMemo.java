package crowdvsmarket.server.service;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.server.dto.Enums;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the lobby says about a crowd account — rounds traded, rounds won, how the last traded round ended —
 * counted from the account's history ONCE per round, not once per call: {@code markets} is polled every
 * 2–3 s by every open lobby, and a market's history only ever grows.
 *
 * <p>A crowd account's history is append-only and its capital changes only together with it, so
 * {@code (the account object, its history size)} says everything the counters depend on: a call that finds
 * the same account at the same size reuses the counters, one that finds it longer counts only the new rounds,
 * and anything else — a different account object, which is what an engine rebuild leaves behind — counts from
 * the beginning. Reads engine state: the caller holds the engine's read lock ({@code GameService#readEngine}),
 * which is what keeps a history from growing while it is counted; the map itself is concurrent, and two
 * readers that count the same history at the same time store equal values.
 *
 * <p><b>"Won".</b> A traded round is won when the account ended it with MORE capital than it started it with
 * (a liquidation always ends with less). A traded round that ends with exactly the capital it started with and
 * was not liquidated — the price closed where it opened — is FLAT: traded, neither won nor lost, and {@code
 * lastOutcome} says so ({@link Enums.CrowdOutcome#FLAT}, contract.ts; it used to say {@code LOSS}, which
 * understated the crowd). Anything else is a loss; a liquidation is {@code LIQUIDATED}, whatever the capital.
 * The three outcomes follow the capital, as {@code roundsWon} does.
 */
final class CrowdStatsMemo {

    /** The counters of one market's crowd account. */
    record Stats(int roundsTraded, int roundsWon, Enums.CrowdOutcome lastOutcome) {

        static final Stats NONE = new Stats(0, 0, null);
    }

    private record Entry(CrowdAccount account, int historySize, Stats stats) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    Stats of(String marketId, CrowdAccount account) {
        List<CrowdAccount.RoundResult> history = account.history();
        Entry known = entries.get(marketId);
        int from = 0;
        int traded = 0;
        int won = 0;
        Enums.CrowdOutcome last = null;
        if (known != null && known.account() == account && known.historySize() <= history.size()) {
            if (known.historySize() == history.size()) {
                return known.stats();
            }
            from = known.historySize();
            traded = known.stats().roundsTraded();
            won = known.stats().roundsWon();
            last = known.stats().lastOutcome();
        }
        for (int i = from; i < history.size(); i++) {
            CrowdAccount.RoundResult round = history.get(i);
            if (!round.traded()) {
                continue;
            }
            traded++;
            int change = round.capitalAfter().compareTo(round.capitalBefore());
            boolean roundWon = !round.liquidated() && change > 0;
            if (roundWon) {
                won++;
            }
            last = round.liquidated() ? Enums.CrowdOutcome.LIQUIDATED
                    : roundWon ? Enums.CrowdOutcome.WIN
                    : change == 0 ? Enums.CrowdOutcome.FLAT : Enums.CrowdOutcome.LOSS;
        }
        Stats stats = new Stats(traded, won, last);
        entries.put(marketId, new Entry(account, history.size(), stats));
        return stats;
    }
}
