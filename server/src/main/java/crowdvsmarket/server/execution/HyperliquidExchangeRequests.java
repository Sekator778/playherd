package crowdvsmarket.server.execution;

import java.util.List;

/**
 * Task item 7 (the project plan §3, README's Hyperliquid-adapter claim): the real wire shape of
 * Hyperliquid's {@code POST /exchange} "place order" ({@code type: "order"}) action, per the
 * public API docs, fetched 2026-09-26:
 * https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/exchange-endpoint — field
 * names/casing quoted exactly as documented there (short single-letter keys are Hyperliquid's own
 * wire convention, not an abbreviation introduced here). {@link HyperliquidAdapter} builds these;
 * {@link HyperliquidSigner} is the (currently unimplemented, see its own javadoc) seam that would
 * fill in {@link HlExchangeRequest#signature}.
 */
public final class HyperliquidExchangeRequests {

    private HyperliquidExchangeRequests() {
    }

    /** One order's time-in-force, limit-order shape (the only order type this adapter builds —
     *  {@code "Gtc"}: good-til-cancelled, the simplest choice for an open/close market-ish order
     *  at a caller-supplied limit price). */
    public record HlLimit(String tif) {
        public static HlLimit goodTilCancelled() {
            return new HlLimit("Gtc");
        }
    }

    public record HlOrderType(HlLimit limit) {
    }

    /** One order in the {@code orders} array. {@code a}=asset index (Hyperliquid assigns these via
     *  its own {@code /info} {@code "meta"} request — this adapter does not look that up
     *  dynamically in this pass, see {@link HyperliquidAdapter}'s config javadoc), {@code b}=is-buy,
     *  {@code p}=limit price (string, exact decimal), {@code s}=size (string, exact decimal),
     *  {@code r}=reduce-only, {@code t}=order type, {@code c}=optional client order id (never set
     *  here). */
    public record HlOrder(int a, boolean b, String p, String s, boolean r, HlOrderType t) {
    }

    /** {@code grouping: "na"}: no TP/SL grouping — this adapter never places bracket orders. */
    public record HlOrderAction(String type, List<HlOrder> orders, String grouping) {
        public static HlOrderAction placeOrders(List<HlOrder> orders) {
            return new HlOrderAction("order", orders, "na");
        }
    }

    /** The final {@code r}/{@code s}/{@code v} an EIP-712 signature over the action produces —
     *  never actually populated by this codebase, see {@link HyperliquidSigner}. */
    public record HlSignature(String r, String s, int v) {
    }

    /** The full {@code POST /exchange} request body. {@code vaultAddress} is {@code null} for a
     *  direct (non-vault) account, per the docs. */
    public record HlExchangeRequest(HlOrderAction action, long nonce, HlSignature signature, String vaultAddress) {
    }

    /** {@code POST /info} {@code {"type":"clearinghouseState","user":"0x..."}} — a user's open
     *  perpetuals positions, per the public API docs, fetched 2026-09-26:
     *  https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/perpetuals
     *  — read-only, no signature required (unlike the exchange endpoint above), so
     *  {@link HyperliquidAdapter#getPosition} can genuinely send this today. */
    public record ClearinghouseStateRequest(String type, String user) {
        public static ClearinghouseStateRequest forUser(String walletAddress) {
            return new ClearinghouseStateRequest("clearinghouseState", walletAddress);
        }
    }
}
