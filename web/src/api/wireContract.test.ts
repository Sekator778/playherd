/**
 * `RealApi` over what the server REALLY answers (`api/wire/*.json`, written by the server's tests): every answer is
 * decoded by the real client, compared with `contract.ts` field by field (`wireSchema.ts`), and read back through
 * the real error mapping. Finding 14 of the parity review: this is the test that was missing — the fixtures of every
 * other test were written from the contract, so a server that spelt a field differently (`roundDurationsSec` in
 * lowercase) passed 1331 tests and printed "NaN SEC" on the public Status page.
 *
 * If a listed file is missing the very first test fails and says which, who writes them and what to run.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError, type Api, type AuthSessionDto, type AuthVerifyRequest, type Interval, type PageParams } from "./contract";
import { resetEngineStateForTests } from "./engineState";
import { realApi } from "./realApi";
import * as schema from "./wireSchema";
import { marketPriceScaleProblems, problemsOf, roundPriceScaleProblems, seriesPriceScaleProblems, type Check } from "./wireSchema";
import { WIRE_HELP, WIRE_NAMES, missingWireFiles, wire, wireError, wireFetch, wireResponse, type WireName } from "./wireSource";

interface Case {
  /** The `RealApi` call the fixture's `method` and `args` stand for. */
  call: (api: Api, args: unknown[]) => Promise<unknown>;
  /** What the contract says the answer is. */
  check: Check;
}

const orUndefined = <T>(v: unknown) => (v === null || v === undefined ? undefined : (v as T));

const CASES: Record<Exclude<WireName, `error.${string}`>, Case> = {
  serverInfo: { call: (api) => api.serverInfo!(), check: schema.serverInfo },
  supply: { call: (api) => api.supply!(), check: schema.supply },
  markets: { call: (api) => api.markets(), check: schema.markets },
  "rounds.hourly": { call: (api, a) => api.rounds(a[0] as Interval, orUndefined<string>(a[1])), check: schema.roundsPair },
  "round.betting": { call: (api, a) => api.round(a[0] as string), check: schema.round },
  "round.running": { call: (api, a) => api.round(a[0] as string), check: schema.round },
  "round.settled": { call: (api, a) => api.round(a[0] as string), check: schema.round },
  "round.cancelled": { call: (api, a) => api.round(a[0] as string), check: schema.round },
  betFeed: { call: (api, a) => api.betFeed!(a[0] as string, orUndefined<number>(a[1])), check: schema.betFeed },
  myPositions: { call: (api) => api.myPositions(), check: (v, p, pr) => schema.arr(schema.myPosition)(v, p, pr) },
  myAccount: { call: (api) => api.myAccount(), check: schema.playerAccount },
  myLedger: { call: (api, a) => api.myLedger(orUndefined<PageParams>(a[0])), check: schema.ledgerPage },
  mySeasonRecord: { call: (api, a) => api.mySeasonRecord(orUndefined<string>(a[0])), check: schema.seasonRecord },
  crowdAccount: { call: (api, a) => api.crowdAccount(orUndefined<string>(a[0])), check: schema.crowdAccount },
  crowdOverview: { call: (api) => api.crowdOverview(), check: schema.crowdOverview },
  ratings: { call: (api, a) => api.ratings(orUndefined<string>(a[0])), check: schema.ratings },
  publicProfile: { call: (api, a) => api.publicProfile(a[0] as string), check: schema.publicProfile },
  cancellations: { call: (api, a) => api.cancellations(orUndefined<PageParams>(a[0])), check: schema.cancellationsPage },
  unseenResults: { call: (api) => api.unseenResults!(), check: (v, p, pr) => schema.arr(schema.profileRoundEntry)(v, p, pr) },
  priceSeries: { call: (api, a) => api.priceSeries(a[0] as string, orUndefined<number>(a[1])), check: schema.priceSeries },
  referencePrice: { call: (api, a) => api.referencePrice(a[0] as string), check: schema.marketReference },
  previewLiquidation: { call: (api, a) => api.previewLiquidation(a[0] as Parameters<Api["previewLiquidation"]>[0]), check: schema.liquidationPreview },
  home: { call: (api) => api.home(), check: schema.home },
  currentPlayer: { call: (api) => api.currentPlayer(), check: schema.playerSummary },
  authVerify: { call: (api, a) => api.authVerify(a[0] as AuthVerifyRequest), check: schema.authSession },
};

const DATA_NAMES = Object.keys(CASES) as Array<keyof typeof CASES>;
const ERROR_NAMES = WIRE_NAMES.filter((n): n is Extract<WireName, `error.${string}`> => n.startsWith("error."));

/** The args as the web sends them: trailing "omitted" arguments are `null` (`realApi.ts`), and a trailing null is the same as none. */
const trimmed = (args: unknown[]) => {
  const copy = [...args];
  while (copy.length > 0 && (copy[copy.length - 1] === null || copy[copy.length - 1] === undefined)) copy.pop();
  return copy;
};

beforeEach(() => resetEngineStateForTests());
afterEach(() => vi.unstubAllGlobals());

describe("the wire fixtures are there", () => {
  it("every listed file exists in web/src/api/wire/", () => {
    const missing = missingWireFiles();
    expect(
      missing,
      `Missing wire fixture(s): ${missing.map((n) => `${n}.json`).join(", ")}.\n${WIRE_HELP}`,
    ).toEqual([]);
  });

  it.each(WIRE_NAMES)("%s.json is {method, args, response} and names a real RPC method", (name) => {
    const file = wire(name);
    expect(typeof file.method).toBe("string");
    expect(Array.isArray(file.args)).toBe(true);
    expect(typeof (realApi as unknown as Record<string, unknown>)[file.method]).toBe("function");
  });
});

describe("RealApi over the server's real answers — each one is what contract.ts says it is", () => {
  it.each(DATA_NAMES)("%s: decoded by RealApi, equal to the server's JSON, and valid against the contract", async (name) => {
    const file = wire(name);
    const fetchStub = wireFetch();
    vi.stubGlobal("fetch", fetchStub);
    const decoded = await CASES[name].call(realApi, file.args);
    // RealApi decodes the JSON and hands it on as typed: nothing is dropped or renamed on the way.
    expect(decoded).toEqual(file.response);
    // What RealApi sent is what the server was asked in the test that wrote the file.
    expect(fetchStub.calls[0].method).toBe(file.method);
    expect(trimmed(fetchStub.calls[0].args)).toEqual(trimmed(file.args));
    // And it is what the contract describes: every required field there, every type right.
    const problems = problemsOf(CASES[name].check, decoded);
    expect(problems, `${name}.json does not match contract.ts:\n  ${problems.join("\n  ")}`).toEqual([]);
  });
});

describe("the details that broke before", () => {
  it("serverInfo.roundDurationsSec is keyed HOURLY / DAILY with real numbers (it came lowercase: 'NaN SEC' on /status)", () => {
    const info = wireResponse<{ roundDurationsSec: Record<string, unknown> }>("serverInfo");
    expect(Object.keys(info.roundDurationsSec).sort()).toEqual(["DAILY", "HOURLY"]);
    expect(Number.isFinite(info.roundDurationsSec.HOURLY)).toBe(true);
    expect(Number.isFinite(info.roundDurationsSec.DAILY)).toBe(true);
  });

  it("every round carries its market's kind, price scale and source — and they agree with markets() (a round's source may also be its market's backup exchange)", () => {
    const markets = wireResponse<{ markets: Array<{ id: string; kind: string; priceDecimals: number; source: string; backupSource?: string | null }> }>("markets").markets;
    const rounds = (["round.betting", "round.running", "round.settled", "round.cancelled"] as const).map((n) => [n, wireResponse<Record<string, unknown>>(n)] as const);
    const pair = wireResponse<{ running: Record<string, unknown>; betting: Record<string, unknown> }>("rounds.hourly");
    const all = [...rounds, ["rounds.hourly.running", pair.running] as const, ["rounds.hourly.betting", pair.betting] as const];
    const problems: string[] = [];
    for (const [name, round] of all) {
      const market = markets.find((m) => m.id === round.marketId);
      for (const field of ["kind", "priceDecimals", "source"] as const) {
        if (round[field] === undefined || round[field] === null) problems.push(`${name}.${field} is missing`);
        // A round runs entirely on one exchange, chosen when it starts (Р-76): its main one, or — when that had no price — its backup.
        else if (field === "source" && market && (round.source === market.source || (market.backupSource != null && round.source === market.backupSource))) continue;
        else if (market && market[field] !== round[field]) problems.push(`${name}.${field} is ${String(round[field])} but market ${market.id} says ${String(market[field])}`);
      }
    }
    expect(problems).toEqual([]);
  });

  it("a market's backup exchange is another source than its own, and a price's venue is one of the two (Р-76)", () => {
    const markets = wireResponse<{
      markets: Array<{ id: string; source: string; backupSource?: string | null; price: { venue?: string | null } | null }>;
    }>("markets").markets;
    const problems: string[] = [];
    for (const m of markets) {
      if (m.backupSource != null && m.backupSource === m.source) problems.push(`${m.id}: its backup exchange is its own source (${m.source})`);
      const venue = m.price?.venue;
      if (venue != null && venue !== m.source && venue !== m.backupSource) problems.push(`${m.id}: its price is from ${venue}, which is neither ${m.source} nor its backup ${String(m.backupSource)}`);
      if (venue != null && venue !== m.source && m.backupSource == null) problems.push(`${m.id}: a price from another exchange (${venue}) on a market that names no backup`);
    }
    expect(problems).toEqual([]);
  });

  it("a cancelled round's cancellation names the market (the log shows it)", () => {
    const round = wireResponse<{ marketId: string; asset: string; cancellation: { marketId?: string; asset?: string } }>("round.cancelled");
    expect(round.cancellation.marketId).toBe(round.marketId);
    expect(round.cancellation.asset).toBe(round.asset);
    for (const row of wireResponse<{ items: Array<{ roundId: string; marketId?: string; asset?: string }> }>("cancellations").items) {
      expect(row.marketId, `cancellations row ${row.roundId} names no market`).toBeTruthy();
      expect(row.asset, `cancellations row ${row.roundId} names no symbol`).toBeTruthy();
    }
  });

  it("serverInfo says whether the engine is ready", () => {
    expect(typeof wireResponse<{ engineReady?: boolean }>("serverInfo").engineReady).toBe("boolean");
  });

  it("a sign-in says whether IT credited a starter pack — a boolean, never left out (the web tells a player about a pack on it, first tester pass D1)", () => {
    const session = wireResponse<AuthSessionDto>("authVerify");
    expect(typeof session.isNewPlayer).toBe("boolean");
    expect(typeof session.starterPackGranted).toBe("boolean");
    // the first sign-in of a player the server has not seen credited the first pack
    expect(session.isNewPlayer).toBe(true);
    expect(session.starterPackGranted).toBe(true);
  });
});

describe("every price has its market's price scale", () => {
  it("markets(): each market's price", () => {
    const markets = wireResponse<{ markets: Array<{ id: string; priceDecimals: number; price: { price: string } | null }> }>("markets").markets;
    const problems = markets.flatMap((m, i) => marketPriceScaleProblems(m, `markets[${i}](${m.id})`));
    expect(problems).toEqual([]);
  });

  it.each(["round.betting", "round.running", "round.settled", "round.cancelled"] as const)("%s: open, close, entries, liquidations and the crowd's — in the round's own scale", (name) => {
    const markets = wireResponse<{ markets: Array<{ id: string; priceDecimals: number }> }>("markets").markets;
    const round = wireResponse<Record<string, unknown>>(name);
    const decimals = markets.find((m) => m.id === round.marketId)?.priceDecimals;
    expect(roundPriceScaleProblems(round, name, decimals)).toEqual([]);
  });

  it("the room's pair of rounds and the home aggregate", () => {
    const pair = wireResponse<{ running: Record<string, unknown>; betting: Record<string, unknown> }>("rounds.hourly");
    expect([...roundPriceScaleProblems(pair.running, "rounds.hourly.running"), ...roundPriceScaleProblems(pair.betting, "rounds.hourly.betting")]).toEqual([]);
    const home = wireResponse<{ hourly: typeof pair; daily: typeof pair }>("home");
    expect([...roundPriceScaleProblems(home.hourly.running, "home.hourly.running"), ...roundPriceScaleProblems(home.daily.running, "home.daily.running")]).toEqual([]);
  });

  it("priceSeries and myPositions", () => {
    const series = wireResponse<{ marketId: string; priceDecimals: number; ticks: Array<{ price: string }> }>("priceSeries");
    const markets = wireResponse<{ markets: Array<{ id: string; priceDecimals: number }> }>("markets").markets;
    expect(series.priceDecimals).toBe(markets.find((m) => m.id === series.marketId)?.priceDecimals);
    expect(seriesPriceScaleProblems(series, "priceSeries")).toEqual([]);
    const problems: string[] = [];
    for (const [i, p] of wireResponse<Array<{ marketId: string; entryPrice: string | null; liquidationPrice: string | null }>>("myPositions").entries()) {
      const decimals = markets.find((m) => m.id === p.marketId)?.priceDecimals;
      for (const field of ["entryPrice", "liquidationPrice"] as const) {
        const value = p[field];
        if (value !== null && decimals !== undefined && schema.problemsOf(schema.decimal(decimals), value, `myPositions[${i}].${field}`).length > 0) {
          problems.push(`myPositions[${i}].${field} "${value}" is not in market ${p.marketId}'s scale (${decimals})`);
        }
      }
    }
    expect(problems).toEqual([]);
  });

  it("referencePrice and the liquidation preview, in the scale of the market they were asked about", () => {
    const markets = wireResponse<{ markets: Array<{ id: string; priceDecimals: number }> }>("markets").markets;
    const refMarket = wire("referencePrice").args[0] as string;
    const refDecimals = markets.find((m) => m.id === refMarket)?.priceDecimals;
    const reference = wireResponse<{ price: string }>("referencePrice");
    expect(schema.problemsOf(schema.decimal(refDecimals), reference.price, "referencePrice.price")).toEqual([]);
    const request = wire("previewLiquidation").args[0] as { marketId?: string };
    const previewDecimals = markets.find((m) => m.id === (request.marketId ?? "BTC"))?.priceDecimals;
    const preview = wireResponse<{ entryPriceUsed: string; liquidationPrice: string }>("previewLiquidation");
    expect(schema.problemsOf(schema.decimal(previewDecimals), preview.entryPriceUsed, "preview.entryPriceUsed")).toEqual([]);
    expect(schema.problemsOf(schema.decimal(previewDecimals), preview.liquidationPrice, "preview.liquidationPrice")).toEqual([]);
  });
});

describe("errors: the envelope, the status, and the code RealApi turns it into", () => {
  const expectedCode = { "error.notSignedIn": "NOT_SIGNED_IN", "error.marketNotFound": "MARKET_NOT_FOUND", "error.engineNotReady": "ENGINE_NOT_READY" } as const;

  it.each(ERROR_NAMES)("%s: the envelope is {error: {code, message}} and the status is an HTTP error", (name) => {
    const { status, code, message } = wireError(name);
    expect(status).toBeGreaterThanOrEqual(400);
    expect(code).toBe(expectedCode[name]);
    expect(message.length).toBeGreaterThan(0);
    const file = wire(name);
    const envelope = (file.response as { error?: unknown } | null)?.error !== undefined ? file.response : { error: (file as unknown as { error?: unknown }).error };
    const problems = problemsOf(schema.errorEnvelope, envelope);
    expect(problems, problems.join("\n")).toEqual([]);
  });

  it.each(ERROR_NAMES)("%s: RealApi throws an ApiRequestError with that code and the server's own message", async (name) => {
    const file = wire(name);
    const { status, code, message } = wireError(name);
    const body = (file.response as { error?: unknown } | null)?.error !== undefined ? file.response : { error: (file as unknown as { error?: unknown }).error };
    const overrides = { [file.method]: () => ({ status, body }) };
    const fetchStub = wireFetch(overrides);
    vi.stubGlobal("fetch", fetchStub);
    const call = (realApi as unknown as Record<string, (...args: unknown[]) => Promise<unknown>>)[file.method];
    const err = await call.apply(
      realApi,
      // the file's positional args, with the trailing nulls the web would not pass as arguments left out
      trimmed(file.args),
    ).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect((err as ApiRequestError).code).toBe(code);
    expect((err as ApiRequestError).message).toBe(message);
  });
});
