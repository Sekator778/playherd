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
