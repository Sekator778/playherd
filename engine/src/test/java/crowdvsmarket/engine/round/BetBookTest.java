package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.util.Collection;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BetBookTest {

    private static Bet bet(String player, String round, Direction direction, int leverage, long stakePlay) {
        return new Bet(player, round, direction, new Leverage(leverage), PlayAmount.ofWholePlay(stakePlay));
    }

    @Test
    void firstPlaceReturnsEmpty() {
        BetBook book = new BetBook();
        Optional<Bet> previous = book.place(bet("alice", "r1", Direction.UP, 10, 100));
        assertThat(previous).isEmpty();
    }

    @Test
    void secondPlaceBySamePlayerSameRoundReplacesAndReturnsThePrevious() {
        BetBook book = new BetBook();
        Bet first = bet("alice", "r1", Direction.UP, 10, 100);
        Bet second = bet("alice", "r1", Direction.DOWN, 20, 300);
        book.place(first);

        Optional<Bet> replaced = book.place(second);

        assertThat(replaced).contains(first);
        assertThat(book.betsFor("r1")).containsExactly(second);
    }

    @Test
    void differentPlayersOnSameRoundBothKept() {
        BetBook book = new BetBook();
        Bet alice = bet("alice", "r1", Direction.UP, 10, 100);
        Bet bob = bet("bob", "r1", Direction.DOWN, 5, 200);
        book.place(alice);
        book.place(bob);

        assertThat(book.betsFor("r1")).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void sameBoardPlayerOnDifferentRoundsIsIndependent() {
        BetBook book = new BetBook();
        Bet onR1 = bet("alice", "r1", Direction.UP, 10, 100);
        Bet onR2 = bet("alice", "r2", Direction.DOWN, 10, 100);
        book.place(onR1);
        book.place(onR2);

        assertThat(book.betsFor("r1")).containsExactly(onR1);
        assertThat(book.betsFor("r2")).containsExactly(onR2);
    }

    @Test
    void cancelRemovesAndReturnsTheBet() {
        BetBook book = new BetBook();
        Bet placed = bet("alice", "r1", Direction.UP, 10, 100);
        book.place(placed);

        Optional<Bet> cancelled = book.cancel("r1", "alice");

        assertThat(cancelled).contains(placed);
        assertThat(book.betsFor("r1")).isEmpty();
    }

    @Test
    void cancelOnUnknownRoundOrPlayerReturnsEmpty() {
        BetBook book = new BetBook();
        assertThat(book.cancel("nope", "alice")).isEmpty();
        book.place(bet("alice", "r1", Direction.UP, 10, 100));
        assertThat(book.cancel("r1", "bob")).isEmpty();
    }

    @Test
    void clearDropsTheWholeRound() {
        BetBook book = new BetBook();
        book.place(bet("alice", "r1", Direction.UP, 10, 100));
        book.place(bet("bob", "r1", Direction.DOWN, 5, 200));

        book.clear("r1");

        Collection<Bet> remaining = book.betsFor("r1");
        assertThat(remaining).isEmpty();
    }

    @Test
    void betsForUnknownRoundIsEmptyNotNull() {
        assertThat(new BetBook().betsFor("nope")).isEmpty();
    }
}
