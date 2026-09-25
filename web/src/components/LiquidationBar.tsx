import type { CSSProperties } from "react";
import type { UsdStr } from "../api/contract";
import { formatPercent, formatUsd } from "../format";
import type { HeatTier } from "./LeverageDial";

/**
 * Pre-bet liquidation distance meter (`docs/product/design/live-round.md` §2) — replaces the
 * audit's "two lines of plain text, no visual indicator of proximity" (`BetSlip.tsx`'s old
 * `.liquidation-preview`) with a horizontal bar: entry price / liquidation price as the two
 * endpoints, fill = how much room is left, colored by the same heat ramp the leverage dial uses.
 *
 * `docs/product/design/implementation-plan.md` §2: "Пре-бет версия... работает уже сегодня,
 * никаких новых данных не требует" — this is exactly that static version, driven only by the
 * existing `LiquidationPreviewDto`. The live, updating-during-RUNNING version (§2's "Live-
 * версия") needs `btcReferencePrice()` wired into a poll loop and is out of scope for this pass.
 *
 * предложение: live-round.md §2's zone thresholds (Safe ≥50% / Watch 25-50% / Close 10-25% /
 * Critical <10%) describe the LIVE case, where the denominator is the distance at bet time and
 * the fraction shrinks toward 0 as price moves. Pre-bet, `percentToLiquidation` alone has no such
 * shrinking denominator — no source fixes what "full bar" means here. `SAFE_REFERENCE` below is
 * this file's own reference denominator (not from any doc): normalizing against it turns
 * `percentToLiquidation` into the same kind of 0..1 "room left" fraction, so the same zone
 * thresholds and heat colors can be reused honestly (more leverage → less room → hotter color,
 * monotonic either way).
 */
const SAFE_REFERENCE = 0.4;

function zoneForFraction(fraction: number): { tier: HeatTier; className: string } {
  if (fraction >= 0.5) return { tier: 2, className: "" };
  if (fraction >= 0.25) return { tier: 3, className: "liq-bar--watch" };
  if (fraction >= 0.1) return { tier: 4, className: "liq-bar--close" };
  return { tier: 5, className: "liq-bar--critical" };
}

/** Same zone table as the bar's own fill (above), exposed so a page can also pulse the whole
 *  card that hosts a LIVE (RUNNING-phase) bar — `live-round.md` §2: "карточка получает... пульс".
 *  Returns `""` for the Safe zone (no card-level pulse there, matching the bar's own "статично"
 *  row). `RoundPage.tsx` is the current caller. */
export function liquidationLiveCardClass(percentToLiquidation: number): string {
  const fraction = Math.max(0, Math.min(1, percentToLiquidation / SAFE_REFERENCE));
  const { className } = zoneForFraction(fraction);
  if (className === "liq-bar--watch") return "card--pulse-watch";
  if (className === "liq-bar--close") return "card--pulse-close";
  if (className === "liq-bar--critical") return "card--pulse-critical";
  return "";
}

const HEAT_VAR: Record<HeatTier, string> = {
  1: "var(--heat-1)",
  2: "var(--heat-2)",
  3: "var(--heat-3)",
  4: "var(--heat-4)",
  5: "var(--heat-5)",
};

export function LiquidationBar({
  entryPrice,
  liquidationPrice,
  percentToLiquidation,
}: {
  entryPrice: UsdStr;
  liquidationPrice: UsdStr;
  /** 0..1 fraction, e.g. `0.025` = 2.5% (`LiquidationPreviewDto.percentToLiquidation`). */
  percentToLiquidation: number;
}) {
  const fraction = Math.max(0, Math.min(1, percentToLiquidation / SAFE_REFERENCE));
  const { tier, className } = zoneForFraction(fraction);
  const style = {
    "--fill-pct": `${Math.round(fraction * 100)}%`,
    "--heat-color": HEAT_VAR[tier],
  } as CSSProperties;

  return (
    <div className={`liq-bar ${className}`} style={style}>
      <div className="liq-bar__labels">
        <span>Entry ~{formatUsd(entryPrice)}</span>
        <span>Liquidation price: {formatUsd(liquidationPrice)}</span>
      </div>
      <div className="liq-bar__track">
        <div className="liq-bar__fill" />
      </div>
      <p className="liq-bar__caption">{formatPercent(percentToLiquidation)} move to liquidation</p>
    </div>
  );
}
