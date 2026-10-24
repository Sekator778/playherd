import { describe, expect, it } from "vitest";
import { availableRanges, dataSpanMs, defaultRange, sliceRange, toPoints, type Point } from "./series";

const pts = (...prices: number[]): Point[] => prices.map((price, i) => ({ atMs: i * 1000, price }));
/** `count` points, one every `stepMs`, ending at t = (count − 1) × stepMs. */
const series = (count: number, stepMs: number): Point[] => Array.from({ length: count }, (_, i) => ({ atMs: i * stepMs, price: 100 + i }));

describe("toPoints", () => {
  it("reads PriceStr as numbers and drops anything that is not finite", () => {
    expect(
      toPoints([
        { atMs: 1, price: "83451.00" },
        { atMs: 2, price: "not a price" },
        { atMs: Number.NaN, price: "5" },
        { atMs: 3, price: "0.580000" },
      ]),
    ).toEqual([
      { atMs: 1, price: 83451 },
      { atMs: 3, price: 0.58 },
    ]);
  });
});

describe("ranges the data allows", () => {
  it("offers a range only once the data covers (nearly) all of it", () => {
    expect(availableRanges(0)).toEqual([]);
    expect(availableRanges(50_000)).toEqual([]); // under 54 s: not even 1M
    expect(availableRanges(55_000)).toEqual(["1M"]);
    expect(availableRanges(265_000)).toEqual(["1M"]); // 265 s < 0.9 × 5 min
    expect(availableRanges(270_000)).toEqual(["1M", "5M"]);
    expect(availableRanges(15 * 60_000)).toEqual(["1M", "5M", "15M"]);
  });

  it("the span of the data is last minus first, zero for fewer than two points", () => {
    expect(dataSpanMs([])).toBe(0);
    expect(dataSpanMs(pts(1))).toBe(0);
    expect(dataSpanMs(series(11, 30_000))).toBe(300_000);
  });

  it("starts on 5M when there is 5M, else on the longest there is, else on everything", () => {
    expect(defaultRange(["1M", "5M", "15M"])).toBe("5M");
    expect(defaultRange(["1M", "5M"])).toBe("5M");
    expect(defaultRange(["1M"])).toBe("1M");
    expect(defaultRange([])).toBeNull();
  });

  it("slices the last range before the newest point: exactly that long, the line entering from the left edge", () => {
    const data = series(31, 30_000); // 15 minutes, a point every 30 s
    const fiveMinutes = sliceRange(data, "5M");
    expect(fiveMinutes[fiveMinutes.length - 1]).toBe(data[30]);
    expect(fiveMinutes[0].atMs).toBe(data[30].atMs - 300_000); // a tick sits exactly on the boundary
    expect(fiveMinutes).toHaveLength(11);
    expect(sliceRange(data, null)).toHaveLength(31);
    expect(sliceRange([], "5M")).toEqual([]);
    expect(sliceRange(data, "15M")).toHaveLength(31);
    expect(sliceRange(pts(1, 2, 3), "5M")).toHaveLength(3); // less data than the range: all of it
  });

  it("a boundary between two ticks gets the price interpolated onto it", () => {
    const data: Point[] = [
      { atMs: 0, price: 100 },
      { atMs: 70_000, price: 107 },
      { atMs: 100_000, price: 110 },
    ];
    // 1M back from t = 100 s is t = 40 s, between the first two ticks: 100 + 7 × 40/70 = 104.
    const sliced = sliceRange(data, "1M");
    expect(sliced[0].atMs).toBe(40_000);
    expect(sliced[0].price).toBeCloseTo(104, 9);
    expect(sliced.slice(1)).toEqual([data[1], data[2]]);
  });
});
