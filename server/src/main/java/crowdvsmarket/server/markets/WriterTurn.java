package crowdvsmarket.server.markets;

import java.util.function.Supplier;

/**
 * Where the registry's journal writes (a market listed, a market closed) take their turn: the core writer runs them alone,
 * between two batches, so the journal stays in the order the game applied its events and the engine is never rebuilt half way
 * through one (architecture plan F6). The default runs the work in place, for a registry that is not part of the running game.
 */
public interface WriterTurn {

    <T> T run(Supplier<T> work);

    /** Runs the work in the calling thread. */
    WriterTurn IN_PLACE = new WriterTurn() {
        @Override
        public <T> T run(Supplier<T> work) {
            return work.get();
        }
    };
}
