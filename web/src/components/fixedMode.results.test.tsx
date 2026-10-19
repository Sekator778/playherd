/**
 * What a "Win or lose" (fixed-payout, Р-106) bet shows after it is placed: the dock and the own-position card while the round runs, the
 * result tile and the result moment when it settles ("+S", "−S", "Tie — stake back"), and the wire of the mock. No boost, no wipeout line.
 */
import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { MarketDto, MyPositionDto, ProfileRoundEntryDto } from "../api/contract";
import { resultEventFromHistoryEntry, winTier, lossTier } from "../fx/resultEvent";
import { MyBetDock } from "./MyBetDock";
import { OwnPositionCard } from "./OwnPositionCard";
import { ResultTile } from "./ResultTile";
import { setProMode } from "./proMode";
import { NOW, market, marketsOf, publicPosition } from "./testFixtures";

const btc = (price: string): MarketDto => market("BTC", { symbol: "BTC", title: "Bitcoin", price: { price, asOfMs: NOW, change24h: 0.01, stale: false } });

const fixedPosition = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
  marketId: "BTC",
  symbol: "BTC",
  roundId: "BTC-1h-0",
  interval: "HOURLY",
  durationSec: 180,
  phase: "RUNNING",
  direction: "UP",
  leverage: 2,
  stake: "500.000",
  entryPrice: "100.00",
  liquidationPrice: null,
  size: "0.00000000",
  liquidated: false,
  untilMs: NOW + 90_000,
  mode: "FIXED",
  ...over,
});

beforeEach(() => {
  window.localStorage.clear();
  setProMode(false);
});

describe("the result tile", () => {
  const tile = (net: number, stakeAndPayout: [string, string]) =>
    render(
      <MemoryRouter>
        <ResultTile roundId="BTC-1h-0" symbol="BTC" direction="UP" leverage={2} net={net} liquidated={false} stake={stakeAndPayout[0]} payout={stakeAndPayout[1]} mode="FIXED" />
      </MemoryRouter>,
    );

  it("a win is +S, a loss is −S, with Win or lose in place of the boost", () => {
    const { unmount } = tile(500, ["500.000", "1000.000"]);
    expect(screen.getByText(/\+500 PLAY/)).toBeInTheDocument();
    expect(screen.getByText(/Win or lose/)).toBeInTheDocument();
    expect(screen.queryByText(/x2/)).not.toBeInTheDocument();
    unmount();
    tile(-500, ["500.000", "0.000"]);
    expect(screen.getByText(/-500 PLAY|−500 PLAY/)).toBeInTheDocument();
  });

  it("a tie says Tie — stake back", () => {
    tile(0, ["500.000", "500.000"]);
    expect(screen.getByText("Tie — stake back")).toBeInTheDocument();
  });
});

describe("a running Win or lose bet", () => {
  it("the own-position card says what a win and a loss are worth and where the price stands, with no liquidation line", () => {
    render(
      <OwnPositionCard
        position={publicPosition({ leverage: 2, size: "0.00000000", liquidationPrice: null, stake: "500.000", direction: "UP", mode: "FIXED" })}
        referencePrice={{ price: "100.50", asOfMs: NOW }}
        market={btc("100.50")}
      />,
    );
    expect(screen.getByText(/Win \+500 PLAY · Lose −500 PLAY/)).toBeInTheDocument();
    expect(screen.getByText("Right now you are ahead")).toBeInTheDocument();
    expect(screen.getByText(/Win or lose/)).toBeInTheDocument();
    expect(screen.queryByText(/liquidat|wipeout/i)).not.toBeInTheDocument();
  });

  it("behind when the price moved against the bet", () => {
    render(
      <OwnPositionCard
        position={publicPosition({ leverage: 2, liquidationPrice: null, stake: "500.000", direction: "UP", mode: "FIXED" })}
        referencePrice={{ price: "99.50", asOfMs: NOW }}
        market={btc("99.50")}
      />,
    );
    expect(screen.getByText("Right now you are behind")).toBeInTheDocument();
  });

  it("the dock names Win or lose, the stake's worth, and Winning / Losing - not a P&L or a wipeout", async () => {
    const api = fakeApi({ markets: vi.fn(async () => marketsOf([btc("100.50")])), myPositions: vi.fn(async () => [fixedPosition()]) });
    render(
      <MemoryRouter>
        <MyBetDock />
      </MemoryRouter>,
      { wrapper: apiWrapper(api, TEST_PLAYER) },
    );
    const dock = await screen.findByRole("region", { name: "My bet" });
    expect(within(dock).getByText(/Win or lose/)).toBeInTheDocument();
    expect(within(dock).getByText(/Win \+500 PLAY · Lose −500 PLAY/)).toBeInTheDocument();
    expect(within(dock).getByText(/Winning/i)).toBeInTheDocument();
    expect(within(dock).queryByText(/wipeout/i)).not.toBeInTheDocument();
  });
});

describe("the result moment of a Win or lose bet (the game-feel tiers work as for any bet)", () => {
  const entry = (over: Partial<ProfileRoundEntryDto>): ProfileRoundEntryDto => ({
    roundId: "BTC-1h-0",
    interval: "HOURLY",
    marketId: "BTC",
    asset: "BTC",
    settledAtMs: NOW,
    direction: "UP",
    leverage: 2,
    stake: "500.000",
    liquidated: false,
    directionCorrect: true,
    payout: "1000.000",
    fees: "15.100",
    mode: "FIXED",
    ...over,
  });

  it("a win is +S, flagged fixed, with the tier of a 2x payout; a bigger loss share is a stronger LOSS tier", () => {
    const win = resultEventFromHistoryEntry(entry({}), "p", { balanceBefore: 10000, balanceAfter: 10500 });
    expect(win).toMatchObject({ kind: "WIN", netPlay: 500, fixed: true, tier: winTier(2) });
    const small = resultEventFromHistoryEntry(entry({ payout: "0.000", stake: "100.000", directionCorrect: false }), "p", { balanceBefore: 10000, balanceAfter: 10000 });
    const big = resultEventFromHistoryEntry(entry({ payout: "0.000", stake: "5000.000", directionCorrect: false }), "p", { balanceBefore: 10000, balanceAfter: 10000 });
    expect(small).toMatchObject({ kind: "LOSS", netPlay: -100, fixed: true, tier: lossTier(0.01) });
    expect(big).toMatchObject({ kind: "LOSS", netPlay: -5000, tier: lossTier(0.5) });
    expect(big.tier).toBeGreaterThan(small.tier);
  });

  it("a tie is an EVEN moment flagged fixed (the card words it Tie — stake back); a percentage bet has no flag", () => {
    const tie = resultEventFromHistoryEntry(entry({ payout: "500.000", directionCorrect: null }), "p", { balanceBefore: 10000, balanceAfter: 10000 });
    expect(tie).toMatchObject({ kind: "EVEN", netPlay: 0, fixed: true });
    const pct = resultEventFromHistoryEntry(entry({ mode: null, leverage: 10 }), "p", { balanceBefore: 10000, balanceAfter: 10500 });
    expect(pct).not.toHaveProperty("fixed");
  });
});
