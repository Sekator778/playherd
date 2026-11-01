// Offline tests of `tools/herd-operator.sh feedback` (`make test-tools`, D-81): the script against a fake server on localhost —
// what it asks for (the bearer token, `sinceMs`, `limit`), what it prints (the table, the count by country, the raw answer for
// --json), what it does with a visitor's text (control characters never reach the operator's terminal) and what it refuses.
// The table is made with jq: the tests that read it are skipped on a machine without jq (`--json` and the refusals do not need it).
import assert from "node:assert/strict";
import { execFile, spawnSync } from "node:child_process";
import { createServer } from "node:http";
import { dirname, resolve } from "node:path";
import { after, before, describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const SCRIPT = resolve(dirname(fileURLToPath(import.meta.url)), "herd-operator.sh");
const TOKEN = "operator-token-for-the-test";
const HAS_JQ = spawnSync("jq", ["--version"], { stdio: "ignore" }).status === 0;
const jq = { skip: HAS_JQ ? false : "jq is not installed" };

const DAY_MS = Date.UTC(2026, 9, 10); // 2026-10-10 00:00 UTC

const ITEMS = [
  { id: 5, receivedAtMs: Date.UTC(2026, 9, 11, 8, 34, 2), message: "Love it!\nBut the sound is \u001b[31mRED\u001b[0m and \u0007 beeps.\tTabbed.", country: "DE", timeZone: "Europe/Berlin", language: "de-DE", page: "/room/BTC", client: "web", playerId: "p1", nickname: "Alice", roundsPlayed: 14 },
  { id: 4, receivedAtMs: Date.UTC(2026, 9, 11, 6, 26, 40), message: "Anonymous words", country: null, timeZone: null, language: null, page: null, client: null, playerId: null, nickname: null, roundsPlayed: null },
  { id: 3, receivedAtMs: Date.UTC(2026, 9, 10, 2, 40, 0), message: "Очень классная игра 日本語", country: "RU", timeZone: "Europe/Moscow", language: "ru", page: "/lobby", client: "pwa", playerId: "p2", nickname: "Boris", roundsPlayed: 0 },
  { id: 2, receivedAtMs: Date.UTC(2026, 9, 8, 22, 53, 20), message: "Second German", country: "DE", timeZone: "America/Argentina/ComodRivadavia", language: "es-419-u-nu-latn-x-private", page: "/some/really/long/path/that/is/long", client: "app", playerId: "p3", nickname: null, roundsPlayed: 2 },
];

let server;
let baseUrl;
const requests = [];
let answer = () => ({ status: 200, body: JSON.stringify(ITEMS) });

before(async () => {
  server = createServer((req, res) => {
    requests.push({ url: req.url, authorization: req.headers.authorization });
    const { status, body } = req.headers.authorization === `Bearer ${TOKEN}`
      ? answer(req.url)
      : { status: 401, body: '{"error":{"code":"UNAUTHORIZED","message":"missing or invalid operator bearer token"}}' };
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(body);
  });
  await new Promise((done) => server.listen(0, "127.0.0.1", done));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(() => new Promise((done) => server.close(done)));

/** The script with these arguments: its exit code, stdout and stderr. */
function feedback(args, { token = TOKEN, reply } = {}) {
  requests.length = 0;
  answer = reply ?? (() => ({ status: 200, body: JSON.stringify(ITEMS) }));
  return new Promise((done) => {
    execFile("bash", [SCRIPT, "feedback", ...args], {
      env: { ...process.env, HERD_SERVER_URL: baseUrl, HERD_OPERATOR_TOKEN: token, HERD_OPERATOR_TOKEN_FILE: "" },
    }, (error, stdout, stderr) => done({ code: error ? error.code : 0, stdout, stderr }));
  });
}

describe("herd-operator.sh feedback", () => {
  it("--json prints the server's answer as it is and asks with the bearer token", async () => {
    const { code, stdout } = await feedback(["--json"]);

    assert.equal(code, 0);
    assert.deepEqual(JSON.parse(stdout), ITEMS);
    assert.deepEqual(requests, [{ url: "/api/admin/feedback", authorization: `Bearer ${TOKEN}` }]);
  });

  it("--since is 00:00 UTC of that day in milliseconds, --limit goes as it is, and both are in the query string", async () => {
    await feedback(["--json", "--since", "2026-10-10", "--limit", "5"]);
    assert.equal(requests[0].url, `/api/admin/feedback?sinceMs=${DAY_MS}&limit=5`);

    await feedback(["--json", "--since=2026-10-10"]);
    assert.equal(requests[0].url, `/api/admin/feedback?sinceMs=${DAY_MS}`);

    await feedback(["--limit", "20", "--json"]);
    assert.equal(requests[0].url, "/api/admin/feedback?limit=20");
  });

  it("the table has a row for every message in UTC, a dash for what is not known, and the count by country", jq, async () => {
    const { code, stdout } = await feedback([]);
    const lines = stdout.split("\n");

    assert.equal(code, 0);
    assert.equal(lines[0], "Feedback (all), newest first: 4 messages");
    assert.match(lines[2], /^time \(UTC\) +country +time zone +language +client +page +nickname +rounds +message$/);
    assert.match(lines[3], /^2026-10-11 08:34:02 +DE +Europe\/Berlin +de-DE +web +\/room\/BTC +Alice +14 +Love it! \/ But the sound is/);
    assert.match(lines[4], /^2026-10-11 06:26:40 +- +- +- +- +- +- +- +Anonymous words$/);
    assert.match(lines[5], /^2026-10-10 02:40:00 +RU +Europe\/Moscow +ru +pwa +\/lobby +Boris +0 +Очень классная игра 日本語$/);
    assert.match(lines[6], /^2026-10-08 22:53:20 +DE +America\/Argentina\/Comod~ +es-419-u-nu~ +app +\/some\/really\/long\/path\/that~ +- +2 +Second German$/);
    const footer = lines.slice(lines.indexOf("By country:"));
    assert.deepEqual(footer.map((l) => l.trim().replace(/ +/g, " ")).filter(Boolean), ["By country:", "DE 2", "unknown 1", "RU 1"]);
  });

  it("the columns line up", jq, async () => {
    const { stdout } = await feedback([]);
    const rows = stdout.split("\n").slice(2, 7);
    const columnOfMessage = rows[0].indexOf("message");

    assert.ok(columnOfMessage > 0);
    assert.equal(rows[1].indexOf("Love it!"), columnOfMessage);
    assert.equal(rows[2].indexOf("Anonymous words"), columnOfMessage);
  });

  it("a visitor's control characters never reach the terminal", jq, async () => {
    const { stdout } = await feedback([]);

    assert.doesNotMatch(stdout, /[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/, "no control character but the line feed that ends a line");
    assert.match(stdout, /Love it! \/ But the sound is {2}\[31mRED {1}\[0m and {3}beeps\. Tabbed\./);
  });

  it("says so when the server has no message, and in which period", jq, async () => {
    const empty = { reply: () => ({ status: 200, body: "[]" }) };

    assert.equal((await feedback([], empty)).stdout, "No feedback.\n");
    assert.equal((await feedback(["--since", "2026-10-10"], empty)).stdout, "No feedback since 2026-10-10 (UTC).\n");
  });

  it("says when the list is cut at the limit asked for", jq, async () => {
    const { stdout } = await feedback(["--limit", "4"]);

    assert.match(stdout.split("\n")[0], /^Feedback \(all\), newest first: 4 messages — the newest 4 only; --limit \(at most 1000\) or --since shows more$/);
  });

  it("a refusal of the server is shown as it is and is a failure", jq, async () => {
    const wrongToken = await feedback([], { token: "not-the-token" });
    assert.equal(wrongToken.code, 1);
    assert.match(wrongToken.stderr, /did not answer with a list/);
    assert.match(wrongToken.stderr, /UNAUTHORIZED/);

    const bad = await feedback(["--limit", "5"], { reply: () => ({ status: 400, body: '{"error":{"code":"BAD_REQUEST","message":"limit must be a whole number"}}' }) });
    assert.equal(bad.code, 1);
    assert.match(bad.stderr, /BAD_REQUEST/);
  });

  it("refuses arguments it does not understand before asking anything", async () => {
    for (const [args, message] of [
      [["--since", "2026-02-30"], /--since must be a date/],
      [["--since", "yesterday"], /--since must be a date/],
      [["--since", "2026-1-1"], /--since must be a date/],
      [["--since"], /--since needs a date/],
      [["--limit", "0"], /--limit must be a whole number from 1 to 1000/],
      [["--limit", "1001"], /--limit must be a whole number from 1 to 1000/],
      [["--limit", "007"], /--limit must be a whole number from 1 to 1000/],
      [["--limit", "many"], /--limit must be a whole number from 1 to 1000/],
      [["--limit"], /--limit needs a number/],
      [["--bogus"], /unknown argument: --bogus/],
      [["today"], /unknown argument: today/],
    ]) {
      const { code, stderr } = await feedback(args);

      assert.equal(code, 1, args.join(" "));
      assert.match(stderr, message, args.join(" "));
      assert.deepEqual(requests, [], `${args.join(" ")}: nothing was asked`);
    }
  });

  it("is in the usage text", async () => {
    const usage = spawnSync("bash", [SCRIPT, "--help"], { encoding: "utf8" });

    assert.match(usage.stderr, /feedback \[--since YYYY-MM-DD\] \[--limit N\] \[--json\]/);
  });
});
