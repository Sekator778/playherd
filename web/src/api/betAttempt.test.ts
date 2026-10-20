import { beforeEach, describe, expect, it, vi } from "vitest";
import { forgetBetAttempts, newClientRequestId, placeBetWithRetryId } from "./betAttempt";
import { ApiRequestError, type BetDto, type PlaceBetRequest } from "./contract";

const BET: BetDto = { playerId: "p", roundId: "1h-1", direction: "UP", leverage: 5, stake: "300.000" };
const REQ: PlaceBetRequest = { roundId: "1h-1", direction: "UP", leverage: 5, stake: "300.000" };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function apiThatReturns(...outcomes: Array<BetDto | Error>) {
  const placeBet = vi.fn<(req: PlaceBetRequest) => Promise<BetDto>>();
  for (const outcome of outcomes) {
    if (outcome instanceof Error) placeBet.mockRejectedValueOnce(outcome);
    else placeBet.mockResolvedValueOnce(outcome);
  }
  return { placeBet };
}

beforeEach(() => {
  forgetBetAttempts();
});

describe("newClientRequestId", () => {
  it("is a UUID v4, a new one every time", () => {
    const ids = new Set(Array.from({ length: 50 }, () => newClientRequestId()));
    expect(ids.size).toBe(50);
    for (const id of ids) expect(id).toMatch(UUID);
  });

  it("still makes one when crypto.randomUUID is missing", () => {
    vi.stubGlobal("crypto", { getRandomValues: (a: Uint8Array) => a.fill(7) });
    try {
      expect(newClientRequestId()).toMatch(UUID);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("is within what the server accepts", () => {
    expect(newClientRequestId()).toMatch(/^[A-Za-z0-9_-]{1,64}$/);
  });
});

describe("placeBetWithRetryId", () => {
  it("sends an id with the bet and returns the answer", async () => {
    const api = apiThatReturns(BET);

    await expect(placeBetWithRetryId(api, REQ)).resolves.toEqual(BET);

    expect(api.placeBet).toHaveBeenCalledTimes(1);
    expect(api.placeBet.mock.calls[0][0]).toMatchObject({ ...REQ, clientRequestId: expect.stringMatching(UUID) });
  });

  it("does not change the request it was given", async () => {
    const api = apiThatReturns(BET);
    const req = { ...REQ };

    await placeBetWithRetryId(api, req);

    expect(req).toEqual(REQ);
  });

  it("a new press after a bet that was placed gets a new id", async () => {
    const api = apiThatReturns(BET, BET);

    await placeBetWithRetryId(api, REQ);
    await placeBetWithRetryId(api, REQ);

    const [first, second] = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(first).not.toBe(second);
  });

  it("a retry after a network error carries the same id", async () => {
    const api = apiThatReturns(new Error("Network error"), BET);

    await expect(placeBetWithRetryId(api, REQ)).rejects.toThrow("Network error");
    await expect(placeBetWithRetryId(api, REQ)).resolves.toEqual(BET);

    const [first, second] = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(first).toBeDefined();
    expect(second).toBe(first);
  });

  it("keeps the id through several failed tries, and spends it when the bet is finally answered", async () => {
    const api = apiThatReturns(new Error("down"), new Error("down"), BET, BET);

    await expect(placeBetWithRetryId(api, REQ)).rejects.toThrow();
    await expect(placeBetWithRetryId(api, REQ)).rejects.toThrow();
    await placeBetWithRetryId(api, REQ);
    await placeBetWithRetryId(api, REQ);

    const ids = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(ids[1]).toBe(ids[0]);
    expect(ids[2]).toBe(ids[0]);
    expect(ids[3]).not.toBe(ids[0]);
  });

  it("an answer from the server, a refusal included, spends the id", async () => {
    const api = apiThatReturns(new ApiRequestError("INSUFFICIENT_BALANCE", "no"), BET);

    await expect(placeBetWithRetryId(api, REQ)).rejects.toBeInstanceOf(ApiRequestError);
    await placeBetWithRetryId(api, REQ);

    const [first, second] = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(second).not.toBe(first);
  });

  it("another bet is another press: a changed stake, side, leverage, round or mode gets its own id", async () => {
    const api = apiThatReturns(new Error("x"), new Error("x"), new Error("x"), new Error("x"), new Error("x"), BET);
    const variants: PlaceBetRequest[] = [
      REQ,
      { ...REQ, stake: "400.000" },
      { ...REQ, direction: "DOWN" },
      { ...REQ, leverage: 6 },
      { ...REQ, roundId: "1h-2" },
    ];
    for (const v of variants) await expect(placeBetWithRetryId(api, v)).rejects.toThrow();
    await placeBetWithRetryId(api, { ...REQ, mode: "FIXED" });

    const ids = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(new Set(ids).size).toBe(6);
  });

  it("an id is not reused after the server may have forgotten it", async () => {
    const api = apiThatReturns(new Error("x"), BET);
    let clock = 1_000_000;

    await expect(placeBetWithRetryId(api, REQ, () => clock)).rejects.toThrow();
    clock += 10 * 60_000;
    await placeBetWithRetryId(api, REQ, () => clock);

    const [first, second] = api.placeBet.mock.calls.map((c) => c[0].clientRequestId);
    expect(second).not.toBe(first);
  });

  it("leaves an id the caller chose alone", async () => {
    const api = apiThatReturns(BET);

    await placeBetWithRetryId(api, { ...REQ, clientRequestId: "mine-1" });

    expect(api.placeBet.mock.calls[0][0].clientRequestId).toBe("mine-1");
  });
});
