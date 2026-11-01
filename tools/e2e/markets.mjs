// What the multi-market run (tools/e2e/run-markets.mjs) decides and checks, as functions of what the stand says —
// no network, no wallet, and the only clock is the stand's own (`serverTimeMs`): which market of each price source to play
// (and why a family is skipped, and whether it may be), what leverage a market allows, and what the stand's own public
// documents of a played round must agree on. Tested offline in markets.test.mjs against the shapes the server sends
// (`MarketDto`, `RoundSettled`, results.json, the oracle records journal and the market registry: server/.../dto/
// Markets.java, Rounds.java, util/ResultsHasher.java, api/OracleRecordsResource.java, api/MarketRegistryResource.java).

// --- the weekend of the underlying markets (xyz) ---

/**
 * The weekly closure of the markets behind the xyz perpetuals, New York time, per lobby category
 * (docs/research/primary_sources.md §2.4, from https://docs.trade.xyz and Pyth `market_hours`, 2026-10-03): stocks trade
 * from Sunday 20:00 to Friday 20:00; indices and gold from Sunday 18:00 to Friday 17:00; Brent from Sunday 18:00 to Friday
 * 18:00. The category is called closed only when every market in it is, so `commodities` closes with Brent (18:00). The
 * daily break of 17:00–18:00 on weekdays is not counted, and neither is a holiday: there is no calendar here, and the
 * stand's own behaviour on one was never measured (UNVERIFIED) — a holiday is seen only as a market the stand closed.
 */
const WEEKLY_CLOSURE = {
  stocks: { fridayCloseMin: 20 * 60, sundayOpenMin: 20 * 60 },
  indices: { fridayCloseMin: 17 * 60, sundayOpenMin: 18 * 60 },
  commodities: { fridayCloseMin: 18 * 60, sundayOpenMin: 18 * 60 },
};

const NEW_YORK_CLOCK = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/New_York",
  weekday: "short",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/**
 * Whether the market behind a market of `category` is closed for the weekend at `ms` (the stand's own clock, never this
 * machine's): from the Friday close to the Sunday opening of the category, New York time. `false` for a category that has
 * no such hours (crypto, politics, sports) and for one this table does not know — a skip that rests on this answer is
 * never taken on a guess.
 */
export function underlyingClosedAt(category, ms) {
  const hours = WEEKLY_CLOSURE[category];
  if (!hours || !Number.isFinite(ms)) return false;
  const parts = Object.fromEntries(NEW_YORK_CLOCK.formatToParts(ms).map((p) => [p.type, p.value]));
  const minuteOfDay = Number(parts.hour) * 60 + Number(parts.minute);
  switch (parts.weekday) {
    case "Sat":
      return true;
    case "Fri":
      return minuteOfDay >= hours.fridayCloseMin;
    case "Sun":
      return minuteOfDay < hours.sundayOpenMin;
    default:
      return false;
  }
}

// --- what a skip may be for ---

// Why a market is not played — a market that cannot be played now, or one a round of which was cancelled — is a `problem`,
// `{ kind, reason }`: the `kind` is what the skip rules of the families below are written in, the `reason` the words that are
// printed. The kinds:
//
// - `closed`: the market is closed — `status` not `ACTIVE` at the pick, or a round cancelled with the reason `market closed:
//   <why>` (what the server writes whenever a catalog closes a market: the source event ended, the chance left the 3%–97%
//   band, the source stopped trading, the asset was delisted, the market moved to a new price source …). All of those are a
//   market that is closed, and nothing else is "market closed";
// - `no_price`, `stale`: the market is listed and open but has no live price;
// - `no_series`, `no_round`, `no_leverage`: it is open but has nothing to bet on;
// - `event_ending` (odds): the event it is about ends within the minutes a run takes;
// - `cancelled`: a round of it was cancelled for any other reason (no price from the source, a failed settlement, an
//   operator's cancellation …).

const CLOSED_CANCELLATION = /^\s*market closed:/i;

/** The problem of a round that was cancelled with `reason`: `closed` when the server says the market closed, else `cancelled`. */
export function cancellationProblem(reason) {
  return { kind: CLOSED_CANCELLATION.test(reason ?? "") ? "closed" : "cancelled", reason: reason || "no reason given" };
}

/**
 * The price sources of the stand, one family each: a run plays one market of every family it can (the BTC run,
 * `make e2e`, plays one crypto market and nothing else).
 *
 * - `spot`: crypto priced by the exchange of the asset itself — Coinbase for BTC, ETH, SOL, XRP and DOGE, Hyperliquid's
 *   spot market for HYPE (docs/research/markets_architecture.md §3.4); market ids `spot_<SYMBOL>`.
 * - `xyz`: stocks, indices and commodities, perpetuals of the Hyperliquid dex `xyz`; market ids `xyz_<SYMBOL>`.
 * - `odds`: the chance of one outcome of an event, Polymarket (kind `ODDS`); market ids `PM<id>`.
 *
 * A family that is not played is not always an ordinary day — `mayBeSkippedFor(problem, market, { serverTimeMs })` says
 * whether the reason (`problem`, see above) is one it may be skipped for, `skipRule` says so in words:
 *
 * - `spot` is the 24/7 family: the exchanges never close, so a spot market that is not played for want of a price (none,
 *   a stale one), of a series or of a round to bet on, or whose round was cancelled for any reason but a closed market, is a
 *   stand that is broken, not a day off. Only a closed market is a skip, and a stand that lists no spot market at all is a
 *   failure too (`alwaysOn`).
 * - `xyz` trades round the clock on Hyperliquid, but the markets behind it do not: it may be skipped for a closed market,
 *   and for a missing or stale price while the market behind it is closed for the weekend (`underlyingClosedAt`, by the
 *   stand's clock and the market's category).
 * - `odds` may be skipped for a closed market (the source event ended, the chance left the 3%–97% band, the source stopped
 *   trading …) and for an event that is about to end. An unreachable Polymarket — odds without a live price — is not on the
 *   list: that is the stand's odds source being down.
 *
 * A family that has no market listed at all is skipped, except `alwaysOn` ones: a stand may be set up without xyz or
 * without Polymarket.
 */
export const FAMILIES = [
  {
    id: "spot",
    title: "spot crypto",
    alwaysOn: true,
    skipRule: "the exchanges never close: it is skipped only when its market is closed",
    mayBeSkippedFor: (problem) => problem.kind === "closed",
    matches: (m) => m.kind === "PERP" && m.category === "crypto" && m.id.startsWith("spot_"),
    // The most liquid first; whatever else is listed follows in the lobby's order.
    preferred: ["spot_BTC", "spot_ETH", "spot_SOL", "spot_XRP", "spot_DOGE", "spot_HYPE"],
  },
  {
    id: "xyz",
    title: "xyz",
    alwaysOn: false,
    skipRule: "it is skipped only when its market is closed, or has no live price while the market behind it is closed for the weekend",
    mayBeSkippedFor: (problem, market, { serverTimeMs }) =>
      problem.kind === "closed" ||
      ((problem.kind === "no_price" || problem.kind === "stale") && underlyingClosedAt(market.category, serverTimeMs)),
    matches: (m) => m.kind === "PERP" && m.id.startsWith("xyz_"),
    // Which of them trade outside US hours is not measured (the internal notes §6.2): the first one with a live price is taken.
    preferred: ["xyz_SP500", "xyz_NVDA", "xyz_GOLD", "xyz_TSLA", "xyz_XYZ100", "xyz_BRENTOIL"],
  },
  {
    id: "odds",
    title: "odds",
    alwaysOn: false,
    skipRule: "it is skipped only when its market is closed (the source event ended, the chance left the 3%–97% band …) or its event is about to end",
    mayBeSkippedFor: (problem) => problem.kind === "closed" || problem.kind === "event_ending",
    matches: (m) => m.kind === "ODDS",
    // Not by name: the one whose chance is nearest to one half is the least likely to leave the 3%–97% band the catalog
    // closes a market at (docs/research/markets_architecture.md §3.3) before the round is over.
    nearestToEven: true,
  },
];

/** The bets of one market: the BTC run's four (mixed directions, leverage 3 to 40), before the market's own ceiling. */
export const BET_PLAN = [
  { direction: "UP", leverage: 5, stake: "500.000" },
  { direction: "DOWN", leverage: 10, stake: "300.000" },
  { direction: "UP", leverage: 40, stake: "100.000" }, // the highest leverage there is
  { direction: "DOWN", leverage: 3, stake: "1000.000" },
];

/** Leverage runs from x2 to the ceiling of the market (`MarketDto.maxLeverage`, 2..40). */
export function clampLeverage(wanted, maxLeverage) {
  return Math.max(2, Math.min(wanted, maxLeverage));
}

/**
 * Economy plan A3: the leverage a stake allows, the cap of the market for the round length divided by 1 / 2 / 5 / 10 for a stake of
 * up to 250 / 500 / 1 200 / more PLAY (at least 2).
 */
export function maxLeverageForStake(cap, stakePlay) {
  const divisor = stakePlay <= 250 ? 1 : stakePlay <= 500 ? 2 : stakePlay <= 1200 ? 5 : 10;
  return Math.max(2, Math.floor(cap / divisor));
}

/** The bets for `market`: the plan with every leverage cut to what the market allows for the round length and the stake. */
export function betPlanFor(market) {
  const cap = hourlySeries(market)?.maxLeverage ?? market.maxLeverage;
  return BET_PLAN.map((bet) => ({ ...bet, leverage: clampLeverage(bet.leverage, maxLeverageForStake(cap, Number(bet.stake))) }));
}

/** The shortest series of the market, the one of the 3-minute rounds on the stand (`interval` `HOURLY`, D-46). */
export function hourlySeries(market) {
  return (market.series ?? []).find((s) => s.interval === "HOURLY") ?? null;
}

/**
 * What keeps `market` from being played now — `null` when nothing does (a `problem`, see above). Not played: a market that
 * is closed, that has no price yet or whose price is stale (no live source behind it; the stand then settles nothing for it,
 * D-77/D-79), whose series is not running or has no round open for bets, whose underlying event is about to end (its rounds
 * would be cancelled), or that allows no leverage. `serverTimeMs` is the stand's own clock (`MarketsDto.serverTimeMs`).
 */
export function unplayable(market, { serverTimeMs }) {
  if (market.status !== "ACTIVE") {
    return { kind: "closed", reason: `closed${market.closeReason ? ` (${market.closeReason})` : ""}` };
  }
  if (!market.price) return { kind: "no_price", reason: "no price yet" };
  if (market.price.stale) return { kind: "stale", reason: "its price is stale" };
  const series = hourlySeries(market);
  if (!series) return { kind: "no_series", reason: "no running series" };
  if (!series.bettingRoundId) return { kind: "no_round", reason: "no round open for bets" };
  if (!(market.maxLeverage >= 2)) return { kind: "no_leverage", reason: `it allows no leverage (maxLeverage ${market.maxLeverage})` };
  if (market.kind === "ODDS" && market.endsAtMs != null) {
    const needMs = 3 * series.durationSec * 1000 + 60_000; // the wait for the window, the round, the settlement
    if (market.endsAtMs - serverTimeMs < needMs) return { kind: "event_ending", reason: "the event it is about ends within minutes" };
  }
  return null;
}

/** The words of `unplayable`: why `market` cannot be played now — `null` when it can. */
export function unplayableReason(market, context) {
  return unplayable(market, context)?.reason ?? null;
}

/** The chance of an `ODDS` market as a number in 0..1 (the raw price is the probability); `NaN` when it is not one. */
function chanceOf(market) {
  return market.price ? Number.parseFloat(market.price.price) : Number.NaN;
}

/** `markets` of `family`, in the order they are tried: the preferred ones first, the rest in listing order. */
function inTryOrder(family, markets) {
  const listed = markets.filter(family.matches);
  if (family.nearestToEven) {
    const distance = (m) => {
      const chance = chanceOf(m);
      return Number.isFinite(chance) ? Math.abs(chance - 0.5) : Number.POSITIVE_INFINITY;
    };
    return listed.map((m, i) => ({ m, i })).sort((a, b) => distance(a.m) - distance(b.m) || a.i - b.i).map(({ m }) => m);
  }
  const rank = (m) => {
    const at = (family.preferred ?? []).indexOf(m.id);
    return at < 0 ? Number.MAX_SAFE_INTEGER : at;
  };
  return listed.map((m, i) => ({ m, i })).sort((a, b) => rank(a.m) - rank(b.m) || a.i - b.i).map(({ m }) => m);
}

/**
 * The markets to play out of the lobby (`markets()`): one of every family, the first of its try order that can be
 * played. A family with no playable market is skipped, with the reason, and the skip says whether the family may be
 * skipped for it (`allowed`, `FAMILIES`): a stand that lists no xyz market, a closed market, an event about to end are
 * ordinary days; a spot market without a live price is a stand that is broken. `offending` are the markets whose reason the
 * family may not be skipped for, and a family that is `alwaysOn` and not listed at all.
 *
 * @returns {{ plays: {family, market, series, passedOver: string[]}[], skips: {family, reason: string, allowed: boolean, offending: string[]}[] }}
 *   `passedOver`: the markets of the family tried before the one that is played, with why each could not be.
 */
export function pickMarkets(lobby) {
  const plays = [];
  const skips = [];
  const context = { serverTimeMs: lobby.serverTimeMs };
  for (const family of FAMILIES) {
    const tried = inTryOrder(family, lobby.markets);
    if (tried.length === 0) {
      const reason = `no ${family.title} market is listed`;
      skips.push({ family, reason, allowed: !family.alwaysOn, offending: family.alwaysOn ? [reason] : [] });
      continue;
    }
    let picked = null;
    const problems = []; // { market, problem } of every market tried and given up
    for (const market of tried) {
      const problem = unplayable(market, context);
      if (problem === null) {
        picked = market;
        break;
      }
      problems.push({ market, problem });
    }
    const words = ({ market, problem }) => `${market.id}: ${problem.reason}`;
    if (picked) {
      plays.push({ family, market: picked, series: hourlySeries(picked), passedOver: problems.map(words) });
    } else {
      const shown = problems.slice(0, 4).map(words).join("; ") + (problems.length > 4 ? `; … (${problems.length} in all)` : "");
      const offending = problems.filter(({ market, problem }) => !family.mayBeSkippedFor(problem, market, context)).map(words);
      skips.push({ family, reason: `none of the ${tried.length} listed can be played — ${shown}`, allowed: offending.length === 0, offending });
    }
  }
  return { plays, skips };
}

// --- what the stand's own documents must agree on ---

/**
 * A raw price string of a market (`priceDecimals` fractional digits, T-3) as the integer the oracle record and the
 * results.json carry: the digits without the point. `price = priceCents / 10^priceDecimals`
 * (api/MarketRegistryResource.java), so no float is ever in play. `null` for anything that is not such a string.
 */
export function centsOf(rawPrice) {
  if (typeof rawPrice !== "string" || !/^\d+(\.\d+)?$/.test(rawPrice)) return null;
  return BigInt(rawPrice.replace(".", ""));
}

function sameBigInt(value, expected) {
  if (expected === null || value === null || value === undefined) return false;
  try {
    return BigInt(value) === expected;
  } catch {
    return false;
  }
}

/**
 * A settled round's balance: stake out, payout in (a flat or cancelled round pays the stake back, a liquidated one
 * nothing). The fees of the bet (herd and network, economy plan A4) go out with the stake and come back only when the bet
 * is cancelled or its round is. All integers, mojos.
 */
export function expectedBalance({ before, stake, payout, fees = 0 }) {
  return before - stake - fees + payout;
}

/** The fees a `placeBet` answer names (`feeHerd` + `feeNetwork`, economy plan A4), in mojos; 0 from a server without them. */
export function feesOfBet(bet) {
  return Math.round(parseFloat(bet?.feeHerd ?? "0") * 1000) + Math.round(parseFloat(bet?.feeNetwork ?? "0") * 1000);
}

/**
 * The checks of a round's `results.json` (FIXED INTERFACE (2), util/ResultsHasher.java): canonical (sorted keys, no
 * whitespace, positions sorted by playerId), about this round, and — what only a market other than BTC has — carrying
 * the market's id under the root key `market` (the BTC market, id `BTC`, has no such key: its documents are what they
 * were before there were markets). For a settled round the prices in it are the round's own. `text` is the exact body.
 *
 * @returns {{name: string, ok: boolean, detail?: string}[]}
 */
export function checkResultsJson({ text, market, round, roundId }) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return [{ name: "results.json is JSON", ok: false, detail: e.message }];
  }
  const checks = [];
  const positions = Array.isArray(parsed.positions) ? parsed.positions : [];
  checks.push({
    name: "results.json positions sorted by playerId",
    ok: positions.every((p, i, all) => i === 0 || all[i - 1].playerId <= p.playerId),
  });
  checks.push({ name: "results.json has no whitespace (canonical encoding)", ok: !/\s/.test(text) });
  checks.push({ name: "results.json is about this round", ok: parsed.roundId === roundId, detail: `roundId=${parsed.roundId}` });
  const expectedMarket = market.id === "BTC" ? undefined : market.id;
  checks.push({
    name: market.id === "BTC" ? "results.json has no market key (the BTC shape)" : "results.json names the market",
    ok: parsed.market === expectedMarket,
    detail: `market=${parsed.market} expected=${expectedMarket}`,
  });
  if (round.phase === "SETTLED") {
    checks.push({ name: "results.json status SETTLED", ok: parsed.status === "SETTLED", detail: `status=${parsed.status}` });
    checks.push({
      name: "results.json prices are the round's own (open and close, as integers of the market's precision)",
      ok: sameBigInt(parsed.openPriceCents, centsOf(round.openPrice)) && sameBigInt(parsed.closePriceCents, centsOf(round.closePrice)),
      detail: `open ${parsed.openPriceCents} vs ${round.openPrice}, close ${parsed.closePriceCents} vs ${round.closePrice}`,
    });
  } else {
    checks.push({ name: "results.json status CANCELLED", ok: parsed.status === "CANCELLED", detail: `status=${parsed.status}` });
  }
  return checks;
}

/** The states in which the chain service holds a record (`FIXED INTERFACE (b)`; `unknown` is "not handed over yet"). */
export const HANDED_OVER = new Set(["queued", "in_flight", "confirmed"]);

/**
 * The checks of a settled round's oracle record: the journal row the stand keeps for the `seq` the round names
 * (`oracleAnchor.seq`, `GET /api/oracle/records`) and the registry (`GET /api/markets/registry.json`) must agree with
 * the round and the market — it is the round's, of the market's, carrying the market's registry index as the asset,
 * the round's resultsHash, the round's close price, and not in conflict. `resultsVenue` is the root key `venue` of the
 * round's results.json (or `undefined`): the journal repeats it.
 *
 * @returns {{name: string, ok: boolean, detail?: string}[]}
 */
export function checkOracleRecord({ row, anchor, registry, market, round, resultsVenue }) {
  if (!row) {
    return [{ name: "the oracle journal has a row for the round's seq", ok: false, detail: `seq ${anchor?.seq}` }];
  }
  const checks = [];
  const entry = Array.isArray(registry) ? registry.find((e) => e.index === row.asset) : undefined;
  checks.push({ name: "oracle record is the round's", ok: row.roundId === round.roundId && row.seq === anchor?.seq, detail: `seq=${row.seq} roundId=${row.roundId}` });
  checks.push({ name: "oracle record is of the round's market", ok: row.marketId === market.id, detail: `marketId=${row.marketId}` });
  checks.push({
    name: "oracle record carries the market's registry index as its asset",
    ok: entry?.id === market.id && (market.oracleIndex === undefined || market.oracleIndex === row.asset),
    detail: `asset=${row.asset} -> ${entry?.id ?? "not in the registry"}, markets() says ${market.oracleIndex}`,
  });
  checks.push({ name: "oracle record carries the round's resultsHash", ok: row.resultsHash === round.resultsHash, detail: `${row.resultsHash} vs ${round.resultsHash}` });
  if (round.phase === "SETTLED") {
    checks.push({
      name: "oracle record carries the round's close price",
      ok: sameBigInt(row.priceCents, centsOf(round.closePrice)),
      detail: `priceCents=${row.priceCents} vs closePrice=${round.closePrice}`,
    });
  }
  checks.push({
    name: "oracle record names the same venue as results.json",
    ok: (row.venue ?? null) === (resultsVenue ?? null),
    detail: `journal ${row.venue ?? "none"}, results.json ${resultsVenue ?? "none"}`,
  });
  checks.push({ name: "oracle record is not in conflict", ok: row.state !== "conflict", detail: `state=${row.state}` });
  return checks;
}
