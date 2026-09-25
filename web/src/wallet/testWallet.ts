/**
 * Browser test wallet (Р-45, task item 2): generates a real Chia key in the browser and signs
 * the login challenge exactly the way Sage does (`docs/research/week1_wallet_auth.md` §2) — same
 * BLS scheme, same synthetic-key p2 puzzle, same `("Chia Signed Message", message)` tree-hash
 * wrapping, same hex-vs-utf8 message ambiguity Sage itself resolves — using
 * `chia-wallet-sdk-wasm`.
 *
 * Package check (task item 2 — "verify its exports; if it cannot do BLS signing + txch address
 * derivation, say exactly why"): read directly off the npm-published `.d.ts`
 * (`chia-wallet-sdk-wasm@0.36.0`, 2026-09-25) — it can. `SecretKey.sign(message): Signature` is
 * the BLS signing primitive; `SecretKey.deriveUnhardenedPath`/`deriveSynthetic` reproduce the
 * standard wallet → synthetic-key derivation week1_wallet_auth.md §2 describes; the free
 * function `standardPuzzleHash(syntheticKey): Uint8Array` is the same p2-puzzle-hash step as
 * `puzzle_hash_for_synthetic_public_key` in that doc; `Address` encodes/decodes the bech32m
 * `txch…` address; `Clvm.pair`/`Program.treeHash()` compute the CLVM tree-hash the message must
 * be wrapped in before signing. No smaller/alternative package was needed — this is the official
 * `xch-dev/chia-wallet-sdk` WASM build (Apache-2.0, same org as Sage), so `.sign()`/`.verify()`
 * are the same `chia_bls`/AugSchemeMPL code path week1_wallet_auth.md §3 already recommends for
 * the server-side verifier (variant A, "same crate and version Sage itself uses") — there is no
 * independent confirmation against a live Sage instance in this environment (none is reachable
 * here), but both sides tracing back to the same upstream crate is the strongest evidence
 * available without one; `testWallet.test.ts` covers everything that CAN be checked without a
 * live Sage: internal sign/verify round-trip, address↔publicKey consistency, and Sage's own
 * hex-vs-utf8 message ambiguity (§2's "important trap").
 *
 * Stored in `localStorage` under a fixed key (task item 2 — "so each browser profile/incognito
 * window/phone = a different player"): a key is generated once per browser profile and reused
 * after that, so the same browser always signs in as the same test player.
 *
 * Explicitly a TEST-ONLY wallet for the local Docker stand (Р-44) — never a real Sage/
 * WalletConnect wallet, and labelled "Test wallet" everywhere it appears in the UI
 * (`ConnectWalletModal.tsx`) so it's never mistaken for the real thing.
 */
import { Address, Clvm, SecretKey, standardPuzzleHash, type PublicKey } from "chia-wallet-sdk-wasm";

const STORAGE_KEY = "herd_test_wallet_seed_v1";

/** `m/12381n/8444n/2/0` — Chia's standard "wallet" derivation path, first unhardened key.
 *  предложение: not itself required for challenge verification (only the final synthetic key
 *  has to match Sage's scheme, see the module doc above) — matching it anyway means this test
 *  wallet derives its key exactly the way a real wallet would. */
const DERIVATION_PATH = [12381, 8444, 2, 0];

/** CHIP-0002's required message wrapper (week1_wallet_auth.md §2) — the sha256tree of this
 *  pair, not the raw message, is what actually gets signed. */
const SIGNED_MESSAGE_PREFIX = "Chia Signed Message";

/** testnet11's bech32m address prefix (week1_wallet_auth.md §2, Р-44/45's local stand). */
const ADDRESS_PREFIX = "txch";

export interface TestWalletIdentity {
  address: string;
  /** 0x-prefixed hex, 48 bytes — the synthetic public key used to sign, exactly what Sage's
   *  `chia_signMessageByAddress` returns alongside the signature (week1_wallet_auth.md §1.1). */
  publicKey: string;
}

export interface TestWalletSignature extends TestWalletIdentity {
  /** 0x-prefixed hex, 96 bytes. */
  signature: string;
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.length % 2 === 0 ? hex : `0${hex}`;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/**
 * Sage's own hex-vs-utf8 message ambiguity (week1_wallet_auth.md §2, `parse_signature_message`):
 * a message that happens to be entirely hex digits gets signed as decoded hex bytes, not as its
 * literal UTF-8 text. Reproduced exactly (not just "always UTF-8") so this test wallet fails on
 * a pathological all-hex challenge message the same way real Sage would, instead of silently
 * signing something a real Sage-backed login never could — see `testWallet.test.ts`.
 */
export function parseSignatureMessageBytes(input: string): Uint8Array {
  const stripped = input.startsWith("0x") ? input.slice(2) : input;
  const isAllHex = stripped.length > 0 && /^[0-9a-fA-F]+$/.test(stripped);
  return isAllHex ? hexToBytes(stripped) : new TextEncoder().encode(input);
}

/** `sha256tree(("Chia Signed Message", message))` (week1_wallet_auth.md §2) — the exact bytes
 *  `SecretKey.sign` is called on, computed via the SDK's own CLVM tree-hash rather than
 *  reimplementing it by hand. */
function signedMessageTreeHash(message: string): Uint8Array {
  const clvm = new Clvm();
  const pair = clvm.pair(clvm.string(SIGNED_MESSAGE_PREFIX), clvm.atom(parseSignatureMessageBytes(message)));
  return pair.treeHash();
}

function loadOrCreateSeed(): Uint8Array {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) return hexToBytes(stored);
  } catch {
    // localStorage unavailable (e.g. some private-browsing configurations) — fall through to an
    // ephemeral seed; the current page load can still sign in, it just won't persist a reload.
  }
  const seed = new Uint8Array(32);
  crypto.getRandomValues(seed);
  try {
    localStorage.setItem(STORAGE_KEY, bytesToHex(seed));
  } catch {
    // best-effort persistence only, see above
  }
  return seed;
}

let cachedSyntheticSk: SecretKey | null = null;

function syntheticKey(): SecretKey {
  if (cachedSyntheticSk) return cachedSyntheticSk;
  const seed = loadOrCreateSeed();
  const master = SecretKey.fromSeed(seed);
  const wallet = master.deriveUnhardenedPath(DERIVATION_PATH);
  cachedSyntheticSk = wallet.deriveSynthetic();
  return cachedSyntheticSk;
}

function addressFor(pk: PublicKey): string {
  const puzzleHash = standardPuzzleHash(pk);
  return new Address(puzzleHash, ADDRESS_PREFIX).encode();
}

/** This browser profile's test-wallet identity — stable across calls/reloads (same persisted
 *  seed), does not sign anything. */
export function getTestWallet(): TestWalletIdentity {
  const pk = syntheticKey().publicKey();
  return { address: addressFor(pk), publicKey: `0x${bytesToHex(pk.toBytes())}` };
}

/** Signs `message` exactly like Sage's `chia_signMessageByAddress` (week1_wallet_auth.md §1.1):
 *  returns the address, the public key used, and the signature — the three fields
 *  `AuthVerifyRequest` needs. */
export function signTestWalletMessage(message: string): TestWalletSignature {
  const sk = syntheticKey();
  const pk = sk.publicKey();
  const signature = sk.sign(signedMessageTreeHash(message));
  return {
    address: addressFor(pk),
    publicKey: `0x${bytesToHex(pk.toBytes())}`,
    signature: `0x${bytesToHex(signature.toBytes())}`,
  };
}

/** Clears the persisted test key — not wired to any UI button yet (no product decision asks for
 *  a "reset test wallet" affordance); exposed for tests and for a future dev-tools action. */
export function resetTestWallet(): void {
  cachedSyntheticSk = null;
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // ignore — best-effort, see loadOrCreateSeed
  }
}
