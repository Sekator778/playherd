package crowdvsmarket.server.markets;

import crowdvsmarket.server.events.GameEvent;
import java.util.function.Function;

/**
 * Where the registry's journal writes (a market listed, a market closed) take their turn: a command of the core writer, in a batch like
 * any other, so the journal stays in the order the game applied its events and the engine is never rebuilt half way through one
 * (architecture plan F6). The turn touches no database: what it needs from the database is known before it (the registry holds the
 * markets in memory) and the markets table is written after it ({@link MarketRegistry#projectFromJournal}). A registry that is not part
 * of the running game has no turn set and appends its events itself.
 */
public interface WriterTurn {

    /** What the work of a turn can do: stage an event, and undo its own memory if the batch does not commit. */
    interface Turn {
        /** Stages {@code event} for the journal (the batch's append); the engine does not apply it. */
        void record(GameEvent event, long atMs);

        /** Runs {@code action} on the writer thread if the batch does not commit, before the engine is rebuilt. */
        void onRollback(Runnable action);
    }

    <T> T run(Function<Turn, T> work);
}
