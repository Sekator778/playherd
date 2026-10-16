import { describe, expect, it } from "vitest";
import {
  availableRanges,
  axisValues,
  buildChartLines,
  dataSpanMs,
  defaultRange,
  describeMove,
  moveSinceOpen,
  placeOnScale,
  sliceRange,
  spreadLabels,
  toPoints,
  yDomain,
  type Point,
} from "./priceChartModel";

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

describe("yDomain", () => {
  it("pads the data by 12 % each side", () => {
    const d = yDomain([100, 110]);
    expect(d.min).toBeCloseTo(98.8, 6);
    expect(d.max).toBeCloseTo(111.2, 6);
  });

  it("gives a dead-flat price a visible band instead of dividing by zero", () => {
    const d = yDomain([50, 50, 50]);
    expect(d.max).toBeGreaterThan(d.min);
    expect((d.min + d.max) / 2).toBeCloseTo(50, 6);
  });

  it("anchors (the round open, the entry) always join the scale", () => {
    const d = yDomain([100, 101], [95]);
    expect(d.min).toBeLessThan(95);
  });

  it("an optional line (liquidation) joins only when it is within one scale-height of the padded data", () => {
    // The padded data is 99.76–102.24 (height 2.48): a line at 98 is within one height of it, 60 is not.
    const near = yDomain([100, 102], [], [98]);
    expect(near.min).toBeLessThan(98);
    const far = yDomain([100, 102], [], [60]); // would flatten the price to a hair, so it stays off the scale
    expect(far.min).toBeCloseTo(99.76, 6);
    expect(far.max).toBeCloseTo(102.24, 6);
  });

  it("no usable numbers still gives a scale", () => {
    expect(yDomain([])).toEqual({ min: 0, max: 1 });
    expect(yDomain([Number.NaN])).toEqual({ min: 0, max: 1 });
  });
});

describe("placeOnScale", () => {
  const domain = { min: 100, max: 200 };
  it("0 is the top of the plot, 1 the bottom", () => {
    expect(placeOnScale(200, domain)).toEqual({ t: 0, off: null });
    expect(placeOnScale(100, domain)).toEqual({ t: 1, off: null });
    expect(placeOnScale(150, domain)).toEqual({ t: 0.5, off: null });
  });
  it("a price beyond the scale is pinned to that edge and flagged", () => {
    expect(placeOnScale(250, domain)).toEqual({ t: 0, off: "above" });
    expect(placeOnScale(50, domain)).toEqual({ t: 1, off: "below" });
  });
});

describe("spreadLabels", () => {
  it("leaves labels that already have room alone", () => {
    expect(spreadLabels([10, 50, 90], 17, 0, 200)).toEqual([10, 50, 90]);
  });

  it("pushes labels that collide apart, keeping their order", () => {
    const out = spreadLabels([100, 102, 104], 17, 0, 200);
    expect(out[1] - out[0]).toBeGreaterThanOrEqual(17);
    expect(out[2] - out[1]).toBeGreaterThanOrEqual(17);
    expect(out[0]).toBeLessThan(out[1]);
  });

  it("returns positions in the order the labels were given, whatever their vertical order", () => {
    const out = spreadLabels([90, 10], 17, 0, 200);
    expect(out).toEqual([90, 10]);
  });

  it("stays inside the bounds, pushing the others up when the bottom one hits the edge", () => {
    const out = spreadLabels([196, 198, 200], 17, 0, 200);
    expect(Math.max(...out)).toBeLessThanOrEqual(200);
    expect(Math.min(...out)).toBeGreaterThanOrEqual(0);
    const sorted = [...out].sort((a, b) => a - b);
    expect(sorted[1] - sorted[0]).toBeGreaterThanOrEqual(17);
    expect(sorted[2] - sorted[1]).toBeGreaterThanOrEqual(17);
  });

  it("nothing to place is nothing", () => {
    expect(spreadLabels([], 17, 0, 100)).toEqual([]);
  });
});

describe("axis and move", () => {
  it("axis values sit strictly inside the scale, top to bottom", () => {
    expect(axisValues({ min: 0, max: 100 }, 3)).toEqual([75, 50, 25]);
  });

  it("the move since open is relative for a price and in points for odds", () => {
    const price = moveSinceOpen(100, 101, false);
    expect(price.unit).toBe("pct");
    expect(price.value).toBeCloseTo(0.01, 9);
    expect(moveSinceOpen(0, 5, false)).toEqual({ value: 0, unit: "pct" });
    const odds = moveSinceOpen(0.5, 0.512, true);
    expect(odds.unit).toBe("pts");
    expect(odds.value).toBeCloseTo(1.2, 6);
  });
});

describe("describeMove — the header's 'vs round open' figure", () => {
  it("a price: a signed percentage with two decimals, three for a tiny move", () => {
    expect(describeMove({ value: 0.0123, unit: "pct" })).toEqual({ text: "+1.23%", tone: "up" });
    expect(describeMove({ value: -0.0037, unit: "pct" })).toEqual({ text: "-0.37%", tone: "down" });
    expect(describeMove({ value: -0.00037, unit: "pct" })).toEqual({ text: "-0.037%", tone: "down" });
  });

  it("odds: points with one decimal and the same 'pt' as the card's 24 h figure", () => {
    expect(describeMove({ value: 1.2, unit: "pts" })).toEqual({ text: "+1.2 pt", tone: "up" });
    expect(describeMove({ value: -0.4, unit: "pts" })).toEqual({ text: "-0.4 pt", tone: "down" });
  });

  it("a move that prints as zero is flat — no sign, no arrow (never '-0.0 pt' with a down arrow)", () => {
    expect(describeMove({ value: -0.04, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: 0.04, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: 0, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: -0.0000001, unit: "pct" })).toEqual({ text: "0.000%", tone: "flat" });
    expect(describeMove({ value: 0, unit: "pct" })).toEqual({ text: "0.000%", tone: "flat" });
  });
});

describe("buildChartLines", () => {
  const own = { direction: "UP" as const, leverage: 10, entryPrice: "100.00", liquidationPrice: "90.50" };
  const crowd = { direction: "DOWN" as const, leverage: 7, entryPrice: "100.00", liquidationPrice: "114.00" };

  it("nothing known, nothing drawn", () => {
    expect(buildChartLines({})).toEqual([]);
    expect(buildChartLines({ openPrice: null })).toEqual([]);
    expect(buildChartLines({ openPrice: "nope" })).toEqual([]);
  });

  it("just the open price for a player with no position", () => {
    expect(buildChartLines({ openPrice: "100.00" })).toEqual([{ id: "open", label: "OPEN", price: 100, tone: "open", faint: false }]);
  });

  it("every position opens at the round's open price, so the player's entry IS the open line, labelled as such", () => {
    const lines = buildChartLines({ openPrice: "100.00", own });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-liq"]);
    expect(lines[0]).toMatchObject({ label: "OPEN · YOUR ENTRY ▲ x10", tone: "entry", faint: false });
    expect(lines[1]).toMatchObject({ label: "YOUR WIPEOUT", price: 90.5, tone: "liq", faint: false });
  });

  it("the herd's lines are the same kind, fainter; its entry merges into the open line, its liquidation is its own", () => {
    const lines = buildChartLines({ openPrice: "100.00", own, crowd });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-liq", "crowd-liq"]);
    expect(lines[2]).toMatchObject({ label: "HERD LIQ ▼ x7", price: 114, tone: "liq", faint: true });
  });

  it("an entry that is NOT at the open price (should not happen, but a server can surprise) is drawn as its own line", () => {
    const lines = buildChartLines({ openPrice: "100.00", own: { ...own, entryPrice: "100.50" }, crowd: { ...crowd, entryPrice: "101.00" } });
    expect(lines.map((l) => l.id)).toEqual(["open", "own-entry", "own-liq", "crowd-entry", "crowd-liq"]);
    expect(lines[0]).toMatchObject({ label: "OPEN", tone: "open" });
    expect(lines.find((l) => l.id === "crowd-entry")).toMatchObject({ faint: true, label: "HERD ENTRY ▼ x7" });
  });

  it("a position with no round open price still draws its own levels", () => {
    const lines = buildChartLines({ own });
    expect(lines.map((l) => l.id)).toEqual(["own-entry", "own-liq"]);
  });
});
