import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fakeApi } from "../api/apiTestUtils";
import type { Api, ProfileRoundEntryDto } from "../api/contract";
import type { ResultEvent } from "./resultEvent";
import { subscribe } from "./resultEventBus";
import { ackResults, eventsFromUnseen, publishUnseenResults, shownResults } from "./unseenResults";

const PLAYER = "player-1";

function entry(overrides: Partial<ProfileRoundEntryDto> = {}): ProfileRoundEntryDto {
  return {
    roundId: "1h-1",
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    settledAtMs: 1000,
    direction: "UP",
    leverage: 10,
    stake: "100.000",
    liquidated: false,
    directionCorrect: true,
    payout: "110.000",
    ...overrides,
  };
}

let published: ResultEvent[] = [];
let unsubscribe: () => void;
let errorSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  published = [];
  unsubscribe = subscribe((e) => published.push(e));
  errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => {
  unsubscribe();
  errorSpy.mockRestore();
});

function apiWith(unseen: ProfileRoundEntryDto[], balance = "10000.000"): { api: Api; ack: ReturnType<typeof vi.fn>; fetchUnseen: ReturnType<typeof vi.fn> } {
  const ack = vi.fn<(roundIds: string[]) => Promise<void>>(async () => {});
  const fetchUnseen = vi.fn(async () => unseen);
  const api = fakeApi({
    unseenResults: fetchUnseen,
    acknowledgeResults: ack,
    myAccount: async () => ({ playerId: PLAYER, balance, currentSeason: "2026-09" }),
  });
  return { api, ack, fetchUnseen };
}

describe("eventsFromUnseen", () => {
  it("returns the events oldest first, with balances walked backwards from the current balance", () => {
    const events = eventsFromUnseen(
      [
        entry({ roundId: "c", payout: "130.000" }), // newest: +30
        entry({ roundId: "b", payout: "80.000", directionCorrect: false }), // -20
        entry({ roundId: "a", payout: "150.000" }), // oldest: +50
      ],
      PLAYER,
      1000,
    );
    expect(events.map((e) => e.roundId)).toEqual(["a", "b", "c"]);
    // c ends at the current 1000 (so began at 970), b ended there (began at 990), a ended there (began at 940)
    expect(events.map((e) => [e.balanceBefore, e.balanceAfter])).toEqual([
      [940, 990],
      [990, 970],
      [970, 1000],
    ]);
  });

  it("a liquidation consumes none of the balance chain (the stake left the balance at placeBet)", () => {
    const events = eventsFromUnseen(
      [entry({ roundId: "win", payout: "120.000" }), entry({ roundId: "liq", liquidated: true, payout: "0.000" })],
      PLAYER,
      500,
    );
    const [liq, win] = events;
    expect(liq.kind).toBe("LIQUIDATION");
    expect(liq.balanceBefore).toBe(liq.balanceAfter);
    expect(win.balanceAfter).toBe(500);
    expect(win.balanceBefore).toBe(480);
    expect(liq.balanceAfter).toBe(480); // the liquidation sits right before the win and sees the same 480
  });

  it("carries each entry's market through", () => {
    const [event] = eventsFromUnseen([entry({ roundId: "xyz_NVDA-1h-0", marketId: "xyz_NVDA", asset: "NVDA" })], PLAYER, 100);
    expect(event).toMatchObject({ marketId: "xyz_NVDA", symbol: "NVDA", id: `xyz_NVDA-1h-0:${PLAYER}` });
  });

  it("is empty for nothing", () => {
    expect(eventsFromUnseen([], PLAYER, 100)).toEqual([]);
  });
});

describe("publishUnseenResults", () => {
  it("publishes each unseen result once and acknowledges them all", async () => {
    const { api, ack } = apiWith([entry({ roundId: "b" }), entry({ roundId: "a" })]);
    await publishUnseenResults(api, PLAYER);
    expect(published.map((e) => e.roundId)).toEqual(["a", "b"]);
    expect(ack).toHaveBeenCalledWith(["b", "a"]);
    expect(shownResults(api).has(`a:${PLAYER}`)).toBe(true);

    // the server still lists them (say the ack was lost): no repeat moment, ack again
    await publishUnseenResults(api, PLAYER);
    expect(published).toHaveLength(2);
    expect(ack).toHaveBeenCalledTimes(2);
  });

  it("two simultaneous calls share one request and one set of moments", async () => {
    const { api, fetchUnseen } = apiWith([entry({ roundId: "a" })]);
    const first = publishUnseenResults(api, PLAYER);
    const second = publishUnseenResults(api, PLAYER);
    expect(second).toBe(first);
    await Promise.all([first, second]);
    expect(fetchUnseen).toHaveBeenCalledTimes(1);
    expect(published).toHaveLength(1);
    // afterwards a new call is a new request
    await publishUnseenResults(api, PLAYER);
    expect(fetchUnseen).toHaveBeenCalledTimes(2);
  });

  it("publishes nothing and acknowledges nothing when the player is no longer the current one", async () => {
    const { api, ack } = apiWith([entry()]);
    await publishUnseenResults(api, PLAYER, () => false);
    expect(published).toEqual([]);
    expect(ack).not.toHaveBeenCalled();
  });

  it("does nothing when the Api has no unseenResults (a server that predates it)", async () => {
    const api = fakeApi({});
    await expect(publishUnseenResults(api, PLAYER)).resolves.toBeUndefined();
    expect(published).toEqual([]);
  });

  it("an empty list is nothing to show and nothing to acknowledge", async () => {
    const { api, ack } = apiWith([]);
    await publishUnseenResults(api, PLAYER);
    expect(published).toEqual([]);
    expect(ack).not.toHaveBeenCalled();
  });

  it("still shows the moment when the balance cannot be read", async () => {
    const api = fakeApi({
      unseenResults: async () => [entry()],
      acknowledgeResults: async () => {},
      myAccount: async () => {
        throw new Error("down");
      },
    });
    await publishUnseenResults(api, PLAYER);
    expect(published).toHaveLength(1);
    expect(published[0].kind).toBe("WIN");
  });

  it("a failing fetch rejects (the caller logs it) and the next call can try again", async () => {
    let fail = true;
    const api = fakeApi({
      unseenResults: async () => {
        if (fail) throw new Error("network");
        return [entry()];
      },
      acknowledgeResults: async () => {},
      myAccount: async () => ({ playerId: PLAYER, balance: "1000.000", currentSeason: "2026-09" }),
    });
    await expect(publishUnseenResults(api, PLAYER)).rejects.toThrow("network");
    fail = false;
    await publishUnseenResults(api, PLAYER);
    expect(published).toHaveLength(1);
  });

  it("each Api instance has its own shown set", async () => {
    const a = apiWith([entry()]).api;
    const b = apiWith([entry()]).api;
    await publishUnseenResults(a, PLAYER);
    await publishUnseenResults(b, PLAYER);
    expect(published).toHaveLength(2); // a reloaded page (a new Api) shows what the server still lists
  });
});

describe("ackResults", () => {
  it("logs a failed acknowledge instead of throwing, and skips an empty list", async () => {
    const acknowledgeResults = vi.fn(async () => {
      throw new Error("down");
    });
    const api = fakeApi({ acknowledgeResults });
    ackResults(api, []);
    expect(acknowledgeResults).not.toHaveBeenCalled();
    ackResults(api, ["a"]);
    await Promise.resolve();
    await Promise.resolve();
    expect(acknowledgeResults).toHaveBeenCalledWith(["a"]);
    expect(errorSpy).toHaveBeenCalled();
  });

  it("is a no-op for an Api without acknowledgeResults", () => {
    expect(() => ackResults(fakeApi({}), ["a"])).not.toThrow();
  });
});
