import { afterEach, describe, expect, it } from "vitest";
import {
  displayTimeZone,
  formatChange24h,
  formatClock,
  formatDate,
  formatDateTime,
  formatDuration,
  formatPlayAmount,
  formatPoints,
  formatPrice,
  formatUpdatedAt,
  market24hMove,
  oddsMove24hPoints,
  setDisplayTimeZone,
  timeZoneLabel,
} from "./format";

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

  it("a change too small to show is '0.00%' — no '+' or '-' that would claim a direction", () => {
    expect(formatChange24h(0.00004)).toBe("0.00%");
    expect(formatChange24h(-0.00004)).toBe("0.00%");
    expect(formatChange24h(0.0001)).toBe("+0.01%");
    expect(market24hMove({ kind: "PERP" }, { price: "100.00", change24h: -0.00004 })).toMatchObject({ text: "0.00%", value: 0 });
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

describe("an ODDS market's 24 h change is in percentage points (the contract's formula), a perp's a percentage", () => {
  // `MarketPriceDto.change24h` is the RELATIVE change: 0.64 → 0.65 is 0.65 / 0.64 − 1 = +1.5625%, which on a card
  // that already speaks in percent would read as "+1.56%" for a move of one point.
  const relative = 0.65 / 0.64 - 1;

  it("0.64 → 0.65 is '+1.0 pt', not '+1.56%'", () => {
    expect(oddsMove24hPoints("0.650000", relative)).toBeCloseTo(1, 9);
    const move = market24hMove({ kind: "ODDS" }, { price: "0.650000", change24h: relative });
    expect(move.text).toBe("+1.0 pt");
    expect(move.text).not.toContain("%");
    expect(move).toMatchObject({ value: 1, unit: "pt" });
  });

  it("the price 24 h ago is price / (1 + change24h): a fall of 4 points reads '-4.0 pt'", () => {
    const fell = 0.5 / 0.54 - 1; // 0.54 → 0.50
    expect(oddsMove24hPoints(0.5, fell)).toBeCloseTo(-4, 9);
    expect(market24hMove({ kind: "ODDS" }, { price: "0.500000", change24h: fell })).toMatchObject({ text: "-4.0 pt", value: -4 });
  });

  it("a move that rounds to nothing is '0.0 pt' with no sign and no direction — never an arrow for a zero", () => {
    const tiny = 0.65 / 0.6499 - 1; // ~0.01 point
    const move = market24hMove({ kind: "ODDS" }, { price: "0.650000", change24h: tiny });
    expect(move.text).toBe("0.0 pt");
    expect(move.value).toBe(0);
    expect(formatPoints(-0.04, { sign: true })).toBe("0.0 pt");
  });

  it("an unknown change — or an impossible one — is a dash, not a made-up zero", () => {
    expect(market24hMove({ kind: "ODDS" }, { price: "0.640000", change24h: null })).toMatchObject({ text: "—", value: null });
    expect(market24hMove({ kind: "ODDS" }, null)).toMatchObject({ text: "—", value: null });
    expect(oddsMove24hPoints("0.64", -1)).toBeNull(); // a probability cannot have lost all of itself
    expect(oddsMove24hPoints("0.64", -1.5)).toBeNull();
    expect(oddsMove24hPoints("nope", 0.1)).toBeNull();
  });

  it("a perp stays a signed percentage with two decimals", () => {
    expect(market24hMove({ kind: "PERP" }, { price: "83451.00", change24h: 0.0098 })).toMatchObject({ text: "+0.98%", value: 0.0098, unit: "pct" });
    expect(market24hMove({ kind: "PERP" }, { price: "2696.80", change24h: -0.0141 }).text).toBe("-1.41%");
    expect(market24hMove({ kind: "PERP" }, { price: "100.00", change24h: null })).toMatchObject({ text: "—", value: null });
    expect(market24hMove({ kind: "PERP" }, null).text).toBe("—");
  });

  it("formatPoints: one decimal, '+' only on a gain", () => {
    expect(formatPoints(1.04, { sign: true })).toBe("+1.0 pt");
    expect(formatPoints(-12.36, { sign: true })).toBe("-12.4 pt");
    expect(formatPoints(2.5)).toBe("2.5 pt");
  });
});

describe("times — one rule: the device's own time zone, named wherever a time is shown (first tester pass, D9)", () => {
  // 2026-10-06 06:12:06 UTC: the room chart's first label in the tester's pass, while his phone read 08:12
  const at = Date.UTC(2026, 9, 6, 6, 12, 6);

  afterEach(() => setDisplayTimeZone("UTC")); // the tests' own default (test/setup.ts)

  it("in UTC (what the tests pin): the plain strings, with the zone named", () => {
    expect(formatClock(at)).toBe("06:12");
    expect(formatClock(at, { seconds: true })).toBe("06:12:06");
    expect(formatDate(at)).toBe("2026-10-06");
    expect(formatDateTime(at)).toBe("2026-10-06 06:12 UTC");
    expect(formatUpdatedAt(at)).toBe("Updated 06:12:06 UTC");
    expect(timeZoneLabel(at)).toBe("UTC");
  });

  it("in a phone's zone: the clock the phone shows, and the zone's name beside the date", () => {
    setDisplayTimeZone("Europe/Berlin"); // CEST in October: two hours on
    expect(formatClock(at)).toBe("08:12");
    expect(formatClock(at, { seconds: true })).toBe("08:12:06");
    expect(formatDateTime(at)).toBe("2026-10-06 08:12 GMT+2");
    expect(formatUpdatedAt(at)).toBe("Updated 08:12:06 GMT+2");
    expect(timeZoneLabel(at)).toBe("GMT+2");
  });

  it("the zone name follows daylight saving — the same zone, another instant", () => {
    setDisplayTimeZone("Europe/Berlin");
    expect(formatDateTime(Date.UTC(2026, 11, 6, 6, 12))).toBe("2026-12-06 07:12 GMT+1");
  });

  it("the date follows the zone across midnight, and midnight is 00, never 24", () => {
    setDisplayTimeZone("Asia/Tokyo"); // UTC+9, no daylight saving
    const lateEvening = Date.UTC(2026, 9, 5, 15, 5, 0); // 2026-10-06 00:05 in Tokyo
    expect(formatDate(lateEvening)).toBe("2026-10-06");
    expect(formatClock(lateEvening)).toBe("00:05");
    expect(formatDateTime(lateEvening)).toBe("2026-10-06 00:05 GMT+9");
  });

  it("a half-hour zone keeps its minutes", () => {
    setDisplayTimeZone("Asia/Kolkata");
    expect(formatClock(at)).toBe("11:42");
    expect(timeZoneLabel(at)).toBe("GMT+5:30");
  });

  it("released, it is the device's: whatever the platform reports, a time is still printed", () => {
    setDisplayTimeZone(null);
    expect(displayTimeZone()).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(formatDateTime(at)).toMatch(/^2026-10-06 \d\d:\d\d \S+$/);
  });
});
