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

describe("App routing and the demo banner", () => {
  it("shows the honest demo-data banner on every page (task requirement)", () => {
    renderApp("/how-it-works");
    expect(
      screen.getByText("DEMO — PLAY is toy money on a test network. Nothing here is real funds."),
    ).toBeInTheDocument();
  });

  it("loads the Home page's round block from the mock API", async () => {
    renderApp("/");
    expect(await screen.findByText("Current round")).toBeInTheDocument();
    // Not signed in by default — the bet slip is replaced by its own connect-wallet CTA
    // (ux_spec §3), alongside the header's own CONNECT WALLET button — two, by design.
    expect(await screen.findAllByRole("button", { name: "CONNECT WALLET" })).toHaveLength(2);
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

  it("loads the public Crowd account page", async () => {
    renderApp("/crowd");
    expect(await screen.findByRole("heading", { name: "Crowd account vs the market" })).toBeInTheDocument();
    expect(screen.getByText(/Price from Hyperliquid/)).toBeInTheDocument();
  });

  it("loads the Ratings page with the multi-account disclosure", async () => {
    renderApp("/ratings");
    expect(await screen.findByRole("heading", { name: "Ratings" })).toBeInTheDocument();
    expect(screen.getByText(/we don't detect multiple accounts/)).toBeInTheDocument();
  });

  it("requires a wallet before showing the own profile page", () => {
    renderApp("/profile/me");
    expect(screen.getByText("Connect your wallet to see your profile.")).toBeInTheDocument();
  });
});
