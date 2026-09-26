/**
 * Integration-level regression test for the wave2 bug (Alex, 2026-09-26): the global result
 * moment (Р-50, "видно с любой страницы") fired reliably only sometimes for a real desktop
 * player who stayed on a round page through settlement. Exercises the REAL
 * `useSettlementWatcher` + `resultEventBus` + `ResultMomentHost` wiring — no mocked hook — against
 * a fake `Api` and fake timers, so it runs the exact tick logic the live bug was in.
 *
 * The fake `Api.home()` deliberately reproduces the real backend's confirmed behavior (not the
 * naive "home().running literally becomes SETTLED" shape `useSettlementWatcher.ts`'s own header
 * comment used to assume of the plan): `RoundSchedulerService.resolveOverdueRounds` resolves a
 * round and advances `RoundProgress`'s cursor in the same synchronous step, so `home()`'s
 * `.running` round jumps straight from RUNNING(R) to RUNNING(R+1) — it is never observed as
 * SETTLED. Confirmed live (wave2b evidence,
 * `docs`-external scratchpad `home_transition_watch.log`: one 1s poll shows `running=R RUNNING`,
 * the very next shows `running=R+1 RUNNING`, no SETTLED in between). Only a direct
 * `api.round(R)` call ever reports SETTLED — the fallback path this test drives.
 */
import { act, render } from "@testing-library/react";
import { MemoryRouter, Route, Routes, Outlet } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiInstanceContext, SessionContext, type SessionState } from "../api/ApiContext";
import type {
  Api,
  CrowdAccountDto,
  HomeSummaryDto,
  PlayerAccountDto,
  PlayerSummaryDto,
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
import { useSettlementWatcher } from "./useSettlementWatcher";

const PLAYER_ID = "player-1";
const PLAYER: PlayerSummaryDto = {
  playerId: PLAYER_ID,
  nickname: "p1",
  walletAddress: "txch1p1",
  displayName: "p1",
};
const EMPTY_CROWD: CrowdAccountDto = { capital: "0.00", history: [], seasonReturns: [] };
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
    asset: "BTC",
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
    asset: "BTC",
    phase: "SETTLED",
    openPrice: "83451.00",
    closePrice: "84000.00",
    positions: positions.map((p) => ({ ...p, roundId })),
    crowd: null,
  };
}
function makeBetting(roundId: string): RoundBetting {
  return { roundId, window: HOURLY_WINDOW, asset: "BTC", phase: "BETTING", participantCount: 1, myBet: null };
}
function noStakeDaily(roundId = "1d-0"): RoundsPairDto {
  return {
    interval: "DAILY",
    running: { roundId, window: DAILY_WINDOW, asset: "BTC", phase: "RUNNING", openPrice: "83451.00", positions: [], crowd: null },
    betting: { roundId: "1d-1", window: DAILY_WINDOW, asset: "BTC", phase: "BETTING", participantCount: 0, myBet: null },
  };
}

/** Mutable "server world" the test steers between ticks — `hourlyRunning` is whatever `home()`
 *  should report as `.hourly.running` right now, and `settled` holds every round a direct
 *  `api.round(id)` call should already resolve as SETTLED (mirrors the real backend: a round
 *  lands in `settled` in the SAME step its id stops being `hourlyRunning`, never in between). */
class FakeApi implements Api {
  hourlyRunning: RoundRunning = makeRunning("1h-0", []);
  balance = 10000;
  settled = new Map<RoundId, RoundDto>();
  /** Test hook for the retry-regression case below: `round(roundId)` throws this many more
   *  times (a stand-in for a transient network/server hiccup) before it starts answering
   *  normally again. */
  failNextRoundCalls = new Map<RoundId, number>();

  home(): Promise<HomeSummaryDto> {
    const hourly: RoundsPairDto = { interval: "HOURLY", running: this.hourlyRunning, betting: makeBetting("1h-betting") };
    return Promise.resolve({ hourly, daily: noStakeDaily(), crowd: EMPTY_CROWD, topRich: [], topProphets: [] });
  }
  myAccount(): Promise<PlayerAccountDto> {
    return Promise.resolve({ playerId: PLAYER_ID, balance: this.balance.toFixed(3), currentSeason: "2026-09" });
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
  ratings = () => this.unused();
  publicProfile = () => this.unused();
  myProfile = () => this.unused();
  cancellations = () => this.unused();
}

function TestLayout() {
  useSettlementWatcher();
  return (
    <>
      <Outlet />
      <ResultMomentHost />
    </>
  );
}

function renderHarness(api: Api, initialPath: string) {
  const session: SessionState = {
    player: PLAYER,
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
            <Route element={<TestLayout />}>
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
 *  chain (`Promise.all([home(), myAccount()]).then(async ...)`, plus any awaited `round()` calls
 *  inside it) so assertions see the settled DOM, not a still-pending one. */
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

beforeEach(() => {
  vi.useFakeTimers();
  setForcedReducedMotion(true); // skip canvas/particle/sound paths — only the card's text matters here
});
afterEach(() => {
  vi.useRealTimers();
  setForcedReducedMotion(null);
  activeUnsubscribes.forEach((u) => u());
  activeUnsubscribes = [];
});

describe("useSettlementWatcher — global result moment fires exactly once per settlement, from any page", () => {
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
    api.balance = 10004.96;

    await tick(); // the rollover tick — detects it, chases round("1h-1"), publishes WIN — SETTLED

    // Reaches the screen (the "видно с любой страницы" requirement) — checked right on this same
    // tick, before the card's own auto-dismiss timer (well under 2000ms) has had a chance to run.
    expect(cardTitleText()).toBe("+4.96 PLAY");
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ kind: "WIN", roundId: "1h-1" });
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
    api.balance = 9980.15;
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
    // needed): the primary path, not the fallback.
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
    api.balance = 10004.96;
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
