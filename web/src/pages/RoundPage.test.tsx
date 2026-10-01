import { render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { RoundCancelled, RoundDto, RoundSettled } from "../api/contract";
import { NOW, bettingRoundOf, catalogue, crowdPosition, marketsOf, publicPosition, runningRoundOf } from "../components/testFixtures";
import { RoundPage } from "./RoundPage";

const HASH = "0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70";
const window3min = { interval: "HOURLY" as const, startMs: NOW - 360_000, endMs: NOW - 180_000 };

function settled(over: Partial<RoundSettled> = {}): RoundSettled {
  return {
    phase: "SETTLED",
    roundId: "BTC-1h-0",
    window: window3min,
    marketId: "BTC",
    asset: "BTC",
    openPrice: "100.00",
    closePrice: "101.00",
    positions: [],
    crowd: null,
    ...over,
  };
}

function renderRound(round: RoundDto, signedIn = false) {
  const api = fakeApi({
    round: vi.fn(async () => round),
    markets: vi.fn(async () => marketsOf(catalogue())),
    referencePrice: vi.fn(async () => ({ price: "100.50", asOfMs: NOW })),
    myAccount: vi.fn(async () => ({ playerId: TEST_PLAYER.playerId, balance: "5000.000", currentSeason: "2026-09", inPlay: "0.000" })),
    previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
  });
  return render(
    <MemoryRouter initialEntries={[`/round/${round.roundId}`]}>
      <Routes>
        <Route path="/round/:roundId" element={<RoundPage />} />
      </Routes>
    </MemoryRouter>,
    { wrapper: apiWrapper(api, signedIn ? TEST_PLAYER : null) },
  );
}

describe("Round page — names the market and the series (S6)", () => {
  it("the hero says which market and how long a round, and links to the market's room", async () => {
    renderRound(settled());
    const link = await screen.findByRole("link", { name: "BTC, 3 MIN round — open the market" });
    expect(link).toHaveAttribute("href", "/play/BTC");
    expect(link).toHaveTextContent("BTC");
    expect(link).toHaveTextContent("3 MIN");
    expect(screen.getByText("SETTLED")).toBeInTheDocument();
    expect(screen.getByText(/BTC closed UP — \$100\.00 → \$101\.00/)).toBeInTheDocument();
  });

  it("the round's own window gives the length — never 'Hourly'/'Daily' for a three-minute round", async () => {
    renderRound(settled());
    await screen.findByText("SETTLED");
    expect(screen.queryByText(/Hourly|Daily/)).not.toBeInTheDocument();
  });

  it("an odds round reads in percent and says whose odds they are", async () => {
    renderRound(
      settled({ roundId: "PM900104-1h-0", marketId: "PM900104", asset: "GER", openPrice: "0.620000", closePrice: "0.640000" }),
    );
    expect(await screen.findByText(/GER closed UP — 62\.0% → 64\.0%/)).toBeInTheDocument();
    expect(screen.getByText(/Odds from Polymarket, published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
  });
});

describe("Round page — «Anchored on Chia» on a settled round (S6)", () => {
  it("confirmed: block, hash, and the link to the public results.json", async () => {
    renderRound(settled({ resultsHash: HASH, oracleAnchor: { seq: 412, state: "confirmed", height: 4_738_927 } }));
    expect(await screen.findByText("Anchored on Chia")).toBeInTheDocument();
    expect(screen.getByText("Oracle record #412, confirmed at block 4,738,927 on testnet11.")).toBeInTheDocument();
    expect(screen.getByText(/Results hash: 0x4b88ce97…080b70/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "verify results.json" })).toHaveAttribute("href", "/api/rounds/BTC-1h-0/results.json");
    // The line about whose key publishes it stays right next to the claim.
    expect(screen.getByText(/published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
  });

  it.each([
    ["unknown", "Not anchored yet", "PENDING"],
    ["queued", "Waiting to be anchored on Chia", "QUEUED"],
    ["in_flight", "Being anchored on Chia", "SENT"],
    ["conflict", "Not anchored — conflict", "CONFLICT"],
  ])("%s is worded as %s", async (state, title, chip) => {
    renderRound(settled({ oracleAnchor: { seq: 9, state, height: null } }));
    expect(await screen.findByText(title)).toBeInTheDocument();
    expect(screen.getByText(chip)).toBeInTheDocument();
    expect(screen.queryByText("Anchored on Chia")).not.toBeInTheDocument();
  });

  it("a server that sends neither an anchor nor a hash gets no anchoring claim", async () => {
    renderRound(settled());
    await screen.findByText("SETTLED");
    expect(screen.queryByText(/Anchored on Chia|Results hash|verify results\.json/)).not.toBeInTheDocument();
  });

  it("a RUNNING round has not been anchored — nothing is claimed yet", async () => {
    renderRound(runningRoundOf("BTC", "BTC"));
    await screen.findByText("LIVE");
    expect(screen.queryByText(/Anchored on Chia/)).not.toBeInTheDocument();
  });
});

describe("Round page — the player's own position", () => {
  const me = { playerId: TEST_PLAYER.playerId, player: { ...TEST_PLAYER } };

  it("settled: the net result with its sign, and what was staked and paid — never the payout as the headline", async () => {
    renderRound(settled({ positions: [publicPosition({ ...me, stake: "100.000", payout: "80.150", liquidated: false })] }), true);
    expect(await screen.findByText("-19.85 PLAY")).toBeInTheDocument();
    expect(screen.getByText(/stake 100 → paid 80.15/)).toBeInTheDocument();
  });

  it("liquidated: the whole stake, stated plainly", async () => {
    renderRound(settled({ positions: [publicPosition({ ...me, stake: "100.000", payout: "0.000", liquidated: true })] }), true);
    expect(await screen.findByText(/-100 PLAY · liquidated/)).toBeInTheDocument();
    expect(screen.getByText("You lost your whole stake.")).toBeInTheDocument();
  });

  it("running: live P&L, the meter of room left before liquidation, entry and liquidation prices", async () => {
    const own = publicPosition({ ...me, size: "10.00000000", entryPrice: "100.00", liquidationPrice: "90.50", stake: "100.000" });
    renderRound(runningRoundOf("BTC", "BTC", { positions: [own], crowd: crowdPosition() }), true);
    const card = (await screen.findByText(/Your position:/)).closest(".card") as HTMLElement;
    expect(within(card).getByText("unrealized")).toBeInTheDocument();
    expect(within(card).getByText(/\+5 PLAY/)).toBeInTheDocument();
    expect(within(card).getByRole("meter", { name: "Room left before liquidation" })).toBeInTheDocument();
    expect(within(card).getByText(/Entry \$100\.00 · Liquidation \$90\.50/)).toBeInTheDocument();
  });
});

describe("Round page — the other phases", () => {
  it("a cancelled round says which market and series, and the server's own reason", async () => {
    const cancelled: RoundCancelled = {
      phase: "CANCELLED",
      roundId: "PM900104-1h-0",
      window: window3min,
      marketId: "PM900104",
      asset: "GER",
      cancellation: { roundId: "PM900104-1h-0", reason: "Market closed: the underlying market ended.", cancelledAtMs: NOW, refundedBetCount: 4 },
    };
    renderRound(cancelled);
    expect(await screen.findByText("GER · 3 MIN")).toBeInTheDocument();
    expect(screen.getByText("This round was cancelled.")).toBeInTheDocument();
    expect(screen.getByText("Market closed: the underlying market ended.")).toBeInTheDocument();
    expect(screen.getByText(/All 4 bets were refunded/)).toBeInTheDocument();
  });

  it("betting: the bet slip and the headcount, the herd's position still hidden", async () => {
    renderRound(bettingRoundOf("BTC", "BTC", { participantCount: 7, upStake: "300.000", downStake: "100.000", majorityDirection: "UP" }));
    expect(await screen.findByText("OPEN FOR BETS")).toBeInTheDocument();
    expect(screen.getByText("7 players have voted")).toBeInTheDocument();
    expect(screen.getByText("75% UP · 25% DOWN")).toBeInTheDocument();
    expect(screen.getByText("The crowd's position is revealed when this round starts.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "BTC, 1 HOUR round — open the market" })).toBeInTheDocument();
  });
});
