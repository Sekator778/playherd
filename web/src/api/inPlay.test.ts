import { describe, expect, it } from "vitest";
import type {
  PublicPositionDto,
  RoundBetting,
  RoundCancelled,
  RoundRunning,
  RoundSettled,
  RoundsPairDto,
} from "./contract";
import { inPlayStakeForSeries, totalInPlayStake } from "./inPlay";

const PLAYER_ID = "me";

function bettingRound(myBetStake: string | null): RoundBetting {
  return {
    roundId: "1h-1",
    window: { interval: "HOURLY", startMs: 1000, endMs: 2000 },
    asset: "BTC",
    phase: "BETTING",
    participantCount: 5,
    myBet:
      myBetStake === null
        ? null
        : { playerId: PLAYER_ID, roundId: "1h-1", direction: "UP", leverage: 10, stake: myBetStake },
  };
}

function position(overrides: Partial<PublicPositionDto> = {}, includeStake = true): PublicPositionDto {
  const base: PublicPositionDto = {
    playerId: PLAYER_ID,
    roundId: "1h-0",
    direction: "UP",
    leverage: 10,
    size: "0.01198",
    entryPrice: "83451.00",
    liquidationPrice: "76056.61",
    pnl: null,
    stake: "100.000",
    player: { playerId: PLAYER_ID, nickname: "me", walletAddress: "xch1...", displayName: "me" },
    liquidated: false,
    payout: null,
    directionCorrect: null,
    ...overrides,
  };
  if (!includeStake) delete base.stake;
  return base;
}

function runningRound(positions: PublicPositionDto[]): RoundRunning {
  return {
    roundId: "1h-0",
    window: { interval: "HOURLY", startMs: 0, endMs: 1000 },
    asset: "BTC",
    phase: "RUNNING",
    openPrice: "83451.00",
    positions,
    crowd: null,
  };
}

function settledRound(positions: PublicPositionDto[]): RoundSettled {
  return {
    roundId: "1h-0",
    window: { interval: "HOURLY", startMs: 0, endMs: 1000 },
    asset: "BTC",
    phase: "SETTLED",
    openPrice: "83451.00",
    closePrice: "84000.00",
    positions,
    crowd: null,
  };
}

const NOTHING_BETTING: RoundBetting = bettingRound(null);
const NOTHING_RUNNING: RoundRunning = runningRound([]);

describe("inPlayStakeForSeries — Alex's report (2026-09-25): the header showed only the free balance", () => {
  it("sums a reserved bet on the open round plus a position on the running round", () => {
    const pair: RoundsPairDto = {
      interval: "HOURLY",
      betting: bettingRound("100.000"),
      running: runningRound([position({ stake: "250.000" })]),
    };
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBeCloseTo(350, 5);
  });

  it("is zero when the player has neither a pending bet nor a running position", () => {
    const pair: RoundsPairDto = { interval: "HOURLY", betting: NOTHING_BETTING, running: NOTHING_RUNNING };
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBe(0);
  });

  it("only counts the player's own position among the running round's positions, not everyone's", () => {
    const pair: RoundsPairDto = {
      interval: "HOURLY",
      betting: NOTHING_BETTING,
      running: runningRound([
        position({ playerId: "npc-1", stake: "999.000" }),
        position({ playerId: PLAYER_ID, stake: "150.000" }),
      ]),
    };
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBeCloseTo(150, 5);
  });

  it("a SETTLED running round contributes nothing — its stake has already been paid out", () => {
    const pair: RoundsPairDto = {
      interval: "HOURLY",
      betting: NOTHING_BETTING,
      running: settledRound([position()]),
    };
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBe(0);
  });

  it("an early-cancelled betting round (RoundCancelled) contributes nothing", () => {
    const cancelled: RoundCancelled = {
      roundId: "1h-1",
      window: { interval: "HOURLY", startMs: 1000, endMs: 2000 },
      asset: "BTC",
      phase: "CANCELLED",
      cancellation: { roundId: "1h-1", reason: "bad oracle read", cancelledAtMs: 1500, refundedBetCount: 3 },
    };
    const pair: RoundsPairDto = { interval: "HOURLY", betting: cancelled, running: NOTHING_RUNNING };
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBe(0);
  });

  it("falls back to the derived stake when a position's exact `stake` field is absent", () => {
    const withoutStake = position({ size: "0.10000", entryPrice: "83451.00", leverage: 10 }, false);
    const pair: RoundsPairDto = {
      interval: "HOURLY",
      betting: NOTHING_BETTING,
      running: runningRound([withoutStake]),
    };
    // hl_liquidation_math.md §3 golden vector: size 0.10000/entry 83451.00/lev 10 -> margin 834.51.
    expect(inPlayStakeForSeries(PLAYER_ID, pair)).toBeCloseTo(834.51, 5);
  });
});

describe("totalInPlayStake — combines both series (task: header shows '+{N} in play')", () => {
  it("sums the hourly and daily series independently", () => {
    const hourly: RoundsPairDto = {
      interval: "HOURLY",
      betting: bettingRound("100.000"),
      running: NOTHING_RUNNING,
    };
    const daily: RoundsPairDto = {
      interval: "DAILY",
      betting: NOTHING_BETTING,
      running: runningRound([position({ stake: "2500.000" })]),
    };
    expect(totalInPlayStake(PLAYER_ID, hourly, daily)).toBeCloseTo(2600, 5);
  });
});
