import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { OracleDisclosureLine, RoundVenueNote } from "./Common";
import { spotCatalogue } from "./testFixtures";

describe("OracleDisclosureLine — names where the number really comes from, and our key", () => {
  it.each([
    ["coinbase", "Price from Coinbase"],
    ["kraken", "Price from Kraken"],
    ["hyperliquid", "Price from Hyperliquid"],
    ["polymarket", "Odds from Polymarket"],
    ["synthetic", "Simulated demo prices (no live source)"],
  ] as const)("%s: '%s, published by our team's key — not a decentralized oracle.'", (source, origin) => {
    render(<OracleDisclosureLine source={source} />);
    expect(screen.getByText(`${origin}, published by our team's key — not a decentralized oracle.`)).toBeInTheDocument();
  });

  it("a caller that does not know the source claims none — never 'Hyperliquid' by default", () => {
    render(<OracleDisclosureLine />);
    expect(screen.getByText("Published by our team's key — not a decentralized oracle.")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Hyperliquid|Coinbase|Kraken|Polymarket/);
  });

  it("a market with a backup exchange names it, and when a round runs on it", () => {
    render(<OracleDisclosureLine source="coinbase" backup="kraken" />);
    const line = screen.getByText(/^Price from Coinbase, published by our team's key/);
    expect(line).toHaveTextContent(
      "Price from Coinbase, published by our team's key — not a decentralized oracle. Backup: Kraken, used for a round only if Coinbase has no price when it starts.",
    );
  });

  it("the backup of HYPE is Coinbase, behind Hyperliquid", () => {
    render(<OracleDisclosureLine source="hyperliquid" backup="coinbase" />);
    expect(screen.getByText(/Backup: Coinbase, used for a round only if Hyperliquid has no price when it starts\./)).toBeInTheDocument();
  });

  it("no backup (null, absent): not a word about one", () => {
    const { rerender } = render(<OracleDisclosureLine source="polymarket" backup={null} />);
    expect(screen.getByText("Odds from Polymarket, published by our team's key — not a decentralized oracle.")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Backup/);
    rerender(<OracleDisclosureLine source="hyperliquid" />);
    expect(document.body.textContent).not.toMatch(/Backup/);
  });
});

describe("RoundVenueNote — a round on the backup exchange says so", () => {
  const [btc] = spotCatalogue();

  it("running on the backup: which exchange, why, and whose prices they are", () => {
    render(<RoundVenueNote source="kraken" market={btc} phase="RUNNING" />);
    expect(screen.getByRole("note")).toHaveTextContent(
      "This round runs on Kraken, the backup exchange — Coinbase had no price when it started. Its open and close prices and every liquidation are Kraken's.",
    );
  });

  it("settled on the backup: the same, in the past tense", () => {
    render(<RoundVenueNote source="kraken" market={btc} phase="SETTLED" />);
    expect(screen.getByRole("note")).toHaveTextContent("This round ran on Kraken, the backup exchange — Coinbase had no price when it started.");
    expect(screen.getByRole("note")).toHaveTextContent("every liquidation were Kraken's");
  });

  it("nothing for a round on the main exchange, nor for a market that is not on the list (its source line still names the exchange)", () => {
    const { rerender } = render(<RoundVenueNote source="coinbase" market={btc} phase="SETTLED" />);
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    rerender(<RoundVenueNote source="kraken" market={undefined} phase="SETTLED" />);
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    rerender(<RoundVenueNote source={undefined} market={btc} phase="RUNNING" />);
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });
});
