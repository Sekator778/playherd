/**
 * TypeScript port of `LiquidationCalculator.java` / `PositionSizing.java`
 * (`engine/src/main/java/crowdvsmarket/engine/margin/LiquidationCalculator.java`,
 * `engine/src/main/java/crowdvsmarket/engine/round/PositionSizing.java`), implementing exactly
 * `docs/research/hl_liquidation_math.md` §2.2/§2.6 — no more, no less. Every function here is a
 * direct translation of the Java method with the same name; keep them in lockstep by hand, field
 * for field, formula for formula, so the bet slip's client-side preview can never diverge from
 * what the server (built on the Java engine) will actually enforce.
 *
 * BigDecimal-equivalent: `decimal.js`, cloned with `precision: 50` (comfortably more significant
 * digits than Java's `MathContext.DECIMAL128` — 34 — for every magnitude this game ever produces:
 * BTC size at 5dp, USD price at 2dp, integer leverage 2..40) and `ROUND_HALF_UP` as the default
 * rounding mode everywhere, matching `RoundingMode.HALF_UP`. Every function below quantizes its
 * final result explicitly to money scale (2dp, USD cents) or size scale (5dp, BTC) with
 * `ROUND_HALF_UP` — exactly where the Java method does — and nowhere else, so the "round only the
 * final result of each formula" rule (doc §2.6, last table row) holds in both languages.
 */
import Decimal from "decimal.js";

const D = Decimal.clone({ precision: 50, rounding: Decimal.ROUND_HALF_UP });
export type Dec = Decimal;

/** `Side.java`: `side = 1 for long and -1 for short` (hl_liquidation_math.md §1.6, quoted verbatim). */
export type Side = 1 | -1;
export const LONG: Side = 1;
export const SHORT: Side = -1;

/** `PositionSizing.SIZE_SCALE` — satoshi precision. */
const SIZE_DP = 5;
/** `LiquidationCalculator` "money" scale — USD cents. */
const MONEY_DP = 2;

/**
 * `LiquidationCalculator.MAINTENANCE_MARGIN_RATE`: BTC tier0 maintenance margin rate
 * `l = 1/(2*40) = 0.0125` (`MarginTable.BTC.tier(0)`) — see the class-level note on why only
 * tier0 is modeled (doc §2.1).
 */
export const MAINTENANCE_MARGIN_RATE: Decimal = new D(1).div(new D(2).mul(40));

function money(x: Decimal): Decimal {
  return x.toDecimalPlaces(MONEY_DP, Decimal.ROUND_HALF_UP);
}

function size5(x: Decimal): Decimal {
  return x.toDecimalPlaces(SIZE_DP, Decimal.ROUND_HALF_UP);
}

/** `PositionSizing.sizeForMargin`: the BTC size whose initial margin, at `price`/`leverage`,
 *  rounds back to exactly `margin` — this is how a PLAY stake becomes a position size. */
export function sizeForMargin(margin: Decimal.Value, leverage: number, price: Decimal.Value): Decimal {
  const raw = new D(margin).mul(leverage).div(price);
  return size5(raw);
}

/** `LiquidationCalculator.initialMargin`: `round2( Q * P0 / L )`. */
export function initialMargin(size: Decimal.Value, entryPrice: Decimal.Value, leverage: number): Decimal {
  const raw = new D(size).mul(entryPrice).div(leverage);
  return money(raw);
}

/** `LiquidationCalculator.maintenanceMarginRequired`: `round2( Q * P0 * l )`. */
export function maintenanceMarginRequired(size: Decimal.Value, entryPrice: Decimal.Value): Decimal {
  const raw = new D(size).mul(entryPrice).mul(MAINTENANCE_MARGIN_RATE);
  return money(raw);
}

/**
 * `LiquidationCalculator.liquidationPrice`:
 * `round2( (Q*P0 - side*isolated_margin) / (Q*(1 - l*side)) )`, using the already-2dp-rounded
 * `isolated_margin` from {@link initialMargin} (doc §2.6, last table row).
 */
export function liquidationPrice(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
): Decimal {
  const isolated = initialMargin(size, entryPrice, leverage);
  const sizeD = new D(size);
  const entryD = new D(entryPrice);
  const sideD = new D(side);
  const numerator = sizeD.mul(entryD).sub(sideD.mul(isolated));
  const denominator = sizeD.mul(new D(1).sub(MAINTENANCE_MARGIN_RATE.mul(sideD)));
  return money(numerator.div(denominator));
}

/** `LiquidationCalculator.unrealizedPnl`: `round2( side * Q * (P - P0) )`. */
export function unrealizedPnl(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  side: Side,
  price: Decimal.Value,
): Decimal {
  const raw = new D(side).mul(size).mul(new D(price).sub(entryPrice));
  return money(raw);
}

/** `LiquidationCalculator.equityAt`: `initial_margin + unrealized_pnl(price)`, unclamped. */
export function equityAt(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  price: Decimal.Value,
): Decimal {
  const isolated = initialMargin(size, entryPrice, leverage);
  const pnl = unrealizedPnl(size, entryPrice, side, price);
  return money(isolated.add(pnl));
}

/** `LiquidationCalculator.payoutAtRoundClose`: `round2( max(0, isolated_margin + unrealized_pnl(P_close)) )`. */
export function payoutAtRoundClose(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  closePrice: Decimal.Value,
): Decimal {
  const equity = equityAt(size, entryPrice, leverage, side, closePrice);
  return Decimal.max(equity, money(new D(0)));
}

/**
 * `LiquidationCalculator.payoutOnLiquidation`: always `0.00` — product decision Р-7
 * (`docs/product/decisions.md`): on liquidation the player loses the entire stake. The
 * liquidation *price* itself is unaffected and still follows Hyperliquid's formula exactly.
 */
export function payoutOnLiquidation(): Decimal {
  return money(new D(0));
}

/** `LiquidationCalculator.marginRatio`: `equityAt(price) / maintenance_margin_required` — 1.0
 *  exactly at the liquidation price; not itself a spec formula, a display/diagnostic ratio. */
export function marginRatio(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  price: Decimal.Value,
): Decimal {
  const equity = equityAt(size, entryPrice, leverage, side, price);
  const mm = maintenanceMarginRequired(size, entryPrice);
  return equity.div(mm);
}

/**
 * Long is liquidated within a candle iff `candle.low <= liq_price_long`; short iff
 * `candle.high >= liq_price_short` (doc §2.3). "Liquidation and round close in the same candle"
 * rule: liquidation takes priority over settlement — callers apply that by checking this
 * predicate before computing {@link payoutAtRoundClose}.
 */
export function isLiquidatedInRange(
  side: Side,
  liqPrice: Decimal.Value,
  candleLow: Decimal.Value,
  candleHigh: Decimal.Value,
): boolean {
  return side === LONG ? new D(candleLow).lte(liqPrice) : new D(candleHigh).gte(liqPrice);
}

/** Convenience: `%` adverse move from `entryPriceUsed` to `liquidationPrice` — the "% move to
 *  liquidation" figure shown before a bet is placed (Р-33, `LiquidationPreviewDto.percentToLiquidation`,
 *  contract.ts — a plain `number`, e.g. `0.025` = 2.5%, not itself money-scaled). */
export function percentToLiquidation(entryPriceUsed: Decimal.Value, liqPrice: Decimal.Value): number {
  const entry = new D(entryPriceUsed);
  const diff = entry.sub(liqPrice).abs();
  return diff.div(entry).toNumber();
}
