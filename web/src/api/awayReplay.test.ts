/**
 * `MockApi.unseenResults()`/`acknowledgeResults()` — the server-facing half of "while you were
 * away" (`fx/useAwayReplay.ts`). Drives the real `mockApi` singleton (same pattern as
 * `components/BetSlip.test.tsx`) through a full BETTING → RUNNING → SETTLED cycle using fake
 * timers on the simulated clock (`api/clock.ts`'s `simNowMs` reads `performance.now()`, which
 * Vitest's fake timers control), rather than a hand-rolled fake `Api` — this is exactly the real
 * settlement pipeline (`MockApi.processSettlements`) `fx/useAwayReplay.ts` depends on.
 */
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { mockApi } from "./mockApi";
import { HOUR_MS } from "./clock";
import { MIN_STAKE_PLAY } from "./contract";

const ADDRESS = "txch1awayreplaytestaddress000000000000000000000000000000q";

beforeAll(async () => {
  const challenge = await mockApi.authChallenge({});
  const session = await mockApi.authVerify({ nonce: challenge.nonce, address: ADDRESS, publicKey: "pk", signature: "sig" });
  expect(session.isNewPlayer).toBe(true);
  await mockApi.setNickname("awayReplayTester");
});

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("unseenResults/acknowledgeResults (MockApi, 'while you were away')", () => {
  it("returns nothing before any round has settled", async () => {
    expect(await mockApi.unseenResults!()).toEqual([]);
  });

  it("surfaces a settled bet as unseen, then stops after it's acknowledged", async () => {
    const pair = await mockApi.rounds("HOURLY");
    if (pair.betting.phase !== "BETTING") throw new Error("expected the next hourly round to be open for bets");
    const roundId = pair.betting.roundId;
    await mockApi.placeBet({ roundId, direction: "UP", leverage: 5, stake: MIN_STAKE_PLAY });

    // Fast-forward the SIMULATED clock (not wall-clock) well past this round's own settlement —
    // `simNowMs()` is `performance.now() * SIM_SPEED` (60x), and fake timers control
    // `performance.now()`, so this advances instantly, not over real seconds.
    await vi.advanceTimersByTimeAsync((2 * HOUR_MS) / 60 + 1000);

    const unseen = await mockApi.unseenResults!();
    expect(unseen.some((e) => e.roundId === roundId)).toBe(true);

    await mockApi.acknowledgeResults!([roundId]);
    const afterAck = await mockApi.unseenResults!();
    expect(afterAck.some((e) => e.roundId === roundId)).toBe(false);
  });

  it("acknowledging an unrelated/unknown roundId is a harmless no-op", async () => {
    await expect(mockApi.acknowledgeResults!(["1h-not-a-real-round"])).resolves.toBeUndefined();
  });
});
