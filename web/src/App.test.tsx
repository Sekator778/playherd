import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { ApiProvider } from "./api/ApiContext";
import { reloadFavoritesFromStorage } from "./api/favorites";

function renderApp(initialPath = "/") {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ApiProvider>
        <App />
      </ApiProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  window.sessionStorage.clear();
  reloadFavoritesFromStorage();
  // `ScrollManager` (in `Layout`) scrolls the window on every navigation; jsdom has no `scrollTo`
  // and would print "Not implemented" for each one.
  vi.spyOn(window, "scrollTo").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("App routing — Lobby / Pair room / bottom tabs IA", () => {
  it("loads the Lobby: a ribbon of categories and a card for every market (Р-69), each with its own live numbers", async () => {
    renderApp("/");
    expect(await screen.findByRole("heading", { level: 1, name: "Markets" })).toBeInTheDocument();
    // 19 markets in the mock, one of which (the closing ODDS market) is still open at load.
    expect(await screen.findAllByRole("article")).toHaveLength(19);
    expect(screen.getAllByText(/playing/).length).toBeGreaterThanOrEqual(19);
    expect(screen.getByRole("tablist", { name: "Market categories" })).toBeInTheDocument();
    expect(screen.getAllByRole("tab").map((t) => t.textContent)).toEqual(
      expect.arrayContaining([expect.stringMatching(/^All/), expect.stringMatching(/^Crypto/), expect.stringMatching(/^Politics/), expect.stringMatching(/^Sports/)]),
    );
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
  });

  it("the BTC lobby card links to its pair room", async () => {
    renderApp("/");
    const btcCard = await screen.findByRole("article", { name: /^BTC/ });
    expect(within(btcCard).getByRole("link", { name: /^BTC — Bitcoin/ })).toHaveAttribute("href", "/play/BTC");
  });

  it("a category tab narrows the lobby to that panel", async () => {
    const user = userEvent.setup();
    renderApp("/");
    await screen.findAllByRole("article");
    await user.click(screen.getByRole("tab", { name: /^Crypto/ }));
    expect(screen.getAllByRole("article")).toHaveLength(6);
    await user.click(screen.getByRole("tab", { name: /^Politics/ }));
    expect(screen.getAllByRole("article")).toHaveLength(4);
  });

  it("a card's DOWN button opens the room with DOWN preselected", async () => {
    const user = userEvent.setup();
    renderApp("/");
    const eth = await screen.findByRole("article", { name: /^ETH/ });
    await user.click(within(eth).getByRole("link", { name: /^Bet DOWN on ETH/ }));
    expect(await screen.findByText("ETH/USD")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /^DOWN/ })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: /^UP/ })).toHaveAttribute("aria-pressed", "false");
  });

  it("loads the BTC pair room with the live arena and a bet slip", async () => {
    renderApp("/play/BTC");
    expect(await screen.findByText("BTC/USD")).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /^UP/ })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: /^DOWN/ })).toBeInTheDocument();
    expect(await screen.findAllByRole("button", { name: "CONNECT WALLET" })).not.toHaveLength(0);
  });

  it("an old lowercase /play/btc link still lands on the BTC room", async () => {
    renderApp("/play/btc");
    expect(await screen.findByText("BTC/USD")).toBeInTheDocument();
  });

  it("every market has a room now — ETH is live, not 'coming soon'", async () => {
    renderApp("/play/ETH");
    expect(await screen.findByText("ETH/USD")).toBeInTheDocument();
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument();
  });

  it("an odds market's room says what a bet on it is", async () => {
    renderApp("/play/PM900101");
    expect(await screen.findByText(/You bet on how the odds move during the round — not on who wins/)).toBeInTheDocument();
    expect(screen.getByText(/Odds from Polymarket, published by our team's key — not a decentralized oracle/)).toBeInTheDocument();
  });

  it("shows an unknown-market state for a market that does not exist, without crashing", async () => {
    renderApp("/play/NOPE");
    expect(await screen.findByText("Unknown market.")).toBeInTheDocument();
  });

  it("renders the static How it works page without any backend call", () => {
    renderApp("/how-it-works");
    expect(screen.getByRole("heading", { level: 1, name: "How it works" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Leverage and liquidation" })).toBeInTheDocument();
  });

  it("the public Status page is at /status, open to everyone — in the mock build it says it is demo data", async () => {
    renderApp("/status");
    expect(await screen.findByRole("heading", { level: 1, name: "Status" })).toBeInTheDocument();
    expect(screen.getByText(/runs on built-in demo data, not on a server/)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { name: "Cancelled rounds" })).toBeInTheDocument();
  });

  it("the footer links to Status and to How it works from every page; How it works links to Status too", async () => {
    renderApp("/how-it-works");
    const footer = screen.getByRole("contentinfo");
    expect(within(footer).getByRole("link", { name: "Status" })).toHaveAttribute("href", "/status");
    expect(within(footer).getByRole("link", { name: "How it works" })).toHaveAttribute("href", "/how-it-works");
    expect(screen.getByRole("link", { name: "Status page" })).toHaveAttribute("href", "/status");
  });

  it("shows a 404 state for an unknown route", () => {
    renderApp("/this-route-does-not-exist");
    expect(screen.getByText("Page not found.")).toBeInTheDocument();
  });

  it("loads the Stats tab's Crowd view, on BTC unless it is told or knows another market", async () => {
    renderApp("/stats");
    expect(await screen.findByRole("heading", { name: "Crowd account vs the market" })).toBeInTheDocument();
    expect(await screen.findByLabelText("Market")).toHaveValue("BTC");
    // BTC is priced by Coinbase now, with Kraken behind it (Р-76) — the mock's catalogue says what the server's does
    const line = await screen.findByText(/Price from Coinbase/);
    expect(line).toHaveTextContent(/Backup: Kraken/);
    expect(line).not.toHaveTextContent(/Hyperliquid/);
  });

  it("Stats opens on the market in ?m=", async () => {
    renderApp("/stats?m=ETH");
    expect(await screen.findByLabelText("Market")).toHaveValue("ETH");
  });

  it("Stats opens on the market the player was last in a room of", async () => {
    window.sessionStorage.setItem("herd.lastMarket", "xyz_NVDA");
    renderApp("/stats");
    expect(await screen.findByLabelText("Market")).toHaveValue("xyz_NVDA");
  });

  it("/crowd redirects to the Stats tab's Crowd view", async () => {
    renderApp("/crowd");
    expect(await screen.findByRole("heading", { name: "Crowd account vs the market" })).toBeInTheDocument();
  });

  it("loads the Stats tab's Ratings view", async () => {
    renderApp("/stats/ratings");
    expect(await screen.findByRole("heading", { name: "Ratings" })).toBeInTheDocument();
    expect(screen.getByText(/we don't detect multiple accounts/)).toBeInTheDocument();
  });

  it("/ratings redirects to the Stats tab's Ratings view", async () => {
    renderApp("/ratings");
    expect(await screen.findByRole("heading", { name: "Ratings" })).toBeInTheDocument();
  });

  it("the Profile tab (/profile/me) prompts to connect a wallet when signed out", () => {
    renderApp("/profile/me");
    expect(screen.getByText("Connect your wallet to see your profile.")).toBeInTheDocument();
  });

  it("signed out, there is no 'Your bets' strip", async () => {
    renderApp("/");
    await screen.findAllByRole("article");
    expect(screen.queryByRole("region", { name: "Your open bets and positions" })).not.toBeInTheDocument();
  });

  it("the rejected concept prototypes B and C are gone, A is still there", async () => {
    renderApp("/lab/concepts/b");
    expect(screen.getByText("Page not found.")).toBeInTheDocument();
    renderApp("/lab/concepts/c");
    expect(screen.getAllByText("Page not found.")).toHaveLength(2);
  });

  it("Р-63: the Lobby and Pair room carry no top DEMO/replay banner", async () => {
    renderApp("/");
    await screen.findAllByRole("article");
    expect(screen.queryByText(/PLAY is a real token \(a Chia CAT\) minted on Chia testnet11/)).not.toBeInTheDocument();

    renderApp("/play/BTC");
    await screen.findByText("BTC/USD");
    expect(screen.queryByText(/PLAY is a real token \(a Chia CAT\) minted on Chia testnet11/)).not.toBeInTheDocument();
  });

  it("keeps the PLAY facts reachable: the wallet says it is a real token that can be withdrawn; How it works keeps its note", () => {
    renderApp("/wallet");
    expect(screen.getByText(/PLAY is a real token minted on Chia testnet11\. It can be withdrawn/)).toBeInTheDocument();

    renderApp("/how-it-works");
    expect(screen.getByRole("heading", { name: "PLAY is not real money" })).toBeInTheDocument();
  });
});
