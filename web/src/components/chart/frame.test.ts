import { describe, expect, it } from "vitest";
import { axisValues, placeOnScale, spreadLabels, yDomain } from "./frame";

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

describe("axis", () => {
  it("axis values sit strictly inside the scale, top to bottom", () => {
    expect(axisValues({ min: 0, max: 100 }, 3)).toEqual([75, 50, 25]);
  });
});
