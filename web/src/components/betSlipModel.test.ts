import { describe, expect, it } from "vitest";
import { LEVERAGE_MAX, LEVERAGE_MIN, type BetDto } from "../api/contract";
import { CAPPED_MARKET_DEFAULT_LEVERAGE, DEFAULT_LEVERAGE, defaultLeverage, savedChangeIsShown, type SavedChange } from "./betSlipModel";

describe("defaultLeverage — where a fresh slip opens (first tester pass, D2)", () => {
  // The whole rule: x10, as the slip has always opened, wherever the market's cap lies above it; x3 where x10 would BE the cap
  // (every odds market, DOGE, HYPE — their Flame setting, which a player who just pressed PLACE BET had not chosen); never above the cap.
  it.each([
    [40, 10, "BTC and the indices — as it always opened"],
    [25, 10, "ETH — as it always opened"],
    [20, 10, "SOL, XRP, NVDA — as it always opened"],
    [10, 3, "every odds market, DOGE, HYPE — x10 was their cap"],
    [5, 3, "a market capped lower still"],
    [3, 3, "x3 is the cap itself"],
    [2, 2, "the cap is the minimum of the dial"],
  ])("a market capped at x%i opens at x%i (%s)", (cap, expected) => {
    expect(defaultLeverage(cap)).toBe(expected);
  });

  it("the two numbers are the ones the table says", () => {
    expect(DEFAULT_LEVERAGE).toBe(10);
    expect(CAPPED_MARKET_DEFAULT_LEVERAGE).toBe(3);
  });

  it("unchanged for every market whose cap is above x10: x10, whatever the cap", () => {
    for (let cap = DEFAULT_LEVERAGE + 1; cap <= LEVERAGE_MAX; cap++) {
      expect(defaultLeverage(cap), `cap x${cap}`).toBe(10);
    }
  });

  it("a market capped at x10 or lower opens at x3 — or on its cap, when that is below x3", () => {
    for (let cap = LEVERAGE_MIN; cap <= DEFAULT_LEVERAGE; cap++) {
      expect(defaultLeverage(cap), `cap x${cap}`).toBe(Math.min(cap, 3));
    }
  });

  it("stays on the dial: never below the minimum, never above the cap", () => {
    for (let cap = LEVERAGE_MIN; cap <= LEVERAGE_MAX; cap++) {
      const value = defaultLeverage(cap);
      expect(value, `cap x${cap}`).toBeGreaterThanOrEqual(LEVERAGE_MIN);
      expect(value, `cap x${cap}`).toBeLessThanOrEqual(cap);
    }
  });
});

describe("savedChangeIsShown — the page shows what the server was told (first tester pass, D4)", () => {
  const bet = (over: Partial<BetDto> = {}): BetDto => ({ playerId: "p", roundId: "r", direction: "UP", leverage: 10, stake: "650.000", ...over });
  const sent: SavedChange = { kind: "updated", roundId: "r", direction: "UP", leverage: 10, stake: "650.000" };

  it("the bet as it was sent is shown — a stake is the number the server stores, whatever its spelling", () => {
    expect(savedChangeIsShown(sent, bet())).toBe(true);
    expect(savedChangeIsShown(sent, bet({ stake: "650" }))).toBe(true);
    expect(savedChangeIsShown({ ...sent, kind: "placed" }, bet())).toBe(true);
  });

  it("the old bet is not, nor one that differs in its stake, side or leverage, nor no bet at all", () => {
    expect(savedChangeIsShown(sent, bet({ stake: "500.000" }))).toBe(false);
    expect(savedChangeIsShown(sent, bet({ stake: "650.001" }))).toBe(false);
    expect(savedChangeIsShown(sent, bet({ direction: "DOWN" }))).toBe(false);
    expect(savedChangeIsShown(sent, bet({ leverage: 5 }))).toBe(false);
    expect(savedChangeIsShown(sent, null)).toBe(false);
    expect(savedChangeIsShown({ ...sent, kind: "placed" }, null)).toBe(false);
  });

  it("after a cancel the page shows it when there is no bet — and not while the bet is still there", () => {
    const cancelled: SavedChange = { kind: "cancelled", roundId: "r", refund: "500.000" };
    expect(savedChangeIsShown(cancelled, null)).toBe(true);
    expect(savedChangeIsShown(cancelled, bet({ stake: "500.000" }))).toBe(false);
  });
});
