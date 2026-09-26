package crowdvsmarket.server.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.server.live.HyperliquidClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

/**
 * Companion to {@link HyperliquidAdapterTest} (task item 7): {@link HyperliquidAdapter} explicitly
 * enabled + configured with a wallet address ({@link EnabledProfile}) — every recorded/mocked call
 * only, never a real Hyperliquid request (Hyperliquid's client is {@code @InjectMock}ed here).
 * {@code openPosition}/{@code closePosition} still cannot actually send anything even once
 * enabled+configured: they reach and fail at the signing step (EIP-712 signing is not implemented,
 * see {@link HyperliquidSigner}), never a config/validation short-circuit — proving the "disabled
 * by default" gate in {@link HyperliquidAdapterTest} is the ONLY thing stopping a live send today,
 * not an accidentally-always-broken code path underneath it.
 */
@QuarkusTest
@TestProfile(HyperliquidAdapterEnabledTest.EnabledProfile.class)
class HyperliquidAdapterEnabledTest {

    public static class EnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.execution.hyperliquid.enabled", "true",
                    "herd.execution.hyperliquid.wallet-address", "0x000000000000000000000000000000000000aa",
                    "herd.execution.hyperliquid.asset-index", "3");
        }
    }

    @Inject
    HyperliquidAdapter adapter;

    @InjectMock
    @RestClient
    HyperliquidClient hyperliquidClient;

    private static Position position() {
        return Position.openFromMargin("crowd", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));
    }

    @Test
    void openPositionBuildsARealRequestThenFailsAtSigningNotAtValidation() {
        assertThatThrownBy(() -> adapter.openPosition("crowd", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00")))
                .isInstanceOf(HyperliquidSigner.SigningNotImplementedException.class);
    }

    @Test
    void closePositionAlsoFailsAtSigningOnceEnabled() {
        assertThatThrownBy(() -> adapter.closePosition(position(), List.of()))
                .isInstanceOf(HyperliquidSigner.SigningNotImplementedException.class);
    }

    @Test
    void getPositionSendsTheRealReadOnlyRequestAndReturnsEmpty() {
        Mockito.when(hyperliquidClient.info(ArgumentMatchers.any(JsonNode.class)))
                .thenReturn(JsonNodeFactory.instance.objectNode());

        var result = adapter.getPosition(position());

        assertThat(result).isEmpty();
        Mockito.verify(hyperliquidClient).info(ArgumentMatchers.argThat(node ->
                "clearinghouseState".equals(node.get("type").asText())
                        && "0x000000000000000000000000000000000000aa".equals(node.get("user").asText())));
    }
}
