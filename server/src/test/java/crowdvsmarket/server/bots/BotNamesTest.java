package crowdvsmarket.server.bots;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotNamesTest {

    @Test
    void aGeneratedNameIsNewAndCarriesTheStrategy() {
        Random random = new Random(3);
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            String name = BotNames.next(random, taken, "Contrarian");
            assertThat(name).startsWith("Bot ").endsWith("(Contrarian)");
            assertThat(taken.add(name)).as("%s is new", name).isTrue();
        }
    }

    @Test
    void whenEveryPairIsTakenANumberIsAdded() {
        Random random = new Random(5);
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            assertThat(taken.add(BotNames.next(random, taken, "Wildcard"))).isTrue();
        }
        assertThat(taken).anyMatch(n -> n.matches("Bot \\w+ \\w+ \\d+ \\(Wildcard\\)"));
    }
}
