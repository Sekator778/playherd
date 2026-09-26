package crowdvsmarket.server.execution;

import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlOrderAction;
import crowdvsmarket.server.execution.HyperliquidExchangeRequests.HlSignature;

/**
 * Task item 7's explicitly-allowed descope ("if full signing is too large for this pass, implement
 * the adapter with request building + a clearly failing signer stub and say so precisely"): EIP-712
 * signing of a Hyperliquid L1 action is NOT implemented — {@link #sign} always throws
 * {@link UnsupportedOperationException}. {@link HyperliquidAdapter} builds a fully real request
 * ({@link HyperliquidExchangeRequests}) and only reaches this seam right before it would otherwise
 * send it, so nothing is ever silently sent unsigned or wrongly signed.
 *
 * <p>What implementing this for real needs (sourced, both fetched 2026-09-25/26 — the exact
 * pipeline, not a guess, so a future implementer starts from real facts rather than
 * rediscovering them):
 * <ul>
 *   <li>Docs' own recommendation: "It is recommended to use an existing SDK instead of manually
 *       generating signatures" (signing.md) — the exact msgpack byte layout and EIP-712 hashing
 *       are documented as easy to get subtly wrong (field order, address lowercasing, decimal
 *       trailing zeros all affect the resulting hash).
 *   <li>EIP-712 domain (Python SDK, {@code hyperliquid/utils/signing.py}, {@code sign_l1_action}):
 *       {@code {name: "Exchange", version: "1", chainId: 1337, verifyingContract:
 *       "0x0000000000000000000000000000000000000000"}} — note {@code chainId} is hardcoded 1337
 *       regardless of mainnet/testnet.
 *   <li>The signed struct is {@code Agent: [{name: "source", type: "string"}, {name:
 *       "connectionId", type: "bytes32"}]}, {@code source} = {@code "a"} on mainnet / {@code "b"}
 *       on testnet.
 *   <li>{@code connectionId = keccak256(msgpack.packb(action) || nonce as 8 big-endian bytes ||
 *       (vaultAddress == null ? 0x00 : 0x01 || vaultAddress bytes))} — msgpack-encoding a JSON-like
 *       action tree byte-exactly the way the Python SDK does it is itself most of the work (a
 *       generic Java msgpack library plus care over key order, since "the order of fields matter
 *       for msgpack" per the docs).
 *   <li>The final signature is a standard EIP-712 {@code eth_signTypedData}-shaped ECDSA signature
 *       (secp256k1) over that {@code Agent} struct's typed-data hash, producing {@code r}/{@code
 *       s}/{@code v} — needs either a small permissive Java EIP-712/secp256k1 library or a
 *       hand-rolled implementation matching the domain/struct above exactly.
 * </ul>
 *
 * <p>None of this is implemented here — {@link #sign} exists only as the one seam
 * {@link HyperliquidAdapter} calls, so completing it later is a one-method change, not a redesign.
 */
public interface HyperliquidSigner {

    /** Thrown by every {@link HyperliquidSigner#sign} call today — see the interface's own javadoc
     *  for exactly what is missing and where to find the real facts needed to implement it. */
    final class SigningNotImplementedException extends UnsupportedOperationException {
        SigningNotImplementedException() {
            super("Hyperliquid EIP-712 L1 action signing is not implemented in this codebase — see "
                    + "HyperliquidSigner's javadoc for the exact domain/struct/msgpack pipeline needed "
                    + "(sourced from Hyperliquid's docs and Python SDK, fetched 2026-09-25/26) — "
                    + "HyperliquidAdapter never sends a request past this point.");
        }
    }

    /** Always throws {@link SigningNotImplementedException} — see this interface's javadoc. */
    HlSignature sign(HlOrderAction action, long nonce, String vaultAddress);

    /** The one (non-)implementation this codebase ships — a real signer is a future, separate
     *  change (see the interface javadoc), not something {@link HyperliquidAdapter} guesses at. */
    final class NotImplementedSigner implements HyperliquidSigner {
        @Override
        public HlSignature sign(HlOrderAction action, long nonce, String vaultAddress) {
            throw new SigningNotImplementedException();
        }
    }
}
