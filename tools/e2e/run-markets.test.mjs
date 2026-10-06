// Offline tests of tools/e2e/run-markets.mjs (`make test-tools`): the whole multi-market run against a fake stand
// (fake-stand.mjs) whose rounds last a fraction of a second — what it plays, what it skips and what it fails on. Nothing here
// touches a real stand, a wallet or a network beyond localhost; the run itself is the one `make e2e-markets` starts, with
// shorter waits and a wallet that signs nothing.
import assert from "node:assert/strict";
import { describe, it, test } from "node:test";
import { fakeWallet, startFakeStand } from "./fake-stand.mjs";
import { runMarkets } from "./run-markets.mjs";

const SPOT = { id: "spot_BTC", source: "coinbase", backupSource: "kraken" };
const XYZ = { id: "xyz_NVDA", category: "stocks", source: "hyperliquid", maxLeverage: 20 };
const ODDS = { id: "PM1", kind: "ODDS", category: "politics", source: "polymarket", maxLeverage: 10 };

const TIMING = { pollMs: 30, startMarginMs: 6_000, settleMarginMs: 6_000, minBettingMs: 120, resultsWaitMs: 3_000, oracleWaitMs: 3_000 };

/** The run against a fake stand, and what it left: the printed lines, the exit codes it set, the stand's state. `clockOffsetMs`: the stand's clock against this machine's. */
async function run(markets, { faults = {}, roundMs = 600, ci = false, timing = {}, clockOffsetMs = 0 } = {}) {
  const stand = await startFakeStand({ roundMs, markets, faults, clockOffsetMs });
  const lines = [];
  const codes = [];
  try {
    const result = await runMarkets({
      baseUrl: stand.url,
      ci,
      createWallet: fakeWallet,
      timing: { ...TIMING, ...timing },
      print: (...parts) => lines.push(parts.join(" ")),
      setExitCode: (code) => codes.push(code),
    });
    return { result, lines, codes, stand };
  } finally {
    await stand.close();
  }
}

const passed = (result, name) => result.results.some((r) => r.ok && r.name === name);
const failed = (result) => result.results.filter((r) => !r.ok).map((r) => r.name);

test("one market of each source is played in the same pass and every check passes", async () => {
  const { result, lines, codes, stand } = await run([SPOT, XYZ, ODDS]);

  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(codes, [], "no exit status for a green run");
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"], ["xyz_NVDA", "settled"], ["PM1", "settled"]]);
  assert.deepEqual(result.skipped, []);
  assert.ok(lines.includes("\nE2E PASSED"));
  assert.ok(["  spot_BTC (spot): settled", "  xyz_NVDA (xyz): settled", "  PM1 (odds): settled"].every((row) => lines.includes(row)), lines.join("\n"));

  // what BTC's run checks, for every one of the three: balances, the results hash, the oracle record
  for (const id of ["spot_BTC", "xyz_NVDA", "PM1"]) {
    const mine = result.results.filter((r) => r.name.startsWith(`[${id}] `));
    assert.equal(mine.filter((r) => r.name.includes("balance matches stake/payout")).length, 4, `${id}: four balances`);
    for (const name of [
      "resultsHash == sha256(results.json)",
      "results.json names the market",
      "oracle record is the round's",
      "oracle record carries the market's registry index as its asset",
      "oracle record carries the round's resultsHash",
      "oracle record carries the round's close price",
    ]) {
      assert.ok(passed(result, `[${id}] ${name}`), `${id}: ${name}`);
    }
    assert.ok(mine.every((r) => r.ok), `${id}: every check passed`);
  }
  assert.ok(passed(result, "chainsvc reachable via serverInfo (oracle block populated)"));

  // four players of their own per market, and the bets their plans give — odds cut to x10, the xyz market to its x20
  assert.equal(stand.state.players.size, 12);
  const betsOn = (id) => stand.calls.filter((c) => c.method === "placeBet" && c.args[0].roundId.startsWith(`${id}-`)).map((c) => c.args[0].leverage);
  assert.deepEqual(betsOn("spot_BTC"), [5, 10, 40, 3]);
  assert.deepEqual(betsOn("xyz_NVDA"), [5, 10, 20, 3]);
  assert.deepEqual(betsOn("PM1"), [5, 10, 10, 3]);

  // one round per market with the four positions, and its oracle record on the market's own index
  // (the stand settles the rounds nobody bet on as well, with a record each, like the real one)
  const played = [...stand.state.finished.values()].filter((r) => r.positions.length > 0);
  assert.deepEqual(played.map((r) => r.market.id).sort(), ["PM1", "spot_BTC", "xyz_NVDA"]);
  assert.ok(played.every((r) => r.positions.length === 4));
  const recordOf = (round) => stand.state.journal.find((r) => r.roundId === round.roundId);
  assert.deepEqual(played.map((r) => [recordOf(r).marketId, recordOf(r).asset]).sort(), [["PM1", 3], ["spot_BTC", 1], ["xyz_NVDA", 2]]);

  // "in the same pass": the three played side by side — each one's bets were placed before any other one was over
  const span = (id) => {
    const placed = stand.calls.filter((c) => c.method === "placeBet" && c.args[0].roundId.startsWith(`${id}-`)).map((c) => c.atMs);
    return { first: Math.min(...placed), over: played.find((r) => r.market.id === id).madeAtMs };
  };
  const spans = ["spot_BTC", "xyz_NVDA", "PM1"].map(span);
  for (const a of spans) for (const b of spans) assert.ok(a.first < b.over, "the markets were played side by side, not one after the other");
});

test("a family that cannot be played for a reason it may be skipped for is skipped with that reason, and the others are played", async () => {
  const { result, lines, stand } = await run([
    SPOT,
    { ...XYZ, id: "xyz_NVDA", status: "CLOSED", closeReason: "The source market ended" },
    { ...XYZ, id: "xyz_TSLA", status: "CLOSED", closeReason: "The asset was delisted at the source" },
    { ...ODDS, id: "PM1", endsAtMs: Date.now() + 30_000 }, // its event ends within the minutes a run takes
    { ...ODDS, id: "PM2", status: "CLOSED", closeReason: "Price left the 3%–97% range" },
  ]);
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"]]);
  assert.deepEqual(result.skipped.map((s) => [s.family, s.allowed]), [["xyz", true], ["odds", true]]);
  assert.ok(lines.some((l) => l.startsWith("SKIP  xyz: none of the 2 listed can be played") && l.includes("xyz_NVDA: closed (The source market ended)") && l.includes("xyz_TSLA: closed (The asset was delisted at the source)")), lines.join("\n"));
  assert.ok(lines.some((l) => l.startsWith("SKIP  odds:") && l.includes("PM1: the event it is about ends within minutes") && l.includes("PM2: closed (Price left the 3%–97% range)")), lines.join("\n"));
  assert.ok(lines.some((l) => l.startsWith("  xyz: not played — none of the 2 listed")), "the table at the end names what was not played");
  assert.ok(lines.includes("  spot_BTC (spot): settled"));
  assert.equal(stand.state.players.size, 4, "only the market that is played has players");
  assert.ok(!stand.calls.some((c) => c.method === "placeBet" && !c.args[0].roundId.startsWith("spot_BTC-")));
});

test("a stand with no market that can be played fails: the run proved nothing", async () => {
  const { result, codes, lines } = await run([{ ...SPOT, price: null }, { ...XYZ, status: "CLOSED" }]);
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["[spot] spot crypto was played, or skipped for a reason its family allows", "at least one market can be played"]);
  assert.deepEqual(codes, [1]);
  assert.equal(result.outcomes.length, 0);
  assert.ok(lines.some((l) => l.startsWith("FAIL  [spot] spot crypto was played") && l.includes("spot_BTC: no price yet")), lines.join("\n"));
  assert.ok(lines.some((l) => l.startsWith("SKIP  xyz:")), "a closed xyz market is an ordinary skip");

  // nothing the family may be skipped for, and nothing playable: the day off alone proves nothing either
  const closed = await run([{ ...SPOT, status: "CLOSED" }, { ...XYZ, status: "CLOSED" }, { ...ODDS, status: "CLOSED" }]);
  assert.deepEqual(failed(closed.result), ["at least one market can be played"]);
  assert.deepEqual(closed.result.skipped.map((s) => s.allowed), [true, true, true]);

  const empty = await run([]);
  assert.deepEqual(failed(empty.result), ["[spot] spot crypto was played, or skipped for a reason its family allows", "at least one market can be played"]);
});

test("a stand that cannot be read fails the run without crashing it", async () => {
  const lines = [];
  const codes = [];
  const result = await runMarkets({ baseUrl: "http://127.0.0.1:1", createWallet: fakeWallet, timing: TIMING, print: (...p) => lines.push(p.join(" ")), setExitCode: (c) => codes.push(c) });
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["read the lobby (markets)"]);
  assert.deepEqual(codes, [1]);
});

test("a round that is cancelled instead of settled is skipped once every stake is back", async () => {
  const { result, lines, codes } = await run([SPOT, XYZ, ODDS], { faults: { cancelRounds: new Set(["PM1"]) } });
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(codes, []);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"], ["xyz_NVDA", "settled"], ["PM1", "skipped"]]);
  assert.match(result.outcomes[2].reason, /the round was cancelled — market closed: the price left the 3%–97% range \(the refund is checked\)/);
  assert.ok(lines.some((l) => l.startsWith("SKIP  [PM1] the round was cancelled")));
  assert.equal(result.results.filter((r) => r.ok && r.name.startsWith("[PM1] stake refunded")).length, 4, "the four stakes came back");
  assert.ok(passed(result, "[PM1] cancelled round states its reason"));
  assert.ok(passed(result, "[PM1] resultsHash == sha256(results.json)"), "a cancelled round is verifiable like a settled one");
  assert.ok(passed(result, "[PM1] results.json status CANCELLED"));
  assert.deepEqual(result.skipped.map((s) => [s.market, s.family]), [["PM1", "odds"]]);
});

test("a run in which every round is cancelled has played nothing and fails", async () => {
  const { result, codes } = await run([SPOT, ODDS], { faults: { cancelRounds: true } });
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["at least one round was played to its settlement"]);
  assert.deepEqual(codes, [1]);
  assert.deepEqual(result.skipped.map((s) => s.market ?? s.family), ["xyz", "spot_BTC", "PM1"], "no xyz market is listed; the other two were cancelled");
});

test("a market that closes between the lobby and the first round is skipped, not failed", async () => {
  const { result, lines } = await run([SPOT, ODDS], { faults: { closeOnRounds: new Set(["PM1"]) } });
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"], ["PM1", "skipped"]]);
  assert.equal(result.outcomes[1].reason, "the market closed before a round could be bet on");
  assert.ok(lines.some((l) => l.startsWith("SKIP  [PM1] the market closed before")));
});

test("a market that closes while the bets are placed is skipped, not failed", async () => {
  const { result } = await run([SPOT, XYZ], { faults: { closeOnBet: new Set(["xyz_NVDA"]) } });
  assert.deepEqual(failed(result), []);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"], ["xyz_NVDA", "skipped"]]);
  assert.equal(result.outcomes[1].reason, "the market closed while the bets were placed");
});

test("a round in which the price does not move gives every stake back, and the balances say so", async () => {
  const { result } = await run([SPOT, ODDS], { faults: { flatRounds: true } });
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.equal(result.results.filter((r) => r.ok && r.name.includes("balance matches stake/payout")).length, 8);
  assert.deepEqual(result.outcomes.map((o) => o.outcome), ["settled", "settled"]);
});

test("a round whose opening price is not known yet is still played to its settlement", async () => {
  const { result, lines } = await run([SPOT], { faults: { noOpenPrice: true } });
  assert.deepEqual(failed(result), []);
  assert.ok(lines.some((l) => l.includes("the opening price is not known yet")));
  assert.ok(!result.results.some((r) => r.name.includes("positions revealed at round start")));
});

// --- what a run must fail on ---

test("a results hash that is not the hash of the document fails that market and only that market", async () => {
  const { result, codes } = await run([SPOT, XYZ, ODDS], { faults: { corruptResultsHash: new Set(["xyz_NVDA"]) } });
  assert.equal(result.ok, false);
  assert.deepEqual(codes, [1]);
  assert.ok(failed(result).includes("[xyz_NVDA] resultsHash == sha256(results.json)"), failed(result).join("|"));
  assert.ok(failed(result).includes("[xyz_NVDA] oracle record carries the round's resultsHash"));
  assert.ok(failed(result).every((name) => name.startsWith("[xyz_NVDA] ")), `only xyz_NVDA: ${failed(result).join(" | ")}`);
  assert.deepEqual(result.outcomes.map((o) => o.outcome), ["settled", "settled", "settled"], "the round itself was settled; what is wrong is what the stand says about it");
});

test("an oracle record missing from the journal fails: the round names a seq the journal does not have", async () => {
  const { result, codes } = await run([SPOT, ODDS], { faults: { noJournalRow: new Set(["PM1"]) } });
  assert.equal(result.ok, false);
  assert.deepEqual(codes, [1]);
  const names = failed(result);
  assert.ok(names.includes("[PM1] the settled round names its oracle record (oracleAnchor)"), names.join("|"));
  assert.ok(names.every((n) => n.startsWith("[PM1] ")), names.join("|"));
  assert.ok(passed(result, "[spot_BTC] oracle record is the round's"));
});

test("an oracle record with another market's index as its asset fails", async () => {
  const { result } = await run([SPOT, XYZ], { faults: { wrongAsset: new Set(["xyz_NVDA"]) } });
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["[xyz_NVDA] oracle record carries the market's registry index as its asset"]);
});

test("a record that is never handed to the chain service fails within the time given", async () => {
  const { result } = await run([SPOT], { faults: { oracleQueueDelayMs: 60_000 }, timing: { oracleWaitMs: 400 } });
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["[spot_BTC] oracle record handed to the chain service within 0s"]);
});

test("a hash that never appears on the round fails within the time given", async () => {
  const { result } = await run([SPOT], { faults: { hashDelayMs: 60_000 }, timing: { resultsWaitMs: 300 } });
  assert.equal(result.ok, false);
  assert.ok(failed(result).includes("[spot_BTC] resultsHash attached to the round within 0s"), failed(result).join("|"));
});

test("a betting window that never has time left fails the market instead of betting in the last moment", async () => {
  // the window is as long as a round (600 ms); a run that wants 700 ms of it never finds one
  const { result, stand } = await run([SPOT], { timing: { minBettingMs: 700, startMarginMs: 400 } });
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["[spot_BTC] a betting window with time left opens", "at least one round was played to its settlement"]);
  assert.ok(!stand.calls.some((c) => c.method === "placeBet"), "no bet was risked on a window about to close");
});

test("by default a betting window must have a third of the round left, so rounds of any length have windows to use", async () => {
  // no `minBettingMs` given: 20 s would never fit a window of 600 ms; a third of the round does
  const { result, stand } = await run([SPOT], { timing: { minBettingMs: null } });
  assert.deepEqual(failed(result), []);
  const firstBet = stand.calls.find((c) => c.method === "placeBet");
  const roundStartMs = Number(firstBet.args[0].roundId.split("-").pop());
  assert.ok(roundStartMs - firstBet.atMs >= 150, `bets are placed with at least a third of the window left (${roundStartMs - firstBet.atMs} ms)`);
});

test("--ci leaves out the chain service's own state and nothing else", async () => {
  const normal = await run([SPOT]);
  const ci = await run([SPOT], { ci: true });
  assert.ok(normal.stand.calls.some((c) => c.method === "serverInfo"));
  assert.ok(!ci.stand.calls.some((c) => c.method === "serverInfo"));
  assert.deepEqual(failed(ci.result), []);
  const names = (r) => r.result.results.map((x) => x.name);
  assert.deepEqual(names(normal).filter((n) => !names(ci).includes(n)), ["chainsvc reachable via serverInfo (oracle block populated)"]);
});

// --- the spot family has no days off ---
//
// Spot crypto is the 24/7 family: the exchanges never close. A run in which it was not played, or its round was cancelled, for
// any reason but a closed market, must not be green because the other two families were — the failure of the stand it is.

const SPOT_FAMILY_CHECK = "[spot] spot crypto was played, or skipped for a reason its family allows";
const SPOT_MARKET_CHECK = "[spot_BTC] was played, or skipped for a reason its family allows";

describe("a spot market that cannot be played fails the run whatever the other families do", { concurrency: 5 }, () => {
  const broken = [
    ["its price is stale", { price: { stale: true } }, "spot_BTC: its price is stale"],
    ["it has no price", { price: null }, "spot_BTC: no price yet"],
    ["it has no series", { noSeries: true }, "spot_BTC: no running series"],
    ["it has no round open for bets", { noBettingRound: true }, "spot_BTC: no round open for bets"],
    ["it allows no leverage", { maxLeverage: 1 }, "spot_BTC: it allows no leverage"],
  ];
  for (const [name, spec, words] of broken) {
    it(name, async () => {
      const { result, codes, stand, lines } = await run([{ ...SPOT, ...spec }, XYZ, ODDS]);
      assert.equal(result.ok, false);
      assert.deepEqual(codes, [1]);
      assert.deepEqual(failed(result), [SPOT_FAMILY_CHECK]);
      const check = result.results.find((r) => !r.ok);
      assert.ok(check.detail.includes(words) && check.detail.includes("the exchanges never close"), check.detail);
      assert.deepEqual(result.skipped.map((s) => [s.family, s.allowed]), [["spot", false]]);
      // the other two are still played, side by side, and settle: their results are not lost to the spot's failure
      assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["xyz_NVDA", "settled"], ["PM1", "settled"]]);
      assert.equal(stand.state.players.size, 8, "no spot player was made");
      assert.ok(lines.includes("\nE2E FAILED"));
    });
  }
});

test("a stand that lists no spot market fails the run, and the other families are played", async () => {
  const { result, codes } = await run([XYZ, ODDS]);
  assert.equal(result.ok, false);
  assert.deepEqual(codes, [1]);
  assert.deepEqual(failed(result), [SPOT_FAMILY_CHECK]);
  assert.match(result.results.find((r) => !r.ok).detail, /no spot crypto market is listed/);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["xyz_NVDA", "settled"], ["PM1", "settled"]]);
});

test("a spot market that is closed is a day off: skipped, and the run is green when the others settle", async () => {
  const { result, codes, lines } = await run([{ ...SPOT, status: "CLOSED", closeReason: "Moved to a new price source: Coinbase" }, XYZ, ODDS]);
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(codes, []);
  assert.deepEqual(result.skipped.map((s) => [s.family, s.allowed]), [["spot", true]]);
  assert.ok(lines.some((l) => l.startsWith("SKIP  spot crypto:") && l.includes("spot_BTC: closed (Moved to a new price source: Coinbase)")), lines.join("\n"));
  assert.deepEqual(result.outcomes.map((o) => o.outcome), ["settled", "settled"]);
});

test("a closed spot market does not excuse another one that has no price: the family fails, naming the one", async () => {
  const { result } = await run([
    { ...SPOT, id: "spot_BTC", status: "CLOSED", closeReason: "Moved to a new price source: Coinbase" },
    { ...SPOT, id: "spot_ETH", price: null },
    XYZ,
  ]);
  assert.deepEqual(failed(result), [SPOT_FAMILY_CHECK]);
  const detail = result.results.find((r) => !r.ok).detail;
  assert.ok(detail.includes("spot_ETH: no price yet"), detail);
  assert.ok(result.skipped[0].reason.includes("spot_BTC: closed (Moved to a new price source: Coinbase)"), "the closed one is still in the words of the skip");
});

test("a spot market passed over for a stale price is said, and the next spot market is played", async () => {
  const { result, lines } = await run([{ ...SPOT, id: "spot_BTC", price: { stale: true } }, { ...SPOT, id: "spot_ETH" }]);
  assert.deepEqual(failed(result), []);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_ETH", "settled"]]);
  assert.ok(lines.some((l) => l.includes("spot crypto: passed over before it — spot_BTC: its price is stale")), lines.join("\n"));
});

describe("a spot round that is cancelled for any reason but a closed market fails the run, after its refunds are checked", { concurrency: 5 }, () => {
  const reasons = [
    "No price from the source; stakes returned",
    "The exchange of this round stopped sending prices; stakes returned",
    "no oracle price available within 1 hour of round close",
    "settlement failed within 1 hour of round close",
    "cancelled by operator",
  ];
  for (const reason of reasons) {
    it(reason, async () => {
      const { result, codes } = await run([SPOT, XYZ, ODDS], { faults: { cancelRounds: new Set(["spot_BTC"]), cancelReason: reason } });
      assert.equal(result.ok, false);
      assert.deepEqual(codes, [1]);
      assert.deepEqual(failed(result), [SPOT_MARKET_CHECK]);
      assert.ok(result.results.find((r) => !r.ok).detail.includes(reason));
      assert.equal(result.results.filter((r) => r.ok && r.name.startsWith("[spot_BTC] stake refunded")).length, 4, "the four stakes came back, and that is checked all the same");
      assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "failed"], ["xyz_NVDA", "settled"], ["PM1", "settled"]]);
      assert.deepEqual(result.skipped, [], "it is no skip");
    });
  }
});

test("a spot round cancelled because its market closed is a day off: the refunds are checked, the run is green", async () => {
  const reason = "market closed: Moved to a new price source: Coinbase";
  const { result, lines } = await run([SPOT, XYZ, ODDS], { faults: { cancelRounds: new Set(["spot_BTC"]), cancelReason: reason } });
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "skipped"], ["xyz_NVDA", "settled"], ["PM1", "settled"]]);
  assert.equal(result.results.filter((r) => r.ok && r.name.startsWith("[spot_BTC] stake refunded")).length, 4);
  assert.ok(lines.some((l) => l.startsWith("SKIP  [spot_BTC] the round was cancelled — " + reason)), lines.join("\n"));
  assert.deepEqual(result.skipped.map((s) => [s.family, s.market, s.allowed]), [["spot", "spot_BTC", true]]);
});

test("a spot market that closes before the first round or while the bets are placed is a day off too", async () => {
  const [before, during] = await Promise.all([
    run([SPOT, XYZ], { faults: { closeOnRounds: new Set(["spot_BTC"]) } }),
    run([SPOT, XYZ], { faults: { closeOnBet: new Set(["spot_BTC"]) } }),
  ]);
  for (const { result } of [before, during]) {
    assert.deepEqual(failed(result), []);
    assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "skipped"], ["xyz_NVDA", "settled"]]);
  }
});

test("a run in which spot is a day off and nothing else settles is no green run", async () => {
  const { result } = await run([{ ...SPOT, status: "CLOSED" }, { ...XYZ, status: "CLOSED" }], {});
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["at least one market can be played"]);
});

// --- xyz and odds: a day off only for the reasons their families have ---

test("an xyz market without a live price is skipped on a weekend by the stand's clock, and fails on any other day", async () => {
  // The stand's clock is put on a day (whatever day it is on this machine): a Saturday, a Wednesday, and the two sides of the
  // close of the stocks on a Friday (20:00 in New York; October is UTC-4).
  const at = (iso) => Date.parse(iso) - Date.now();
  const noPrice = [SPOT, { ...XYZ, price: null }, ODDS];
  const stale = [SPOT, { ...XYZ, price: { stale: true } }];
  const [weekend, weekday, beforeClose, afterClose] = await Promise.all([
    run(noPrice, { clockOffsetMs: at("2026-10-10T15:00:00Z") }), // Saturday 11:00
    run(noPrice, { clockOffsetMs: at("2026-10-07T15:00:00Z") }), // Wednesday 11:00
    run(stale, { clockOffsetMs: at("2026-10-09T23:30:00Z") }), // Friday 19:30: the stocks still trade
    run(stale, { clockOffsetMs: at("2026-10-10T00:30:00Z") }), // Friday 20:30: they do not
  ]);

  assert.deepEqual(failed(weekend.result), []);
  assert.equal(weekend.result.ok, true);
  assert.deepEqual(weekend.result.skipped.map((s) => [s.family, s.allowed]), [["xyz", true]]);
  assert.ok(weekend.lines.some((l) => l.startsWith("SKIP  xyz:") && l.includes("xyz_NVDA: no price yet")), weekend.lines.join("\n"));
  assert.deepEqual(weekend.result.outcomes.map((o) => o.outcome), ["settled", "settled"]);

  assert.equal(weekday.result.ok, false);
  assert.deepEqual(failed(weekday.result), ["[xyz] xyz was played, or skipped for a reason its family allows"]);
  assert.match(weekday.result.results.find((r) => !r.ok).detail, /xyz_NVDA: no price yet/);
  assert.deepEqual(weekday.result.outcomes.map((o) => o.outcome), ["settled", "settled"], "the others are played all the same");

  assert.deepEqual(failed(beforeClose.result), ["[xyz] xyz was played, or skipped for a reason its family allows"]);
  assert.deepEqual(failed(afterClose.result), []);
});

test("a weekend does not excuse an xyz market that has nothing to bet on, nor a category that has no weekend", async () => {
  const saturday = Date.parse("2026-10-10T15:00:00Z") - Date.now();
  const [noSeries, crypto] = await Promise.all([
    run([SPOT, { ...XYZ, noSeries: true }], { clockOffsetMs: saturday }),
    run([SPOT, { ...XYZ, category: "crypto", price: null }], { clockOffsetMs: saturday }),
  ]);
  assert.deepEqual(failed(noSeries.result), ["[xyz] xyz was played, or skipped for a reason its family allows"]);
  assert.deepEqual(failed(crypto.result), ["[xyz] xyz was played, or skipped for a reason its family allows"]);
});

test("an xyz or odds round cancelled for want of a price fails the run; cancelled because its market closed it is a day off", async () => {
  // (a round the server lets go for want of a price is cancelled minutes after its end, Р-79; a stand that did it sooner is
  // still no day off: the family has no such reason)
  const noPrice = "No price from the source; stakes returned";
  const [xyz, odds, closed] = await Promise.all([
    run([SPOT, XYZ], { faults: { cancelRounds: new Set(["xyz_NVDA"]), cancelReason: noPrice } }),
    run([SPOT, ODDS], { faults: { cancelRounds: new Set(["PM1"]), cancelReason: noPrice } }),
    run([SPOT, XYZ, ODDS], {
      faults: {
        cancelRounds: true,
        cancelReason: { xyz_NVDA: "market closed: The source market stopped trading", PM1: "market closed: Price left the 3%–97% range", spot_BTC: "market closed: The asset was delisted at the source" },
      },
    }),
  ]);
  assert.deepEqual(failed(xyz.result), ["[xyz_NVDA] was played, or skipped for a reason its family allows"]);
  assert.deepEqual(xyz.result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"], ["xyz_NVDA", "failed"]]);
  assert.deepEqual(failed(odds.result), ["[PM1] was played, or skipped for a reason its family allows"]);
  assert.deepEqual(failed(closed.result), ["at least one round was played to its settlement"], "every round was a day off: nothing was played");
  assert.deepEqual(closed.result.outcomes.map((o) => o.outcome), ["skipped", "skipped", "skipped"]);
});

describe("an odds market that has no live price, or nothing to bet on, fails the run; a closed one or one about to end is a day off", { concurrency: 5 }, () => {
  const broken = [
    ["its price is stale", { price: { stale: true } }, "PM1: its price is stale"],
    ["it has no price", { price: null }, "PM1: no price yet"],
    ["it has no series", { noSeries: true }, "PM1: no running series"],
    ["it has no round open for bets", { noBettingRound: true }, "PM1: no round open for bets"],
  ];
  for (const [name, spec, words] of broken) {
    it(name, async () => {
      const { result } = await run([SPOT, { ...ODDS, ...spec }]);
      assert.equal(result.ok, false);
      assert.deepEqual(failed(result), ["[odds] odds was played, or skipped for a reason its family allows"]);
      assert.ok(result.results.find((r) => !r.ok).detail.includes(words));
      assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"]]);
    });
  }
  it("it is closed (the chance left the 3%–97% band) or its event is about to end", async () => {
    const [closed, ending] = await Promise.all([
      run([SPOT, { ...ODDS, status: "CLOSED", closeReason: "Price left the 3%–97% range" }]),
      run([SPOT, { ...ODDS, endsAtMs: Date.now() + 30_000 }]),
    ]);
    for (const { result } of [closed, ending]) {
      assert.deepEqual(failed(result), []);
      assert.deepEqual(result.skipped.filter((s) => s.family === "odds").map((s) => s.allowed), [true]);
    }
  });
});

// --- the stand's clock is not this machine's ---
//
// The windows the stand names are on its own clock. A run that takes them for this machine's time bets too late when the stand
// is ahead and never finds a window when it is behind: `serverNow()` of run-markets.mjs is the offset between the two, read from
// the lobby's `serverTimeMs`. The fake stand is put 60 s ahead of, and 60 s behind, this machine's clock (its rounds, windows,
// round ids and the times it stamps on bets are all on that clock), and every bet must be taken with the window the run asked for
// left on the stand's clock. A minute is a multiple of the round, so the stand's rounds start at the same moments of a second
// as this machine's.

/** Waits until this machine's clock is `phaseMs` into a period of `periodMs`. */
async function waitForPhase(periodMs, phaseMs) {
  const phase = Date.now() % periodMs;
  await new Promise((resolve) => setTimeout(resolve, (phaseMs - phase + periodMs) % periodMs));
}

/**
 * A run of the spot market against a stand whose clock is `clockOffsetMs` off this machine's, begun when this machine's clock
 * (and so the stand's: the offsets are a multiple of the round) is `phaseMs` into a round of 1200 ms, asking of a betting window
 * 600 ms. Resolves what the stand saw: the bets, and the window the first of them had left on the stand's clock.
 */
async function runOnAStandOffBy(clockOffsetMs, { phaseMs, faults = {} }) {
  const roundMs = 1200;
  const minBettingMs = 600;
  await waitForPhase(roundMs, phaseMs);
  const { result, stand } = await run([SPOT], { roundMs, clockOffsetMs, faults, timing: { minBettingMs, startMarginMs: 3_000, settleMarginMs: 3_000 } });
  const bets = stand.calls.filter((c) => c.method === "placeBet");
  const startOf = (bet) => Number(bet.args[0].roundId.split("-").pop());
  return { result, stand, bets, startOf, minBettingMs, roundMs, firstLeftMs: bets.length > 0 ? startOf(bets[0]) - bets[0].atMs : null };
}

for (const [skewMs, where] of [[60_000, "ahead of"], [-60_000, "behind"]]) {
  test(`a stand 60 s ${where} this machine's clock is bet on with the window the run asked for left, on the stand's clock`, async () => {
    // Begin when the window of the stand that is open has 400 ms left: not enough, the run has to wait for the next window.
    // (Taken for this machine's time, the stand 60 s ahead looks like a window with 60 s left, and the bets are placed at once,
    // with 300 ms of it to spare; the stand 60 s behind looks like no window at all.)
    const { result, bets, startOf, minBettingMs, roundMs, firstLeftMs } = await runOnAStandOffBy(skewMs, { phaseMs: 800 });

    assert.deepEqual(failed(result), []);
    assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"]]);
    assert.equal(bets.length, 4, "all four bets were taken");
    // the stand is on the clock it was given: the rounds it names are minute-skewed relative to this machine's time
    assert.ok(Math.abs(startOf(bets[0]) - (Date.now() + skewMs)) < 10 * roundMs, `the round ${bets[0].args[0].roundId} is on the stand's clock`);
    for (const bet of bets) {
      const left = startOf(bet) - bet.atMs; // the stand's own clock, both
      assert.ok(left > 0, `the bet was taken inside the window (${left} ms left)`);
    }
    // 50 ms of the time between the run's look at the clock and the bet reaching the stand
    assert.ok(firstLeftMs >= minBettingMs - 50, `the first bet had ${firstLeftMs} ms of the window left, the run asked for ${minBettingMs} ms`);
  });
}

test("the stand's clock is read when the lobby answers, not when the market registry behind it has", async () => {
  // The registry takes half a second. A run that sets its clock by the lobby's `serverTimeMs` against the time it has read the
  // registry too thinks the stand is 500 ms behind where it is: begun 250 ms into a round, it looks at the window some 600 ms
  // later, with 350 ms left, which looks like 850 ms to it -- more than the 600 ms it asked for -- and bets at once.
  const { result, firstLeftMs, minBettingMs } = await runOnAStandOffBy(60_000, { phaseMs: 250, faults: { registryDelayMs: 500 } });
  assert.deepEqual(failed(result), []);
  assert.ok(firstLeftMs >= minBettingMs - 50, `the first bet had ${firstLeftMs} ms of the window left, the run asked for ${minBettingMs} ms`);
});
