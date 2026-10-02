import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { CrowdAccountDto, CrowdOverviewDto } from "../api/contract";
import { catalogue, marketsOf } from "../components/testFixtures";
import { CrowdPage } from "./CrowdPage";

const MARKETS = catalogue();

function accountOf(marketId: string): CrowdAccountDto {
  const m = MARKETS.find((x) => x.id === marketId)!;
  return {
    marketId,
    symbol: m.symbol,
    capital: "103400.00",
    history: [
      { roundId: `${marketId}-1d-1`, atMs: Date.UTC(2026, 8, 24), seasonBoundary: "2026-09", crowdCapital: "100000.00", holdCapital: "100000.00", antiCrowdCapital: "100000.00" },
      { roundId: `${marketId}-1d-2`, atMs: Date.UTC(2026, 8, 25), seasonBoundary: null, crowdCapital: "103400.00", holdCapital: "101000.00", antiCrowdCapital: "98000.00" },
    ],
    seasonReturns: [{ season: "2026-09", returnPct: 0.034 }],
  };
}

const row = (marketId: string, symbol: string, returnPct: number, roundsTraded = 10, over: Partial<CrowdOverviewDto["markets"][number]> = {}): CrowdOverviewDto["markets"][number] => ({
  marketId,
  symbol,
  title: MARKETS.find((m) => m.id === marketId)?.title ?? symbol,
  status: "ACTIVE" as const,
  category: "crypto" as const,
  capital: (100000 * (1 + returnPct)).toFixed(2),
  returnPct,
  roundsTraded,
  roundsWon: Math.floor(roundsTraded / 2),
  ...over,
});

const OVERVIEW: CrowdOverviewDto = {
  totalCapital: "1830214.00",
  totalStartingCapital: "1800000.00",
  returnPct: 0.0168,
  markets: [row("BTC", "BTC", 0.034, 20), row("ETH", "ETH", -0.031, 10), row("xyz_NVDA", "NVDA", 0, 0), row("PM900104", "GER", 0.012, 6)],
};

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

function renderCrowd(path = "/stats", overview: CrowdOverviewDto = OVERVIEW) {
  const crowdAccount = vi.fn(async (id: string = "BTC") => accountOf(id));
  const crowdOverview = vi.fn(async () => overview);
  const api = fakeApi({ markets: vi.fn(async () => marketsOf(MARKETS)), crowdAccount, crowdOverview });
  render(
    <MemoryRouter initialEntries={[path]}>
      <CrowdPage />
      <LocationProbe />
    </MemoryRouter>,
    { wrapper: apiWrapper(api) },
  );
  return { crowdAccount, crowdOverview };
}

beforeEach(() => {
  window.sessionStorage.clear();
});

describe("Stats → Crowd — the market selector (S4)", () => {
  it("opens on BTC by default and loads that market's crowd account", async () => {
    const { crowdAccount } = renderCrowd();
    expect(await screen.findByLabelText("Market")).toHaveValue("BTC");
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("BTC"));
    expect(await screen.findByText("Capital")).toBeInTheDocument();
  });

  it("opens on the market in ?m=", async () => {
    const { crowdAccount } = renderCrowd("/stats?m=ETH");
    expect(await screen.findByLabelText("Market")).toHaveValue("ETH");
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("ETH"));
  });

  it("opens on the market the player came from, when the link does not say", async () => {
    window.sessionStorage.setItem("herd.lastMarket", "PM900104");
    const { crowdAccount } = renderCrowd();
    expect(await screen.findByLabelText("Market")).toHaveValue("PM900104");
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("PM900104"));
  });

  it("?m= wins over the remembered market; the id is matched ignoring case", async () => {
    window.sessionStorage.setItem("herd.lastMarket", "PM900104");
    renderCrowd("/stats?m=eth");
    expect(await screen.findByLabelText("Market")).toHaveValue("ETH");
  });

  it("a market that is not on the list falls back to BTC", async () => {
    renderCrowd("/stats?m=NOPE");
    expect(await screen.findByLabelText("Market")).toHaveValue("BTC");
  });

  it("picking another market loads its account, writes ?m= and is remembered for next time", async () => {
    const user = userEvent.setup();
    const { crowdAccount } = renderCrowd();
    const select = await screen.findByLabelText("Market");
    await user.selectOptions(select, "xyz_NVDA");
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("xyz_NVDA"));
    expect(screen.getByTestId("location")).toHaveTextContent("/stats?m=xyz_NVDA");
    expect(window.sessionStorage.getItem("herd.lastMarket")).toBe("xyz_NVDA");
    expect(screen.getByRole("link", { name: "Play NVDA" })).toHaveAttribute("href", "/play/xyz_NVDA");
  });

  it("groups the markets by category and marks a closed one", async () => {
    renderCrowd();
    const select = await screen.findByLabelText("Market");
    const groups = within(select).getAllByRole("group").map((g) => g.getAttribute("label"));
    expect(groups).toEqual(["Crypto", "Politics", "Sports", "Stocks"].filter((g) => groups.includes(g)));
    expect(groups).toEqual(expect.arrayContaining(["Crypto", "Sports", "Stocks", "Politics"]));
    expect(within(select).getByRole("option", { name: /OLD · Draw · Friendly \(closed\)/ })).toBeInTheDocument();
    expect(within(select).getByRole("option", { name: "BTC · Bitcoin" })).toBeInTheDocument();
  });

  it("names the source of the selected market in the oracle line — Hyperliquid for a perp, Polymarket for odds", async () => {
    const user = userEvent.setup();
    renderCrowd();
    expect(await screen.findByText(/Price from Hyperliquid, published by our team's key/)).toBeInTheDocument();
    await user.selectOptions(await screen.findByLabelText("Market"), "PM900104");
    expect(await screen.findByText(/Odds from Polymarket, published by our team's key/)).toBeInTheDocument();
  });
});

describe("Stats → Crowd — the herd overview (S4)", () => {
  it("totals: the whole herd's capital and return, signed and arrowed", async () => {
    renderCrowd();
    const overview = await screen.findByRole("region", { name: /The whole herd — 4 markets/ });
    expect(within(overview).getByText("$1,830,214.00")).toBeInTheDocument();
    expect(within(overview).getByText(/of \$1,800,000 paper money at the start/)).toBeInTheDocument();
    expect(overview.querySelector(".crowd-overview__big .crowd-return")).toHaveTextContent("▲ +1.7%");
  });

  it("names the best market and the worst — the worst as visible as the best", async () => {
    renderCrowd();
    const overview = await screen.findByRole("region", { name: /The whole herd/ });
    const best = within(overview).getByRole("button", { name: /^Best market: BTC, \+3\.4%/ });
    const worst = within(overview).getByRole("button", { name: /^Worst market: ETH, -3\.1%/ });
    expect(best).toHaveTextContent("▲ +3.4%");
    expect(worst).toHaveTextContent("▼ -3.1%");
  });

  it("a market the herd has not traded in is neither best nor worst", async () => {
    renderCrowd();
    const overview = await screen.findByRole("region", { name: /The whole herd/ });
    expect(within(overview).queryByRole("button", { name: /NVDA/ })).not.toBeInTheDocument();
  });

  it("tapping best or worst opens that market's account", async () => {
    const user = userEvent.setup();
    const { crowdAccount } = renderCrowd("/stats?m=GER");
    const overview = await screen.findByRole("region", { name: /The whole herd/ });
    await user.click(within(overview).getByRole("button", { name: /^Worst market: ETH/ }));
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("ETH"));
    expect(screen.getByLabelText("Market")).toHaveValue("ETH");
  });

  it("lists every traded market best to worst", async () => {
    const user = userEvent.setup();
    renderCrowd();
    const overview = await screen.findByRole("region", { name: /The whole herd/ });
    await user.click(within(overview).getByText("Every market, best to worst"));
    const rows = within(overview).getAllByRole("button", { name: /Show its account\.$/ }).filter((b) => b.className.includes("crowd-rank__row"));
    expect(rows.map((r) => r.textContent?.slice(0, 3))).toEqual(["BTC", "GER", "ETH"]);
  });

  it("says so when the herd has not traded anywhere yet", async () => {
    renderCrowd("/stats", { ...OVERVIEW, markets: [row("BTC", "BTC", 0, 0)] });
    expect(await screen.findByText(/The herd hasn't traded yet/)).toBeInTheDocument();
  });

  it("the account still shows if the overview fails", async () => {
    const crowdAccount = vi.fn(async (id: string = "BTC") => accountOf(id));
    const api = fakeApi({ markets: vi.fn(async () => marketsOf(MARKETS)), crowdAccount, crowdOverview: vi.fn(async () => Promise.reject(new Error("down"))) });
    render(
      <MemoryRouter>
        <CrowdPage />
      </MemoryRouter>,
      { wrapper: apiWrapper(api) },
    );
    expect(await screen.findByText("Capital")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: /The whole herd/ })).not.toBeInTheDocument();
  });
});

describe("Stats → Crowd — a closed market keeps its row, named by the row itself (CrowdOverviewDto.title / status)", () => {
  const GONE = row("PM_GONE", "GONE", -0.02, 4, { status: "CLOSED", title: "Will the old match end in a draw?", category: "sports" });
  const WITH_CLOSED: CrowdOverviewDto = { ...OVERVIEW, markets: [...OVERVIEW.markets, GONE] };

  it("marks the closed market's row closed — in words — and carries its title, with no join against the market list", async () => {
    const user = userEvent.setup();
    renderCrowd("/stats", WITH_CLOSED);
    const overview = await screen.findByRole("region", { name: /The whole herd — 5 markets/ });
    await user.click(within(overview).getByText("Every market, best to worst"));
    const goneRow = within(overview).getByRole("button", { name: /^GONE, closed: -2\.0%/ });
    expect(goneRow).toHaveTextContent("closed");
    expect(goneRow).toHaveAttribute("title", "Will the old match end in a draw?");
    // an open market's row has no such word
    const btcRow = within(overview).getAllByRole("button", { name: /^BTC: \+3\.4%/ }).find((b) => b.className.includes("crowd-rank__row"))!;
    expect(btcRow).not.toHaveTextContent("closed");
  });

  it("a closed market that is the worst says so on the worst card too", async () => {
    renderCrowd("/stats", { ...WITH_CLOSED, markets: [row("BTC", "BTC", 0.034, 20), { ...GONE, returnPct: -0.09 }] });
    const worst = await screen.findByRole("button", { name: /^Worst market: GONE, closed, -9\.0%/ });
    expect(worst).toHaveTextContent("closed");
  });

  it("its account is still reachable after it has left the market list: opened, not silently swapped for BTC", async () => {
    const { crowdAccount } = renderCrowd("/stats?m=PM_GONE", WITH_CLOSED);
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("PM_GONE"));
    const select = await screen.findByLabelText("Market");
    expect(select).toHaveValue("PM_GONE");
    expect(within(select).getByRole("option", { name: "GONE · Will the old match end in a draw? (closed)" })).toBeInTheDocument();
    // nothing to bet on, and no source to name for it
    expect(screen.queryByRole("link", { name: /^Play / })).not.toBeInTheDocument();
    expect(screen.queryByText(/published by our team's key/)).not.toBeInTheDocument();
  });

  it("picking it from the ranked list opens its account", async () => {
    const user = userEvent.setup();
    const { crowdAccount } = renderCrowd("/stats", WITH_CLOSED);
    const overview = await screen.findByRole("region", { name: /The whole herd/ });
    await user.click(within(overview).getByText("Every market, best to worst"));
    await user.click(within(overview).getByRole("button", { name: /^GONE, closed/ }));
    await waitFor(() => expect(crowdAccount).toHaveBeenCalledWith("PM_GONE"));
    expect(screen.getByLabelText("Market")).toHaveValue("PM_GONE");
  });

  it("an open market still has its Play link and its source line", async () => {
    renderCrowd("/stats?m=ETH", WITH_CLOSED);
    expect(await screen.findByRole("link", { name: "Play ETH" })).toHaveAttribute("href", "/play/ETH");
    expect(await screen.findByText(/Price from Hyperliquid, published by our team's key/)).toBeInTheDocument();
  });
});
