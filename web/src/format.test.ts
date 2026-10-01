import { describe, expect, it } from "vitest";
import { formatChange24h, formatDuration, formatPlayAmount, formatPrice } from "./format";

describe("formatPlayAmount — auto decimals (independent critique, 2026-09-26)", () => {
  it("shows a whole amount with 0 decimals", () => {
    expect(formatPlayAmount(10000)).toBe("10,000");
    expect(formatPlayAmount("10000.000")).toBe("10,000");
    expect(formatPlayAmount(100)).toBe("100");
  });

  it("shows exactly the decimals a fractional amount needs, no trailing mojo zeros", () => {
    expect(formatPlayAmount(601.32)).toBe("601.32");
    expect(formatPlayAmount("601.320")).toBe("601.32");
    expect(formatPlayAmount(601.325)).toBe("601.325");
  });

  it("never renders a 100.000-style string a comma-decimal locale could misread as 100,000 —", () => {
    // formatPlayAmount always builds the string itself (Intl "en-US"), never binds a raw
    // contract-precision string straight into a native <input type=\"number\">, which is the bug
    // this — and BetSlip.tsx's switch to a plain text input — closes.
    expect(formatPlayAmount("100.000")).toBe("100");
    expect(formatPlayAmount("100.000")).not.toContain(".");
  });

  it("an explicit decimals option still overrides auto mode when a caller genuinely needs it", () => {
    expect(formatPlayAmount(10000, { decimals: 2 })).toBe("10,000.00");
  });

  it("sign option still works the same way with auto decimals", () => {
    expect(formatPlayAmount(601.32, { sign: true })).toBe("+601.32");
    expect(formatPlayAmount(-601.32, { sign: true })).toBe("-601.32");
    expect(formatPlayAmount(0, { sign: true })).toBe("0");
  });
});

const PERP = (priceDecimals: number) => ({ kind: "PERP" as const, priceDecimals });
const ODDS = { kind: "ODDS" as const, priceDecimals: 6 };

describe("formatPrice — PERP: USD, thousands separators, 5 significant digits, never past priceDecimals", () => {
  it("a large price keeps cents", () => {
    expect(formatPrice("84629.12", PERP(2))).toBe("$84,629.12");
    expect(formatPrice("83451.00", PERP(2))).toBe("$83,451.00");
    expect(formatPrice("2696.80", PERP(2))).toBe("$2,696.80");
    expect(formatPrice("30557.00", PERP(2))).toBe("$30,557.00");
  });

  it("a price below 1000 shows enough digits for 5 significant figures", () => {
    expect(formatPrice("117.842", PERP(3))).toBe("$117.84");
    expect(formatPrice("87.2350", PERP(4))).toBe("$87.235");
    expect(formatPrice("1.49640", PERP(5))).toBe("$1.4964");
    expect(formatPrice("231.700", PERP(3))).toBe("$231.70");
  });

  it("a sub-dollar price is not rounded to cents", () => {
    expect(formatPrice("0.0945340", PERP(7))).toBe("$0.094534");
    expect(formatPrice("0.0057460", PERP(7))).toBe("$0.0057460"); // 5 significant digits: 5,7,4,6,0
    expect(formatPrice("0.24491", PERP(5))).toBe("$0.24491");
  });

  it("never shows more digits than the market's priceDecimals, and never fewer than cents", () => {
    expect(formatPrice("0.0945340", PERP(4))).toBe("$0.0945");
    expect(formatPrice("1.49640", PERP(3))).toBe("$1.496");
    expect(formatPrice("5", PERP(2))).toBe("$5.00");
    expect(formatPrice("123456789.5", PERP(2))).toBe("$123,456,789.50");
  });

  it("never switches to scientific notation, however small or large", () => {
    expect(formatPrice("0.00000012", PERP(8))).toBe("$0.00000012");
    expect(formatPrice(1e-7, PERP(8))).toBe("$0.00000010");
    expect(formatPrice("1000000000000", PERP(2))).toBe("$1,000,000,000,000.00");
    expect(formatPrice(1e21, PERP(2))).not.toMatch(/e/i);
  });

  it("accepts a number as well as a PriceStr, and a zero", () => {
    expect(formatPrice(83451.5, PERP(2))).toBe("$83,451.50");
    expect(formatPrice("0", PERP(2))).toBe("$0.00");
  });

  it("an unreadable price is a dash, not NaN", () => {
    expect(formatPrice("", PERP(2))).toBe("$0.00"); // Number("") === 0, same as the rest of the file's formatters
    expect(formatPrice("abc", PERP(2))).toBe("—");
  });
});

describe("formatPrice — ODDS: a percentage with one decimal", () => {
  it("prints the probability like a Polymarket card", () => {
    expect(formatPrice("0.523000", ODDS)).toBe("52.3%");
    expect(formatPrice("0.5", ODDS)).toBe("50.0%");
    expect(formatPrice("0.100000", ODDS)).toBe("10.0%");
    expect(formatPrice("0.904500", ODDS)).toBe("90.5%");
    expect(formatPrice("0.012345", ODDS)).toBe("1.2%");
    expect(formatPrice("0.999", ODDS)).toBe("99.9%");
  });
});

describe("formatChange24h", () => {
  it("signs a gain, keeps the minus of a loss, two decimals", () => {
    expect(formatChange24h(0.0098)).toBe("+0.98%");
    expect(formatChange24h(-0.012)).toBe("-1.20%");
    expect(formatChange24h(0.1234)).toBe("+12.34%");
  });

  it("zero is unsigned and a missing change (no source figure) is a dash", () => {
    expect(formatChange24h(0)).toBe("0.00%");
    expect(formatChange24h(null)).toBe("—");
  });
});

describe("formatDuration — the series chips", () => {
  it("minutes, hours, days", () => {
    expect(formatDuration(180)).toBe("3 MIN");
    expect(formatDuration(900)).toBe("15 MIN");
    expect(formatDuration(60)).toBe("1 MIN");
    expect(formatDuration(3600)).toBe("1 HOUR");
    expect(formatDuration(7200)).toBe("2 HOURS");
    expect(formatDuration(86_400)).toBe("1 DAY");
    expect(formatDuration(172_800)).toBe("2 DAYS");
  });

  it("uses the largest unit that divides evenly, falls back to seconds", () => {
    expect(formatDuration(5400)).toBe("90 MIN");
    expect(formatDuration(45)).toBe("45 SEC");
    expect(formatDuration(0)).toBe("0 SEC");
  });
});
