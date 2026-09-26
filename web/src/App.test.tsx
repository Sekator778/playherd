import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import App from "./App";
import { ApiProvider } from "./api/ApiContext";

function renderApp(initialPath = "/") {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ApiProvider>
        <App />
      </ApiProvider>
    </MemoryRouter>,
  );
}

describe("App routing — Lobby / Pair room / bottom tabs IA", () => {
  it("loads the Lobby with a live BTC tile and clearly-labelled coming-soon tiles", async () => {
    renderApp("/");
    expect(await screen.findByRole("heading", { name: "Pick a market" })).toBeInTheDocument();
    expect(await screen.findByText(/playing this round/)).toBeInTheDocument();
    // ETH/SOL are placeholders — never a fabricated live number next to them (task requirement).
    expect(screen.getAllByText(/coming soon/i).length).toBeGreaterThanOrEqual(2);
  });

  it("the BTC lobby tile links to its pair room", async () => {
    renderApp("/");
    const btcTile = await screen.findByRole("link", { name: /BTC/ });
    expect(btcTile).toHaveAttribute("href", "/play/btc");
  });

  it("loads the BTC pair room with the live arena and a bet slip", async () => {
    renderApp("/play/btc");
    expect(await screen.findByText("BTC/USD")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /UP/ })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /DOWN/ })).toBeInTheDocument();
    expect(await screen.findAllByRole("button", { name: "CONNECT WALLET" })).not.toHaveLength(0);
  });

  it("shows a coming-soon state for a pair that isn't live yet, without crashing", async () => {
    renderApp("/play/eth");
    expect(await screen.findByText("ETH — coming soon")).toBeInTheDocument();
  });

  it("renders the static How it works page without any backend call", () => {
    renderApp("/how-it-works");
    expect(screen.getByRole("heading", { level: 1, name: "How it works" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Leverage and liquidation" })).toBeInTheDocument();
  });

  it("shows a 404 state for an unknown route", () => {
    renderApp("/this-route-does-not-exist");
    expect(screen.getByText("Page not found.")).toBeInTheDocument();
  });

  it("loads the Stats tab's Crowd view", async () => {
    renderApp("/stats");
    expect(await screen.findByRole("heading", { name: "Crowd account vs the market" })).toBeInTheDocument();
    expect(screen.getByText(/Price from Hyperliquid/)).toBeInTheDocument();
  });

  it("/crowd redirects to the Stats tab's Crowd view", async () => {
    renderApp("/crowd");
    expect(await screen.findByRole("heading", { name: "Crowd account vs the market" })).toBeInTheDocument();
  });

  it("loads the Stats tab's Ratings view", async () => {
    renderApp("/stats/ratings");
    expect(await screen.findByRole("heading", { name: "Ratings" })).toBeInTheDocument();
    expect(screen.getByText(/we don't detect multiple accounts/)).toBeInTheDocument();
  });

  it("/ratings redirects to the Stats tab's Ratings view", async () => {
    renderApp("/ratings");
    expect(await screen.findByRole("heading", { name: "Ratings" })).toBeInTheDocument();
  });

  it("the Profile tab (/profile/me) prompts to connect a wallet when signed out", () => {
    renderApp("/profile/me");
    expect(screen.getByText("Connect your wallet to see your profile.")).toBeInTheDocument();
  });

  it("Р-63: the Lobby and Pair room carry no top DEMO/replay banner", async () => {
    renderApp("/");
    await screen.findByRole("heading", { name: "Pick a market" });
    expect(screen.queryByText(/DEMO — PLAY is toy money/)).not.toBeInTheDocument();

    renderApp("/play/btc");
    await screen.findByText("BTC/USD");
    expect(screen.queryByText(/DEMO — PLAY is toy money/)).not.toBeInTheDocument();
  });

  it("keeps the honest 'PLAY is test money' fact reachable on Wallet and How it works", () => {
    renderApp("/wallet");
    expect(screen.getByText(/PLAY.*test tokens/)).toBeInTheDocument();

    renderApp("/how-it-works");
    expect(screen.getByRole("heading", { name: "PLAY is not real money" })).toBeInTheDocument();
  });
});
