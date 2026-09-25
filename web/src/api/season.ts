/** Calendar-month UTC season math (Р-10/Р-16, `round_engine_design.md` §4.4) — pure date
 *  arithmetic, shared by the mock data generators and by UI code that needs to slice a player's
 *  full round history down to one season's worth (`SeasonRecordDto` itself has no "growth"
 *  field; the UI derives it from `ProfileRoundEntryDto[]` the same way the ratings mock does). */
import type { EpochMs, SeasonId } from "./contract";

export function seasonIdOf(ms: EpochMs): SeasonId {
  const d = new Date(ms);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

export function seasonRange(season: SeasonId): { startMs: EpochMs; endMs: EpochMs } {
  const [y, m] = season.split("-").map(Number);
  const startMs = Date.UTC(y, m - 1, 1);
  const endMs = Date.UTC(m === 12 ? y + 1 : y, m === 12 ? 0 : m, 1);
  return { startMs, endMs };
}
