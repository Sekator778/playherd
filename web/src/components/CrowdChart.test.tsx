import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CrowdHistoryPointDto } from "../api/contract";
import { CrowdChart } from "./CrowdChart";
import { LazyCrowdChart } from "./LazyCrowdChart";

/** `n` points a second apart (a short span, so the chart opens on ALL of them), the crowd drifting a few hundred dollars. */
function history(n: number): CrowdHistoryPointDto[] {
  return Array.from({ length: n }, (_, i) => ({
    roundId: `r${i}`,
    atMs: Date.UTC(2026, 9, 5) + 1_000 * i,
    seasonBoundary: null,
    crowdCapital: (100_000 + ((i * 7919) % 2_003) * 0.37).toFixed(2),
    holdCapital: (100_000 + ((i * 104_729) % 1_009) * 0.41).toFixed(2),
    antiCrowdCapital: (100_000 - ((i * 7919) % 2_003) * 0.37).toFixed(2),
  }));
}

describe("CrowdChart — a long history is drawn, not a reason for the page to fail", () => {
  it("150 000 points (450 000 values for the axis to scale by): the chart renders, with its range chips", () => {
    render(<CrowdChart history={history(150_000)} variant="full" symbol="BTC" />);
    expect(screen.getByRole("tablist", { name: "Chart range" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute("aria-selected", "true");
  });

  it("…and through the lazy wrapper it is the chart, not the line that says it could not be drawn", async () => {
    render(<LazyCrowdChart history={history(150_000)} variant="full" symbol="BTC" />);
    expect(await screen.findByRole("tablist", { name: "Chart range" }, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByText("This chart could not be drawn.")).not.toBeInTheDocument();
  });
});
