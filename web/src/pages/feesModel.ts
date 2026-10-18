/**
 * The "All fees" table (Р-102, `docs/research/fee_display_venues_2026-10-18.md` §4): for each market and round length, the herd fee as a share
 * of the stake at x3, x10, x25 and the market's own highest leverage for a stake up to 250 PLAY.
 *
 * Nothing here is a fee of its own: `r_m` (`MarketSeriesDto.herdFeeBp`), `k` (`feeFactor`) and the cap (`maxLeverage`) are the server's, read from
 * `markets()`; the page only applies the server's formula `stake * min(20 %, k * r_m * L)`. A series the server did not describe fully (an older
 * server) is left out rather than guessed. The three constants below are the rules the server states in `EconomyRules` and nothing in
 * `markets()` carries them yet.
 */
import type { Interval, MarketDto, MarketSeriesDto } from "../api/contract";

/** The leverages every row is priced at, besides the market's own highest. */
export const FEE_TABLE_LEVERAGES = [3, 10, 25] as const;
/** The flat network fee of a bet, PLAY (`EconomyRules`). */
export const NETWORK_FEE_PLAY = "0.1";
/** The guard on the herd fee: it never takes more than this share of the stake (`EconomyRules`). */
export const HERD_FEE_GUARD_PERCENT = 20;
/** The stake tiers (PLAY) and the share of the market's cap each allows: up to 250 the cap, up to 500 half, up to 1200 a fifth, above a tenth. */
export const STAKE_TIERS = [
  { upTo: 250, divisor: 1 },
  { upTo: 500, divisor: 2 },
  { upTo: 1200, divisor: 5 },
  { upTo: null, divisor: 10 },
] as const;

export interface FeeCell {
  leverage: number;
  /** The herd fee as a share of the stake, in percent (0.92 = 0.92 %). */
  percent: number;
}

export interface FeeRow {
  marketId: string;
  symbol: string;
  title: string;
  odds: boolean;
  interval: Interval;
  durationSec: number;
  /** `r_m` in basis points and `k`, as the server sent them. */
  rateBp: number;
  factor: number;
  /** One per entry of `FEE_TABLE_LEVERAGES`; null where the market's cap is lower than that leverage. */
  cells: Array<FeeCell | null>;
  /** The market's highest leverage for a stake up to 250 PLAY. */
  max: FeeCell;
}

/** `min(20 %, k * r_m * L)` as a percent of the stake. */
export function herdFeePercent(rateBp: number, factor: number, leverage: number): number {
  return Math.min(HERD_FEE_GUARD_PERCENT, (factor * rateBp * leverage) / 100);
}

/** "0.105 %", "2.3 %", "20 %": up to three decimals below 1 %, two above, trailing zeros dropped. */
export function formatFeeShare(percent: number): string {
  if (!Number.isFinite(percent)) return "—";
  const text = percent < 1 ? percent.toFixed(3) : percent.toFixed(2);
  return `${text.replace(/\.?0+$/, "")} %`;
}

function rowOf(market: MarketDto, series: MarketSeriesDto): FeeRow | null {
  const rateBp = Number(series.herdFeeBp);
  const factor = Number(series.feeFactor);
  const cap = series.maxLeverage;
  if (series.herdFeeBp == null || series.feeFactor == null || cap == null) return null;
  if (!Number.isFinite(rateBp) || !Number.isFinite(factor) || !(cap >= 1)) return null;
  const cell = (leverage: number): FeeCell => ({ leverage, percent: herdFeePercent(rateBp, factor, leverage) });
  return {
    marketId: market.id,
    symbol: market.symbol,
    title: market.title,
    odds: market.kind === "ODDS",
    interval: series.interval,
    durationSec: series.durationSec,
    rateBp,
    factor,
    cells: FEE_TABLE_LEVERAGES.map((l) => (l <= cap ? cell(l) : null)),
    max: cell(cap),
  };
}

/** The table's rows: open markets in the server's order, the short round before the long one. */
export function feeRows(markets: readonly MarketDto[]): FeeRow[] {
  const rows: FeeRow[] = [];
  for (const market of markets) {
    if (market.status !== "ACTIVE") continue;
    for (const series of market.series ?? []) {
      const row = rowOf(market, series);
      if (row) rows.push(row);
    }
  }
  return rows;
}

/** `k` of one kind of round, from the rows (the page quotes it in the formula); null when no row of that kind exists. */
export function factorOf(rows: readonly FeeRow[], interval: Interval, odds: boolean): number | null {
  return rows.find((r) => r.interval === interval && r.odds === odds)?.factor ?? null;
}
