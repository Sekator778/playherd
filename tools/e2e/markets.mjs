// What the multi-market run (tools/e2e/run-markets.mjs) decides and checks, as functions of what the stand says —
// no network, no clock, no wallet: which market of each price source to play (and why a family is skipped), what
// leverage a market allows, and what the stand's own public documents of a played round must agree on. Tested offline
// in markets.test.mjs against the shapes the server sends (`MarketDto`, `RoundSettled`, results.json, the oracle
// records journal and the market registry: server/.../dto/Markets.java, Rounds.java, util/ResultsHasher.java,
// api/OracleRecordsResource.java, api/MarketRegistryResource.java).

/**
 * The price sources of the stand, one family each: a run plays one market of every family it can (the BTC run,
 * `make e2e`, plays one crypto market and nothing else).
 *
 * - `spot`: crypto priced by the exchange of the asset itself — Coinbase for BTC, ETH, SOL, XRP and DOGE, Hyperliquid's
 *   spot market for HYPE (docs/research/markets_architecture.md §3.4); market ids `spot_<SYMBOL>`.
 * - `xyz`: stocks, indices and commodities, perpetuals of the Hyperliquid dex `xyz`; market ids `xyz_<SYMBOL>`.
 * - `odds`: the chance of one outcome of an event, Polymarket (kind `ODDS`); market ids `PM<id>`.
 */
export const FAMILIES = [
  {
    id: "spot",
    title: "spot crypto",
    matches: (m) => m.kind === "PERP" && m.category === "crypto" && m.id.startsWith("spot_"),
    // The most liquid first; whatever else is listed follows in the lobby's order.
    preferred: ["spot_BTC", "spot_ETH", "spot_SOL", "spot_XRP", "spot_DOGE", "spot_HYPE"],
  },
  {
    id: "xyz",
    title: "xyz",
    matches: (m) => m.kind === "PERP" && m.id.startsWith("xyz_"),
    // Which of them trade outside US hours is not measured (docs/HANDOFF.md §6.2): the first one with a live price is taken.
    preferred: ["xyz_SP500", "xyz_NVDA", "xyz_GOLD", "xyz_TSLA", "xyz_XYZ100", "xyz_BRENTOIL"],
  },
  {
    id: "odds",
    title: "odds",
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

/** The bets for `market`: the plan with every leverage cut to what the market allows. */
export function betPlanFor(market) {
  return BET_PLAN.map((bet) => ({ ...bet, leverage: clampLeverage(bet.leverage, market.maxLeverage) }));
}

/** The shortest series of the market, the one of the 3-minute rounds on the stand (`interval` `HOURLY`, Р-46). */
export function hourlySeries(market) {
  return (market.series ?? []).find((s) => s.interval === "HOURLY") ?? null;
}

/**
 * Why `market` cannot be played now — `null` when it can. Not played: a market that is closed, that has no price yet
 * or whose price is stale (no live source behind it; the stand then settles nothing for it, Р-77/Р-79), whose series
 * is not running or has no round open for bets, whose underlying event is about to end (its rounds would be cancelled),
 * or that allows no leverage. `serverTimeMs` is the stand's own clock (`MarketsDto.serverTimeMs`).
 */
export function unplayableReason(market, { serverTimeMs }) {
  if (market.status !== "ACTIVE") {
    return `closed${market.closeReason ? ` (${market.closeReason})` : ""}`;
  }
  if (!market.price) return "no price yet";
  if (market.price.stale) return "its price is stale";
  const series = hourlySeries(market);
  if (!series) return "no running series";
  if (!series.bettingRoundId) return "no round open for bets";
  if (!(market.maxLeverage >= 2)) return `it allows no leverage (maxLeverage ${market.maxLeverage})`;
  if (market.kind === "ODDS" && market.endsAtMs != null) {
    const needMs = 3 * series.durationSec * 1000 + 60_000; // the wait for the window, the round, the settlement
    if (market.endsAtMs - serverTimeMs < needMs) return "the event it is about ends within minutes";
  }
  return null;
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
 * played. A family with no playable market is skipped, with the reason — never an error: a stand that lists no xyz
 * market, a weekend with no live price on the stocks, a Polymarket that is out of reach are all ordinary days.
 *
 * @returns {{ plays: {family, market, series}[], skips: {family, reason: string}[] }}
 */
export function pickMarkets(lobby) {
  const plays = [];
  const skips = [];
  for (const family of FAMILIES) {
    const tried = inTryOrder(family, lobby.markets);
    if (tried.length === 0) {
      skips.push({ family, reason: `no ${family.title} market is listed` });
      continue;
    }
    let picked = null;
    const reasons = [];
    for (const market of tried) {
      const reason = unplayableReason(market, { serverTimeMs: lobby.serverTimeMs });
      if (reason === null) {
        picked = market;
        break;
      }
      reasons.push(`${market.id}: ${reason}`);
    }
    if (picked) {
      plays.push({ family, market: picked, series: hourlySeries(picked) });
    } else {
      const shown = reasons.slice(0, 4).join("; ") + (reasons.length > 4 ? `; … (${reasons.length} in all)` : "");
      skips.push({ family, reason: `none of the ${tried.length} listed can be played — ${shown}` });
    }
  }
  return { plays, skips };
}

// --- what the stand's own documents must agree on ---

/**
 * A raw price string of a market (`priceDecimals` fractional digits, Т-3) as the integer the oracle record and the
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
 * nothing). All integers, mojos.
 */
export function expectedBalance({ before, stake, payout }) {
  return before - stake + payout;
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
