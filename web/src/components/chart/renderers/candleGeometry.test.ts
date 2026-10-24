import { describe, expect, it } from "vitest";
import { makeFrame } from "../frame";
import type { Candle } from "../series";
import { candleShapes, directionOf } from "./candleGeometry";

// 100 px of plot for 100 s and for prices 0..100, so one second is one pixel and one price unit is one pixel.
const frame = makeFrame({ width: 108, height: 100, pad: { left: 4, top: 0, right: 4, bottom: 0 }, fromMs: 0, toMs: 100_000, domain: { min: 0, max: 100 } });
const candle = (over: Partial<Candle>): Candle => ({ startMs: 0, endMs: 10_000, open: 50, high: 60, low: 40, close: 55, ...over });

describe("candleShapes", () => {
  it("up when it closed above its open, down when below, flat when equal", () => {
    expect(directionOf(candle({ close: 55 }))).toBe("up");
    expect(directionOf(candle({ close: 45 }))).toBe("down");
    expect(directionOf(candle({ close: 50 }))).toBe("flat");
  });

  it("the body runs from the open to the close, the wick from the high to the low", () => {
    const [up] = candleShapes([candle({ open: 50, close: 55, high: 60, low: 40 })], frame);
    expect(up.bodyY).toBeCloseTo(45, 6); // y(55)
    expect(up.bodyH).toBeCloseTo(5, 6);
    expect(up.wickTop).toBeCloseTo(40, 6); // y(60)
    expect(up.wickBottom).toBeCloseTo(60, 6); // y(40)
    const [down] = candleShapes([candle({ open: 55, close: 50 })], frame);
    expect(down.direction).toBe("down");
    expect(down.bodyY).toBeCloseTo(45, 6);
    expect(down.bodyH).toBeCloseTo(5, 6);
  });

  it("a down candle has the same size of body as an up candle of the same move", () => {
    const [a] = candleShapes([candle({ open: 50, close: 56 })], frame);
    const [b] = candleShapes([candle({ open: 56, close: 50 })], frame);
    expect(b.bodyH).toBeCloseTo(a.bodyH, 6);
    expect(b.bodyW).toBeCloseTo(a.bodyW, 6);
  });

  it("a candle that closed where it opened has no body, only a line at that price", () => {
    const [flat] = candleShapes([candle({ open: 50, close: 50 })], frame);
    expect(flat.direction).toBe("flat");
    expect(flat.bodyH).toBe(0);
    expect(flat.bodyY).toBeCloseTo(50, 6);
  });

  it("a tiny real move still gets a visible body, never thinner than 2 px", () => {
    const [tiny] = candleShapes([candle({ open: 50, close: 50.1 })], frame);
    expect(tiny.direction).toBe("up");
    expect(tiny.bodyH).toBeGreaterThanOrEqual(2);
  });

  it("the body is centred in its slot and narrower than it, leaving a gap to the next candle", () => {
    const [s] = candleShapes([candle({ startMs: 20_000, endMs: 30_000 })], frame);
    const slotLeft = frame.x(20_000);
    expect(s.cx).toBeCloseTo(slotLeft + 5, 6);
    expect(s.bodyW).toBeLessThan(10);
    expect(s.bodyW).toBeGreaterThan(5);
    expect(s.bodyX + s.bodyW / 2).toBeCloseTo(s.cx, 6);
  });

  it("stays readable when slots are narrow (a phone, 15M): at least 1.5 px wide", () => {
    const narrow = makeFrame({ width: 100, height: 100, pad: { left: 0, top: 0, right: 0, bottom: 0 }, fromMs: 0, toMs: 3_000_000, domain: { min: 0, max: 100 } });
    const [s] = candleShapes([candle({ startMs: 0, endMs: 30_000 })], narrow); // 1 px slot
    expect(s.bodyW).toBeGreaterThanOrEqual(1.5);
  });
});
