import { describe, expect, it } from "vitest";
import { crowdSplitOf, herdSideOf, isOpenMarket, kickoffOf, leadSeries, marketPool, nextCloseMs } from "./marketModel";
import { NOW, catalogue, market, seriesDto } from "./testFixtures";

describe("crowdSplitOf", () => {
  it("is the share of the stake on each side, whole percent, summing to 100", () => {
    expect(crowdSplitOf({ upStake: "3000.000", downStake: "1000.000" })).toEqual({ upPct: 75, downPct: 25, totalStake: 4000 });
    expect(crowdSplitOf({ upStake: "1.000", downStake: "2.000" })).toMatchObject({ upPct: 33, downPct: 67 });
  });

  it("is null while nothing is staked — never an invented 50/50", () => {
    expect(crowdSplitOf({ upStake: "0.000", downStake: "0.000" })).toBeNull();
    expect(crowdSplitOf({})).toBeNull();
  });
});

describe("herdSideOf", () => {
  it("takes the server's majorityDirection when it sends one, a tie included", () => {
    expect(herdSideOf({ majorityDirection: "DOWN", upStake: "900.000", downStake: "100.000" })).toBe("DOWN");
    expect(herdSideOf({ majorityDirection: null, upStake: "900.000", downStake: "100.000" })).toBeNull();
  });

  it("reads the stakes when a server does not send it; equal stakes are no herd", () => {
    expect(herdSideOf({ upStake: "900.000", downStake: "100.000" })).toBe("UP");
    expect(herdSideOf({ upStake: "100.000", downStake: "900.000" })).toBe("DOWN");
    expect(herdSideOf({ upStake: "100.000", downStake: "100.000" })).toBeNull();
    expect(herdSideOf({})).toBeNull();
  });
});

describe("market helpers", () => {
  const [btc, , , runoff, ger, old] = catalogue();

  it("a market is open unless it is CLOSED", () => {
    expect(isOpenMarket(btc)).toBe(true);
    expect(isOpenMarket(old)).toBe(false);
  });

  it("the lead series is the first (shortest) one", () => {
    expect(leadSeries(btc)?.durationSec).toBe(180);
    expect(leadSeries({ series: [] })).toBeUndefined();
  });

  it("the pool is both sides' stake in the lead series' open round", () => {
    expect(marketPool(btc)).toBe(4000);
    expect(marketPool(market("X"))).toBe(0);
    expect(marketPool({ series: [] })).toBe(0);
  });

  it("the next close is the earlier of the betting window and the event's own end", () => {
    expect(nextCloseMs(btc)).toBe(NOW + 75_000);
    expect(nextCloseMs(ger)).toBe(NOW + 30_000); // betting closes before the 40 min event end
    expect(nextCloseMs({ series: [seriesDto({ bettingClosesAtMs: NOW + 900_000 })], endsAtMs: NOW + 60_000 })).toBe(NOW + 60_000);
    expect(nextCloseMs(runoff)).toBe(NOW + 75_000);
  });
});

describe("kickoffOf — a sports game against its kick-off (MarketDto.eventStartsAtMs)", () => {
  it("before it: how long is left; at and after it: live", () => {
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW + 7_500_000 }, NOW)).toEqual({ state: "upcoming", inMs: 7_500_000 });
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW }, NOW)).toEqual({ state: "live" });
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: NOW - 60_000 }, NOW)).toEqual({ state: "live" });
  });
  it("nothing for a market without a kick-off — null and absent alike", () => {
    expect(kickoffOf({ status: "ACTIVE", eventStartsAtMs: null }, NOW)).toBeNull();
    expect(kickoffOf({ status: "ACTIVE" }, NOW)).toBeNull();
  });
  it("nothing for a closed market: a card that is closed does not say the game is live", () => {
    expect(kickoffOf({ status: "CLOSED", eventStartsAtMs: NOW - 60_000 }, NOW)).toBeNull();
  });
});
