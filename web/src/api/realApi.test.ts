import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiRequestError } from "./contract";
import { realApi } from "./realApi";

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
