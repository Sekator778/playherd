import type { CSSProperties } from "react";
import type { PriceStr } from "../api/contract";
import { formatPercent, formatPrice, formatUsd, type PriceFormatMarket } from "../format";
import { HEAT_VAR, liquidationZone } from "./heat";

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

export function LiquidationBar({
  entryPrice,
  liquidationPrice,
  percentToLiquidation,
  market,
}: {
  entryPrice: PriceStr;
  liquidationPrice: PriceStr;
  /** 0..1 fraction, e.g. `0.025` = 2.5% (`LiquidationPreviewDto.percentToLiquidation`). */
  percentToLiquidation: number;
  /** The market the prices belong to, so a sub-dollar price or a probability is not squeezed into
   *  cents; omitted → plain USD with 2 decimals, as for BTC. */
  market?: PriceFormatMarket;
}) {
  const price = (p: PriceStr) => (market ? formatPrice(p, market) : formatUsd(p));
  const fraction = Math.max(0, Math.min(1, percentToLiquidation / SAFE_REFERENCE));
  const { tier, className } = liquidationZone(fraction);
  const style = {
    "--fill-pct": `${Math.round(fraction * 100)}%`,
    "--heat-color": HEAT_VAR[tier],
  } as CSSProperties;

  return (
    <div className={`liq-bar ${className}`} style={style}>
      <div className="liq-bar__labels">
        <span>Entry ~{price(entryPrice)}</span>
        <span>Liquidation price: {price(liquidationPrice)}</span>
      </div>
      <div className="liq-bar__track">
        <div className="liq-bar__fill" />
      </div>
      <p className="liq-bar__caption">{formatPercent(percentToLiquidation)} move to liquidation</p>
    </div>
  );
}

/**
 * The live distance-to-liquidation meter of a RUNNING position (live-round.md §2, "полоска до
 * ликвидации"): the bar is the room left as a share of the distance the position had at entry, coloured
 * and pulsing by zone — the same ramp the pre-bet bar and the leverage dial use — and the caption says it
 * in plain words (how far the price still has to move against the position). `thin` is the positions
 * strip's compact version, the caption left to the card around it.
 */
export function LiveLiqBar({
  roomFraction,
  pctToLiquidation,
  thin = false,
}: {
  /** 0..1, `livePosition.ts`'s `liquidationRoomFraction`. */
  roomFraction: number;
  /** The price move that would liquidate the position, as a fraction of the current price. */
  pctToLiquidation: number;
  thin?: boolean;
}) {
  const fraction = Math.max(0, Math.min(1, roomFraction));
  const { tier, className } = liquidationZone(fraction);
  const style = { "--fill-pct": `${Math.round(fraction * 100)}%`, "--heat-color": HEAT_VAR[tier] } as CSSProperties;
  return (
    <div className={`liq-bar liq-bar--live ${thin ? "liq-bar--thin" : ""} ${className}`} style={style}>
      <div
        className="liq-bar__track"
        role="meter"
        aria-label="Room left before liquidation"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(fraction * 100)}
        aria-valuetext={`${formatPercent(pctToLiquidation)} to liquidation`}
      >
        <div className="liq-bar__fill" />
      </div>
      {!thin && <p className="liq-bar__caption">{formatPercent(pctToLiquidation)} to liquidation</p>}
    </div>
  );
}
