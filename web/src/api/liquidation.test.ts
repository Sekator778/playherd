import { describe, expect, it } from "vitest";
import {
  LONG,
  SHORT,
  initialMargin,
  isLiquidatedInRange,
  liquidationPrice,
  maintenanceMarginRequired,
  payoutAtRoundClose,
  payoutOnLiquidation,
  type Side,
} from "./liquidation";

/**
 * Every row of the golden-vector table in `docs/research/hl_liquidation_math.md` §3 (20 rows:
 * long/short × leverage {2,3,10,25,40} × entry price {83451.00, 50000.00}), transcribed verbatim
 * so this test can never silently drift from the doc. `size_btc=0.10000` for every row (doc
 * §2.7's `STANDARD_SIZE_BTC`). These are the exact numbers the Java engine's own tests are
 * expected to reproduce too — TS and Java are proven never to diverge by both matching this table.
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
