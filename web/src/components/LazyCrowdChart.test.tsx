/**
 * The crowd chart is loaded lazily and may fail in two ways the page must survive: its code throws while it renders
 * (`CrowdChart` is replaced here by one that does), or its chunk cannot be loaded at all.
 */
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CrowdHistoryPointDto } from "../api/contract";
import { LazyCrowdChart } from "./LazyCrowdChart";

const chart = vi.hoisted(() => ({ mode: "throws" as "throws" | "fine" }));

vi.mock("./CrowdChart", () => ({
  CrowdChart: () => {
    if (chart.mode === "throws") throw new RangeError("Maximum call stack size exceeded");
    return <p>the chart</p>;
  },
}));

const history = (n: number): CrowdHistoryPointDto[] =>
  Array.from({ length: n }, (_, i) => ({
    roundId: `r${i}`,
    atMs: 1_000 * i,
    seasonBoundary: null,
    crowdCapital: "100000.00",
    holdCapital: "100000.00",
    antiCrowdCapital: "100000.00",
  }));

let consoleError: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  chart.mode = "throws";
  consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
});
afterEach(() => consoleError.mockRestore());

describe("LazyCrowdChart — a chart that cannot be drawn does not take the app with it", () => {
  it("an error thrown while it renders leaves one line in its place, and everything around it", async () => {
    render(
      <div>
        <header>the header</header>
        <LazyCrowdChart history={history(3)} variant="full" symbol="BTC" />
        <footer>the tab bar</footer>
      </div>,
    );
    expect(await screen.findByText("This chart could not be drawn.")).toBeInTheDocument();
    expect(screen.getByText("the header")).toBeInTheDocument();
    expect(screen.getByText("the tab bar")).toBeInTheDocument();
  });

  it("new history gives it another try — the failure of one dataset is not for good", async () => {
    const { rerender } = render(<LazyCrowdChart history={history(3)} variant="full" />);
    expect(await screen.findByText("This chart could not be drawn.")).toBeInTheDocument();
    chart.mode = "fine";
    rerender(<LazyCrowdChart history={history(4)} variant="full" />);
    expect(await screen.findByText("the chart")).toBeInTheDocument();
    expect(screen.queryByText("This chart could not be drawn.")).not.toBeInTheDocument();
  });
});
