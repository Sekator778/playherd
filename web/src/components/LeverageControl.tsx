import { useId, useState } from "react";
import { LEVERAGE_MIN, type Direction, type Interval, type MarketId } from "../api/contract";
import { useT } from "../i18n";
import { Rich } from "../i18n/Rich";
import { formatMovePercent } from "./betSlipModel";
import { LeverageDial } from "./LeverageDial";
import { DEFAULT_LEVERAGE_LIMIT, dialCeiling, highLeverageAvailable, leverageLimit } from "./leverageScale";
import { useLiquidationPreview } from "./useLiquidationPreview";

/**
 * The slip's leverage control (economy stage C, C2; D-92): the dial, up to x100 (or the market's cap when that is lower), and — where the
 * market and the stake allow more — a "High leverage" switch. Turning it ON does not unlock anything yet: it asks first, with the number
 * that matters (from the liquidation preview at the highest leverage on offer: "a move of 0.083% against you liquidates this bet"),
 * and only the answer "Unlock" lifts the dial's ceiling to what the stake allows. Turning it off drops the dial back to x100.
 * The dial's track is logarithmic above x100 (`leverageScale.ts`).
 */
export function LeverageControl({
  id,
  value,
  onChange,
  cap,
  stakeMax,
  high,
  onHighChange,
  marketId,
  direction,
  stake,
  interval,
}: {
  id: string;
  /** The leverage in use (already within the dial's ceiling). */
  value: number;
  onChange: (leverage: number) => void;
  /** The market's cap for this round length. */
  cap: number;
  /** The highest leverage the stake allows, once the server has said. */
  stakeMax: number | null;
  /** "High leverage" is on: the dial may go above x100. */
  high: boolean;
  onHighChange: (high: boolean) => void;
  marketId: MarketId;
  direction: Direction;
  stake: string;
  interval?: Interval;
}) {
  const t = useT();
  const [confirming, setConfirming] = useState(false);
  const switchId = useId();
  const confirmId = useId();
  const available = highLeverageAvailable(cap, stakeMax);
  const ceiling = dialCeiling(cap, high, stakeMax);
  // What "High leverage" would unlock: the cap, as far as the stake allows.
  const unlockTo = Math.max(DEFAULT_LEVERAGE_LIMIT, Math.min(leverageLimit(cap, true), stakeMax ?? Infinity));
  const risk = useLiquidationPreview(marketId, direction, unlockTo, stake, interval, confirming);
  const riskAt = risk.leverage;

  const toggle = () => {
    if (high) {
      onHighChange(false);
      setConfirming(false);
    } else {
      setConfirming((open) => !open);
    }
  };

  return (
    <div className="leverage-control">
      <LeverageDial id={id} value={value} min={LEVERAGE_MIN} max={ceiling} onChange={onChange} />
      {(available || high) && (
        <div className="high-leverage">
          <button
            id={switchId}
            type="button"
            role="switch"
            aria-checked={high}
            aria-expanded={confirming}
            aria-controls={confirmId}
            className={`high-leverage__switch ${high ? "high-leverage__switch--on" : ""}`}
            onClick={toggle}
          >
            <span className="high-leverage__knob" aria-hidden="true" />
            <span className="high-leverage__label">{t("betSlip.highLeverage")}</span>
          </button>
          <span className="faint high-leverage__state">
            {high ? t("betSlip.highOn", { max: ceiling }) : t("betSlip.highHint", { limit: DEFAULT_LEVERAGE_LIMIT })}
          </span>
        </div>
      )}
      {confirming && !high && (
        <div id={confirmId} className="high-leverage__confirm" role="group" aria-label={t("betSlip.highConfirmTitle")}>
          <p className="high-leverage__title">
            <strong>{t("betSlip.highConfirmTitle")}</strong>
          </p>
          <p aria-live="polite">
            {risk.preview ? (
              <Rich text={t("betSlip.highConfirmBody", { max: riskAt, pct: formatMovePercent(risk.preview.percentToLiquidation) })} />
            ) : (
              t("betSlip.highConfirmLoading")
            )}
          </p>
          <div className="direction-row">
            <button type="button" className="btn btn--secondary" onClick={() => setConfirming(false)}>
              {t("betSlip.highKeep", { limit: DEFAULT_LEVERAGE_LIMIT })}
            </button>
            <button
              type="button"
              className="btn btn--danger"
              disabled={!risk.preview}
              onClick={() => {
                onHighChange(true);
                setConfirming(false);
              }}
            >
              {t("betSlip.highUnlock", { max: riskAt })}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
