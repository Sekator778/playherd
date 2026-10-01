/**
 * TypeScript port of `LiquidationCalculator.java` / `PositionSizing.java`
 * (`engine/src/main/java/crowdvsmarket/engine/margin/LiquidationCalculator.java`,
 * `engine/src/main/java/crowdvsmarket/engine/round/PositionSizing.java`), implementing exactly
 * `docs/research/hl_liquidation_math.md` §2.2/§2.6 — no more, no less. Every function here is a
 * direct translation of the Java method with the same name; keep them in lockstep by hand, field
 * for field, formula for formula, so the bet slip's client-side preview can never diverge from
 * what the server (built on the Java engine) will actually enforce.
 *
 * Per market (Р-69, `docs/research/markets_architecture.md` §8): every function takes the market's
 * `{ maxLeverage, priceDecimals }` as its last argument (default: BTC, 40 and 2 — the old
 * behaviour). A price handed to a function is the RAW price of the market (what every DTO carries,
 * `priceDecimals` fractional digits); the engine works on the LOT price instead
 * (`lotPrice = price × 10^(priceDecimals − 2)`, scale 2, Т-3), so each function converts on the way
 * in, runs the unchanged 2dp formulas with the maintenance margin rate `l = 1/(2·maxLeverage)`
 * (Т-4), and a liquidation price is converted back (÷ 10^(priceDecimals − 2), formatted with
 * `priceDecimals` digits). A position size is in lots (one lot = 10^(priceDecimals − 2) units),
 * 8 dp like `PositionSizing.SIZE_SCALE`. Money (margin, P&L, payout) is USD at 2 dp for every
 * market — P&L and liquidation do not depend on the scale (Т-3).
 *
 * BigDecimal-equivalent: `decimal.js`, cloned with `precision: 50` (comfortably more significant
 * digits than Java's `MathContext.DECIMAL128` — 34 — for every magnitude this game ever produces:
 * size at 8dp, lot price at 2dp, integer leverage 2..40) and `ROUND_HALF_UP` as the default
 * rounding mode everywhere, matching `RoundingMode.HALF_UP`. Every function below quantizes its
 * final result explicitly to money scale (2dp, USD cents) or size scale (8dp) with
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

/** `PositionSizing.SIZE_SCALE` — satoshi precision. The pre-markets port used 5 here, which is
 *  what the old BTC-only client rounded to; the server has always used 8. */
const SIZE_DP = 8;
/** `LiquidationCalculator` "money" scale — USD cents. */
const MONEY_DP = 2;
/** Scale of the lot price (`Market.toEngine`): the engine's `Candle`/`LiquidationCalculator` work in cents. */
const LOT_PRICE_DP = 2;

/**
 * The two market parameters the math depends on: `MarketDto.maxLeverage` (Т-4: `l` follows from
 * it) and `MarketDto.priceDecimals` (Т-3: `2 + lotExp`). A `MarketDto` satisfies this as is.
 */
export interface MarketParams {
  maxLeverage: number;
  priceDecimals: number;
}

/** The legacy BTC market: 40x (`l = 0.0125`), prices at 2 dp, lot = 1 BTC. */
export const BTC_MARKET: MarketParams = { maxLeverage: 40, priceDecimals: 2 };

/**
 * `MarketSpec.maintenanceMarginRate`: tier-0 maintenance margin rate `l = 1/(2*maxLeverage)`
 * (`MarginTable.build`; BTC: `1/(2*40) = 0.0125`) — see the class-level note of
 * `LiquidationCalculator.java` on why only tier0 is modeled (doc §2.1).
 */
export function maintenanceMarginRate(market: MarketParams = BTC_MARKET): Decimal {
  return new D(1).div(new D(2).mul(market.maxLeverage));
}

/** `LiquidationCalculator.MAINTENANCE_MARGIN_RATE`: the BTC rate. */
export const MAINTENANCE_MARGIN_RATE: Decimal = maintenanceMarginRate(BTC_MARKET);

function pow10(exponent: number): Decimal {
  return new D(10).pow(exponent);
}

function money(x: Decimal): Decimal {
  return x.toDecimalPlaces(MONEY_DP, Decimal.ROUND_HALF_UP);
}

function size8(x: Decimal): Decimal {
  return x.toDecimalPlaces(SIZE_DP, Decimal.ROUND_HALF_UP);
}

/** `Market.toEngine`: `raw × 10^(priceDecimals − 2)` at scale 2 — the price the engine computes with. */
export function toLotPrice(price: Decimal.Value, market: MarketParams = BTC_MARKET): Decimal {
  return new D(price).mul(pow10(market.priceDecimals - LOT_PRICE_DP)).toDecimalPlaces(LOT_PRICE_DP, Decimal.ROUND_HALF_UP);
}

/** `Market.toRaw`: `lot ÷ 10^(priceDecimals − 2)` at scale `priceDecimals` — inverse of {@link toLotPrice}. */
export function fromLotPrice(lotPrice: Decimal.Value, market: MarketParams = BTC_MARKET): Decimal {
  return new D(lotPrice).div(pow10(market.priceDecimals - LOT_PRICE_DP)).toDecimalPlaces(market.priceDecimals, Decimal.ROUND_HALF_UP);
}

/** A raw price as the contract's `PriceStr`: exactly `priceDecimals` fractional digits. */
export function priceStr(price: Decimal.Value, market: MarketParams = BTC_MARKET): string {
  return new D(price).toFixed(market.priceDecimals, Decimal.ROUND_HALF_UP);
}

/** `PositionSizing.sizeForMargin`: the size (in lots) whose initial margin, at `price`/`leverage`,
 *  rounds back to exactly `margin` — this is how a PLAY stake becomes a position size.
 *  `round8( margin × L / lotPrice )`. */
export function sizeForMargin(
  margin: Decimal.Value,
  leverage: number,
  price: Decimal.Value,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const raw = new D(margin).mul(leverage).div(toLotPrice(price, market));
  return size8(raw);
}

/** `LiquidationCalculator.initialMargin`: `round2( Q * P0 / L )`. */
export function initialMargin(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const raw = new D(size).mul(toLotPrice(entryPrice, market)).div(leverage);
  return money(raw);
}

/** `LiquidationCalculator.maintenanceMarginRequired`: `round2( Q * P0 * l )`. */
export function maintenanceMarginRequired(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const raw = new D(size).mul(toLotPrice(entryPrice, market)).mul(maintenanceMarginRate(market));
  return money(raw);
}

/**
 * `LiquidationCalculator.liquidationPrice`:
 * `round2( (Q*P0 - side*isolated_margin) / (Q*(1 - l*side)) )` on lot prices, using the
 * already-2dp-rounded `isolated_margin` from {@link initialMargin} (doc §2.6, last table row); the
 * result is converted back to the market's raw price (`priceDecimals` fractional digits — format
 * it with {@link priceStr}).
 */
export function liquidationPrice(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const isolated = initialMargin(size, entryPrice, leverage, market);
  const sizeD = new D(size);
  const entryD = toLotPrice(entryPrice, market);
  const sideD = new D(side);
  const numerator = sizeD.mul(entryD).sub(sideD.mul(isolated));
  const denominator = sizeD.mul(new D(1).sub(maintenanceMarginRate(market).mul(sideD)));
  return fromLotPrice(money(numerator.div(denominator)), market);
}

/** `LiquidationCalculator.unrealizedPnl`: `round2( side * Q * (P - P0) )` on lot prices. */
export function unrealizedPnl(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  side: Side,
  price: Decimal.Value,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const raw = new D(side).mul(size).mul(toLotPrice(price, market).sub(toLotPrice(entryPrice, market)));
  return money(raw);
}

/** `LiquidationCalculator.equityAt`: `initial_margin + unrealized_pnl(price)`, unclamped. */
export function equityAt(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  price: Decimal.Value,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const isolated = initialMargin(size, entryPrice, leverage, market);
  const pnl = unrealizedPnl(size, entryPrice, side, price, market);
  return money(isolated.add(pnl));
}

/** `LiquidationCalculator.payoutAtRoundClose`: `round2( max(0, isolated_margin + unrealized_pnl(P_close)) )`. */
export function payoutAtRoundClose(
  size: Decimal.Value,
  entryPrice: Decimal.Value,
  leverage: number,
  side: Side,
  closePrice: Decimal.Value,
  market: MarketParams = BTC_MARKET,
): Decimal {
  const equity = equityAt(size, entryPrice, leverage, side, closePrice, market);
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
  market: MarketParams = BTC_MARKET,
): Decimal {
  const equity = equityAt(size, entryPrice, leverage, side, price, market);
  const mm = maintenanceMarginRequired(size, entryPrice, market);
  return equity.div(mm);
}

/**
 * Long is liquidated within a candle iff `candle.low <= liq_price_long`; short iff
 * `candle.high >= liq_price_short` (doc §2.3). "Liquidation and round close in the same candle"
 * rule: liquidation takes priority over settlement — callers apply that by checking this
 * predicate before computing {@link payoutAtRoundClose}. Every price here is a raw price of the
 * same market, so the comparison needs no scale conversion.
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
 *  contract.ts — a plain `number`, e.g. `0.025` = 2.5%, not itself money-scaled; a ratio of two
 *  prices of the same market, so the lot scale cancels). */
export function percentToLiquidation(entryPriceUsed: Decimal.Value, liqPrice: Decimal.Value): number {
  const entry = new D(entryPriceUsed);
  const diff = entry.sub(liqPrice).abs();
  return diff.div(entry).toNumber();
}
