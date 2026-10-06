#!/usr/bin/env node
/**
 * The header's WIDTH TEST: does the signed-in header of the app fit a phone with its gutter, whatever the nickname and the PLAY
 * in play are? It needs a real layout engine, which jsdom is not, so it drives headless Chrome over the DevTools protocol (Node's own
 * WebSocket, no dependency) against the app served in its mock mode:
 *
 *   npm run dev -- --port 5173            # in one terminal (the mock API is the default)
 *   node scripts/check-header-width.mjs http://127.0.0.1:5173      # in another; CHROME_BIN=... to name the browser
 *
 * For each width (390, 360, 320 px) and each case below it signs in with the browser test wallet, picks the nickname, places the
 * bet that puts that much PLAY in play, and measures the header's controls. A case passes when
 *   - the page does not scroll sideways (a phone zooms such a page out), and
 *   - the last control ends no nearer the screen's edge than the header's own right padding (the gutter, 12 px), and
 *   - no two controls overlap.
 * Exit code 1 when any case fails, with the table of what was measured.
 *
 * Cases (first tester pass, D12 and its follow-up): a signed-out visitor; a short nickname with 800 PLAY in play (what the 40 px
 * controls were first measured with); a 24-character nickname (the longest there is) with 10,000 PLAY in play (the whole starter
 * pack — the case that ran to 1.3 px from the edge at 320); and the same nickname with 100,000 PLAY in play (a player who has
 * won: the pill's line is overwritten, there is no way to stake that much on the mock).
 */
import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const BASE = process.argv[2] ?? "http://127.0.0.1:5173";
const WIDTHS = (process.env.WIDTHS ?? "390,360,320").split(",").map(Number);
const GUTTER_PX = 12; // `--header-gutter` at phone widths (index.css)
const LONGEST_NICKNAME = "abcdefghijklmnopqrstuvwx"; // 24 characters: `NICKNAME_MAX`

const CASES = [
  { name: "signed out", nickname: null },
  { name: "6-char nickname, 800 in play", nickname: "tester", stake: "800" },
  { name: "24-char nickname, 10,000 in play", nickname: LONGEST_NICKNAME, stake: "max" },
  { name: "24-char nickname, 100,000 in play", nickname: LONGEST_NICKNAME, stake: "max", inPlayText: "+100,000 in play" },
];

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function findChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/google-chrome-stable",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
  ].filter(Boolean);
  const found = candidates.find((path) => existsSync(path));
  if (!found) throw new Error("No Chrome found: set CHROME_BIN to a Chrome or Chromium binary.");
  return found;
}

async function startChrome() {
  const port = 9222 + Math.floor(Math.random() * 600);
  const profile = mkdtempSync(join(tmpdir(), "herd-width-"));
  const chrome = spawn(
    findChrome(),
    ["--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank"],
    { stdio: "ignore" },
  );
  for (let attempt = 0; attempt < 80; attempt++) {
    try {
      const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const page = targets.find((target) => target.type === "page");
      if (page) {
        const socket = new WebSocket(page.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => {
          socket.addEventListener("open", resolve);
          socket.addEventListener("error", reject);
        });
        let nextId = 0;
        const pending = new Map();
        socket.addEventListener("message", (message) => {
          const data = JSON.parse(message.data);
          const waiting = data.id && pending.get(data.id);
          if (!waiting) return;
          pending.delete(data.id);
          if (data.error) waiting.reject(new Error(JSON.stringify(data.error)));
          else waiting.resolve(data.result);
        });
        const send = (method, params = {}) =>
          new Promise((resolve, reject) => {
            const id = ++nextId;
            pending.set(id, { resolve, reject });
            socket.send(JSON.stringify({ id, method, params }));
          });
        const stop = () => {
          try {
            socket.close();
          } catch {
            // already closed
          }
          chrome.kill("SIGKILL");
          rmSync(profile, { recursive: true, force: true });
        };
        return { send, stop };
      }
    } catch {
      // Chrome is not up yet
    }
    await sleep(250);
  }
  chrome.kill("SIGKILL");
  throw new Error("Chrome did not come up");
}

const browser = await startChrome();

async function evaluate(expression) {
  const answer = await browser.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
  if (answer.exceptionDetails) throw new Error(`the page threw: ${JSON.stringify(answer.exceptionDetails).slice(0, 400)}`);
  return answer.result.value;
}

async function waitFor(what, expression, timeoutMs = 30000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    if (await evaluate(expression)) return;
    await sleep(150);
  }
  throw new Error(`timed out waiting for ${what}`);
}

const clickButton = (text) =>
  `(() => { const b = [...document.querySelectorAll("button, a")].find((x) => x.textContent.trim() === ${JSON.stringify(text)} && !x.disabled); if (b) { b.click(); return true; } return false; })()`;

const typeInto = (selector, value) =>
  `(() => { const i = document.querySelector(${JSON.stringify(selector)}); const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set; set.call(i, ${JSON.stringify(value)}); i.dispatchEvent(new Event("input", { bubbles: true })); })()`;

/** Opens the app at `width` px, gets it into the state of `testCase`, and measures the header. */
async function measure(width, testCase) {
  await browser.send("Page.enable");
  await browser.send("Emulation.setDeviceMetricsOverride", { width, height: 740, deviceScaleFactor: 2, mobile: true });
  await browser.send("Emulation.setTouchEmulationEnabled", { enabled: true });
  await browser.send("Page.navigate", { url: BASE });
  await waitFor("the header", `!!document.querySelector(".app-header")`);

  if (testCase.nickname) {
    await waitFor("CONNECT WALLET", clickButton("CONNECT WALLET"));
    await waitFor("the test wallet button", clickButton("Continue with test wallet"));
    await waitFor("the nickname field", `!!document.querySelector("#nickname-input")`);
    await evaluate(typeInto("#nickname-input", testCase.nickname));
    await waitFor("CONTINUE", clickButton("CONTINUE"));
    await waitFor("EXPLORE", clickButton("EXPLORE"));
    await waitFor("the balance pill", `!!document.querySelector(".balance-pill")`);
    // into a room (client-side navigation keeps the mock session) and a bet that puts the PLAY in play
    await waitFor("a market card", `(() => { const a = document.querySelector('a[href="/play/BTC"]'); if (a) { a.click(); return true; } return false; })()`);
    await waitFor("the stake field", `!!document.querySelector("#stake-input")`);
    await waitFor("the available balance", `/Available/.test(document.body.textContent)`);
    if (testCase.stake === "max") await waitFor("MAX", `(() => { const b = document.querySelector('button[aria-label^="MAX"]'); if (b) { b.click(); return true; } return false; })()`);
    else await evaluate(typeInto("#stake-input", testCase.stake));
    await waitFor("PLACE BET", clickButton("PLACE BET"));
    await waitFor("the bet on the card", `/Your bet:/.test(document.body.textContent)`);
    await waitFor("PLAY in play on the pill", `!!document.querySelector(".balance-pill__in-play")`);
    await sleep(2500); // the pill counts up to its number
    if (testCase.inPlayText) {
      await evaluate(`document.querySelector(".balance-pill__in-play").textContent = ${JSON.stringify(testCase.inPlayText)}`);
      await sleep(300);
    }
  }

  return evaluate(`(() => {
    const box = (el) => { const r = el.getBoundingClientRect(); return { left: r.left, right: r.right }; };
    const header = document.querySelector(".app-header");
    const controls = [".app-header__logo", ".fx-toggle", ".balance-pill", ".profile-menu__button", ".app-header__right > .btn"]
      .map((selector) => { const el = header.querySelector(selector); return el ? { name: selector.replace(/^.*[._>]\\s*/, ""), ...box(el) } : null; })
      .filter(Boolean);
    return { innerWidth: window.innerWidth, scrollWidth: document.documentElement.scrollWidth, controls };
  })()`);
}

const rows = [];
let failed = false;
try {
  for (const width of WIDTHS) {
    for (const testCase of CASES) {
      const m = await measure(width, testCase);
      const lastRight = Math.max(...m.controls.map((c) => c.right));
      const gutter = m.innerWidth - lastRight;
      const overlaps = m.controls.some((c, i) => i > 0 && c.left < m.controls[i - 1].right - 0.5);
      const problems = [];
      if (m.scrollWidth > m.innerWidth) problems.push(`the page scrolls sideways (${m.scrollWidth} > ${m.innerWidth})`);
      if (gutter < GUTTER_PX - 0.5) problems.push(`the gutter is ${gutter.toFixed(1)} px, not ${GUTTER_PX}`);
      if (overlaps) problems.push("two controls overlap");
      if (problems.length) failed = true;
      rows.push({ width, case: testCase.name, "last control ends": lastRight.toFixed(1), gutter: gutter.toFixed(1), result: problems.length ? `FAIL: ${problems.join("; ")}` : "ok" });
    }
  }
} finally {
  browser.stop();
}

console.table(rows);
if (failed) {
  console.error("The header does not fit its width in at least one case.");
  process.exit(1);
}
console.log("The header keeps its gutter in every case.");
