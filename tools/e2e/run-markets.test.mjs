// Offline tests of tools/e2e/run-markets.mjs (`make test-tools`): the whole multi-market run against a fake stand
// (fake-stand.mjs) whose rounds last a fraction of a second — what it plays, what it skips and what it fails on. Nothing here
// touches a real stand, a wallet or a network beyond localhost; the run itself is the one `make e2e-markets` starts, with
// shorter waits and a wallet that signs nothing.
import assert from "node:assert/strict";
import { test } from "node:test";
import { fakeWallet, startFakeStand } from "./fake-stand.mjs";
import { runMarkets } from "./run-markets.mjs";

const SPOT = { id: "spot_BTC", source: "coinbase", backupSource: "kraken" };
const XYZ = { id: "xyz_NVDA", category: "stocks", source: "hyperliquid", maxLeverage: 20 };
const ODDS = { id: "PM1", kind: "ODDS", category: "politics", source: "polymarket", maxLeverage: 10 };

const TIMING = { pollMs: 30, startMarginMs: 6_000, settleMarginMs: 6_000, minBettingMs: 120, resultsWaitMs: 3_000, oracleWaitMs: 3_000 };

/** The run against a fake stand, and what it left: the printed lines, the exit codes it set, the stand's state. */
async function run(markets, { faults = {}, roundMs = 600, ci = false, timing = {} } = {}) {
  const stand = await startFakeStand({ roundMs, markets, faults });
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

test("a family that cannot be played is skipped with its reason, and the others are played", async () => {
  const { result, lines, stand } = await run([
    SPOT,
    { ...XYZ, id: "xyz_NVDA", status: "CLOSED", closeReason: "The source market ended" },
    { ...XYZ, id: "xyz_TSLA", price: null },
    { ...ODDS, price: { stale: true } },
  ]);
  assert.deepEqual(failed(result), []);
  assert.equal(result.ok, true);
  assert.deepEqual(result.outcomes.map((o) => [o.market, o.outcome]), [["spot_BTC", "settled"]]);
  assert.deepEqual(result.skipped.map((s) => s.family), ["xyz", "odds"]);
  assert.ok(lines.some((l) => l.startsWith("SKIP  xyz: none of the 2 listed can be played") && l.includes("xyz_NVDA: closed (The source market ended)") && l.includes("xyz_TSLA: no price yet")), lines.join("\n"));
  assert.ok(lines.some((l) => l.startsWith("SKIP  odds:") && l.includes("PM1: its price is stale")));
  assert.ok(lines.some((l) => l.startsWith("  xyz: not played — none of the 2 listed")), "the table at the end names what was not played");
  assert.ok(lines.includes("  spot_BTC (spot): settled"));
  assert.equal(stand.state.players.size, 4, "only the market that is played has players");
  assert.ok(!stand.calls.some((c) => c.method === "placeBet" && !c.args[0].roundId.startsWith("spot_BTC-")));
});

test("a stand with no market that can be played fails: the run proved nothing", async () => {
  const { result, codes, lines } = await run([{ ...SPOT, price: null }, { ...XYZ, status: "CLOSED" }]);
  assert.equal(result.ok, false);
  assert.deepEqual(failed(result), ["at least one market can be played"]);
  assert.deepEqual(codes, [1]);
  assert.equal(result.outcomes.length, 0);
  assert.ok(lines.some((l) => l.startsWith("SKIP  spot crypto:")));
  const empty = await run([]);
  assert.deepEqual(failed(empty.result), ["at least one market can be played"]);
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

test("--ci leaves out the chain service's own state and nothing else", async () => {
  const normal = await run([SPOT]);
  const ci = await run([SPOT], { ci: true });
  assert.ok(normal.stand.calls.some((c) => c.method === "serverInfo"));
  assert.ok(!ci.stand.calls.some((c) => c.method === "serverInfo"));
  assert.deepEqual(failed(ci.result), []);
  const names = (r) => r.result.results.map((x) => x.name);
  assert.deepEqual(names(normal).filter((n) => !names(ci).includes(n)), ["chainsvc reachable via serverInfo (oracle block populated)"]);
});
