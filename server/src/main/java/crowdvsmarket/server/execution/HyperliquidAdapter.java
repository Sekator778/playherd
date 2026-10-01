package crowdvsmarket.server.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.execution.ExecutionAdapter;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.ClearinghouseStateRequest;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlLimit;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlOrder;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlOrderAction;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlOrderType;
import crowdvsmarket.server.live.HyperliquidClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Task item 7 (PLAN.md §3, README's "адаптер на реальную биржу (Hyperliquid) есть в коде" claim):
 * a real {@link ExecutionAdapter} for Hyperliquid — request building against the real, sourced
 * wire shapes ({@link HyperliquidExchangeRequests}, fetched from Hyperliquid's own public API docs
 * 2026-09-26) — but {@link #openPosition}/{@link #closePosition} DISABLED by default and refusing
 * to send anything unless BOTH {@code herd.execution.hyperliquid.enabled=true} AND a wallet address
 * are explicitly configured ({@link GameConfig}) — and, even then, always failing at the signing
 * step, since EIP-712 L1 action signing is not implemented in this pass (see
 * {@link HyperliquidSigner}'s javadoc for exactly what is missing and the sourced facts needed to
 * finish it). Nothing in this codebase constructs this adapter with real keys or wires it into any
 * live game path today — {@link crowdvsmarket.engine.crowd.CrowdAccount}/{@code
 * ComparisonAccounts} use {@link crowdvsmarket.engine.execution.PaperTradingAdapter} exclusively
 * (Р-38: "Активация Hyperliquid-адаптера реальными деньгами... решение и ответственность того, кто
 * разворачивает продукт").
 *
 * <p>{@link #getPosition} is the one method this adapter can genuinely execute today without any
 * signing at all — Hyperliquid's {@code POST /info} {@code clearinghouseState} is a public,
 * unauthenticated read (see {@link HyperliquidExchangeRequests.ClearinghouseStateRequest}'s
 * javadoc) — but its response shape (a live mark-to-market snapshot) does not map cleanly onto
 * {@link PositionSimulator.Outcome} (this project's round-based, deterministic-replay-shaped
 * outcome type — see {@link ExecutionAdapter}'s own javadoc on the two adapters' different
 * operational shapes), so it deliberately always returns {@link Optional#empty()} rather than
 * fabricate a mapping this task's sources don't actually pin down.
 */
@ApplicationScoped
public class HyperliquidAdapter implements ExecutionAdapter {

    private static final Logger LOG = Logger.getLogger(HyperliquidAdapter.class);

    @Inject
    GameConfig config;

    @Inject
    ObjectMapper mapper;

    @RestClient
    HyperliquidClient hyperliquidClient;

    private final HyperliquidSigner signer = new HyperliquidSigner.NotImplementedSigner();

    /** @throws IllegalStateException if disabled or no wallet address is configured — the one
     *          enforcement point every trading (not read-only) call goes through first, before
     *          building or sending anything. */
    private void requireEnabledAndConfigured() {
        if (!config.hyperliquidExecutionEnabled()) {
            throw new IllegalStateException("HyperliquidAdapter is disabled (herd.execution.hyperliquid.enabled="
                    + "false, the default) — refusing to build or send a real order. See "
                    + "docs/research/server_design.md \"Execution adapter\".");
        }
        if (config.hyperliquidWalletAddress().isBlank()) {
            throw new IllegalStateException("HyperliquidAdapter has no wallet address configured "
                    + "(herd.execution.hyperliquid.wallet-address) — refusing to trade with no account to trade as.");
        }
    }

    /** Pure request building (package-visible for direct unit testing, independent of the
     *  enabled/configured gate above) — {@code isBuy}=true opens/adds to a long. */
    static HlOrderAction buildOrderAction(int assetIndex, boolean isBuy, boolean reduceOnly, BigDecimal price,
            BigDecimal size) {
        HlOrder order = new HlOrder(assetIndex, isBuy, price.toPlainString(), size.toPlainString(), reduceOnly,
                new HlOrderType(HlLimit.goodTilCancelled()));
        return HlOrderAction.placeOrders(List.of(order));
    }

    @Override
    public Position openPosition(MarketSpec spec, String userId, String roundId, Direction direction,
            Leverage leverage, BigDecimal margin, BigDecimal entryPrice) {
        requireEnabledAndConfigured();
        // Liquidation price is a property of the position itself, independent of which venue
        // executes it (Р-7's Hyperliquid formula) — see ExecutionAdapter's javadoc.
        Position position = Position.openFromMargin(userId, roundId, direction, leverage, margin, entryPrice, spec);
        HlOrderAction action = buildOrderAction(config.hyperliquidAssetIndex(), direction == Direction.UP, false,
                entryPrice, position.size());
        long nonce = System.currentTimeMillis();
        signer.sign(action, nonce, null); // always throws — see HyperliquidSigner's javadoc
        return position; // unreachable until real signing exists
    }

    @Override
    public Optional<PositionSimulator.Outcome> getPosition(Position position) {
        String wallet = config.hyperliquidWalletAddress();
        if (wallet == null || wallet.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode request = mapper.valueToTree(ClearinghouseStateRequest.forUser(wallet));
            hyperliquidClient.info(request); // genuinely sent — read-only, no signing needed
        } catch (Exception e) {
            LOG.debugf(e, "Hyperliquid /info clearinghouseState unreachable for %s", wallet);
            return Optional.empty();
        }
        // See this class's own javadoc: deliberately not mapped onto PositionSimulator.Outcome —
        // that would require guessing at a live-mark-to-market -> round-based-outcome mapping this
        // task's sources don't pin down, rather than fabricating one.
        return Optional.empty();
    }

    @Override
    public PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen) {
        requireEnabledAndConfigured();
        // Closing is the opposite side of the original position, reduce-only — a long is closed by
        // selling, a short by buying. The candle path's last known close is used as this (never
        // actually sent) order's limit price — a live adapter would normally use a real-time mark
        // price instead, but this codebase has no live streaming feed wired to it, and the interface
        // explicitly allows an adapter to treat this parameter loosely (see its javadoc).
        boolean closingIsBuy = position.direction() == Direction.DOWN;
        BigDecimal limitPrice = priceHistorySinceOpen == null || priceHistorySinceOpen.isEmpty()
                ? position.entryPrice()
                : priceHistorySinceOpen.get(priceHistorySinceOpen.size() - 1).close();
        HlOrderAction action = buildOrderAction(config.hyperliquidAssetIndex(), closingIsBuy, true, limitPrice,
                position.size());
        long nonce = System.currentTimeMillis();
        signer.sign(action, nonce, null); // always throws — see HyperliquidSigner's javadoc
        return PositionSimulator.simulate(position, priceHistorySinceOpen); // unreachable today
    }
}
