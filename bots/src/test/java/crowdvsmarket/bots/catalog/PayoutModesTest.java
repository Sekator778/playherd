package crowdvsmarket.bots.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.round.BetMode;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PayoutModesTest {

    private static List<String> fixedIds(double share) {
        return Catalog.load().bots().stream()
                .filter(e -> e.profile("player-1", share).preferredMode() == BetMode.FIXED)
                .map(CatalogEntry::catalogId).collect(Collectors.toList());
    }

    @Test
    void theSplitIsTheSameOnEveryCallAndDoesNotDependOnThePlayerId() {
        for (CatalogEntry e : Catalog.load().bots()) {
            BetMode first = e.profile("player-1", 0.5).preferredMode();
            assertThat(e.profile("player-1", 0.5).preferredMode()).isEqualTo(first);
            assertThat(e.profile("another-player", 0.5).preferredMode()).isEqualTo(first);
        }
    }

    @Test
    void theDefaultShareIsAboutHalfOfTheCatalog() {
        assertThat(PayoutModes.DEFAULT_FIXED_SHARE).isEqualTo(0.5);
        assertThat(PayoutModes.parseShare(null)).isEqualTo(0.5);
        assertThat(PayoutModes.parseShare(" ")).isEqualTo(0.5);
        assertThat(fixedIds(PayoutModes.DEFAULT_FIXED_SHARE)).hasSizeBetween(7, 14);
    }

    @Test
    void theShareSettingMovesTheSplit() {
        assertThat(fixedIds(0.0)).isEmpty();
        assertThat(fixedIds(1.0)).hasSize(21);
        // a bigger share only adds bots to the fixed side
        assertThat(fixedIds(0.8)).containsAll(fixedIds(0.5));
        assertThat(fixedIds(0.5)).containsAll(fixedIds(0.2));
        assertThat(fixedIds(0.2).size()).isLessThan(fixedIds(0.8).size());
    }

    @Test
    void theSettingIsParsedAndValidated() {
        assertThat(PayoutModes.parseShare("0.25")).isEqualTo(0.25);
        assertThat(PayoutModes.parseShare("1")).isEqualTo(1.0);
        assertThatThrownBy(() -> PayoutModes.parseShare("half")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutModes.parseShare("1.5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutModes.parseShare("-0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PayoutModes.preferredMode("x", Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
