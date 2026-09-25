/**
 * The demo's simulated clock and a deterministic, seeded BTC price path over time.
 *
 * `docs/product/decisions.md` Р-35: "фронт разрабатывается параллельно с бэкендом, сначала на
 * тестовых данных". Round windows here mirror `RoundClock`/`RoundWindow`
 * (`engine/src/main/java/crowdvsmarket/engine/round/`) — UTC-aligned, no calendar/DST
 * (`round_engine_design.md` §4.3) — hourly rounds on the hour, daily rounds at 00:00 UTC.
 *
 * The clock does not read `Date.now()` directly: it starts at a fixed anchor
 * ({@link SIM_BASE_MS}, "today" per the task) and advances {@link SIM_SPEED}× faster than real
 * time, so a hovering demo session actually sees a round go BETTING → RUNNING → SETTLED instead
 * of waiting up to an hour — предложение, no source fixes a demo playback speed.
 */
import type { EpochMs, Interval, RoundId, RoundWindowDto } from "./contract";
import { randFloat, rngFor } from "./rng";

export const HOUR_MS = 3_600_000;
export const DAY_MS = 86_400_000;

/** 1 real second of wall-clock time = 60 simulated seconds — a hourly round's whole BETTING →
 *  RUNNING → SETTLED cycle (60 simulated minutes) plays out in about 60 real seconds. */
export const SIM_SPEED = 60;

/** Fixed demo anchor ("today", `CLAUDE.md` task date), offset into an hour so the first render
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
// Round windows (RoundClock/RoundWindow.java)
// ---------------------------------------------------------------------------

function windowSizeMs(interval: Interval): number {
  return interval === "HOURLY" ? HOUR_MS : DAY_MS;
}

export function windowFor(interval: Interval, atMs: EpochMs): RoundWindowDto {
  const size = windowSizeMs(interval);
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

/** `RoundWindow.roundId()`: `${interval.code()}-${startMs}` (contract.ts's `RoundId` doc comment). */
export function roundIdFor(window: RoundWindowDto): RoundId {
  const code = window.interval === "HOURLY" ? "1h" : "1d";
  return `${code}-${window.startMs}`;
}

export function parseRoundId(id: RoundId): { interval: Interval; startMs: EpochMs } | null {
  const match = /^(1h|1d)-(\d+)$/.exec(id);
  if (!match) return null;
  return { interval: match[1] === "1h" ? "HOURLY" : "DAILY", startMs: Number(match[2]) };
}

// ---------------------------------------------------------------------------
// Deterministic BTC price path (предложение — no historical replay endpoint exists yet in the
// mock era, see week1_wallet_auth.md/round_engine_design.md open questions; a seeded random walk
// stands in so every screen agrees on the same price for the same timestamp).
// ---------------------------------------------------------------------------

const PRICE_BASE_MS = SIM_BASE_MS;
/** Last real BTC daily close price quoted in `hl_liquidation_math.md` §3's golden vectors —
 *  anchors the walk to a number that is already "real" elsewhere in this codebase. */
const PRICE_BASE = 83_451;

const dailyLogReturnCache = new Map<number, number>(); // dayIndex -> log-return for that day
const cumulativeCache = new Map<number, number>([[0, 0]]); // dayIndex -> cumulative log-return from day 0
let maxComputedDay = 0;
let minComputedDay = 0;

function dailyLogReturn(dayIndex: number): number {
  let cached = dailyLogReturnCache.get(dayIndex);
  if (cached !== undefined) return cached;
  const rng = rngFor("btc-daily-return", dayIndex);
  // Two uniforms averaged approximate a triangular distribution (softer tails than one uniform),
  // plus a small positive drift — plausible demo BTC volatility, not a real Hyperliquid feed.
  const a = rng();
  const b = rng();
  cached = (a + b - 1) * 0.035 + 0.0008;
  dailyLogReturnCache.set(dayIndex, cached);
  return cached;
}

function cumulativeLogReturn(dayIndex: number): number {
  if (dayIndex > maxComputedDay) {
    for (let d = maxComputedDay + 1; d <= dayIndex; d++) {
      cumulativeCache.set(d, cumulativeCache.get(d - 1)! + dailyLogReturn(d));
    }
    maxComputedDay = dayIndex;
  } else if (dayIndex < minComputedDay) {
    for (let d = minComputedDay - 1; d >= dayIndex; d--) {
      cumulativeCache.set(d, cumulativeCache.get(d + 1)! - dailyLogReturn(d + 1));
    }
    minComputedDay = dayIndex;
  }
  return cumulativeCache.get(dayIndex)!;
}

function dailyAnchorPrice(dayIndex: number): number {
  return PRICE_BASE * Math.exp(cumulativeLogReturn(dayIndex));
}

/** A smooth, deterministic, always-positive BTC price for any timestamp — the sole source of
 *  truth for every open/close/reference price shown anywhere in the demo. */
export function btcPriceAt(atMs: EpochMs): number {
  const dayIndex = Math.floor((atMs - PRICE_BASE_MS) / DAY_MS);
  const dayStartMs = PRICE_BASE_MS + dayIndex * DAY_MS;
  const fracOfDay = (atMs - dayStartMs) / DAY_MS;
  const p0 = dailyAnchorPrice(dayIndex);
  const p1 = dailyAnchorPrice(dayIndex + 1);
  const interpolated = p0 + (p1 - p0) * fracOfDay;
  const noiseRng = rngFor("btc-hour-noise", Math.floor(atMs / HOUR_MS));
  const noise = (noiseRng() - 0.5) * 0.006; // ± 0.3% texture within the hour
  return Math.max(1, interpolated * (1 + noise));
}

export function btcPriceStrAt(atMs: EpochMs): string {
  return btcPriceAt(atMs).toFixed(2);
}

export interface WindowOhlc {
  open: string;
  close: string;
  low: string;
  high: string;
}

/** A plausible OHLC for a round window, seeded by its `roundId` — open/close come straight from
 *  {@link btcPriceAt} (so the same round always has the same open/close), low/high add a bounded
 *  extra excursion so the liquidation math (doc §2.3) has something to bite on. */
export function ohlcFor(window: RoundWindowDto): WindowOhlc {
  const openRaw = btcPriceAt(window.startMs);
  const closeRaw = btcPriceAt(window.endMs);
  const rng = rngFor("ohlc-extremes", roundIdFor(window));
  const lower = Math.min(openRaw, closeRaw);
  const upper = Math.max(openRaw, closeRaw);
  const low = lower * (1 - randFloat(rng, 0, 0.012));
  const high = upper * (1 + randFloat(rng, 0, 0.012));
  return {
    open: openRaw.toFixed(2),
    close: closeRaw.toFixed(2),
    low: low.toFixed(2),
    high: high.toFixed(2),
  };
}
