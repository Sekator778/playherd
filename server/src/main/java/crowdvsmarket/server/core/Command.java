package crowdvsmarket.server.core;

/**
 * One request to change the game, run by {@link CoreWriter}'s thread: it validates against the state the commands before it
 * have left, applies its events to the engine through {@link WriteContext#record} and returns the answer the caller gets once
 * the batch it was in has committed. A {@code RuntimeException} thrown before anything was applied is a refusal (answered at
 * once, nothing is written); one thrown after something was applied means the engine may be ahead of the journal, and the
 * writer rebuilds it ({@link WriteContext#touch}).
 */
@FunctionalInterface
public interface Command<T> {
    T run(WriteContext ctx);
}
