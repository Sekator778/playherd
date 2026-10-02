import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ResultMomentHost } from "../fx/ResultMomentHost";
import { setForcedReducedMotion } from "../fx/reducedMotion";
import { LabStatesPage } from "./LabStatesPage";

function renderLab() {
  return render(
    <MemoryRouter>
      <LabStatesPage />
      <ResultMomentHost />
    </MemoryRouter>,
    { wrapper: apiWrapper(fakeApi({})) },
  );
}

describe("/lab/states — the designed states are all drawn", () => {
  it("market cards: kick-off before and after, the odds in points, a closed one with the server's reason and one without", () => {
    renderLab();
    const card = (symbol: string) => screen.getAllByRole("article").find((a) => within(a).queryByRole("heading", { level: 3, name: new RegExp(`^${symbol}\\b`) }))!;
    expect(within(card("NOR")).getByText(/Starts in/)).toHaveTextContent("Starts in 2h 05m");
    expect(within(card("NOR")).getByText("+1.0 pt")).toBeInTheDocument();
    expect(within(card("SWE")).getByText("LIVE")).toBeInTheDocument();
    expect(within(card("POR")).getByRole("note")).toHaveTextContent("Closed 3 min ago — Price left the 3%–97% range");
    expect(within(card("GER")).getByRole("note")).toHaveTextContent("Closed — open bets refunded. This market takes no more bets.");
  });

  it("the round with no opening price: the notice and the player's own bet in it — and one strip card, none of them with a number", () => {
    renderLab();
    expect(screen.getAllByText("Waiting for the opening price…").length).toBeGreaterThanOrEqual(3);
    expect(screen.getByText(/Your bet:/)).toHaveTextContent("UP · x15 · BTC · 500 PLAY");
  });

  it("the oracle asset line appears in the anchor states", () => {
    renderLab();
    expect(screen.getByText(/NVDA is asset #7 in the oracle record/)).toBeInTheDocument();
  });

  it("the buttons fire the mid-round liquidation moment, and the quiet summary", async () => {
    setForcedReducedMotion(true);
    try {
      const user = userEvent.setup();
      renderLab();
      await user.click(screen.getByRole("button", { name: "Fire: liquidated mid-round" }));
      expect(document.querySelector(".result-card__stamp")).toHaveTextContent("LIQUIDATED");
      expect(document.querySelector(".result-card__note")).toHaveTextContent(/Your whole stake is lost\. The round is still running/);
      await act(async () => {
        await user.click(screen.getByRole("button", { name: "Fire: the quiet summary at settlement" }));
      });
      expect(document.querySelector(".quiet-note")).toHaveTextContent("Your liquidated bet: -300 PLAY");
    } finally {
      setForcedReducedMotion(null);
    }
  });
});
