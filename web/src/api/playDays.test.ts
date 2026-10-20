import { describe, expect, it } from "vitest";
import { DAY_MS, playDaysOf, utcDayOf, weekStartOf } from "./playDays";

const at = (iso: string) => Date.parse(iso);

describe("mock play days", () => {
  const now = at("2026-10-22T12:00:00Z");
  it("counts UTC days up to today, and up to yesterday while today is not played", () => {
    expect(playDaysOf([at("2026-10-22T01:00:00Z"), at("2026-10-21T23:59:59Z"), at("2026-10-20T00:00:00Z")], now)).toEqual({ current: 3, best: 3 });
    expect(playDaysOf([at("2026-10-21T10:00:00Z"), at("2026-10-20T10:00:00Z")], now)).toEqual({ current: 2, best: 2 });
  });
  it("a gap ends the run, not the best; zero when nothing", () => {
    expect(playDaysOf([at("2026-10-22T01:00:00Z"), at("2026-10-19T10:00:00Z"), at("2026-10-18T10:00:00Z"), at("2026-10-17T10:00:00Z")], now)).toEqual({ current: 1, best: 3 });
    expect(playDaysOf([at("2026-10-15T10:00:00Z")], now)).toEqual({ current: 0, best: 1 });
    expect(playDaysOf([], now)).toEqual({ current: 0, best: 0 });
  });
  it("the day changes at 00:00 UTC", () => {
    expect(utcDayOf(at("2026-10-22T00:00:00Z"))).toBe(utcDayOf(at("2026-10-21T23:59:59.999Z")) + 1);
    expect(DAY_MS).toBe(86_400_000);
  });
  it("the week starts on Monday 00:00 UTC", () => {
    expect(weekStartOf(at("2026-10-19T00:00:00Z"))).toBe(at("2026-10-19T00:00:00Z"));
    expect(weekStartOf(at("2026-10-18T23:59:59.999Z"))).toBe(at("2026-10-12T00:00:00Z"));
    expect(weekStartOf(at("2026-10-25T23:59:59Z"))).toBe(at("2026-10-19T00:00:00Z"));
  });
});
