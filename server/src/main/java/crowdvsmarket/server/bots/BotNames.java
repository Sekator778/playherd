package crowdvsmarket.server.bots;

import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The names of the bots that replace retired ones (economy stage B, plan B4): two words, so that a new bot does not stand out as "number 21"
 * by its name - {@code "Bot Amber Fox (Contrarian)"}. A name that is already in use (by a bot of any time, retired ones included) is not
 * given again.
 */
final class BotNames {

    private static final List<String> ADJECTIVES = List.of("Amber", "Quiet", "Swift", "Lucky", "Bold", "Calm", "Clever", "Crimson",
            "Distant", "Eager", "Frosty", "Golden", "Hidden", "Iron", "Jolly", "Keen", "Lunar", "Mellow", "Nimble", "Odd", "Proud",
            "Rapid", "Silver", "Tidy", "Urban", "Vivid", "Wild", "Young");
    private static final List<String> NOUNS = List.of("Fox", "Heron", "Otter", "Falcon", "Lynx", "Panda", "Raven", "Tiger", "Whale",
            "Badger", "Cobra", "Dolphin", "Eagle", "Gecko", "Hare", "Ibis", "Jackal", "Koala", "Lemur", "Moose", "Newt", "Owl",
            "Pike", "Quail", "Robin", "Stork", "Toad", "Viper");

    private BotNames() {
    }

    /** A name not in {@code taken}: {@code "Bot <Adjective> <Noun> (<label>)"}, with a number added only when the 784 pairs of a label run out. */
    static String next(Random random, Set<String> taken, String label) {
        for (int attempt = 0; attempt < 200; attempt++) {
            String name = "Bot " + ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + " "
                    + NOUNS.get(random.nextInt(NOUNS.size())) + " (" + label + ")";
            if (!taken.contains(name)) {
                return name;
            }
        }
        int n = 2;
        while (true) {
            String name = "Bot " + ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + " "
                    + NOUNS.get(random.nextInt(NOUNS.size())) + " " + n++ + " (" + label + ")";
            if (!taken.contains(name)) {
                return name;
            }
        }
    }
}
