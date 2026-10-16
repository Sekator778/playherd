/**
 * The room's default view (Р-98, "game first"): the price, the chart, two big buttons — and nothing of the terminal. UP and DOWN open the bet
 * sheet; the oracle paragraph is behind an "i"; the crowd account is on the Stats page; the chart draws the player's own lines and the herd's
 * only in Pro. (`PairRoomPage.test.tsx` runs the same room in the Pro view.)
 */
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Interval, RoundsPairDto } from "../api/contract";
import { closeBetSheet, useBetSheetRequest } from "../components/betSheetState";
import { setProMode } from "../components/proMode";
import { NOW, bettingRoundOf, catalogue, crowdPosition, marketsOf, publicPosition, runningRoundOf, ticksOf } from "../components/testFixtures";
import { PairRoomPage } from "./PairRoomPage";

const MARKETS = catalogue();

function SheetProbe() {
  const request = useBetSheetRequest();
  return <p data-testid="sheet">{request ? `${request.marketId}:${request.interval}:${request.direction}` : "closed"}</p>;
}

function renderRoom(signedIn = false, running = (id: string) => runningRoundOf(id, "BTC")) {
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf(MARKETS)),
    rounds: vi.fn(async (interval: Interval, marketId: string = "BTC"): Promise<RoundsPairDto> => ({
      interval,
      running: running(marketId),
      betting: bettingRoundOf(marketId, "BTC", { upStake: "300.000", downStake: "100.000", majorityDirection: "UP" }),
    })),
    referencePrice: vi.fn(async () => ({ price: "83400.00", asOfMs: NOW })),
    priceSeries: vi.fn(async (id: string) => ({ marketId: id, priceDecimals: 2, ticks: ticksOf(300, NOW, 83400, 3_000, 2) })),
    myPositions: vi.fn(async () => []),
    myAccount: vi.fn(async () => ({ playerId: TEST_PLAYER.playerId, balance: "5000.000", currentSeason: "2026-09", inPlay: "0.000" })),
    previewLiquidation: vi.fn(async () => ({ entryPriceUsed: "100.00", liquidationPrice: "92.00", percentToLiquidation: 0.08 })),
  });
  return render(
    <MemoryRouter initialEntries={["/play/BTC"]}>
      <Routes>
        <Route path="/play/:marketId" element={<PairRoomPage />} />
      </Routes>
      <SheetProbe />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, signedIn ? TEST_PLAYER : null) },
  );
}

beforeEach(() => {
  window.localStorage.clear();
  setProMode(false);
});
afterEach(() => act(() => closeBetSheet()));

describe("Room — the default view", () => {
  it("tapping DOWN opens the sheet for this market, series and side — no slip, no herd shortcuts, no leverage dial", async () => {
    const user = userEvent.setup();
    renderRoom();
    await user.click(await screen.findByRole("button", { name: /^DOWN/ }));
    expect(screen.getByTestId("sheet")).toHaveTextContent("BTC:HOURLY:DOWN");
    expect(screen.queryByRole("button", { name: "CONNECT WALLET" })).not.toBeInTheDocument();
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
    expect(screen.queryByText("With the herd")).not.toBeInTheDocument();
    expect(screen.queryByText("Against the herd")).not.toBeInTheDocument();
  });

  it("the oracle paragraph is behind an 'i', with the way to How it works", async () => {
    const user = userEvent.setup();
    renderRoom();
    const info = await screen.findByRole("button", { name: "About the price source" });
    expect(screen.queryByText(/not a decentralized oracle/)).not.toBeInTheDocument();
    await user.click(info);
    expect(screen.getByText(/not a decentralized oracle/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "How it works" })).toHaveAttribute("href", "/how-it-works");
  });

  it("the crowd account is not in the room: one line leads to the Stats page", async () => {
    renderRoom(false, (id) => runningRoundOf(id, "BTC", { openPrice: "83400.00", crowd: crowdPosition({ entryPrice: "83400.00", liquidationPrice: "91000.00", direction: "DOWN", leverage: 7 }) }));
    const link = await screen.findByRole("link", { name: "The crowd account is on the Stats page" });
    expect(link).toHaveAttribute("href", "/stats?m=BTC");
    expect(screen.queryByText("Crowd account")).not.toBeInTheDocument();
  });

  it("the chart draws YOUR entry and wipeout lines, not the herd's", async () => {
    const own = publicPosition({ playerId: TEST_PLAYER.playerId, player: { ...TEST_PLAYER }, entryPrice: "83400.00", liquidationPrice: "75100.00", leverage: 10, direction: "UP" });
    renderRoom(true, (id) =>
      runningRoundOf(id, "BTC", { openPrice: "83400.00", positions: [own], crowd: crowdPosition({ entryPrice: "83400.00", liquidationPrice: "91000.00", direction: "DOWN", leverage: 7 }) }),
    );
    const chart = await screen.findByRole("img", { name: /BTC price/ });
    expect(chart).toHaveAccessibleName(/your wipeout \$75,100\.00/);
    expect(chart).not.toHaveAccessibleName(/herd liq/);
  });

  it("'Pro view' brings everything back: the slip, the herd shortcuts, the crowd account", async () => {
    const user = userEvent.setup();
    renderRoom();
    await screen.findByRole("button", { name: /^DOWN/ });
    await user.click(screen.getByRole("button", { name: "Pro view" }));
    expect(await screen.findByRole("button", { name: "CONNECT WALLET" })).toBeInTheDocument();
    expect(screen.getByText("With the herd")).toBeInTheDocument();
    expect(screen.getByRole("slider")).toBeInTheDocument();
    expect(within(document.body).getByRole("button", { name: "Pro view" })).toHaveAttribute("aria-pressed", "true");
  });

  it("the Pro choice is remembered in storage, and a browser without storage still switches for the page", async () => {
    const user = userEvent.setup();
    renderRoom();
    await user.click(await screen.findByRole("button", { name: "Pro view" }));
    expect(window.localStorage.getItem("herd.pro")).toBe("1");
    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage is off");
    });
    await user.click(screen.getByRole("button", { name: "Pro view" }));
    expect(screen.getByRole("button", { name: "Pro view" })).toHaveAttribute("aria-pressed", "false");
    setItem.mockRestore();
  });
});
