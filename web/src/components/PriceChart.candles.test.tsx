import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it } from "vitest";
import { PriceChart, type PriceChartProps } from "./PriceChart";
import { CHART_STYLE_KEY } from "./chart/useChartStyle";
import { NOW, ticksOf } from "./testFixtures";

const BTC = { id: "BTC", symbol: "BTC", kind: "PERP" as const, priceDecimals: 2 };
const GER = { id: "PM900104", symbol: "GER", kind: "ODDS" as const, priceDecimals: 6 };

function chart(over: Partial<PriceChartProps> = {}) {
  return render(<PriceChart market={BTC} ticks={ticksOf(300, NOW, 100)} lines={[]} loading={false} error={false} {...over} />);
}

afterEach(() => {
  window.localStorage.clear();
});

describe("PriceChart — candles", () => {
  it("a price market opens on candles: bodies and wicks, no line, and the last price still in its bubble", () => {
    const { container } = chart();
    expect(container.querySelectorAll(".pchart__candle").length).toBeGreaterThanOrEqual(20);
    expect(container.querySelectorAll(".pchart__wick").length).toBe(container.querySelectorAll(".pchart__candle").length);
    expect(container.querySelector(".pchart__line")).not.toBeInTheDocument();
    expect(container.querySelector(".pchart__bubble")).toHaveTextContent(/^\$\d+\.\d\d$/);
    expect(container.querySelector(".pchart__dot")).toBeInTheDocument();
  });

  it("the Line / Candles switch sits next to the range chips, as tabs, with the current look selected", () => {
    chart();
    const switcher = screen.getByRole("tablist", { name: "Chart style" });
    expect(within(switcher).getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Line", "Candles"]);
    expect(screen.getByRole("tab", { name: "Candles" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Line" })).toHaveAttribute("aria-selected", "false");
  });

  it("switching to the line draws the line and remembers the choice for the next chart", async () => {
    const user = userEvent.setup();
    const { container, unmount } = chart();
    await user.click(screen.getByRole("tab", { name: "Line" }));
    expect(container.querySelector(".pchart__line")).toBeInTheDocument();
    expect(container.querySelector(".pchart__candle")).not.toBeInTheDocument();
    expect(window.localStorage.getItem(CHART_STYLE_KEY)).toBe("line");
    unmount();
    const next = chart();
    expect(next.container.querySelector(".pchart__line")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Line" })).toHaveAttribute("aria-selected", "true");
  });

  it("an odds market is the line only, with no switch — even when candles are what was remembered", () => {
    window.localStorage.setItem(CHART_STYLE_KEY, "candles");
    const { container } = chart({ market: GER, ticks: ticksOf(300, NOW, 0.64, 3_000, 6) });
    expect(container.querySelector(".pchart__line")).toBeInTheDocument();
    expect(container.querySelector(".pchart__candle")).not.toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "Chart style" })).not.toBeInTheDocument();
  });

  it("the levels stay on top: round open, entry and liquidation lines and the round-start marker", () => {
    const lines = [
      { id: "open" as const, label: "ROUND START", price: 100, tone: "open" as const, faint: false },
      { id: "own-liq" as const, label: "YOUR WIPEOUT", price: 99.8, tone: "liq" as const, faint: false },
    ];
    const { container } = chart({ lines, roundStartMs: NOW - 120_000 });
    const svg = container.querySelector("svg")!;
    const children = [...svg.children];
    const lastCandle = children.findIndex((c) => c.classList.contains("pchart__candles"));
    const firstRef = children.findIndex((c) => c.classList.contains("pchart__ref"));
    expect(lastCandle).toBeGreaterThanOrEqual(0);
    expect(firstRef).toBeGreaterThan(lastCandle);
    expect(container.querySelectorAll(".pchart__ref")).toHaveLength(2);
    expect(container.querySelector(".pchart__start")).toBeInTheDocument();
  });

  it("the spoken summary says what the candles are as well as where the price went", () => {
    chart();
    const svg = screen.getByRole("img", { name: /BTC price, 5M view/ });
    expect(svg).toHaveAccessibleName(/candles of 15 s, high \$\d.*, low \$\d/);
  });

  it("a gap in the feed leaves a gap in the candles: no candle is drawn where there were no ticks", () => {
    const early = ticksOf(100, NOW - 160_000, 100); // ticks, then 40 s of silence, then ticks again
    const late = ticksOf(40, NOW, 100);
    const { container } = chart({ ticks: [...early, ...late] });
    const xs = [...container.querySelectorAll(".pchart__wick")].map((el) => Number(el.getAttribute("x1")));
    const gaps = xs.slice(1).map((x, i) => x - xs[i]);
    expect(Math.max(...gaps)).toBeGreaterThan(Math.min(...gaps) * 2);
  });
});
