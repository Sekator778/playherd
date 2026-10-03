import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, Interval, MarketDto, MyPositionDto, RoundBetting, RoundRunning, RoundsPairDto } from "../api/contract";
import { reloadFavoritesFromStorage } from "../api/favorites";
import {
  NOW,
  bettingRoundOf,
  catalogue,
  crowdPosition,
  market,
  marketsOf,
  publicPosition,
  runningRoundOf,
  seriesDto,
  spotCatalogue,
  ticksOf,
} from "../components/testFixtures";
import { PairRoomPage } from "./PairRoomPage";

const MARKETS = catalogue();
const BTC = MARKETS[0];

interface RoomSetup {
  markets?: MarketDto[];
  betting?: (marketId: string, interval: Interval) => RoundBetting;
  running?: (marketId: string, interval: Interval) => RoundRunning;
  positions?: MyPositionDto[];
  price?: string;
  methods?: Partial<Api>;
}

function roomApi(setup: RoomSetup = {}) {
  const markets = setup.markets ?? MARKETS;
  const find = (id: string) => markets.find((m) => m.id === id)!;
  const rounds = vi.fn(async (interval: Interval, marketId: string = "BTC"): Promise<RoundsPairDto> => {
    const m = find(marketId);
    return {
      interval,
      running: setup.running ? setup.running(marketId, interval) : runningRoundOf(marketId, m.symbol),
      betting: setup.betting ? setup.betting(marketId, interval) : bettingRoundOf(marketId, m.symbol),
    };
  });
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(markets)),
    rounds,
    referencePrice: vi.fn(async (id: string) => ({ price: setup.price ?? find(id).price?.price ?? "100.00", asOfMs: NOW })),
    priceSeries: vi.fn(async (id: string) => ({ marketId: id, priceDecimals: find(id).priceDecimals, ticks: ticksOf(300, NOW, Number(find(id).price?.price ?? 100), 3_000, find(id).priceDecimals) })),
    myPositions: vi.fn(async () => setup.positions ?? []),
    myAccount: vi.fn(async () => ({ playerId: TEST_PLAYER.playerId, balance: "5000.000", currentSeason: "2026-09", inPlay: "0.000" })),
    previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
    ...setup.methods,
  });
  return { api, rounds };
}

function renderRoom(path: string, setup: RoomSetup = {}, signedIn = false) {
  const { api, rounds } = roomApi(setup);
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/play/:marketId" element={<PairRoomPage />} />
        <Route path="/" element={<p>lobby</p>} />
      </Routes>
      {/* Links the positions strip and the lobby cards would render, to try a query change in an open room. */}
      <nav>
        <Link to="/play/BTC?series=daily">strip: BTC daily</Link>
        <Link to="/play/BTC?series=hourly">strip: BTC hourly</Link>
        <Link to="/play/BTC?side=down">card: BTC down</Link>
        <Link to="/play/ETH?side=down">card: ETH down</Link>
      </nav>
    </MemoryRouter>,
    { wrapper: apiWrapper(api, signedIn ? TEST_PLAYER : null) },
  );
  return { api, rounds };
}

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  reloadFavoritesFromStorage();
});

describe("Room — header", () => {
  it("a perp: ticker/USD, name, live price with its 24 h change, and no odds note", async () => {
    renderRoom("/play/BTC");
    expect(await screen.findByText("BTC/USD")).toBeInTheDocument();
    expect(screen.getByText("Bitcoin")).toBeInTheDocument();
    expect(await screen.findByText("$83,451.00")).toBeInTheDocument();
    expect(screen.getByText(/\+0\.98%/)).toBeInTheDocument();
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.getByText(/Price from Hyperliquid, published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
  });

  it("an odds market: the question, the outcome, a percentage price — and ONE honest line about what a bet is", async () => {
    renderRoom("/play/PM900104");
    expect(await screen.findByText("GER")).toBeInTheDocument();
    expect(screen.getByText("ODDS")).toBeInTheDocument();
    expect(screen.getByText("Will Germany win against Serbia?")).toBeInTheDocument();
    expect(screen.getByText("Germany · UEFA Nations League")).toBeInTheDocument();
    expect(await screen.findByText("64.0%")).toBeInTheDocument();
    const note = screen.getByRole("note");
    expect(note).toHaveTextContent("You bet on how the odds move during the round — not on who wins.");
    expect(screen.getByText(/Odds from Polymarket, published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
    expect(screen.queryByText(/Price from Hyperliquid/)).not.toBeInTheDocument();
  });

  it("an unknown 24 h change is a dash, not a made-up zero", async () => {
    renderRoom("/play/PM900104");
    await screen.findByText("64.0%");
    expect(screen.getByText("24h").closest(".room-change")).toHaveTextContent("— 24h");
    expect(screen.getByText("24h").closest(".room-change")).not.toHaveTextContent("0.00%");
  });

  it("the star keeps the market as a favourite", async () => {
    const user = userEvent.setup();
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    const star = screen.getByRole("button", { name: "Add BTC to favorites" });
    await user.click(star);
    expect(screen.getByRole("button", { name: "Remove BTC from favorites" })).toHaveAttribute("aria-pressed", "true");
    expect(JSON.parse(window.localStorage.getItem("herd.favorites") ?? "[]")).toEqual(["BTC"]);
  });

  it("the back arrow falls back to the market's own panel of the lobby", async () => {
    renderRoom("/play/xyz_NVDA");
    await screen.findByText("NVDA/USD");
    expect(screen.getByRole("link", { name: "Back" })).toHaveAttribute("href", "/?c=stocks");
  });

  it("a stale price says so", async () => {
    renderRoom("/play/xyz_NVDA");
    await screen.findByText("NVDA/USD");
    expect(screen.getByText("Price is stale")).toBeInTheDocument();
  });

  it("remembers the market so Stats can open on it", async () => {
    renderRoom("/play/xyz_NVDA");
    await screen.findByText("NVDA/USD");
    expect(window.sessionStorage.getItem("herd.lastMarket")).toBe("xyz_NVDA");
  });

  it("a market id is matched ignoring case, as the old /play/btc links need", async () => {
    renderRoom("/play/btc");
    expect(await screen.findByText("BTC/USD")).toBeInTheDocument();
  });

  it("an unknown market, once the list is in, is said to be unknown", async () => {
    renderRoom("/play/NOPE");
    expect(await screen.findByText("Unknown market.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to Lobby" })).toHaveAttribute("href", "/");
  });
});

describe("Room — a sports game's kick-off, the odds' 24 h change", () => {
  it("before kick-off the header says how long is left — 'Starts in 2h 05m' — and has no LIVE mark", async () => {
    renderRoom("/play/PM900104");
    await screen.findByText("GER");
    expect(screen.getByText(/Starts in/)).toHaveTextContent(/^Starts in 2h 0[45]m$/);
    expect(screen.queryByText("LIVE")).not.toBeInTheDocument();
  });

  it("after kick-off the header wears a LIVE mark and no longer says 'Starts in'", async () => {
    const started = MARKETS.map((m) => (m.id === "PM900104" ? { ...m, eventStartsAtMs: NOW - 30 * 60_000 } : m));
    renderRoom("/play/PM900104", { markets: started });
    await screen.findByText("GER");
    expect(await screen.findByText("LIVE")).toBeInTheDocument();
    expect(screen.queryByText(/Starts in/)).not.toBeInTheDocument();
  });

  it("a market with no kick-off says nothing of one", async () => {
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(screen.queryByText(/Starts in/)).not.toBeInTheDocument();
    expect(screen.queryByText("LIVE")).not.toBeInTheDocument();
  });

  it("an odds market's 24 h change is in points ('+1.0 pt', not '+1.56%'); a perp's stays a percentage", async () => {
    const odds = MARKETS.map((m) =>
      m.id === "PM900104" ? { ...m, price: { price: "0.650000", asOfMs: NOW - 500, change24h: 0.65 / 0.64 - 1, stale: false } } : m,
    );
    renderRoom("/play/PM900104", { markets: odds, price: "0.650000" });
    await screen.findByText("65.0%");
    const change = screen.getByText("24h").closest(".room-change") as HTMLElement;
    expect(change).toHaveTextContent("+1.0 pt 24h");
    expect(change).not.toHaveTextContent("%");
    expect(within(change).getByText("24 hour change in percentage points")).toBeInTheDocument();
  });
});

describe("Room — leverage is capped at the market's own maximum (Т-4)", () => {
  it.each([
    ["BTC", "40"],
    ["ETH", "25"],
    ["xyz_NVDA", "20"],
    ["PM900101", "10"],
  ])("%s: the dial runs 2…%s and says so", async (id, max) => {
    renderRoom(`/play/${id}`);
    const dial = await screen.findByLabelText("Leverage");
    expect(dial).toHaveAttribute("min", "2");
    expect(dial).toHaveAttribute("max", max);
    expect(screen.getByText(new RegExp(`max x${max}`))).toBeInTheDocument();
  });
});

describe("Room — the arena, the side from the lobby card, the herd", () => {
  const split = (up: string, down: string, majority: "UP" | "DOWN" | null) => (id: string) =>
    bettingRoundOf(id, market(id).symbol, { participantCount: 9, upStake: up, downStake: down, majorityDirection: majority, upCount: 6, downCount: 3 });

  it("?side=down preselects DOWN, UP is the default", async () => {
    const down = renderRoom("/play/BTC?side=down");
    expect(await screen.findByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /^UP/ })).toHaveAttribute("aria-pressed", "false");
    expect(down.api).toBeDefined();
  });

  it("UP is preselected without a side in the URL", async () => {
    renderRoom("/play/BTC");
    expect(await screen.findByRole("button", { name: /^UP/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("the halves show the live split and the player counts", async () => {
    renderRoom("/play/BTC", { betting: split("750.000", "250.000", "UP") });
    const up = await screen.findByRole("button", { name: /^UP/ });
    expect(up).toHaveTextContent("75%");
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveTextContent("25%");
    expect(screen.getByText(/9 players have voted/)).toHaveTextContent("6 UP / 3 DOWN");
  });

  it("With the herd / Against the herd move the arena's selection to the majority side / the other", async () => {
    const user = userEvent.setup();
    renderRoom("/play/BTC?side=down", { betting: split("900.000", "100.000", "UP") });
    const withHerd = await screen.findByRole("button", { name: /With the herd/ });
    const against = screen.getByRole("button", { name: /Against the herd/ });
    expect(withHerd).toHaveTextContent("UP");
    expect(against).toHaveTextContent("DOWN");
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");

    await user.click(withHerd);
    expect(screen.getByRole("button", { name: /^UP/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "false");

    await user.click(against);
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("with no majority yet both herd buttons are off, with a hint", async () => {
    renderRoom("/play/BTC", { betting: split("0.000", "0.000", null) });
    expect(await screen.findByRole("button", { name: /With the herd/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Against the herd/ })).toBeDisabled();
    expect(screen.getByText(/No herd yet/)).toBeInTheDocument();
    // No percentages on the halves either — never an invented 50/50.
    expect(screen.getByRole("button", { name: /^UP/ })).not.toHaveTextContent("%");
  });
});

describe("Room — the series are named by their real length", () => {
  it("chips say 3 MIN and 15 MIN (the market's durationSec), never Hourly/Daily", async () => {
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    const tabs = within(await screen.findByRole("tablist", { name: "Round interval" })).getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual(["3 MIN", "15 MIN"]);
    expect(screen.queryByText(/Hourly|Daily/)).not.toBeInTheDocument();
  });

  it("a market the server lists without its series still never says Hourly/Daily — the chips say what they are, shorter and longer", async () => {
    const bare = MARKETS.map((m) => (m.id === "BTC" ? { ...m, series: [] } : m));
    renderRoom("/play/BTC", { markets: bare });
    await screen.findByText("BTC/USD");
    const tabs = within(await screen.findByRole("tablist", { name: "Round interval" })).getAllByRole("tab");
    expect(tabs.map((t) => t.textContent)).toEqual(["Shorter", "Longer"]);
    expect(screen.queryByText(/Hourly|Daily/)).not.toBeInTheDocument();
  });

  it("picking the longer series loads its rounds; ?series=daily starts there", async () => {
    const user = userEvent.setup();
    const { rounds } = renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    await user.click(await screen.findByRole("tab", { name: "15 MIN" }));
    expect(rounds).toHaveBeenCalledWith("DAILY", "BTC");
  });

  it("?series=daily opens on the longer series", async () => {
    const { rounds } = renderRoom("/play/BTC?series=daily");
    await screen.findByText("BTC/USD");
    await screen.findByRole("tab", { name: "15 MIN" });
    expect(rounds).toHaveBeenCalledWith("DAILY", "BTC");
    expect(rounds).not.toHaveBeenCalledWith("HOURLY", "BTC");
    expect(screen.getByRole("tab", { name: "15 MIN" })).toHaveAttribute("aria-selected", "true");
  });

  it("a link into the SAME room with another series (the positions strip) switches the series — and only then", async () => {
    const user = userEvent.setup();
    const { rounds } = renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(screen.getByRole("tab", { name: "3 MIN" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("link", { name: "strip: BTC daily" }));
    expect(await screen.findByRole("tab", { name: "15 MIN" })).toHaveAttribute("aria-selected", "true");
    expect(rounds).toHaveBeenCalledWith("DAILY", "BTC");

    // The player's own tap on a chip is not overridden by the URL they are already on.
    await user.click(screen.getByRole("tab", { name: "3 MIN" }));
    expect(screen.getByRole("tab", { name: "3 MIN" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("link", { name: "strip: BTC hourly" }));
    expect(screen.getByRole("tab", { name: "3 MIN" })).toHaveAttribute("aria-selected", "true");
  });

  it("a link with another side switches the arena; moving to another market starts that room from its own link", async () => {
    const user = userEvent.setup();
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(screen.getByRole("button", { name: /^UP/ })).toHaveAttribute("aria-pressed", "true");
    await user.click(screen.getByRole("link", { name: "card: BTC down" }));
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");

    await user.click(screen.getByRole("link", { name: "card: ETH down" }));
    expect(await screen.findByText("ETH/USD")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");
  });

  it("a series where the player has a bet open wears a dot, announced in words", async () => {
    const position: MyPositionDto = {
      marketId: "BTC",
      symbol: "BTC",
      roundId: "BTC-1d-0",
      interval: "DAILY",
      durationSec: 900,
      phase: "BETTING",
      direction: "UP",
      leverage: 5,
      stake: "100.000",
      entryPrice: null,
      liquidationPrice: null,
      liquidated: false,
      untilMs: NOW + 1000,
    };
    renderRoom("/play/BTC", { positions: [position] }, true);
    await screen.findByText("BTC/USD");
    const dot = await screen.findByRole("img", { name: "1 of your bets open in this series" });
    expect(within(screen.getByRole("tab", { name: /15 MIN/ })).getByRole("img", { name: /bets open/ })).toBe(dot);
    expect(within(screen.getByRole("tab", { name: /3 MIN/ })).queryByRole("img")).not.toBeInTheDocument();
  });
});

describe("Room — the live chart", () => {
  it("draws the price with the ranges the data allows, and the round's open price as a line", async () => {
    renderRoom("/play/BTC", { running: (id) => runningRoundOf(id, "BTC", { openPrice: "83400.00" }) });
    const chart = await screen.findByRole("img", { name: /BTC price, 5M view/ });
    expect(chart).toHaveAccessibleName(/round opened at \$83,400\.00/);
    const ranges = within(screen.getByRole("tablist", { name: "Chart range" })).getAllByRole("tab");
    expect(ranges.map((r) => r.textContent)).toEqual(["1M", "5M", "15M"]);
    expect(screen.getByText(/Round open — every bet's entry price/)).toBeInTheDocument();
    expect(screen.getByText(/vs round open/)).toBeInTheDocument();
  });

  it("shows the player's own entry and liquidation, and the herd's fainter pair", async () => {
    const own = publicPosition({ playerId: TEST_PLAYER.playerId, player: { ...TEST_PLAYER }, entryPrice: "83400.00", liquidationPrice: "75100.00", leverage: 10, direction: "UP" });
    renderRoom(
      "/play/BTC",
      {
        running: (id) => runningRoundOf(id, "BTC", { openPrice: "83400.00", positions: [own], crowd: crowdPosition({ entryPrice: "83400.00", liquidationPrice: "91000.00", direction: "DOWN", leverage: 7 }) }),
      },
      true,
    );
    const chart = await screen.findByRole("img", { name: /BTC price/ });
    expect(chart).toHaveAccessibleName(/your liq \$75,100\.00/);
    expect(chart).toHaveAccessibleName(/herd liq ▼ x7 \$91,000\.00/);
    expect(screen.getByText("Your liquidation price")).toBeInTheDocument();
    expect(screen.getByText("Herd's liquidation price")).toBeInTheDocument();
    // The entry is the open price: said once, as the player's entry.
    expect(screen.getByText("Round open = your entry price")).toBeInTheDocument();
  });

  it("without a position of the player's there is no own-liquidation line", async () => {
    renderRoom("/play/BTC", { running: (id) => runningRoundOf(id, "BTC", { openPrice: "83400.00", crowd: crowdPosition({ entryPrice: "83400.00", liquidationPrice: "91000.00" }) }) });
    await screen.findByRole("img", { name: /BTC price/ });
    expect(screen.queryByText("Your liquidation price")).not.toBeInTheDocument();
    expect(screen.getByText("Herd's liquidation price")).toBeInTheDocument();
  });

  it("an odds market's chart reads in percent", async () => {
    renderRoom("/play/PM900104", { running: (id) => runningRoundOf(id, "GER", { openPrice: "0.620000" }) });
    const chart = await screen.findByRole("img", { name: /GER price/ });
    expect(chart).toHaveAccessibleName(/round opened at 62\.0%/);
    expect(screen.getByText(/ pt$/)).toBeInTheDocument(); // the move since open, in points
  });
});

describe("Room — a position in the running round", () => {
  it("shows the player's live P&L, the room left before liquidation and the prices that matter", async () => {
    const own = publicPosition({
      playerId: TEST_PLAYER.playerId,
      player: { ...TEST_PLAYER },
      size: "10.00000000",
      entryPrice: "100.00",
      liquidationPrice: "90.50",
      leverage: 10,
      direction: "UP",
      stake: "100.000",
    });
    renderRoom("/play/BTC", { price: "100.50", running: (id) => runningRoundOf(id, "BTC", { positions: [own] }) }, true);
    expect(await screen.findByText("unrealized")).toBeInTheDocument();
    expect(screen.getByText(/Your position:.*UP · x10 · BTC/)).toBeInTheDocument();
    expect(screen.getByText(/\+5 PLAY/)).toBeInTheDocument(); // 10 × 0.50
    const meter = screen.getByRole("meter", { name: "Room left before liquidation" });
    expect(meter).toHaveAttribute("aria-valuenow", "100");
    expect(screen.getByText(/Entry \$100\.00 · Liquidation \$90\.50/)).toBeInTheDocument();
  });

  it("a liquidated position is stated as plainly as a win would be: the whole stake", async () => {
    const own = publicPosition({ playerId: TEST_PLAYER.playerId, player: { ...TEST_PLAYER }, liquidated: true, stake: "250.000" });
    renderRoom("/play/BTC", { running: (id) => runningRoundOf(id, "BTC", { positions: [own] }) }, true);
    expect(await screen.findByText(/-250 PLAY · liquidated/)).toBeInTheDocument();
    expect(screen.getByText("You lost your whole stake.")).toBeInTheDocument();
  });
});

describe("Room — a running round with no opening price yet (RoundRunning.openPrice === null)", () => {
  const waiting = (id: string) => runningRoundOf(id, market(id).symbol, { openPrice: null, positions: [], crowd: null });
  const ownBet = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
    marketId: "BTC",
    symbol: "BTC",
    roundId: "BTC-1h-0",
    interval: "HOURLY",
    durationSec: 180,
    phase: "RUNNING",
    direction: "UP",
    leverage: 10,
    stake: "500.000",
    entryPrice: null,
    liquidationPrice: null,
    size: null,
    liquidated: false,
    untilMs: NOW + 90_000,
    ...over,
  });

  it("says 'Waiting for the opening price…' in place of the herd's position and the list of players — no zero, no NaN", async () => {
    renderRoom("/play/BTC", { running: waiting });
    expect(await screen.findByRole("heading", { name: "Waiting for the opening price…" })).toBeInTheDocument();
    expect(screen.getByText(/the price source hasn't sent its opening price yet/)).toBeInTheDocument();
    // the things that would have been wrong to show: a tie, an empty round, a price that is not there
    expect(screen.queryByRole("heading", { name: /Crowd account/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/tied this round/)).not.toBeInTheDocument();
    expect(screen.queryByText(/players? in this round/)).not.toBeInTheDocument();
    expect(screen.queryByText(/unrealized/)).not.toBeInTheDocument();
    expect(screen.queryByRole("meter")).not.toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/NaN|undefined/);
  });

  it("the chart draws no open line and no round-start marker — there is no open price to put on it", async () => {
    renderRoom("/play/BTC", { running: waiting });
    const chart = await screen.findByRole("img", { name: /BTC price/ });
    expect(chart).not.toHaveAccessibleName(/round opened at/);
    expect(screen.queryByText(/vs round open/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Round open/)).not.toBeInTheDocument();
    expect(screen.queryByText("Round start")).not.toBeInTheDocument();
  });

  it("betting on the next round is untouched — the arena and the slip are there", async () => {
    renderRoom("/play/BTC", { running: waiting });
    expect(await screen.findByRole("button", { name: /^UP/ })).toBeEnabled();
    expect(screen.getByRole("button", { name: /^DOWN/ })).toBeEnabled();
  });

  it("the player's own bet in that round is said, with the same wait — no P&L, no entry, no liquidation price", async () => {
    renderRoom("/play/BTC", { running: waiting, positions: [ownBet()] }, true);
    const card = (await screen.findByText(/Your bet:/)).closest(".card") as HTMLElement;
    expect(card).toHaveTextContent(/Your bet:.*UP · x10 · BTC · 500 PLAY/);
    expect(within(card).getByText("Waiting for the opening price…")).toBeInTheDocument();
    expect(within(card).getByText(/the price source hasn't sent its opening price yet/)).toBeInTheDocument();
    expect(within(card).queryByText(/unrealized/)).not.toBeInTheDocument();
    expect(within(card).queryByRole("meter")).not.toBeInTheDocument();
    expect(within(card).queryByText(/Entry|Liquidation/)).not.toBeInTheDocument();
    // …said once: the round-level notice is not repeated beside it
    expect(screen.queryByRole("heading", { name: "Waiting for the opening price…" })).not.toBeInTheDocument();
  });

  it("a bet in another round of this market is not shown as this round's", async () => {
    renderRoom("/play/BTC", { running: waiting, positions: [ownBet({ roundId: "BTC-1h-999" })] }, true);
    await screen.findByRole("heading", { name: "Waiting for the opening price…" });
    expect(screen.queryByText(/Your bet:/)).not.toBeInTheDocument();
  });

  it("once the price is known the usual panel is back", async () => {
    renderRoom("/play/BTC", { running: (id) => runningRoundOf(id, "BTC", { openPrice: "100.00", crowd: crowdPosition() }) });
    expect(await screen.findByRole("heading", { name: "Crowd account" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Waiting for the opening price…" })).not.toBeInTheDocument();
  });
});

describe("Room — an open market asks for its live price", () => {
  it("polls referencePrice for the room's market", async () => {
    const { api } = roomApi();
    render(
      <MemoryRouter initialEntries={["/play/BTC"]}>
        <Routes>
          <Route path="/play/:marketId" element={<PairRoomPage />} />
        </Routes>
      </MemoryRouter>,
      { wrapper: apiWrapper(api, null) },
    );
    await screen.findByText("BTC/USD");
    expect(api.referencePrice).toHaveBeenCalledWith("BTC");
  });
});

describe("Room — a closed market", () => {
  const closedBtc: MarketDto = { ...BTC, status: "CLOSED", series: [seriesDto(), seriesDto({ interval: "DAILY", durationSec: 900 })] };

  it("says it is closed, offers no bet and tells what happened to the open ones", async () => {
    renderRoom("/play/PM900199", { markets: [...MARKETS] });
    expect(await screen.findByRole("heading", { name: "This market is closed" })).toBeInTheDocument();
    expect(screen.getByText("CLOSED")).toBeInTheDocument();
    expect(screen.getByText(/refunded in full/)).toBeInTheDocument();
    expect(screen.getByText("Last price")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^UP/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "PLACE BET" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "CONNECT WALLET" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open markets in this panel" })).toHaveAttribute("href", "/?c=sports");
    expect(screen.getByRole("link", { name: "How the herd did here" })).toHaveAttribute("href", "/stats?m=PM900199");
  });

  it("asks for no price: a closed market has none to give (503 PRICE_UNAVAILABLE, every two seconds, was a console error)", async () => {
    const referencePrice = vi.fn(async () => {
      throw new Error("no price for market PM900199 yet");
    });
    renderRoom("/play/PM900199", { markets: [...MARKETS], methods: { referencePrice } });
    await screen.findByRole("heading", { name: "This market is closed" });
    expect(referencePrice).not.toHaveBeenCalled();
  });

  it("shows the server's own cancellation reason as it is", async () => {
    renderRoom("/play/BTC", {
      markets: [closedBtc, ...MARKETS.slice(1)],
      betting: () => ({
        phase: "CANCELLED",
        roundId: "BTC-1h-1",
        window: { interval: "HOURLY", startMs: NOW, endMs: NOW + 1000 },
        marketId: "BTC",
        asset: "BTC",
        cancellation: { roundId: "BTC-1h-1", reason: "Market closed: the underlying market ended.", cancelledAtMs: NOW, refundedBetCount: 3 },
      }) as unknown as RoundBetting,
    });
    expect(await screen.findByText("Market closed: the underlying market ended.")).toBeInTheDocument();
  });

  it("says the server's reason for closing as it is, and when — ahead of any round's cancellation text", async () => {
    const reasoned: MarketDto = { ...closedBtc, closedAtMs: Date.UTC(2026, 9, 1, 14, 25), closeReason: "Price left the 3%–97% range" };
    renderRoom("/play/BTC", {
      markets: [reasoned, ...MARKETS.slice(1)],
      betting: () => ({
        phase: "CANCELLED",
        roundId: "BTC-1h-1",
        window: { interval: "HOURLY", startMs: NOW, endMs: NOW + 1000 },
        marketId: "BTC",
        asset: "BTC",
        cancellation: { roundId: "BTC-1h-1", reason: "Market closed: the underlying market ended.", cancelledAtMs: NOW, refundedBetCount: 3 },
      }) as unknown as RoundBetting,
    });
    expect(await screen.findByText("Price left the 3%–97% range")).toBeInTheDocument();
    expect(screen.getByText("Closed 2026-10-01 14:25 UTC")).toBeInTheDocument();
    expect(screen.queryByText("Market closed: the underlying market ended.")).not.toBeInTheDocument();
    // the plain facts about the bets stay
    expect(screen.getByText(/refunded in full/)).toBeInTheDocument();
  });

  it("without a reason from the server there is no invented one", async () => {
    renderRoom("/play/PM900199");
    await screen.findByRole("heading", { name: "This market is closed" });
    expect(document.querySelector(".room-closed__reason")).toBeNull();
    expect(screen.queryByText(/^Closed 20/)).not.toBeInTheDocument();
  });
});

describe("Room — the exchange behind the price, and behind the round (Р-76)", () => {
  const SPOT = spotCatalogue();
  const spotRunning = (source: RoundRunning["source"], over: Partial<RoundRunning> = {}) => (marketId: string) =>
    runningRoundOf(marketId, SPOT.find((m) => m.id === marketId)!.symbol, { source, ...over });

  it("the oracle line names the exchange and its backup — and says a round runs on the backup only if the main one has no price at its start", async () => {
    renderRoom("/play/spot_BTC", { markets: SPOT });
    await screen.findByText("BTC/USD");
    expect(screen.getByText(/^Price from Coinbase, published by our team's key/)).toHaveTextContent(
      "Price from Coinbase, published by our team's key — not a decentralized oracle. Backup: Kraken, used for a round only if Coinbase has no price when it starts.",
    );
  });

  it("HYPE is priced by Hyperliquid's spot market, with Coinbase behind it", async () => {
    renderRoom("/play/spot_HYPE", { markets: SPOT });
    await screen.findByText("HYPE/USD");
    expect(screen.getByText(/^Price from Hyperliquid, published by our team's key/)).toHaveTextContent(
      "Backup: Coinbase, used for a round only if Hyperliquid has no price when it starts.",
    );
  });

  it("a market with no backup names none", async () => {
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(document.querySelector(".disclaimer-line__backup")).toBeNull();
    expect(screen.queryByText(/Backup:/)).not.toBeInTheDocument();
  });

  it("a price that is the backup's is marked beside the number, and the chart says its latest point is Kraken's", async () => {
    renderRoom("/play/spot_ETH", { markets: SPOT });
    await screen.findByText("ETH/USD");
    const mark = await screen.findByText("Kraken price");
    expect(mark).toHaveAttribute("title", "Coinbase has no fresh price right now, so this is Kraken's — its backup.");
    expect(mark.closest(".room-pricebar")).not.toBeNull();
    expect(await screen.findByText("Latest point: Kraken's price — Coinbase has no fresh price right now")).toBeInTheDocument();
  });

  it("a price from the main exchange has no mark and the chart no such caption", async () => {
    renderRoom("/play/spot_BTC", { markets: SPOT });
    await screen.findByText("BTC/USD");
    await screen.findByRole("img", { name: /BTC price/ });
    expect(document.querySelector(".room-venue")).toBeNull();
    expect(screen.queryByText(/Latest point:/)).not.toBeInTheDocument();
  });

  it("a running round on the backup says so: which exchange, why, and whose prices they are", async () => {
    renderRoom("/play/spot_BTC", { markets: SPOT, running: spotRunning("kraken") });
    await screen.findByText("BTC/USD");
    const note = await screen.findByRole("note");
    expect(note).toHaveTextContent(
      "The live round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.",
    );
  });

  it("a running round on the main exchange says nothing of the backup", async () => {
    renderRoom("/play/spot_BTC", { markets: SPOT, running: spotRunning("coinbase") });
    await screen.findByText("BTC/USD");
    await screen.findByRole("button", { name: /^UP/ });
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("the round that just settled on the backup says it ran there", async () => {
    renderRoom("/play/spot_BTC", {
      markets: SPOT,
      running: (marketId) => ({ ...runningRoundOf(marketId, "BTC", { source: "kraken" }), phase: "SETTLED", openPrice: "84000.00", closePrice: "84100.00", positions: [], crowd: null }) as unknown as RoundRunning,
    });
    expect(await screen.findByRole("note")).toHaveTextContent("The round that just ended ran on Kraken, the backup exchange");
  });

  it("an odds market's room names its source in the tag's hover text — Polymarket's here, a simulated one's elsewhere", async () => {
    renderRoom("/play/PM900104");
    await screen.findByText("GER");
    expect(screen.getByText("ODDS")).toHaveAttribute("title", "A Polymarket odds market: the price is a probability");
  });

  it("the market a new source replaced keeps its closed room: the server's reason as it sent it", async () => {
    renderRoom("/play/BTC", { markets: SPOT });
    expect(await screen.findByRole("heading", { name: "This market is closed" })).toBeInTheDocument();
    expect(screen.getByText("Moved to a new price source: Coinbase")).toBeInTheDocument();
    expect(screen.getByText(/refunded in full/)).toBeInTheDocument();
    // a closed room's last price is a last price — not marked as any exchange's
    expect(screen.getByText("Last price")).toBeInTheDocument();
    expect(document.querySelector(".room-venue")).toBeNull();
  });
});

describe("Room — states", () => {
  it("a failing rounds poll is an error with a way to retry", async () => {
    const user = userEvent.setup();
    const rounds = vi
      .fn<Api["rounds"]>()
      .mockRejectedValueOnce(new Error("offline"))
      .mockImplementation(async (interval, marketId = "BTC") => ({
        interval,
        running: runningRoundOf(marketId, "BTC"),
        betting: bettingRoundOf(marketId, "BTC"),
      }));
    renderRoom("/play/BTC", { methods: { rounds } });
    expect(await screen.findByText("Something went wrong.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("button", { name: /^UP/ })).toBeInTheDocument();
  });

  it("a rail offers the other open markets of the same panel", async () => {
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    const rail = await screen.findByRole("region", { name: "More in Crypto" });
    expect(within(rail).getByRole("link", { name: /^ETH — Ethereum/ })).toHaveAttribute("href", "/play/ETH");
    expect(within(rail).queryByRole("link", { name: /^BTC/ })).not.toBeInTheDocument();
    expect(within(rail).getByRole("link", { name: "See all →" })).toHaveAttribute("href", "/?c=crypto");
  });

  it("a market with no other open one in its panel has no rail", async () => {
    renderRoom("/play/xyz_NVDA");
    await screen.findByText("NVDA/USD");
    expect(screen.queryByRole("region", { name: /More in/ })).not.toBeInTheDocument();
  });

  it("links on to the round and to the herd's account for this market", async () => {
    renderRoom("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(screen.getByRole("link", { name: "View full round →" })).toHaveAttribute("href", "/round/BTC-1h-0");
    expect(screen.getByRole("link", { name: /How the herd is doing on BTC/ })).toHaveAttribute("href", "/stats?m=BTC");
  });

  it("while the market list has not come back it shows a skeleton, not 'unknown market'", async () => {
    const pending = new Promise<never>(() => {});
    renderRoom("/play/BTC", { methods: { markets: () => pending } });
    expect(screen.queryByText("Unknown market.")).not.toBeInTheDocument();
    expect(screen.queryByText("BTC/USD")).not.toBeInTheDocument();
  });

});
