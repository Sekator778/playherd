/**
 * Renders `value` as a scannable QR code — used by the Wallet page's deposit address (Р-53) and
 * the WalletConnect pairing URI (Р-55, `ConnectWalletModal.tsx`).
 *
 * Library check (task item 1 — "small permissive QR lib or hand-rolled"): `qrcode-generator`
 * (npm, `2.0.4`, checked 2026-09-26 via `npm view`) — MIT license, the well-known
 * kazuhikoarase encoder (same one embedded in countless other projects), a single ~15KB
 * dependency-free module (`npm view qrcode-generator dist.unpackedSize` — no transitive
 * dependencies at all, unlike the multi-package `@walletconnect/*` install this same task pulls
 * in for `wallet/walletConnect.ts`). Small enough that it is NOT dynamically imported the way
 * `chia-wallet-sdk-wasm`/`@walletconnect/sign-client` are (`wallet/testWallet.ts`,
 * `wallet/walletConnect.ts`) — it ships in the main bundle.
 *
 * Builds the QR as real SVG `<rect>` elements from the library's module grid
 * (`isDark(row, col)`) rather than using its own `createSvgTag()` (a raw HTML string), so each
 * square is `currentColor`-friendly and the whole thing themes correctly in dark mode without
 * regenerating anything.
 */
import qrcode from "qrcode-generator";

export function QrCode({ value, size = 200, label }: { value: string; size?: number; label: string }) {
  const qr = qrcode(0, "M"); // type 0 = auto-sized to fit `value`; 'M' = 15% error correction —
  // plenty of margin for a phone camera at an angle on a plain URI/address payload this short,
  // without the larger module count 'H' would need for the same data.
  qr.addData(value);
  qr.make();
  const count = qr.getModuleCount();
  const cells: string[] = [];
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (qr.isDark(row, col)) cells.push(`M${col},${row}h1v1h-1z`);
    }
  }
  return (
    <svg
      className="qr-code"
      viewBox={`0 0 ${count} ${count}`}
      width={size}
      height={size}
      role="img"
      aria-label={label}
    >
      <rect width={count} height={count} className="qr-code__bg" />
      {/* One <path> for every dark module (not one <rect> each) — far fewer DOM nodes for the
       *  same picture, and easier to recolor as a single `currentColor` shape. */}
      <path d={cells.join(" ")} className="qr-code__fg" />
    </svg>
  );
}
