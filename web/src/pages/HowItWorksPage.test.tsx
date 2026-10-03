import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { HowItWorksPage } from "./HowItWorksPage";

function renderPage() {
  return render(
    <MemoryRouter>
      <HowItWorksPage />
    </MemoryRouter>,
  );
}

describe("How it works — where the prices come from (Р-74, Р-76)", () => {
  it("names every source: a line for Coinbase, Kraken, Hyperliquid and Polymarket, each with our key", () => {
    renderPage();
    for (const origin of ["Price from Coinbase", "Price from Kraken", "Price from Hyperliquid", "Odds from Polymarket"]) {
      expect(screen.getByText(`${origin}, published by our team's key — not a decentralized oracle.`)).toBeInTheDocument();
    }
  });

  it("says which exchange prices what — crypto on an exchange where it trades, the rest on Hyperliquid — and not that every price is a Hyperliquid perpetual", () => {
    renderPage();
    const markets = screen.getByRole("heading", { name: "Markets" }).closest("section")!;
    expect(markets).toHaveTextContent("a crypto asset's price on an exchange where it is traded (Coinbase; Hyperliquid's own spot market for HYPE)");
    expect(markets).toHaveTextContent("a stock's, an index's or a commodity's as a Hyperliquid perpetual");
    expect(markets).not.toHaveTextContent("follows the price of a Hyperliquid perpetual in dollars");
  });

  it("explains the backup exchange: one exchange per round, chosen when it starts, never a mix — and that the open can differ from the last close", () => {
    renderPage();
    const source = screen.getByRole("heading", { name: "Where the price comes from" }).closest("section")!;
    expect(source).toHaveTextContent("Prices come straight from the exchanges — Coinbase and Hyperliquid, with Kraken and Coinbase as backups — and odds from Polymarket");
    expect(source).toHaveTextContent("A round runs on one exchange.");
    expect(source).toHaveTextContent("Kraken for Bitcoin, Ethereum, Solana, XRP and Dogecoin; Coinbase for HYPE");
    expect(source).toHaveTextContent("the main one if it has a live price at that moment, otherwise the backup");
    expect(source).toHaveTextContent("come from that exchange alone, never a mix");
    expect(source).toHaveTextContent("can open a little away from where the previous round closed");
    expect(source).toHaveTextContent("shows the backup's price, marked with its name");
  });

  it("keeps the rest of its promises: no decentralized oracle, an hour to a refund", () => {
    renderPage();
    expect(screen.getByText(/There is no decentralized price oracle here/)).toBeInTheDocument();
    expect(screen.getByText(/If a reliable price can't be read within an hour of a round closing/)).toBeInTheDocument();
  });
});
