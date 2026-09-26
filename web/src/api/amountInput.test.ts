import { describe, expect, it } from "vitest";
import { checkPlayAmount, hasTooManyDecimals } from "./amountInput";

describe("hasTooManyDecimals", () => {
  it("accepts up to 3 fractional digits", () => {
    expect(hasTooManyDecimals("100")).toBe(false);
    expect(hasTooManyDecimals("100.1")).toBe(false);
    expect(hasTooManyDecimals("100.12")).toBe(false);
    expect(hasTooManyDecimals("100.123")).toBe(false);
  });

  it("rejects a 4th fractional digit", () => {
    expect(hasTooManyDecimals("100.1234")).toBe(true);
    expect(hasTooManyDecimals("0.0001")).toBe(true);
  });
});

describe("checkPlayAmount — the bug this closes (Alex, wave2): a negative amount used to pass", () => {
  it("a negative amount is rejected outright — the old `Number(amount) || 0` form let it through", () => {
    const c = checkPlayAmount("-50");
    expect(c.isPositive).toBe(false);
    expect(c.valid).toBe(false);
  });

  it("zero is rejected", () => {
    expect(checkPlayAmount("0").valid).toBe(false);
    expect(checkPlayAmount("").valid).toBe(false);
  });

  it("garbage input is rejected, not silently coerced to 0-and-passing", () => {
    const c = checkPlayAmount("abc");
    expect(Number.isNaN(c.amount)).toBe(true);
    expect(c.valid).toBe(false);
  });

  it("a valid positive amount within balance passes", () => {
    const c = checkPlayAmount("123.456", 200);
    expect(c.valid).toBe(true);
    expect(c.amount).toBeCloseTo(123.456, 5);
  });

  it("more than 3 decimals is rejected even though it's positive and in range", () => {
    const c = checkPlayAmount("123.4567", 200);
    expect(c.isPositive).toBe(true);
    expect(c.tooManyDecimals).toBe(true);
    expect(c.valid).toBe(false);
  });

  it("over the given balance is rejected (withdraw ceiling)", () => {
    const c = checkPlayAmount("300", 200);
    expect(c.overBalance).toBe(true);
    expect(c.valid).toBe(false);
  });

  it("exactly the balance is allowed (boundary)", () => {
    const c = checkPlayAmount("200", 200);
    expect(c.overBalance).toBe(false);
    expect(c.valid).toBe(true);
  });

  it("no maxBalance given (deposit) never flags overBalance regardless of size", () => {
    const c = checkPlayAmount("1000000");
    expect(c.overBalance).toBe(false);
    expect(c.valid).toBe(true);
  });

  it("a negative amount is never also reported as overBalance, even against a small balance", () => {
    const c = checkPlayAmount("-50", 10);
    expect(c.overBalance).toBe(false);
    expect(c.valid).toBe(false);
  });
});
