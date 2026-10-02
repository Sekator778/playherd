import { useServerInfo } from "../api/useServerInfo";
import { CopyButton } from "./Common";
import { playAssetIdOf, RECOMMENDED_DEPOSIT_FEE } from "./playAsset";
import "./SageHints.css";

/** The two things that trip a first Sage sign-in (`docs/research/sage_real_e2e.md` «Трение» п. 1, 3). */
export function SageSignInTips() {
  return (
    <ul className="sage-tips sage-tips--compact">
      <li>
        <strong>Network.</strong> Sage starts on mainnet — switch it to <strong>testnet11</strong> first (Settings → Network).
      </li>
      <li>
        <strong>Every sign-in is a new connection.</strong> On the same phone, copy the link below and paste it in Sage → Settings →
        WalletConnect; scanning only works from another device.
      </li>
    </ul>
  );
}

/**
 * "Using Sage? Four things to know" on the Wallet screen — the friction a real player met connecting a real
 * Sage (`docs/research/sage_real_e2e.md` «Трение»): the wrong default network, PLAY shown as "Unknown CAT"
 * (so its asset id, with a copy button), the fee a deposit needs on a busy testnet11, and the fact that every
 * sign-in is a new WalletConnect pairing.
 */
export function SageDepositHints() {
  // The server's own PLAY asset id when it sends one (`ServerInfoDto.playAssetId`), else this build's.
  const { info } = useServerInfo();
  const assetId = playAssetIdOf(info);
  return (
    <div className="card sage-hints">
      <h3>Using Sage? Four things to know</h3>
      <ol className="sage-tips">
        <li>
          <strong>Network.</strong> Sage starts on mainnet — switch it to <strong>testnet11</strong> (Settings → Network → Default network).
        </li>
        <li>
          <strong>“Unknown CAT”.</strong> PLAY shows up in Sage under that name — it isn't on a token list yet. Use its asset id to add or
          rename the token:
          <span className="sage-asset">
            <code className="mono sage-asset__id" aria-label="PLAY asset id">
              {assetId}
            </code>
            <CopyButton value={assetId} label="Copy asset id" className="btn btn--secondary sage-asset__copy" />
          </span>
        </li>
        <li>
          <strong>Fee.</strong> When you send PLAY to your deposit address, set the network fee to about{" "}
          <strong className="mono">{RECOMMENDED_DEPOSIT_FEE}</strong> — a busy testnet11 rejects a transfer with a zero fee.
        </li>
        <li>
          <strong>Signing in again.</strong> Each sign-in is a new WalletConnect connection: copy the link in Herd, paste it in Sage →
          Settings → WalletConnect. It isn't remembered after you reload this page.
        </li>
      </ol>
    </div>
  );
}
