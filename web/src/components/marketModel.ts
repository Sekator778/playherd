/**
 * What a market's `MarketDto` says, read the way the lobby card, the room and the positions strip
 * need it: pure functions, no React. (The lobby's filter/sort/URL rules sit on top of these in
 * `pages/lobbyModel.ts`.)
 */
import type { Direction, EpochMs, MarketDto, MarketSeriesDto } from "../api/contract";

/** A market that takes bets: not closed. Closed ones stay in `markets()` for a while (a card can say
 *  why it left) but take no more bets. */
export function isOpenMarket(market: Pick<MarketDto, "status">): boolean {
  return market.status === "ACTIVE";
}

/** The series a card talks about: the shortest (listed first, `MarketDto.series`) — always something fresh to say. */
export function leadSeries(market: Pick<MarketDto, "series">): MarketSeriesDto | undefined {
  return market.series[0];
}

/** The stake of the round open for betting in the lead series (both sides, PLAY) — what the Hot sort ranks by. */
export function marketPool(market: Pick<MarketDto, "series">): number {
  const series = leadSeries(market);
  if (!series) return 0;
  const pool = Number(series.upStake) + Number(series.downStake);
  return Number.isFinite(pool) ? pool : 0;
}

export interface CrowdSplit {
  /** Whole percent of the stake on UP; DOWN is `100 − upPct`. */
  upPct: number;
  downPct: number;
  totalStake: number;
}

/** The live UP/DOWN split of an open round by stake; `null` while nothing is staked (never a made-up 50/50). */
export function crowdSplitOf(round: { upStake?: string; downStake?: string }): CrowdSplit | null {
  const up = Number(round.upStake ?? "0");
  const down = Number(round.downStake ?? "0");
  const total = up + down;
  if (!Number.isFinite(total) || total <= 0) return null;
  const upPct = Math.round((up / total) * 100);
  return { upPct, downPct: 100 - upPct, totalStake: total };
}

/** The side the herd is on: the majority by stake, `null` for a tie or an empty round (the same rule the
 *  crowd account trades by, Р-5/Р-14). Takes the server's own `majorityDirection` when it sends one. */
export function herdSideOf(round: { majorityDirection?: Direction | null; upStake?: string; downStake?: string }): Direction | null {
  if (round.majorityDirection !== undefined) return round.majorityDirection;
  const up = Number(round.upStake ?? "0");
  const down = Number(round.downStake ?? "0");
  if (!Number.isFinite(up) || !Number.isFinite(down) || up === down) return null;
  return up > down ? "UP" : "DOWN";
}

/** When something about the market closes next: the lead series' betting window, or the underlying
 *  event itself when that ends first (`ODDS` — the market then closes and open bets are refunded). */
export function nextCloseMs(market: Pick<MarketDto, "series" | "endsAtMs">): EpochMs {
  const series = leadSeries(market);
  const betting = series ? series.bettingClosesAtMs : Number.POSITIVE_INFINITY;
  return Math.min(betting, market.endsAtMs ?? Number.POSITIVE_INFINITY);
}

/** Where a sports game stands against its kick-off (`MarketDto.eventStartsAtMs`): not started yet — with the time
 *  left — or under way. `null` for everything that has no kick-off, and for a closed market (nothing to say). */
export type Kickoff = { state: "upcoming"; inMs: number } | { state: "live" };

export function kickoffOf(market: Pick<MarketDto, "status" | "eventStartsAtMs">, nowMs: EpochMs): Kickoff | null {
  const startsAt = market.eventStartsAtMs;
  if (!isOpenMarket(market) || startsAt === null || startsAt === undefined) return null;
  return startsAt > nowMs ? { state: "upcoming", inMs: startsAt - nowMs } : { state: "live" };
}
