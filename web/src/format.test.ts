import { describe, expect, it } from "vitest";
import { formatPlayAmount } from "./format";

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
