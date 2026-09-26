package crowdvsmarket.server.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlOrderAction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task item 7 (PLAN.md §3, README's Hyperliquid-adapter claim): {@link HyperliquidAdapter} is
 * DISABLED by default — this class runs with NO {@code @TestProfile} override, so it exercises
 * the application's real default config (application.properties:
 * {@code herd.execution.hyperliquid.enabled=false}). See {@link HyperliquidAdapterEnabledTest} for
 * the companion "explicitly enabled+configured, but signing is not implemented" coverage.
 */
@QuarkusTest
class HyperliquidAdapterTest {

    @Inject
    HyperliquidAdapter adapter;

    private static Position position() {
        return Position.openFromMargin("crowd", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));
    }

    @Test
    void openPositionRefusesWhenDisabledByDefault() {
        assertThatThrownBy(() -> adapter.openPosition("crowd", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void closePositionRefusesWhenDisabledByDefault() {
        assertThatThrownBy(() -> adapter.closePosition(position(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void getPositionReturnsEmptyWithNoWalletAddressConfigured() {
        assertThat(adapter.getPosition(position())).isEmpty();
    }

    @Test
    void buildOrderActionMatchesTheDocumentedWireShape() {
        HlOrderAction action = HyperliquidAdapter.buildOrderAction(7, true, false,
                new BigDecimal("50000.00"), new BigDecimal("0.01"));

        assertThat(action.type()).isEqualTo("order");
        assertThat(action.grouping()).isEqualTo("na");
        assertThat(action.orders()).hasSize(1);
        var order = action.orders().get(0);
        assertThat(order.a()).isEqualTo(7);
        assertThat(order.b()).isTrue();
        assertThat(order.p()).isEqualTo("50000.00");
        assertThat(order.s()).isEqualTo("0.01");
        assertThat(order.r()).isFalse();
        assertThat(order.t().limit().tif()).isEqualTo("Gtc");
    }
}
