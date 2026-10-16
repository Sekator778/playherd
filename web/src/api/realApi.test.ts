import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "./contract";
import { resetEngineStateForTests, useEngineState } from "./engineState";
import { realApi } from "./realApi";
import { renderHook } from "@testing-library/react";

/** What `rpc()` sent: `[url, {method, body}]` of the last `fetch` call, with the body decoded. */
function lastCall(fetchMock: ReturnType<typeof vi.fn>): { url: string; init: RequestInit; args: unknown[] } {
  const [url, init] = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as [string, RequestInit];
  return { url, init, args: (JSON.parse(init.body as string) as { args: unknown[] }).args };
}

function okResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, text: async () => (body === undefined ? "" : JSON.stringify(body)) };
}

function errorResponse(status: number, code: string, message: string) {
  return { ok: false, status, text: async () => JSON.stringify({ error: { code, message } }) };
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn().mockResolvedValue(okResponse({}));
  vi.stubGlobal("fetch", fetchMock);
  resetEngineStateForTests();
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("RealApi — one POST /api/rpc/<method> per Api method, positional args, omitted optionals as null", () => {
  it("markets / referencePrice / myPositions / crowdOverview / btcReferencePrice", async () => {
    await realApi.markets();
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/markets", args: [] });
    await realApi.referencePrice("xyz_NVDA");
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/referencePrice", args: ["xyz_NVDA"] });
    await realApi.myPositions();
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/myPositions", args: [] });
    await realApi.crowdOverview();
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/crowdOverview", args: [] });
    await realApi.btcReferencePrice();
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/btcReferencePrice", args: [] });
  });

  it("priceSeries sends the market and sinceMs (null when omitted)", async () => {
    await realApi.priceSeries("ETH");
    expect(lastCall(fetchMock).args).toEqual(["ETH", null]);
    await realApi.priceSeries("ETH", 1_790_000_000_000);
    expect(lastCall(fetchMock).args).toEqual(["ETH", 1_790_000_000_000]);
  });

  it("candles sends the market and the number of minutes (null when omitted)", async () => {
    await realApi.candles("ETH");
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/candles", args: ["ETH", null] });
    await realApi.candles("ETH", 60);
    expect(lastCall(fetchMock).args).toEqual(["ETH", 60]);
  });

  it("rounds and crowdAccount take an optional marketId", async () => {
    await realApi.rounds("HOURLY");
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/rounds", args: ["HOURLY", null] });
    await realApi.rounds("DAILY", "SOL");
    expect(lastCall(fetchMock).args).toEqual(["DAILY", "SOL"]);
    await realApi.crowdAccount();
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/crowdAccount", args: [null] });
    await realApi.crowdAccount("PM540816");
    expect(lastCall(fetchMock).args).toEqual(["PM540816"]);
  });

  it("betFeed (it was missing) sends the round id and the sinceSeq cursor", async () => {
    await realApi.betFeed!("ETH-1h-3600000");
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/betFeed", args: ["ETH-1h-3600000", null] });
    await realApi.betFeed!("ETH-1h-3600000", 42);
    expect(lastCall(fetchMock).args).toEqual(["ETH-1h-3600000", 42]);
  });

  it("previewLiquidation / placeBet carry the whole request object (marketId included)", async () => {
    const preview = { marketId: "SOL", direction: "UP" as const, leverage: 8, stake: "100.000" };
    await realApi.previewLiquidation(preview);
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/previewLiquidation", args: [preview] });
    const bet = { roundId: "SOL-1h-3600000", direction: "DOWN" as const, leverage: 5, stake: "250.000" };
    await realApi.placeBet(bet);
    expect(lastCall(fetchMock)).toMatchObject({ url: "/api/rpc/placeBet", args: [bet] });
  });

  it("posts JSON with the session cookie attached", async () => {
    await realApi.home();
    const { init } = lastCall(fetchMock);
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(init.headers).toEqual({ "Content-Type": "application/json" });
  });

  it("returns the decoded JSON; an empty body is null (currentPlayer with no session)", async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ serverTimeMs: 7, categories: [], markets: [] }));
    expect(await realApi.markets()).toEqual({ serverTimeMs: 7, categories: [], markets: [] });
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, text: async () => "" });
    expect(await realApi.currentPlayer()).toBeNull();
  });

  it("implements every optional method too (serverInfo, unseenResults, acknowledgeResults, betFeed)", () => {
    expect(typeof realApi.serverInfo).toBe("function");
    expect(typeof realApi.unseenResults).toBe("function");
    expect(typeof realApi.acknowledgeResults).toBe("function");
    expect(typeof realApi.betFeed).toBe("function");
  });
});

describe("RealApi — error mapping", () => {
  it.each([
    "NOT_SIGNED_IN",
    "ROUND_NOT_OPEN_FOR_BETTING",
    "STAKE_BELOW_MINIMUM",
    "INSUFFICIENT_BALANCE",
    "INVALID_LEVERAGE",
    "WITHDRAWAL_EXCEEDS_BALANCE",
    "CHALLENGE_EXPIRED_OR_UNKNOWN",
    "SIGNATURE_INVALID",
    "MARKET_NOT_FOUND",
    "MARKET_CLOSED",
    "PRICE_UNAVAILABLE",
    "RATE_LIMITED",
    "ENGINE_NOT_READY",
    "NOT_FOUND",
    "INVALID_NICKNAME",
  ])("a known server code (%s) is an ApiRequestError carrying that code and the server's text", async (code) => {
    fetchMock.mockResolvedValueOnce(errorResponse(400, code, `server says ${code}`));
    const err = await realApi.placeBet({ roundId: "1h-0", direction: "UP", leverage: 2, stake: "100.000" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect(err.code).toBe(code);
    expect(err.message).toBe(`server says ${code}`);
  });

  it("any OTHER server code is ApiRequestError('OTHER') with the server's own message (not a plain Error)", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(400, "BAD_REQUEST", "stake must be a decimal string"));
    const err = await realApi.placeBet({ roundId: "1h-0", direction: "UP", leverage: 2, stake: "x" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect(err.code).toBe("OTHER");
    expect(err.message).toBe("stake must be a decimal string");

    fetchMock.mockResolvedValueOnce(errorResponse(500, "INTERNAL", "boom"));
    const internal = await realApi.markets().catch((e) => e);
    expect(internal).toBeInstanceOf(ApiRequestError);
    expect(internal.code).toBe("OTHER");
    expect(internal.message).toBe("boom");
  });

  it("a server that literally says OTHER is treated the same", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(409, "OTHER", "something specific"));
    const err = await realApi.markets().catch((e) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect(err.code).toBe("OTHER");
  });

  it("transport failures stay a plain Error: fetch rejecting, or an error with no server envelope", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const network = await realApi.markets().catch((e) => e);
    expect(network).toBeInstanceOf(Error);
    expect(network).not.toBeInstanceOf(ApiRequestError);
    expect(network.message).toMatch(/Network error/);

    fetchMock.mockResolvedValueOnce({ ok: false, status: 502, text: async () => "<html>Bad Gateway</html>" });
    const proxy = await realApi.markets().catch((e) => e);
    expect(proxy).toBeInstanceOf(Error);
    expect(proxy).not.toBeInstanceOf(ApiRequestError);
    expect(proxy.message).toBe("Request failed (502).");
  });
});

describe("RealApi — HTTP 429", () => {
  it("with the server's envelope: RATE_LIMITED, the code the UI words in its own plain sentence", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(429, "RATE_LIMITED", "too many requests — please slow down and try again shortly"));
    const err = await realApi.placeBet({ roundId: "1h-0", direction: "UP", leverage: 2, stake: "100.000" }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect(err.code).toBe("RATE_LIMITED");
  });

  it("without any envelope (a proxy's own limit, an HTML page): still RATE_LIMITED — not a plain 'Request failed (429)'", async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 429, text: async () => "<html>Too Many Requests</html>" });
    const err = await realApi.authChallenge({}).catch((e) => e);
    expect(err).toBeInstanceOf(ApiRequestError);
    expect(err.code).toBe("RATE_LIMITED");
    expect(err.message).toBe("Too many requests just now — wait a few seconds and try again.");
  });

  it("other statuses with no envelope stay plain Errors", async () => {
    fetchMock.mockResolvedValueOnce({ ok: false, status: 503, text: async () => "<html>Service Unavailable</html>" });
    const err = await realApi.markets().catch((e) => e);
    expect(err).not.toBeInstanceOf(ApiRequestError);
    expect(err.message).toBe("Request failed (503).");
  });
});

describe("RealApi — submitFeedback (Р-81)", () => {
  const request = { message: "Nice game", timeZone: "Europe/Berlin", language: "de-DE", page: "/play/BTC", client: "web" as const };

  it("POSTs /api/rpc/submitFeedback with the request as the one argument — exactly its five fields", async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ id: 17 }));
    const receipt = await realApi.submitFeedback(request);
    expect(receipt).toEqual({ id: 17 });
    const call = lastCall(fetchMock);
    expect(call).toMatchObject({ url: "/api/rpc/submitFeedback", args: [request] });
    expect(call.init).toMatchObject({ method: "POST", credentials: "include" });
    expect(call.args).toHaveLength(1);
    expect(Object.keys(call.args[0] as object).sort()).toEqual(["client", "language", "message", "page", "timeZone"]);
  });

  it("what the browser could not say goes as JSON null, not left out", async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ id: "a1" }));
    const receipt = await realApi.submitFeedback({ message: "x", timeZone: null, language: null, page: null, client: null });
    expect(receipt).toEqual({ id: "a1" }); // the id is opaque: a number or a string
    const sent = (JSON.parse(lastCall(fetchMock).init.body as string) as { args: Array<Record<string, unknown>> }).args[0];
    expect(sent).toEqual({ message: "x", timeZone: null, language: null, page: null, client: null });
  });

  it("a rate limit is RATE_LIMITED — with the server's envelope, and without one (a proxy's own 429)", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(429, "RATE_LIMITED", "too many requests"));
    const withEnvelope = await realApi.submitFeedback(request).catch((e) => e);
    expect(withEnvelope).toBeInstanceOf(ApiRequestError);
    expect(withEnvelope.code).toBe("RATE_LIMITED");

    fetchMock.mockResolvedValueOnce({ ok: false, status: 429, text: async () => "<html>Too Many Requests</html>" });
    const bare = await realApi.submitFeedback(request).catch((e) => e);
    expect(bare).toBeInstanceOf(ApiRequestError);
    expect(bare.code).toBe("RATE_LIMITED");
  });

  it("any other refusal is an ApiRequestError, a dead network or a proxy's page a plain Error — the form words both as 'couldn't send'", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(400, "BAD_REQUEST", "the message is empty"));
    const refused = await realApi.submitFeedback(request).catch((e) => e);
    expect(refused).toBeInstanceOf(ApiRequestError);
    expect(refused.code).toBe("OTHER");

    fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const offline = await realApi.submitFeedback(request).catch((e) => e);
    expect(offline).toBeInstanceOf(Error);
    expect(offline).not.toBeInstanceOf(ApiRequestError);

    fetchMock.mockResolvedValueOnce({ ok: false, status: 502, text: async () => "<html>Bad Gateway</html>" });
    const proxy = await realApi.submitFeedback(request).catch((e) => e);
    expect(proxy).not.toBeInstanceOf(ApiRequestError);
  });
});

describe("RealApi — what it tells the engine state (api/engineState.ts)", () => {
  const state = () => renderHook(() => useEngineState()).result.current;

  it("a 503 ENGINE_NOT_READY from any call marks the engine not ready", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(503, "ENGINE_NOT_READY", "the game engine is recovering"));
    await realApi.myAccount().catch(() => {});
    expect(state()).toEqual({ notReady: true, epoch: 0 });
  });

  it("a success of a read that NEEDS the engine marks it ready again — and counts the recovery", async () => {
    fetchMock.mockResolvedValueOnce(errorResponse(503, "ENGINE_NOT_READY", "x"));
    await realApi.rounds("HOURLY").catch(() => {});
    fetchMock.mockResolvedValueOnce(okResponse({ serverTimeMs: 1, categories: [], markets: [] }));
    await realApi.markets();
    expect(state()).toEqual({ notReady: false, epoch: 1 });
  });

  it.each([
    ["cancellations", () => realApi.cancellations()],
    ["referencePrice", () => realApi.referencePrice("BTC")],
    ["priceSeries", () => realApi.priceSeries("BTC")],
    ["currentPlayer", () => realApi.currentPlayer()],
    ["round", () => realApi.round("BTC-1h-0")],
    ["submitFeedback", () => realApi.submitFeedback({ message: "x", timeZone: null, language: null, page: null, client: null })],
  ])("a success of %s proves nothing about the engine (the server answers it from the database): the state stays", async (_name, call) => {
    fetchMock.mockResolvedValueOnce(errorResponse(503, "ENGINE_NOT_READY", "x"));
    await realApi.myAccount().catch(() => {});
    fetchMock.mockResolvedValueOnce(okResponse({}));
    await call();
    expect(state().notReady).toBe(true);
  });

  it("serverInfo() saying engineReady: false marks it not ready; true — or no flag at all — marks it ready", async () => {
    fetchMock.mockResolvedValueOnce(okResponse({ mode: "live", engineReady: false }));
    await realApi.serverInfo!();
    expect(state().notReady).toBe(true);
    fetchMock.mockResolvedValueOnce(okResponse({ mode: "live" }));
    await realApi.serverInfo!();
    expect(state()).toEqual({ notReady: false, epoch: 1 });
    fetchMock.mockResolvedValueOnce(okResponse({ mode: "live", engineReady: false }));
    await realApi.serverInfo!();
    fetchMock.mockResolvedValueOnce(okResponse({ mode: "live", engineReady: true }));
    await realApi.serverInfo!();
    expect(state()).toEqual({ notReady: false, epoch: 2 });
  });

  it("an answer that was already on its way when the engine went down does not clear it (no flicker)", async () => {
    vi.useFakeTimers();
    try {
      let answerOld: (r: unknown) => void = () => {};
      fetchMock.mockImplementationOnce(() => new Promise((resolve) => (answerOld = resolve))); // a markets() sent while all was well
      const old = realApi.markets();
      vi.advanceTimersByTime(50);
      fetchMock.mockResolvedValueOnce(errorResponse(503, "ENGINE_NOT_READY", "x")); // then the engine goes down
      await realApi.myAccount().catch(() => {});
      expect(state().notReady).toBe(true);
      vi.advanceTimersByTime(50);
      answerOld(okResponse({ serverTimeMs: 1, categories: [], markets: [] })); // the old answer lands late
      await old;
      expect(state().notReady).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});
