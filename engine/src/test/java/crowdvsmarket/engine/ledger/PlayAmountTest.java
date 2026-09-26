package crowdvsmarket.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlayAmountTest {

    @Test
    void ofWholePlayConvertsAtOneThousandMojosPerPlay() {
        assertThat(PlayAmount.ofWholePlay(10_000).mojos()).isEqualTo(10_000_000L);
        assertThat(PlayAmount.ofWholePlay(100).mojos()).isEqualTo(100_000L);
    }

    @Test
    void fromMoneyAndToMoneyRoundTripExactly() {
        PlayAmount amount = PlayAmount.fromMoney(new BigDecimal("123.45"));
        assertThat(amount.mojos()).isEqualTo(123_450L);
        assertThat(amount.toMoney()).isEqualByComparingTo("123.45");
    }

    @Test
    void fromMoneyAcceptsThreeDecimalPlaces() {
        PlayAmount amount = PlayAmount.fromMoney(new BigDecimal("0.001"));
        assertThat(amount.mojos()).isEqualTo(1L);
    }

    @Test
    void fromMoneyRejectsPrecisionFinerThanOneMojo() {
        assertThatThrownBy(() -> PlayAmount.fromMoney(new BigDecimal("0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void arithmeticIsExact() {
        PlayAmount a = PlayAmount.ofWholePlay(100);
        PlayAmount b = PlayAmount.fromMoney(new BigDecimal("40.50"));

        assertThat(a.plus(b).toMoney()).isEqualByComparingTo("140.50");
        assertThat(a.minus(b).toMoney()).isEqualByComparingTo("59.50");
        assertThat(b.negate().toMoney()).isEqualByComparingTo("-40.50");
        assertThat(b.negate().isNegative()).isTrue();
        assertThat(a.isPositive()).isTrue();
        assertThat(PlayAmount.ZERO.isNegative()).isFalse();
        assertThat(PlayAmount.ZERO.isPositive()).isFalse();
    }

    @Test
    void requirePositiveAcceptsAPositiveAmountAndReturnsItself() {
        PlayAmount amount = PlayAmount.ofWholePlay(100);
        assertThat(amount.requirePositive("stake")).isSameAs(amount);
    }

    @Test
    void requirePositiveRejectsZero() {
        assertThatThrownBy(() -> PlayAmount.ZERO.requirePositive("withdrawal amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("withdrawal amount");
    }

    @Test
    void requirePositiveRejectsNegative() {
        // Wave-2 exploit shape: requestWithdrawal("-5000.000").
        PlayAmount negative = PlayAmount.fromMoney(new BigDecimal("-5000.000"));
        assertThatThrownBy(() -> negative.requirePositive("withdrawal amount"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("withdrawal amount");
    }

    @Test
    void comparableOrdersByMojos() {
        PlayAmount small = PlayAmount.ofWholePlay(1);
        PlayAmount big = PlayAmount.ofWholePlay(2);
        assertThat(small.compareTo(big)).isLessThan(0);
        assertThat(big.compareTo(small)).isGreaterThan(0);
        assertThat(small.compareTo(PlayAmount.ofWholePlay(1))).isZero();
    }
}
