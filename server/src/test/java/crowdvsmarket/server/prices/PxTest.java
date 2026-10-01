package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** The scaled-long price of a tick: exact for every price a market can have. */
class PxTest {

    static Stream<String> prices() {
        return Stream.of("84647.0", "2694.4", "150.12", "2.5123", "0.094534", "0.005708", "0.5230", "0.0005", "0.9995",
                "1316.6893", "30557.0", "0.0000012345", "899999999.5");
    }

    @ParameterizedTest
    @MethodSource("prices")
    void roundTripsExactly(String price) {
        BigDecimal raw = new BigDecimal(price);

        assertThat(Px.isStorable(raw)).isTrue();
        assertThat(Px.decode(Px.encode(raw))).isEqualByComparingTo(raw);
    }

    @Test
    void decodedPricesHaveNoExponentNotation() {
        assertThat(Px.decode(Px.encode(new BigDecimal("84000.0"))).toString()).isEqualTo("84000");
        assertThat(Px.decode(Px.encode(new BigDecimal("1E+3"))).toString()).isEqualTo("1000");
        assertThat(Px.decode(Px.encode(new BigDecimal("0.5230"))).toString()).isEqualTo("0.523");
    }

    @Test
    void rejectsPricesThatCannotBeOnesOfAMarket() {
        assertThat(Px.isStorable(null)).isFalse();
        assertThat(Px.isStorable(BigDecimal.ZERO)).isFalse();
        assertThat(Px.isStorable(new BigDecimal("-1"))).isFalse();
        assertThat(Px.isStorable(new BigDecimal("900000001"))).isFalse(); // beyond what the scaled long holds
        assertThat(Px.isStorable(new BigDecimal("1E+30"))).isFalse();
        assertThat(Px.isStorable(new BigDecimal("0.00000000001"))).isFalse(); // rounds to zero at 10 decimals
    }
}
