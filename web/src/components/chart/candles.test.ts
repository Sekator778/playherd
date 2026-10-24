import { describe, expect, it } from "vitest";
import { buildCandleSeries, toCandles, type Point } from "./series";

const B = 15_000;
const at = (atMs: number, price: number): Point => ({ atMs, price });

describe("toCandles", () => {
  it("aligns each candle to an epoch multiple of the bucket and gives it the slot [start, start + bucket)", () => {
    const base = 1_000_005 * 1000; // not a multiple of 15 s
    const candles = toCandles([at(base + 2_000, 10), at(base + 20_000, 11)], B);
    for (const c of candles) {
      expect(c.startMs % B).toBe(0);
      expect(c.endMs - c.startMs).toBe(B);
    }
    expect(candles[0].startMs).toBeLessThanOrEqual(base + 2_000);
    expect(candles[0].endMs).toBeGreaterThan(base + 2_000);
  });

  it("open is the first tick of the slot, close the last, high and low the extremes", () => {
    const candles = toCandles([at(0, 100), at(3_000, 103), at(6_000, 98), at(9_000, 101), at(12_000, 102)], B);
    expect(candles).toEqual([{ startMs: 0, endMs: B, open: 100, high: 103, low: 98, close: 102 }]);
  });

  it("ticks in different slots make separate candles, in time order", () => {
    const candles = toCandles([at(1_000, 100), at(14_000, 101), at(15_000, 105), at(29_000, 104)], B);
    expect(candles.map((c) => [c.startMs, c.open, c.close])).toEqual([
      [0, 100, 101],
      [15_000, 105, 104],
    ]);
  });

  it("a slot without ticks has no candle — nothing is invented for a silent stretch", () => {
    const candles = toCandles([at(1_000, 100), at(61_000, 110)], B);
    expect(candles.map((c) => c.startMs)).toEqual([0, 60_000]);
    expect(candles[1].open).toBe(110); // it does not open at the previous close
  });

  it("a slot with one tick is a candle whose four prices are that tick", () => {
    expect(toCandles([at(16_000, 7)], B)).toEqual([{ startMs: 15_000, endMs: 30_000, open: 7, high: 7, low: 7, close: 7 }]);
  });

  it("the newest candle is the forming one: each new tick in its slot updates close, high and low, and adds no candle", () => {
    const ticks = [at(1_000, 100), at(4_000, 101)];
    const before = toCandles(ticks, B);
    expect(before).toHaveLength(1);
    const higher = toCandles([...ticks, at(7_000, 105)], B);
    expect(higher).toHaveLength(1);
    expect(higher[0]).toMatchObject({ open: 100, high: 105, low: 100, close: 105 });
    const lower = toCandles([...ticks, at(7_000, 95)], B);
    expect(lower[0]).toMatchObject({ open: 100, high: 101, low: 95, close: 95 });
    const next = toCandles([...ticks, at(16_000, 99)], B);
    expect(next).toHaveLength(2);
    expect(next[0].close).toBe(101); // the finished candle is final
  });

  it("no ticks, or a bucket that is not positive: no candles", () => {
    expect(toCandles([], B)).toEqual([]);
    expect(toCandles([at(1, 1)], 0)).toEqual([]);
  });
});

describe("buildCandleSeries", () => {
  // A tick every 3 s for 20 minutes, ending at t = 1_200_000.
  const ticks: Point[] = Array.from({ length: 401 }, (_, i) => at(i * 3_000, 100 + Math.sin(i / 8) * 5));

  it("uses only the ticks of the window, in the bucket of that window (5M → 15 s)", () => {
    const series = buildCandleSeries(ticks, "5M");
    const first = series.data[0];
    expect(first.startMs).toBeGreaterThanOrEqual(1_200_000 - 300_000 - 15_000);
    expect(series.data.length).toBeGreaterThanOrEqual(20);
    expect(series.data.length).toBeLessThanOrEqual(21);
    expect(series.data[0].endMs - series.data[0].startMs).toBe(15_000);
  });

  it("the extent runs from the first slot's start to the newest slot's end and covers every high and low", () => {
    const { extent, data } = buildCandleSeries(ticks, "1M");
    expect(extent.fromMs).toBe(data[0].startMs);
    expect(extent.toMs).toBe(data[data.length - 1].endMs);
    expect(extent.high).toBe(Math.max(...data.map((c) => c.high)));
    expect(extent.low).toBe(Math.min(...data.map((c) => c.low)));
    expect(data[0].endMs - data[0].startMs).toBe(5_000);
  });

  it("does not use the price interpolated onto the window's edge: every price in a candle was a real tick", () => {
    const real = new Set(ticks.map((p) => p.price));
    for (const c of buildCandleSeries(ticks, "5M").data) {
      for (const v of [c.open, c.high, c.low, c.close]) expect(real.has(v)).toBe(true);
    }
  });

  it("a short history shows what there is; no ticks gives an empty series", () => {
    expect(buildCandleSeries(ticks.slice(0, 5), null).data.length).toBeGreaterThan(0);
    expect(buildCandleSeries([], "5M").data).toEqual([]);
  });
});
