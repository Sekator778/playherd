import { act, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ApiRequestError, type CrowdRoundResultDto, type MyPositionDto, type RoundCancelled, type RoundDto, type RoundSettled } from "../api/contract";
import { NOW, bettingRoundOf, catalogue, crowdPosition, marketsOf, publicPosition, runningRoundOf, spotCatalogue } from "../components/testFixtures";
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
    kind: "PERP",
    priceDecimals: 2,
    source: "hyperliquid",
    openPrice: "100.00",
    closePrice: "101.00",
    positions: [],
    crowd: null,
    ...over,
  };
}

function renderRound(round: RoundDto, signedIn = false, positions: MyPositionDto[] = [], markets = catalogue()) {
  const api = fakeApi({
    round: vi.fn(async () => round),
    markets: vi.fn(async () => marketsOf(markets)),
    myPositions: vi.fn(async () => positions),
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
      settled({
        roundId: "PM900104-1h-0",
        marketId: "PM900104",
        asset: "GER",
        kind: "ODDS",
        priceDecimals: 6,
        source: "polymarket",
        openPrice: "0.620000",
        closePrice: "0.640000",
      }),
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

  it("liquidated: says at which price, and that the price reached it DURING the round — the round may close well clear of it (first tester pass, D6)", async () => {
    renderRound(
      settled({
        openPrice: "100.00",
        closePrice: "99.20", // a close less than 1% down; x10 liquidates at 90.50, which the path of the round touched
        positions: [publicPosition({ ...me, stake: "100.000", payout: "0.000", liquidated: true, entryPrice: "100.00", liquidationPrice: "90.50" })],
      }),
      true,
    );
    const card = (await screen.findByText(/-100 PLAY · liquidated/)).closest(".card") as HTMLElement;
    expect(within(card).getByText("You lost your whole stake.")).toBeInTheDocument();
    expect(within(card).getByText(/Entry \$100\.00 · Liquidation price \$90\.50/)).toBeInTheDocument();
    expect(card).toHaveTextContent(
      "The price reached $90.50 during the round, so the position was liquidated at that moment. Liquidation is checked all through the round, not only at its close (this round closed at $99.20).",
    );
  });

  it("liquidated on an odds round: 'odds', in percent — the tester's BOLT, which closed near where it opened", async () => {
    renderRound(
      settled({
        roundId: "PM900104-1h-0",
        marketId: "PM900104",
        asset: "GER",
        kind: "ODDS",
        priceDecimals: 6,
        source: "polymarket",
        openPrice: "0.745000",
        closePrice: "0.735000",
        positions: [publicPosition({ ...me, stake: "100.000", payout: "0.000", liquidated: true, entryPrice: "0.745000", liquidationPrice: "0.704000" })],
      }),
      true,
    );
    const card = (await screen.findByText(/-100 PLAY · liquidated/)).closest(".card") as HTMLElement;
    expect(within(card).getByText(/Entry 74\.5% · Liquidation price 70\.4%/)).toBeInTheDocument();
    expect(card).toHaveTextContent("The odds reached 70.4% during the round");
    expect(card).toHaveTextContent("(this round closed at 73.5%)");
  });

  it("liquidated while the round still runs: the same two facts, and no close to compare with yet", async () => {
    const own = publicPosition({ ...me, stake: "100.000", liquidated: true, entryPrice: "100.00", liquidationPrice: "90.50" });
    renderRound(runningRoundOf("BTC", "BTC", { positions: [own], crowd: crowdPosition() }), true);
    const card = (await screen.findByText(/-100 PLAY · liquidated/)).closest(".card") as HTMLElement;
    expect(within(card).getByText(/Entry \$100\.00 · Liquidation price \$90\.50/)).toBeInTheDocument();
    expect(card).toHaveTextContent("The price reached $90.50 during the round");
    expect(card).not.toHaveTextContent("closed at");
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

describe("Round page — «Anchored on Chia»: which asset of the oracle record this market is (MarketDto.oracleIndex)", () => {
  const anchored = (marketOver: Record<string, unknown>) =>
    catalogue().map((m) => (m.id === "BTC" ? { ...m, ...marketOver } : m));

  it("says 'BTC is asset #0 in the oracle record' with the public registry — 0 is a real index", async () => {
    renderRound(settled({ resultsHash: HASH, oracleAnchor: { seq: 412, state: "confirmed", height: 4_738_927 } }), false, [], anchored({ oracleIndex: 0 }));
    expect(await screen.findByText(/BTC is asset #0 in the oracle record/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "market registry" })).toHaveAttribute("href", "/api/markets/registry.json");
  });

  it("another market says its own number", async () => {
    renderRound(
      settled({ roundId: "ETH-1h-0", marketId: "ETH", asset: "ETH", resultsHash: HASH, oracleAnchor: { seq: 413, state: "queued", height: null } }),
      false,
      [],
      catalogue().map((m) => (m.id === "ETH" ? { ...m, oracleIndex: 7 } : m)),
    );
    expect(await screen.findByText(/ETH is asset #7 in the oracle record/)).toBeInTheDocument();
  });

  it("a server that sends no index gets no sentence about one", async () => {
    renderRound(settled({ resultsHash: HASH, oracleAnchor: { seq: 412, state: "confirmed", height: 4_738_927 } }));
    await screen.findByText("Anchored on Chia");
    expect(screen.queryByText(/in the oracle record/)).not.toBeInTheDocument();
  });
});

describe("Round page — a running round with no opening price yet (RoundRunning.openPrice === null)", () => {
  const waiting = () => runningRoundOf("BTC", "BTC", { openPrice: null, positions: [], crowd: null });

  it("says it is waiting — and prints no open price, no crowd position, no player list, no zero", async () => {
    renderRound(waiting());
    expect(await screen.findByRole("heading", { name: "Waiting for the opening price…" })).toBeInTheDocument();
    expect(screen.getByText("LIVE")).toBeInTheDocument(); // the round is running; the clock still counts down
    expect(screen.getByText(/Resolves in/)).toBeInTheDocument();
    expect(screen.queryByText(/Open price/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Crowd account's position/)).not.toBeInTheDocument();
    expect(screen.queryByText(/tied this round/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No positions in this round/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "All positions" })).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NaN|undefined|\$0\.00/);
  });

  it("the player's own bet in it: said, with no P&L and no liquidation meter", async () => {
    const bet: MyPositionDto = {
      marketId: "BTC",
      symbol: "BTC",
      roundId: "BTC-1h-0",
      interval: "HOURLY",
      durationSec: 180,
      phase: "RUNNING",
      direction: "DOWN",
      leverage: 12,
      stake: "250.000",
      entryPrice: null,
      liquidationPrice: null,
      size: null,
      liquidated: false,
      untilMs: NOW + 90_000,
    };
    renderRound(waiting(), true, [bet]);
    expect(await screen.findByText(/Your bet:/)).toHaveTextContent(/DOWN · x12 · BTC · 250 PLAY/);
    expect(screen.queryByText(/unrealized/)).not.toBeInTheDocument();
    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
    expect(screen.queryByText(/Your position:/)).not.toBeInTheDocument();
    // said once, on the card of the player's own bet — the round-level notice is not stacked beside it
    expect(screen.getAllByText("Waiting for the opening price…")).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Waiting for the opening price…" })).not.toBeInTheDocument();
  });

  it("with a known open price it is the usual page", async () => {
    renderRound(runningRoundOf("BTC", "BTC", { openPrice: "100.00", crowd: crowdPosition() }));
    expect(await screen.findByText(/Open price: \$100\.00/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Waiting for the opening price…" })).not.toBeInTheDocument();
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
      kind: "ODDS",
      priceDecimals: 6,
      source: "polymarket",
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

describe("Round page — prices and source from the ROUND's own fields: the market may have left markets()", () => {
  // `PM999999` / `DOGE9` are in no catalogue: their market is gone, the round page is all there is.
  const odds = (over: Partial<RoundSettled> = {}) =>
    settled({ roundId: "PM999999-1h-0", marketId: "PM999999", asset: "GER", kind: "ODDS", priceDecimals: 6, source: "polymarket", openPrice: "0.523000", closePrice: "0.534000", ...over });

  it("an odds round of a departed market reads in percent and names Polymarket — not '$0.52' and not Hyperliquid", async () => {
    renderRound(odds());
    expect(await screen.findByText(/GER closed UP — 52\.3% → 53\.4%/)).toBeInTheDocument();
    expect(screen.getByText("Open price: 52.3%")).toBeInTheDocument();
    expect(screen.getByText(/Odds from Polymarket, published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/\$0\.5|Hyperliquid/);
  });

  it("a 7-digit perp of a departed market keeps its five significant digits", async () => {
    renderRound(
      settled({ roundId: "DOGE9-1h-0", marketId: "DOGE9", asset: "DOGE", priceDecimals: 7, openPrice: "0.0945340", closePrice: "0.0951120" }),
    );
    expect(await screen.findByText(/DOGE closed UP — \$0\.094534 → \$0\.095112/)).toBeInTheDocument();
    expect(screen.getByText("Open price: $0.094534")).toBeInTheDocument();
    expect(screen.getByText(/Price from Hyperliquid, published by our team's key/)).toBeInTheDocument();
  });

  it("a simulated round says so", async () => {
    renderRound(settled({ source: "synthetic" }));
    expect(await screen.findByText(/Simulated demo prices \(no live source\), published by our team's key/)).toBeInTheDocument();
  });

  it("a round that names no source (an older server) and whose market is gone claims NO source — never 'Hyperliquid' by default", async () => {
    const unnamed = { ...odds(), source: undefined, kind: undefined, priceDecimals: undefined } as unknown as RoundSettled;
    renderRound(unnamed);
    expect(await screen.findByText("Published by our team's key — not a decentralized oracle.")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Hyperliquid|Polymarket/);
    // …and its price scale is read off the price strings rather than guessed as cents (an unnamed kind reads as a perp)
    expect(screen.getByText(/GER closed UP — \$0\.52300 → \$0\.53400/)).toBeInTheDocument();
  });

  it("a round of a market still on the list agrees with the list", async () => {
    renderRound(settled({ roundId: "PM900104-1h-0", marketId: "PM900104", asset: "GER", kind: "ODDS", priceDecimals: 6, source: "polymarket", openPrice: "0.620000", closePrice: "0.640000" }));
    expect(await screen.findByText(/GER closed UP — 62\.0% → 64\.0%/)).toBeInTheDocument();
  });
});

describe("Round page — the exchange a round ran on (RoundBase.source, Р-76: one round, one exchange)", () => {
  // `spot_BTC` is priced by Coinbase with Kraken behind it (the new catalogue); a round carries the exchange it runs on.
  const onSpot = (source: RoundSettled["source"], over: Partial<RoundSettled> = {}) =>
    settled({ roundId: "spot_BTC-1h-0", marketId: "spot_BTC", asset: "BTC", priceDecimals: 2, source, openPrice: "84000.00", closePrice: "84100.00", ...over });

  it("a round that ran on the backup says so — which exchange, why, and that every price is its own — beside 'Price from Kraken'", async () => {
    renderRound(onSpot("kraken"), false, [], spotCatalogue());
    expect(await screen.findByText(/BTC closed UP — \$84,000\.00 → \$84,100\.00/)).toBeInTheDocument();
    expect(screen.getByText(/^Price from Kraken, published by our team's key — not a decentralized oracle\.$/)).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent(
      "This round ran on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation were Kraken's.",
    );
  });

  it("a round on the main exchange names it and says nothing of a backup", async () => {
    renderRound(onSpot("coinbase"), false, [], spotCatalogue());
    expect(await screen.findByText(/^Price from Coinbase, published by our team's key — not a decentralized oracle\.$/)).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Backup|backup/);
  });

  it("a round of a market that has left the list still names its exchange — the 'backup' remark needs the market that says which one is main", async () => {
    renderRound(onSpot("kraken"), false, [], catalogue()); // no spot_BTC in this list
    expect(await screen.findByText(/^Price from Kraken, published by our team's key/)).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Hyperliquid|Coinbase/);
  });

  it("a running round on the backup says it runs there", async () => {
    renderRound(runningRoundOf("spot_BTC", "BTC", { source: "kraken", openPrice: "84000.00" }), false, [], spotCatalogue());
    expect(await screen.findByText(/Open price: \$84,000\.00/)).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("This round runs on Kraken, the backup exchange — Coinbase had no price when it started.");
    expect(screen.getByText(/^Price from Kraken, published by our team's key/)).toBeInTheDocument();
  });

  it("a round still waiting for its opening price is not yet on any backup: no exchange has priced it, so none is claimed", async () => {
    renderRound(runningRoundOf("spot_BTC", "BTC", { source: "coinbase", openPrice: null, positions: [], crowd: null }), false, [], spotCatalogue());
    expect(await screen.findByRole("heading", { name: "Waiting for the opening price…" })).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("HYPE: a round on Coinbase is the backup of Hyperliquid's spot market", async () => {
    renderRound(onSpot("coinbase", { roundId: "spot_HYPE-1h-0", marketId: "spot_HYPE", asset: "HYPE", priceDecimals: 3, openPrice: "87.100", closePrice: "87.235" }), false, [], spotCatalogue());
    expect(await screen.findByRole("note")).toHaveTextContent("This round ran on Coinbase, the backup exchange — Hyperliquid had no price when it started.");
  });
});

describe("Round page — the crowd's round that closed where it opened is flat, neither a win nor a loss", () => {
  const crowd = (over: Partial<CrowdRoundResultDto>): CrowdRoundResultDto => ({
    roundId: "BTC-1h-0",
    traded: true,
    direction: "UP",
    leverage: 5,
    margin: "10000.00",
    entryPrice: "100.00",
    liquidated: false,
    capitalBefore: "100000.00",
    capitalAfter: "100000.00",
    ...over,
  });

  it("says flat in words, with the zero", async () => {
    renderRound(settled({ crowd: crowd({}) }));
    expect(await screen.findByText("flat")).toBeInTheDocument();
    expect(screen.getByText(/neither a win nor a loss for the crowd account/)).toBeInTheDocument();
    expect(screen.getByText("$0.00")).toBeInTheDocument();
  });

  it.each([
    ["a win", { capitalAfter: "100250.00" }],
    ["a loss", { capitalAfter: "99700.00" }],
    ["a liquidation", { capitalAfter: "90000.00", liquidated: true }],
  ])("%s is not called flat", async (_name, over) => {
    renderRound(settled({ crowd: crowd(over) }));
    await screen.findByText("Capital before: $100,000.00");
    expect(screen.queryByText("flat")).not.toBeInTheDocument();
    expect(screen.queryByText(/neither a win nor a loss/)).not.toBeInTheDocument();
  });
});

describe("Round page — the live reference price is asked for only while a round RUNS", () => {
  function referenceCalls(round: RoundDto) {
    const referencePrice = vi.fn(async () => ({ price: "100.50", asOfMs: NOW }));
    const api = fakeApi({
      round: vi.fn(async () => round),
      markets: vi.fn(async () => marketsOf(catalogue())),
      myPositions: vi.fn(async () => []),
      referencePrice,
      previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
    });
    render(
      <MemoryRouter initialEntries={[`/round/${round.roundId}`]}>
        <Routes>
          <Route path="/round/:roundId" element={<RoundPage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, null) },
    );
    return referencePrice;
  }

  it("a settled round asks for none (its market may have closed, and then has no price: a 503 in the console every two seconds)", async () => {
    const referencePrice = referenceCalls(settled());
    await screen.findByText("SETTLED");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 100));
    });
    expect(referencePrice).not.toHaveBeenCalled();
  });

  it("a running round does", async () => {
    const referencePrice = referenceCalls(runningRoundOf("BTC", "BTC"));
    await screen.findByText("LIVE");
    expect(referencePrice).toHaveBeenCalledWith("BTC");
  });
});

describe("Round page — the SERVER's clock decides when betting is closed, not the phone's", () => {
  it("a betting round opens against the server's now: the slip and the countdown are there (the client clock is years ahead of this fixture)", async () => {
    renderRound(bettingRoundOf("BTC", "BTC"));
    expect(await screen.findByText("PREVIEW — not signed in")).toBeInTheDocument();
    expect(screen.queryByText("Betting just closed for this round.")).not.toBeInTheDocument();
    // ten hours on the server's clock (the offset is read to a quarter second, so it may already show 9:59:5x)
    expect(screen.getByText(/Closes in (10:00:0\d|9:59:5\d)/)).toBeInTheDocument();
  });
});

describe("Round page — a failed read", () => {
  const engineNotReady = () => new ApiRequestError("ENGINE_NOT_READY", "the game engine is recovering — nothing was changed, please try again in a moment");

  function renderWith(round: (id: string) => Promise<RoundDto>) {
    const api = fakeApi({
      round: vi.fn(round),
      markets: vi.fn(async () => marketsOf(catalogue())),
      referencePrice: vi.fn(async () => ({ price: "100.50", asOfMs: NOW })),
      myPositions: vi.fn(async () => []),
    });
    render(
      <MemoryRouter initialEntries={["/round/BTC-1h-0"]}>
        <Routes>
          <Route path="/round/:roundId" element={<RoundPage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, null) },
    );
    return api;
  }

  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => vi.useRealTimers());

  it("a server that is restarting (503) is not an error: the skeleton stays, no error card, and the page loads by itself when it is back", async () => {
    let up = false;
    renderWith(async () => {
      if (!up) throw engineNotReady();
      return settled();
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText("Something went wrong.")).not.toBeInTheDocument();
    up = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(await screen.findByText("SETTLED")).toBeInTheDocument();
  });

  it("…and a round already on screen stays on screen while the server restarts", async () => {
    let down = false;
    renderWith(async () => {
      if (down) throw engineNotReady();
      return settled();
    });
    expect(await screen.findByText("SETTLED")).toBeInTheDocument();
    down = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4200);
    });
    expect(screen.getByText("SETTLED")).toBeInTheDocument();
    expect(screen.queryByText("Something went wrong.")).not.toBeInTheDocument();
  });

  it("an unknown round (404 NOT_FOUND) says so instead of 'Something went wrong'", async () => {
    renderWith(async () => {
      throw new ApiRequestError("NOT_FOUND", "unknown round BTC-1h-0");
    });
    expect(await screen.findByText("This round doesn't exist.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("any other failure keeps the generic error with a retry", async () => {
    renderWith(async () => {
      throw new Error("Network error — check your connection and try again.");
    });
    expect(await screen.findByText("Something went wrong.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});
