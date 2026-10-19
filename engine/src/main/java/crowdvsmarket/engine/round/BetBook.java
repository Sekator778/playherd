package crowdvsmarket.engine.round;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pending bets, keyed by round id then player id — enforces "one position per player per round"
 * (Р-13) at the data-structure level: {@link #place} always replaces any prior bet for the same
 * (round, player), returning the replaced one (if any) so the caller can refund its stake.
 *
 * <p><b>O(1) aggregates (architecture plan F2):</b> next to each round's bets the book keeps the open notional
 * ({@code stake x leverage}) of each side, updated by the only three mutations there are ({@link #place},
 * {@link #cancel}, {@link #clear}), so {@link #openNotional} and {@link #find} cost the same with one bet in the round as
 * with ten thousand. The sums are whole mojos ({@code long}), exact like the {@code BigDecimal} sum they replace: the
 * state is a function of the bets alone, so a live run and a replay of its journal end with the same numbers.
 * The same holds for the stake of each side's fixed-payout bets (Р-106), which count for the fixed-mode treasury limits and not
 * for the notional. The aggregates live here, not in a separate class, because the book is the single owner of the bets and every mutation
 * of them (live, replay, a state built aside) already goes through these methods.
 */
public final class BetBook {

    /** One round's bets, the notional of each side (percentage-mode bets) and the stake of each side (fixed-payout bets), in mojos of PLAY. */
    private static final class RoundBets {
        final Map<String, Bet> bets = new LinkedHashMap<>();
        long upNotional;
        long downNotional;
        long upFixed;
        long downFixed;

        void add(Bet bet) {
            shift(bet, 1);
        }

        void remove(Bet bet) {
            shift(bet, -1);
        }

        private void shift(Bet bet, int sign) {
            long notional = sign * notionalOf(bet);
            long fixed = sign * fixedStakeOf(bet);
            if (bet.direction() == Direction.UP) {
                upNotional = Math.addExact(upNotional, notional);
                upFixed = Math.addExact(upFixed, fixed);
            } else {
                downNotional = Math.addExact(downNotional, notional);
                downFixed = Math.addExact(downFixed, fixed);
            }
        }

        long side(Direction direction) {
            return direction == Direction.UP ? upNotional : downNotional;
        }

        long fixedSide(Direction direction) {
            return direction == Direction.UP ? upFixed : downFixed;
        }
    }

    /** What a bet adds to the open notional: {@code stake x leverage} for a percentage-mode bet, nothing for a fixed-payout one (it has no leverage). */
    private static long notionalOf(Bet bet) {
        return bet.isFixed() ? 0L : Math.multiplyExact(bet.stake().mojos(), (long) bet.leverage().value());
    }

    /** What a bet adds to the fixed-payout stake of its side: its stake if it is fixed-payout, else nothing. */
    private static long fixedStakeOf(Bet bet) {
        return bet.isFixed() ? bet.stake().mojos() : 0L;
    }

    private final Map<String, RoundBets> byRound = new LinkedHashMap<>();

    /** Places {@code bet}, replacing any earlier bet by the same player on the same round. */
    public Optional<Bet> place(Bet bet) {
        RoundBets round = byRound.computeIfAbsent(bet.roundId(), r -> new RoundBets());
        Bet previous = round.bets.put(bet.playerId(), bet);
        if (previous != null) {
            round.remove(previous);
        }
        round.add(bet);
        return Optional.ofNullable(previous);
    }

    public Optional<Bet> cancel(String roundId, String playerId) {
        RoundBets round = byRound.get(roundId);
        if (round == null) {
            return Optional.empty();
        }
        Bet removed = round.bets.remove(playerId);
        if (removed != null) {
            round.remove(removed);
        }
        return Optional.ofNullable(removed);
    }

    /** A copy of the round's bets in the order they were first placed — O(bets); for one player's bet use {@link #find}. */
    public Collection<Bet> betsFor(String roundId) {
        RoundBets round = byRound.get(roundId);
        return round == null ? List.of() : List.copyOf(round.bets.values());
    }

    /** {@code playerId}'s pending bet on {@code roundId}, if any — O(1), no copy. */
    public Optional<Bet> find(String roundId, String playerId) {
        RoundBets round = byRound.get(roundId);
        return round == null ? Optional.empty() : Optional.ofNullable(round.bets.get(playerId));
    }

    /** How many bets the round holds — O(1). */
    public int count(String roundId) {
        RoundBets round = byRound.get(roundId);
        return round == null ? 0 : round.bets.size();
    }

    /**
     * The notional ({@code stake x leverage}, PLAY, scale 3) open on {@code direction}'s side of the round, without
     * {@code exceptPlayer}'s bet ({@code null} for none) — O(1). {@link BigDecimal#ZERO} when no bet is counted.
     */
    public BigDecimal openNotional(String roundId, Direction direction, String exceptPlayer) {
        RoundBets round = byRound.get(roundId);
        if (round == null) {
            return BigDecimal.ZERO;
        }
        long total = round.side(direction);
        if (exceptPlayer != null) {
            Bet own = round.bets.get(exceptPlayer);
            if (own != null && own.direction() == direction) {
                total -= notionalOf(own);
            }
        }
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(total, 3);
    }

    /**
     * The stake (PLAY, scale 3) of the fixed-payout bets (Р-106) open on {@code direction}'s side of the round, without
     * {@code exceptPlayer}'s bet ({@code null} for none) - O(1), like {@link #openNotional}.
     */
    public BigDecimal openFixedStake(String roundId, Direction direction, String exceptPlayer) {
        RoundBets round = byRound.get(roundId);
        if (round == null) {
            return BigDecimal.ZERO;
        }
        long total = round.fixedSide(direction);
        if (exceptPlayer != null) {
            Bet own = round.bets.get(exceptPlayer);
            if (own != null && own.direction() == direction) {
                total -= fixedStakeOf(own);
            }
        }
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(total, 3);
    }

    /** Drops the round entirely, once its bets have been opened into positions at round start —
     * bets no longer accept modification after that (Р-8). */
    public void clear(String roundId) {
        byRound.remove(roundId);
    }
}
