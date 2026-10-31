// Node port of web/src/wallet/testWallet.ts — same key derivation, same message wrapping, same
// signing scheme (Р-45: "та же схема и проверка подписи, что для Sage"), reusing the identical
// `chia-wallet-sdk-wasm` package the browser test wallet uses (imported straight from
// `web/node_modules` — no separate install, per the task's "no global installs").
//
// Verified byte-for-byte against chain/c2-svc/tests/vectors/auth_vectors.json's "positive" case
// (same seed -> same public key, same address, same signature) before this file was written.
//
// Run with `node --experimental-wasm-modules` (plain `import` of a `.wasm` file is still
// experimental in Node 22) — see tools/e2e/run.mjs's shebang-adjacent comment and
// docs/product/local_stand.md.

import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const sdkPath = path.join(__dirname, "..", "..", "web", "node_modules", "chia-wallet-sdk-wasm", "chia_wallet_sdk_wasm.js");

const sdk = await import(sdkPath);

const DERIVATION_PATH = [12381, 8444, 2, 0]; // m/12381n/8444n/2n/0n, unhardened — Chia's standard wallet path
const SIGNED_MESSAGE_PREFIX = "Chia Signed Message";
const ADDRESS_PREFIX = "txch";

function hexToBytes(hex) {
  const clean = hex.startsWith("0x") ? hex.slice(2) : hex;
  return Buffer.from(clean, "hex");
}

function bytesToHex(bytes) {
  return Buffer.from(bytes).toString("hex");
}

/** Sage's own hex-vs-utf8 ambiguity (week1_wallet_auth.md §2) — reproduced exactly, matching
 *  web/src/wallet/testWallet.ts's parseSignatureMessageBytes. None of this e2e's own challenge
 *  messages are all-hex (they start with "Herd sign-in\n", see AuthService.java), so this only
 *  ever takes the utf8 branch here — kept for fidelity with the real wallet code path. */
function parseSignatureMessageBytes(input) {
  const stripped = input.startsWith("0x") ? input.slice(2) : input;
  const isAllHex = stripped.length > 0 && /^[0-9a-fA-F]+$/.test(stripped);
  return isAllHex ? hexToBytes(stripped) : new TextEncoder().encode(input);
}

/**
 * One deterministic test-wallet identity, derived from a fixed label the same way the Rust
 * vector generator derives its seeds (`sha256(label)` as the 32-byte EIP-2333 seed) — so re-
 * running the e2e script always signs in as the same four players instead of creating new ones
 * every time.
 */
export function createTestWallet(label) {
  const seed = createHash("sha256").update(label, "utf8").digest();
  const master = sdk.SecretKey.fromSeed(seed);
  const wallet = master.deriveUnhardenedPath(DERIVATION_PATH);
  const sk = wallet.deriveSynthetic();
  const pk = sk.publicKey();
  const puzzleHash = sdk.standardPuzzleHash(pk);
  const address = new sdk.Address(puzzleHash, ADDRESS_PREFIX).encode();
  const publicKey = `0x${bytesToHex(pk.toBytes())}`;

  function sign(message) {
    const clvm = new sdk.Clvm();
    const pair = clvm.pair(clvm.string(SIGNED_MESSAGE_PREFIX), clvm.atom(parseSignatureMessageBytes(message)));
    const treeHash = pair.treeHash();
    const signature = sk.sign(treeHash);
    return `0x${bytesToHex(signature.toBytes())}`;
  }

  return { label, address, publicKey, sign };
}
