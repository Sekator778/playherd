package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The thinning rule of {@code priceSeries} (contract.ts: at most 600 points, thinned evenly so they still
 * span the whole range): the oldest and the NEWEST tick are always kept, the order is preserved, no tick is
 * kept twice, and the gaps between kept ticks differ by at most one.
 */
class PriceThinningTest {

    private static List<Integer> ticks(int n) {
        return IntStream.range(0, n).boxed().toList();
    }

    @Test
    void fewerTicksThanTheCapAreReturnedAsTheyAre() {
        List<Integer> few = ticks(37);

        assertThat(PriceThinning.thin(few, 600)).isSameAs(few);
        assertThat(PriceThinning.thin(List.<Integer>of(), 600)).isEmpty();
        assertThat(PriceThinning.thin(List.of(7), 600)).containsExactly(7);
    }

    @Test
    void exactlyTheCapIsNotThinned() {
        List<Integer> exact = ticks(600);

        assertThat(PriceThinning.thin(exact, 600)).isSameAs(exact);
    }

    @Test
    void oneTickOverTheCapKeepsBothEndsAndDropsOneInTheMiddle() {
        List<Integer> thinned = PriceThinning.thin(ticks(601), 600);

        assertThat(thinned).hasSize(600);
        assertThat(thinned.get(0)).isZero();
        assertThat(thinned.get(599)).isEqualTo(600);
        assertThat(thinned).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void tenThousandTicksBecomeSixHundredSpanningTheWholeRangeEvenly() {
        List<Integer> thinned = PriceThinning.thin(ticks(10_000), 600);

        assertThat(thinned).hasSize(600);
        assertThat(thinned.get(0)).as("the oldest").isZero();
        assertThat(thinned.get(599)).as("the newest is always kept").isEqualTo(9_999);
        // 9999 / 599 = 16.69…: every step is 16 or 17 ticks
        for (int i = 1; i < thinned.size(); i++) {
            assertThat(thinned.get(i) - thinned.get(i - 1)).isBetween(16, 17);
        }
    }

    @Test
    void forEveryLengthAboveTheCapTheResultIsSortedDistinctAndEvenlySpacedWithBothEnds() {
        for (int n = 601; n <= 2_500; n++) {
            List<Integer> thinned = PriceThinning.thin(ticks(n), 600);

            assertThat(thinned).as("n=%d", n).hasSize(600);
            assertThat(thinned.get(0)).as("n=%d oldest", n).isZero();
            assertThat(thinned.get(599)).as("n=%d newest", n).isEqualTo(n - 1);
            int low = (n - 1) / 599;
            int high = low + ((n - 1) % 599 == 0 ? 0 : 1);
            for (int i = 1; i < thinned.size(); i++) {
                int gap = thinned.get(i) - thinned.get(i - 1);
                assertThat(gap).as("n=%d gap before %d", n, i).isBetween(low, high);
                assertThat(gap).isPositive();
            }
        }
    }

    @Test
    void theKeptTicksAreTheOriginalElementsInTheirOriginalOrder() {
        List<String> labelled = new ArrayList<>();
        for (int i = 0; i < 1_234; i++) {
            labelled.add("t" + i);
        }

        List<String> thinned = PriceThinning.thin(labelled, 600);

        assertThat(labelled).containsAll(thinned);
        assertThat(thinned.stream().mapToInt(s -> Integer.parseInt(s.substring(1))).boxed().toList()).isSorted();
        assertThat(thinned.get(thinned.size() - 1)).isEqualTo("t1233");
    }

    @Test
    void aSmallerCapWorksTheSame() {
        assertThat(PriceThinning.thin(ticks(100), 2)).containsExactly(0, 99);
        assertThat(PriceThinning.thin(ticks(11), 3)).containsExactly(0, 5, 10);
        assertThat(PriceThinning.thin(ticks(5), 5)).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void aCapBelowTwoCannotSpanARange() {
        assertThatThrownBy(() -> PriceThinning.thin(ticks(10), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceThinning.thin(ticks(10), 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
