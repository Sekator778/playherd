package crowdvsmarket.engine.round;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pending bets, keyed by round id then player id — enforces "one position per player per round"
 * (Р-13) at the data-structure level: {@link #place} always replaces any prior bet for the same
 * (round, player), returning the replaced one (if any) so the caller can refund its stake.
 */
public final class BetBook {

    private final Map<String, Map<String, Bet>> byRound = new LinkedHashMap<>();

    /** Places {@code bet}, replacing any earlier bet by the same player on the same round. */
    public Optional<Bet> place(Bet bet) {
        Map<String, Bet> roundBets = byRound.computeIfAbsent(bet.roundId(), r -> new LinkedHashMap<>());
        Bet previous = roundBets.put(bet.playerId(), bet);
        return Optional.ofNullable(previous);
    }

    public Optional<Bet> cancel(String roundId, String playerId) {
        Map<String, Bet> roundBets = byRound.get(roundId);
        if (roundBets == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(roundBets.remove(playerId));
    }

    public Collection<Bet> betsFor(String roundId) {
        Map<String, Bet> roundBets = byRound.get(roundId);
        return roundBets == null ? List.of() : List.copyOf(roundBets.values());
    }

    /** Drops the round entirely, once its bets have been opened into positions at round start —
     * bets no longer accept modification after that (Р-8). */
    public void clear(String roundId) {
        byRound.remove(roundId);
    }
}
