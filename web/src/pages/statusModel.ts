/**
 * What the public Status page (`StatusPage.tsx`) says, as pure functions over the DTOs: where prices come from in
 * plain words, which exchange (or Polymarket) is behind which markets, how old each market's price is and whether it
 * counts as stale, the oracle's balance in TXCH, and how a "load older" page of the cancellations log is asked for. No
 * React here, so every sentence has a test.
 */
import type { CancellationDto, MarketDto, PageParams, ServerInfoDto } from "../api/contract";
import { mergeNewestFirst, nextPageParams, type PageOps } from "../api/paging";
import { sourceName, type SourceId } from "../components/marketModel";
import { formatDuration } from "../format";

export { sourceName };

export type PriceSourceKind = "live" | "synthetic" | "replay";

/** Where prices come from: the server's own word (`priceSource`), else what its `mode` implies (a server that
 *  predates the field: `replay` mode replays, anything else is live). */
export function priceSourceOf(info: Pick<ServerInfoDto, "mode" | "priceSource">): PriceSourceKind {
  return info.priceSource ?? (info.mode === "replay" ? "replay" : "live");
}

export interface PriceSourceWording {
  kind: PriceSourceKind;
  /** The short name ("Live", "Simulated", "Replay"). */
  title: string;
  /** One or two plain sentences: what the number on a card really is. */
  detail: string;
}

/** `2026-09-01…` → `2026-09-01` (the date part of an ISO instant, as the replay line prints it). */
function dateOf(iso: string): string {
  return iso.slice(0, 10);
}

export function describePriceSource(info: Pick<ServerInfoDto, "mode" | "priceSource" | "replay">): PriceSourceWording {
  const kind = priceSourceOf(info);
  switch (kind) {
    case "live":
      return {
        kind,
        title: "Live",
        detail:
          "Prices and odds are the sources' own current numbers — prices from the exchanges themselves (Coinbase and Hyperliquid, with Kraken and Coinbase as backups), odds from Polymarket — read by our server every few seconds. Rounds open and settle on them.",
      };
    case "synthetic":
      return {
        kind,
        title: "Simulated",
        detail:
          "No real source: the server makes up prices with a deterministic random walk. Nothing here follows a real market — this is how tests and offline runs work.",
      };
    case "replay":
      return {
        kind,
        title: "Replay",
        detail: info.replay
          ? `Recorded BTC prices from ${dateOf(info.replay.sourceFromIso)} to ${dateOf(info.replay.sourceToIso)}, replayed ${info.replay.speedup}× faster than they happened. Only BTC, and not today's price.`
          : "Recorded prices replayed faster than they happened — not today's prices.",
      };
  }
}

/**
 * The two round lengths the server reports (`ServerInfoDto.roundDurationsSec`, keyed `HOURLY` / `DAILY` whatever their
 * real length) as the words the page prints: `{ short: "3 MIN", long: "15 MIN" }`. `null` unless BOTH are real,
 * positive, finite numbers — a server that spells the keys otherwise, or sends a null, gets no "Round lengths" row at
 * all rather than "NaN SEC" (a row that cannot be read is left out, never printed broken).
 */
export function roundLengthsOf(info: Pick<ServerInfoDto, "roundDurationsSec">): { short: string; long: string } | null {
  const durations: Partial<ServerInfoDto["roundDurationsSec"]> | null | undefined = info.roundDurationsSec;
  const short = durations?.HOURLY;
  const long = durations?.DAILY;
  const usable = (n: unknown): n is number => typeof n === "number" && Number.isFinite(n) && n > 0;
  return usable(short) && usable(long) ? { short: formatDuration(short), long: formatDuration(long) } : null;
}

/** How old a price is, to the second while it is young: `4 s ago`, `2 min ago`, `1 h 5 min ago`. Never negative. */
export function ageText(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  if (total < 60) return `${total} s ago`;
  const minutes = Math.floor(total / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} h ago` : `${hours} h ${rest} min ago`;
}

export type FreshnessKind = "fresh" | "stale" | "none" | "closed";

export interface Freshness {
  kind: FreshnessKind;
  /** The state as one word — the thing that carries the meaning, colour only dresses it. */
  word: string;
  /** How old the newest price is; `null` when there is none. */
  ageMs: number | null;
}

/** One market's price, as the Status page reports it: closed, no price yet, stale (the server's own flag —
 *  `MarketPriceDto.stale` — the source's limit is the server's), or live. */
export function freshnessOf(market: Pick<MarketDto, "status" | "price">, nowMs: number): Freshness {
  const ageMs = market.price ? Math.max(0, nowMs - market.price.asOfMs) : null;
  if (market.status === "CLOSED") return { kind: "closed", word: "Closed", ageMs };
  if (!market.price) return { kind: "none", word: "No price yet", ageMs: null };
  if (market.price.stale) return { kind: "stale", word: "Stale", ageMs };
  return { kind: "fresh", word: "Live", ageMs };
}

export interface MarketsSummary {
  total: number;
  /** Open markets with a fresh price. */
  fresh: number;
  stale: number;
  noPrice: number;
  closed: number;
}

export function summarizeMarkets(markets: readonly MarketDto[], nowMs: number): MarketsSummary {
  const summary: MarketsSummary = { total: markets.length, fresh: 0, stale: 0, noPrice: 0, closed: 0 };
  for (const market of markets) {
    const kind = freshnessOf(market, nowMs).kind;
    if (kind === "fresh") summary.fresh += 1;
    else if (kind === "stale") summary.stale += 1;
    else if (kind === "none") summary.noPrice += 1;
    else summary.closed += 1;
  }
  return summary;
}

/** The order the sources are listed in: the exchanges that price crypto first, then the odds source, then the simulation. */
const SOURCE_ORDER: readonly SourceId[] = ["coinbase", "kraken", "hyperliquid", "polymarket", "synthetic"];

/**
 * One source on the Status page — an exchange, Polymarket or the simulation — as the open markets say it. The public
 * API has no list of price feeds (the operator's `/api/admin/status` has, behind a key), so what the page can tell of a
 * source is what its markets show: which it is the main source of, which it stands behind as a backup (Р-76), whose
 * shown price is its own although it is only their backup (the main exchange has no fresh price), and how the prices
 * of its main markets are doing.
 */
export interface SourceSummary {
  source: SourceId;
  /** "Coinbase", "Kraken", "Hyperliquid", "Polymarket" — as a player knows it. */
  name: string;
  /** Open markets it is the main source of (`MarketDto.source`), in listing order. */
  main: MarketDto[];
  /** Open markets it is the backup of (`MarketDto.backupSource`). */
  backupFor: MarketDto[];
  /** Open markets that show this source's price now although it is only their backup (`MarketPriceDto.venue`). */
  shownFor: MarketDto[];
  /** The prices of its `main` markets, counted like the markets summary does: live, stale, no price yet. */
  fresh: number;
  stale: number;
  noPrice: number;
}

/** The sources behind the open markets, each with its markets (closed ones are not served by anyone any more). Exchanges
 *  first, as {@link SOURCE_ORDER} has them; a source this build does not know follows, in the order the list names it. */
export function summarizeSources(markets: readonly MarketDto[], nowMs: number): SourceSummary[] {
  const bySource = new Map<SourceId, SourceSummary>();
  const entry = (source: SourceId): SourceSummary | null => {
    const name = sourceName(source);
    if (name === null) return null;
    let summary = bySource.get(source);
    if (!summary) {
      summary = { source, name, main: [], backupFor: [], shownFor: [], fresh: 0, stale: 0, noPrice: 0 };
      bySource.set(source, summary);
    }
    return summary;
  };
  for (const market of markets) {
    if (market.status !== "ACTIVE") continue;
    const main = entry(market.source);
    if (main) {
      main.main.push(market);
      const kind = freshnessOf(market, nowMs).kind;
      if (kind === "fresh") main.fresh += 1;
      else if (kind === "stale") main.stale += 1;
      else main.noPrice += 1;
    }
    const backup = market.backupSource ? entry(market.backupSource) : null;
    if (backup) {
      backup.backupFor.push(market);
      const venue = market.price?.venue;
      if (venue && venue === market.backupSource) backup.shownFor.push(market);
    }
  }
  const rank = (source: SourceId) => {
    const at = SOURCE_ORDER.indexOf(source);
    return at < 0 ? SOURCE_ORDER.length : at;
  };
  return [...bySource.values()].sort((a, b) => rank(a.source) - rank(b.source));
}

/** A list of market tickers for a sentence: the tickers themselves while they are few, else how many ("7 markets"). */
export function marketsList(markets: readonly Pick<MarketDto, "symbol">[], max = 6): string {
  if (markets.length <= max) return markets.map((m) => m.symbol).join(", ");
  return `${markets.length} markets`;
}

const MOJOS_PER_COIN = 1_000_000_000_000n;

/**
 * The operator key's balance (`ServerInfoDto.oracle.operatorBalanceMojos`, a decimal string of mojos) as TXCH
 * with four decimals: `500000000000` → `0.5000 TXCH`. `null` (the chain service does not say) is a dash; a string
 * that is not a whole number is shown as it came, in mojos, rather than guessed at.
 */
export function formatOperatorBalance(mojos: string | null | undefined): string {
  if (mojos === null || mojos === undefined) return "—";
  if (!/^\d+$/.test(mojos)) return `${mojos} mojos`;
  const amount = BigInt(mojos);
  const whole = amount / MOJOS_PER_COIN;
  const fraction = (amount % MOJOS_PER_COIN).toString().padStart(12, "0").slice(0, 4);
  return `${whole.toLocaleString("en-US")}.${fraction} TXCH`;
}

/** The command that checks the oracle against the chain, filled in for this server (the repository's own
 *  `tools/verify-oracle/README.md`). */
export function verifierCommand(launcherId: string | null | undefined, origin: string): string {
  return `python3 tools/verify-oracle/verify_oracle.py ${launcherId || "<launcher id>"} --stand-url ${origin}`;
}

// ---------------------------------------------------------------------------
// The cancellations log
// ---------------------------------------------------------------------------

export const CANCELLATIONS_PAGE = 10;

/** How the cancellations log pages (`api/paging.ts`): by round id, by `cancelledAtMs`, newest first. */
export const CANCELLATION_OPS: PageOps<CancellationDto> = {
  keyOf: (c) => c.roundId,
  atMsOf: (c) => c.cancelledAtMs,
  compare: (a, b) => b.cancelledAtMs - a.cancelledAtMs,
};

/**
 * The parameters of the page after `known` (newest first): `PageParams.before` is "items strictly before this
 * timestamp", and a market that closes cancels several rounds at one instant — so a cursor of exactly the oldest
 * timestamp would skip the rest of that instant. One millisecond later asks for it again; the caller drops what it
 * already holds (`mergeCancellations`). `limit` grows when a whole page turned out to be repeats (`api/paging.ts`).
 */
export function nextCancellationsParams(known: readonly CancellationDto[], limit: number): PageParams {
  return nextPageParams(known, limit, CANCELLATION_OPS);
}

/** `known` followed by whatever of `incoming` it does not hold yet (by round id), newest first. */
export function mergeCancellations(known: readonly CancellationDto[], incoming: readonly CancellationDto[]): CancellationDto[] {
  return mergeNewestFirst(known, incoming, CANCELLATION_OPS);
}

/** "1 bet" / "4 bets". */
export function betsCount(count: number): string {
  return `${count} ${count === 1 ? "bet" : "bets"}`;
}
