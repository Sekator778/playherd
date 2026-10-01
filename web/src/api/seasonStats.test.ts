import { describe, expect, it } from "vitest";
import type { ProfileRoundEntryDto } from "./contract";
import { bestSingleRound, biggestComeback, seasonHistory } from "./seasonStats";

const SEASON = "2026-09";

function entry(overrides: Partial<ProfileRoundEntryDto> & { payout: string; stake: string; settledAtMs: number }): ProfileRoundEntryDto {
  return {
    roundId: `1h-${overrides.settledAtMs}`,
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    direction: "UP",
    leverage: 10,
    liquidated: false,
    directionCorrect: true,
    ...overrides,
  };
}

const SEP_1 = Date.UTC(2026, 8, 1, 0, 0, 0);
const AUG_1 = Date.UTC(2026, 7, 1, 0, 0, 0);

describe("seasonHistory", () => {
  it("keeps only rounds settled within the given season", () => {
    const history = [
      entry({ settledAtMs: SEP_1 + 1000, payout: "100.000", stake: "100.000" }),
      entry({ settledAtMs: AUG_1 + 1000, payout: "50.000", stake: "100.000" }),
    ];
    expect(seasonHistory(history, SEASON)).toHaveLength(1);
    expect(seasonHistory(history, SEASON)[0].settledAtMs).toBe(SEP_1 + 1000);
  });
});

describe("bestSingleRound", () => {
  it("picks the biggest net win, ignoring losses and other seasons", () => {
    const history = [
      entry({ settledAtMs: SEP_1 + 3000, payout: "50.000", stake: "100.000" }), // -50 (loss)
      entry({ settledAtMs: SEP_1 + 2000, payout: "900.000", stake: "100.000" }), // +800 (best)
      entry({ settledAtMs: SEP_1 + 1000, payout: "300.000", stake: "100.000" }), // +200
      entry({ settledAtMs: AUG_1 + 1000, payout: "5000.000", stake: "100.000" }), // +4900, but last season
    ];
    const best = bestSingleRound(history, SEASON);
    expect(best?.net).toBe(800);
  });

  it("returns null when the season has no winning round at all — never surfaces a 'least bad' loss", () => {
    const history = [
      entry({ settledAtMs: SEP_1 + 1000, payout: "0.000", stake: "100.000" }), // liquidated
      entry({ settledAtMs: SEP_1 + 2000, payout: "80.000", stake: "100.000" }), // -20
    ];
    expect(bestSingleRound(history, SEASON)).toBeNull();
  });

  it("returns null for an empty history", () => {
    expect(bestSingleRound([], SEASON)).toBeNull();
  });
});

describe("biggestComeback", () => {
  it("finds a win that immediately follows a loss, biggest first", () => {
    // newest-first: round at +4000 (win) follows round at +3000 (loss) chronologically.
    const history = [
      entry({ settledAtMs: SEP_1 + 4000, payout: "700.000", stake: "100.000" }), // +600, right after a loss
      entry({ settledAtMs: SEP_1 + 3000, payout: "0.000", stake: "100.000" }), // liquidated (loss)
      entry({ settledAtMs: SEP_1 + 2000, payout: "150.000", stake: "100.000" }), // +50, right after a win — not a comeback
      entry({ settledAtMs: SEP_1 + 1000, payout: "200.000", stake: "100.000" }), // +100 (win, oldest)
    ];
    const comeback = biggestComeback(history, SEASON);
    expect(comeback?.net).toBe(600);
  });

  it("returns null when no win is ever preceded by a loss", () => {
    const history = [
      entry({ settledAtMs: SEP_1 + 2000, payout: "200.000", stake: "100.000" }), // +100
      entry({ settledAtMs: SEP_1 + 1000, payout: "150.000", stake: "100.000" }), // +50 (still a win before it)
    ];
    expect(biggestComeback(history, SEASON)).toBeNull();
  });

  it("returns null for fewer than two rounds this season", () => {
    const history = [entry({ settledAtMs: SEP_1 + 1000, payout: "200.000", stake: "100.000" })];
    expect(biggestComeback(history, SEASON)).toBeNull();
  });
});
