import { describe, expect, it } from "vitest";
import type { ProfileRoundEntryDto, PublicPositionDto, RoundBetting, RoundDto, RoundRunning, RoundSettled } from "../api/contract";
import {
  applyLeverageKicker,
  detectResultEvent,
  liquidationTier,
  lossTier,
  resultEventFromHistoryEntry,
  roundHasPlayerStake,
  winTier,
} from "./resultEvent";

const PLAYER_ID = "player-1";
const WINDOW = { interval: "HOURLY" as const, startMs: 0, endMs: 3_600_000 };

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
    player: { playerId: PLAYER_ID, nickname: "p1", walletAddress: "", displayName: "p1" },
    liquidated: false,
    payout: null,
    directionCorrect: null,
    ...overrides,
  };
}

function makeRunning(positions: PublicPositionDto[], roundId = "1h-0"): RoundRunning {
  return {
    roundId,
    window: WINDOW,
    marketId: "BTC",
    asset: "BTC",
    phase: "RUNNING",
    openPrice: "83451.00",
    positions: positions.map((p) => ({ ...p, roundId })),
    crowd: null,
  };
}

function makeSettled(positions: PublicPositionDto[], roundId = "1h-0"): RoundSettled {
  return {
    roundId,
    window: WINDOW,
    marketId: "BTC",
    asset: "BTC",
    phase: "SETTLED",
    openPrice: "83451.00",
    closePrice: "84000.00",
    positions: positions.map((p) => ({ ...p, roundId })),
    crowd: null,
  };
}

function makeBetting(myBet: RoundBetting["myBet"] = null, roundId = "1h-1"): RoundBetting {
  return { roundId, window: WINDOW, marketId: "BTC", asset: "BTC", phase: "BETTING", participantCount: 1, myBet };
}

describe("winTier boundaries (result-moment.md §2)", () => {
  it("1.49x -> T1, 1.5x -> T2", () => {
    expect(winTier(1.49)).toBe(1);
    expect(winTier(1.5)).toBe(2);
  });
  it("2.99x -> T2, 3x -> T3", () => {
    expect(winTier(2.99)).toBe(2);
    expect(winTier(3)).toBe(3);
  });
  it("7.99x -> T3, 8x -> T4", () => {
    expect(winTier(7.99)).toBe(3);
    expect(winTier(8)).toBe(4);
  });
});

describe("lossTier boundaries (result-moment.md §2)", () => {
  it("4.9% -> T1, 5% -> T2", () => {
    expect(lossTier(0.049)).toBe(1);
    expect(lossTier(0.05)).toBe(2);
  });
  it("14.9% -> T2, 15% -> T3", () => {
    expect(lossTier(0.149)).toBe(2);
    expect(lossTier(0.15)).toBe(3);
  });
  it("39.9% -> T3, 40% -> T4", () => {
    expect(lossTier(0.399)).toBe(3);
    expect(lossTier(0.4)).toBe(4);
  });
});

describe("liquidationTier boundaries (result-moment.md §2)", () => {
  it("x9 -> T1, x10 -> T2", () => {
    expect(liquidationTier(9)).toBe(1);
    expect(liquidationTier(10)).toBe(2);
  });
  it("x19 -> T2, x20 -> T3", () => {
    expect(liquidationTier(19)).toBe(2);
    expect(liquidationTier(20)).toBe(3);
  });
  it("x29 -> T3, x30 -> T4", () => {
    expect(liquidationTier(29)).toBe(3);
    expect(liquidationTier(30)).toBe(4);
  });
  it("x39 stays T4 (ceiling)", () => {
    expect(liquidationTier(39)).toBe(4);
  });
});

describe("applyLeverageKicker (result-moment.md §2: leverage >= 30 -> tier+1, floor T2, ceiling T4)", () => {
  it("leaves a tier unchanged below x30", () => {
    expect(applyLeverageKicker(1, 29)).toBe(1);
    expect(applyLeverageKicker(3, 10)).toBe(3);
  });
  it("bumps by one at/above x30", () => {
    expect(applyLeverageKicker(1, 30)).toBe(2);
    expect(applyLeverageKicker(2, 35)).toBe(3);
  });
  it("floors at T2 even from a hypothetical tier below it", () => {
    expect(applyLeverageKicker(1, 40)).toBe(2);
  });
  it("ceilings at T4", () => {
    expect(applyLeverageKicker(3, 30)).toBe(4);
    expect(applyLeverageKicker(4, 40)).toBe(4);
  });
});

describe("detectResultEvent — LIQUIDATION (mid-round, instant)", () => {
  it("fires the instant `liquidated` first flips true, tiered by leverage", () => {
    const prev = makeRunning([makePosition({ liquidated: false, leverage: 25 })]);
    const curr = makeRunning([makePosition({ liquidated: true, leverage: 25 })]);
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 9900,
      nowMs: 1000,
    });
    expect(event).not.toBeNull();
    expect(event?.kind).toBe("LIQUIDATION");
    expect(event?.tier).toBe(3); // x25 -> T3
    expect(event?.netPlay).toBe(-100);
    expect(event?.balanceBefore).toBe(event?.balanceAfter); // no change at liquidation (§2)
  });

  it("does not re-fire once already liquidated on a previous tick", () => {
    const prev = makeRunning([makePosition({ liquidated: true, leverage: 25 })]);
    const curr = makeRunning([makePosition({ liquidated: true, leverage: 25 })]);
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 9900,
      nowMs: 1000,
    });
    expect(event).toBeNull();
  });
});

describe("detectResultEvent — settlement WIN/LOSS/EVEN", () => {
  it("WIN: net>0, tier by multiplier, no kicker below x30", () => {
    const prev = makeRunning([makePosition({ leverage: 10 })]);
    const curr = makeSettled([makePosition({ leverage: 10, payout: "500.000" })]); // 5x -> T3
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10400,
      nowMs: 1000,
    });
    expect(event?.kind).toBe("WIN");
    expect(event?.netPlay).toBe(400);
    expect(event?.tier).toBe(3);
  });

  it("WIN at x38 leverage gets the kicker even for a small multiplier", () => {
    const prev = makeRunning([makePosition({ leverage: 38 })]);
    const curr = makeSettled([makePosition({ leverage: 38, payout: "110.000" })]); // 1.1x -> base T1
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10010,
      nowMs: 1000,
    });
    expect(event?.kind).toBe("WIN");
    expect(event?.tier).toBe(2); // T1 + kicker -> T2
  });

  it("LOSS: net<0, tier by stakeShare of balanceBefore", () => {
    const prev = makeRunning([makePosition({ leverage: 10 })]);
    const curr = makeSettled([makePosition({ leverage: 10, payout: "80.150" })]); // net -19.85
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 80.15, // balanceBefore = 80.15 - (-19.85) = 100 -> stakeShare 100/100 = 100%
      nowMs: 1000,
    });
    expect(event?.kind).toBe("LOSS");
    expect(event?.netPlay).toBeCloseTo(-19.85, 5);
    expect(event?.tier).toBe(4);
  });

  it("EVEN: payout === stake -> net 0, fixed tier 1 (§0's предложение path)", () => {
    const prev = makeRunning([makePosition({ leverage: 10 })]);
    const curr = makeSettled([makePosition({ leverage: 10, payout: "100.000" })]);
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10100,
      nowMs: 1000,
    });
    expect(event?.kind).toBe("EVEN");
    expect(event?.netPlay).toBe(0);
    expect(event?.tier).toBe(1);
  });

  it("a liquidated position's own settlement never fires a second event (§1 dedup)", () => {
    const prev = makeRunning([makePosition({ liquidated: true, leverage: 10 })]);
    const curr = makeSettled([makePosition({ liquidated: true, leverage: 10, payout: "0.000" })]);
    const event = detectResultEvent({
      prev,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false, // simulates the watcher's Set already having handled it via LIQUIDATION,
      // but even without that flag the settlement branch itself must not re-emit for a liquidated position:
      currentBalance: 9900,
      nowMs: 1000,
    });
    expect(event).toBeNull();
  });

  it("alreadyEmitted short-circuits everything", () => {
    const curr = makeSettled([makePosition({ payout: "500.000" })]);
    const event = detectResultEvent({
      prev: undefined,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: true,
      currentBalance: 10400,
      nowMs: 1000,
    });
    expect(event).toBeNull();
  });
});

describe("detectResultEvent — hourly and daily settle in the same tick", () => {
  it("produces two independent events, one per interval", () => {
    const hourlyPrev = makeRunning([makePosition({ leverage: 10 })], "1h-0");
    const hourlyCurr = makeSettled([makePosition({ leverage: 10, payout: "200.000" })], "1h-0");
    const dailyPrev = makeRunning([makePosition({ leverage: 10 })], "1d-0");
    const dailyCurr = makeSettled([makePosition({ leverage: 10, payout: "50.000" })], "1d-0");

    const hourlyEvent = detectResultEvent({
      prev: hourlyPrev,
      curr: hourlyCurr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10100,
      nowMs: 1000,
    });
    const dailyEvent = detectResultEvent({
      prev: dailyPrev,
      curr: dailyCurr,
      playerId: PLAYER_ID,
      interval: "DAILY",
      alreadyEmitted: false,
      currentBalance: 9950,
      nowMs: 1000,
    });

    expect(hourlyEvent).not.toBeNull();
    expect(dailyEvent).not.toBeNull();
    expect(hourlyEvent?.interval).toBe("HOURLY");
    expect(dailyEvent?.interval).toBe("DAILY");
    expect(hourlyEvent?.kind).toBe("WIN");
    expect(dailyEvent?.kind).toBe("LOSS");
    expect(hourlyEvent?.id).not.toBe(dailyEvent?.id);
  });
});

function historyEntry(overrides: Partial<ProfileRoundEntryDto> = {}): ProfileRoundEntryDto {
  return {
    roundId: "1h-0",
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    settledAtMs: 1000,
    direction: "UP",
    leverage: 10,
    stake: "100.000",
    liquidated: false,
    directionCorrect: true,
    payout: "500.000",
    ...overrides,
  };
}

describe("resultEventFromHistoryEntry (fx/useAwayReplay.ts — 'while you were away')", () => {
  it("WIN: same tier math as the live path, from a settled history entry alone", () => {
    const entry = historyEntry({ payout: "500.000", stake: "100.000", leverage: 10 }); // 5x -> T3
    const event = resultEventFromHistoryEntry(entry, PLAYER_ID, { balanceBefore: 10000, balanceAfter: 10400 });
    expect(event.kind).toBe("WIN");
    expect(event.netPlay).toBe(400);
    expect(event.tier).toBe(3);
    expect(event.balanceBefore).toBe(10000);
    expect(event.balanceAfter).toBe(10400);
  });

  it("LOSS: tier by stakeShare of the caller-supplied balanceBefore", () => {
    const entry = historyEntry({ payout: "80.150", stake: "100.000" }); // net -19.85
    const event = resultEventFromHistoryEntry(entry, PLAYER_ID, { balanceBefore: 100, balanceAfter: 80.15 });
    expect(event.kind).toBe("LOSS");
    expect(event.netPlay).toBeCloseTo(-19.85, 5);
    expect(event.tier).toBe(4); // stakeShare 100/100 = 100%
  });

  it("LIQUIDATION wins over settlement even when payout looks like a partial refund", () => {
    const entry = historyEntry({ liquidated: true, payout: "0.000", stake: "100.000", leverage: 25 });
    const event = resultEventFromHistoryEntry(entry, PLAYER_ID, { balanceBefore: 9000, balanceAfter: 9500 });
    expect(event.kind).toBe("LIQUIDATION");
    expect(event.netPlay).toBe(-100);
    expect(event.tier).toBe(3); // x25 -> T3
    // Liquidation never moves the running balance (already debited at placeBet) — the caller's
    // balanceBefore is ignored in favor of balanceAfter for both fields (result-moment.md §2).
    expect(event.balanceBefore).toBe(event.balanceAfter);
  });

  it("EVEN: payout === stake -> net 0, fixed tier 1", () => {
    const entry = historyEntry({ payout: "100.000", stake: "100.000" });
    const event = resultEventFromHistoryEntry(entry, PLAYER_ID, { balanceBefore: 10000, balanceAfter: 10100 });
    expect(event.kind).toBe("EVEN");
    expect(event.netPlay).toBe(0);
    expect(event.tier).toBe(1);
  });

  it("id is the same `${roundId}:${playerId}` dedup key the live path uses", () => {
    const entry = historyEntry({ roundId: "1h-42" });
    const event = resultEventFromHistoryEntry(entry, PLAYER_ID, { balanceBefore: 0, balanceAfter: 0 });
    expect(event.id).toBe(`1h-42:${PLAYER_ID}`);
  });
});

describe("roundHasPlayerStake", () => {
  it("true for a BETTING round with the player's own bet", () => {
    const round: RoundDto = makeBetting({ playerId: PLAYER_ID, roundId: "1h-1", direction: "UP", leverage: 10, stake: "100.000" });
    expect(roundHasPlayerStake(round, PLAYER_ID)).toBe(true);
  });
  it("false for a BETTING round with someone else's bet", () => {
    const round: RoundDto = makeBetting({ playerId: "other", roundId: "1h-1", direction: "UP", leverage: 10, stake: "100.000" });
    expect(roundHasPlayerStake(round, PLAYER_ID)).toBe(false);
  });
  it("true for a RUNNING round with the player's position", () => {
    expect(roundHasPlayerStake(makeRunning([makePosition()]), PLAYER_ID)).toBe(true);
  });
  it("false for a RUNNING round without the player", () => {
    expect(roundHasPlayerStake(makeRunning([makePosition({ playerId: "other" })]), PLAYER_ID)).toBe(false);
  });
});

describe("the event names its market (Р-69)", () => {
  it("a live settlement carries the round's marketId and the market's symbol", () => {
    const curr: RoundSettled = {
      ...makeSettled([makePosition({ roundId: "xyz_NVDA-1h-0", payout: "130.000", directionCorrect: true })], "xyz_NVDA-1h-0"),
      marketId: "xyz_NVDA",
      asset: "NVDA",
    };
    const event = detectResultEvent({
      prev: undefined,
      curr,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10_030,
      nowMs: 5,
    });
    expect(event).toMatchObject({ kind: "WIN", marketId: "xyz_NVDA", symbol: "NVDA", roundId: "xyz_NVDA-1h-0" });
  });

  it("a mid-round liquidation carries them too", () => {
    const prev: RoundRunning = { ...makeRunning([makePosition({ leverage: 20 })], "ETH-1h-0"), marketId: "ETH", asset: "ETH" };
    const curr: RoundRunning = { ...makeRunning([makePosition({ leverage: 20, liquidated: true })], "ETH-1h-0"), marketId: "ETH", asset: "ETH" };
    const event = detectResultEvent({ prev, curr, playerId: PLAYER_ID, interval: "HOURLY", alreadyEmitted: false, currentBalance: 9_900, nowMs: 5 });
    expect(event).toMatchObject({ kind: "LIQUIDATION", marketId: "ETH", symbol: "ETH" });
  });

  it("an event rebuilt from a history entry takes them from the entry (marketId and asset)", () => {
    const event = resultEventFromHistoryEntry(
      historyEntry({ roundId: "PM900104-1d-86400000", interval: "DAILY", marketId: "PM900104", asset: "GER" }),
      PLAYER_ID,
      { balanceBefore: 10_000, balanceAfter: 10_400 },
    );
    expect(event).toMatchObject({ marketId: "PM900104", symbol: "GER", interval: "DAILY", roundId: "PM900104-1d-86400000" });
  });
});

describe("a server that predates markets (no marketId on its DTOs) means BTC", () => {
  it("the history-entry path and the live path both default marketId to BTC", () => {
    const legacyEntry: Partial<ProfileRoundEntryDto> = historyEntry();
    delete legacyEntry.marketId;
    const fromHistory = resultEventFromHistoryEntry(legacyEntry as ProfileRoundEntryDto, PLAYER_ID, { balanceBefore: 1, balanceAfter: 2 });
    expect(fromHistory).toMatchObject({ marketId: "BTC", symbol: "BTC" });

    const legacyRound: Partial<RoundSettled> = makeSettled([makePosition({ payout: "130.000" })]);
    delete legacyRound.marketId;
    const live = detectResultEvent({
      prev: undefined,
      curr: legacyRound as RoundSettled,
      playerId: PLAYER_ID,
      interval: "HOURLY",
      alreadyEmitted: false,
      currentBalance: 10_030,
      nowMs: 5,
    });
    expect(live).toMatchObject({ marketId: "BTC", symbol: "BTC" });
  });
});

describe("the event carries the round's real length, so the card never says Hourly/Daily", () => {
  it("an event rebuilt from a history entry takes durationSec from the entry", () => {
    const event = resultEventFromHistoryEntry(historyEntry({ durationSec: 180 }), PLAYER_ID, { balanceBefore: 10_000, balanceAfter: 10_400 });
    expect(event.durationSec).toBe(180);
  });

  it("an entry from a server that sends none gives an event with none (the card then reads the market's series)", () => {
    const event = resultEventFromHistoryEntry(historyEntry(), PLAYER_ID, { balanceBefore: 10_000, balanceAfter: 10_400 });
    expect(event.durationSec).toBeUndefined();
  });

  it("a live settlement and a live liquidation take it from the round's own window", () => {
    const window = { interval: "HOURLY" as const, startMs: 0, endMs: 900_000 };
    const settled: RoundSettled = { ...makeSettled([makePosition({ payout: "130.000", directionCorrect: true })]), window };
    const win = detectResultEvent({ prev: undefined, curr: settled, playerId: PLAYER_ID, interval: "HOURLY", alreadyEmitted: false, currentBalance: 10_030, nowMs: 5 });
    expect(win?.durationSec).toBe(900);

    const prev: RoundRunning = { ...makeRunning([makePosition({ leverage: 20 })]), window };
    const curr: RoundRunning = { ...makeRunning([makePosition({ leverage: 20, liquidated: true })]), window };
    const liq = detectResultEvent({ prev, curr, playerId: PLAYER_ID, interval: "HOURLY", alreadyEmitted: false, currentBalance: 9_900, nowMs: 5 });
    expect(liq).toMatchObject({ kind: "LIQUIDATION", durationSec: 900 });
  });
});
