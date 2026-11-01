import { useServerInfo } from "../api/useServerInfo";
import { CopyButton } from "./Common";
import { Rich } from "../i18n/Rich";
import { useT } from "../i18n";
import { playAssetIdOf, RECOMMENDED_DEPOSIT_FEE } from "./playAsset";
import "./SageHints.css";

/** The two things that trip a first Sage sign-in (`docs/research/sage_real_e2e.md` "Friction" section, items 1 and 3). */
export function SageSignInTips() {
  const t = useT();
  return (
    <ul className="sage-tips sage-tips--compact">
      <li>
        <Rich text={t("sage.signInNetwork")} />
      </li>
      <li>
        <Rich text={t("sage.signInNew")} />
      </li>
    </ul>
  );
}

/**
 * "Using Sage? Four things to know" on the Wallet screen — the friction a real player met connecting a real
 * Sage (`docs/research/sage_real_e2e.md` "Friction" section): the wrong default network, PLAY shown as "Unknown CAT"
 * (so its asset id, with a copy button), the fee a deposit needs on a busy testnet11, and the fact that every
 * sign-in is a new WalletConnect pairing.
 */
export function SageDepositHints() {
  const t = useT();
  // The server's own PLAY asset id when it sends one (`ServerInfoDto.playAssetId`), else this build's.
  const { info } = useServerInfo();
  const assetId = playAssetIdOf(info);
  return (
    <div className="card sage-hints">
      <h3>{t("sage.depositTitle")}</h3>
      <ol className="sage-tips">
        <li>
          <Rich text={t("sage.depositNetwork")} />
        </li>
        <li>
          <Rich text={t("sage.unknownCat")} />
          <span className="sage-asset">
            <code className="mono sage-asset__id" aria-label={t("sage.assetAria")}>
              {assetId}
            </code>
            <CopyButton value={assetId} label={t("sage.copyAsset")} className="btn btn--secondary sage-asset__copy" />
          </span>
        </li>
        <li>
          <Rich text={t("sage.fee", { fee: RECOMMENDED_DEPOSIT_FEE })} />
        </li>
        <li>
          <Rich text={t("sage.again")} />
        </li>
      </ol>
    </div>
  );
}
