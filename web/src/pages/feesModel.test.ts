import { describe, expect, it } from "vitest";
import { NOW, market, seriesDto } from "../components/testFixtures";
import { factorOf, feeRows, formatFeeShare, herdFeePercent } from "./feesModel";

const btc = market("BTC", {
  symbol: "BTC",
  series: [
    seriesDto({ maxLeverage: 40, herdFeeBp: "0.92", feeFactor: "1" }),
    seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 20, herdFeeBp: "0.92", feeFactor: "2.2" }),
  ],
});

describe("herdFeePercent — stake · min(20 %, k · r_m · L), as a share of the stake", () => {
  it("is k · r · L basis points: 1.05 bp at x10 in the 3-minute round is 0.105 %, 2.2 times that in the 15-minute one", () => {
    expect(herdFeePercent(1.05, 1, 10)).toBeCloseTo(0.105);
    expect(herdFeePercent(1.05, 2.2, 10)).toBeCloseTo(0.231);
  });
  it("stops at the 20 % guard", () => {
    expect(herdFeePercent(2.5, 3, 40)).toBeCloseTo(3);
    expect(herdFeePercent(20, 3, 400)).toBe(20);
  });
});

describe("feeRows — only what the server sent", () => {
  it("one row per series, priced at x3, x10, x25 and the series' own highest boost", () => {
    const rows = feeRows([btc]);
    expect(rows.map((r) => [r.marketId, r.interval])).toEqual([["BTC", "HOURLY"], ["BTC", "DAILY"]]);
    const short = rows[0];
    expect(short.cells.map((c) => c?.leverage)).toEqual([3, 10, 25]);
    expect(short.cells[1]?.percent).toBeCloseTo(0.092);
    expect(short.max).toEqual({ leverage: 40, percent: herdFeePercent(0.92, 1, 40) });
    const long = rows[1];
    expect(long.cells[1]?.percent).toBeCloseTo(0.2024);
    // the cap of the long round is lower: x25 is not offered, the highest is x20
    expect(long.cells[2]).toBeNull();
    expect(long.max.leverage).toBe(20);
  });

  it("leaves out a series the server did not describe (no rate, no factor or no cap), and closed markets — never guesses a number", () => {
    const old = market("OLD", { series: [seriesDto({ maxLeverage: 10, herdFeeBp: "1" })] });
    const noCap = market("NOCAP", { series: [seriesDto({ herdFeeBp: "1", feeFactor: "1" })] });
    const closed = market("CLOSED", { status: "CLOSED", series: [seriesDto({ maxLeverage: 10, herdFeeBp: "1", feeFactor: "1" })] });
    expect(feeRows([old, noCap, closed])).toEqual([]);
  });

  it("marks an odds market and reads its own k", () => {
    const odds = market("GER", { kind: "ODDS", series: [seriesDto({ interval: "DAILY", durationSec: 900, maxLeverage: 10, herdFeeBp: "2.5", feeFactor: "3" })] });
    const rows = feeRows([btc, odds]);
    expect(rows[2].odds).toBe(true);
    expect(rows[2].cells[1]?.percent).toBeCloseTo(0.75);
    expect(factorOf(rows, "DAILY", true)).toBe(3);
    expect(factorOf(rows, "DAILY", false)).toBe(2.2);
    expect(factorOf(rows, "HOURLY", true)).toBeNull();
  });
});

describe("formatFeeShare", () => {
  it("shows up to three decimals below 1 %, two above, no trailing zeros", () => {
    expect(formatFeeShare(0.0315)).toBe("0.032 %");
    expect(formatFeeShare(0.105)).toBe("0.105 %");
    expect(formatFeeShare(0.2)).toBe("0.2 %");
    expect(formatFeeShare(2.3)).toBe("2.3 %");
    expect(formatFeeShare(20)).toBe("20 %");
    expect(NOW).toBeGreaterThan(0);
  });
});
