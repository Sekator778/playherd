import { describe, expect, it } from "vitest";
import {
  DEFAULT_LEVERAGE_LIMIT,
  SLIDER_STEPS,
  dialCeiling,
  effectiveLeverage,
  highLeverageAvailable,
  leverageAfterMove,
  leverageLimit,
  leverageToPosition,
  positionToLeverage,
  snapLeverage,
  usesLogScale,
} from "./leverageScale";
import { formatFeePercent, formatMovePercent, maxAffordableStake } from "./betSlipModel";

describe("leverageScale — the ceiling", () => {
  it("is x100 by default and the market's cap when that is lower; high leverage lifts it to the cap", () => {
    expect(leverageLimit(1200, false)).toBe(100);
    expect(leverageLimit(1200, true)).toBe(1200);
    expect(leverageLimit(40, false)).toBe(40);
    expect(leverageLimit(40, true)).toBe(40);
  });

  it("is lowered to what the stake allows (the tiers), never below the minimum", () => {
    expect(dialCeiling(1200, true, 240)).toBe(240);
    expect(dialCeiling(1200, false, 240)).toBe(100);
    expect(dialCeiling(1200, false, 60)).toBe(60);
    expect(dialCeiling(1200, true, null)).toBe(1200); // not known yet
    expect(dialCeiling(1200, true, 1)).toBe(2);
  });

  it("high leverage has something to unlock only above x100", () => {
    expect(highLeverageAvailable(1200, null)).toBe(true);
    expect(highLeverageAvailable(1200, 240)).toBe(true);
    expect(highLeverageAvailable(1200, 100)).toBe(false); // a big stake: the tier is x100 or less
    expect(highLeverageAvailable(100, null)).toBe(false);
    expect(highLeverageAvailable(40, null)).toBe(false);
  });

  it("the leverage in use is the choice (or the default) within the ceiling", () => {
    expect(effectiveLeverage(null, 10, 1200, false, null)).toBe(10);
    expect(effectiveLeverage(500, 10, 1200, false, null)).toBe(100); // high leverage is off
    expect(effectiveLeverage(500, 10, 1200, true, null)).toBe(500);
    expect(effectiveLeverage(500, 10, 1200, true, 240)).toBe(240);
  });
});

describe("leverageScale — the logarithmic track", () => {
  it("is only used above x100", () => {
    expect(usesLogScale(100)).toBe(false);
    expect(usesLogScale(40)).toBe(false);
    expect(usesLogScale(101)).toBe(true);
  });

  it("the ends of the track are the minimum and exactly the cap", () => {
    for (const max of [150, 600, 1200, 4000]) {
      expect(positionToLeverage(0, 2, max)).toBe(2);
      expect(positionToLeverage(SLIDER_STEPS, 2, max)).toBe(max);
      expect(leverageToPosition(2, 2, max)).toBe(0);
      expect(leverageToPosition(max, 2, max)).toBe(SLIDER_STEPS);
    }
  });

  it("equal distances on the track are equal multiples: x2..x100 take a bit over half of a x1200 track, not a twelfth", () => {
    const share = leverageToPosition(100, 2, 1200) / SLIDER_STEPS;
    expect(share).toBeGreaterThan(0.55);
    expect(share).toBeLessThan(0.65);
  });

  it("is monotonic, and every value it gives is on the grid: whole x to x100, 5s to x500, 10s to x1000, 50s above", () => {
    let last = 0;
    for (let p = 0; p <= SLIDER_STEPS; p++) {
      const v = positionToLeverage(p, 2, 4000);
      expect(v).toBeGreaterThanOrEqual(last);
      last = v;
      if (v !== 4000 && v !== 2) {
        const step = v <= 100 ? 1 : v <= 500 ? 5 : v <= 1000 ? 10 : 50;
        expect(v % step, `x${v} at ${p}`).toBe(0);
      }
    }
  });

  it("snapLeverage rounds to those steps", () => {
    expect(snapLeverage(37.4)).toBe(37);
    expect(snapLeverage(103)).toBe(105);
    expect(snapLeverage(612)).toBe(610);
    expect(snapLeverage(1234)).toBe(1250);
  });

  it("a keyboard step never sticks: one position that rounds back to the same value moves one grid step instead", () => {
    // around x600 one position is under 10, the grid step: the plain mapping can give the same value again
    const current = 600;
    const position = leverageToPosition(current, 2, 4000);
    const up = leverageAfterMove(position + 1, current, 2, 4000);
    const down = leverageAfterMove(position - 1, current, 2, 4000);
    expect(up).toBeGreaterThan(current);
    expect(down).toBeLessThan(current);
    expect(leverageAfterMove(position, current, 2, 4000)).toBe(current);
    // never past the ends
    expect(leverageAfterMove(SLIDER_STEPS, 4000, 2, 4000)).toBe(4000);
    expect(leverageAfterMove(0, 2, 2, 4000)).toBe(2);
  });

  it("a drag lands where the thumb is", () => {
    expect(leverageAfterMove(500, 10, 2, 1200)).toBe(positionToLeverage(500, 2, 1200));
    expect(DEFAULT_LEVERAGE_LIMIT).toBe(100);
  });
});

describe("betSlipModel — fees", () => {
  it("a fee as a share of the stake", () => {
    expect(formatFeePercent(0.1, 100)).toBe("0.10%");
    expect(formatFeePercent(1, 100)).toBe("1.00%");
    expect(formatFeePercent(0.001, 100000)).toBe("<0.01%");
    expect(formatFeePercent(0.1, 0)).toBe("—");
  });

  it("the move to liquidation keeps two significant digits, so x1000 is not '0.1%' or '0.0%'", () => {
    expect(formatMovePercent(0.00083)).toBe("0.083%");
    expect(formatMovePercent(0.024)).toBe("2.4%");
    expect(formatMovePercent(0.1234)).toBe("12%");
    expect(formatMovePercent(0)).toBe("—");
  });

  it("the biggest affordable stake leaves room for its own fees", () => {
    // 1 000 PLAY, herd fee 0.1% of the stake, network 0.1
    const max = maxAffordableStake(1000, 100, 0.1, 0.1);
    expect(max).toBeLessThan(999);
    const total = max + max * 0.001 + 0.1;
    expect(total).toBeLessThanOrEqual(1000);
    expect(1000 - total).toBeLessThan(0.02);
    expect(maxAffordableStake(1000, null, null, null)).toBe(1000); // no preview: the balance, as before
    expect(maxAffordableStake(0.05, 100, 0.1, 0.1)).toBe(0);
  });
});
