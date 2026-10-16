/**
 * The pinned "My bet" card (Р-98): the newest open bet, its three facts (the price now, side + stake + boost, the distance to the wipeout line),
 * Winning / Losing with a signed P&L, the free-cancel promise before the round starts, and the way to the other bets. Not in the Pro view.
 */
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, MarketDto, MyPositionDto } from "../api/contract";
import { MyBetDock } from "./MyBetDock";
import { newestPosition, otherPositions } from "./myBetModel";
import { setProMode } from "./proMode";
import { NOW, market, marketsOf } from "./testFixtures";

/** BTC from 100.00 at x10, stake 1000: size 100 units, so every 1.00 of price is 100 PLAY. */
const btc = (price: string): MarketDto => market("BTC", { symbol: "BTC", title: "Bitcoin", price: { price, asOfMs: NOW, change24h: 0.01, stale: false } });

const position = (over: Partial<MyPositionDto> = {}): MyPositionDto => ({
  marketId: "BTC",
  symbol: "BTC",
  roundId: "BTC-1h-0",
  interval: "HOURLY",
  durationSec: 180,
  phase: "RUNNING",
  direction: "DOWN",
  leverage: 10,
  stake: "1000.000",
  entryPrice: "100.00",
  liquidationPrice: "109.50",
  liquidated: false,
  untilMs: NOW + 90_000,
  ...over,
});

function renderDock(positions: MyPositionDto[], markets: MarketDto[] = [btc("100.00")], signedIn = true, methods: Partial<Api> = {}) {
  const api = fakeApi({ markets: vi.fn(async () => marketsOf(markets)), myPositions: vi.fn(async () => positions), ...methods });
  return render(
    <MemoryRouter>
      <MyBetDock />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, signedIn ? TEST_PLAYER : null) },
  );
}

const dock = () => screen.findByRole("region", { name: "My bet" });

beforeEach(() => {
  window.localStorage.clear();
  setProMode(false);
});
afterEach(() => setProMode(false));

describe("which bet the card shows", () => {
  it("is the one whose round starts (or started) last", () => {
    const running = position({ roundId: "r-run", phase: "RUNNING", untilMs: NOW + 90_000, durationSec: 180 }); // started NOW - 90 s
    const waiting = position({ roundId: "r-wait", phase: "BETTING", untilMs: NOW + 40_000, entryPrice: null, liquidationPrice: null });
    expect(newestPosition([running, waiting])?.roundId).toBe("r-wait");
    expect(newestPosition([waiting, running])?.roundId).toBe("r-wait");
    expect(otherPositions([running, waiting], waiting).map((p) => p.roundId)).toEqual(["r-run"]);
    expect(newestPosition([])).toBeNull();
  });
});

describe("when it shows", () => {
  it("signed out: nothing, and nothing is asked of the server", async () => {
    const myPositions = vi.fn(async () => [position()]);
    renderDock([position()], [btc("100.00")], false, { myPositions });
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("region", { name: "My bet" })).not.toBeInTheDocument();
    expect(myPositions).not.toHaveBeenCalled();
  });

  it("nothing open: nothing", async () => {
    renderDock([]);
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("region", { name: "My bet" })).not.toBeInTheDocument();
  });

  it("the Pro view keeps the older strip instead: the card is not drawn", async () => {
    setProMode(true);
    renderDock([position()]);
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByRole("region", { name: "My bet" })).not.toBeInTheDocument();
  });
});

describe("a bet waiting for its round", () => {
  it("says it is placed, when it starts, and that cancelling is free until then", async () => {
    renderDock([position({ phase: "BETTING", entryPrice: null, liquidationPrice: null, untilMs: NOW + 57_000 })]);
    const card = await dock();
    expect(card).toHaveAttribute("data-state", "waiting");
    expect(card).toHaveTextContent(/Bet placed\. Starts in \d\d:\d\d\. You can cancel for free until then\./);
    expect(card).toHaveTextContent("DOWN");
    expect(card).toHaveTextContent("1,000 PLAY");
    expect(card).toHaveTextContent("Boost x10");
  });

  it("cancels for free after a confirmation that names the amount", async () => {
    const cancelBet = vi.fn(async () => {});
    const user = userEvent.setup();
    renderDock([position({ phase: "BETTING", entryPrice: null, liquidationPrice: null, untilMs: NOW + 57_000 })], [btc("100.00")], true, { cancelBet });
    await dock();
    await user.click(within(await dock()).getAllByRole("button")[0]);
    await user.click(screen.getByRole("button", { name: "Cancel bet (free)" }));
    expect(screen.getByText(/1,000 PLAY comes back, fees included/)).toBeInTheDocument();
    expect(cancelBet).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Yes, cancel" }));
    expect(cancelBet).toHaveBeenCalledWith("BTC-1h-0");
  });
});

describe("a live bet", () => {
  it("losing: the price now, the signed P&L in full, the word, and the distance to the wipeout line", async () => {
    renderDock([position()], [btc("101.00")]); // a short, the price rose 1.00: -100 PLAY
    const card = await dock();
    await within(card).findByText("-100 PLAY");
    expect(card).toHaveAttribute("data-state", "live");
    expect(within(card).getByText("Losing")).toBeInTheDocument();
    expect(card).toHaveTextContent("BTC now");
    expect(card).toHaveTextContent("101.00");
    expect(card).toHaveTextContent("Wipeout line");
    expect(card).toHaveTextContent("109.50");
    expect(card).toHaveTextContent(/\d+\.\d% away/);
    expect(within(card).getByRole("meter")).toBeInTheDocument();
  });

  it("winning: the word and a plus sign", async () => {
    renderDock([position()], [btc("99.00")]);
    const card = await dock();
    await within(card).findByText("+100 PLAY");
    expect(within(card).getByText("Winning")).toBeInTheDocument();
  });

  it("near the wipeout line: it says so in words, not only in colour", async () => {
    renderDock([position()], [btc("109.00")]); // 0.5 of 9.5 left
    const card = await dock();
    await within(card).findByText("-900 PLAY");
    expect(card).toHaveTextContent("Close to the wipeout line");
  });

  it("wiped out: the whole stake, signed, with the word", async () => {
    renderDock([position({ liquidated: true })]);
    const card = await dock();
    expect(card).toHaveAttribute("data-state", "wiped");
    expect(card).toHaveTextContent("Wiped out");
    expect(card).toHaveTextContent("-1,000 PLAY");
  });

  it("no opening price yet: one calm line, no figure", async () => {
    renderDock([position({ entryPrice: null, liquidationPrice: null })]);
    const card = await dock();
    expect(card).toHaveAttribute("data-state", "waitingOpen");
    expect(card).toHaveTextContent("Waiting for the opening price");
    expect(card).not.toHaveTextContent("PLAY\n");
  });

  it("tap opens it: the entry price, what the wipeout line means, the round and the other bets", async () => {
    const user = userEvent.setup();
    renderDock(
      [position(), position({ marketId: "ETH", symbol: "ETH", roundId: "ETH-1h-0", direction: "UP", stake: "500.000", leverage: 5, untilMs: NOW + 150_000 })],
      [btc("101.00"), market("ETH", { symbol: "ETH", price: { price: "50.00", asOfMs: NOW, change24h: 0, stale: false } })],
    );
    const card = await dock();
    // The ETH bet's round started later (30 s ago against 90 s): it is the newest one; BTC is the "other".
    expect(card).toHaveTextContent("ETH");
    expect(within(card).getByLabelText("1 more open bets")).toBeInTheDocument();
    await user.click(within(card).getAllByRole("button")[0]);
    expect(card).toHaveTextContent("My other bets");
    expect(within(card).getByRole("link", { name: /BTC · 1,000 PLAY/ })).toHaveAttribute("href", "/play/BTC?series=hourly");
    expect(within(card).getByRole("link", { name: "Open this round" })).toHaveAttribute("href", "/play/ETH?series=hourly");
    expect(card).toHaveTextContent(/If ETH reaches it, you lose the whole 500 PLAY\./);
  });
});

describe("the page reserves the card's real height", () => {
  it("writes the measured height to --dock-height, follows a resize (expanded) and clears it when the card goes", async () => {
    let height = 100;
    let notify: () => void = () => {};
    vi.stubGlobal(
      "ResizeObserver",
      class {
        constructor(cb: () => void) {
          notify = cb;
        }
        observe() {}
        disconnect() {}
      },
    );
    const spy = vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(() => ({ height }) as DOMRect);
    const { unmount } = renderDock([position()]);
    await dock();
    const root = document.documentElement;
    expect(root.style.getPropertyValue("--dock-height")).toBe("100px");
    height = 260;
    notify();
    expect(root.style.getPropertyValue("--dock-height")).toBe("260px");
    unmount();
    expect(root.style.getPropertyValue("--dock-height")).toBe("");
    spy.mockRestore();
    vi.unstubAllGlobals();
  });
});
