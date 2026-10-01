import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, MarketDto, MyPositionDto } from "../api/contract";
import { PositionsStrip } from "./PositionsStrip";
import { POSITIONS_STRIP_COLLAPSED_KEY, positionRoomPath } from "./positionsStripModel";
import { NOW, market, marketsOf, seriesDto } from "./testFixtures";

/** BTC at x10 from 100.00: size = 1000 × 10 / 100 = 100 units, so every 1.00 of price is 100 PLAY. */
const btc = (price: string): MarketDto =>
  market("BTC", { symbol: "BTC", title: "Bitcoin", price: { price, asOfMs: NOW, change24h: 0.01, stale: false } });
const eth = market("ETH", { symbol: "ETH", title: "Ethereum", series: [seriesDto({ durationSec: 180 }), seriesDto({ interval: "DAILY", durationSec: 900 })] });

const position = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
  marketId: "BTC",
  symbol: "BTC",
  roundId: "1h-0",
  interval: "HOURLY",
  phase: "RUNNING",
  direction: "UP",
  leverage: 10,
  stake: "1000.000",
  entryPrice: "100.00",
  liquidationPrice: "90.50",
  liquidated: false,
  untilMs: NOW + 90_000,
  ...over,
});

function renderStrip(positions: MyPositionDto[], markets: MarketDto[], signedIn = true, methods: Partial<Api> = {}) {
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(markets)),
    myPositions: vi.fn(async () => positions),
    ...methods,
  });
  return render(
    <MemoryRouter>
      <PositionsStrip />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, signedIn ? TEST_PLAYER : null) },
  );
}

const strip = () => screen.getByRole("region", { name: "Your open bets and positions" });

beforeEach(() => {
  window.localStorage.clear();
});

describe("Your bets — when it shows", () => {
  it("signed out: nothing, and nothing is asked of the server — not even the market list", async () => {
    const myPositions = vi.fn(async () => [position()]);
    const markets = vi.fn(async () => marketsOf([btc("100.00")]));
    renderStrip([position()], [btc("100.00")], false, { myPositions, markets });
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
    expect(myPositions).not.toHaveBeenCalled();
    expect(markets).not.toHaveBeenCalled();
  });

  it("signed in with nothing open: nothing, and the market list is not polled for the strip's sake", async () => {
    const markets = vi.fn(async () => marketsOf([btc("100.00")]));
    renderStrip([], [btc("100.00")], true, { markets });
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
    expect(markets).not.toHaveBeenCalled();
  });

  it("signed in with bets in several markets: one strip, a card each, with a count", async () => {
    renderStrip([position(), position({ marketId: "ETH", symbol: "ETH", roundId: "ETH-1h-0", direction: "DOWN", leverage: 5 })], [btc("100.00"), eth]);
    await screen.findByRole("region", { name: "Your open bets and positions" });
    expect(within(strip()).getByText("Your bets")).toBeInTheDocument();
    expect(within(strip()).getAllByRole("link")).toHaveLength(2);
    expect(strip()).toHaveTextContent("2");
  });
});

describe("Your bets — a running position", () => {
  it("shows live P&L from the market's current price, with sign, and a thin liquidation meter", async () => {
    renderStrip([position()], [btc("100.50")]);
    const card = await screen.findByRole("link", { name: /^BTC, UP x10/ });
    expect(card).toHaveAccessibleName(/live \+50 PLAY, \d+\.\d% to liquidation/); // 100 units × 0.50
    expect(within(card).getByText("+50 PLAY")).toBeInTheDocument();
    expect(within(card).getByRole("meter", { name: "Room left before liquidation" })).toBeInTheDocument();
    expect(within(card).getByText(/stake 1,000 · 3 MIN/)).toBeInTheDocument();
    expect(within(card).getByText(/^ends \d\d:\d\d$/)).toBeInTheDocument();
  });

  it("a loss is as plain as a win: the minus sign, in full", async () => {
    renderStrip([position()], [btc("97.00")]);
    const card = await screen.findByRole("link", { name: /^BTC, UP x10/ });
    expect(within(card).getByText("-300 PLAY")).toBeInTheDocument();
    expect(card).toHaveAccessibleName(/live -300 PLAY/);
  });

  it("the header adds up the live P&L of every running bet", async () => {
    renderStrip(
      [position(), position({ marketId: "ETH", symbol: "ETH", roundId: "ETH-1h-0", direction: "DOWN", entryPrice: "100.00", liquidationPrice: "109.50", stake: "500.000", leverage: 10 })],
      [btc("101.00"), market("ETH", { symbol: "ETH", price: { price: "101.00", asOfMs: NOW, change24h: 0, stale: false } })],
    );
    // BTC long: +100 (100 units × 1.00); ETH short: size 50 × −1.00 = −50  → +50 in total.
    await screen.findByRole("region", { name: "Your open bets and positions" });
    expect(await within(strip()).findByText("+50 PLAY live")).toBeInTheDocument();
  });

  it("a short gains when the price falls", async () => {
    renderStrip([position({ direction: "DOWN", liquidationPrice: "109.50" })], [btc("99.00")]);
    const card = await screen.findByRole("link", { name: /^BTC, DOWN x10/ });
    expect(within(card).getByText("+100 PLAY")).toBeInTheDocument();
  });

  it("opens that market's room on the series the position is in", async () => {
    renderStrip([position({ interval: "DAILY" })], [btc("100.00")]);
    const card = await screen.findByRole("link", { name: /^BTC, UP x10/ });
    expect(card).toHaveAttribute("href", "/play/BTC?series=daily");
    expect(positionRoomPath({ marketId: "xyz_NVDA", interval: "HOURLY" })).toBe("/play/xyz_NVDA?series=hourly");
  });
});

describe("Your bets — a bet still waiting for its round", () => {
  it("says so, with the time to the start — and no P&L it cannot have", async () => {
    renderStrip([position({ phase: "BETTING", entryPrice: null, liquidationPrice: null, untilMs: NOW + 45_000 })], [btc("100.00")]);
    const card = await screen.findByRole("link", { name: /^BTC, UP x10/ });
    expect(within(card).getByText(/Bet placed — waits for the round to start/)).toBeInTheDocument();
    expect(within(card).getByText(/^starts \d\d:\d\d$/)).toBeInTheDocument();
    expect(within(card).queryByRole("meter")).not.toBeInTheDocument();
    expect(card).toHaveAccessibleName(/round starts in/);
  });
});

describe("Your bets — a liquidated position", () => {
  it("is shown as plainly as a win would be: the whole stake, the word, a crack", async () => {
    renderStrip([position({ liquidated: true })], [btc("85.00")]);
    const card = await screen.findByRole("link", { name: /liquidated/ });
    expect(within(card).getByText("-1,000 PLAY")).toBeInTheDocument();
    expect(within(card).getByText("liquidated")).toBeInTheDocument();
    expect(card.className).toContain("pos-card--liquidated");
    expect(within(card).queryByRole("meter")).not.toBeInTheDocument();
    expect(within(strip()).getByText("1 liquidated")).toBeInTheDocument();
  });
});

describe("Your bets — a position it cannot read yet", () => {
  it("a market with no price yet, or missing from the list, shows the position without inventing a number", async () => {
    renderStrip([position()], [market("BTC", { symbol: "BTC", price: null })]);
    const card = await screen.findByRole("link", { name: /^BTC, UP x10/ });
    expect(within(card).getByText(/Live — waiting for a price/)).toBeInTheDocument();
    expect(within(card).queryByText(/PLAY$/)).not.toBeInTheDocument();
  });
});

describe("Your bets — collapsible, remembered", () => {
  it("collapses to one line and expands again; the choice is kept", async () => {
    const user = userEvent.setup();
    renderStrip([position()], [btc("100.00")]);
    const toggle = await screen.findByRole("button", { name: /Your bets/ });
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getAllByRole("link")).toHaveLength(1);

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryAllByRole("link")).toHaveLength(0);
    expect(window.localStorage.getItem(POSITIONS_STRIP_COLLAPSED_KEY)).toBe("1");

    await user.click(toggle);
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(window.localStorage.getItem(POSITIONS_STRIP_COLLAPSED_KEY)).toBe("0");
  });

  it("opens collapsed when it was left collapsed", async () => {
    window.localStorage.setItem(POSITIONS_STRIP_COLLAPSED_KEY, "1");
    renderStrip([position()], [btc("100.00")]);
    const toggle = await screen.findByRole("button", { name: /Your bets/ });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });

  it("even collapsed, the one line carries the live total", async () => {
    window.localStorage.setItem(POSITIONS_STRIP_COLLAPSED_KEY, "1");
    renderStrip([position()], [btc("100.50")]);
    expect(await screen.findByText("+50 PLAY live")).toBeInTheDocument();
  });
});

describe("Your bets — it is in the page, not on top of it", () => {
  it("is not positioned fixed or sticky, so it can never cover a bet slip's primary button", async () => {
    const css = await import("./PositionsStrip.css?raw");
    const text = (css as unknown as { default: string }).default;
    expect(text).not.toMatch(/position:\s*(fixed|sticky)/);
  });
});

describe("Your bets — it follows the server's list", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("a position that settles leaves the strip on the next poll", async () => {
    const myPositions = vi.fn<Api["myPositions"]>().mockResolvedValueOnce([position()]).mockResolvedValue([]);
    renderStrip([], [btc("100.00")], true, { myPositions });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getAllByRole("link")).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });
});
