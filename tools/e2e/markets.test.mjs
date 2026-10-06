// Offline tests of tools/e2e/markets.mjs (`make test-tools`): which markets a multi-market run plays and why it skips a
// family, and what it holds the stand's documents to. Plain `node --test`, no network, no wallet.
import assert from "node:assert/strict";
import { test } from "node:test";
import {
  BET_PLAN,
  FAMILIES,
  betPlanFor,
  centsOf,
  checkOracleRecord,
  checkResultsJson,
  clampLeverage,
  expectedBalance,
  pickMarkets,
  unplayableReason,
} from "./markets.mjs";

const NOW = 1_790_000_000_000;

/** A playable market as `markets()` sends it, with `over` laid on top. */
function market(over = {}) {
  const id = over.id ?? "spot_BTC";
  return {
    id,
    kind: "PERP",
    category: "crypto",
    symbol: id.replace(/^[a-z]+_/, ""),
    status: "ACTIVE",
    maxLeverage: 40,
    priceDecimals: 2,
    oracleIndex: 61,
    source: "coinbase",
    backupSource: "kraken",
    endsAtMs: null,
    closedAtMs: null,
    closeReason: null,
    price: { price: "93000.12", asOfMs: NOW, change24h: 0.01, stale: false, venue: null },
    series: [
      { interval: "HOURLY", durationSec: 180, bettingRoundId: `${id}-1h-2`, bettingClosesAtMs: NOW + 60_000, runningRoundId: `${id}-1h-1`, runningEndsAtMs: NOW + 60_000 },
      { interval: "DAILY", durationSec: 900, bettingRoundId: `${id}-1d-2`, bettingClosesAtMs: NOW + 600_000, runningRoundId: `${id}-1d-1`, runningEndsAtMs: NOW + 600_000 },
    ],
    ...over,
  };
}
const xyz = (symbol, over = {}) => market({ id: `xyz_${symbol}`, category: "stocks", source: "hyperliquid", backupSource: null, maxLeverage: 20, ...over });
const odds = (id, chance, over = {}) =>
  market({
    id,
    kind: "ODDS",
    category: "politics",
    source: "polymarket",
    backupSource: null,
    maxLeverage: 10,
    priceDecimals: 6,
    price: { price: chance, asOfMs: NOW, change24h: null, stale: false, venue: null },
    ...over,
  });
const lobby = (markets) => ({ serverTimeMs: NOW, categories: [], markets });

test("a run plays one market of each family: spot crypto, xyz and odds", () => {
  const { plays, skips } = pickMarkets(lobby([market(), xyz("NVDA"), odds("PM1", "0.520000")]));
  assert.deepEqual(plays.map((p) => [p.family.id, p.market.id]), [["spot", "spot_BTC"], ["xyz", "xyz_NVDA"], ["odds", "PM1"]]);
  assert.deepEqual(skips, []);
  assert.equal(plays[0].series.interval, "HOURLY", "the series that is played is the 3-minute one, not the daily");
  assert.equal(plays[0].series.durationSec, 180);
});

test("inside a family the preferred market comes first, then the lobby's order", () => {
  const spot = ["spot_HYPE", "spot_DOGE", "spot_ETH", "spot_BTC", "spot_SOL"].map((id) => market({ id }));
  assert.equal(pickMarkets(lobby(spot)).plays[0].market.id, "spot_BTC");
  assert.equal(pickMarkets(lobby(spot.filter((m) => m.id !== "spot_BTC"))).plays[0].market.id, "spot_ETH");
  const unknown = [xyz("ZZZ"), xyz("AAA")];
  assert.equal(pickMarkets(lobby(unknown)).plays.find((p) => p.family.id === "xyz").market.id, "xyz_ZZZ", "nothing preferred: the first listed");
  assert.equal(
    pickMarkets(lobby([xyz("NVDA"), xyz("SP500"), xyz("GOLD")])).plays.find((p) => p.family.id === "xyz").market.id,
    "xyz_SP500",
  );
});

test("the odds market whose chance is nearest to one half is played", () => {
  const picked = (...markets) => pickMarkets(lobby(markets)).plays.find((p) => p.family.id === "odds").market.id;
  assert.equal(picked(odds("PM1", "0.950000"), odds("PM2", "0.430000"), odds("PM3", "0.700000")), "PM2");
  assert.equal(picked(odds("PM1", "0.600000"), odds("PM2", "0.400000")), "PM1", "a tie: the first listed");
  assert.equal(picked(odds("PM1", "0.030000"), odds("PM2", "0.970000")), "PM1", "all extreme: still one is played");
  assert.equal(picked(odds("PM1", "0.500000", { price: null }), odds("PM2", "0.900000")), "PM2", "no price: not the one");
});

test("a market that cannot be played says why", () => {
  const ctx = { serverTimeMs: NOW };
  assert.equal(unplayableReason(market(), ctx), null);
  assert.equal(unplayableReason(market({ status: "CLOSED", closeReason: "The source market ended" }), ctx), "closed (The source market ended)");
  assert.equal(unplayableReason(market({ status: "CLOSED" }), ctx), "closed");
  assert.equal(unplayableReason(market({ price: null }), ctx), "no price yet");
  assert.equal(unplayableReason(market({ price: { price: "1.00", stale: true } }), ctx), "its price is stale");
  assert.equal(unplayableReason(market({ series: [] }), ctx), "no running series");
  assert.equal(unplayableReason(market({ series: [{ interval: "DAILY", durationSec: 900, bettingRoundId: "x" }] }), ctx), "no running series", "the 3-minute series is what is played");
  assert.equal(unplayableReason(market({ series: [{ interval: "HOURLY", durationSec: 180, bettingRoundId: null }] }), ctx), "no round open for bets");
  assert.match(unplayableReason(market({ maxLeverage: 1 }), ctx), /no leverage/);
  // an event that ends within the time a run takes (the wait for the window, the round, its settlement)
  assert.equal(unplayableReason(odds("PM1", "0.5", { endsAtMs: NOW + 5 * 60_000 }), ctx), "the event it is about ends within minutes");
  assert.equal(unplayableReason(odds("PM1", "0.5", { endsAtMs: NOW + 20 * 60_000 }), ctx), null);
  assert.equal(unplayableReason(odds("PM1", "0.5", { endsAtMs: null }), ctx), null);
});

test("a family whose preferred market cannot be played falls through to the next one that can", () => {
  const { plays } = pickMarkets(lobby([market({ id: "spot_BTC", price: { price: "1.00", stale: true } }), market({ id: "spot_ETH" })]));
  assert.deepEqual(plays.map((p) => p.market.id), ["spot_ETH"]);
});

test("a family with nothing to play is skipped with the reasons, never an error", () => {
  const { plays, skips } = pickMarkets(
    lobby([
      market(),
      xyz("NVDA", { status: "CLOSED", closeReason: "The source market ended" }),
      xyz("TSLA", { price: null }),
      odds("PM1", "0.5", { price: { price: "0.5", stale: true } }),
    ]),
  );
  assert.deepEqual(plays.map((p) => p.market.id), ["spot_BTC"]);
  assert.deepEqual(skips.map((s) => s.family.id), ["xyz", "odds"]);
  assert.match(skips[0].reason, /none of the 2 listed can be played/);
  assert.match(skips[0].reason, /xyz_NVDA: closed \(The source market ended\)/);
  assert.match(skips[0].reason, /xyz_TSLA: no price yet/);
  assert.match(skips[1].reason, /PM1: its price is stale/);

  const empty = pickMarkets(lobby([market()]));
  assert.deepEqual(empty.skips.map((s) => [s.family.id, s.reason]), [["xyz", "no xyz market is listed"], ["odds", "no odds market is listed"]]);

  const nothing = pickMarkets(lobby([]));
  assert.equal(nothing.plays.length, 0);
  assert.equal(nothing.skips.length, FAMILIES.length);
});

test("a long list of reasons is cut to four", () => {
  const many = Array.from({ length: 7 }, (_, i) => xyz(`S${i}`, { price: null }));
  const reason = pickMarkets(lobby(many)).skips.find((s) => s.family.id === "xyz").reason;
  assert.match(reason, /none of the 7 listed/);
  assert.match(reason, /\(7 in all\)/);
  assert.equal((reason.match(/xyz_S\d: no price yet/g) ?? []).length, 4);
});

test("markets of no family are left alone: the old BTC perpetual is not a spot market", () => {
  const { plays } = pickMarkets(lobby([market({ id: "BTC", source: "hyperliquid", backupSource: null }), market({ id: "ETH" })]));
  assert.deepEqual(plays, []);
});

test("leverage runs from x2 to the ceiling of the market", () => {
  assert.equal(clampLeverage(40, 40), 40);
  assert.equal(clampLeverage(40, 10), 10);
  assert.equal(clampLeverage(3, 10), 3);
  assert.equal(clampLeverage(1, 40), 2);
  assert.deepEqual(betPlanFor({ maxLeverage: 40 }).map((b) => b.leverage), [5, 10, 40, 3]);
  assert.deepEqual(betPlanFor({ maxLeverage: 10 }).map((b) => b.leverage), [5, 10, 10, 3], "odds go up to x10");
  assert.deepEqual(betPlanFor({ maxLeverage: 4 }).map((b) => b.leverage), [4, 4, 4, 3]);
  assert.deepEqual(betPlanFor({ maxLeverage: 2 }).map((b) => b.leverage), [2, 2, 2, 2]);
  assert.deepEqual(betPlanFor({ maxLeverage: 40 }).map((b) => [b.direction, b.stake]), BET_PLAN.map((b) => [b.direction, b.stake]), "the stakes and directions are the plan's");
  assert.deepEqual(BET_PLAN.map((b) => b.direction), ["UP", "DOWN", "UP", "DOWN"], "both sides are bet on");
});

test("a price string is the integer of its digits", () => {
  assert.equal(centsOf("93000.12"), 9300012n);
  assert.equal(centsOf("0.523000"), 523000n);
  assert.equal(centsOf("12"), 12n);
  assert.equal(centsOf("0.00000001"), 1n);
  for (const bad of [null, undefined, 12, "", "1.2.3", "-1.00", "1e5", "abc"]) assert.equal(centsOf(bad), null, String(bad));
});

test("the balance of a player after a round is the balance before, less the stake, plus the payout", () => {
  assert.equal(expectedBalance({ before: 10_000_000, stake: 500_000, payout: 1_000_000 }), 10_500_000);
  assert.equal(expectedBalance({ before: 10_000_000, stake: 500_000, payout: 0 }), 9_500_000, "liquidated");
  assert.equal(expectedBalance({ before: 10_000_000, stake: 500_000, payout: 500_000 }), 10_000_000, "flat or cancelled: the stake back");
});

// --- results.json ---

const SETTLED = { phase: "SETTLED", roundId: "spot_BTC-1h-9", openPrice: "93000.12", closePrice: "93100.50", resultsHash: "0xhash" };
const canonicalDoc = (over = {}) =>
  JSON.stringify({
    closePriceCents: 9310050,
    crowd: null,
    market: "spot_BTC",
    openPriceCents: 9300012,
    positions: [{ direction: "UP", leverage: 5, outcome: "SETTLED", payoutMojos: 1000, playerId: "a", stakeMojos: 500 }, { direction: "DOWN", leverage: 3, outcome: "LIQUIDATED", payoutMojos: 0, playerId: "b", stakeMojos: 500 }],
    roundId: "spot_BTC-1h-9",
    series: "1h",
    status: "SETTLED",
    venue: "coinbase",
    ...over,
  });
const failing = (checks) => checks.filter((c) => !c.ok).map((c) => c.name);

test("a canonical results.json of the round and the market passes every check", () => {
  const checks = checkResultsJson({ text: canonicalDoc(), market: market(), round: SETTLED, roundId: SETTLED.roundId });
  assert.deepEqual(failing(checks), []);
  assert.ok(checks.length >= 6);
});

test("a results.json that is not what the stand promised is caught", () => {
  const run = (text, over = {}) => failing(checkResultsJson({ text, market: market(), round: SETTLED, roundId: SETTLED.roundId, ...over }));
  assert.deepEqual(run("not json"), ["results.json is JSON"]);
  assert.deepEqual(run(canonicalDoc({ positions: [{ playerId: "b" }, { playerId: "a" }] })), ["results.json positions sorted by playerId"]);
  assert.deepEqual(run(JSON.stringify(JSON.parse(canonicalDoc()), null, 1)), ["results.json has no whitespace (canonical encoding)"]);
  assert.deepEqual(run(canonicalDoc({ roundId: "spot_BTC-1h-8" })), ["results.json is about this round"]);
  assert.deepEqual(run(canonicalDoc({ market: "spot_ETH" })), ["results.json names the market"]);
  assert.deepEqual(run(canonicalDoc({ market: undefined })), ["results.json names the market"], "a market other than BTC has the key");
  assert.deepEqual(run(canonicalDoc({ status: "CANCELLED" })), ["results.json status SETTLED"]);
  assert.deepEqual(run(canonicalDoc({ closePriceCents: 9310051 })), ["results.json prices are the round's own (open and close, as integers of the market's precision)"]);
});

test("the market BTC has no market key in its results.json, any other market has", () => {
  const btc = market({ id: "BTC" });
  const round = { ...SETTLED, roundId: "1h-9" };
  const text = canonicalDoc({ roundId: "1h-9", market: undefined });
  assert.deepEqual(failing(checkResultsJson({ text, market: btc, round, roundId: "1h-9" })), []);
  assert.deepEqual(failing(checkResultsJson({ text: canonicalDoc({ roundId: "1h-9" }), market: btc, round, roundId: "1h-9" })), ["results.json has no market key (the BTC shape)"]);
});

test("the results.json of a cancelled round says CANCELLED and carries no prices to compare", () => {
  const round = { phase: "CANCELLED", roundId: "spot_BTC-1h-9" };
  const text = canonicalDoc({ status: "CANCELLED", openPriceCents: null, closePriceCents: null });
  assert.deepEqual(failing(checkResultsJson({ text, market: market(), round, roundId: round.roundId })), []);
  assert.deepEqual(failing(checkResultsJson({ text: canonicalDoc(), market: market(), round, roundId: round.roundId })), ["results.json status CANCELLED"]);
});

// --- the oracle record ---

const REGISTRY = [{ index: 60, id: "spot_ETH" }, { index: 61, id: "spot_BTC" }];
const ROW = { seq: 4242, roundId: "spot_BTC-1h-9", marketId: "spot_BTC", asset: 61, ts: 1, priceCents: 9310050, resultsHash: "0xhash", state: "queued", venue: "coinbase" };
const ANCHOR = { seq: 4242, state: "queued", height: null };

test("the journal row of the round, the registry and the market agree: every check passes", () => {
  const checks = checkOracleRecord({ row: ROW, anchor: ANCHOR, registry: REGISTRY, market: market(), round: SETTLED, resultsVenue: "coinbase" });
  assert.deepEqual(failing(checks), []);
  assert.ok(checks.length >= 7);
  // the price compares as an integer of the digits: a market with more decimals, an odds market
  const oddsRound = { phase: "SETTLED", roundId: "PM1-1h-9", resultsHash: "0xhash", closePrice: "0.523000" };
  const oddsRow = { ...ROW, roundId: "PM1-1h-9", marketId: "PM1", asset: 70, priceCents: 523000, venue: null };
  assert.deepEqual(failing(checkOracleRecord({ row: oddsRow, anchor: ANCHOR, registry: [{ index: 70, id: "PM1" }], market: odds("PM1", "0.52", { oracleIndex: 70 }), round: oddsRound, resultsVenue: undefined })), []);
});

test("an oracle record that is not the round's is caught, whichever way it is wrong", () => {
  const run = (row, over = {}) => failing(checkOracleRecord({ row, anchor: ANCHOR, registry: REGISTRY, market: market(), round: SETTLED, resultsVenue: "coinbase", ...over }));
  assert.deepEqual(run(null), ["the oracle journal has a row for the round's seq"]);
  assert.deepEqual(run({ ...ROW, roundId: "spot_BTC-1h-8" }), ["oracle record is the round's"]);
  assert.deepEqual(run({ ...ROW, seq: 1 }), ["oracle record is the round's"]);
  assert.deepEqual(run({ ...ROW, marketId: "spot_ETH" }), ["oracle record is of the round's market"]);
  assert.deepEqual(run({ ...ROW, asset: 60 }), ["oracle record carries the market's registry index as its asset"], "another market's index");
  assert.deepEqual(run({ ...ROW, asset: 99 }), ["oracle record carries the market's registry index as its asset"], "an index that is in no registry");
  assert.deepEqual(run(ROW, { market: market({ oracleIndex: 60 }) }), ["oracle record carries the market's registry index as its asset"], "markets() says another index");
  assert.deepEqual(run({ ...ROW, resultsHash: "0xother" }), ["oracle record carries the round's resultsHash"]);
  assert.deepEqual(run({ ...ROW, priceCents: 9310051 }), ["oracle record carries the round's close price"]);
  assert.deepEqual(run({ ...ROW, venue: "kraken" }), ["oracle record names the same venue as results.json"]);
  assert.deepEqual(run({ ...ROW, venue: null }), ["oracle record names the same venue as results.json"]);
  assert.deepEqual(run({ ...ROW, state: "conflict" }), ["oracle record is not in conflict"]);
  assert.deepEqual(run(ROW, { registry: null }), ["oracle record carries the market's registry index as its asset"]);
});

test("a cancelled round has no close price for the record to carry", () => {
  const round = { phase: "CANCELLED", roundId: "spot_BTC-1h-9", resultsHash: "0xhash" };
  const checks = checkOracleRecord({ row: { ...ROW, priceCents: 0 }, anchor: ANCHOR, registry: REGISTRY, market: market(), round, resultsVenue: "coinbase" });
  assert.deepEqual(failing(checks), []);
  assert.ok(!checks.some((c) => c.name.includes("close price")));
});
