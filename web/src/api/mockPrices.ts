/**
 * The mock's price paths: a deterministic, seeded random-walk-like price for every market in
 * `mockMarkets.ts`, as a PURE function of time — any instant, past or future, has one price, so
 * every endpoint (`markets`, `priceSeries`, `referencePrice`, round open/close/extremes, the
 * crowd history) agrees on it without storing anything.
 *
 * Shape of a path: a slow component (`PERP`: a day-to-day walk, the BTC one is the mock's old
 * `btcPriceAt` anchor series unchanged) plus value-noise octaves of shrinking period (hour, ten
 * minutes, minute, one 3 s tick), each smoothly interpolated between seeded random knots — the sum
 * wiggles like a real price at every zoom and costs a handful of knot lookups per evaluation.
 * `ODDS` markets (probabilities, T-2) live in logit space with bounded octaves, so a price can never
 * leave 0.10–0.90 for the starting levels in the catalog. Numbers are demo-grade, not calibrated
 * to any real feed; nothing here is a Coinbase, Kraken, Hyperliquid or Polymarket reading.
 *
 * A round's extremes (the liquidation check, D-7) are the lowest/highest price on a fixed grid
 * inside the window (every simulated minute for an hourly round, every 15 minutes for a daily one)
 * — the same grid whether the round is running ("so far") or settled, so a position that has been
 * liquidated on the path so far can never become un-liquidated at settlement.
 */
import type { CandleDto, EpochMs, PriceStr, PriceTickDto, RoundWindowDto } from "./contract";
import { DAY_MS, HOUR_MS, SIM_BASE_MS } from "./clock";
import { priceStr } from "./liquidation";
import { hasMockPrice, type MockMarketSpec } from "./mockMarkets";
import { rngFor } from "./rng";

/** Spacing of the mock's price ticks: a simulated 3 s (the sources are polled every 3 s,
 *  `docs/research/markets_architecture.md` §3.2). */
export const TICK_MS = 3_000;

const MINUTE_MS = 60_000;
const TEN_MINUTES_MS = 10 * MINUTE_MS;

// ---------------------------------------------------------------------------
// Seeded noise
// ---------------------------------------------------------------------------

const knotCache = new Map<string, number>();
const KNOT_CACHE_LIMIT = 200_000;

/** The `index`-th random knot of a noise stream, uniform in [-1, 1) — a pure function of its key. */
function knot(seed: string, periodMs: number, index: number): number {
  const key = `${seed}|${periodMs}|${index}`;
  let value = knotCache.get(key);
  if (value === undefined) {
    if (knotCache.size >= KNOT_CACHE_LIMIT) knotCache.clear();
    value = rngFor("price-knot", key)() * 2 - 1;
    knotCache.set(key, value);
  }
  return value;
}

/** Smooth noise in [-1, 1]: a random knot every `periodMs`, cosine-interpolated between them. */
function valueNoise(seed: string, periodMs: number, atMs: EpochMs): number {
  const k = Math.floor(atMs / periodMs);
  const f = (atMs - k * periodMs) / periodMs;
  const blend = (1 - Math.cos(Math.PI * f)) / 2;
  const a = knot(seed, periodMs, k);
  return a + (knot(seed, periodMs, k + 1) - a) * blend;
}

/** Day-to-day walk in log space, anchored at {@link SIM_BASE_MS}: day `d`'s log-return is
 *  `(u1 + u2 − 1)·spread + drift` (two uniforms ≈ a triangular distribution), cumulated forwards
 *  and backwards from day 0, interpolated linearly inside a day. With seed `"btc-daily-return"`,
 *  spread 0.035 and drift 0.0008 this is exactly the walk the mock's BTC price has always had. */
function makeDailyWalk(seed: string, spread: number, drift: number): (atMs: EpochMs) => number {
  const cumulative = new Map<number, number>([[0, 0]]);
  let maxDay = 0;
  let minDay = 0;

  const dailyReturn = (day: number): number => {
    const rng = rngFor(seed, day);
    const a = rng();
    const b = rng();
    return (a + b - 1) * spread + drift;
  };

  const cumulativeAt = (day: number): number => {
    if (day > maxDay) {
      for (let d = maxDay + 1; d <= day; d++) cumulative.set(d, cumulative.get(d - 1)! + dailyReturn(d));
      maxDay = day;
    } else if (day < minDay) {
      for (let d = minDay - 1; d >= day; d--) cumulative.set(d, cumulative.get(d + 1)! - dailyReturn(d + 1));
      minDay = day;
    }
    return cumulative.get(day)!;
  };

  return (atMs) => {
    const day = Math.floor((atMs - SIM_BASE_MS) / DAY_MS);
    const frac = (atMs - (SIM_BASE_MS + day * DAY_MS)) / DAY_MS;
    const start = cumulativeAt(day);
    return start + (cumulativeAt(day + 1) - start) * frac;
  };
}

const walks = new Map<string, (atMs: EpochMs) => number>();

function dailyWalkOf(spec: MockMarketSpec): (atMs: EpochMs) => number {
  let walk = walks.get(spec.id);
  if (!walk) {
    walk =
      spec.id === "BTC"
        ? makeDailyWalk("btc-daily-return", 0.035, 0.0008)
        : makeDailyWalk(`${spec.id}-walk`, 8 * spec.hourlyVol, 0);
    walks.set(spec.id, walk);
  }
  return walk;
}

// ---------------------------------------------------------------------------
// The price itself
// ---------------------------------------------------------------------------

interface Octave {
  periodMs: number;
  /** Peak amplitude as a multiple of the market's `hourlyVol`. */
  amp: number;
}

/** `PERP`: log-price octaves (hour, ten minutes, minute) — `amp × hourlyVol` is a fraction of the price. */
const PERP_OCTAVES: readonly Octave[] = [
  { periodMs: HOUR_MS, amp: 1.4 },
  { periodMs: TEN_MINUTES_MS, amp: 0.6 },
  { periodMs: MINUTE_MS, amp: 0.2 },
];
const PERP_TICK_AMP = 0.05;

/** `ODDS`: log-odds octaves; the amplitudes sum to < 0.4 for every `hourlyVol` ≤ 0.03, which
 *  keeps a price started in 0.34–0.64 well inside 0.10–0.90. */
const ODDS_OCTAVES: readonly Octave[] = [
  { periodMs: 12 * HOUR_MS, amp: 8 },
  { periodMs: HOUR_MS, amp: 3 },
  { periodMs: TEN_MINUTES_MS, amp: 1.2 },
  { periodMs: MINUTE_MS, amp: 0.5 },
];
const ODDS_TICK_AMP = 0.1; // 8 + 3 + 1.2 + 0.5 + 0.1 = 12.8 × hourlyVol in total

/** `coarse` leaves out the octaves finer than ten minutes — for places that sample sparsely
 *  anyway (daily rounds, the crowd history), where they average out. */
export type PriceDetail = "fine" | "coarse";

/** The market's price at `atMs`: USD for a `PERP`, a probability for an `ODDS` market (a float —
 *  format it with {@link priceStrAt}). */
export function priceAt(spec: MockMarketSpec, atMs: EpochMs, detail: PriceDetail = "fine"): number {
  const fine = detail === "fine";
  if (spec.kind === "ODDS") {
    let logit = Math.log(spec.startPrice / (1 - spec.startPrice));
    for (const o of ODDS_OCTAVES) {
      if (!fine && o.periodMs < TEN_MINUTES_MS) continue;
      logit += o.amp * spec.hourlyVol * valueNoise(`${spec.id}|v`, o.periodMs, atMs);
    }
    if (fine) logit += ODDS_TICK_AMP * spec.hourlyVol * knot(`${spec.id}|t`, TICK_MS, Math.floor(atMs / TICK_MS));
    return 1 / (1 + Math.exp(-logit));
  }
  let logPrice = dailyWalkOf(spec)(atMs);
  for (const o of PERP_OCTAVES) {
    if (!fine && o.periodMs < TEN_MINUTES_MS) continue;
    logPrice += o.amp * spec.hourlyVol * valueNoise(`${spec.id}|v`, o.periodMs, atMs);
  }
  if (fine) logPrice += PERP_TICK_AMP * spec.hourlyVol * knot(`${spec.id}|t`, TICK_MS, Math.floor(atMs / TICK_MS));
  return spec.startPrice * Math.exp(logPrice);
}

/** {@link priceAt} as the contract's `PriceStr` (exactly `priceDecimals` digits). */
export function priceStrAt(spec: MockMarketSpec, atMs: EpochMs): PriceStr {
  return priceStr(priceAt(spec, atMs), spec);
}

/** Relative change vs 24 hours earlier (`MarketPriceDto.change24h`, `0.0098` = +0.98%) — for an `ODDS` market
 *  too: the RELATIVE move of the probability (0.64 → 0.65 is +1.5625%), which the UI turns into points. Null
 *  when there is no price from that long ago (a market listed less than a day ago): the source gives none. */
export function change24hAt(spec: MockMarketSpec, atMs: EpochMs): number | null {
  if (!hasMockPrice(spec, atMs - DAY_MS)) return null;
  return priceAt(spec, atMs) / priceAt(spec, atMs - DAY_MS) - 1;
}

/**
 * The mock's `priceSeries`, in the contract's words (`Api.priceSeries`): the ticks with `afterMs < atMs ≤ untilMs`,
 * oldest first — STRICTLY after `afterMs`, never the tick a poll already holds — at most `max` of them. When more
 * exist, `max` are returned thinned evenly, so they still span the whole range from the first tick after
 * `afterMs` to the newest one (both ends are always in). Ticks sit on a fixed {@link TICK_MS} grid, so two calls
 * never disagree about a tick's time or price; a market has none from before its first price.
 */
export function ticksBetween(spec: MockMarketSpec, afterMs: EpochMs, untilMs: EpochMs, max: number): PriceTickDto[] {
  const last = Math.floor(untilMs / TICK_MS);
  let first = Math.floor(afterMs / TICK_MS) + 1;
  if (spec.priceFromMs !== null) first = Math.max(first, Math.ceil(spec.priceFromMs / TICK_MS));
  const count = last - first + 1;
  if (count <= 0 || max <= 0) return [];
  const take = Math.min(count, max);
  const ticks: PriceTickDto[] = [];
  for (let i = 0; i < take; i++) {
    const k = take === 1 ? last : first + Math.round((i * (count - 1)) / (take - 1));
    ticks.push({ atMs: k * TICK_MS, price: priceStrAt(spec, k * TICK_MS) });
  }
  return ticks;
}

/**
 * The mock's `candles` (`Api.candles`): the finished 1-minute candles among the `minutes` minutes before the one
 * `untilMs` is in, oldest first — each folded from the ticks on the {@link TICK_MS} grid inside its minute, exactly
 * the prices `priceSeries` shows (the open is the first tick, the close the last). A minute before the market's
 * first price has no candle.
 */
export function candlesBefore(spec: MockMarketSpec, untilMs: EpochMs, minutes: number): CandleDto[] {
  const current = Math.floor(untilMs / MINUTE_MS);
  const out: CandleDto[] = [];
  for (let m = current - Math.max(0, minutes); m < current; m++) {
    const start = m * MINUTE_MS;
    let first = Math.floor(start / TICK_MS);
    if (first * TICK_MS < start) first += 1;
    if (spec.priceFromMs !== null) first = Math.max(first, Math.ceil(spec.priceFromMs / TICK_MS));
    const last = Math.ceil((start + MINUTE_MS) / TICK_MS) - 1;
    if (last < first) continue;
    let high = -Infinity;
    let low = Infinity;
    let open = 0;
    let close = 0;
    for (let k = first; k <= last; k++) {
      const price = Number(priceStrAt(spec, k * TICK_MS));
      if (k === first) open = price;
      close = price;
      high = Math.max(high, price);
      low = Math.min(low, price);
    }
    out.push({
      openTimeMs: start,
      open: priceStr(open, spec),
      high: priceStr(high, spec),
      low: priceStr(low, spec),
      close: priceStr(close, spec),
    });
  }
  return out;
}

// ---------------------------------------------------------------------------
// Round open / close / extremes
// ---------------------------------------------------------------------------

export interface RoundOhlc {
  open: PriceStr;
  /** The price at the window's end once it is over, otherwise the latest price seen. */
  close: PriceStr;
  /** Lowest/highest price on the window's grid (and the open, and a finished round's close). */
  low: PriceStr;
  high: PriceStr;
}

const ohlcCache = new Map<string, RoundOhlc>();

/**
 * The window's open, close and extremes. `untilMs` (default: the window's end) limits the path to
 * what has happened by then — a running round's "so far"; the extremes of an earlier `untilMs` are
 * always within those of a later one.
 */
export function ohlcFor(spec: MockMarketSpec, window: RoundWindowDto, untilMs: EpochMs = window.endMs): RoundOhlc {
  const complete = untilMs >= window.endMs;
  const cacheKey = complete ? `${spec.id}|${window.startMs}|${window.endMs}` : null;
  if (cacheKey) {
    const hit = ohlcCache.get(cacheKey);
    if (hit) return hit;
  }

  const duration = window.endMs - window.startMs;
  const detail: PriceDetail = duration > 2 * HOUR_MS ? "coarse" : "fine";
  const step = Math.max(MINUTE_MS, Math.round(duration / 96));
  const stop = Math.min(untilMs, window.endMs);

  const open = priceAt(spec, window.startMs);
  let low = open;
  let high = open;
  for (let t = window.startMs + step; t <= stop; t += step) {
    const p = priceAt(spec, t, detail);
    if (p < low) low = p;
    if (p > high) high = p;
  }
  const close = priceAt(spec, stop);
  if (complete) {
    low = Math.min(low, close);
    high = Math.max(high, close);
  }

  const ohlc: RoundOhlc = {
    open: priceStr(open, spec),
    close: priceStr(close, spec),
    low: priceStr(low, spec),
    high: priceStr(high, spec),
  };
  if (cacheKey) {
    if (ohlcCache.size >= 5_000) ohlcCache.clear();
    ohlcCache.set(cacheKey, ohlc);
  }
  return ohlc;
}
