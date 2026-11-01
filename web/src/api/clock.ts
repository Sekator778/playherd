/**
 * The demo's simulated clock, the mock's round-window math, and the ONE place a round id is built
 * or taken apart.
 *
 * `the decision log` Р-35: "фронт разрабатывается параллельно с бэкендом, сначала на
 * тестовых данных". Round windows here mirror `RoundClock`/`RoundWindow`
 * (`engine/src/main/java/crowdvsmarket/engine/round/`) — UTC-aligned, no calendar/DST
 * (`round_engine_design.md` §4.3) — hourly rounds on the hour, daily rounds at 00:00 UTC.
 *
 * The clock does not read `Date.now()` directly: it starts at a fixed anchor
 * ({@link SIM_BASE_MS}, "today" per the task) and advances {@link SIM_SPEED}× faster than real
 * time, so a hovering demo session actually sees a round go BETTING → RUNNING → SETTLED instead
 * of waiting up to an hour — предложение, no source fixes a demo playback speed.
 *
 * What lives elsewhere: the mock's price paths are `mockPrices.ts`, its market catalog
 * `mockMarkets.ts`. Real code never needs the window math below: a round's window comes from the
 * server (`RoundDto.window`), a series' length from `MarketSeriesDto.durationSec`.
 */
import type { EpochMs, Interval, MarketId, RoundId, RoundWindowDto } from "./contract";

export const HOUR_MS = 3_600_000;
export const DAY_MS = 86_400_000;

/** 1 real second of wall-clock time = 60 simulated seconds — a hourly round's whole BETTING →
 *  RUNNING → SETTLED cycle (60 simulated minutes) plays out in about 60 real seconds. */
export const SIM_SPEED = 60;

/** Fixed demo anchor ("today", `the project notes` task date), offset into an hour so the first render
 *  already shows an hourly round partway through RUNNING rather than always starting at :00. */
export const SIM_BASE_MS: EpochMs = Date.UTC(2026, 8, 25, 14, 23, 0);

const realStartMs = typeof performance !== "undefined" ? performance.now() : Date.now();

function realElapsedMs(): number {
  return (typeof performance !== "undefined" ? performance.now() : Date.now()) - realStartMs;
}

/** The current simulated time — the single source of "now" for both the React clock hook and
 *  every `MockApi` method that needs to know which round is running. */
export function simNowMs(): EpochMs {
  return SIM_BASE_MS + realElapsedMs() * SIM_SPEED;
}

// ---------------------------------------------------------------------------
// Round windows (RoundClock/RoundWindow.java) — the MOCK server's side of the clock
// ---------------------------------------------------------------------------

/**
 * The mock server's round lengths, reported to clients through `MarketSeriesDto.durationSec`
 * (the real server's are config: 180/900 s on the stand, 1 h/1 d live, Т-11). Only the `mock*`
 * modules read this — UI code takes durations from the DTOs, never from constants.
 */
export const MOCK_SERIES_MS: Readonly<Record<Interval, number>> = { HOURLY: HOUR_MS, DAILY: DAY_MS };

export function windowFor(interval: Interval, atMs: EpochMs): RoundWindowDto {
  const size = MOCK_SERIES_MS[interval];
  const startMs = Math.floor(atMs / size) * size;
  return { interval, startMs, endMs: startMs + size };
}

export function nextWindow(window: RoundWindowDto): RoundWindowDto {
  const size = window.endMs - window.startMs;
  return { interval: window.interval, startMs: window.endMs, endMs: window.endMs + size };
}

export function prevWindow(window: RoundWindowDto): RoundWindowDto {
  const size = window.endMs - window.startMs;
  return { interval: window.interval, startMs: window.startMs - size, endMs: window.startMs };
}

// ---------------------------------------------------------------------------
// Round ids (`RoundWindow.roundId()` / `RoundWindow.parseRoundId`, engine README "Markets")
// ---------------------------------------------------------------------------

/** The legacy market: its rounds keep the pre-markets id (`"1h-<startMs>"`). */
export const BTC_MARKET_ID: MarketId = "BTC";

/** `Interval.code()` (Interval.java) — the id's series part, whatever the series' real length. */
const INTERVAL_CODES: Readonly<Record<Interval, string>> = { HOURLY: "1h", DAILY: "1d" };

function intervalOfCode(code: string): Interval | null {
  return code === INTERVAL_CODES.HOURLY ? "HOURLY" : code === INTERVAL_CODES.DAILY ? "DAILY" : null;
}

const MARKET_ID_PATTERN = /^[A-Za-z0-9_]{1,32}$/;
const START_MS_PATTERN = /^(0|[1-9]\d*)$/;

/** A round id taken apart. */
export interface RoundRef {
  marketId: MarketId;
  interval: Interval;
  startMs: EpochMs;
}

/**
 * The one builder of round ids: BTC → the legacy `"<code>-<startMs>"` (`"1h-1735689600000"`),
 * every other market → `"<marketId>-<code>-<startMs>"` (`"ETH-1h-1735689600000"`). Nothing else in
 * the web assembles an id — a UI that needs "the round after this one" asks {@link nextRoundId}.
 */
export function buildRoundId(marketId: MarketId, interval: Interval, startMs: EpochMs): RoundId {
  const legacy = `${INTERVAL_CODES[interval]}-${startMs}`;
  return marketId === BTC_MARKET_ID ? legacy : `${marketId}-${legacy}`;
}

/**
 * The one parser (inverse of {@link buildRoundId}): accepts exactly what the builder produces —
 * one canonical id per round, like `RoundWindow.parseRoundId` — so `"BTC-1h-5"` (BTC is always the
 * two-part form), `"1h-05"` and `"1h-+5"` are `null`, like any other malformed id. A market id
 * never contains `'-'`, which is what makes the split unambiguous. Prefer the `marketId` carried by
 * the DTOs; this exists for the mock and for an id that arrives without a DTO (a route param).
 */
export function parseRoundId(id: RoundId): RoundRef | null {
  const parts = id.split("-");
  if (parts.length !== 2 && parts.length !== 3) return null;
  const [marketId, code, start] = parts.length === 2 ? [BTC_MARKET_ID, parts[0], parts[1]] : parts;
  const interval = intervalOfCode(code);
  if (!interval || !MARKET_ID_PATTERN.test(marketId) || !START_MS_PATTERN.test(start)) return null;
  const startMs = Number(start);
  if (!Number.isSafeInteger(startMs)) return null;
  return buildRoundId(marketId, interval, startMs) === id ? { marketId, interval, startMs } : null;
}

/** `buildRoundId` for a window DTO of `marketId`. */
export function roundIdForWindow(marketId: MarketId, window: RoundWindowDto): RoundId {
  return buildRoundId(marketId, window.interval, window.startMs);
}

/** The id of the round that starts where `round` ends — same market, same series (the bet slip's
 *  "View next round" link once betting just closed). */
export function nextRoundId(round: { marketId: MarketId; window: RoundWindowDto }): RoundId {
  return buildRoundId(round.marketId, round.window.interval, round.window.endMs);
}
