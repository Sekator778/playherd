import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { ResultTile } from "./ResultTile";

function tile(over: Partial<Parameters<typeof ResultTile>[0]> = {}) {
  return render(
    <MemoryRouter>
      <ResultTile roundId="PM1-1h-0" symbol="GER" seriesLabel="3 MIN" direction="UP" leverage={5} net={12} liquidated={false} odds {...over} />
    </MemoryRouter>,
  );
}

describe("ResultTile — the odds tag names whose odds they are only when the tile knows its market's source", () => {
  it("a Polymarket market's tag says so", () => {
    tile({ source: "polymarket" });
    expect(screen.getByText("ODDS")).toHaveAttribute("title", "A Polymarket odds market: the result is how the odds moved");
  });

  it("a history row carries no source and its market may have left the list: the tag claims none — never 'Polymarket' by default", () => {
    tile();
    expect(screen.getByText("ODDS")).toHaveAttribute("title", "An odds market: the result is how the odds moved");
  });

  it("a simulated market's tag says simulated", () => {
    tile({ source: "synthetic" });
    expect(screen.getByText("ODDS")).toHaveAttribute("title", "A simulated odds market: the result is how the odds moved");
  });

  it("a price market has no odds tag at all", () => {
    tile({ odds: false, symbol: "BTC" });
    expect(screen.queryByText("ODDS")).not.toBeInTheDocument();
  });
});

describe("ResultTile — staked → returned, fees below (D-98 step 3, fee_display_venues §4)", () => {
  it("says what was staked and what came back, next to the net that excludes the fees", () => {
    tile({ stake: "100.000", payout: "109.200", net: 9.2 });
    expect(screen.getByRole("link")).toHaveTextContent("Staked 100 → returned 109.2");
    expect(screen.getByText("+9.20 PLAY")).toBeInTheDocument();
  });

  it("a loss reads the same way: nothing is hidden when little came back", () => {
    tile({ stake: "100.000", payout: "80.150", net: -19.85 });
    expect(screen.getByRole("link")).toHaveTextContent("Staked 100 → returned 80.15");
    expect(screen.getByText("-19.85 PLAY")).toBeInTheDocument();
  });

  it("a liquidation returns nothing", () => {
    tile({ stake: "100.000", payout: "0.000", net: -100, liquidated: true });
    expect(screen.getByRole("link")).toHaveTextContent("Staked 100 → returned 0");
  });

  it("the fees, when the caller knows them, are a small line after it", () => {
    tile({ stake: "100.000", payout: "109.200", net: 9.2, fees: 1.07 });
    expect(screen.getByRole("link")).toHaveTextContent("Staked 100 → returned 109.20fees 1.07");
  });

  it("without a stake and a payout the tile has no such line (nothing is made up)", () => {
    tile();
    expect(screen.queryByText(/Staked/)).not.toBeInTheDocument();
  });
});
