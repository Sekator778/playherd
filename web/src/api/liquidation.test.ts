import { describe, expect, it } from "vitest";
import {
  BTC_MARKET,
  LONG,
  SHORT,
  fromLotPrice,
  initialMargin,
  isLiquidatedInRange,
  liquidationPrice,
  maintenanceMarginRate,
  maintenanceMarginRequired,
  payoutAtRoundClose,
  payoutOnLiquidation,
  percentToLiquidation,
  priceStr,
  sizeForMargin,
  toLotPrice,
  unrealizedPnl,
  type MarketParams,
  type Side,
} from "./liquidation";

/**
 * Every row of the golden-vector table in `docs/research/hl_liquidation_math.md` §3 (20 rows:
 * long/short × leverage {2,3,10,25,40} × entry price {83451.00, 50000.00}), transcribed verbatim
 * so this test can never silently drift from the doc. `size_btc=0.10000` for every row (doc
 * §2.7's `STANDARD_SIZE_BTC`). These are the exact numbers the Java engine's own tests are
 * expected to reproduce too — TS and Java are proven never to diverge by both matching this table.
 *
 * Size scale: the port now rounds a position size to 8 dp like `PositionSizing.SIZE_SCALE` (it
 * used 5 before markets, Р-69). No row of this table depended on that: every row hands its size
 * in as the literal `0.10000`, so nothing here needed a recomputed expected value; the 8 dp are
 * exercised by the per-market vectors further down.
 */
interface GoldenRow {
  side: Side;
  entryPrice: string;
  leverage: number;
  sizeBtc: string;
  isolatedMarginUsd: string;
  liquidationPrice: string;
  maintenanceMarginUsd: string;
  payoutOnLiquidationUsd: string;
  closePrice: string;
  payoutAtRoundCloseUsd: string;
}

const GOLDEN_VECTORS: GoldenRow[] = [
  { side: LONG, entryPrice: "83451.00", leverage: 2, sizeBtc: "0.10000", isolatedMarginUsd: "4172.55", liquidationPrice: "42253.67", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "4827.45" },
  { side: LONG, entryPrice: "83451.00", leverage: 3, sizeBtc: "0.10000", isolatedMarginUsd: "2781.70", liquidationPrice: "56338.23", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "3436.60" },
  { side: LONG, entryPrice: "83451.00", leverage: 10, sizeBtc: "0.10000", isolatedMarginUsd: "834.51", liquidationPrice: "76056.61", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "1489.41" },
  { side: LONG, entryPrice: "83451.00", leverage: 25, sizeBtc: "0.10000", isolatedMarginUsd: "333.80", liquidationPrice: "81127.09", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "988.70" },
  { side: LONG, entryPrice: "83451.00", leverage: 40, sizeBtc: "0.10000", isolatedMarginUsd: "208.63", liquidationPrice: "82394.63", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "863.53" },
  { side: SHORT, entryPrice: "83451.00", leverage: 2, sizeBtc: "0.10000", isolatedMarginUsd: "4172.55", liquidationPrice: "123631.11", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "3517.65" },
  { side: SHORT, entryPrice: "83451.00", leverage: 3, sizeBtc: "0.10000", isolatedMarginUsd: "2781.70", liquidationPrice: "109894.32", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "2126.80" },
  { side: SHORT, entryPrice: "83451.00", leverage: 10, sizeBtc: "0.10000", isolatedMarginUsd: "834.51", liquidationPrice: "90662.81", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "179.61" },
  { side: SHORT, entryPrice: "83451.00", leverage: 25, sizeBtc: "0.10000", isolatedMarginUsd: "333.80", liquidationPrice: "85717.53", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "0.00" },
  { side: SHORT, entryPrice: "83451.00", leverage: 40, sizeBtc: "0.10000", isolatedMarginUsd: "208.63", liquidationPrice: "84481.28", maintenanceMarginUsd: "104.31", payoutOnLiquidationUsd: "0.00", closePrice: "90000.00", payoutAtRoundCloseUsd: "0.00" },
  { side: LONG, entryPrice: "50000.00", leverage: 2, sizeBtc: "0.10000", isolatedMarginUsd: "2500.00", liquidationPrice: "25316.46", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "2000.00" },
  { side: LONG, entryPrice: "50000.00", leverage: 3, sizeBtc: "0.10000", isolatedMarginUsd: "1666.67", liquidationPrice: "33755.24", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "1166.67" },
  { side: LONG, entryPrice: "50000.00", leverage: 10, sizeBtc: "0.10000", isolatedMarginUsd: "500.00", liquidationPrice: "45569.62", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "0.00" },
  { side: LONG, entryPrice: "50000.00", leverage: 25, sizeBtc: "0.10000", isolatedMarginUsd: "200.00", liquidationPrice: "48607.59", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "0.00" },
  { side: LONG, entryPrice: "50000.00", leverage: 40, sizeBtc: "0.10000", isolatedMarginUsd: "125.00", liquidationPrice: "49367.09", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "0.00" },
  { side: SHORT, entryPrice: "50000.00", leverage: 2, sizeBtc: "0.10000", isolatedMarginUsd: "2500.00", liquidationPrice: "74074.07", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "3000.00" },
  { side: SHORT, entryPrice: "50000.00", leverage: 3, sizeBtc: "0.10000", isolatedMarginUsd: "1666.67", liquidationPrice: "65843.65", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "2166.67" },
  { side: SHORT, entryPrice: "50000.00", leverage: 10, sizeBtc: "0.10000", isolatedMarginUsd: "500.00", liquidationPrice: "54320.99", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "1000.00" },
  { side: SHORT, entryPrice: "50000.00", leverage: 25, sizeBtc: "0.10000", isolatedMarginUsd: "200.00", liquidationPrice: "51358.02", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "700.00" },
  { side: SHORT, entryPrice: "50000.00", leverage: 40, sizeBtc: "0.10000", isolatedMarginUsd: "125.00", liquidationPrice: "50617.28", maintenanceMarginUsd: "62.50", payoutOnLiquidationUsd: "0.00", closePrice: "45000.00", payoutAtRoundCloseUsd: "625.00" },
];

describe("liquidation math golden vectors (hl_liquidation_math.md §3)", () => {
  it.each(GOLDEN_VECTORS)("$side entry=$entryPrice lev=$leverage: isolated margin", (row) => {
    expect(initialMargin(row.sizeBtc, row.entryPrice, row.leverage).toFixed(2)).toBe(row.isolatedMarginUsd);
  });

  it.each(GOLDEN_VECTORS)("$side entry=$entryPrice lev=$leverage: liquidation price", (row) => {
    expect(liquidationPrice(row.sizeBtc, row.entryPrice, row.leverage, row.side).toFixed(2)).toBe(
      row.liquidationPrice,
    );
  });

  it.each(GOLDEN_VECTORS)("$side entry=$entryPrice lev=$leverage: maintenance margin", (row) => {
    expect(maintenanceMarginRequired(row.sizeBtc, row.entryPrice).toFixed(2)).toBe(row.maintenanceMarginUsd);
  });

  it.each(GOLDEN_VECTORS)("$side entry=$entryPrice lev=$leverage: payout on liquidation is always 0 (Р-7)", (row) => {
    expect(payoutOnLiquidation().toFixed(2)).toBe(row.payoutOnLiquidationUsd);
  });

  it.each(GOLDEN_VECTORS)("$side entry=$entryPrice lev=$leverage: payout at round close", (row) => {
    expect(
      payoutAtRoundClose(row.sizeBtc, row.entryPrice, row.leverage, row.side, row.closePrice).toFixed(2),
    ).toBe(row.payoutAtRoundCloseUsd);
  });

  it("maintenance margin does not depend on leverage for a fixed entry price (doc §3 observation)", () => {
    for (const entry of ["83451.00", "50000.00"]) {
      const rows = GOLDEN_VECTORS.filter((r) => r.entryPrice === entry);
      const distinct = new Set(rows.map((r) => r.maintenanceMarginUsd));
      expect(distinct.size).toBe(1);
    }
  });
});

/**
 * Sample of the historical scenarios (doc §5, `hl_btc_1d.csv` "biggest daily drops" table) —
 * cross-checks {@link isLiquidatedInRange} against the doc's stated liquidated-leverage ranges
 * for a handful of candles, independent of the golden-vector table above.
 */
describe("isLiquidatedInRange against historical scenarios (hl_liquidation_math.md §5)", () => {
  const STANDARD_SIZE_BTC = "0.10000";

  interface Scenario {
    utc: string;
    open: string;
    high: string;
    low: string;
    longLiquidatedFrom: number | null; // inclusive lower bound of the "N-40" range, or null for "none"
    shortLiquidatedFrom: number | null;
  }

  const scenarios: Scenario[] = [
    { utc: "2021-05-19 daily drop", open: "42882.5", high: "43615.9", low: "28688.0", longLiquidatedFrom: 3, shortLiquidatedFrom: 34 },
    { utc: "2021-12-04 daily drop", open: "53611.9", high: "53889.7", low: "40888.9", longLiquidatedFrom: 5, shortLiquidatedFrom: null },
    { utc: "2022-11-08 daily drop", open: "20578.5", high: "20685.0", low: "16830.0", longLiquidatedFrom: 6, shortLiquidatedFrom: null },
    { utc: "2021-07-26 daily rise", open: "35370.7", high: "48168.6", low: "35185.5", longLiquidatedFrom: null, shortLiquidatedFrom: 3 },
    { utc: "2021-01-02 daily rise", open: "29337.2", high: "33480.0", low: "28958.2", longLiquidatedFrom: 40, shortLiquidatedFrom: 7 },
  ];

  for (const s of scenarios) {
    it(`${s.utc}: long liquidated leverages match "${s.longLiquidatedFrom ?? "none"}-40"`, () => {
      for (let lev = 2; lev <= 40; lev++) {
        const liq = liquidationPrice(STANDARD_SIZE_BTC, s.open, lev, LONG);
        const expected = s.longLiquidatedFrom !== null && lev >= s.longLiquidatedFrom;
        expect(isLiquidatedInRange(LONG, liq, s.low, s.high)).toBe(expected);
      }
    });

    it(`${s.utc}: short liquidated leverages match "${s.shortLiquidatedFrom ?? "none"}-40"`, () => {
      for (let lev = 2; lev <= 40; lev++) {
        const liq = liquidationPrice(STANDARD_SIZE_BTC, s.open, lev, SHORT);
        const expected = s.shortLiquidatedFrom !== null && lev >= s.shortLiquidatedFrom;
        expect(isLiquidatedInRange(SHORT, liq, s.low, s.high)).toBe(expected);
      }
    });
  }
});

/**
 * Per-market vectors (Р-69, `docs/research/markets_architecture.md` §8): the same formulas on the
 * lot price (`price × 10^(priceDecimals − 2)`), size rounded to 8 dp, `l = 1/(2·maxLeverage)`,
 * liquidation price converted back to the market's raw price. Markets: BTC 40x/2 dp (the stake-
 * derived 8 dp sizes), 25x/2 dp (price 2694.40), 20x/3 dp (150.123), 10x/6 dp (an `ODDS` market,
 * 0.523000) and 20x/5 dp (2.51230).
 *
 * Every number below was computed independently of this port with python3 `decimal` (context
 * precision 34 = Java's `MathContext.DECIMAL128`, `ROUND_HALF_UP` at the final step of each
 * formula, same order of operations as `LiquidationCalculator.java`/`PositionSizing.java`; the
 * 2 dp `isolated_margin` rounded before it enters `liquidation_price`). The script, whose output
 * is the `PER_MARKET_ROWS` list of this file, is:
 *
 * <pre>
 * from decimal import Decimal as D, ROUND_HALF_UP, getcontext
 *
 * getcontext().prec = 34  # Java MathContext.DECIMAL128: 34 digits, intermediates HALF_EVEN (python default)
 *
 *
 * def q(x, places):
 *     return x.quantize(D(1).scaleb(-places), rounding=ROUND_HALF_UP)  # the final step of every formula
 *
 *
 * def lot_price(price, pd):  # Market.toEngine: raw * 10^(priceDecimals-2), scale 2
 *     return q(D(price).scaleb(pd - 2), 2)
 *
 *
 * def raw_price(lot, pd):  # Market.toRaw: lot / 10^(priceDecimals-2), scale priceDecimals
 *     return q(lot.scaleb(-(pd - 2)), pd)
 *
 *
 * def size_for_margin(margin, lev, lot):  # PositionSizing.sizeForMargin: SIZE_SCALE 8
 *     return q(D(margin) * D(lev) / lot, 8)
 *
 *
 * def initial_margin(size, lot, lev):
 *     return q(size * lot / D(lev), 2)
 *
 *
 * def maintenance_margin(size, lot, rate):
 *     return q(size * lot * rate, 2)
 *
 *
 * def liquidation_lot(size, lot, lev, side, rate):  # side: +1 long, -1 short
 *     im = initial_margin(size, lot, lev)  # rounded to 2dp BEFORE it enters the formula (section 2.6)
 *     return q((size * lot - D(side) * im) / (size * (D(1) - rate * D(side))), 2)
 *
 *
 * def pnl(size, side, lot_entry, lot_px):
 *     return q(D(side) * size * (lot_px - lot_entry), 2)
 *
 *
 * def payout(size, lot, lev, side, lot_px):  # payout_at_round_close: max(0, im + pnl)
 *     return max(q(initial_margin(size, lot, lev) + pnl(size, side, lot, lot_px), 2), D("0.00"))
 *
 *
 * # (label, maxLeverage, priceDecimals, entry price, close price, stakes, leverages)
 * MARKETS = [
 *     ("BTC 40x", 40, 2, "83451.00", "84000.00", ("100.000", "2500.000"), (2, 10, 40)),
 *     ("ETH-like 25x", 25, 2, "2694.40", "2750.00", ("500.000", "1234.567"), (2, 10, 25)),
 *     ("SOL-like 20x", 20, 3, "150.123", "148.500", ("100.000", "777.777"), (2, 8, 20)),
 *     ("ODDS 10x", 10, 6, "0.523000", "0.549000", ("250.000", "999.999"), (2, 5, 10)),
 *     ("XRP-like 20x", 20, 5, "2.51230", "2.60000", ("100.000", "1500.000"), (2, 8, 20)),
 * ]
 *
 * for label, max_lev, pd, entry, close, stakes, levs in MARKETS:
 *     rate = D(1) / (D(2) * D(max_lev))  # 1 / (2 * maxLeverage), section 1.4
 *     lot, lot_close = lot_price(entry, pd), lot_price(close, pd)
 *     print("  // %s: lot price %s, close lot price %s, l = %s" % (label, lot, lot_close, rate))
 *     for stake in stakes:
 *         for lev in levs:
 *             size = size_for_margin(stake, lev, lot)
 *             for name, side in (("LONG", 1), ("SHORT", -1)):
 *                 print(
 *                     '  { maxLeverage: %d, priceDecimals: %d, entry: "%s", close: "%s", stake: "%s", leverage: %d, side: %s, '
 *                     'size: "%s", initialMargin: "%s", maintenanceMargin: "%s", liquidationPrice: "%s", pnl: "%s", payout: "%s" },'
 *                     % (
 *                         max_lev, pd, entry, close, stake, lev, "LONG" if side == 1 else "SHORT", size,
 *                         initial_margin(size, lot, lev), maintenance_margin(size, lot, rate),
 *                         raw_price(liquidation_lot(size, lot, lev, side, rate), pd),
 *                         pnl(size, side, lot, lot_close), payout(size, lot, lev, side, lot_close),
 *                     )
 *                 )
 * </pre>
 */
interface MarketRow {
  maxLeverage: number;
  priceDecimals: number;
  entry: string;
  close: string;
  stake: string;
  leverage: number;
  side: Side;
  size: string;
  initialMargin: string;
  maintenanceMargin: string;
  liquidationPrice: string;
  pnl: string;
  payout: string;
}

const PER_MARKET_ROWS: MarketRow[] = [
  // BTC 40x: lot price 83451.00, close lot price 84000.00, l = 0.0125
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 2, side: LONG, size: "0.00239662", initialMargin: "100.00", maintenanceMargin: "2.50", liquidationPrice: "42253.74", pnl: "1.32", payout: "101.32" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 2, side: SHORT, size: "0.00239662", initialMargin: "100.00", maintenanceMargin: "2.50", liquidationPrice: "123631.04", pnl: "-1.32", payout: "98.68" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 10, side: LONG, size: "0.01198308", initialMargin: "100.00", maintenanceMargin: "12.50", liquidationPrice: "76056.61", pnl: "6.58", payout: "106.58" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 10, side: SHORT, size: "0.01198308", initialMargin: "100.00", maintenanceMargin: "12.50", liquidationPrice: "90662.81", pnl: "-6.58", payout: "93.42" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 40, side: LONG, size: "0.04793232", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "82394.66", pnl: "26.31", payout: "126.31" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "100.000", leverage: 40, side: SHORT, size: "0.04793232", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "84481.26", pnl: "-26.31", payout: "73.69" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 2, side: LONG, size: "0.05991540", initialMargin: "2500.00", maintenanceMargin: "62.50", liquidationPrice: "42253.67", pnl: "32.89", payout: "2532.89" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 2, side: SHORT, size: "0.05991540", initialMargin: "2500.00", maintenanceMargin: "62.50", liquidationPrice: "123631.11", pnl: "-32.89", payout: "2467.11" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 10, side: LONG, size: "0.29957700", initialMargin: "2500.00", maintenanceMargin: "312.50", liquidationPrice: "76056.61", pnl: "164.47", payout: "2664.47" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 10, side: SHORT, size: "0.29957700", initialMargin: "2500.00", maintenanceMargin: "312.50", liquidationPrice: "90662.81", pnl: "-164.47", payout: "2335.53" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 40, side: LONG, size: "1.19830799", initialMargin: "2500.00", maintenanceMargin: "1250.00", liquidationPrice: "82394.66", pnl: "657.87", payout: "3157.87" },
  { maxLeverage: 40, priceDecimals: 2, entry: "83451.00", close: "84000.00", stake: "2500.000", leverage: 40, side: SHORT, size: "1.19830799", initialMargin: "2500.00", maintenanceMargin: "1250.00", liquidationPrice: "84481.26", pnl: "-657.87", payout: "1842.13" },
  // ETH-like 25x: lot price 2694.40, close lot price 2750.00, l = 0.02
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 2, side: LONG, size: "0.37114014", initialMargin: "500.00", maintenanceMargin: "20.00", liquidationPrice: "1374.69", pnl: "20.64", payout: "520.64" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 2, side: SHORT, size: "0.37114014", initialMargin: "500.00", maintenanceMargin: "20.00", liquidationPrice: "3962.35", pnl: "-20.64", payout: "479.36" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 10, side: LONG, size: "1.85570071", initialMargin: "500.00", maintenanceMargin: "100.00", liquidationPrice: "2474.45", pnl: "103.18", payout: "603.18" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 10, side: SHORT, size: "1.85570071", initialMargin: "500.00", maintenanceMargin: "100.00", liquidationPrice: "2905.73", pnl: "-103.18", payout: "396.82" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 25, side: LONG, size: "4.63925178", initialMargin: "500.00", maintenanceMargin: "250.00", liquidationPrice: "2639.41", pnl: "257.94", payout: "757.94" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "500.000", leverage: 25, side: SHORT, size: "4.63925178", initialMargin: "500.00", maintenanceMargin: "250.00", liquidationPrice: "2747.23", pnl: "-257.94", payout: "242.06" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 2, side: LONG, size: "0.91639474", initialMargin: "1234.57", maintenanceMargin: "49.38", liquidationPrice: "1374.69", pnl: "50.95", payout: "1285.52" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 2, side: SHORT, size: "0.91639474", initialMargin: "1234.57", maintenanceMargin: "49.38", liquidationPrice: "3962.36", pnl: "-50.95", payout: "1183.62" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 10, side: LONG, size: "4.58197372", initialMargin: "1234.57", maintenanceMargin: "246.91", liquidationPrice: "2474.45", pnl: "254.76", payout: "1489.33" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 10, side: SHORT, size: "4.58197372", initialMargin: "1234.57", maintenanceMargin: "246.91", liquidationPrice: "2905.73", pnl: "-254.76", payout: "979.81" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 25, side: LONG, size: "11.45493431", initialMargin: "1234.57", maintenanceMargin: "617.28", liquidationPrice: "2639.41", pnl: "636.89", payout: "1871.46" },
  { maxLeverage: 25, priceDecimals: 2, entry: "2694.40", close: "2750.00", stake: "1234.567", leverage: 25, side: SHORT, size: "11.45493431", initialMargin: "1234.57", maintenanceMargin: "617.28", liquidationPrice: "2747.23", pnl: "-636.89", payout: "597.68" },
  // SOL-like 20x: lot price 1501.23, close lot price 1485.00, l = 0.025
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 2, side: LONG, size: "0.13322409", initialMargin: "100.00", maintenanceMargin: "5.00", liquidationPrice: "76.986", pnl: "-2.16", payout: "97.84" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 2, side: SHORT, size: "0.13322409", initialMargin: "100.00", maintenanceMargin: "5.00", liquidationPrice: "219.692", pnl: "2.16", payout: "102.16" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 8, side: LONG, size: "0.53289636", initialMargin: "100.00", maintenanceMargin: "20.00", liquidationPrice: "134.726", pnl: "-8.65", payout: "91.35" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 8, side: SHORT, size: "0.53289636", initialMargin: "100.00", maintenanceMargin: "20.00", liquidationPrice: "164.769", pnl: "8.65", payout: "108.65" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 20, side: LONG, size: "1.33224090", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "146.274", pnl: "-21.62", payout: "78.38" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "100.000", leverage: 20, side: SHORT, size: "1.33224090", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "153.785", pnl: "21.62", payout: "121.62" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 2, side: LONG, size: "1.03618633", initialMargin: "777.78", maintenanceMargin: "38.89", liquidationPrice: "76.986", pnl: "-16.82", payout: "760.96" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 2, side: SHORT, size: "1.03618633", initialMargin: "777.78", maintenanceMargin: "38.89", liquidationPrice: "219.692", pnl: "16.82", payout: "794.60" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 8, side: LONG, size: "4.14474531", initialMargin: "777.78", maintenanceMargin: "155.56", liquidationPrice: "134.726", pnl: "-67.27", payout: "710.51" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 8, side: SHORT, size: "4.14474531", initialMargin: "777.78", maintenanceMargin: "155.56", liquidationPrice: "164.769", pnl: "67.27", payout: "845.05" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 20, side: LONG, size: "10.36186327", initialMargin: "777.78", maintenanceMargin: "388.89", liquidationPrice: "146.274", pnl: "-168.17", payout: "609.61" },
  { maxLeverage: 20, priceDecimals: 3, entry: "150.123", close: "148.500", stake: "777.777", leverage: 20, side: SHORT, size: "10.36186327", initialMargin: "777.78", maintenanceMargin: "388.89", liquidationPrice: "153.785", pnl: "168.17", payout: "945.95" },
  // ODDS 10x: lot price 5230.00, close lot price 5490.00, l = 0.05
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 2, side: LONG, size: "0.09560229", initialMargin: "250.00", maintenanceMargin: "25.00", liquidationPrice: "0.275263", pnl: "24.86", payout: "274.86" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 2, side: SHORT, size: "0.09560229", initialMargin: "250.00", maintenanceMargin: "25.00", liquidationPrice: "0.747143", pnl: "-24.86", payout: "225.14" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 5, side: LONG, size: "0.23900574", initialMargin: "250.00", maintenanceMargin: "62.50", liquidationPrice: "0.440421", pnl: "62.14", payout: "312.14" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 5, side: SHORT, size: "0.23900574", initialMargin: "250.00", maintenanceMargin: "62.50", liquidationPrice: "0.597714", pnl: "-62.14", payout: "187.86" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 10, side: LONG, size: "0.47801147", initialMargin: "250.00", maintenanceMargin: "125.00", liquidationPrice: "0.495474", pnl: "124.28", payout: "374.28" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "250.000", leverage: 10, side: SHORT, size: "0.47801147", initialMargin: "250.00", maintenanceMargin: "125.00", liquidationPrice: "0.547905", pnl: "-124.28", payout: "125.72" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 2, side: LONG, size: "0.38240880", initialMargin: "1000.00", maintenanceMargin: "100.00", liquidationPrice: "0.275263", pnl: "99.43", payout: "1099.43" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 2, side: SHORT, size: "0.38240880", initialMargin: "1000.00", maintenanceMargin: "100.00", liquidationPrice: "0.747143", pnl: "-99.43", payout: "900.57" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 5, side: LONG, size: "0.95602199", initialMargin: "1000.00", maintenanceMargin: "250.00", liquidationPrice: "0.440421", pnl: "248.57", payout: "1248.57" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 5, side: SHORT, size: "0.95602199", initialMargin: "1000.00", maintenanceMargin: "250.00", liquidationPrice: "0.597714", pnl: "-248.57", payout: "751.43" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 10, side: LONG, size: "1.91204398", initialMargin: "1000.00", maintenanceMargin: "500.00", liquidationPrice: "0.495474", pnl: "497.13", payout: "1497.13" },
  { maxLeverage: 10, priceDecimals: 6, entry: "0.523000", close: "0.549000", stake: "999.999", leverage: 10, side: SHORT, size: "1.91204398", initialMargin: "1000.00", maintenanceMargin: "500.00", liquidationPrice: "0.547905", pnl: "-497.13", payout: "502.87" },
  // XRP-like 20x: lot price 2512.30, close lot price 2600.00, l = 0.025
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 2, side: LONG, size: "0.07960833", initialMargin: "100.00", maintenanceMargin: "5.00", liquidationPrice: "1.28836", pnl: "6.98", payout: "106.98" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 2, side: SHORT, size: "0.07960833", initialMargin: "100.00", maintenanceMargin: "5.00", liquidationPrice: "3.67654", pnl: "-6.98", payout: "93.02" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 8, side: LONG, size: "0.31843331", initialMargin: "100.00", maintenanceMargin: "20.00", liquidationPrice: "2.25463", pnl: "27.93", payout: "127.93" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 8, side: SHORT, size: "0.31843331", initialMargin: "100.00", maintenanceMargin: "20.00", liquidationPrice: "2.75740", pnl: "-27.93", payout: "72.07" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 20, side: LONG, size: "0.79608327", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "2.44788", pnl: "69.82", payout: "169.82" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "100.000", leverage: 20, side: SHORT, size: "0.79608327", initialMargin: "100.00", maintenanceMargin: "50.00", liquidationPrice: "2.57358", pnl: "-69.82", payout: "30.18" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 2, side: LONG, size: "1.19412491", initialMargin: "1500.00", maintenanceMargin: "75.00", liquidationPrice: "1.28836", pnl: "104.72", payout: "1604.72" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 2, side: SHORT, size: "1.19412491", initialMargin: "1500.00", maintenanceMargin: "75.00", liquidationPrice: "3.67654", pnl: "-104.72", payout: "1395.28" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 8, side: LONG, size: "4.77649962", initialMargin: "1500.00", maintenanceMargin: "300.00", liquidationPrice: "2.25463", pnl: "418.90", payout: "1918.90" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 8, side: SHORT, size: "4.77649962", initialMargin: "1500.00", maintenanceMargin: "300.00", liquidationPrice: "2.75740", pnl: "-418.90", payout: "1081.10" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 20, side: LONG, size: "11.94124905", initialMargin: "1500.00", maintenanceMargin: "750.00", liquidationPrice: "2.44788", pnl: "1047.25", payout: "2547.25" },
  { maxLeverage: 20, priceDecimals: 5, entry: "2.51230", close: "2.60000", stake: "1500.000", leverage: 20, side: SHORT, size: "11.94124905", initialMargin: "1500.00", maintenanceMargin: "750.00", liquidationPrice: "2.57358", pnl: "-1047.25", payout: "452.75" },
];

function marketOf(row: MarketRow): MarketParams {
  return { maxLeverage: row.maxLeverage, priceDecimals: row.priceDecimals };
}

describe("per-market vectors (python3 decimal, same rule as the Java engine)", () => {
  const label = (r: MarketRow) =>
    `${r.maxLeverage}x/${r.priceDecimals}dp entry=${r.entry} stake=${r.stake} lev=${r.leverage} ${r.side === LONG ? "LONG" : "SHORT"}`;

  it.each(PER_MARKET_ROWS)("size from stake: $stake PLAY x$leverage at $entry -> $size lots (8 dp)", (row) => {
    expect(sizeForMargin(row.stake, row.leverage, row.entry, marketOf(row)).toFixed(8)).toBe(row.size);
  });

  it.each(PER_MARKET_ROWS.map((r) => [label(r), r] as const))("%s: initial margin = the stake, to the cent", (_name, row) => {
    expect(initialMargin(row.size, row.entry, row.leverage, marketOf(row)).toFixed(2)).toBe(row.initialMargin);
  });

  it.each(PER_MARKET_ROWS.map((r) => [label(r), r] as const))("%s: maintenance margin", (_name, row) => {
    expect(maintenanceMarginRequired(row.size, row.entry, marketOf(row)).toFixed(2)).toBe(row.maintenanceMargin);
  });

  it.each(PER_MARKET_ROWS.map((r) => [label(r), r] as const))("%s: liquidation price (raw units, priceDecimals digits)", (_name, row) => {
    const market = marketOf(row);
    const liq = liquidationPrice(row.size, row.entry, row.leverage, row.side, market);
    expect(priceStr(liq, market)).toBe(row.liquidationPrice);
  });

  it.each(PER_MARKET_ROWS.map((r) => [label(r), r] as const))("%s: unrealized P&L at the close price", (_name, row) => {
    expect(unrealizedPnl(row.size, row.entry, row.side, row.close, marketOf(row)).toFixed(2)).toBe(row.pnl);
  });

  it.each(PER_MARKET_ROWS.map((r) => [label(r), r] as const))("%s: payout at round close", (_name, row) => {
    expect(
      payoutAtRoundClose(row.size, row.entry, row.leverage, row.side, row.close, marketOf(row)).toFixed(2),
    ).toBe(row.payout);
  });
});

describe("market parameters", () => {
  it("l = 1/(2*maxLeverage): 0.0125 for BTC, 0.02 for 25x, 0.025 for 20x, 0.05 for 10x", () => {
    expect(maintenanceMarginRate(BTC_MARKET).toString()).toBe("0.0125");
    expect(maintenanceMarginRate({ maxLeverage: 25, priceDecimals: 2 }).toString()).toBe("0.02");
    expect(maintenanceMarginRate({ maxLeverage: 20, priceDecimals: 3 }).toString()).toBe("0.025");
    expect(maintenanceMarginRate({ maxLeverage: 10, priceDecimals: 6 }).toString()).toBe("0.05");
  });

  it("the lot price is the raw price times 10^(priceDecimals - 2), at 2 dp, and converts back", () => {
    expect(toLotPrice("2694.40", { maxLeverage: 25, priceDecimals: 2 }).toFixed(2)).toBe("2694.40");
    expect(toLotPrice("150.123", { maxLeverage: 20, priceDecimals: 3 }).toFixed(2)).toBe("1501.23");
    expect(toLotPrice("0.523000", { maxLeverage: 10, priceDecimals: 6 }).toFixed(2)).toBe("5230.00");
    expect(toLotPrice("2.51230", { maxLeverage: 20, priceDecimals: 5 }).toFixed(2)).toBe("2512.30");
    expect(fromLotPrice("5230.00", { maxLeverage: 10, priceDecimals: 6 }).toFixed(6)).toBe("0.523000");
    expect(fromLotPrice("1501.23", { maxLeverage: 20, priceDecimals: 3 }).toFixed(3)).toBe("150.123");
  });

  it("a price with digits below the market's own scale rounds half up to the lot cent (Market.toEngine)", () => {
    expect(toLotPrice("150.1235", { maxLeverage: 20, priceDecimals: 3 }).toFixed(2)).toBe("1501.24");
  });

  it("with no market argument every function behaves as BTC (the pre-markets signatures)", () => {
    const size = sizeForMargin("100.000", 10, "83451.00");
    expect(size.toFixed(8)).toBe(sizeForMargin("100.000", 10, "83451.00", BTC_MARKET).toFixed(8));
    expect(liquidationPrice(size, "83451.00", 10, LONG).toFixed(2)).toBe(
      liquidationPrice(size, "83451.00", 10, LONG, BTC_MARKET).toFixed(2),
    );
    expect(liquidationPrice(size, "83451.00", 10, LONG).toFixed(2)).toBe("76056.61");
  });

  it("8 dp size: a 100 PLAY stake at x10 and 83451.00 gives back exactly 100.00 (at 5 dp it was 99.97)", () => {
    const size = sizeForMargin("100.000", 10, "83451.00");
    expect(size.toFixed(8)).toBe("0.01198308");
    expect(initialMargin(size, "83451.00", 10).toFixed(2)).toBe("100.00");
  });

  it("a long liquidates below the entry and a short above it, on every market, at every leverage", () => {
    const markets: Array<[MarketParams, string]> = [
      [BTC_MARKET, "83451.00"],
      [{ maxLeverage: 25, priceDecimals: 2 }, "2694.40"],
      [{ maxLeverage: 20, priceDecimals: 3 }, "150.123"],
      [{ maxLeverage: 10, priceDecimals: 6 }, "0.523000"],
      [{ maxLeverage: 20, priceDecimals: 5 }, "2.51230"],
    ];
    for (const [market, entry] of markets) {
      for (let lev = 2; lev <= market.maxLeverage; lev++) {
        const size = sizeForMargin("500.000", lev, entry, market);
        const longLiq = liquidationPrice(size, entry, lev, LONG, market);
        const shortLiq = liquidationPrice(size, entry, lev, SHORT, market);
        expect(longLiq.lt(entry)).toBe(true);
        expect(shortLiq.gt(entry)).toBe(true);
        // the string form always carries exactly `priceDecimals` digits
        expect(priceStr(longLiq, market).split(".")[1]).toHaveLength(market.priceDecimals);
        // the % move is a ratio of two prices of the same market — scale-free
        expect(percentToLiquidation(entry, longLiq)).toBeGreaterThan(0);
        expect(percentToLiquidation(entry, longLiq)).toBeLessThan(1);
      }
    }
  });

  it("the liquidation test compares raw prices: a candle touching the liquidation price liquidates, one tick short does not", () => {
    const market: MarketParams = { maxLeverage: 10, priceDecimals: 6 };
    const size = sizeForMargin("250.000", 10, "0.523000", market);
    const longLiq = liquidationPrice(size, "0.523000", 10, LONG, market); // 0.495474
    expect(isLiquidatedInRange(LONG, longLiq, "0.495474", "0.540000")).toBe(true);
    expect(isLiquidatedInRange(LONG, longLiq, "0.495475", "0.540000")).toBe(false);
    const shortLiq = liquidationPrice(size, "0.523000", 10, SHORT, market); // 0.547905
    expect(isLiquidatedInRange(SHORT, shortLiq, "0.500000", "0.547905")).toBe(true);
    expect(isLiquidatedInRange(SHORT, shortLiq, "0.500000", "0.547904")).toBe(false);
  });
});
