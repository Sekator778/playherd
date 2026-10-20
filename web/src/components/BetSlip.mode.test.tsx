import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { PlayerAccountDto } from "../api/contract";
import { BetSlip } from "./BetSlip";
import { loadBetMode } from "./betModeModel";
import { closeBetSheet, useBetSheetRequest } from "./betSheetState";
import { NOW, bettingRoundOf, market, marketsOf, seriesDto } from "./testFixtures";

/** Р-106: the Pro slip pays in the percentage mode; its "Win or lose" chip hands the bet over to the bet sheet in the fixed mode. */

const account: PlayerAccountDto = { playerId: TEST_PLAYER.playerId, balance: "5000.000", currentSeason: "2026-10", inPlay: "0.000" };

function SheetProbe() {
  const request = useBetSheetRequest();
  return <output data-testid="sheet">{request ? `${request.marketId} ${request.interval} ${request.direction}` : "closed"}</output>;
}

function renderSlip(kind: "PERP" | "ODDS") {
  const btc = market("BTC", { kind, series: [seriesDto({ maxLeverage: 100 })] });
  const api = fakeApi({
    markets: vi.fn(async () => marketsOf([btc])),
    myAccount: vi.fn(async () => account),
    previewLiquidation: vi.fn(async () => ({
      entryPriceUsed: "100.00",
      liquidationPrice: "95.00",
      percentToLiquidation: 0.05,
      feeHerd: "0.050",
      feeNetwork: "0.100",
      totalDebit: "100.150",
      maxLeverageForStake: 100,
    })),
  });
  render(
    <MemoryRouter>
      <BetSlip round={bettingRoundOf("BTC", "BTC")} nowMs={NOW} onChanged={() => {}} />
      <SheetProbe />
    </MemoryRouter>,
    { wrapper: apiWrapper(api, TEST_PLAYER) },
  );
}

afterEach(() => {
  closeBetSheet();
  window.localStorage.clear();
});

describe("BetSlip — the payout mode switch (Р-106)", () => {
  it("shows Win or lose next to %, and Win or lose opens the bet sheet in the fixed mode for this round", async () => {
    window.localStorage.setItem("herd.betMode", "PERCENTAGE");
    renderSlip("PERP");
    const winOrLose = await screen.findByRole("button", { name: "Win or lose" });
    expect(screen.getByRole("button", { name: "%" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(winOrLose);
    expect(loadBetMode()).toBe("FIXED");
    expect(screen.getByTestId("sheet")).toHaveTextContent(/^BTC HOURLY (UP|DOWN)$/);
  });

  it("has no switch on an odds market, where only the percentage mode is offered", async () => {
    renderSlip("ODDS");
    await screen.findByRole("slider");
    expect(screen.queryByRole("button", { name: "Win or lose" })).toBeNull();
  });
});
