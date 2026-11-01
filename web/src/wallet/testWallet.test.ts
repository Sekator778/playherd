import { Address, Clvm, PublicKey, Signature, standardPuzzleHash } from "chia-wallet-sdk-wasm";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { getTestWallet, parseSignatureMessageBytes, resetTestWallet, signTestWalletMessage } from "./testWallet";

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.startsWith("0x") ? hex.slice(2) : hex;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Recomputes the exact `sha256tree(("Chia Signed Message", message))` `testWallet.ts` signs
 *  over, the same way `week1_wallet_auth.md` §2 says Sage does — used here only to verify a
 *  produced signature really is over that hash, not to duplicate production logic the module
 *  under test already owns. */
function signedMessageTreeHash(message: string): Uint8Array {
  const clvm = new Clvm();
  return clvm.pair(clvm.string("Chia Signed Message"), clvm.atom(parseSignatureMessageBytes(message))).treeHash();
}

beforeEach(() => {
  resetTestWallet();
  try {
    localStorage.clear();
  } catch {
    // jsdom always has localStorage in this project's test environment; ignore otherwise
  }
});

describe("browser test wallet (D-45)", () => {
  it("derives a stable txch address across repeated calls in the same profile", () => {
    const first = getTestWallet();
    const second = getTestWallet();
    expect(first.address).toBe(second.address);
    expect(first.publicKey).toBe(second.publicKey);
    expect(first.address.startsWith("txch1")).toBe(true);
  });

  it("persists the same identity across a page reload (same localStorage, fresh module instance)", async () => {
    const before = getTestWallet();
    // `resetTestWallet()` clears localStorage too (it's the "wipe this test player" action) —
    // a real reload only drops the module's in-memory key cache, not localStorage, so that's
    // simulated with `vi.resetModules()` + a fresh dynamic import instead.
    vi.resetModules();
    const fresh = await import("./testWallet");
    const after = fresh.getTestWallet();
    expect(after).toEqual(before);
  });

  it("a fresh profile (cleared storage, e.g. a different browser/incognito window) gets a different identity", () => {
    const a = getTestWallet();
    resetTestWallet();
    localStorage.clear();
    const b = getTestWallet();
    expect(b.address).not.toBe(a.address);
    expect(b.publicKey).not.toBe(a.publicKey);
  });

  it("signs over the exact sha256tree Sage signs, verifiable by the SDK's own BLS verify", () => {
    const message = "Herd — sign in\nDomain: herd.demo\nNonce: abc123\n";
    const signed = signTestWalletMessage(message);

    const pk = PublicKey.fromBytes(hexToBytes(signed.publicKey));
    const sig = Signature.fromBytes(hexToBytes(signed.signature));
    expect(pk.verify(signedMessageTreeHash(message), sig)).toBe(true);
  });

  it("the address decodes back to the same public key's standard puzzle hash", () => {
    const wallet = getTestWallet();
    const pk = PublicKey.fromBytes(hexToBytes(wallet.publicKey));
    const decoded = Address.decode(wallet.address);
    expect(bytesToHex(decoded.puzzleHash)).toBe(bytesToHex(standardPuzzleHash(pk)));
    expect(decoded.prefix).toBe("txch");
  });

  it("a signature does not verify against a different message (not a fixed/constant signature)", () => {
    const signed = signTestWalletMessage("original challenge text");
    const pk = PublicKey.fromBytes(hexToBytes(signed.publicKey));
    const sig = Signature.fromBytes(hexToBytes(signed.signature));
    expect(pk.verify(signedMessageTreeHash("tampered challenge text"), sig)).toBe(false);
  });
});

describe("Sage's hex-vs-utf8 message ambiguity (week1_wallet_auth.md §2's trap)", () => {
  it("treats an all-hex-digit message as decoded hex bytes, not its literal UTF-8 text", () => {
    const bytes = parseSignatureMessageBytes("48656c6c6f"); // hex for "Hello"
    expect(new TextDecoder().decode(bytes)).toBe("Hello");
  });

  it("strips a 0x prefix before applying the hex/utf8 check, like Sage does", () => {
    const bytes = parseSignatureMessageBytes("0x48656c6c6f");
    expect(new TextDecoder().decode(bytes)).toBe("Hello");
  });

  it("treats a normal challenge message (non-hex characters present) as literal UTF-8 text", () => {
    const message = "Herd — sign in\nDomain: herd.demo\nNonce: abc123\n";
    expect(parseSignatureMessageBytes(message)).toEqual(new TextEncoder().encode(message));
  });
});
