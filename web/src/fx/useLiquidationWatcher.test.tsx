/**
 * The mid-round LIQUIDATION moment (Р-50), back: the REAL `useLiquidationWatcher` + `useSettlementWatcher` +
 * `useAwayReplay` + `resultEventBus` + `ResultMomentHost` against a fake `Api` and fake timers — the wiring
 * `Layout.tsx` has — plus the claim store (`liquidationMoment.ts`) on its own.
 *
 * What is promised: the moment fires the first time `myPositions()` reports a RUNNING position liquidated (not at
 * settlement), exactly once however many polls, reloads or tabs; it says the stake is lost and the round is still
 * running; when the round later settles the player is not shown a second flash, stamp and shake — a quiet summary
 * says it ended; nothing fires while signed out.
 */
import { act, render, screen } from "@testing-library/react";
import { MemoryRouter, Outlet, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiInstanceContext, SessionContext, type SessionState } from "../api/ApiContext";
import { fakeApi } from "../api/apiTestUtils";
import type { Api, MyPositionDto, PlayerSummaryDto, ProfileRoundEntryDto, RoundId } from "../api/contract";
import { catalogue, marketsOf } from "../components/testFixtures";
import {
  claimLiquidationMoment,
  liquidationMomentEvent,
  liquidationMomentShown,
  quietSummaryOf,
  resetLiquidationClaimsForTests,
} from "./liquidationMoment";
import { setForcedReducedMotion } from "./reducedMotion";
import { ResultMomentHost } from "./ResultMomentHost";
import type { ResultEvent } from "./resultEvent";
import { subscribe } from "./resultEventBus";
import { useAwayReplay } from "./useAwayReplay";
import { useLiquidationWatcher } from "./useLiquidationWatcher";
import { useSettlementWatcher } from "./useSettlementWatcher";

const PLAYER_ID = "player-1";
const PLAYER: PlayerSummaryDto = { playerId: PLAYER_ID, nickname: "p1", walletAddress: "txch1p1", displayName: "p1" };

function position(over: Partial<MyPositionDto> = {}): MyPositionDto {
  return {
    marketId: "BTC",
    symbol: "BTC",
    roundId: "1h-3600000",
    interval: "HOURLY",
    durationSec: 180,
    phase: "RUNNING",
    direction: "UP",
    leverage: 25,
    stake: "100.000",
    entryPrice: "83451.00",
    liquidationPrice: "80100.00",
    size: "0.02993000",
    liquidated: false,
    untilMs: 5_000_000,
    ...over,
  };
}

function historyEntry(over: Partial<ProfileRoundEntryDto> = {}): ProfileRoundEntryDto {
  return {
    roundId: "1h-3600000",
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    durationSec: 180,
    settledAtMs: 179_999,
    direction: "UP",
    leverage: 25,
    stake: "100.000",
    liquidated: true,
    directionCorrect: false,
    payout: "0.000",
    ...over,
  };
}

/** The fake "server": what `myPositions()` and `unseenResults()` read — shared by every Api made over it, so a
 *  second Api on the same world is "the same player after a reload". */
class World {
  positions: MyPositionDto[] = [];
  unseen: ProfileRoundEntryDto[] = [];
  acked = new Set<RoundId>();
  positionsCalls = 0;
}

function makeApi(world: World, opts: { withUnseen?: boolean } = {}): Api {
  const withUnseen = opts.withUnseen ?? true;
  return fakeApi({
    markets: vi.fn(async () => marketsOf(catalogue())),
    myPositions: vi.fn(async () => {
      world.positionsCalls += 1;
      return world.positions;
    }),
    myAccount: vi.fn(async () => ({ playerId: PLAYER_ID, balance: "9900.000", currentSeason: "2026-10" })),
    ...(withUnseen
      ? {
          unseenResults: vi.fn(async () => world.unseen.filter((e) => !world.acked.has(e.roundId))),
          acknowledgeResults: vi.fn(async (ids: RoundId[]) => {
            ids.forEach((id) => world.acked.add(id));
          }),
        }
      : {}),
  });
}

/** `Layout.tsx`'s watchers and host, around a stub page. */
function WatchersLayout() {
  useSettlementWatcher();
  useLiquidationWatcher();
  useAwayReplay();
  return (
    <>
      <Outlet />
      <ResultMomentHost />
    </>
  );
}

function renderHarness(api: Api, opts: { player?: PlayerSummaryDto | null } = {}) {
  const session: SessionState = {
    player: opts.player === undefined ? PLAYER : opts.player,
    loading: false,
    refresh: async () => {},
    signOut: async () => {},
    openConnectModal: () => {},
  };
  return render(
    <ApiInstanceContext.Provider value={api}>
      <SessionContext.Provider value={session}>
        <MemoryRouter initialEntries={["/wallet"]}>
          <Routes>
            <Route element={<WatchersLayout />}>
              <Route path="/wallet" element={<div>Wallet page stub</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </SessionContext.Provider>
    </ApiInstanceContext.Provider>,
  );
}

async function tick(ms = 2000) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

let unsubscribes: Array<() => void> = [];
function collectEvents(): ResultEvent[] {
  const events: ResultEvent[] = [];
  unsubscribes.push(subscribe((e) => events.push(e)));
  return events;
}

const stampText = () => document.querySelector(".result-card__stamp")?.textContent ?? null;
const titleText = () => document.querySelector(".result-card__title")?.textContent ?? null;
const quietNotes = () => Array.from(document.querySelectorAll(".quiet-note"));

let errorSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.useFakeTimers();
  setForcedReducedMotion(true);
  window.localStorage.clear();
  resetLiquidationClaimsForTests();
  errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => {
  errorSpy.mockRestore();
  vi.useRealTimers();
  setForcedReducedMotion(null);
  unsubscribes.forEach((u) => u());
  unsubscribes = [];
});

describe("the mid-round liquidation moment", () => {
  it("fires the first time myPositions() reports a RUNNING position liquidated — while its round is still running", async () => {
    const world = new World();
    world.positions = [position()];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    await tick();
    expect(events).toHaveLength(0); // running, not liquidated: nothing to say

    world.positions = [position({ liquidated: true })];
    await tick();

    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({
      kind: "LIQUIDATION",
      midRound: true,
      roundId: "1h-3600000",
      marketId: "BTC",
      symbol: "BTC",
      interval: "HOURLY",
      durationSec: 180,
      netPlay: -100,
      stakePlay: 100,
      leverage: 25,
      id: `1h-3600000:${PLAYER_ID}`,
    });
    // On screen at once: the stamp, the signed loss — and what is true right now.
    expect(stampText()).toBe("LIQUIDATED");
    expect(titleText()).toBe("-100 PLAY");
    const note = document.querySelector(".result-card__note");
    expect(note).toHaveTextContent("Your whole stake is lost. The round is still running — if it is cancelled, every bet in it is refunded.");
    expect(screen.getByText(/stake 100 PLAY · x25 · 3 MIN round/)).toBeInTheDocument();
  });

  it("names the round's real length from the position itself — no market list needed, never Hourly", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true, durationSec: 900, interval: "DAILY" })];
    renderHarness(makeApi(world));
    await tick(0);
    expect(screen.getByText(/· 15 MIN round/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Hourly|Daily/);
  });

  it("fires only once however many polls report the same liquidated position", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    for (let i = 0; i < 6; i++) await tick();
    expect(events).toHaveLength(1);
    expect(world.positionsCalls).toBeGreaterThan(3); // it kept polling; it just did not repeat itself
  });

  it("is not fired for a bet still waiting for its round, nor for a running position that is not liquidated", async () => {
    const world = new World();
    world.positions = [
      position({ roundId: "1h-1", phase: "BETTING", entryPrice: null, liquidationPrice: null, size: null }),
      position({ roundId: "1h-2", liquidated: false }),
    ];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    await tick();
    expect(events).toHaveLength(0);
  });

  it("each liquidated round gets its own moment — two in one poll fire two, once each", async () => {
    const world = new World();
    world.positions = [position({ roundId: "1h-3600000", liquidated: true }), position({ roundId: "ETH-1h-3600000", marketId: "ETH", symbol: "ETH", liquidated: true })];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    await tick();
    expect(events.map((e) => e.symbol).sort()).toEqual(["BTC", "ETH"]);
  });
});

describe("once — across reloads and tabs", () => {
  it("a reload does not show it again: the new page finds the claim the old one left", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();

    const first = renderHarness(makeApi(world));
    await tick(0);
    expect(events).toHaveLength(1);
    first.unmount();

    // "Reload": new hook instances, a new Api object (no in-memory state), the same server world and the same storage.
    renderHarness(makeApi(world));
    await tick(0);
    await tick();
    await tick();
    expect(events).toHaveLength(1);
  });

  it("another tab that already told the player keeps this one quiet", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    // what the other tab wrote the moment it fired
    window.localStorage.setItem(`herd.liqMoment.${PLAYER_ID}:1h-3600000`, JSON.stringify({ at: Date.now(), token: "the-other-tab" }));
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    await tick();
    expect(events).toHaveLength(0);
    expect(liquidationMomentShown(PLAYER_ID, "1h-3600000")).toBe(true);
  });

  it("one player's claim is not another's", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    window.localStorage.setItem(`herd.liqMoment.someone-else:1h-3600000`, JSON.stringify({ at: Date.now(), token: "x" }));
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    expect(events).toHaveLength(1);
  });

  it("where storage cannot be used it still never repeats within the page", async () => {
    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage blocked");
    });
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage blocked");
    });
    try {
      const world = new World();
      world.positions = [position({ liquidated: true })];
      const events = collectEvents();
      renderHarness(makeApi(world));
      await tick(0);
      await tick();
      await tick();
      expect(events).toHaveLength(1);
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
    }
  });
});

describe("when the liquidated round later settles", () => {
  it("the player is not flashed, stamped and shaken a second time — a quiet summary says it ended, with the loss signed", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    expect(events).toHaveLength(1);
    expect(stampText()).toBe("LIQUIDATED");

    // The liquidation card goes away on its own (or by tap); then the round ends and settles.
    await tick(3000);
    expect(stampText()).toBeNull();
    world.positions = [];
    world.unseen = [historyEntry()];
    await tick();

    expect(events).toHaveLength(2);
    expect(events[1]).toMatchObject({ kind: "LIQUIDATION", quiet: true, netPlay: -100, roundId: "1h-3600000" });
    expect(events[1].midRound).toBeUndefined();
    // No second overlay…
    expect(stampText()).toBeNull();
    expect(document.querySelector(".result-overlay")).toBeNull();
    // …one calm line, which still says the loss with its sign, and that the balance does not move now.
    const notes = quietNotes();
    expect(notes).toHaveLength(1);
    expect(notes[0]).toHaveTextContent("BTC · 3 MIN round ended");
    expect(notes[0]).toHaveTextContent("Your liquidated bet: -100 PLAY");
    expect(notes[0]).toHaveTextContent(/balance doesn't change now/);
    expect(notes[0].querySelector('a[href="/round/1h-3600000"]')).not.toBeNull();
    // Acknowledged like any settled result, so it is not offered again.
    expect(world.acked.has("1h-3600000")).toBe(true);
    await tick();
    await tick();
    expect(events).toHaveLength(2);
  });

  it("also after a reload between the liquidation and the settlement", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();
    const first = renderHarness(makeApi(world));
    await tick(0);
    first.unmount();

    world.positions = [];
    world.unseen = [historyEntry()];
    renderHarness(makeApi(world));
    await tick(0);

    expect(events.filter((e) => !e.quiet)).toHaveLength(1); // the live one, before the reload
    expect(events.filter((e) => e.quiet)).toHaveLength(1);
    expect(stampText()).toBeNull();
    expect(quietNotes()).toHaveLength(1);
  });

  it("a liquidation the player was never shown live (the page was closed) is still announced in full at settlement", async () => {
    const world = new World();
    world.unseen = [historyEntry()];
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LIQUIDATION", netPlay: -100 });
    expect(events[0].quiet).toBeUndefined();
    expect(stampText()).toBe("LIQUIDATED");
    expect(quietNotes()).toHaveLength(0);
  });

  it("a win or a loss that is not a liquidation is never turned quiet", async () => {
    const world = new World();
    world.unseen = [historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH", liquidated: false, payout: "80.150", directionCorrect: false })];
    // even if some other round's liquidation was announced
    claimLiquidationMoment(PLAYER_ID, "1h-3600000");
    const events = collectEvents();
    renderHarness(makeApi(world));
    await tick(0);
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LOSS" });
    expect(events[0].quiet).toBeUndefined();
  });

  it("a quiet note can be dismissed, and leaves on its own", async () => {
    const world = new World();
    world.unseen = [historyEntry(), historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH" })];
    claimLiquidationMoment(PLAYER_ID, "1h-3600000");
    claimLiquidationMoment(PLAYER_ID, "ETH-1h-3600000");
    renderHarness(makeApi(world));
    await tick(0);
    expect(quietNotes()).toHaveLength(2);

    await act(async () => {
      screen.getAllByRole("button", { name: "Dismiss" })[0].click();
    });
    expect(quietNotes()).toHaveLength(1);
    await tick(8000);
    expect(quietNotes()).toHaveLength(0);
  });
});

describe("what does not fire it", () => {
  it("nothing while signed out — and not even a request for positions", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();
    renderHarness(makeApi(world), { player: null });
    await tick(0);
    await tick();
    await tick();
    expect(world.positionsCalls).toBe(0);
    expect(events).toHaveLength(0);
  });

  it("an Api without unseenResults is the legacy BTC-only server: its own detector fires the moment, this one stays out", async () => {
    const world = new World();
    world.positions = [position({ liquidated: true })];
    const events = collectEvents();
    // The legacy fallback calls home()/myAccount(); give it a home with nothing running that the player is in.
    const api = fakeApi({
      ...(makeApi(world, { withUnseen: false }) as unknown as Partial<Api>),
      home: vi.fn(async () => {
        throw new Error("legacy home not under test");
      }),
    });
    renderHarness(api);
    await tick(0);
    await tick();
    expect(events.filter((e) => e.midRound)).toHaveLength(0);
  });
});

describe("liquidationMoment.ts on its own", () => {
  it("the first claim wins, every later one loses — for a round, per player", () => {
    expect(liquidationMomentShown("p", "r")).toBe(false);
    expect(claimLiquidationMoment("p", "r")).toBe(true);
    expect(claimLiquidationMoment("p", "r")).toBe(false);
    expect(liquidationMomentShown("p", "r")).toBe(true);
    expect(claimLiquidationMoment("p", "r2")).toBe(true);
    expect(claimLiquidationMoment("q", "r")).toBe(true);
  });

  it("claims older than a week are dropped, so the storage does not grow for ever", () => {
    const old = Date.now() - 8 * 24 * 3_600_000;
    window.localStorage.setItem("herd.liqMoment.p:old-round", JSON.stringify({ at: old, token: "t" }));
    window.localStorage.setItem("herd.liqMoment.p:fresh-round", JSON.stringify({ at: Date.now(), token: "t" }));
    window.localStorage.setItem("herd.liqMoment.p:garbage", "not json");
    window.localStorage.setItem("unrelated.key", "keep me");
    resetLiquidationClaimsForTests(); // clears claims — re-seed after, the prune runs on the next claim
    window.localStorage.setItem("herd.liqMoment.p:old-round", JSON.stringify({ at: old, token: "t" }));
    window.localStorage.setItem("herd.liqMoment.p:fresh-round", JSON.stringify({ at: Date.now(), token: "t" }));
    window.localStorage.setItem("herd.liqMoment.p:garbage", "not json");
    window.localStorage.setItem("unrelated.key", "keep me");

    expect(claimLiquidationMoment("p", "new-round")).toBe(true);

    expect(window.localStorage.getItem("herd.liqMoment.p:old-round")).toBeNull();
    expect(window.localStorage.getItem("herd.liqMoment.p:garbage")).toBeNull();
    expect(window.localStorage.getItem("herd.liqMoment.p:fresh-round")).not.toBeNull();
    expect(window.localStorage.getItem("unrelated.key")).toBe("keep me");
  });

  it("the event is the whole stake, negative, with the round's length — and quietSummaryOf turns it into the settlement summary", () => {
    const event = liquidationMomentEvent(position({ stake: "250.000", leverage: 12 }), PLAYER_ID, 1234);
    expect(event).toMatchObject({ kind: "LIQUIDATION", midRound: true, netPlay: -250, stakePlay: 250, tier: 2, durationSec: 180, atMs: 1234 });
    const quiet = quietSummaryOf(event);
    expect(quiet).toMatchObject({ kind: "LIQUIDATION", quiet: true, netPlay: -250, id: event.id });
    expect(quiet.midRound).toBeUndefined();
  });
});
