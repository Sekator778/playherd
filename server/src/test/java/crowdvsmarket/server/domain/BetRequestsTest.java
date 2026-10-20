package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.round.Direction;
import org.junit.jupiter.api.Test;

/** The memory of request ids: per player and id, a window counted from the request's own time, bounded, order of the events kept. */
class BetRequestsTest {

    private static BetRequests.Entry entry(String player, String id, long atMs) {
        return new BetRequests.Entry(player, id, atMs, "BTC:1", Direction.UP, 5, 300_000L, 9_000L, 100L, false);
    }

    @Test
    void anEntryCountsInsideTheWindowOnly() {
        BetRequests memory = new BetRequests();
        memory.remember(entry("p1", "a", 1_000));

        assertThat(memory.find("p1", "a", 1_000)).isNotNull();
        assertThat(memory.find("p1", "a", 1_000 + BetRequests.WINDOW_MS)).isNotNull();
        assertThat(memory.find("p1", "a", 1_000 + BetRequests.WINDOW_MS + 1)).isNull();
        assertThat(memory.find("p1", "a", 500)).as("a request stamped a little before the first one").isNotNull();
    }

    @Test
    void thePlayerIsPartOfTheKey() {
        BetRequests memory = new BetRequests();
        memory.remember(entry("p1", "a", 1_000));

        assertThat(memory.find("p2", "a", 1_000)).isNull();
        assertThat(memory.find("p1", "b", 1_000)).isNull();
    }

    @Test
    void aLaterEntryDropsTheOnesOlderThanTheWindowAndANewerSameKeyReplacesTheOld() {
        BetRequests memory = new BetRequests();
        memory.remember(entry("p1", "a", 1_000));
        memory.remember(entry("p1", "b", 2_000));
        memory.remember(entry("p1", "c", 1_000 + BetRequests.WINDOW_MS + 1));

        assertThat(memory.all()).extracting(BetRequests.Entry::requestId).containsExactly("b", "c");

        memory.remember(entry("p1", "b", 3_000));
        assertThat(memory.all()).extracting(BetRequests.Entry::requestId).containsExactly("c", "b");
        assertThat(memory.find("p1", "b", 3_000).atMs()).isEqualTo(3_000);
    }

    @Test
    void theMemoryIsBoundedOldestFirst() {
        BetRequests memory = new BetRequests();
        for (int i = 0; i < BetRequests.MAX_ENTRIES + 25; i++) {
            memory.remember(entry("p" + i, "id", 1_000 + i));
        }

        assertThat(memory.size()).isEqualTo(BetRequests.MAX_ENTRIES);
        assertThat(memory.find("p0", "id", 1_000)).isNull();
        assertThat(memory.find("p" + (BetRequests.MAX_ENTRIES + 24), "id", 1_000)).isNotNull();
    }

    @Test
    void restoreGivesBackWhatAllGave() {
        BetRequests memory = new BetRequests();
        memory.remember(entry("p1", "a", 1_000));
        memory.remember(entry("p2", "a", 2_000));

        BetRequests copy = new BetRequests();
        copy.remember(entry("p9", "zzz", 5));
        copy.restore(memory.all());

        assertThat(copy.all()).isEqualTo(memory.all());
    }
}
