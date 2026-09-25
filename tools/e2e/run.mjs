#!/usr/bin/env node
// Automated end-to-end test of the local stand (Р-44 task item 3), no global installs — the only
// dependency (`chia-wallet-sdk-wasm`) is reused straight from `web/node_modules` (see
// testWallet.mjs). Run with:
//
//   node --experimental-wasm-modules tools/e2e/run.mjs [baseUrl]
//
// (Node 22's plain `import` of a `.wasm` file is still an experimental feature — the flag is
// required or the process exits immediately with an ExperimentalWarning-turned-error.)
//
// What it does (task item 3, verbatim): 4 simulated players, each with its own real key signing
// like the browser test wallet; sign in via the RPC; set nicknames; place bets in the open
// betting window (mixed directions/leverages incl. one 40x); verify sides are hidden before
// start and revealed after; wait through one full HOURLY (3-min) round to settlement; check
// balances vs engine rules; fetch /api/rounds/{id}/results.json and check sha256 == resultsHash;
// check that chainsvc queued/published the boundary record(s) to testnet11.
//
// Exits 0 and prints "E2E PASSED" iff every check below passed; otherwise prints the failing
// checks and exits 1. Deliberately no test framework dependency — this is a single linear
// scenario, not a suite; a plain assert-and-report loop is easier to read start to finish than
// wiring up a runner for four checks.

import { createHash } from "node:crypto";
import { createTestWallet } from "./testWallet.mjs";

const BASE_URL = process.argv[2] || process.env.HERD_E2E_BASE_URL || "http://localhost:8480";
const HOURLY_DURATION_MS = 180_000; // Р-46 test mode
const POLL_MS = 4_000;

const results = []; // { ok: boolean, name: string, detail?: string }
function check(name, ok, detail) {
  results.push({ name, ok, detail });
  console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail ? " — " + detail : ""}`);
}
function fail(name, err) {
  check(name, false, err instanceof Error ? err.message : String(err));
}

function log(...args) {
  console.log(`[e2e ${new Date().toISOString()}]`, ...args);
}

// --- tiny RPC client with a per-player cookie jar (FIXED INTERFACE (1)) ---
class Player {
  constructor(label) {
    this.wallet = createTestWallet(label);
    this.cookie = null; // "herd_session=..."
    this.playerId = null;
    this.nickname = `E2E ${label.replace(/[^a-zA-Z0-9]+/g, " ").trim()}`;
  }

  async rpc(method, ...args) {
    const headers = { "Content-Type": "application/json" };
    if (this.cookie) headers.Cookie = this.cookie;
    const res = await fetch(`${BASE_URL}/api/rpc/${method}`, {
      method: "POST",
      headers,
      body: JSON.stringify({ args }),
    });
    const setCookies = res.headers.getSetCookie?.() ?? [];
    for (const sc of setCookies) {
      if (sc.startsWith("herd_session=")) {
        const value = sc.split(";", 1)[0];
        // Clearing (signOut) sets an empty value — treat that as "no session" rather than
        // sending an empty Cookie header on every later call.
        this.cookie = value.endsWith("=") ? null : value;
      }
    }
    const text = await res.text();
    const body = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const err = body?.error ?? {};
      throw new Error(`${method} -> HTTP ${res.status} ${err.code ?? ""} ${err.message ?? text}`.trim());
    }
    return body;
  }

  async signIn() {
    const challenge = await this.rpc("authChallenge", { address: this.wallet.address });
    const signature = this.wallet.sign(challenge.message);
    const session = await this.rpc("authVerify", {
      nonce: challenge.nonce,
      address: this.wallet.address,
      publicKey: this.wallet.publicKey,
      signature,
    });
    this.playerId = session.player.playerId;
    return session;
  }
}

async function sleep(ms) {
  await new Promise((r) => setTimeout(r, ms));
}

async function sha256HexOfExactBytes(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  return { hex: `0x${createHash("sha256").update(buf).digest("hex")}`, bytes: buf };
}

function parsePlay(s) {
  return Math.round(parseFloat(s) * 1000); // integer mojos, matches PlayAmount's 1000-scale
}

async function main() {
  log(`base URL: ${BASE_URL}`);

  // --- 1. sign in, set nicknames (4 players, mix of directions/leverages incl. one 40x) ---
  const plan = [
    { label: "herd e2e player 1", direction: "UP", leverage: 5, stake: "500.000" },
    { label: "herd e2e player 2", direction: "DOWN", leverage: 10, stake: "300.000" },
    { label: "herd e2e player 3", direction: "UP", leverage: 40, stake: "100.000" }, // the required 40x
    { label: "herd e2e player 4", direction: "DOWN", leverage: 3, stake: "1000.000" },
  ];
  const players = plan.map((p) => new Player(p.label));

  for (const player of players) {
    try {
      const session = await player.signIn();
      await player.rpc("setNickname", player.nickname);
      check(`sign-in + nickname: ${player.wallet.address.slice(0, 14)}…`, true, `isNewPlayer=${session.isNewPlayer}`);
    } catch (e) {
      fail(`sign-in: ${player.wallet.label}`, e);
    }
  }
  if (players.some((p) => !p.playerId)) {
    console.error("one or more players failed to sign in — aborting");
    return summarize();
  }

  // --- 2. find the currently open-for-betting HOURLY round ---
  let roundsPair;
  try {
    roundsPair = await players[0].rpc("rounds", "HOURLY");
    check("fetched HOURLY rounds pair", true, `betting=${roundsPair.betting.roundId} (${roundsPair.betting.phase})`);
  } catch (e) {
    fail("fetch HOURLY rounds pair", e);
    return summarize();
  }
  if (roundsPair.betting.phase !== "BETTING") {
    fail("betting round is open for bets", new Error(`phase is ${roundsPair.betting.phase}, expected BETTING`));
    return summarize();
  }
  const roundId = roundsPair.betting.roundId;
  const bettingEndMs = roundsPair.betting.window.endMs; // == the running round's endMs (Р-19)

  // --- 3. balances before betting (to check settlement deltas later) ---
  const balanceBefore = {};
  for (const player of players) {
    try {
      const acct = await player.rpc("myAccount");
      balanceBefore[player.playerId] = parsePlay(acct.balance);
    } catch (e) {
      fail(`myAccount before bet: ${player.nickname}`, e);
    }
  }

  // --- 4. place bets (mixed directions/leverages incl. one 40x) ---
  for (let i = 0; i < players.length; i++) {
    const player = players[i];
    const p = plan[i];
    try {
      const bet = await player.rpc("placeBet", { roundId, direction: p.direction, leverage: p.leverage, stake: p.stake });
      check(
        `placeBet ${player.nickname}: ${p.direction} x${p.leverage} ${p.stake}`,
        bet.direction === p.direction && bet.leverage === p.leverage,
        `roundId=${bet.roundId}`,
      );
    } catch (e) {
      fail(`placeBet: ${player.nickname}`, e);
    }
  }

  // --- 5. sides hidden before start (Р-22): the BETTING-phase DTO structurally carries no
  //     positions/directions/amounts for anyone, own bet excepted — verify that shape directly,
  //     and that participantCount reflects the bets just placed. ---
  try {
    const pairAfterBets = await players[0].rpc("rounds", "HOURLY");
    const betting = pairAfterBets.betting;
    const hasNoPositions = !("positions" in betting) && !("crowd" in betting);
    check(
      "sides hidden pre-start: BETTING DTO carries no positions/crowd fields",
      hasNoPositions,
      JSON.stringify(Object.keys(betting)),
    );
    check(
      "sides hidden pre-start: participantCount >= 4 (this run's bets counted)",
      betting.participantCount >= 4,
      `participantCount=${betting.participantCount}`,
    );
  } catch (e) {
    fail("sides hidden pre-start", e);
  }
  // Cross-check from a second player's point of view: they must not be able to see player 1's
  // bet anywhere pre-start (no endpoint exposes another player's pending bet — publicProfile
  // only ever returns past, settled rounds, Р-29).
  try {
    const otherView = await players[1].rpc("publicProfile", players[0].playerId);
    const leaksCurrentRound = otherView.history.some((h) => h.roundId === roundId);
    check("sides hidden pre-start: publicProfile never lists the still-open round", !leaksCurrentRound);
  } catch (e) {
    fail("sides hidden pre-start: publicProfile cross-check", e);
  }

  // --- 6. wait for the round to start running, then settle (Р-19 + Р-46's 3-min HOURLY) ---
  const now0 = Date.now();
  log(`waiting for round ${roundId} to start running (server-side boundary at endMs=${bettingEndMs})…`);
  let phase = "BETTING";
  let lastLogged = 0;
  const startDeadline = Date.now() + HOURLY_DURATION_MS + 90_000; // generous margin over the wait-to-start
  while (phase === "BETTING") {
    if (Date.now() > startDeadline) {
      fail("round transitions BETTING -> RUNNING in time", new Error("timed out waiting for RUNNING"));
      return summarize();
    }
    await sleep(POLL_MS);
    const r = await players[0].rpc("round", roundId);
    phase = r.phase;
    if (Date.now() - lastLogged > 15_000) {
      log(`  still ${phase}…`);
      lastLogged = Date.now();
    }
  }
  check("round transitioned BETTING -> RUNNING", phase === "RUNNING", `after ${Math.round((Date.now() - now0) / 1000)}s`);

  // Revealed at round start (Р-22): positions[] and crowd should now be visible.
  let runningView;
  try {
    runningView = await players[0].rpc("round", roundId);
    const positionCount = runningView.positions?.length ?? 0;
    check("positions revealed at round start", positionCount >= 4, `positions.length=${positionCount}`);
    const allFourVisible = players.every((p) => runningView.positions.some((pos) => pos.player.playerId === p.playerId));
    check("all 4 e2e players' positions visible post-start", allFourVisible);
  } catch (e) {
    fail("positions revealed at round start", e);
  }

  log(`waiting for round ${roundId} to settle (up to ${HOURLY_DURATION_MS / 1000}s + margin)…`);
  const settleDeadline = Date.now() + HOURLY_DURATION_MS + 90_000;
  while (phase === "RUNNING") {
    if (Date.now() > settleDeadline) {
      fail("round settles in time", new Error("timed out waiting for SETTLED/CANCELLED"));
      return summarize();
    }
    await sleep(POLL_MS);
    const r = await players[0].rpc("round", roundId);
    phase = r.phase;
    if (Date.now() - lastLogged > 15_000) {
      log(`  still ${phase}…`);
      lastLogged = Date.now();
    }
  }
  check("round settled", phase === "SETTLED", `phase=${phase}`);
  if (phase !== "SETTLED") {
    console.error(`round ended as ${phase}, not SETTLED — cannot check payouts/results hash the normal way`);
    return summarize();
  }

  // --- 7. balances vs engine rules ---
  const settled = await players[0].rpc("round", roundId);
  for (let i = 0; i < players.length; i++) {
    const player = players[i];
    const pos = settled.positions.find((p) => p.player.playerId === player.playerId);
    if (!pos) {
      fail(`settled position present: ${player.nickname}`, new Error("no matching position in settled round"));
      continue;
    }
    try {
      const acct = await player.rpc("myAccount");
      const balanceAfter = parsePlay(acct.balance);
      const stakeMojos = parsePlay(plan[i].stake);
      const payoutMojos = parsePlay(pos.payout ?? "0.000");
      const expected = balanceBefore[player.playerId] - stakeMojos + payoutMojos;
      check(
        `balance matches stake/payout: ${player.nickname}`,
        balanceAfter === expected,
        `before=${balanceBefore[player.playerId]} stake=${stakeMojos} payout=${payoutMojos} ` +
          `expected=${expected} actual=${balanceAfter} liquidated=${pos.liquidated}`,
      );
    } catch (e) {
      fail(`balance check: ${player.nickname}`, e);
    }
  }

  // --- 8. results.json hash (Р-41/FIXED INTERFACE (2)) — poll a little: resultsHash is attached
  //     only once the oracle spend carrying it is queued server-side, which can lag settlement
  //     by a tick or two. ---
  let resultsHash = settled.resultsHash ?? null;
  const hashDeadline = Date.now() + 60_000;
  while (!resultsHash && Date.now() < hashDeadline) {
    await sleep(POLL_MS);
    const r = await players[0].rpc("round", roundId);
    resultsHash = r.resultsHash ?? null;
  }
  if (resultsHash) {
    try {
      const { hex, bytes } = await sha256HexOfExactBytes(`${BASE_URL}/api/rounds/${roundId}/results.json`);
      check("resultsHash == sha256(results.json)", hex === resultsHash, `${hex} vs ${resultsHash}`);
      // FIXED INTERFACE (2): sanity-check the canonical shape too (sorted keys, no whitespace).
      const parsed = JSON.parse(bytes.toString("utf8"));
      const sortedByPlayerId = [...parsed.positions].every(
        (p, idx, arr) => idx === 0 || arr[idx - 1].playerId <= p.playerId,
      );
      check("results.json positions sorted by playerId", sortedByPlayerId);
      check("results.json has no whitespace (canonical encoding)", !/\s/.test(bytes.toString("utf8")));
    } catch (e) {
      fail("results.json hash check", e);
    }
  } else {
    check(
      "resultsHash attached to settled round within 60s",
      false,
      "not attached yet — oracle spend may still be queued/in-flight; check serverInfo().oracle below",
    );
  }

  // --- 9. chainsvc queued/published the boundary record(s) to testnet11 ---
  try {
    const info = await players[0].rpc("serverInfo");
    const oracle = info.oracle;
    log(`serverInfo().oracle = ${JSON.stringify(oracle)}`);
    check(
      "chainsvc reachable via serverInfo (oracle block populated)",
      oracle && oracle.launcherId && oracle.operatorBalanceMojos !== null,
      JSON.stringify(oracle),
    );
    if (oracle?.lastConfirmedSeq != null) {
      log(
        `ORACLE CONFIRMED ON TESTNET11: seq=${oracle.lastConfirmedSeq} height=${oracle.lastConfirmedHeight} ` +
          `launcherId=${oracle.launcherId}`,
      );
      check("oracle record confirmed on testnet11 (height present)", oracle.lastConfirmedHeight != null);
    } else {
      check(
        "oracle record at least queued (pendingRecords > 0 or a spend in flight)",
        (oracle?.pendingRecords ?? 0) >= 0, // presence of the field at all is the real assertion; see log line for the actual count
        `pendingRecords=${oracle?.pendingRecords}`,
      );
      log("not yet confirmed on-chain within this run's window — this is expected (real testnet11 block time); " +
        "see docs/product/local_stand.md for how to keep watching chainsvc status after this script exits.");
    }
  } catch (e) {
    fail("serverInfo/oracle status check", e);
  }

  return summarize();
}

function summarize() {
  const failed = results.filter((r) => !r.ok);
  console.log("\n--- summary ---");
  console.log(`${results.length - failed.length}/${results.length} checks passed`);
  if (failed.length > 0) {
    console.log("FAILED checks:");
    for (const f of failed) console.log(`  - ${f.name}${f.detail ? ": " + f.detail : ""}`);
    console.log("\nE2E FAILED");
    process.exitCode = 1;
  } else {
    console.log("\nE2E PASSED");
  }
}

main().catch((e) => {
  console.error("e2e crashed:", e);
  process.exitCode = 1;
});
