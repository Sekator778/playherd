/**
 * WalletConnect / Reown sign-in — the real, non-test-wallet route for connecting an actual Sage
 * wallet (`docs/research/week1_wallet_auth.md` §1.1–§1.2: `chia_signMessageByAddress` over a
 * WalletConnect v2 session).
 *
 * Not implemented here: it needs its own Reown Cloud `projectId`, a registration Alex hasn't
 * made yet (week1_wallet_auth.md §5, "Что нужно от Алекса") — outbound registrations are only
 * ever made with Alex's explicit go-ahead (`CLAUDE.md` §"Зафиксированные решения").
 *
 * Kept only as the shape this path will have once that `projectId` exists, so
 * `ConnectWalletModal.tsx` (today wired to `wallet/testWallet.ts` for the local stand, Р-45) has
 * a stable interface to branch to later without redesigning the connect flow around it.
 */
export interface WalletConnectSignResult {
  address: string;
  /** 0x-prefixed hex, 48 bytes — same shape as `TestWalletIdentity.publicKey`. */
  publicKey: string;
  /** 0x-prefixed hex, 96 bytes. */
  signature: string;
}

/** Always `false` until a Reown `projectId` is configured (see module doc) — lets a future
 *  connect-modal branch on "is real WalletConnect even offered right now" without importing an
 *  env var directly. */
export function isWalletConnectAvailable(): boolean {
  return false;
}

/** Not implemented — throws until a Reown `projectId` exists (see module doc). Signature kept
 *  stable with `signTestWalletMessage` (`wallet/testWallet.ts`) on purpose: same
 *  `message in, {address, publicKey, signature} out` shape, so swapping one for the other later
 *  doesn't touch `ConnectWalletModal.tsx`'s call site beyond the import. */
export async function signWithWalletConnect(message: string): Promise<WalletConnectSignResult> {
  void message;
  throw new Error(
    "WalletConnect sign-in isn't wired up yet — it needs its own Reown Cloud projectId " +
      "(docs/research/week1_wallet_auth.md §1.2/§5).",
  );
}
