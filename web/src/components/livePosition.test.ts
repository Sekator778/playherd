import { describe, expect, it } from "vitest";
import type { MyPositionDto } from "../api/contract";
import { isWaitingForOpen, liquidationRoomFraction, liveReadOfPosition, serverSizeOf } from "./livePosition";

describe("liquidationRoomFraction", () => {
  it("long: 1 at the entry price, 0 at liquidation, linear between, clamped outside", () => {
    expect(liquidationRoomFraction("100", "90", "100", "UP")).toBe(1);
    expect(liquidationRoomFraction("100", "90", "95", "UP")).toBeCloseTo(0.5);
    expect(liquidationRoomFraction("100", "90", "90", "UP")).toBe(0);
    expect(liquidationRoomFraction("100", "90", "85", "UP")).toBe(0); // beyond it (liquidated)
    expect(liquidationRoomFraction("100", "90", "130", "UP")).toBe(1); // in the player's favour: still just "full"
  });

  it("short mirrors it", () => {
    expect(liquidationRoomFraction("100", "110", "100", "DOWN")).toBe(1);
    expect(liquidationRoomFraction("100", "110", "104", "DOWN")).toBeCloseTo(0.6);
    expect(liquidationRoomFraction("100", "110", "111", "DOWN")).toBe(0);
    expect(liquidationRoomFraction("100", "110", "70", "DOWN")).toBe(1);
  });

  it("a degenerate or unreadable position reads as full room, never NaN", () => {
    expect(liquidationRoomFraction("100", "100", "99", "UP")).toBe(1);
    expect(liquidationRoomFraction("100", "110", "100", "UP")).toBe(1); // liquidation on the wrong side of entry
    expect(liquidationRoomFraction("x", "90", "95", "UP")).toBe(1);
    expect(liquidationRoomFraction("100", "90", "nope", "UP")).toBe(1);
  });
});

const BTC = { maxLeverage: 40, priceDecimals: 2 };
const running = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
  marketId: "BTC",
  symbol: "BTC",
  roundId: "1h-3600000",
  interval: "HOURLY",
  durationSec: 180,
  phase: "RUNNING",
  direction: "UP",
  leverage: 10,
  stake: "1000.000",
  entryPrice: "80000.00",
  liquidationPrice: "72100.00",
  liquidated: false,
  untilMs: 7_200_000,
  ...over,
});

describe("liveReadOfPosition", () => {
  it("P&L from the exact liquidation.ts math: size = stake × leverage / entry, pnl = size × move", () => {
    // size = 1000 × 10 / 80000 = 0.125 BTC; price +800 → +100.00
    const read = liveReadOfPosition(running(), "80800.00", BTC);
    expect(read?.pnl).toBe(100);
    expect(read?.pctToLiquidation).toBeCloseTo((80800 - 72100) / 80800, 6);
    expect(read?.roomFraction).toBe(1); // above entry
  });

  it("a short loses when the price rises, and its room shrinks toward liquidation", () => {
    const read = liveReadOfPosition(running({ direction: "DOWN", liquidationPrice: "87900.00" }), "83950.00", BTC);
    expect(read?.pnl).toBe(-493.75);
    expect(read?.roomFraction).toBeCloseTo((87900 - 83950) / (87900 - 80000), 6);
  });

  it("nothing to read for a bet that has not started, a liquidated position, or without a price/market", () => {
    expect(liveReadOfPosition(running({ phase: "BETTING", entryPrice: null, liquidationPrice: null }), "80800.00", BTC)).toBeNull();
    expect(liveReadOfPosition(running({ liquidated: true }), "80800.00", BTC)).toBeNull();
    expect(liveReadOfPosition(running(), null, BTC)).toBeNull();
    expect(liveReadOfPosition(running(), "80800.00", undefined)).toBeNull();
  });

  it("works in the scale of the market it is in (a 6-digit odds price)", () => {
    const odds = { maxLeverage: 10, priceDecimals: 6 };
    const read = liveReadOfPosition(
      running({ marketId: "PM1", leverage: 5, stake: "500.000", entryPrice: "0.500000", liquidationPrice: "0.400000" }),
      "0.450000",
      odds,
    );
    expect(read?.pnl).toBeLessThan(0);
    expect(read?.roomFraction).toBeCloseTo(0.5, 6);
  });
});

describe("the server's size (MyPositionDto.size) drives the live P&L when it sends one", () => {
  it("uses the server's size, not a re-derivation from the stake", () => {
    // The stake would derive 0.125 BTC (1000 × 10 / 80000); the server says it opened 0.2 — the P&L is of the 0.2.
    const read = liveReadOfPosition(running({ size: "0.20000000" }), "80800.00", BTC);
    expect(read?.pnl).toBe(160); // 0.2 × 800
    expect(liveReadOfPosition(running(), "80800.00", BTC)?.pnl).toBe(100); // no size sent: the derivation, as before
  });

  it("falls back to the derivation for a size that is null, empty, zero or not a number", () => {
    for (const size of [null, "", "0.00000000", "abc"]) {
      expect(liveReadOfPosition(running({ size }), "80800.00", BTC)?.pnl).toBe(100);
    }
    expect(serverSizeOf({ size: "0.12500000" })).toBe("0.12500000");
    expect(serverSizeOf({ size: undefined })).toBeNull();
    expect(serverSizeOf({ size: null })).toBeNull();
  });
});

describe("a round with no opening price yet reads as nothing, never a zero", () => {
  const waiting = running({ entryPrice: null, liquidationPrice: null, size: null });

  it("isWaitingForOpen: RUNNING with no entry price — not a bet still waiting for its round", () => {
    expect(isWaitingForOpen(waiting)).toBe(true);
    expect(isWaitingForOpen(running())).toBe(false);
    expect(isWaitingForOpen(running({ phase: "BETTING", entryPrice: null, liquidationPrice: null }))).toBe(false);
  });

  it("has no live reading: no P&L, no room, no distance to liquidation", () => {
    expect(liveReadOfPosition(waiting, "80800.00", BTC)).toBeNull();
  });
});
