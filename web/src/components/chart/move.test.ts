import { describe, expect, it } from "vitest";
import { describeMoneyMove, describeMove, moveSinceOpen } from "./move";

describe("move since open", () => {
  it("the move since open is relative for a price and in points for odds", () => {
    const price = moveSinceOpen(100, 101, false);
    expect(price.unit).toBe("pct");
    expect(price.value).toBeCloseTo(0.01, 9);
    expect(moveSinceOpen(0, 5, false)).toEqual({ value: 0, unit: "pct" });
    const odds = moveSinceOpen(0.5, 0.512, true);
    expect(odds.unit).toBe("pts");
    expect(odds.value).toBeCloseTo(1.2, 6);
  });
});

describe("describeMove — the header's 'vs round open' figure", () => {
  it("a price: a signed percentage with two decimals, three for a tiny move", () => {
    expect(describeMove({ value: 0.0123, unit: "pct" })).toEqual({ text: "+1.23%", tone: "up" });
    expect(describeMove({ value: -0.0037, unit: "pct" })).toEqual({ text: "-0.37%", tone: "down" });
    expect(describeMove({ value: -0.00037, unit: "pct" })).toEqual({ text: "-0.037%", tone: "down" });
  });

  it("odds: points with one decimal and the same 'pt' as the card's 24 h figure", () => {
    expect(describeMove({ value: 1.2, unit: "pts" })).toEqual({ text: "+1.2 pt", tone: "up" });
    expect(describeMove({ value: -0.4, unit: "pts" })).toEqual({ text: "-0.4 pt", tone: "down" });
  });

  it("a move that prints as zero is flat — no sign, no arrow (never '-0.0 pt' with a down arrow)", () => {
    expect(describeMove({ value: -0.04, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: 0.04, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: 0, unit: "pts" })).toEqual({ text: "0.0 pt", tone: "flat" });
    expect(describeMove({ value: -0.0000001, unit: "pct" })).toEqual({ text: "0.000%", tone: "flat" });
    expect(describeMove({ value: 0, unit: "pct" })).toEqual({ text: "0.000%", tone: "flat" });
  });
});

describe("describeMoneyMove — the distance from the round open in money", () => {
  const usd = (v: number) => `$${v.toFixed(2)}`;
  it("signs the difference and formats it with the room's formatter", () => {
    expect(describeMoneyMove(100, 105.8, usd)).toBe("+$5.80");
    expect(describeMoneyMove(100, 94.2, usd)).toBe("-$5.80");
  });
  it("a difference that prints as zero has no sign", () => {
    expect(describeMoneyMove(100, 100, usd)).toBeNull();
    expect(describeMoneyMove(100, 100.004, usd)).toBeNull();
  });
  it("a non-finite price gives nothing", () => {
    expect(describeMoneyMove(100, Number.NaN, usd)).toBeNull();
  });
});
