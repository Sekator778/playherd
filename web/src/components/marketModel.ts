/**
 * What a market's `MarketDto` says, read the way the lobby card, the room and the positions strip
 * need it: pure functions, no React. (The lobby's filter/sort/URL rules sit on top of these in
 * `pages/lobbyModel.ts`.)
 */
import type { Direction, EpochMs, MarketDto, MarketKind, MarketPriceDto, MarketSeriesDto } from "../api/contract";
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

// ---------------------------------------------------------------------------
// Where a number comes from (Р-74, Р-76)
// ---------------------------------------------------------------------------

/** What a market, a round or a price can name as its source: an exchange, Polymarket, or `synthetic` (no live source). */
export type SourceId = MarketDto["source"];

/** The exchange a shown price is from (`MarketPriceDto.venue`). */
export type PriceVenue = NonNullable<MarketPriceDto["venue"]>;

const SOURCE_NAMES: Readonly<Record<SourceId, string>> = {
  hyperliquid: "Hyperliquid",
  coinbase: "Coinbase",
  kraken: "Kraken",
  polymarket: "Polymarket",
  synthetic: "Simulated",
};

/**
 * A source as a player knows it: "Coinbase", "Kraken", "Hyperliquid" (its spot market and its perpetuals alike),
 * "Polymarket", "Simulated" (no live source). A name this build does not know — a newer server may add an exchange —
 * is printed as the server sent it, capitalised, never as another source's name; no name at all is `null`, and a page
 * then claims no source (never "Hyperliquid" by default).
 */
export function sourceName(source: string | null | undefined): string | null {
  const raw = typeof source === "string" ? source.trim() : "";
  if (raw === "") return null;
  if (Object.hasOwn(SOURCE_NAMES, raw)) return SOURCE_NAMES[raw as SourceId];
  return raw.charAt(0).toUpperCase() + raw.slice(1);
}

/**
 * What a number really comes from, in the words of the disclosure lines (Р-33): "Price from Coinbase", "Price from
 * Kraken", "Price from Hyperliquid", "Odds from Polymarket", "Simulated demo prices (no live source)"; `null` for a
 * source nobody names.
 */
export function originPhrase(source: string | null | undefined): string | null {
  const name = sourceName(source);
  if (name === null) return null;
  if (source === "polymarket") return "Odds from Polymarket";
  if (source === "synthetic") return "Simulated demo prices (no live source)";
  return `Price from ${name}`;
}

/** The start of the hover text of an `ODDS` tag: whose odds they are, when the market says — a simulated market is not
 *  a Polymarket one, and a result tile that does not know its market's source claims none. */
export function oddsMarketPhrase(source: string | null | undefined): string {
  if (source === "synthetic") return "A simulated odds market";
  const name = sourceName(source);
  return name ? `A ${name} odds market` : "An odds market";
}

/** A shown price that is the BACKUP exchange's (Р-76): which one, and whose it stands in for. */
export interface BackupPrice {
  /** The exchange the number is from: "Kraken". */
  name: string;
  /** The market's own exchange, which has no fresh price: "Coinbase" (`null` when the server does not name it). */
  primaryName: string | null;
}

/**
 * The market's shown price is the backup exchange's, not its own (`MarketPriceDto.venue`): a crypto market whose main
 * exchange has no fresh price shows its backup's, and says so. `null` for the main exchange's price — an absent venue,
 * a null one, or one equal to the market's own source (a server that names the venue of every price) — and when there
 * is no price.
 */
export function backupPriceOf(market: Pick<MarketDto, "source" | "price">): BackupPrice | null {
  const venue = market.price?.venue;
  if (!venue || venue === market.source) return null;
  const name = sourceName(venue);
  return name === null ? null : { name, primaryName: sourceName(market.source) };
}

/** Why the number is another exchange's, in one sentence: the hover text of the "Kraken price" marks and the chart's caption. */
export function backupPriceNote(backup: BackupPrice): string {
  return `${backup.primaryName ?? "The main exchange"} has no fresh price right now, so this is ${backup.name}'s — its backup.`;
}

/** A round on the backup exchange (Р-76): which one, and the main exchange that could not price its start. */
export interface BackupRound {
  /** The exchange the round runs on: "Kraken". */
  name: string;
  /** The market's own exchange: "Coinbase". */
  primaryName: string;
}

/**
 * The round runs (or ran) on its market's BACKUP exchange: a round runs entirely on one exchange, chosen when it starts —
 * the main one if it has a live price then, else the backup (`RoundBase.source`) — so its open, its close and every
 * liquidation are that exchange's. Needs the market, to know which of the two is the main one: `null` for a round on the
 * market's own exchange, for a round that names no exchange, and for a market that is not on the list any more or names no
 * backup (the round's own source line still names where its prices are from).
 */
export function backupRoundOf(
  round: { source?: SourceId | null },
  market: Pick<MarketDto, "source" | "backupSource"> | undefined,
): BackupRound | null {
  if (!market || !round.source || !market.backupSource) return null;
  if (round.source !== market.backupSource || round.source === market.source) return null;
  const name = sourceName(round.source);
  const primaryName = sourceName(market.source);
  return name === null || primaryName === null ? null : { name, primaryName };
}

/** Which round a sentence is about: `this` — the round whose own page it is on; `live` — the round now running (or just
 *  ended) among the rounds of a room, where the round open for bets is another one and has no exchange yet. */
export type RoundSubject = "this" | "live";

/** What the page says about such a round, in the tense of its phase. */
export function backupRoundNote(backup: BackupRound, phase: "RUNNING" | "SETTLED", subject: RoundSubject = "this"): string {
  const runs = phase === "SETTLED" ? "ran" : "runs";
  const are = phase === "SETTLED" ? "were" : "are";
  const which = subject === "live" ? (phase === "SETTLED" ? "The round that just ended" : "The live round") : "This round";
  return `${which} ${runs} on ${backup.name}, the backup exchange — ${backup.primaryName} had no price when it started. Its open and close prices and every liquidation ${are} ${backup.name}'s.`;
}
