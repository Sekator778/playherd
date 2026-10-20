/**
 * "Played N days in a row" and the "This week" window, as the MOCK server computes them (the real server's rules are
 * `PlayDays.java` and `WeeklyBoards.java`; nothing in the app reads a history to count days - the profile answer carries
 * `playDays`). A day is a UTC calendar day; a run counts up to today, or up to yesterday while today is not played yet;
 * a week starts on Monday 00:00 UTC.
 */
import type { EpochMs } from "./contract";

export const DAY_MS = 86_400_000;

export function utcDayOf(ms: EpochMs): number {
  return Math.floor(ms / DAY_MS);
}

/** Monday 00:00 UTC on or before `ms`. */
export function weekStartOf(ms: EpochMs): EpochMs {
  const day = utcDayOf(ms);
  // 1970-01-01 was a Thursday: day 0 is Thursday, so Monday-based weekday = (day + 3) mod 7
  const sinceMonday = (((day + 3) % 7) + 7) % 7;
  return (day - sinceMonday) * DAY_MS;
}

/** The streak of UTC days with a settled round, from the settlement times of a history (any order). */
export function playDaysOf(settledAtMs: readonly EpochMs[], nowMs: EpochMs): { current: number; best: number } {
  const days = [...new Set(settledAtMs.map(utcDayOf))].sort((a, b) => b - a);
  const today = utcDayOf(nowMs);
  let best = 0;
  let run = 0;
  let current: number | null = null;
  let previous: number | null = null;
  for (const day of days) {
    if (previous === null) {
      run = 1;
      if (day < today - 1) current = 0;
    } else if (day === previous - 1) {
      run += 1;
    } else {
      if (current === null) current = run;
      run = 1;
    }
    best = Math.max(best, run);
    previous = day;
  }
  return { current: current ?? run, best };
}
