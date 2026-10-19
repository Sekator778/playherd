import { afterEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_BET_MODE, effectiveBetMode, fixedModeAvailable, fixedOutcome, fixedQuote, isFixedMode, loadBetMode, saveBetMode } from "./betModeModel";
import { fixedStanding, myBetState } from "./myBetModel";
import type { MyPositionDto } from "../api/contract";

afterEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe("fixed-payout quote (Р-106): 3 % of the stake rounded UP to 0.001 PLAY, plus 0.1 PLAY, on top of the stake", () => {
  it("is exact on round stakes", () => {
    expect(fixedQuote(100, null)).toEqual({ win: 100, lose: -100, platformFee: 3, networkFee: 0.1, fee: 3.1, totalDebit: 103.1 });
    expect(fixedQuote(250.5, null)?.platformFee).toBe(7.515);
  });

  it("rounds the platform fee UP to a thousandth, with no float error (33.333 -> 0.99999 -> 1.000; 33.334 -> 1.00002 -> 1.001)", () => {
    expect(fixedQuote(33.333, null)?.platformFee).toBe(1);
    expect(fixedQuote(33.334, null)?.platformFee).toBe(1.001);
    expect(fixedQuote(0.001, null)?.platformFee).toBe(0.001);
    expect(fixedQuote(1, null)?.platformFee).toBe(0.03);
  });

  it("reads the rates the server states (a different platform percent, a different network fee)", () => {
    expect(fixedQuote(100, { networkFeePlay: "0.2", fixedPlatformFeePercent: "5" })).toMatchObject({ platformFee: 5, networkFee: 0.2, fee: 5.2 });
  });

  it("is none for a stake that is not a positive number", () => {
    expect(fixedQuote(0, null)).toBeNull();
    expect(fixedQuote(Number.NaN, null)).toBeNull();
    expect(fixedQuote(-5, null)).toBeNull();
  });
});

describe("the mode", () => {
  it("is Win or lose for a player who never chose, and the last choice after", () => {
    expect(DEFAULT_BET_MODE).toBe("FIXED");
    expect(loadBetMode()).toBe("FIXED");
    saveBetMode("PERCENTAGE");
    expect(loadBetMode()).toBe("PERCENTAGE");
    window.localStorage.setItem("herd.betMode", "nonsense");
    expect(loadBetMode()).toBe("FIXED");
  });

  it("falls back to the default when storage is blocked", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    expect(loadBetMode()).toBe("FIXED");
    expect(() => saveBetMode("PERCENTAGE")).not.toThrow();
  });

  it("is the percentage mode on an odds market whatever was chosen, and the switch is offered everywhere else", () => {
    expect(fixedModeAvailable("ODDS")).toBe(false);
    expect(fixedModeAvailable("PERP")).toBe(true);
    expect(fixedModeAvailable(undefined)).toBe(true);
    expect(effectiveBetMode("FIXED", "ODDS")).toBe("PERCENTAGE");
    expect(effectiveBetMode("FIXED", "PERP")).toBe("FIXED");
    expect(effectiveBetMode("PERCENTAGE", "PERP")).toBe("PERCENTAGE");
  });

  it("an absent or null mode is the percentage mode", () => {
    expect(isFixedMode({})).toBe(false);
    expect(isFixedMode({ mode: null })).toBe(false);
    expect(isFixedMode({ mode: "PERCENTAGE" })).toBe(false);
    expect(isFixedMode({ mode: "FIXED" })).toBe(true);
    expect(isFixedMode(undefined)).toBe(false);
  });

  it("words the result of a fixed bet by its net: +S win, -S lose, 0 tie", () => {
    expect(fixedOutcome(100)).toBe("win");
    expect(fixedOutcome(-100)).toBe("lose");
    expect(fixedOutcome(0)).toBe("tie");
  });
});

describe("a running fixed bet (the dock)", () => {
  const running = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
    marketId: "BTC",
    symbol: "BTC",
    roundId: "BTC-1h-1",
    interval: "HOURLY",
    durationSec: 180,
    phase: "RUNNING",
    direction: "UP",
    leverage: 2,
    stake: "100.000",
    entryPrice: "100.00",
    liquidationPrice: null,
    size: "0.00000000",
    liquidated: false,
    untilMs: 1,
    mode: "FIXED",
    ...over,
  });

  it("says where the price stands against the open, never a P&L or a wipeout line", () => {
    expect(myBetState(running(), "100.50", undefined)).toEqual({ kind: "fixed", standing: "ahead" });
    expect(myBetState(running(), "99.50", undefined)).toEqual({ kind: "fixed", standing: "behind" });
    expect(myBetState(running({ direction: "DOWN" }), "99.50", undefined)).toEqual({ kind: "fixed", standing: "ahead" });
    expect(myBetState(running(), "100.00", undefined)).toEqual({ kind: "fixed", standing: "level" });
    expect(myBetState(running(), null, undefined)).toEqual({ kind: "fixed", standing: null });
  });

  it("fixedStanding is null without an open price", () => {
    expect(fixedStanding({ direction: "UP", entryPrice: null }, "100")).toBeNull();
  });
});
