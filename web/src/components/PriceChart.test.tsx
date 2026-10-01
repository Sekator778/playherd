import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PriceChart, type PriceChartProps } from "./PriceChart";
import { buildChartLines } from "./priceChartModel";
import { NOW, ticksOf } from "./testFixtures";

const BTC = { id: "BTC", symbol: "BTC", kind: "PERP" as const, priceDecimals: 2 };
const GER = { id: "PM900104", symbol: "GER", kind: "ODDS" as const, priceDecimals: 6 };

function chart(over: Partial<PriceChartProps> = {}) {
  return render(<PriceChart market={BTC} ticks={ticksOf(300, NOW, 100)} lines={[]} loading={false} error={false} {...over} />);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("PriceChart — what it draws", () => {
  it("a live line with the current price in a bubble, and an accessible summary of the view", () => {
    const { container } = chart();
    const svg = screen.getByRole("img", { name: /BTC price, 5M view/ });
    expect(svg).toBeInTheDocument();
    expect(container.querySelector(".pchart__line")).toBeInTheDocument();
    expect(container.querySelector(".pchart__bubble")).toHaveTextContent(/^\$\d+\.\d\d$/);
    expect(container.querySelector(".pchart__dot")).toBeInTheDocument();
  });

  it("the final point is the reference price when that is newer than the last tick, so the bubble and the header agree", () => {
    const { container } = chart({ current: { price: "123.45", asOfMs: NOW + 2_000 } });
    expect(container.querySelector(".pchart__bubble")).toHaveTextContent("$123.45");
    const svg = screen.getByRole("img", { name: /BTC price/ });
    expect(svg).toHaveAccessibleName(/to \$123\.45/);
  });

  it("a reference price OLDER than the last tick does not move the line back", () => {
    const { container } = chart({ current: { price: "999.00", asOfMs: NOW - 60_000 } });
    expect(container.querySelector(".pchart__bubble")).not.toHaveTextContent("999");
  });

  it("an odds market reads in percent, on the bubble and on the axis", () => {
    const { container } = chart({ market: GER, ticks: ticksOf(300, NOW, 0.64, 3_000, 6) });
    expect(container.querySelector(".pchart__bubble")).toHaveTextContent(/^\d+\.\d%$/);
    expect(container.querySelectorAll(".pchart__axis")[0].textContent).toMatch(/%$|^\d\d:\d\d/);
    expect(screen.getByRole("img", { name: /GER price/ })).toHaveAccessibleName(/\d+\.\d%/);
  });
});

describe("PriceChart — the ranges the data allows", () => {
  it("15 minutes of ticks: 1M, 5M and 15M, opening on 5M", () => {
    chart();
    const chips = within(screen.getByRole("tablist", { name: "Chart range" })).getAllByRole("tab");
    expect(chips.map((c) => c.textContent)).toEqual(["1M", "5M", "15M"]);
    expect(screen.getByRole("tab", { name: "5M" })).toHaveAttribute("aria-selected", "true");
  });

  it("three minutes of ticks: only 1M — a 5M chip over three minutes would be a lie", () => {
    chart({ ticks: ticksOf(60, NOW) });
    const chips = within(screen.getByRole("tablist", { name: "Chart range" })).getAllByRole("tab");
    expect(chips.map((c) => c.textContent)).toEqual(["1M"]);
    expect(screen.getByRole("tab", { name: "1M" })).toHaveAttribute("aria-selected", "true");
  });

  it("under a minute: no chips at all, just 'Live'", () => {
    chart({ ticks: ticksOf(10, NOW) });
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.getByText("Live")).toBeInTheDocument();
  });

  it("a chip changes the view", async () => {
    const user = userEvent.setup();
    chart();
    await user.click(screen.getByRole("tab", { name: "15M" }));
    expect(screen.getByRole("tab", { name: "15M" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "5M" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("img", { name: /BTC price, 15M view/ })).toBeInTheDocument();
  });
});

describe("PriceChart — the betting lines", () => {
  it("draws the round's open price, with a label and a legend", () => {
    const { container } = chart({ lines: buildChartLines({ openPrice: "100.00" }) });
    expect(container.querySelectorAll(".pchart__ref")).toHaveLength(1);
    expect(container.querySelector(".pchart__ref-text")).toHaveTextContent("OPEN $100.00");
    expect(screen.getByText("Round open — every bet's entry price")).toBeInTheDocument();
    expect(screen.getByText(/vs round open/)).toBeInTheDocument();
  });

  it("with a position: the open line is the player's entry, and the player's and the herd's liquidation lines are drawn", () => {
    const lines = buildChartLines({
      openPrice: "100.00",
      own: { direction: "UP", leverage: 10, entryPrice: "100.00", liquidationPrice: "99.70" },
      crowd: { direction: "DOWN", leverage: 7, entryPrice: "100.00", liquidationPrice: "100.40" },
    });
    const { container } = chart({ lines });
    const labels = [...container.querySelectorAll(".pchart__ref-text")].map((t) => t.textContent);
    expect(labels).toEqual(expect.arrayContaining([expect.stringContaining("OPEN · YOUR ENTRY ▲ x10"), expect.stringContaining("YOUR LIQ"), expect.stringContaining("HERD LIQ ▼ x7")]));
    expect(container.querySelectorAll(".pchart__ref--faint")).toHaveLength(1); // the herd's
    expect(container.querySelectorAll(".pchart__ref--liq")).toHaveLength(2);
    expect(screen.getByText("Round open = your entry price")).toBeInTheDocument();
    expect(screen.getByText("Your liquidation price")).toBeInTheDocument();
    expect(screen.getByText("Herd's liquidation price")).toBeInTheDocument();
  });

  it("a liquidation price too far away to share the scale is pinned to the edge and says how far off it is, in words", () => {
    const lines = buildChartLines({
      openPrice: "100.00",
      own: { direction: "UP", leverage: 40, entryPrice: "100.00", liquidationPrice: "60.00" },
      crowd: { direction: "DOWN", leverage: 40, entryPrice: "100.00", liquidationPrice: "140.00" },
    });
    const { container } = chart({ lines });
    const texts = [...container.querySelectorAll(".pchart__ref-text")].map((t) => t.textContent ?? "");
    expect(texts.find((t) => t.startsWith("YOUR LIQ"))).toMatch(/\$60\.00 · \d+\.\d% below/);
    expect(texts.find((t) => t.startsWith("HERD LIQ"))).toMatch(/\$140\.00 · \d+\.\d% above/);
  });

  it("labels of lines that sit close together do not overlap", () => {
    const lines = buildChartLines({
      openPrice: "100.00",
      own: { direction: "UP", leverage: 10, entryPrice: "100.00", liquidationPrice: "99.99" },
      crowd: { direction: "DOWN", leverage: 7, entryPrice: "100.00", liquidationPrice: "100.01" },
    });
    const { container } = chart({ lines });
    const ys = [...container.querySelectorAll(".pchart__ref-box")].map((r) => Number(r.getAttribute("y"))).sort((a, b) => a - b);
    expect(ys).toHaveLength(3);
    for (let i = 1; i < ys.length; i++) expect(ys[i] - ys[i - 1]).toBeGreaterThanOrEqual(16);
  });

  it("shows the move since the round opened, signed and arrowed — up or down", () => {
    const up = chart({ lines: buildChartLines({ openPrice: "50.00" }) });
    expect(up.container.querySelector(".pchart__move--up")).toHaveTextContent(/▲ \+\d+\.\d+% vs round open/);
    up.unmount();
    const down = chart({ lines: buildChartLines({ openPrice: "200.00" }) });
    expect(down.container.querySelector(".pchart__move--down")).toHaveTextContent(/▼ -\d+\.\d+% vs round open/);
  });

  it("for odds the move is in points, not percent", () => {
    const { container } = chart({ market: GER, ticks: ticksOf(300, NOW, 0.64, 3_000, 6), lines: buildChartLines({ openPrice: "0.500000" }) });
    expect(container.querySelector(".pchart__move")).toHaveTextContent(/\+\d+\.\d pts vs round open/);
  });

  it("the round start is marked when it is inside the view", () => {
    const { container } = chart({ roundStartMs: NOW - 120_000 });
    expect(container.querySelector(".pchart__start")).toBeInTheDocument();
    expect(screen.getByText("Round start")).toBeInTheDocument(); // explained in the legend, not on top of the lines
  });

  it("not when it is older than the view", () => {
    const { container } = chart({ roundStartMs: NOW - 3_600_000 });
    expect(container.querySelector(".pchart__start")).not.toBeInTheDocument();
    expect(screen.queryByText("Round start")).not.toBeInTheDocument();
  });
});

describe("PriceChart — states", () => {
  it("loading with nothing yet is a placeholder, not a blank hole", () => {
    const { container } = chart({ ticks: [], loading: true });
    expect(container.querySelector(".skeleton")).toBeInTheDocument();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });

  it("no ticks yet says it is waiting", () => {
    chart({ ticks: [] });
    expect(screen.getByRole("status")).toHaveTextContent("Waiting for the first prices…");
  });

  it("a feed that is not answering says that", () => {
    chart({ ticks: [], error: true });
    expect(screen.getByRole("status")).toHaveTextContent("The price feed isn't answering");
  });

  it("an error after a good load keeps the chart and says it is reconnecting", () => {
    chart({ error: true });
    expect(screen.getByRole("img", { name: /BTC price/ })).toBeInTheDocument();
    expect(screen.getByText("Reconnecting to the price feed…")).toBeInTheDocument();
  });

  it("a stale feed says the last point is old", () => {
    chart({ stale: true });
    expect(screen.getByText("Price feed is stale — the last point is old")).toBeInTheDocument();
  });

  it("a malformed price does not blank the chart", () => {
    const ticks = [...ticksOf(100, NOW), { atMs: NOW + 3_000, price: "garbage" }];
    chart({ ticks });
    expect(screen.getByRole("img", { name: /BTC price/ })).toBeInTheDocument();
  });

  it("the pulsing end point can be switched off by reduced motion (the rule is in the stylesheet)", async () => {
    const css = (await import("./PriceChart.css?raw")) as unknown as { default: string };
    expect(css.default).toMatch(/prefers-reduced-motion: reduce[\s\S]*\.pchart__pulse[\s\S]*animation: none/);
    expect(css.default).toMatch(/html\[data-reduced-motion="true"\] \.pchart__pulse/);
  });
});

describe("PriceChart — size", () => {
  it("measures the box it ends up in, even though the plot only appears after a placeholder that was there first", () => {
    const callbacks: Array<(entries: unknown[]) => void> = [];
    class FakeResizeObserver {
      constructor(callback: (entries: unknown[]) => void) {
        callbacks.push(callback);
      }
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal("ResizeObserver", FakeResizeObserver);

    const props = { market: BTC, lines: [], error: false } as const;
    const { rerender, container } = render(<PriceChart {...props} ticks={[]} loading={true} />);
    expect(container.querySelector("svg")).not.toBeInTheDocument(); // the skeleton is showing

    rerender(<PriceChart {...props} ticks={ticksOf(300, NOW)} loading={false} />);
    expect(callbacks.length).toBeGreaterThan(0); // an observer was attached to the plot's box when it appeared
    act(() => callbacks[callbacks.length - 1]([{ contentRect: { width: 612 } }]));
    expect(container.querySelector("svg")).toHaveAttribute("width", "612");
  });

  it("is shorter on a phone than on a desktop, so the arena below stays in the first screen", () => {
    const callbacks: Array<(entries: unknown[]) => void> = [];
    class FakeResizeObserver {
      constructor(callback: (entries: unknown[]) => void) {
        callbacks.push(callback);
      }
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    vi.stubGlobal("ResizeObserver", FakeResizeObserver);
    const { container } = chart();
    act(() => callbacks[callbacks.length - 1]([{ contentRect: { width: 358 } }]));
    const phone = Number(container.querySelector("svg")!.getAttribute("height"));
    act(() => callbacks[callbacks.length - 1]([{ contentRect: { width: 540 } }]));
    const wide = Number(container.querySelector("svg")!.getAttribute("height"));
    expect(phone).toBeLessThan(wide);
  });

  it("a closed market's chart is a record: its end point does not pulse", () => {
    const live = chart();
    expect(live.container.querySelector(".pchart__pulse")).toBeInTheDocument();
    live.unmount();
    const closed = chart({ live: false });
    expect(closed.container.querySelector(".pchart__pulse")).not.toBeInTheDocument();
    expect(closed.container.querySelector(".pchart__dot")).toBeInTheDocument();
  });
});
