// Offline tests of tools/e2e/lib.mjs (`make test-tools`): the collector of PASS/FAIL checks and its exit status, and the
// RPC client — sign-in with a cookie jar, the error the server's envelope turns into — against the fake stand.
import assert from "node:assert/strict";
import http from "node:http";
import { test } from "node:test";
import { fakeWallet, startFakeStand } from "./fake-stand.mjs";
import { Player, createChecks, parsePlay, publicRpc, sha256HexOfExactBytes } from "./lib.mjs";

function collector() {
  const lines = [];
  const codes = [];
  return { lines, codes, checks: createChecks({ print: (line) => lines.push(line), setExitCode: (code) => codes.push(code) }) };
}

test("a check prints PASS or FAIL with its detail and is remembered", () => {
  const { lines, checks } = collector();
  checks.check("balance matches", true, "before=1");
  checks.check("hash matches", false);
  checks.fail("sign-in", new Error("HTTP 500"));
  checks.fail("crash", "just a string");
  assert.deepEqual(lines, ["PASS  balance matches — before=1", "FAIL  hash matches", "FAIL  sign-in — HTTP 500", "FAIL  crash — just a string"]);
  assert.equal(checks.results.length, 4);
  assert.deepEqual(checks.failed().map((r) => r.name), ["hash matches", "sign-in", "crash"]);
});

test("check and fail work taken out of the collector, as run.mjs calls them", () => {
  const { lines, checks } = collector();
  const { check, fail } = checks;
  check("one", true);
  fail("two", new Error("no"));
  assert.deepEqual(lines, ["PASS  one", "FAIL  two — no"]);
});

test("a scope prefixes every name and shares the results, whatever the depth", () => {
  const { lines, checks } = collector();
  const spot = checks.scoped("spot_BTC");
  spot.check("settled", true);
  spot.fail("hash", new Error("differs"));
  spot.scoped("p1").check("deeper", true);
  checks.check("unscoped", true);
  assert.deepEqual(lines, ["PASS  [spot_BTC] settled", "FAIL  [spot_BTC] hash — differs", "PASS  [spot_BTC p1] deeper", "PASS  unscoped"]);
  assert.deepEqual(checks.results.map((r) => r.name), ["[spot_BTC] settled", "[spot_BTC] hash", "[spot_BTC p1] deeper", "unscoped"]);
  assert.deepEqual(spot.results, checks.results, "one list for every scope");
});

test("the summary tallies the checks, lists the failures and sets the exit status only for a failure", () => {
  const green = collector();
  green.checks.check("a", true);
  const summary = green.checks.summarize();
  assert.deepEqual(summary, { passed: 1, failed: 0, ok: true });
  assert.deepEqual(green.codes, []);
  assert.ok(green.lines.includes("1/1 checks passed") && green.lines.includes("\nE2E PASSED"), green.lines.join("|"));

  const red = collector();
  red.checks.check("a", true);
  red.checks.check("b", false, "why");
  assert.deepEqual(red.checks.summarize(), { passed: 1, failed: 1, ok: false });
  assert.deepEqual(red.codes, [1]);
  assert.ok(red.lines.includes("1/2 checks passed") && red.lines.includes("  - b: why") && red.lines.includes("\nE2E FAILED"), red.lines.join("|"));
});

test("PLAY amounts are integer mojos", () => {
  assert.equal(parsePlay("500.000"), 500_000);
  assert.equal(parsePlay("0.001"), 1);
  assert.equal(parsePlay("10000.000"), 10_000_000);
  assert.equal(parsePlay("1234.567"), 1_234_567);
});

test("a player signs in with a key of its own, keeps its session cookie and is signed out of nothing else", async () => {
  const stand = await startFakeStand({ markets: [{ id: "spot_BTC" }] });
  try {
    const [a, b] = [new Player("herd e2e spot p1", { baseUrl: stand.url, createWallet: fakeWallet }), new Player("herd e2e spot p2", { baseUrl: stand.url, createWallet: fakeWallet })];
    await assert.rejects(() => a.rpc("myAccount"), (e) => e.code === "NOT_SIGNED_IN" && e.status === 401 && /myAccount -> HTTP 401 NOT_SIGNED_IN/.test(e.message));

    const session = await a.signIn();
    assert.equal(session.isNewPlayer, true);
    assert.ok(a.playerId && a.cookie?.startsWith("herd_session="));
    assert.equal((await a.rpc("myAccount")).balance, "10000.000");
    await a.rpc("setNickname", a.nickname);

    await b.signIn();
    assert.notEqual(a.playerId, b.playerId);
    assert.notEqual(a.cookie, b.cookie, "a jar per player");
    assert.equal((await new Player("herd e2e spot p1", { baseUrl: stand.url, createWallet: fakeWallet }).signIn()).isNewPlayer, false, "the same label is the same player");
  } finally {
    await stand.close();
  }
});

test("a call that needs no session needs no player; an error of the stand carries its code and status", async () => {
  const stand = await startFakeStand({ markets: [{ id: "spot_BTC" }] });
  try {
    const lobby = await publicRpc(stand.url, "markets");
    assert.deepEqual(lobby.markets.map((m) => m.id), ["spot_BTC"]);
    await assert.rejects(() => publicRpc(stand.url, "rounds", "HOURLY", "NOPE"), (e) => e.code === "MARKET_NOT_FOUND" && e.status === 404);
    await assert.rejects(() => publicRpc(stand.url, "noSuchMethod"), (e) => e.status === 404 && /noSuchMethod -> HTTP 404/.test(e.message));
  } finally {
    await stand.close();
  }
});

test("the nickname of a player is the label's letters and digits, and fits what the server takes", () => {
  const p = new Player("herd e2e spot p1", { baseUrl: "http://unused", createWallet: fakeWallet });
  assert.equal(p.nickname, "E2E herd e2e spot p1");
  assert.equal(new Player("herd e2e player 3", { baseUrl: "http://unused", createWallet: fakeWallet }).nickname, "E2E herd e2e player 3", "the BTC run's, unchanged");
  assert.equal(new Player("a/b_c", { baseUrl: "http://unused", createWallet: fakeWallet }).nickname, "E2E a b c");
  // the server takes 3-24 of [A-Za-z0-9 _.-]
  for (const family of ["spot", "xyz", "odds"]) {
    for (let n = 1; n <= 4; n++) {
      const nick = new Player(`herd e2e ${family} p${n}`, { baseUrl: "http://unused", createWallet: fakeWallet }).nickname;
      assert.match(nick, /^[A-Za-z0-9 _.-]{3,24}$/, nick);
    }
  }
});

test("the hash of a document is the sha256 of its exact bytes", async () => {
  const body = Buffer.from('{"b":1,"a":[1, 2]}\n');
  const server = http.createServer((req, res) => {
    if (req.url === "/ok") return res.end(body);
    res.writeHead(404).end();
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const { hex, bytes } = await sha256HexOfExactBytes(`${base}/ok`);
    assert.deepEqual(bytes, body);
    assert.match(hex, /^0x[0-9a-f]{64}$/);
    const { createHash } = await import("node:crypto");
    assert.equal(hex, `0x${createHash("sha256").update(body).digest("hex")}`);
    await assert.rejects(() => sha256HexOfExactBytes(`${base}/missing`), /HTTP 404/);
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve) => server.close(resolve));
  }
});
