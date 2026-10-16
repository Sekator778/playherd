package crowdvsmarket.bots.brain;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * The seed of every decision: {@code hash(botId, marketId, roundId)}, 64-bit FNV-1a over the UTF-8 bytes of the three
 * strings with a zero byte between them. Same triple, same seed, on any machine and any JVM; no long-lived generator.
 */
public final class Seeds {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Seeds() {
    }

    public static long of(String botId, String marketId, String roundId) {
        long h = FNV_OFFSET;
        h = mix(h, botId);
        h = mix(h, marketId);
        h = mix(h, roundId);
        return h;
    }

    /** A fresh generator for one decision. */
    public static SplittableRandom rng(String botId, String marketId, String roundId) {
        return new SplittableRandom(of(botId, marketId, roundId));
    }

    private static long mix(long h, String s) {
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= FNV_PRIME;
        }
        h ^= 0;
        h *= FNV_PRIME;
        return h;
    }
}
