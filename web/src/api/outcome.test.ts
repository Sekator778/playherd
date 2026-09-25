import { describe, expect, it } from "vitest";
import {
  derivedStakeFromPosition,
  netFromPayoutAndStake,
  outcomeClassName,
  outcomeFromNet,
  positionStakeStr,
} from "./outcome";

describe("outcomeFromNet — labels come from net, never from payout > 0 (Alex's report, 2026-09-25)", () => {
  it("a losing round that still returns some equity is 'lost', not 'won' (the actual bug reported)", () => {
    // 100 PLAY staked, 80.15 PLAY paid back — a real loss (-19.85), the exact shape of the bug:
    // the old `payout > 0` check would have called this a win.
    expect(outcomeFromNet(netFromPayoutAndStake("80.150", "100.000"))).toBe("lost");
  });

  it("a real win stays a win even though the payout number alone looks unremarkable", () => {
    // stake 100, payout 104.96 -> net +4.96 (test1's UP x10 round from Alex's report).
    expect(outcomeFromNet(netFromPayoutAndStake("104.960", "100.000"))).toBe("won");
  });

  it("a bigger win nets correctly at scale (test3's UP x26 round from Alex's report)", () => {
    expect(netFromPayoutAndStake("2822.640", "2500.000")).toBeCloseTo(322.64, 5);
    expect(outcomeFromNet(netFromPayoutAndStake("2822.640", "2500.000"))).toBe("won");
  });

  it("liquidation (payout 0) is a loss of the whole stake", () => {
    expect(netFromPayoutAndStake("0.000", "40.000")).toBeCloseTo(-40, 5);
    expect(outcomeFromNet(-40)).toBe("lost");
  });

  it("an exact break-even payout is 'even', not 'won'", () => {
    expect(outcomeFromNet(netFromPayoutAndStake("100.000", "100.000"))).toBe("even");
  });

  it("boundary values", () => {
    expect(outcomeFromNet(0.01)).toBe("won");
    expect(outcomeFromNet(-0.01)).toBe("lost");
    expect(outcomeFromNet(0)).toBe("even");
  });
});

describe("outcomeClassName", () => {
  it("maps every outcome to its CSS class", () => {
    expect(outcomeClassName("won")).toBe("outcome-won");
    expect(outcomeClassName("lost")).toBe("outcome-lost");
    expect(outcomeClassName("even")).toBe("outcome-even");
  });
});

describe("positionStakeStr / derivedStakeFromPosition", () => {
  it("prefers the server-sent exact `stake` field when present", () => {
    expect(
      positionStakeStr({ stake: "100.000", size: "0.01198", entryPrice: "83451.00", leverage: 10 }),
    ).toBe("100.000");
  });

  it("falls back to the liquidation-math derivation when `stake` is absent, matching the golden-vector table", () => {
    // hl_liquidation_math.md §3 golden vector: size 0.10000 BTC, entry 83451.00, leverage 10 ->
    // isolated margin 834.51 exactly (liquidation.test.ts's own GOLDEN_VECTORS row) — the same
    // formula `derivedStakeFromPosition` calls under the hood.
    const stake = derivedStakeFromPosition({ size: "0.10000", entryPrice: "83451.00", leverage: 10 });
    expect(stake.toFixed(2)).toBe("834.51");
    expect(positionStakeStr({ size: "0.10000", entryPrice: "83451.00", leverage: 10 })).toBe("834.51");
  });

  it("the fallback derivation is lossy across its two roundings (documented in contract.ts's `stake` field)", () => {
    // A 100 PLAY stake at x10/entry 83451.00 -> size rounds to 0.01198 BTC, and recovering the
    // margin from THAT rounds to 99.97, not 100.00 — the exact gap `PositionDto.stake` exists to
    // close (contract.ts's doc comment on the field spells out this same example).
    const recovered = derivedStakeFromPosition({ size: "0.01198", entryPrice: "83451.00", leverage: 10 });
    expect(recovered.toFixed(2)).toBe("99.97");
    expect(recovered.toFixed(2)).not.toBe("100.00");
  });
});
