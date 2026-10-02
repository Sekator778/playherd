/**
 * What a market's `MarketDto` says, read the way the lobby card, the room and the positions strip
 * need it: pure functions, no React. (The lobby's filter/sort/URL rules sit on top of these in
 * `pages/lobbyModel.ts`.)
 */
import type { Direction, EpochMs, MarketDto, MarketKind, MarketSeriesDto } from "../api/contract";
import type { PriceFormatMarket } from "../format";

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

/** Fractional digits of a decimal string ("0.523000" → 6); `null` for anything that is not a plain decimal. */
function decimalsOfPriceString(price: string | null | undefined): number | null {
  if (typeof price !== "string" || !/^-?\d+(\.\d+)?$/.test(price)) return null;
  const dot = price.indexOf(".");
  return dot < 0 ? 0 : price.length - dot - 1;
}

/** What prints a round's prices and names where they come from. */
export interface RoundPriceInfo {
  /** The kind and price scale `formatPrice` needs. */
  format: PriceFormatMarket;
  /** `undefined` when nothing says — the page then claims no source at all (never "Hyperliquid" by default). */
  source: MarketDto["source"] | undefined;
}

/**
 * How a round page prints its prices and names their source (`RoundBase.kind` / `priceDecimals` / `source`): the
 * round's OWN fields first — a market leaves `markets()` ten minutes after it closes, its rounds stay readable for
 * good — then, for a server that does not send them yet, the market's, when it is still on the list; the price scale
 * can also be read off the price strings themselves. A source nobody names stays `undefined`: saying "Hyperliquid"
 * about an odds round would be a false claim on a trust page. The kind of a round no one names is read as a perp.
 */
export function roundPriceInfo(
  round: { kind?: MarketKind; priceDecimals?: number; source?: MarketDto["source"]; openPrice?: string | null; closePrice?: string | null },
  market?: Pick<MarketDto, "kind" | "priceDecimals" | "source">,
): RoundPriceInfo {
  const own = round.priceDecimals;
  const priceDecimals =
    typeof own === "number" && Number.isInteger(own) && own >= 0
      ? own
      : (market?.priceDecimals ?? decimalsOfPriceString(round.openPrice) ?? decimalsOfPriceString(round.closePrice) ?? 2);
  return { format: { kind: round.kind ?? market?.kind ?? "PERP", priceDecimals }, source: round.source ?? market?.source };
}

/** Outcomes that already say what they mean on their own: "to win" is never added to them. */
const SELF_EXPLAINING_OUTCOME = /^(yes|no|draw|tie|over|under)$/i;

/**
 * The line under an `ODDS` market's title (`MarketDto.subtitle`: "the outcome plus its context", "Germany · UEFA
 * Nations League"). The context can repeat the title itself — a sports market's "Steelers · Steelers vs. Browns" under
 * "Steelers vs. Browns", a politics one's "Yes · Will the U.S. invade Iran before 2027?" under that very question — and
 * then only the outcome is worth saying: "Steelers to win" for a team of a sports game, "Yes" / "Draw" / "Over" as
 * they are. Any other subtitle is shown as the server sent it; `null` when there is none.
 */
export function subtitleOf(market: Pick<MarketDto, "title" | "subtitle" | "category">): string | null {
  const subtitle = market.subtitle?.trim();
  if (!subtitle) return null;
  const at = subtitle.indexOf(" · ");
  if (at < 0) return subtitle;
  const outcome = subtitle.slice(0, at).trim();
  const context = subtitle.slice(at + 3).trim();
  const repeatsTitle = context.localeCompare(market.title.trim(), "en", { sensitivity: "base" }) === 0;
  if (!outcome || !repeatsTitle) return subtitle;
  return market.category === "sports" && !SELF_EXPLAINING_OUTCOME.test(outcome) ? `${outcome} to win` : outcome;
}
