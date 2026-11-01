/**
 * The "All fees" table (D-102, `docs/research/fee_display_venues_2026-10-18.md` §4): for each market and round length, the herd fee as a share
 * of the stake at x3, x10, x25 and the market's own highest leverage for a stake up to 250 PLAY.
 *
 * Nothing here is a fee of its own: `r_m` (`MarketSeriesDto.herdFeeBp`), `k` (`feeFactor`), the cap (`maxLeverage`) and the shared rules (the network fee, the guard,
 * the stake tiers: `MarketsDto.economy`) are the server's, read from `markets()`; the page only applies the server's formula
 * `stake * min(guard, k * r_m * L)`. A series the server did not describe fully, or a server that sends no `economy`, gives no rows: no table,
 * no constant of our own.
 */
import type { EconomyRulesDto, Interval, MarketDto, MarketSeriesDto } from "../api/contract";

/** The leverages every row is priced at, besides the market's own highest. */
export const FEE_TABLE_LEVERAGES = [3, 10, 25] as const;

/** The rules every market shares, read from `markets().economy` (the server's `EconomyRules`): the page holds no copy of them. */
export interface FeeRules {
  /** The flat network fee of a bet, PLAY. */
  networkFee: string;
  /** The guard on the herd fee: it never takes more than this share of the stake, in percent. */
  guardPercent: number;
  /** The three stake bounds (PLAY) of the bounded tiers, smallest first: up to `bounds[0]` the market's highest boost, up to `bounds[1]` 1/`divisors[1]` of it, and so on. */
  bounds: [number, number, number];
  /** The divisor of each of the four tiers, the last one open-ended (above `bounds[2]`). */
  divisors: [number, number, number, number];
}

/** The server's rules, or null when the server did not send them completely (an older server, or a malformed answer): then there is no table. */
export function feeRules(economy: EconomyRulesDto | null | undefined): FeeRules | null {
  if (!economy) return null;
  const guardPercent = Number(economy.herdFeeGuardPercent);
  const tiers = economy.stakeTiers;
  if (!(Number(economy.networkFeePlay) >= 0) || !(guardPercent > 0) || !Array.isArray(tiers) || tiers.length !== 4) return null;
  const bounded = tiers.slice(0, 3).every((t) => typeof t.upToPlay === "number" && t.divisor >= 1);
  if (!bounded || tiers[3].upToPlay !== null || !(tiers[3].divisor >= 1)) return null;
  return {
    networkFee: economy.networkFeePlay,
    guardPercent,
    bounds: [tiers[0].upToPlay as number, tiers[1].upToPlay as number, tiers[2].upToPlay as number],
    divisors: [tiers[0].divisor, tiers[1].divisor, tiers[2].divisor, tiers[3].divisor],
  };
}

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

/** `min(guard, k * r_m * L)` as a percent of the stake; `guardPercent` is the server's (`FeeRules.guardPercent`). */
export function herdFeePercent(rateBp: number, factor: number, leverage: number, guardPercent: number): number {
  return Math.min(guardPercent, (factor * rateBp * leverage) / 100);
}

/** "0.105 %", "2.3 %", "20 %": up to three decimals below 1 %, two above, trailing zeros dropped. */
export function formatFeeShare(percent: number): string {
  if (!Number.isFinite(percent)) return "—";
  const text = percent < 1 ? percent.toFixed(3) : percent.toFixed(2);
  return `${text.replace(/\.?0+$/, "")} %`;
}

function rowOf(market: MarketDto, series: MarketSeriesDto, guardPercent: number): FeeRow | null {
  const rateBp = Number(series.herdFeeBp);
  const factor = Number(series.feeFactor);
  const cap = series.maxLeverage;
  if (series.herdFeeBp == null || series.feeFactor == null || cap == null) return null;
  if (!Number.isFinite(rateBp) || !Number.isFinite(factor) || !(cap >= 1)) return null;
  const cell = (leverage: number): FeeCell => ({ leverage, percent: herdFeePercent(rateBp, factor, leverage, guardPercent) });
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
export function feeRows(markets: readonly MarketDto[], rules: FeeRules | null): FeeRow[] {
  const rows: FeeRow[] = [];
  if (!rules) return rows;
  for (const market of markets) {
    if (market.status !== "ACTIVE") continue;
    for (const series of market.series ?? []) {
      const row = rowOf(market, series, rules.guardPercent);
      if (row) rows.push(row);
    }
  }
  return rows;
}

/** `k` of one kind of round, from the rows (the page quotes it in the formula); null when no row of that kind exists. */
export function factorOf(rows: readonly FeeRow[], interval: Interval, odds: boolean): number | null {
  return rows.find((r) => r.interval === interval && r.odds === odds)?.factor ?? null;
}
