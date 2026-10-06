// What every end-to-end run of the stand needs, whichever market it plays — shared by tools/e2e/run.mjs (the BTC run,
// `make e2e`) and tools/e2e/run-markets.mjs (one market of each price source, `make e2e-markets`): a tiny RPC client with
// a cookie jar per player (FIXED INTERFACE (1)), the collector of PASS/FAIL checks and its summary, sleeping, the sha256 of
// the exact bytes behind a URL, and PLAY amounts as integer mojos.
//
// No dependency of its own — the test wallet (the only one: `chia-wallet-sdk-wasm`, see testWallet.mjs) is handed in
// by whoever runs, so this file loads without it and the offline tests (`tools/e2e/*.test.mjs`) run without
// `web/node_modules`.
import { createHash } from "node:crypto";

// --- checks ---

/**
 * The checks of one run: `check(name, ok, detail)` prints `PASS`/`FAIL  name — detail` and remembers it, `fail(name,
 * err)` is a failed check with the error's text, `summarize()` prints the tally and the failures and sets the exit
 * status. `scoped("spot_BTC")` is the same collector with every name prefixed `[spot_BTC] `, so that the checks of
 * markets played side by side tell their markets apart.
 *
 * `print` and `setExitCode` are the console and `process.exitCode` by default; a test hands in its own.
 */
export function createChecks({ print = console.log, setExitCode = (code) => { process.exitCode = code; } } = {}) {
  const results = []; // { ok: boolean, name: string, detail?: string }

  // No `this` anywhere: run.mjs takes `check` and `fail` out of the collector and calls them bare.
  function make(prefix) {
    const named = (name) => (prefix ? `[${prefix}] ${name}` : name);
    const api = {
      results,
      check(name, ok, detail) {
        const full = named(name);
        results.push({ name: full, ok, detail });
        print(`${ok ? "PASS" : "FAIL"}  ${full}${detail ? " — " + detail : ""}`);
      },
      fail(name, err) {
        api.check(name, false, err instanceof Error ? err.message : String(err));
      },
      scoped(scope) {
        return make(prefix ? `${prefix} ${scope}` : scope);
      },
    };
    return api;
  }

  const root = make("");
  return {
    ...root,
    failed: () => results.filter((r) => !r.ok),
    summarize() {
      const failed = results.filter((r) => !r.ok);
      print("\n--- summary ---");
      print(`${results.length - failed.length}/${results.length} checks passed`);
      if (failed.length > 0) {
        print("FAILED checks:");
        for (const f of failed) print(`  - ${f.name}${f.detail ? ": " + f.detail : ""}`);
        print("\nE2E FAILED");
        setExitCode(1);
      } else {
        print("\nE2E PASSED");
      }
      return { passed: results.length - failed.length, failed: failed.length, ok: failed.length === 0 };
    },
  };
}

// --- RPC client (FIXED INTERFACE (1)) ---

/** `POST <baseUrl>/api/rpc/<method>` with `{args}`; `cookie` is the session, if any. Resolves `{ body, setCookies }`. */
async function callRpc(baseUrl, cookie, method, args) {
  const headers = { "Content-Type": "application/json" };
  if (cookie) headers.Cookie = cookie;
  const res = await fetch(`${baseUrl}/api/rpc/${method}`, { method: "POST", headers, body: JSON.stringify({ args }) });
  const setCookies = res.headers.getSetCookie?.() ?? [];
  const text = await res.text();
  const body = text ? JSON.parse(text) : {};
  if (!res.ok) {
    const err = body?.error ?? {};
    const error = new Error(`${method} -> HTTP ${res.status} ${err.code ?? ""} ${err.message ?? text}`.trim());
    error.status = res.status;
    error.code = err.code ?? null;
    throw error;
  }
  return { body, setCookies };
}

/** An RPC call that needs no session (`markets`, `rounds`, `round`, `serverInfo` …): no player, no wallet. */
export async function publicRpc(baseUrl, method, ...args) {
  return (await callRpc(baseUrl, null, method, args)).body;
}

/**
 * One simulated player: its own test wallet (the key signs the sign-in the way the browser's test wallet does) and its
 * own cookie jar. `createWallet(label)` gives `{ label, address, publicKey, sign }` (testWallet.mjs `createTestWallet`).
 * The nickname is derived from the label (the server takes 3–24 of letters, digits, space, `_`, `.`, `-`).
 */
export class Player {
  constructor(label, { baseUrl, createWallet }) {
    this.baseUrl = baseUrl;
    this.wallet = createWallet(label);
    this.cookie = null; // "herd_session=..."
    this.playerId = null;
    this.nickname = `E2E ${label.replace(/[^a-zA-Z0-9]+/g, " ").trim()}`;
  }

  async rpc(method, ...args) {
    const { body, setCookies } = await callRpc(this.baseUrl, this.cookie, method, args);
    for (const sc of setCookies) {
      if (sc.startsWith("herd_session=")) {
        const value = sc.split(";", 1)[0];
        // Clearing (signOut) sets an empty value — treat that as "no session" rather than
        // sending an empty Cookie header on every later call.
        this.cookie = value.endsWith("=") ? null : value;
      }
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

// --- small helpers ---

export async function sleep(ms) {
  await new Promise((r) => setTimeout(r, ms));
}

/** `GET url`, the exact bytes it answers, and their `0x` + sha256 hex — what a resultsHash is. */
export async function sha256HexOfExactBytes(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`GET ${url} -> HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  return { hex: `0x${createHash("sha256").update(buf).digest("hex")}`, bytes: buf };
}

/** A PLAY amount string ("500.000") as integer mojos — matches PlayAmount's 1000 scale. */
export function parsePlay(s) {
  return Math.round(parseFloat(s) * 1000);
}
