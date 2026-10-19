package crowdvsmarket.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F3: a ledger book that does not retain its entries hands them to its listeners, in order and complete,
 * and its memory stays the size of the players, not of the movements.
 */
class LedgerBookListenerTest {

    private static final Instant APRIL = Instant.parse("2026-04-10T10:00:00Z");

    private static PlayAmount play(long whole) {
        return PlayAmount.ofWholePlay(whole);
    }

    /** The same activity on any book: every player gets a pack, then {@code rounds} rounds of stake and settlement. */
    private static void activity(LedgerBook ledger, int players, int rounds) {
        for (int round = 0; round < rounds; round++) {
            for (int p = 0; p < players; p++) {
                String player = "p" + p;
                String roundId = "r" + round;
                ledger.reserveStake(player, roundId, play(100), APRIL.plusSeconds(round));
                ledger.settleRound(player, roundId, play(100), (p + round) % 2 == 0 ? play(130) : play(100), Optional.of(true),
                        APRIL.plusSeconds(round));
            }
        }
    }

    @Test
    void aListenerSeesWhatARetainingBookKeeps_inTheSameOrderWithTheSameFields() {
        LedgerBook retaining = new LedgerBook();
        List<LedgerEntry> heard = new ArrayList<>();
        retaining.addListener(heard::add);
        activity(retaining, 5, 20);

        assertThat(heard).isNotEmpty().isEqualTo(retaining.entries());
        assertThat(heard).extracting(LedgerEntry::sequence).isSorted().doesNotHaveDuplicates();
        assertThat(retaining.lastSequence()).isEqualTo(heard.size());
    }

    @Test
    void aBookThatDoesNotRetainProducesTheSameEntriesAndRefusesToListThem() {
        LedgerBook retaining = new LedgerBook();
        LedgerBook lean = new LedgerBook(false);
        List<LedgerEntry> heard = new ArrayList<>();
        lean.addListener(heard::add);
        activity(retaining, 5, 20);
        activity(lean, 5, 20);

        assertThat(heard).isEqualTo(retaining.entries());
        for (String player : retaining.playerIds()) {
            assertThat(lean.balance(player)).isEqualTo(retaining.balance(player));
        }
        assertThatThrownBy(lean::entries).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theMemoryOfALeanBookIsTheSizeOfThePlayersNotOfTheMovements() throws Exception {
        int players = 40;
        LedgerBook lean = new LedgerBook(false);
        long[] heard = {0};
        lean.addListener(e -> heard[0]++);

        activity(lean, players, 400); // 32 000 movements

        assertThat(heard[0]).isGreaterThan(30_000);
        int largest = 0;
        for (Field field : LedgerBook.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(lean);
            int size = value instanceof Collection<?> c ? c.size() : value instanceof Map<?, ?> m ? m.size() : 0;
            largest = Math.max(largest, size);
        }
        assertThat(largest).as("the largest collection the book holds, after %d movements of %d players", heard[0], players)
                .isLessThanOrEqualTo(players);
    }
}
