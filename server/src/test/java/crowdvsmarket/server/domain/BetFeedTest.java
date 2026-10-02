package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.dto.Enums;
import org.junit.jupiter.api.Test;

/** {@link BetFeed#adopt}: the swap that ends a rebuild — the feed the replay filled aside replaces the one
 *  that went on serving, rows and sequence counter together. */
class BetFeedTest {

    private static void record(BetFeed feed, String roundId, String player) {
        feed.record(roundId, player, player, false, Enums.Direction.UP, "100.000", 5, 1L);
    }

    @Test
    void adoptTakesOverTheRowsAndTheSequenceOfTheFeedBuiltAside() {
        BetFeed live = new BetFeed();
        record(live, "1h-1", "stale");
        BetFeed built = new BetFeed();
        record(built, "1h-2", "a");
        record(built, "1h-2", "b");

        live.adopt(built);

        assertThat(live.since("1h-1", 0).entries()).as("what the old feed held is gone").isEmpty();
        BetFeed.Result adopted = live.since("1h-2", 0);
        assertThat(adopted.entries()).extracting(BetFeed.Entry::playerId).containsExactly("a", "b");
        assertThat(adopted.entries()).extracting(BetFeed.Entry::seq).containsExactly(1L, 2L);
        // the sequence goes on from where the adopted feed was
        record(live, "1h-2", "c");
        assertThat(live.since("1h-2", 0).entries()).extracting(BetFeed.Entry::seq).containsExactly(1L, 2L, 3L);
        assertThat(live.since("1h-2", 2).entries()).extracting(BetFeed.Entry::playerId).containsExactly("c");
    }
}
