/**
 * Integration-level tests of the global result moment (D-50, "видно с любой страницы"): the REAL
 * `useSettlementWatcher` (+ `useAwayReplay`) + `resultEventBus` + `ResultMomentHost` wiring — no
 * mocked hook — against a fake `Api` and fake timers.
 *
 * Two paths, both covered:
 *
 * 1. The market-agnostic one (D-69): an `Api` that has `unseenResults()`/`acknowledgeResults()` is
 *    the single source — a moment fires for every newly settled round of the player in ANY market,
 *    exactly once, acked so it never repeats on a reload.
 * 2. The legacy fallback for an `Api` without them: the `home()`-based BTC detector, including the
 *    wave2 regression (Alex, 2026-09-26): the moment fired only sometimes for a desktop player who
 *    stayed on a round page through settlement. The fake's `home()` there deliberately reproduces
 *    the real backend's confirmed behavior: `RoundSchedulerService.resolveOverdueRounds` resolves a
 *    round and advances `RoundProgress`'s cursor in the same synchronous step, so `home()`'s
 *    `.running` round jumps straight from RUNNING(R) to RUNNING(R+1) — it is never observed as
 *    SETTLED. Only a direct `api.round(R)` call ever reports SETTLED.
 */
import { act, render } from "@testing-library/react";
import { MemoryRouter, Route, Routes, Outlet } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiInstanceContext, SessionContext, type SessionState } from "../api/ApiContext";
import { SIM_SPEED, simNowMs } from "../api/clock";
import { mockApi } from "../api/mockApi";
import type {
  Api,
  CrowdAccountDto,
  HomeSummaryDto,
  PlayerAccountDto,
  PlayerSummaryDto,
  ProfileRoundEntryDto,
  PublicPositionDto,
  RoundBetting,
  RoundDto,
  RoundId,
  RoundRunning,
  RoundSettled,
  RoundsPairDto,
} from "../api/contract";
import { setForcedReducedMotion } from "./reducedMotion";
import { ResultMomentHost } from "./ResultMomentHost";
import type { ResultEvent } from "./resultEvent";
import { subscribe } from "./resultEventBus";
import { useAwayReplay } from "./useAwayReplay";
import { useSettlementWatcher } from "./useSettlementWatcher";

const PLAYER_ID = "player-1";
const PLAYER: PlayerSummaryDto = {
  playerId: PLAYER_ID,
  nickname: "p1",
  walletAddress: "txch1p1",
  displayName: "p1",
};
const EMPTY_CROWD: CrowdAccountDto = { marketId: "BTC", symbol: "BTC", capital: "0.00", history: [], seasonReturns: [] };
const HOURLY_WINDOW = { interval: "HOURLY" as const, startMs: 0, endMs: 180_000 };
const DAILY_WINDOW = { interval: "DAILY" as const, startMs: 0, endMs: 900_000 };

function makePosition(overrides: Partial<PublicPositionDto> = {}): PublicPositionDto {
  return {
    playerId: PLAYER_ID,
    roundId: "1h-0",
    direction: "UP",
    leverage: 10,
    size: "0.01000000",
    entryPrice: "83451.00",
    liquidationPrice: "79528.10",
    pnl: null,
    stake: "100.000",
    player: PLAYER,
    liquidated: false,
    payout: null,
    directionCorrect: null,
    ...overrides,
  };
}
function makeRunning(roundId: string, positions: PublicPositionDto[]): RoundRunning {
  return {
    roundId,
    window: HOURLY_WINDOW,
    marketId: "BTC",
    asset: "BTC",
    kind: "PERP",
    priceDecimals: 2,
    source: "hyperliquid",
    phase: "RUNNING",
    openPrice: "83451.00",
    positions: positions.map((p) => ({ ...p, roundId })),
    crowd: null,
  };
}
function makeSettled(roundId: string, positions: PublicPositionDto[]): RoundSettled {
  return {
    roundId,
    window: HOURLY_WINDOW,
    marketId: "BTC",
    asset: "BTC",
    kind: "PERP",
    priceDecimals: 2,
    source: "hyperliquid",
    phase: "SETTLED",
    openPrice: "83451.00",
    closePrice: "84000.00",
    positions: positions.map((p) => ({ ...p, roundId })),
    crowd: null,
  };
}
function makeBetting(roundId: string): RoundBetting {
  return { roundId, window: HOURLY_WINDOW, marketId: "BTC", asset: "BTC", kind: "PERP", priceDecimals: 2, source: "hyperliquid", phase: "BETTING", participantCount: 1, myBet: null };
}
function noStakeDaily(roundId = "1d-0"): RoundsPairDto {
  return {
    interval: "DAILY",
    running: { roundId, window: DAILY_WINDOW, marketId: "BTC", asset: "BTC", kind: "PERP", priceDecimals: 2, source: "hyperliquid", phase: "RUNNING", openPrice: "83451.00", positions: [], crowd: null },
    betting: { roundId: "1d-1", window: DAILY_WINDOW, marketId: "BTC", asset: "BTC", kind: "PERP", priceDecimals: 2, source: "hyperliquid", phase: "BETTING", participantCount: 0, myBet: null },
  };
}

/** One settled position as `unseenResults()` reports it. */
function historyEntry(overrides: Partial<ProfileRoundEntryDto> = {}): ProfileRoundEntryDto {
  return {
    roundId: "1h-1",
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    settledAtMs: 179_999,
    direction: "UP",
    leverage: 10,
    stake: "100.000",
    liquidated: false,
    directionCorrect: true,
    payout: "104.960",
    ...overrides,
  };
}

/** The fake "server": what both `unseenResults()` and the legacy `home()` read, shared by every
 *  `FakeApi` made over it — a second `FakeApi` on the same world is "the same player after a
 *  reload": nothing in memory survives, the server's acks do. */
class World {
  balance = 10000;
  /** Newest first, like the real `unseenResults()`; an acked round is no longer listed. */
  unseen: ProfileRoundEntryDto[] = [];
  acked = new Set<RoundId>();
  ackCalls: RoundId[][] = [];
  ackShouldFail = false;
  unseenCalls = 0;
}

/** Mutable "server world" the test steers between ticks. With `unseen: true` the Api has
 *  `unseenResults`/`acknowledgeResults` (the market-agnostic path); without, it is the old server
 *  the fallback is for: `hourlyRunning` is whatever `home()` should report as `.hourly.running`
 *  right now, and `settled` holds every round a direct `api.round(id)` call should already resolve
 *  as SETTLED (a round lands in `settled` in the SAME step its id stops being `hourlyRunning`). */
class FakeApi implements Api {
  hourlyRunning: RoundRunning = makeRunning("1h-0", []);
  settled = new Map<RoundId, RoundDto>();
  homeCalls = 0;
  /** Test hook for the retry-regression case below: `round(roundId)` throws this many more
   *  times (a stand-in for a transient network/server hiccup) before it starts answering
   *  normally again. */
  failNextRoundCalls = new Map<RoundId, number>();

  unseenResults?: () => Promise<ProfileRoundEntryDto[]>;
  acknowledgeResults?: (roundIds: RoundId[]) => Promise<void>;

  constructor(
    readonly world: World = new World(),
    opts: { unseen?: boolean } = {},
  ) {
    if (opts.unseen) {
      this.unseenResults = () => {
        this.world.unseenCalls += 1;
        return Promise.resolve(this.world.unseen.filter((e) => !this.world.acked.has(e.roundId)));
      };
      this.acknowledgeResults = (roundIds) => {
        this.world.ackCalls.push(roundIds);
        if (this.world.ackShouldFail) return Promise.reject(new Error("simulated ack failure"));
        roundIds.forEach((id) => this.world.acked.add(id));
        return Promise.resolve();
      };
    }
  }

  home(): Promise<HomeSummaryDto> {
    this.homeCalls += 1;
    const hourly: RoundsPairDto = { interval: "HOURLY", running: this.hourlyRunning, betting: makeBetting("1h-betting") };
    return Promise.resolve({ hourly, daily: noStakeDaily(), crowd: EMPTY_CROWD, topRich: [], topProphets: [] });
  }
  myAccount(): Promise<PlayerAccountDto> {
    return Promise.resolve({ playerId: PLAYER_ID, balance: this.world.balance.toFixed(3), currentSeason: "2026-09" });
  }
  round(roundId: RoundId): Promise<RoundDto> {
    const remaining = this.failNextRoundCalls.get(roundId) ?? 0;
    if (remaining > 0) {
      this.failNextRoundCalls.set(roundId, remaining - 1);
      return Promise.reject(new Error("simulated transient network/server error"));
    }
    const s = this.settled.get(roundId);
    if (s) return Promise.resolve(s);
    if (this.hourlyRunning.roundId === roundId) return Promise.resolve(this.hourlyRunning);
    return Promise.reject(new Error(`round ${roundId} not found`));
  }

  // Nothing else is reached by this test's harness pages.
  private unused(): never {
    throw new Error("not implemented in this fake");
  }
  authChallenge = () => this.unused();
  authVerify = () => this.unused();
  currentPlayer = () => this.unused();
  setNickname = () => this.unused();
  signOut = () => this.unused();
  myInvite = () => this.unused();
  myBots = () => this.unused();
  createBot = () => this.unused();
  regenerateBotKey = () => this.unused();
  revokeBotKey = () => this.unused();
  botRatings = () => this.unused();
  botWeeklyRatings = () => this.unused();
  weeklyRatings = () => this.unused();
  markets = () => this.unused();
  priceSeries = () => this.unused();
  candles = () => this.unused();
  referencePrice = () => this.unused();
  myPositions = () => this.unused();
  rounds = () => this.unused();
  btcReferencePrice = () => this.unused();
  previewLiquidation = () => this.unused();
  placeBet = () => this.unused();
  cancelBet = () => this.unused();
  mySeasonRecord = () => this.unused();
  myLedger = () => this.unused();
  requestWithdrawal = () => this.unused();
  withdrawal = () => this.unused();
  myDepositAddress = () => this.unused();
  crowdAccount = () => this.unused();
  crowdOverview = () => this.unused();
  ratings = () => this.unused();
  publicProfile = () => this.unused();
  myProfile = () => this.unused();
  cancellations = () => this.unused();
  submitFeedback = () => this.unused();
}

function Host() {
  return (
    <>
      <Outlet />
      <ResultMomentHost />
    </>
  );
}

function WatcherLayout() {
  useSettlementWatcher();
  return <Host />;
}

/** Mirrors `Layout.tsx`: the watcher and the away replay both live there. */
function WatcherAndAwayReplayLayout() {
  useSettlementWatcher();
  useAwayReplay();
  return <Host />;
}

function renderHarness(
  api: Api,
  initialPath: string,
  opts: { player?: PlayerSummaryDto | null; awayReplay?: boolean } = {},
) {
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
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route element={opts.awayReplay ? <WatcherAndAwayReplayLayout /> : <WatcherLayout />}>
              <Route path="/round/:roundId" element={<div>Round page stub</div>} />
              <Route path="/wallet" element={<div>Wallet page stub</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </SessionContext.Provider>
    </ApiInstanceContext.Provider>,
  );
}

/** Advances the watcher's own 2000ms poll by exactly one tick and flushes the resulting promise
 *  chain (the poll's requests, plus any awaited `round()` calls inside it) so assertions see the
 *  settled DOM, not a still-pending one. */
async function tick(ms = 2000) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

/** All published `resultEventBus` events for the test's lifetime — the "exactly once" dedup
 *  assertion is checked against this, not DOM presence, because the card's own auto-dismiss timer
 *  (`ResultCard.tsx`) legitimately removes it from the DOM well before a settlement's later
 *  ticks/rollovers are done firing in these tests, and that removal is correct product behavior,
 *  not the bug under test. */
let activeUnsubscribes: Array<() => void> = [];
function collectPublishedEvents(): ResultEvent[] {
  const events: ResultEvent[] = [];
  activeUnsubscribes.push(subscribe((e) => events.push(e)));
  return events;
}

function cardTitleText(): string | null {
  return document.querySelector(".result-card__title")?.textContent ?? null;
}
function cardStampText(): string | null {
  return document.querySelector(".result-card__stamp")?.textContent ?? null;
}

let errorSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.useFakeTimers();
  setForcedReducedMotion(true); // skip canvas/particle/sound paths — only the card's text matters here
  errorSpy = vi.spyOn(console, "error").mockImplementation(() => {}); // the watcher logs failed polls/acks on purpose
});
afterEach(() => {
  errorSpy.mockRestore();
  vi.useRealTimers();
  setForcedReducedMotion(null);
  activeUnsubscribes.forEach((u) => u());
  activeUnsubscribes = [];
});

describe("useSettlementWatcher — unseenResults is the single source: any market, exactly once", () => {
  it.each([
    ["the RoundPage itself", "/round/ETH-1h-3600000"],
    ["a completely different page (Wallet)", "/wallet"],
  ])("fires the WIN moment for a newly settled ETH round, once, when mounted on %s", async (_label, path) => {
    const api = new FakeApi(new World(), { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, path);

    await tick(0); // initial immediate poll on mount
    await tick();
    expect(events).toHaveLength(0);
    expect(cardTitleText()).toBeNull();

    // The ETH round settles server-side (net = 104.960 - 100.000 = +4.96, a WIN): it shows up in
    // the player's unseen results; the balance already includes the payout.
    api.world.unseen = [historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH" })];
    api.world.balance = 10004.96;
    await tick();

    // Reaches the screen (the "видно с любой страницы" requirement) — checked right on this same
    // tick, before the card's own auto-dismiss timer has had a chance to run.
    expect(cardTitleText()).toBe("+4.96 PLAY");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "WIN", roundId: "ETH-1h-3600000", marketId: "ETH", symbol: "ETH", interval: "HOURLY" });
    expect(events[0].netPlay).toBeCloseTo(4.96, 5);
    expect(events[0].balanceAfter).toBeCloseTo(10004.96, 5);
    expect(events[0].balanceBefore).toBeCloseTo(10000, 5);
    expect(api.world.ackCalls).toEqual([["ETH-1h-3600000"]]);

    // Dedup: the server stopped listing the acked round, and further ticks publish nothing.
    await tick();
    await tick();
    await tick();
    expect(events).toHaveLength(1);
  });

  it("fires the LOSS moment for a settled loss", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet");
    await tick(0);

    api.world.unseen = [historyEntry({ roundId: "SOL-1h-3600000", marketId: "SOL", asset: "SOL", payout: "80.150", directionCorrect: false })];
    api.world.balance = 9980.15;
    await tick();

    expect(cardTitleText()).toBe("-19.85 PLAY");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LOSS", roundId: "SOL-1h-3600000", symbol: "SOL" });
    expect(events[0].netPlay).toBeCloseTo(-19.85, 5);
  });

  it("fires LIQUIDATION for a liquidated position when its round settles (payout 0, the whole stake is the loss)", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet");
    await tick(0);

    api.world.unseen = [
      historyEntry({ roundId: "xyz_NVDA-1d-0", interval: "DAILY", marketId: "xyz_NVDA", asset: "NVDA", leverage: 20, liquidated: true, payout: "0.000" }),
    ];
    await tick();

    expect(cardStampText()).toBe("LIQUIDATED");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LIQUIDATION", roundId: "xyz_NVDA-1d-0", symbol: "NVDA", interval: "DAILY", netPlay: -100 });
    // A liquidation never moves the balance at settlement: the stake left it at placeBet.
    expect(events[0].balanceBefore).toBe(events[0].balanceAfter);
  });

  it("several markets settling in one poll fire one moment each, oldest first, with balances chained back from the current one", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet");
    await tick(0);

    // newest first, like the server: SOL +10, ETH -20, BTC +30
    api.world.unseen = [
      historyEntry({ roundId: "SOL-1h-7200000", marketId: "SOL", asset: "SOL", payout: "110.000" }),
      historyEntry({ roundId: "ETH-1h-7200000", marketId: "ETH", asset: "ETH", payout: "80.000", directionCorrect: false }),
      historyEntry({ roundId: "1h-7200000", marketId: "BTC", asset: "BTC", payout: "130.000" }),
    ];
    api.world.balance = 10000;
    await tick();

    expect(events.map((e) => e.symbol)).toEqual(["BTC", "ETH", "SOL"]);
    expect(events.map((e) => e.kind)).toEqual(["WIN", "LOSS", "WIN"]);
    expect(events.map((e) => [e.balanceBefore, e.balanceAfter])).toEqual([
      [9980, 10010],
      [10010, 9990],
      [9990, 10000],
    ]);
    expect(api.world.ackCalls).toEqual([["SOL-1h-7200000", "ETH-1h-7200000", "1h-7200000"]]);
    expect(api.homeCalls).toBe(0); // never reads a single pair
  });

  it("a failed acknowledge never fires the moment again this session, and is retried until it lands", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    api.world.ackShouldFail = true;
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet");
    await tick(0);

    api.world.unseen = [historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH" })];
    await tick();
    expect(events).toHaveLength(1);
    expect(api.world.ackCalls).toHaveLength(1);

    await tick(); // the server still lists it (the ack failed): no second moment, ack retried
    expect(events).toHaveLength(1);
    expect(api.world.ackCalls).toHaveLength(2);

    api.world.ackShouldFail = false;
    await tick(); // lands
    expect(api.world.acked.has("ETH-1h-3600000")).toBe(true);
    await tick();
    await tick();
    expect(events).toHaveLength(1);
  });

  it("never fires the same round twice across a reload — the server's ack is what carries over", async () => {
    const world = new World();
    world.unseen = [historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH" })];
    const events = collectPublishedEvents();

    const first = renderHarness(new FakeApi(world, { unseen: true }), "/wallet");
    await tick(0); // catch-up poll at mount
    expect(events).toHaveLength(1);
    first.unmount();

    // "Reload": new hook instances, a new Api object (no in-memory dedupe), same server world.
    renderHarness(new FakeApi(world, { unseen: true }), "/wallet");
    await tick(0);
    await tick();
    expect(events).toHaveLength(1);
  });

  it("catches up on what settled while away at mount, once, even with the away replay mounted next to it", async () => {
    const world = new World();
    world.unseen = [
      historyEntry({ roundId: "ETH-1h-7200000", marketId: "ETH", asset: "ETH", payout: "120.000" }),
      historyEntry({ roundId: "1h-3600000", marketId: "BTC", asset: "BTC", payout: "90.000", directionCorrect: false }),
    ];
    world.balance = 10010;
    const api = new FakeApi(world, { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet", { awayReplay: true });

    await tick(0);
    expect(events.map((e) => e.roundId)).toEqual(["1h-3600000", "ETH-1h-7200000"]); // oldest first
    expect(world.unseenCalls).toBe(1); // the two hooks shared one call
    expect(world.ackCalls).toEqual([["ETH-1h-7200000", "1h-3600000"]]);

    await tick();
    await tick();
    expect(events).toHaveLength(2);
  });

  it("does nothing while signed out", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    api.world.unseen = [historyEntry()];
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet", { player: null });
    await tick(0);
    await tick();
    expect(api.world.unseenCalls).toBe(0);
    expect(events).toHaveLength(0);
  });

  it("a failed poll costs one tick, not the moment", async () => {
    const api = new FakeApi(new World(), { unseen: true });
    const events = collectPublishedEvents();
    renderHarness(api, "/wallet");
    await tick(0);

    api.world.unseen = [historyEntry({ roundId: "ETH-1h-3600000", marketId: "ETH", asset: "ETH" })];
    const realUnseen = api.unseenResults!;
    api.unseenResults = () => Promise.reject(new Error("simulated network error"));
    await tick();
    expect(events).toHaveLength(0);

    api.unseenResults = realUnseen;
    await tick();
    expect(events).toHaveLength(1);
  });
});

describe("useSettlementWatcher against the real mock Api — an end-to-end check of the market-agnostic path", () => {
  it("a bet on ETH and one on a politics ODDS market each fire their own moment when their rounds settle, once", async () => {
    const challenge = await mockApi.authChallenge({});
    await mockApi.authVerify({
      nonce: challenge.nonce,
      address: "txch1watchertestaddress000000000000000000000000000000000000q",
      publicKey: "pk",
      signature: "sig",
    });
    await mockApi.setNickname("watcherTester");
    const player = (await mockApi.currentPlayer())!;
    const eth = (await mockApi.rounds("HOURLY", "ETH")).betting;
    const odds = (await mockApi.rounds("HOURLY", "PM900101")).betting;
    await mockApi.placeBet({ roundId: eth.roundId, direction: "UP", leverage: 10, stake: "500.000" });
    await mockApi.placeBet({ roundId: odds.roundId, direction: "DOWN", leverage: 5, stake: "300.000" });

    const events = collectPublishedEvents();
    renderHarness(mockApi, "/wallet", { player, awayReplay: true });
    await tick(0);
    await tick();
    expect(events).toHaveLength(0); // both rounds are still ahead

    // play the simulated clock past the end of that betting round's window (the round runs a whole sim hour after it)
    const untilSettled = (eth.window.endMs + 60_000 - simNowMs()) / SIM_SPEED;
    await tick(Math.ceil(untilSettled));
    await tick();

    expect(events.map((e) => e.symbol).sort()).toEqual(["ETH", "RUNOFF"]);
    expect(events.map((e) => e.marketId).sort()).toEqual(["ETH", "PM900101"]);
    expect(events.every((e) => e.interval === "HOURLY")).toBe(true);
    // what the moment says is what the ledger did
    const entries = (await mockApi.myProfile()).history;
    for (const event of events) {
      const entry = entries.find((h) => h.roundId === event.roundId)!;
      expect(event.netPlay).toBeCloseTo(Number(entry.payout) - Number(entry.stake), 6);
      expect(event.kind === "LIQUIDATION").toBe(entry.liquidated);
    }
    // acknowledged server-side, so nothing is left to replay on the next open
    expect(await mockApi.unseenResults!()).toEqual([]);
    await tick();
    await tick();
    expect(events).toHaveLength(2);
  });
});

describe("useSettlementWatcher — legacy fallback (an Api without unseenResults): home()-based BTC detector", () => {
  it.each([
    ["the RoundPage itself", "/round/1h-1"],
    ["a completely different page (Wallet)", "/wallet"],
  ])("fires the WIN moment exactly once when mounted on %s", async (_label, path) => {
    const api = new FakeApi();
    api.hourlyRunning = makeRunning("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000" })]);
    const events = collectPublishedEvents();
    renderHarness(api, path);

    await tick(0); // initial immediate poll on mount
    expect(cardTitleText()).toBeNull();

    // Several ticks while the round is still running — no event yet (BETTING already happened
    // before this hook ever sees the round; RUNNING is what it observes here).
    await tick();
    await tick();
    expect(events).toHaveLength(0);

    // The round settles server-side (net = 104.960 - 100.000 = +4.96, a WIN) and — per the real
    // backend's confirmed behavior — `.running` rolls straight to the NEXT round in the same
    // step; `round("1h-1")` already reports SETTLED by the time that rollover is observable.
    api.settled.set(
      "1h-1",
      makeSettled("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000", payout: "104.960", directionCorrect: true })]),
    );
    api.hourlyRunning = makeRunning("1h-2", []); // a new round the player has no stake in
    api.world.balance = 10004.96;

    await tick(); // the rollover tick — detects it, chases round("1h-1"), publishes WIN — SETTLED

    // Reaches the screen (the "видно с любой страницы" requirement) — checked right on this same
    // tick, before the card's own auto-dismiss timer (well under 2000ms) has had a chance to run.
    expect(cardTitleText()).toBe("+4.96 PLAY");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "WIN", roundId: "1h-1", marketId: "BTC", symbol: "BTC" });
    expect(events[0].netPlay).toBeCloseTo(4.96, 5);

    // Dedup: further ticks — including the card's own auto-dismiss and more rollovers — never
    // publish a second event for the same round. This is the "keeps one session across BETTING ->
    // RUNNING -> SETTLED" guarantee: one long-lived hook instance, not a fresh one per page.
    await tick();
    await tick();
    api.hourlyRunning = makeRunning("1h-3", []);
    await tick();
    await tick();
    expect(events).toHaveLength(1);
  });

  it("fires the LOSS moment exactly once for a settled loss", async () => {
    const api = new FakeApi();
    api.hourlyRunning = makeRunning("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000" })]);
    const events = collectPublishedEvents();
    renderHarness(api, "/round/1h-1");
    await tick(0);

    api.settled.set(
      "1h-1",
      makeSettled("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000", payout: "80.150", directionCorrect: false })]),
    );
    api.hourlyRunning = makeRunning("1h-2", []);
    api.world.balance = 9980.15;
    await tick();

    expect(cardTitleText()).toBe("-19.85 PLAY");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LOSS", roundId: "1h-1" });
    expect(events[0].netPlay).toBeCloseTo(-19.85, 5);

    await tick();
    await tick();
    expect(events).toHaveLength(1);
  });

  it("fires LIQUIDATION the instant it happens mid-round, and never fires a second moment when that same round later settles", async () => {
    const api = new FakeApi();
    api.hourlyRunning = makeRunning("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000", leverage: 25, liquidated: false })]);
    const events = collectPublishedEvents();
    renderHarness(api, "/round/1h-1");
    await tick(0);
    expect(events).toHaveLength(0);

    // Mid-round liquidation — observable directly via `.running`'s own positions (no rollover
    // needed): the primary path, not the follow-up.
    api.hourlyRunning = makeRunning("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000", leverage: 25, liquidated: true })]);
    await tick();
    expect(cardStampText()).toBe("LIQUIDATED");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "LIQUIDATION", roundId: "1h-1", netPlay: -100 });

    // The round eventually rolls over and settles (a liquidated position still gets a SETTLED
    // record, payout 0) — must NOT produce a second moment for the same (roundId, playerId).
    api.settled.set(
      "1h-1",
      makeSettled("1h-1", [
        makePosition({ roundId: "1h-1", stake: "100.000", leverage: 25, liquidated: true, payout: "0.000" }),
      ]),
    );
    api.hourlyRunning = makeRunning("1h-2", []);
    await tick();
    await tick();

    expect(events).toHaveLength(1);
  });

  it("retries a settlement chase after a transient api.round() failure instead of losing it (the wave2 bug)", async () => {
    const api = new FakeApi();
    api.hourlyRunning = makeRunning("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000" })]);
    const events = collectPublishedEvents();
    renderHarness(api, "/round/1h-1");
    await tick(0);

    api.settled.set(
      "1h-1",
      makeSettled("1h-1", [makePosition({ roundId: "1h-1", stake: "100.000", payout: "104.960", directionCorrect: true })]),
    );
    api.hourlyRunning = makeRunning("1h-2", []);
    api.world.balance = 10004.96;
    // Simulate one bad request on the very first chase attempt — the pre-fix code deleted the
    // pending round right here and never looked at it again, so the moment simply never fired.
    api.failNextRoundCalls.set("1h-1", 1);

    await tick(); // rollover tick — chase attempt #1 fails
    expect(events).toHaveLength(0);

    await tick(); // chase attempt #2 — succeeds
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "WIN", roundId: "1h-1" });
  });
});
