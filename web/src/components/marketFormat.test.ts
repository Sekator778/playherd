import { describe, expect, it } from "vitest";
import { formatAgo, formatStartsIn, formatTimeLeft, trend } from "./marketFormat";

describe("formatTimeLeft", () => {
  it("shows the two most significant units", () => {
    expect(formatTimeLeft(5 * 86_400_000 + 3 * 3_600_000 + 59 * 60_000)).toBe("5d 3h");
    expect(formatTimeLeft(95 * 60_000)).toBe("1h 35m");
    expect(formatTimeLeft(12 * 60_000 + 59_000)).toBe("12m");
    expect(formatTimeLeft(45_000)).toBe("45s");
  });
  it("never goes negative", () => {
    expect(formatTimeLeft(-5_000)).toBe("0s");
  });
});

describe("formatAgo", () => {
  it("is coarse and honest about how old a tick is", () => {
    expect(formatAgo(3_000)).toBe("just now");
    expect(formatAgo(40_000)).toBe("40 s ago");
    expect(formatAgo(3 * 60_000 + 5_000)).toBe("3 min ago");
    expect(formatAgo(2 * 3_600_000)).toBe("2 h ago");
  });
});

describe("trend", () => {
  it("zero and unknown are flat, never a gain", () => {
    expect(trend(0.01)).toBe("up");
    expect(trend(-0.01)).toBe("down");
    expect(trend(0)).toBe("flat");
    expect(trend(null)).toBe("flat");
    expect(trend(Number.NaN)).toBe("flat");
  });
});

describe("formatStartsIn — 'Starts in …' before a kick-off", () => {
  it("reads like a clock: hours with the minutes padded", () => {
    expect(formatStartsIn(2 * 3_600_000 + 5 * 60_000 + 30_000)).toBe("2h 05m");
    expect(formatStartsIn(3_600_000)).toBe("1h 00m");
    expect(formatStartsIn(10 * 3_600_000 + 45 * 60_000)).toBe("10h 45m");
  });
  it("days with the hours; under an hour just the minutes; under a minute the seconds", () => {
    expect(formatStartsIn(2 * 86_400_000 + 4 * 3_600_000 + 59 * 60_000)).toBe("2d 4h");
    expect(formatStartsIn(45 * 60_000 + 59_000)).toBe("45m");
    expect(formatStartsIn(40_000)).toBe("40s");
  });
  it("never goes negative", () => {
    expect(formatStartsIn(-1)).toBe("0s");
  });
});
