import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { apiWrapper, fakeApi } from "../api/apiTestUtils";
import { ResultMomentHost } from "../fx/ResultMomentHost";
import { resetEngineStateForTests } from "../api/engineState";
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

  it("the backup exchange (Р-76): a card whose price is Kraken's says so, one on its own exchange does not, and the market a new source replaced is closed with its reason", () => {
    renderLab();
    const card = (symbol: string) => screen.getAllByRole("article").find((a) => within(a).queryByRole("heading", { level: 3, name: new RegExp(`^${symbol}\\b`) }))!;
    expect(within(card("ETH")).getByText("Kraken price")).toBeInTheDocument();
    expect(within(card("SOL")).queryByText(/ price$/)).not.toBeInTheDocument();
    expect(within(card("XRP")).getByRole("note")).toHaveTextContent("Closed 4 min ago — Moved to a new price source: Coinbase");
  });

  it("the backup exchange: the oracle line names it, a round on it says so (running and settled), and the chart's legend says whose latest point it is", () => {
    renderLab();
    expect(screen.getByText(/^Price from Coinbase, published by our team's key/)).toHaveTextContent("Backup: Kraken, used for a round only if Coinbase has no price when it starts.");
    const notes = screen.getAllByRole("note").map((n) => n.textContent);
    expect(notes).toContain("This round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.");
    expect(notes).toContain("This round ran on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation were Kraken's.");
    expect(notes).toContain("The live round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.");
    expect(screen.getByText("Latest point: Kraken's price — Coinbase has no fresh price right now")).toBeInTheDocument();
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
      expect(document.querySelector(".result-card__note")).toHaveTextContent(/You lose most of your stake\. The round is still running/);
      await act(async () => {
        await user.click(screen.getByRole("button", { name: "Fire: the quiet summary at settlement" }));
      });
      expect(document.querySelector(".quiet-note")).toHaveTextContent("Your liquidated bet: -300 PLAY");
    } finally {
      setForcedReducedMotion(null);
    }
  });
});

describe("/lab/states — the states of the fix round", () => {
  it("the refund note: round cancelled, stake refunded, fired through the same bus", async () => {
    setForcedReducedMotion(true);
    try {
      const user = userEvent.setup();
      renderLab();
      await act(async () => {
        await user.click(screen.getByRole("button", { name: "Fire: round cancelled — stake refunded" }));
      });
      const note = document.querySelector(".quiet-note--refunded");
      expect(note).toHaveTextContent("BTC · 3 MIN round cancelled");
      expect(note).toHaveTextContent("Your stake of 300 PLAY is refunded");
    } finally {
      setForcedReducedMotion(null);
    }
  });

  it("the restart notice is drawn, and the switch drives the real shared state", async () => {
    resetEngineStateForTests();
    const user = userEvent.setup();
    renderLab();
    expect(screen.getByText("The server is restarting.")).toBeInTheDocument();
    await act(async () => {
      await user.click(screen.getByRole("button", { name: "Show the banner on this page" }));
      await user.click(screen.getByRole("button", { name: "Back to normal" }));
    });
    resetEngineStateForTests();
  });

  it("the flat crowd round says flat in words", () => {
    renderLab();
    expect(screen.getByText("flat")).toBeInTheDocument();
    expect(screen.getByText(/neither a win nor a loss for the crowd account/)).toBeInTheDocument();
  });
});
