import { describe, expect, it } from "vitest";
import type { CategoryDto } from "../api/contract";
import { setDisplayTimeZone } from "../format";
import {
  SHORT_SPAN_MS,
  bestAndWorst,
  capitalAxis,
  crowdRanges,
  dedupeByTime,
  defaultCrowdRange,
  formatAxisTick,
  formatAxisTooltip,
  isShortSpan,
  marketGroups,
  rankedByReturn,
  type OverviewRow,
} from "./crowdModel";
import { catalogue } from "./testFixtures";

const row = (symbol: string, returnPct: number, roundsTraded = 10): OverviewRow => ({
  marketId: symbol,
  symbol,
  title: symbol,
  status: "ACTIVE",
  category: "crypto",
  capital: "100000.00",
  returnPct,
  roundsTraded,
  roundsWon: Math.floor(roundsTraded / 2),
});

describe("best and worst market", () => {
  it("ranks the markets the herd has traded in, best return first", () => {
    const rows = [row("ETH", -0.03), row("BTC", 0.034), row("SOL", 0.12), row("NEW", 0, 0)];
    expect(rankedByReturn(rows).map((r) => r.symbol)).toEqual(["SOL", "BTC", "ETH"]);
  });

  it("a market with no trade is neither best nor worst", () => {
    const result = bestAndWorst([row("NEW", 0, 0), row("BTC", 0.05), row("ETH", -0.02)]);
    expect(result?.best.symbol).toBe("BTC");
    expect(result?.worst?.symbol).toBe("ETH");
  });

  it("the worst can be a loss and the best can be a loss too — nothing is dressed up", () => {
    const result = bestAndWorst([row("A", -0.01), row("B", -0.2)]);
    expect(result?.best.symbol).toBe("A");
    expect(result?.worst?.symbol).toBe("B");
  });

  it("one traded market is named once; none is nothing", () => {
    expect(bestAndWorst([row("BTC", 0.05)])?.worst).toBeNull();
    expect(bestAndWorst([row("NEW", 0, 0)])).toBeNull();
    expect(bestAndWorst([])).toBeNull();
  });

  it("ties keep the server's order", () => {
    expect(rankedByReturn([row("X", 0.01), row("Y", 0.01)]).map((r) => r.symbol)).toEqual(["X", "Y"]);
  });
});

describe("market selector groups", () => {
  const categories: CategoryDto[] = [
    { id: "crypto", title: "Crypto", marketCount: 2 },
    { id: "sports", title: "Sports", marketCount: 1 },
  ];

  it("groups by the server's categories in its order, closed markets included", () => {
    const groups = marketGroups(catalogue(), categories);
    expect(groups.map((g) => [g.id, g.markets.map((m) => m.symbol)])).toEqual([
      ["crypto", ["BTC", "ETH"]],
      ["sports", ["GER", "OLD"]],
      ["stocks", ["NVDA"]], // a category the list did not name still gets its group, last
      ["politics", ["RUNOFF"]],
    ]);
  });

  it("drops empty groups", () => {
    expect(marketGroups([], categories)).toEqual([]);
  });
});

describe("crowd chart ranges and axis follow the amount of history", () => {
  const HOUR = 3_600_000;
  const DAY = 86_400_000;

  it("a short history gets hour chips shorter than itself, then All", () => {
    expect(crowdRanges(30 * 60_000).map((r) => r.key)).toEqual(["ALL"]);
    expect(crowdRanges(3 * HOUR).map((r) => r.key)).toEqual(["1H", "ALL"]);
    expect(crowdRanges(10 * HOUR).map((r) => r.key)).toEqual(["1H", "6H", "ALL"]);
    expect(crowdRanges(40 * HOUR).map((r) => r.key)).toEqual(["1H", "6H", "1D", "ALL"]);
  });

  it("a long history keeps the month chips", () => {
    expect(crowdRanges(60 * DAY).map((r) => r.key)).toEqual(["1M", "ALL"]);
    expect(crowdRanges(200 * DAY).map((r) => r.key)).toEqual(["1M", "3M", "ALL"]);
    expect(crowdRanges(500 * DAY).map((r) => r.key)).toEqual(["1M", "3M", "1Y", "ALL"]);
  });

  it("opens on everything for a short history and on 3M for a long one, as before", () => {
    expect(defaultCrowdRange(5 * HOUR).key).toBe("ALL");
    expect(defaultCrowdRange(500 * DAY).key).toBe("3M");
    expect(defaultCrowdRange(60 * DAY).key).toBe("ALL"); // no 3M chip yet: everything
  });

  it("short means up to two days — and then the axis shows the time of day, not the date", () => {
    expect(isShortSpan(SHORT_SPAN_MS)).toBe(true);
    expect(isShortSpan(SHORT_SPAN_MS + 1)).toBe(false);
    const at = Date.UTC(2026, 8, 25, 14, 23, 45);
    expect(formatAxisTick(at, 3 * HOUR)).toBe("14:23");
    expect(formatAxisTick(at, 90 * DAY)).toBe("2026-09-25");
  });

  it("a short history's tooltip carries the date too — a bare time is ambiguous across midnight", () => {
    const at = Date.UTC(2026, 8, 25, 0, 5, 0);
    expect(formatAxisTooltip(at, 3 * HOUR)).toBe("2026-09-25 00:05 UTC");
    expect(formatAxisTooltip(at, 90 * DAY)).toBe("2026-09-25");
  });

  it("ticks and tooltips are in the device's zone, named in the tooltip (first tester pass, D9)", () => {
    setDisplayTimeZone("Europe/Berlin");
    try {
      const at = Date.UTC(2026, 8, 25, 14, 23, 45);
      expect(formatAxisTick(at, 3 * HOUR)).toBe("16:23");
      expect(formatAxisTooltip(at, 3 * HOUR)).toBe("2026-09-25 16:23 GMT+2");
      // across midnight the DATE moves with the zone too
      const lateEvening = Date.UTC(2026, 8, 25, 22, 30, 0);
      expect(formatAxisTick(lateEvening, 90 * DAY)).toBe("2026-09-26");
    } finally {
      setDisplayTimeZone("UTC");
    }
  });
});

describe("capitalAxis — round ticks whose labels differ (first tester pass, D8: '$101k $100k $100k $100k $100k')", () => {
  const labels = (axis: NonNullable<ReturnType<typeof capitalAxis>>) => axis.ticks.map(axis.format);

  it("a crowd account that moved a few hundred dollars: five round, distinct, readable ticks", () => {
    const axis = capitalAxis([100_000, 100_120, 100_350, 100_080, 100_240])!;
    expect(labels(axis)).toEqual(["$100.0k", "$100.1k", "$100.2k", "$100.3k", "$100.4k"]);
    expect(new Set(labels(axis)).size).toBe(axis.ticks.length);
    expect(axis.domain).toEqual([100_000, 100_400]);
  });

  it("a flat account (no trade yet) gets room above and below, not five copies of $100k", () => {
    const axis = capitalAxis([100_000, 100_000, 100_000])!;
    expect(labels(axis)).toEqual(["$99.90k", "$99.95k", "$100.00k", "$100.05k", "$100.10k"]);
    expect(axis.domain[0]).toBeLessThan(100_000);
    expect(axis.domain[1]).toBeGreaterThan(100_000);
  });

  it("a wide range keeps whole thousands, as it always read", () => {
    const axis = capitalAxis([92_300, 108_100, 99_000])!;
    expect(labels(axis)).toEqual(["$90k", "$95k", "$100k", "$105k", "$110k"]);
  });

  it("a half-step (2.5 × 10ⁿ) gets the one decimal it needs, on every tick alike", () => {
    const axis = capitalAxis([95_200, 104_300], 5)!; // 9100 over four intervals: steps of 2500
    expect(labels(axis)).toEqual(["$95.0k", "$97.5k", "$100.0k", "$102.5k", "$105.0k"]);
  });

  it("a zero is '$0', never '$0k' (an account that lost everything on the way)", () => {
    const axis = capitalAxis([0, 120_000, 200_000])!;
    expect(labels(axis)[0]).toBe("$0");
    expect(new Set(labels(axis)).size).toBe(axis.ticks.length);
  });

  it("covers the data, ascends, stays short and never repeats a label — over a sweep of centres and spans", () => {
    for (const centre of [100_000, 96_500, 250_000, 1_000_000, 12_345.67]) {
      for (const span of [0, 0.5, 3, 40, 150, 600, 2_500, 9_000, 60_000]) {
        const lo = centre - span / 2;
        const hi = centre + span / 2;
        const axis = capitalAxis([lo, hi, centre])!;
        const what = `centre ${centre}, span ${span}`;
        expect(axis.domain[0], what).toBeLessThanOrEqual(lo);
        expect(axis.domain[1], what).toBeGreaterThanOrEqual(hi);
        expect(axis.ticks, what).toEqual([...axis.ticks].sort((a, b) => a - b));
        expect(axis.ticks.length, what).toBeGreaterThanOrEqual(3);
        expect(axis.ticks.length, what).toBeLessThanOrEqual(8);
        expect(new Set(labels(axis)).size, `${what}: ${labels(axis).join(" ")}`).toBe(axis.ticks.length);
      }
    }
  });

  it("the label column is as wide as its widest label needs (a wide label must not be cut by the chart's edge)", () => {
    expect(capitalAxis([92_300, 108_100])!.width).toBeLessThan(capitalAxis([100_000, 100_350])!.width);
  });

  it("nothing to scale by: null, and the chart keeps its own default", () => {
    expect(capitalAxis([])).toBeNull();
    expect(capitalAxis([Number.NaN, Number.POSITIVE_INFINITY])).toBeNull();
  });

  // The axis used to spread every value into Math.min / Math.max, and a call takes only so many arguments: past about 40 000
  // in V8 (22 000 in WebKit) it throws a RangeError, and with nothing to catch it the whole app unmounted. A stand with
  // 3-minute rounds writes a crowd-capital point per settled round, three values each, so months of history get there.
  describe("with a very long history (the chart reads every point, three values each)", () => {
    const wave = (i: number) => 100_000 + ((i * 7919) % 2_003) * 0.37 - 300; // a few hundred dollars of drift, no two alike for long
    const many = (n: number) => Array.from({ length: n }, (_, i) => wave(i));
    /** What an axis says, without its `format` function (a new closure each time, never equal to another). */
    const shapeOf = (axis: NonNullable<ReturnType<typeof capitalAxis>>) => ({ domain: axis.domain, ticks: axis.ticks, width: axis.width, labels: axis.ticks.map(axis.format) });

    it.each([50_000, 150_000, 450_000])("%i values: an axis, not a RangeError", (n) => {
      expect(() => capitalAxis(many(n))).not.toThrow();
    });

    it("150 000 values give the axis of their extremes — nothing else about them matters", () => {
      const values = many(150_000);
      let lo = Infinity;
      let hi = -Infinity;
      for (const v of values) {
        if (v < lo) lo = v;
        if (v > hi) hi = v;
      }
      const axis = capitalAxis(values)!;
      expect(shapeOf(axis)).toEqual(shapeOf(capitalAxis([lo, hi])!));
      expect(axis.domain[0]).toBeLessThanOrEqual(lo);
      expect(axis.domain[1]).toBeGreaterThanOrEqual(hi);
    });

    it("a value that is not a number among them is passed over, as it is among a few", () => {
      const values = many(150_000);
      values[10] = Number.NaN;
      values[20] = Number.POSITIVE_INFINITY;
      values[149_999] = Number.NEGATIVE_INFINITY;
      expect(shapeOf(capitalAxis(values)!)).toEqual(shapeOf(capitalAxis(values.filter(Number.isFinite))!));
      expect(capitalAxis(Array.from({ length: 150_000 }, () => Number.NaN))).toBeNull();
    });
  });
});

describe("dedupeByTime — one point per instant", () => {
  const point = (atMs: number, capital: string, roundId = `r${atMs}`) => ({ atMs, crowdCapital: capital, roundId });

  it("rounds of two series that end at one boundary share a millisecond: the last one stays, order is kept", () => {
    const history = [point(100, "100000.00", "ETH-1d-0"), point(100, "100312.77", "ETH-1h-0"), point(200, "100286.76"), point(300, "100300.13", "a"), point(300, "100300.13", "b")];
    expect(dedupeByTime(history).map((p) => `${p.atMs}:${p.roundId}`)).toEqual(["100:ETH-1h-0", "200:r200", "300:b"]);
  });

  it("a history with no repeats comes back unchanged (same points, same order), and an empty one stays empty", () => {
    const history = [point(1, "1"), point(2, "2"), point(3, "3")];
    expect(dedupeByTime(history)).toEqual(history);
    expect(dedupeByTime([])).toEqual([]);
  });
});
