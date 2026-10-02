import { describe, expect, it } from "vitest";
import type { CategoryDto } from "../api/contract";
import {
  SHORT_SPAN_MS,
  bestAndWorst,
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
