import { describe, expect, it } from "vitest";
import {
  DAY_MS,
  HOUR_MS,
  buildRoundId,
  nextRoundId,
  nextWindow,
  parseRoundId,
  prevWindow,
  roundIdForWindow,
  windowFor,
} from "./clock";

describe("buildRoundId / parseRoundId (engine RoundWindow.roundId / parseRoundId)", () => {
  it("BTC keeps the legacy two-part id, every other market is prefixed with its id", () => {
    expect(buildRoundId("BTC", "HOURLY", 1735689600000)).toBe("1h-1735689600000");
    expect(buildRoundId("BTC", "DAILY", 1735689600000)).toBe("1d-1735689600000");
    expect(buildRoundId("ETH", "HOURLY", 1735689600000)).toBe("ETH-1h-1735689600000");
    expect(buildRoundId("xyz_NVDA", "DAILY", 86400000)).toBe("xyz_NVDA-1d-86400000");
    expect(buildRoundId("PM540816", "HOURLY", 3600000)).toBe("PM540816-1h-3600000");
  });

  it("parses both forms back into (marketId, interval, startMs)", () => {
    expect(parseRoundId("1h-1735689600000")).toEqual({ marketId: "BTC", interval: "HOURLY", startMs: 1735689600000 });
    expect(parseRoundId("1d-0")).toEqual({ marketId: "BTC", interval: "DAILY", startMs: 0 });
    expect(parseRoundId("ETH-1h-1735689600000")).toEqual({ marketId: "ETH", interval: "HOURLY", startMs: 1735689600000 });
    expect(parseRoundId("xyz_NVDA-1d-86400000")).toEqual({ marketId: "xyz_NVDA", interval: "DAILY", startMs: 86400000 });
  });

  it("round-trips every (market, interval, start) it builds", () => {
    for (const marketId of ["BTC", "ETH", "SOL", "xyz_GOLD", "PM900101"]) {
      for (const interval of ["HOURLY", "DAILY"] as const) {
        for (const startMs of [0, 3600000, 1790000000000]) {
          expect(parseRoundId(buildRoundId(marketId, interval, startMs))).toEqual({ marketId, interval, startMs });
        }
      }
    }
  });

  it("accepts exactly the canonical ids (like RoundWindow.parseRoundId): one id per round", () => {
    expect(parseRoundId("BTC-1h-5")).toBeNull(); // BTC is always the two-part form
    expect(parseRoundId("1h-05")).toBeNull(); // leading zero
    expect(parseRoundId("1h-+5")).toBeNull();
    expect(parseRoundId("1h--5")).toBeNull();
    expect(parseRoundId("1h-")).toBeNull();
    expect(parseRoundId("1h-1.5")).toBeNull();
    expect(parseRoundId("1w-5")).toBeNull(); // unknown interval code
    expect(parseRoundId("ETH-1w-5")).toBeNull();
    expect(parseRoundId("ET-H-1h-5")).toBeNull(); // a market id never contains '-'
    expect(parseRoundId("ETH-1h")).toBeNull();
    expect(parseRoundId("1h")).toBeNull();
    expect(parseRoundId("")).toBeNull();
    expect(parseRoundId("1h-not-a-real-round")).toBeNull();
    expect(parseRoundId("constructor-123")).toBeNull();
    expect(parseRoundId("1h-99999999999999999999")).toBeNull(); // not a safe integer
  });
});

describe("round windows (the mock server's clock)", () => {
  it("windowFor aligns to the hour / the UTC day, next/prev are adjacent and equally long", () => {
    const at = Date.UTC(2026, 8, 25, 14, 23, 7);
    const hourly = windowFor("HOURLY", at);
    expect(hourly).toEqual({ interval: "HOURLY", startMs: Date.UTC(2026, 8, 25, 14), endMs: Date.UTC(2026, 8, 25, 15) });
    const daily = windowFor("DAILY", at);
    expect(daily.startMs).toBe(Date.UTC(2026, 8, 25));
    expect(daily.endMs - daily.startMs).toBe(DAY_MS);
    expect(nextWindow(hourly).startMs).toBe(hourly.endMs);
    expect(nextWindow(hourly).endMs - nextWindow(hourly).startMs).toBe(HOUR_MS);
    expect(prevWindow(hourly).endMs).toBe(hourly.startMs);
  });

  it("nextRoundId is the id of the round starting where this one ends, in the same market", () => {
    const window = windowFor("HOURLY", Date.UTC(2026, 8, 25, 14, 23));
    expect(nextRoundId({ marketId: "BTC", window })).toBe(roundIdForWindow("BTC", nextWindow(window)));
    expect(nextRoundId({ marketId: "ETH", window })).toBe(`ETH-1h-${window.endMs}`);
  });
});
