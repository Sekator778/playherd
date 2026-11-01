#!/usr/bin/env node
// End-to-end run of the stand over every price source (`make e2e-markets`): where tools/e2e/run.mjs plays the bitcoin
// market only, this one plays ONE market of each family the stand lists — spot crypto (Coinbase, Hyperliquid spot), an
// xyz market (Hyperliquid dex `xyz`: stocks, indices, commodities) and an odds market (Polymarket) — all in the same
// pass: the markets are played side by side, so a run takes about as long as one round (3 minutes of betting window and
// 3 of round on the stand, D-46), not three. Run with:
//
//   node --experimental-wasm-modules tools/e2e/run-markets.mjs [baseUrl] [--ci]
//
// (same flag and same only dependency as run.mjs: the test wallet of web/node_modules, see testWallet.mjs.)
//
// Per market, with four players of its own (their own keys and sign-ins, bets of mixed directions and leverage, cut to
// the market's ceiling — odds go up to x10), it checks what the BTC run checks: sign-in, the live crowd split and bet
// feed during betting (D-62), positions visible once the round runs, the round settled, every balance equal to the
// balance before minus the stake plus the payout, `resultsHash` equal to the sha256 of the exact bytes of
// `GET /api/rounds/{id}/results.json` (and that document canonical, about this round and market), and the oracle record —
// here the very record of the round: the row of the public journal `GET /api/oracle/records` for the `seq` the round names
// (`oracleAnchor`) must be the round's, of the market's, carry the market's registry index (`GET /api/markets/registry.json`)
// as its asset, the round's resultsHash and close price, and be handed to the chain service within two minutes. Once, at the
// end, the chain service itself (`serverInfo().oracle`).
//
// A family that cannot be played is skipped, not failed, only for a reason that family may be skipped for (tools/e2e/markets.mjs
// `FAMILIES`, `pickMarkets`). Spot crypto is the 24/7 family — the exchanges never close — so for it a stale price, no price,
// no series or round to bet on, a stand that lists no spot market, or a round cancelled for any reason but `market closed:`
// is a failure: only a closed market is a day off. The xyz family may be skipped for a closed market and for a missing or
// stale price while the market behind it is closed for the weekend (by the stand's own clock and the market's category); the
// odds family for a closed market (the source event ended, the chance left the 3%–97% band, the source stopped trading) and
// for an event that is about to end. A family with no market listed at all is skipped too (except spot): a stand may be set up
// without xyz or Polymarket. A round that is cancelled instead of settled for a reason its family may be skipped for (`market
// closed: …`) is not a failure as long as every stake came back: the refund is what is checked, the market counts as skipped.
// What is a failure: any check that fails, a family played or skipped for a reason it may not be, and a run in which not one
// round was played to its settlement — it proved nothing.
//
// `--ci` (or env HERD_E2E_CI=1): as for run.mjs, the on-chain check of the end (a chain service that has launched the
// oracle singleton) is left out; every other check stays.
import { pathToFileURL } from "node:url";
import { Player, createChecks, parsePlay, publicRpc, sha256HexOfExactBytes, sleep } from "./lib.mjs";
import {
  HANDED_OVER,
  betPlanFor,
  cancellationProblem,
  checkOracleRecord,
  checkResultsJson,
  expectedBalance,
  feesOfBet,
  pickMarkets,
} from "./markets.mjs";

/** The waits of a run, in ms; a test hands in much shorter ones. */
export const DEFAULT_TIMING = {
  pollMs: 4_000,
  /** beyond the wait for the betting window to close: the round starts. */
  startMarginMs: 90_000,
  /** beyond the length of the round: it settles. */
  settleMarginMs: 90_000,
  /** a betting window with less left than this is not used — the bets would race its end — the next one is. `null`: a third
   *  of the round, at most 20 s (a stand with rounds of 20 s has windows of 20 s). */
  minBettingMs: null,
  /** `resultsHash` is attached to a settled round a tick or two after settlement. */
  resultsWaitMs: 60_000,
  /** the record is handed to the chain service by the next tick of the publisher, and the journal learns it from the
   *  reconciliation that runs every minute. */
  oracleWaitMs: 120_000,
};

async function getJson(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  return res.json();
}

/**
 * Plays one market of every family it can, side by side, and summarizes.
 *
 * @param {object} options
 * @param {string} options.baseUrl           the stand, e.g. http://localhost:8480
 * @param {boolean} [options.ci]             leave out the check of the chain service's own state
 * @param {(label: string) => {label, address, publicKey, sign}} options.createWallet  testWallet.mjs `createTestWallet`
 * @param {Partial<typeof DEFAULT_TIMING>} [options.timing]
 * @param {(line: string) => void} [options.print]
 * @param {(code: number) => void} [options.setExitCode]
 * @returns {Promise<{ok: boolean, passed: number, failed: number, results: object[], outcomes: object[], skipped: object[]}>}
 *   `outcomes`: what became of every market that was played (`settled`, `skipped` for a reason its family may be skipped for,
 *   `failed`); `skipped`: the families that were not played, and the rounds that were let go, `allowed` or not.
 */
export async function runMarkets({ baseUrl, ci = false, createWallet, timing: overrides = {}, print = console.log, setExitCode } = {}) {
  const timing = { ...DEFAULT_TIMING, ...overrides };
  const checks = createChecks({ print, ...(setExitCode ? { setExitCode } : {}) });
  const log = (...args) => print(`[e2e ${new Date().toISOString()}]`, ...args);
  const outcomes = [];
  const skipped = [];
  const finish = () => ({ ...checks.summarize(), results: checks.results, outcomes, skipped });

  log(`base URL: ${baseUrl}`);
  let lobby;
  let lobbyAskedAtMs;
  let lobbyAnsweredAtMs;
  let registry;
  try {
    lobbyAskedAtMs = Date.now();
    lobby = await publicRpc(baseUrl, "markets");
    lobbyAnsweredAtMs = Date.now();
    checks.check("read the lobby (markets)", true, `${lobby.markets.length} markets`);
  } catch (e) {
    checks.fail("read the lobby (markets)", e);
    return finish();
  }
  try {
    registry = await getJson(`${baseUrl}/api/markets/registry.json`);
    checks.check("read the market registry", Array.isArray(registry), `${registry.length} entries`);
  } catch (e) {
    checks.fail("read the market registry", e);
    return finish();
  }

  // The stand's own clock decides how much of a betting window is left, wherever this runs (the windows it names are on its clock,
  // not this machine's): its serverTimeMs against the middle of the time the lobby took to answer, once -- not against the time it
  // is now, which is after the registry has been read too.
  const clockOffsetMs = lobby.serverTimeMs - Math.round((lobbyAskedAtMs + lobbyAnsweredAtMs) / 2);
  const serverNow = () => Date.now() + clockOffsetMs;

  // A family that is not played is a skip only for a reason it may be skipped for (markets.mjs `FAMILIES`): a spot market that has
  // no live price, or whose stand lists none, is a failure — the 24/7 family has no days off.
  const { plays, skips } = pickMarkets(lobby);
  for (const skip of skips) {
    skipped.push({ family: skip.family.id, reason: skip.reason, allowed: skip.allowed });
    if (skip.allowed) {
      print(`SKIP  ${skip.family.title}: ${skip.reason}`);
    } else {
      checks.scoped(skip.family.id).check(`${skip.family.title} was played, or skipped for a reason its family allows`, false, `${skip.reason} (${skip.family.skipRule})`);
    }
  }
  if (plays.length === 0) {
    checks.check("at least one market can be played", false, skips.map((s) => `${s.family.id}: ${s.reason}`).join(" | "));
    return finish();
  }
  for (const { family, market, series, passedOver } of plays) {
    log(`${family.title}: ${market.id} (${market.symbol}, ${market.source}, leverage up to x${market.maxLeverage}, rounds of ${series.durationSec} s)`);
    if (passedOver.length > 0) log(`${family.title}: passed over before it — ${passedOver.join("; ")}`);
  }

  const results = await Promise.all(
    plays.map(({ family, market, series }) =>
      playMarket({ baseUrl, family, market, series, createWallet, registry, checks: checks.scoped(market.id), timing, serverNow, log, print }).catch(
        (e) => {
          checks.scoped(market.id).fail("run crashed", e);
          return { market: market.id, family: family.id, outcome: "failed", reason: "crashed" };
        },
      ),
    ),
  );
  outcomes.push(...results);
  for (const r of results) {
    if (r.outcome !== "settled" && r.outcome !== "failed") skipped.push({ family: r.family, market: r.market, reason: r.reason, allowed: true });
  }
  print("\n--- markets ---");
  for (const skip of skips) print(`  ${skip.family.id}: not played — ${skip.reason}`);
  for (const r of results) print(`  ${r.market} (${r.family}): ${r.outcome}${r.reason ? ` — ${r.reason}` : ""}`);

  if (!ci) await checkChainService({ baseUrl, checks, log });
  checks.check(
    "at least one round was played to its settlement",
    results.some((r) => r.outcome === "settled"),
    results.map((r) => `${r.market}: ${r.outcome}${r.reason ? ` (${r.reason})` : ""}`).join(", "),
  );
  return finish();
}

/** The chain service's own state, once: the oracle block of serverInfo (the BTC run's check 9). */
async function checkChainService({ baseUrl, checks, log }) {
  try {
    const info = await publicRpc(baseUrl, "serverInfo");
    const oracle = info.oracle;
    log(`serverInfo().oracle = ${JSON.stringify(oracle)}`);
    checks.check(
      "chainsvc reachable via serverInfo (oracle block populated)",
      Boolean(oracle && oracle.launcherId && oracle.operatorBalanceMojos !== null),
      JSON.stringify(oracle),
    );
    if (oracle?.lastConfirmedSeq != null) {
      log(`ORACLE CONFIRMED ON TESTNET11: seq=${oracle.lastConfirmedSeq} height=${oracle.lastConfirmedHeight} launcherId=${oracle.launcherId}`);
      checks.check("an oracle record is confirmed on testnet11 (height present)", oracle.lastConfirmedHeight != null);
    }
  } catch (e) {
    checks.fail("serverInfo/oracle status check", e);
  }
}

/**
 * One market from the first sign-in to the oracle record. Resolves what became of it: `settled`; `skipped` (it closed before
 * there was a round to bet on, or the round was cancelled and every stake came back — for a reason its family may be skipped
 * for, markets.mjs `FAMILIES`); `failed` (a check said so, among them a skip for a reason its family may not be skipped for:
 * a round of the 24/7 spot family cancelled for want of a price is no day off).
 */
async function playMarket({ baseUrl, family, market, series, createWallet, registry, checks: c, timing, serverNow, log, print }) {
  const durationMs = series.durationSec * 1000;
  const minBettingMs = timing.minBettingMs ?? Math.min(20_000, durationMs / 3);
  const plan = betPlanFor(market);
  const outcome = (kind, reason) => ({ market: market.id, family: family.id, outcome: kind, reason });
  // `problem` is why (markets.mjs: `{ kind, reason }`); what the family may be skipped for decides whether this is a skip at all.
  const skip = (reason, problem) => {
    if (family.mayBeSkippedFor(problem, market, { serverTimeMs: serverNow() })) {
      print(`SKIP  [${market.id}] ${reason}`);
      return outcome("skipped", reason);
    }
    c.check("was played, or skipped for a reason its family allows", false, `${reason} (${family.skipRule})`);
    return outcome("failed", reason);
  };
  // Four players of this family's own: nicknames of at most 24 characters (`E2E herd e2e spot p1`).
  const players = plan.map((_, i) => new Player(`herd e2e ${family.id} p${i + 1}`, { baseUrl, createWallet }));
  const nickOf = (player) => player.nickname;

  // --- 1. sign in, set nicknames ---
  for (const player of players) {
    try {
      const session = await player.signIn();
      await player.rpc("setNickname", player.nickname);
      c.check(`sign-in + nickname: ${player.wallet.address.slice(0, 14)}…`, true, `isNewPlayer=${session.isNewPlayer}`);
    } catch (e) {
      c.fail(`sign-in: ${player.wallet.label}`, e);
    }
  }
  if (players.some((p) => !p.playerId)) return outcome("failed", "sign-in failed");

  // --- 2. a round open for bets with time left in its window (a window about to close is let go: the next one is used) ---
  let pair;
  const windowDeadline = Date.now() + durationMs + timing.startMarginMs;
  for (;;) {
    try {
      pair = await players[0].rpc("rounds", "HOURLY", market.id);
    } catch (e) {
      if (e.code === "MARKET_CLOSED") return skip("the market closed before a round could be bet on", { kind: "closed" });
      c.fail("fetch HOURLY rounds pair", e);
      return outcome("failed", "no rounds pair");
    }
    // betting for a round closes where the round starts (D-19: the bets go on the NEXT round): its window's start
    const left = pair.betting.window.startMs - serverNow();
    if (pair.betting.phase === "BETTING" && left >= minBettingMs) break;
    if (Date.now() > windowDeadline) {
      c.fail("a betting window with time left opens", new Error(`phase ${pair.betting.phase}, ${left} ms left`));
      return outcome("failed", "no betting window");
    }
    await sleep(left > 0 && left < timing.pollMs ? left + 100 : timing.pollMs);
  }
  const roundId = pair.betting.roundId;
  const bettingEndMs = pair.betting.window.startMs; // the running round's endMs: the round that is bet on starts there
  c.check("fetched HOURLY rounds pair", true, `betting=${roundId} (${pair.betting.phase}), ${Math.round((bettingEndMs - serverNow()) / 1000)} s left`);

  // --- 3. balances before betting ---
  const balanceBefore = {};
  for (const player of players) {
    try {
      balanceBefore[player.playerId] = parsePlay((await player.rpc("myAccount")).balance);
    } catch (e) {
      c.fail(`myAccount before bet: ${nickOf(player)}`, e);
    }
  }

  // --- 4. bets: mixed directions and leverage, cut to the market's ceiling ---
  const placed = []; // the indexes of the players whose bet was taken
  const feesPaid = {}; // index -> mojos of herd + network fee the bet was charged (economy plan A4)
  for (let i = 0; i < players.length; i++) {
    const player = players[i];
    const bet = plan[i];
    try {
      const taken = await player.rpc("placeBet", { roundId, direction: bet.direction, leverage: bet.leverage, stake: bet.stake });
      c.check(
        `placeBet ${nickOf(player)}: ${bet.direction} x${bet.leverage} ${bet.stake}`,
        taken.direction === bet.direction && taken.leverage === bet.leverage,
        `roundId=${taken.roundId}`,
      );
      placed.push(i);
      feesPaid[i] = feesOfBet(taken);
    } catch (e) {
      if (e.code === "MARKET_CLOSED") return skip("the market closed while the bets were placed", { kind: "closed" });
      c.fail(`placeBet: ${nickOf(player)}`, e);
    }
  }
  if (placed.length === 0) return outcome("failed", "no bet was taken");

  // --- 5. the live crowd split and bet feed during betting (D-62), while the window is open ---
  try {
    const betting = await players[0].rpc("round", roundId);
    if (betting.phase === "BETTING") {
      const hasLiveAggregate = "upStake" in betting && "downStake" in betting && "upCount" in betting && "downCount" in betting;
      c.check("live split pre-start: BETTING DTO carries upStake/downStake/upCount/downCount (D-62)", hasLiveAggregate, JSON.stringify(Object.keys(betting)));
      c.check(
        `live split pre-start: upCount + downCount >= ${placed.length} (this run's bets counted)`,
        betting.upCount + betting.downCount >= placed.length,
        `upCount=${betting.upCount} downCount=${betting.downCount}`,
      );
      c.check(`live split pre-start: participantCount >= ${placed.length} (this run's bets counted)`, betting.participantCount >= placed.length, `participantCount=${betting.participantCount}`);
      const feedHasOurBets =
        Array.isArray(betting.recentBets) && players.some((p) => betting.recentBets.some((b) => b.player.playerId === p.playerId));
      c.check("live feed pre-start: recentBets includes at least one of this run's bets (D-62)", feedHasOurBets, `recentBets.length=${betting.recentBets?.length}`);
    } else {
      log(`[${market.id}] the betting window closed before the live split could be read (phase ${betting.phase}) — those checks are left out`);
    }
  } catch (e) {
    c.fail("live split/feed pre-start", e);
  }
  try {
    const otherView = await players[1 % players.length].rpc("publicProfile", players[0].playerId);
    c.check("publicProfile still never lists the still-open round (D-29, unaffected by D-62)", !otherView.history.some((h) => h.roundId === roundId));
  } catch (e) {
    c.fail("publicProfile cross-check", e);
  }

  // --- 6. wait for the round to start running ---
  log(`[${market.id}] waiting for round ${roundId} to start running (server-side boundary at endMs=${bettingEndMs})…`);
  let view = { phase: "BETTING" };
  const startDeadline = Date.now() + Math.max(0, bettingEndMs - serverNow()) + timing.startMarginMs;
  while (view.phase === "BETTING") {
    if (Date.now() > startDeadline) {
      c.fail("round transitions BETTING -> RUNNING in time", new Error("timed out waiting for RUNNING"));
      return outcome("failed", "never started");
    }
    await sleep(Math.max(50, Math.min(timing.pollMs, bettingEndMs - serverNow() + 100)));
    view = await players[0].rpc("round", roundId);
  }

  // --- 7. positions visible once it runs (an opening price that is not known yet shows none, by design) ---
  if (view.phase === "RUNNING") {
    c.check("round transitioned BETTING -> RUNNING", true);
    if (view.openPrice === null) {
      log(`[${market.id}] the opening price is not known yet — the positions are not shown yet; they are checked at settlement`);
    } else {
      c.check("positions revealed at round start", (view.positions?.length ?? 0) >= placed.length, `positions.length=${view.positions?.length ?? 0}`);
      c.check(
        `all ${placed.length} e2e players' positions visible post-start`,
        placed.every((i) => view.positions.some((pos) => pos.player.playerId === players[i].playerId)),
      );
    }
  }

  // --- 8. wait for it to settle (or to be cancelled) ---
  log(`[${market.id}] waiting for round ${roundId} to settle (up to ${durationMs / 1000}s + margin)…`);
  const settleDeadline = Date.now() + durationMs + timing.settleMarginMs;
  while (view.phase === "RUNNING") {
    if (Date.now() > settleDeadline) {
      c.fail("round settles in time", new Error("timed out waiting for SETTLED/CANCELLED"));
      return outcome("failed", "never settled");
    }
    await sleep(timing.pollMs);
    view = await players[0].rpc("round", roundId);
  }

  const balanceAfter = async (player) => parsePlay((await player.rpc("myAccount")).balance);

  // --- 8b. cancelled: no settlement to check, but every stake must be back ---
  if (view.phase === "CANCELLED") {
    const reason = view.cancellation?.reason ?? "";
    c.check("cancelled round states its reason", reason.length > 0, reason);
    for (const i of placed) {
      try {
        const after = await balanceAfter(players[i]);
        c.check(`stake refunded: ${nickOf(players[i])}`, after === balanceBefore[players[i].playerId], `before=${balanceBefore[players[i].playerId]} after=${after}`);
      } catch (e) {
        c.fail(`balance check: ${nickOf(players[i])}`, e);
      }
    }
    await checkResultsDocument({ baseUrl, c, roundId, market, timing, view });
    return skip(`the round was cancelled — ${reason || "no reason given"} (the refund is checked)`, cancellationProblem(reason));
  }

  c.check("round settled", view.phase === "SETTLED", `phase=${view.phase}`);
  if (view.phase !== "SETTLED") return outcome("failed", `ended as ${view.phase}`);

  // --- 9. balances vs engine rules ---
  const settled = await players[0].rpc("round", roundId);
  for (const i of placed) {
    const player = players[i];
    const pos = settled.positions.find((p) => p.player.playerId === player.playerId);
    if (!pos) {
      c.fail(`settled position present: ${nickOf(player)}`, new Error("no matching position in settled round"));
      continue;
    }
    try {
      const after = await balanceAfter(player);
      const stake = parsePlay(plan[i].stake);
      const payout = parsePlay(pos.payout ?? "0.000");
      const expected = expectedBalance({ before: balanceBefore[player.playerId], stake, payout, fees: feesPaid[i] ?? 0 });
      c.check(
        `balance matches stake/payout: ${nickOf(player)}`,
        after === expected,
        `before=${balanceBefore[player.playerId]} stake=${stake} fees=${feesPaid[i] ?? 0} payout=${payout} expected=${expected} actual=${after} liquidated=${pos.liquidated}`,
      );
    } catch (e) {
      c.fail(`balance check: ${nickOf(player)}`, e);
    }
  }

  // --- 10. results.json, and then the oracle record of the round ---
  const document = await checkResultsDocument({ baseUrl, c, roundId, market, timing, view: settled });
  await checkOracleRecordOfRound({ baseUrl, c, player: players[0], roundId, market, registry, timing, document, log });
  return outcome("settled");
}

/**
 * The results hash of a finished round: waits for `resultsHash` on the round, then checks it is the sha256 of the exact
 * bytes of results.json and that the document is canonical, about this round and market. Resolves the document's text
 * (`null` when there is none).
 */
async function checkResultsDocument({ baseUrl, c, roundId, market, timing, view }) {
  let round = view;
  const deadline = Date.now() + timing.resultsWaitMs;
  while (!round.resultsHash && Date.now() < deadline) {
    await sleep(timing.pollMs);
    round = await publicRpc(baseUrl, "round", roundId);
  }
  if (!round.resultsHash) {
    c.check(`resultsHash attached to the round within ${Math.round(timing.resultsWaitMs / 1000)}s`, false, "not attached — the oracle record may still be being made");
    return null;
  }
  try {
    const { hex, bytes } = await sha256HexOfExactBytes(`${baseUrl}/api/rounds/${roundId}/results.json`);
    c.check("resultsHash == sha256(results.json)", hex === round.resultsHash, `${hex} vs ${round.resultsHash}`);
    const text = bytes.toString("utf8");
    for (const k of checkResultsJson({ text, market, round, roundId })) c.check(k.name, k.ok, k.detail);
    return { text, round };
  } catch (e) {
    c.fail("results.json hash check", e);
    return null;
  }
}

/**
 * The record the round made on the oracle: the `seq` the round names, its row in the stand's public journal (waited for
 * until the chain service has it), and the registry — what `checkOracleRecord` says they must agree on.
 */
async function checkOracleRecordOfRound({ baseUrl, c, player, roundId, market, registry, timing, document, log }) {
  const round = await player.rpc("round", roundId); // the freshest anchor
  const anchor = round.oracleAnchor ?? null;
  c.check("the settled round names its oracle record (oracleAnchor)", Number.isInteger(anchor?.seq), anchor ? `seq=${anchor.seq} state=${anchor.state}` : "no oracleAnchor on the round");
  if (!Number.isInteger(anchor?.seq)) return;

  const rowOf = async () => {
    const journal = await getJson(`${baseUrl}/api/oracle/records?fromSeq=${anchor.seq}&limit=1`);
    return journal.records.find((r) => r.seq === anchor.seq) ?? null;
  };
  let row = null;
  try {
    const deadline = Date.now() + timing.oracleWaitMs;
    for (;;) {
      row = await rowOf();
      if ((row && HANDED_OVER.has(row.state)) || row?.state === "conflict" || Date.now() > deadline) break;
      await sleep(timing.pollMs);
    }
  } catch (e) {
    c.fail("read the oracle journal", e);
    return;
  }
  c.check(
    `oracle record handed to the chain service within ${Math.round(timing.oracleWaitMs / 1000)}s`,
    Boolean(row && HANDED_OVER.has(row.state)),
    `seq=${anchor.seq} state=${row?.state}`,
  );
  let resultsVenue;
  try {
    resultsVenue = document ? JSON.parse(document.text).venue : undefined;
  } catch {
    resultsVenue = undefined;
  }
  for (const k of checkOracleRecord({ row, anchor, registry, market, round, resultsVenue })) c.check(k.name, k.ok, k.detail);
  if (row?.state === "confirmed") {
    log(`[${market.id}] oracle record seq=${anchor.seq} confirmed on chain (height ${round.oracleAnchor?.height ?? "?"})`);
  } else {
    log(`[${market.id}] oracle record seq=${anchor.seq} is ${row?.state} — not confirmed on chain within this run's window; expected (real testnet11 block time)`);
  }
}

// --- the command line ---

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const ci = args.includes("--ci") || process.env.HERD_E2E_CI === "1";
  const baseUrl = args.find((a) => !a.startsWith("--")) || process.env.HERD_E2E_BASE_URL || "http://localhost:8480";
  // Only the command line needs the wallet (and with it the wasm of web/node_modules): tests import this file without it.
  const { createTestWallet } = await import("./testWallet.mjs");
  runMarkets({ baseUrl, ci, createWallet: createTestWallet }).catch((e) => {
    console.error("e2e crashed:", e);
    process.exitCode = 1;
  });
}
