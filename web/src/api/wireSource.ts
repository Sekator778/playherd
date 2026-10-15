/// <reference types="node" />
/**
 * Test-only: the server's REAL answers, as the server's own tests wrote them to `web/src/api/wire/<name>.json`
 * (`{"method", "args", "response"}`, and a `"status"` on the error files), and a `fetch` that serves them — so
 * `RealApi` and the pages can be run over what the server really says instead of over what `contract.ts` says it
 * should say. Nothing in the app imports this file. (Finding 14 of the parity review: every other fixture in this
 * folder was written from the contract, so a field the server spelt differently passed the whole suite.)
 *
 * The files are generated, not written by hand, and may not exist yet (a fresh checkout before the server's
 * generator ran): {@link wire} then throws a message that says which file, who writes it and what to do — the tests
 * that read it FAIL, loudly, and never quietly pass over nothing.
 *
 * `HERD_WIRE_DIR` points the loader at another folder holding the same files (used to try the tests against answers
 * captured from a running stand); unset, it reads `web/src/api/wire/`.
 */
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

/** The fixtures the server writes. `rounds.hourly` is `rounds("HOURLY", <a market>)`; each `round.*` is `round(<id>)` of a
 *  round in that phase; `authVerify` is the answer to a FIRST sign-in (a new player, a starter pack credited); the three
 *  `error.*` files carry the error envelope and its HTTP `status`. */
export const WIRE_NAMES = [
  "serverInfo",
  "supply",
  "markets",
  "rounds.hourly",
  "round.betting",
  "round.running",
  "round.settled",
  "round.cancelled",
  "betFeed",
  "myPositions",
  "myAccount",
  "myLedger",
  "mySeasonRecord",
  "crowdAccount",
  "crowdOverview",
  "ratings",
  "publicProfile",
  "cancellations",
  "unseenResults",
  "priceSeries",
  "referencePrice",
  "previewLiquidation",
  "home",
  "currentPlayer",
  "authVerify",
  "error.notSignedIn",
  "error.marketNotFound",
  "error.engineNotReady",
] as const;

export type WireName = (typeof WIRE_NAMES)[number];

export interface WireFile {
  /** The RPC method the server was asked. */
  method: string;
  /** The positional `args` it was asked with. */
  args: unknown[];
  /** The server's answer: the DTO, or — in an `error.*` file — the `{"error":{"code","message"}}` envelope. */
  response: unknown;
  /** The HTTP status; absent on a success. */
  status?: number;
}

/** Under Vitest a module's `__dirname` is the folder of the file (this one: `web/src/api`); the working directory is the fallback. */
const HERE: string = typeof __dirname === "string" ? __dirname : join(process.cwd(), "src", "api");
export const WIRE_DIR: string = process.env.HERD_WIRE_DIR ?? join(HERE, "wire");

const cache = new Map<string, WireFile>();

function pathOf(name: string): string {
  return `${WIRE_DIR.replace(/\/$/, "")}/${name}.json`;
}

/** The names of the listed fixtures that are not there. */
export function missingWireFiles(): WireName[] {
  return WIRE_NAMES.filter((name) => !existsSync(pathOf(name)));
}

/** The one-line instruction every missing-file failure ends with. */
export const WIRE_HELP =
  "These files are written by the server's tests (the answers its RPC layer really gives); until they exist the web tests that read them cannot run. " +
  "Generate them with the server's wire-fixture test, then re-run `npm test -- --run`.";

export function wire(name: WireName): WireFile {
  const cached = cache.get(name);
  if (cached) return cached;
  const path = pathOf(name);
  if (!existsSync(path)) {
    throw new Error(`Wire fixture "${name}" is missing: ${path}\n${WIRE_HELP}`);
  }
  let file: WireFile;
  try {
    file = JSON.parse(readFileSync(path, "utf8")) as WireFile;
  } catch (err) {
    throw new Error(`Wire fixture "${name}" (${path}) is not valid JSON: ${(err as Error).message}`, { cause: err });
  }
  if (typeof file.method !== "string" || !Array.isArray(file.args) || !("response" in file)) {
    throw new Error(`Wire fixture "${name}" (${path}) must be {"method": string, "args": [...], "response": ...}; it has the keys ${Object.keys(file).join(", ")}`);
  }
  cache.set(name, file);
  return file;
}

/** `wire(name).response`, typed by the caller (the schema checks say whether it is what the contract describes). */
export function wireResponse<T>(name: WireName): T {
  return wire(name).response as T;
}

/** The HTTP status an error file carries — at the top of the file, or beside the envelope inside `response`. */
export function wireStatus(name: WireName): number | undefined {
  const file = wire(name);
  if (typeof file.status === "number") return file.status;
  const inside = (file.response as { status?: unknown } | null)?.status;
  return typeof inside === "number" ? inside : undefined;
}

/** An error file's envelope: `{"error": {"code", "message"}}` — wherever the file put it (the `response`, or the file itself). */
export function wireError(name: WireName): { status: number; code: string; message: string } {
  const file = wire(name);
  const envelope = ((file.response as { error?: { code?: string; message?: string } } | null)?.error ?? (file as unknown as { error?: { code?: string; message?: string } }).error) ?? null;
  if (!envelope || typeof envelope.code !== "string") {
    throw new Error(`Wire fixture "${name}" is an error file but carries no {"error": {"code", "message"}} envelope`);
  }
  const status = wireStatus(name);
  if (status === undefined) throw new Error(`Wire fixture "${name}" is an error file but carries no "status"`);
  return { status, code: envelope.code, message: envelope.message ?? "" };
}

// ---------------------------------------------------------------------------
// A fetch that serves the fixtures
// ---------------------------------------------------------------------------

export interface WireReply {
  status?: number;
  body: unknown;
}

export type WireOverrides = Partial<Record<string, (args: unknown[]) => WireReply | Promise<WireReply>>>;

const NOT_FOUND = (message: string): WireReply => ({ status: 404, body: { error: { code: "NOT_FOUND", message } } });

/** The round fixture whose `round(<id>)` this is. */
function roundReply(id: unknown): WireReply {
  for (const name of ["round.betting", "round.running", "round.settled", "round.cancelled"] as const) {
    if (wire(name).args[0] === id) return { body: wire(name).response };
  }
  return NOT_FOUND(`unknown round ${String(id)}`);
}

interface MarketLike {
  id: string;
  price: { price: string; asOfMs: number } | null;
  priceDecimals: number;
}

function marketById(id: unknown): MarketLike | undefined {
  const markets = (wire("markets").response as { markets: MarketLike[] }).markets;
  return markets.find((m) => m.id === id);
}

/** What the fixtures answer, by method. A method the server's tests did not record answers 404 (the page then shows
 *  its not-found state) — a test that needs it passes an override. */
function defaultReply(method: string, args: unknown[]): WireReply {
  switch (method) {
    case "serverInfo":
    case "supply":
    case "markets":
    case "betFeed":
    case "myPositions":
    case "myAccount":
    case "mySeasonRecord":
    case "crowdOverview":
    case "ratings":
    case "previewLiquidation":
    case "home":
    case "currentPlayer":
    case "authVerify":
    case "unseenResults":
    case "cancellations":
      return { body: wire(method as WireName).response };
    case "rounds":
      return { body: wire("rounds.hourly").response };
    case "round":
      return roundReply(args[0]);
    case "myLedger": {
      // The recorded page is the newest one; an older page (a `before` cursor) is empty.
      const params = args[0] as { before?: number } | null | undefined;
      return params?.before !== undefined && params.before !== null ? { body: { items: [], hasMore: false } } : { body: wire("myLedger").response };
    }
    case "crowdAccount":
      return { body: wire("crowdAccount").response };
    case "publicProfile":
    case "myProfile":
      return { body: wire("publicProfile").response };
    case "referencePrice": {
      const recorded = wire("referencePrice");
      if (recorded.args[0] === args[0]) return { body: recorded.response };
      const market = marketById(args[0]);
      return market?.price ? { body: { price: market.price.price, asOfMs: market.price.asOfMs } } : { status: 404, body: { error: { code: "MARKET_NOT_FOUND", message: `unknown market ${String(args[0])}` } } };
    }
    case "priceSeries": {
      const recorded = wire("priceSeries");
      if (recorded.args[0] === args[0]) return { body: recorded.response };
      const market = marketById(args[0]);
      return market?.price
        ? { body: { marketId: market.id, priceDecimals: market.priceDecimals, ticks: [{ atMs: market.price.asOfMs - 3000, price: market.price.price }, { atMs: market.price.asOfMs, price: market.price.price }] } }
        : { status: 404, body: { error: { code: "MARKET_NOT_FOUND", message: `unknown market ${String(args[0])}` } } };
    }
    case "myDepositAddress":
      return { body: { address: "txch1wireDepositAddressForTests" } };
    case "acknowledgeResults":
    case "signOut":
    case "cancelBet":
      return { body: {} };
    default:
      return NOT_FOUND(`no wire fixture answers ${method}`);
  }
}

/**
 * A `fetch` over the fixtures: `POST /api/rpc/<method>` with `{"args": [...]}` answers what the server's tests
 * recorded. `calls` lists what was asked (method and args), in order.
 */
export function wireFetch(overrides: WireOverrides = {}) {
  const calls: Array<{ method: string; args: unknown[] }> = [];
  const fetchImpl = async (input: unknown, init?: { body?: unknown }) => {
    const url = String(input);
    const method = url.slice(url.lastIndexOf("/") + 1);
    const args = ((init?.body ? (JSON.parse(String(init.body)) as { args?: unknown[] }).args : []) ?? []) as unknown[];
    calls.push({ method, args });
    const override = overrides[method];
    const reply = override ? await override(args) : defaultReply(method, args);
    const status = reply.status ?? 200;
    return { ok: status >= 200 && status < 300, status, text: async () => (reply.body === undefined ? "" : JSON.stringify(reply.body)) };
  };
  return Object.assign(fetchImpl, { calls });
}
