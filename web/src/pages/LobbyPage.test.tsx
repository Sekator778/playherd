import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { setProMode } from "../components/proMode";
import { ApiInstanceContext, SessionContext, type SessionState } from "../api/ApiContext";
import { TEST_PLAYER, apiWrapper, fakeApi } from "../api/apiTestUtils";
import type { Api, MarketDto, MarketsDto } from "../api/contract";
import { FAVORITES_STORAGE_KEY, reloadFavoritesFromStorage } from "../api/favorites";
import { catalogue, market, marketsOf, spotCatalogue } from "../components/testFixtures";
import { LobbyPage } from "./LobbyPage";

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.pathname + location.search}</output>;
}

function renderLobby(markets: MarketDto[] = catalogue(), path = "/", methods: Partial<Api> = {}) {
  const api = fakeApi({ markets: vi.fn(async () => marketsOf(markets)), ...methods });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LobbyPage />
      <LocationProbe />
    </MemoryRouter>,
    { wrapper: apiWrapper(api) },
  );
}

/** The lobby for a given state of the session (signed out, signed in, still being read). `intro` is the lobby's own flag (off unless asked). */
function renderLobbyAs(session: Partial<SessionState>, intro?: boolean) {
  const api = fakeApi({ markets: vi.fn(async () => marketsOf(catalogue())) });
  const state: SessionState = { player: null, loading: false, refresh: async () => {}, signOut: async () => {}, openConnectModal: () => {}, ...session };
  return render(
    <MemoryRouter>
      <ApiInstanceContext.Provider value={api}>
        <SessionContext.Provider value={state}>
          {intro === undefined ? <LobbyPage /> : <LobbyPage intro={intro} />}
        </SessionContext.Provider>
      </ApiInstanceContext.Provider>
    </MemoryRouter>,
  );
}

/** The cards on screen, top to bottom, by their ticker. */
function tickers(): string[] {
  return screen.getAllByRole("article").map((card) => within(card).getByRole("heading", { level: 3 }).textContent ?? "");
}

const cardOf = (symbol: string) => screen.getByRole("article", { name: new RegExp(`^${symbol}\\b`) });

beforeEach(() => {
  window.localStorage.clear();
  reloadFavoritesFromStorage();
});

describe("Lobby — the ribbon of categories (Р-69)", () => {
  it("shows skeletons while the first response is on its way, then a card for every market", async () => {
    renderLobby();
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    await screen.findAllByRole("article");
    expect(tickers()).toEqual(["BTC", "ETH", "NVDA", "RUNOFF", "GER", "OLD"]);
  });

  it("lists All and each category the server has, with the number of OPEN markets in it", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const tabs = screen.getAllByRole("tab").map((t) => t.getAttribute("aria-label"));
    expect(tabs).toEqual([
      "All, 5 open markets", // the closed OLD market is on the list but is not "open"
      "Crypto, 2 open markets",
      "Politics, 1 open market",
      "Sports, 1 open market",
      "Stocks, 1 open market",
    ]);
    expect(screen.getByRole("tab", { name: /^All/ })).toHaveAttribute("aria-selected", "true");
  });

  it("a tab filters the list, marks itself selected and is written to the URL", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    await user.click(screen.getByRole("tab", { name: /^Sports/ }));

    expect(tickers()).toEqual(["GER", "OLD"]); // the open one, then the closed one of that panel
    expect(screen.getByRole("tab", { name: /^Sports/ })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: /^All/ })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByTestId("location")).toHaveTextContent("/?c=sports");
    // the ribbon's chip counts OPEN markets (1 here); the line under it says how many of the cards are open and how many closed
    expect(screen.getByText("1 market · 1 closed")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: /^All/ }));
    expect(tickers()).toHaveLength(6);
    expect(screen.getByText("5 markets · 1 closed")).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/);
  });

  it("opens on the panel a link points to, and reads an unknown panel as All", async () => {
    renderLobby(catalogue(), "/?c=stocks");
    await screen.findAllByRole("article");
    expect(tickers()).toEqual(["NVDA"]);
    expect(screen.getByRole("tab", { name: /^Stocks/ })).toHaveAttribute("aria-selected", "true");
  });

  it("a panel that is not on the ribbon (an old link) is All, not an empty page", async () => {
    renderLobby(catalogue(), "/?c=gadgets");
    await screen.findAllByRole("article");
    expect(tickers()).toHaveLength(6);
    expect(screen.getByRole("tab", { name: /^All/ })).toHaveAttribute("aria-selected", "true");
  });
});

describe("Lobby — chips and search", () => {
  it("Hot orders by the biggest pool in the open round, tags the top ones, and a second tap undoes it", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    const hot = screen.getByRole("button", { name: /Hot/ });
    expect(hot).toHaveAttribute("aria-pressed", "false");
    await user.click(hot);

    expect(hot).toHaveAttribute("aria-pressed", "true");
    expect(tickers()).toEqual(["BTC", "ETH", "GER", "RUNOFF", "NVDA", "OLD"]);
    expect(within(cardOf("BTC")).getByText("HOT")).toBeInTheDocument();
    expect(within(cardOf("GER")).getByText("HOT")).toBeInTheDocument();
    expect(within(cardOf("NVDA")).queryByText("HOT")).not.toBeInTheDocument(); // nothing staked there
    expect(screen.getByText(/biggest pools first/)).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("sort=hot");

    await user.click(hot);
    expect(tickers()).toEqual(["BTC", "ETH", "NVDA", "RUNOFF", "GER", "OLD"]);
    expect(screen.queryByText("HOT")).not.toBeInTheDocument();
  });

  it("Closing soon orders by what closes next — and swaps with Hot, they are one sort", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    await user.click(screen.getByRole("button", { name: /Closing soon/ }));
    expect(tickers()).toEqual(["GER", "ETH", "BTC", "NVDA", "RUNOFF", "OLD"]);
    expect(screen.getByRole("button", { name: /Closing soon/ })).toHaveAttribute("aria-pressed", "true");

    await user.click(screen.getByRole("button", { name: /Hot/ }));
    expect(screen.getByRole("button", { name: /Closing soon/ })).toHaveAttribute("aria-pressed", "false");
    expect(tickers()[0]).toBe("BTC");
  });

  it("a sort chip never hides a market", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");
    await user.click(screen.getByRole("button", { name: /Hot/ }));
    expect(tickers()).toHaveLength(6);
    await user.click(screen.getByRole("button", { name: /Closing soon/ }));
    expect(tickers()).toHaveLength(6);
  });

  it("search narrows the list as you type, in every part of a market's name", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");
    const search = screen.getByRole("searchbox", { name: "Search markets" });

    await user.type(search, "ger");
    expect(tickers()).toEqual(["GER"]);
    expect(screen.getByTestId("location")).toHaveTextContent("q=ger");

    await user.clear(search);
    await user.type(search, "brazil");
    expect(tickers()).toEqual(["RUNOFF"]); // found by its question, not its ticker

    await user.clear(search);
    await user.type(search, "stocks");
    expect(tickers()).toEqual(["NVDA"]); // found by its category
  });

  it("a search with no match says so, and one tap shows everything again", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    await user.type(screen.getByRole("searchbox", { name: "Search markets" }), "zzzz");
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByText("No markets match “zzzz”.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Show all markets" }));
    expect(tickers()).toHaveLength(6);
    expect(screen.getByRole("searchbox", { name: "Search markets" })).toHaveValue("");
  });

  it("the clear button empties the search", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");
    const search = screen.getByRole("searchbox", { name: "Search markets" });
    expect(screen.queryByRole("button", { name: "Clear search" })).not.toBeInTheDocument();
    await user.type(search, "eth");
    await user.click(screen.getByRole("button", { name: "Clear search" }));
    expect(search).toHaveValue("");
    expect(tickers()).toHaveLength(6);
  });

  it("search, panel and sort combine", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");
    await user.click(screen.getByRole("tab", { name: /^Crypto/ }));
    await user.click(screen.getByRole("button", { name: /Closing soon/ }));
    expect(tickers()).toEqual(["ETH", "BTC"]);
    await user.type(screen.getByRole("searchbox", { name: "Search markets" }), "bit");
    expect(tickers()).toEqual(["BTC"]);
  });
});

describe("Lobby — favourites", () => {
  it("the star keeps a market, tells assistive technology it is pressed, and survives a reload", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    const star = within(cardOf("ETH")).getByRole("button", { name: "Add ETH to favorites" });
    expect(star).toHaveAttribute("aria-pressed", "false");
    await user.click(star);

    const pressed = within(cardOf("ETH")).getByRole("button", { name: "Remove ETH from favorites" });
    expect(pressed).toHaveAttribute("aria-pressed", "true");
    expect(JSON.parse(window.localStorage.getItem(FAVORITES_STORAGE_KEY) ?? "[]")).toEqual(["ETH"]);
    expect(screen.getByRole("button", { name: /Favorites/ })).toHaveTextContent("1");

    reloadFavoritesFromStorage();
    expect(within(cardOf("ETH")).getByRole("button", { name: "Remove ETH from favorites" })).toBeInTheDocument();
  });

  it("the Favorites chip shows only the starred markets; without any it says how to add one", async () => {
    const user = userEvent.setup();
    renderLobby();
    await screen.findAllByRole("article");

    await user.click(screen.getByRole("button", { name: /Favorites/ }));
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByText("No favorites yet")).toBeInTheDocument();
    expect(screen.getByText(/Tap the star on a market/)).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("fav=1");

    await user.click(screen.getByRole("button", { name: "Show all markets" }));
    await user.click(within(cardOf("GER")).getByRole("button", { name: "Add GER to favorites" }));
    await user.click(within(cardOf("BTC")).getByRole("button", { name: "Add BTC to favorites" }));
    await user.click(screen.getByRole("button", { name: /Favorites/ }));
    expect(tickers()).toEqual(["BTC", "GER"]);

    // Un-starring inside the filtered view takes the card out of it.
    await user.click(within(cardOf("BTC")).getByRole("button", { name: "Remove BTC from favorites" }));
    expect(tickers()).toEqual(["GER"]);
  });
});

describe("Lobby — the card", () => {
  it("shows ticker, name, live price, 24 h change with an arrow, the split, players and the countdown", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const btc = within(cardOf("BTC"));

    expect(btc.getByText("Bitcoin")).toBeInTheDocument();
    expect(btc.getByText("$83,451.00")).toBeInTheDocument();
    expect(btc.getByText("+0.98%")).toBeInTheDocument();
    expect(btc.getByRole("img", { name: /Crowd split by stake on BTC: 75% up, 25% down, 12 playing/ })).toBeInTheDocument();
    expect(btc.getByText(/12 playing/)).toBeInTheDocument();
    expect(btc.getByText("3 MIN")).toBeInTheDocument(); // the series is named by its real length
    expect(btc.getByText(/closes in \d\d:\d\d/)).toBeInTheDocument();
    expect(cardOf("ETH")).toHaveTextContent("-1.41%");
  });

  it("UP and DOWN open the bet sheet over the lobby (Р-98); the crowd's side is marked in words", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const btc = within(cardOf("BTC"));

    // Buttons now, not links: a tap opens the sheet where the bet is made, it does not take the player down a page.
    const up = btc.getByRole("button", { name: /^Bet UP on BTC/ });
    const down = btc.getByRole("button", { name: /^Bet DOWN on BTC/ });
    expect(up).toHaveAttribute("aria-haspopup", "dialog");
    expect(up).toHaveAccessibleName("Bet UP on BTC — the crowd's side");
    expect(down).toHaveAccessibleName("Bet DOWN on BTC");
    expect(within(up).getByText("crowd")).toBeInTheDocument();
    expect(within(down).queryByText("crowd")).not.toBeInTheDocument();
  });

  it("in the Pro view UP and DOWN are links into the room with that side preselected, as before", async () => {
    setProMode(true);
    try {
      renderLobby();
      await screen.findAllByRole("article");
      const btc = within(cardOf("BTC"));
      expect(btc.getByRole("link", { name: /^Bet UP on BTC/ })).toHaveAttribute("href", "/play/BTC?side=up");
      expect(btc.getByRole("link", { name: /^Bet DOWN on BTC/ })).toHaveAttribute("href", "/play/BTC?side=down");
    } finally {
      setProMode(false);
    }
  });

  it("the card's own link opens the room and keeps ids URL-safe", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    expect(within(cardOf("NVDA")).getByRole("link", { name: /^NVDA — NVIDIA/ })).toHaveAttribute("href", "/play/xyz_NVDA");
  });

  it("the herd badge is the market's crowd account return, signed, and links to its stats", async () => {
    renderLobby();
    await screen.findAllByRole("article");

    const btcHerd = within(cardOf("BTC")).getByRole("link", { name: /Herd on BTC: \+3\.4% return over 20 traded rounds/ });
    expect(btcHerd).toHaveAttribute("href", "/stats?m=BTC");
    expect(btcHerd).toHaveTextContent("Herd +3.4%");
    expect(within(cardOf("ETH")).getByRole("link", { name: /Herd on ETH: -3\.0% return/ })).toHaveTextContent("Herd -3.0%");
    // No trades yet: say so, never a bare "0.0%".
    expect(within(cardOf("NVDA")).getByRole("link", { name: "Herd — no trades yet" })).toBeInTheDocument();
  });

  it("an empty round says so instead of showing a made-up 50/50", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const nvda = within(cardOf("NVDA"));
    expect(nvda.getByText("No bets yet — be the first to call it")).toBeInTheDocument();
    expect(nvda.queryByText(/UP 50%/)).not.toBeInTheDocument();
  });

  it("an odds market shows the question, the outcome and a percentage price, tagged ODDS", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const runoff = within(cardOf("RUNOFF"));
    expect(runoff.getByText("ODDS")).toBeInTheDocument();
    expect(runoff.getByText("58.0%")).toBeInTheDocument();
    expect(runoff.getByText("Will Brazil's 2026 presidential election go to a runoff?")).toBeInTheDocument();
    // the outcome ("Yes", what the 58.0% is the probability of) and its context are two parts of one line
    expect(runoff.getByText("Yes")).toHaveClass("mk-card__outcome");
    expect(runoff.getByText("· Brazil election")).toHaveClass("mk-card__context");
    expect(runoff.getByTitle("If the market closes first, open bets are refunded")).toHaveTextContent(/Event ends in 5d/);
    // A sports game has no end date, only a kick-off: it says "Starts in", not "Event ends in".
    expect(within(cardOf("GER")).queryByText(/Event ends in/)).not.toBeInTheDocument();
    // Odds with no 24 h figure: a dash, not a made-up zero.
    expect(within(cardOf("GER")).getByText("—")).toBeInTheDocument();
    expect(within(cardOf("BTC")).queryByText("ODDS")).not.toBeInTheDocument();
  });

  it("a stale price stays on the card, dimmed and dated — betting is not blocked", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const nvda = within(cardOf("NVDA"));
    expect(nvda.getByText("$231.70")).toBeInTheDocument();
    expect(nvda.getByText(/^Stale · /)).toBeInTheDocument();
    expect(nvda.getByRole("button", { name: /^Bet UP on NVDA/ })).toBeInTheDocument();
  });

  it("a market with no price yet says so and its UP/DOWN are off — such a round could not settle", async () => {
    renderLobby([market("BTC", { symbol: "BTC", title: "Bitcoin", price: null })]);
    await screen.findAllByRole("article");
    const btc = within(cardOf("BTC"));
    expect(btc.getByLabelText("No price yet")).toBeInTheDocument();
    expect(btc.getByText("Waiting for a price")).toBeInTheDocument();
    expect(btc.getByText("No bets yet")).toBeInTheDocument(); // not "be the first to call it": it cannot be called yet
    expect(btc.getByRole("button", { name: /Bet UP on BTC — waiting for a price/ })).toBeDisabled();
    expect(btc.getByRole("button", { name: /Bet DOWN on BTC — waiting for a price/ })).toBeDisabled();
    expect(btc.queryByRole("link", { name: /Bet UP/ })).not.toBeInTheDocument();
  });

  it("an open market listed with no round series is not called closed", async () => {
    renderLobby([market("BTC", { symbol: "BTC", title: "Bitcoin", series: [] })]);
    await screen.findAllByRole("article");
    const btc = within(cardOf("BTC"));
    expect(btc.getByText("No rounds are open on this market yet.")).toBeInTheDocument();
    expect(btc.queryByText(/Closed/)).not.toBeInTheDocument();
  });

  it("a closed market says why and takes no bets, but can still be opened and starred", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const old = within(cardOf("OLD"));
    expect(old.getByText("Closed")).toBeInTheDocument();
    expect(old.getByText(/open bets refunded/)).toBeInTheDocument();
    expect(old.getByText("Last price")).toBeInTheDocument();
    expect(old.queryByRole("link", { name: /Bet UP/ })).not.toBeInTheDocument();
    expect(old.queryByRole("button", { name: /Bet UP/ })).not.toBeInTheDocument();
    expect(old.getByRole("link", { name: /^OLD — .*\(closed\)/ })).toHaveAttribute("href", "/play/PM900199");
    expect(old.getByRole("button", { name: "Add OLD to favorites" })).toBeInTheDocument();
  });
});

describe("Lobby — the card: a sports game, the odds' 24 h change, a closed market's reason", () => {
  it("before kick-off a game says how long is left — 'Starts in 2h 05m' — and wears no LIVE mark", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    const ger = within(cardOf("GER"));
    expect(ger.getByText(/Starts in/)).toHaveTextContent(/^Starts in 2h 0[45]m$/);
    expect(ger.queryByText("LIVE")).not.toBeInTheDocument();
    // …and only a game: a market with no kick-off says nothing of one.
    expect(within(cardOf("BTC")).queryByText(/Starts in/)).not.toBeInTheDocument();
    expect(within(cardOf("RUNOFF")).queryByText(/Starts in/)).not.toBeInTheDocument();
  });

  it("after kick-off the game wears a LIVE mark (a word, not only a pulse) and 'Starts in' is gone", async () => {
    const started = market("PM900104", {
      kind: "ODDS",
      category: "sports",
      symbol: "GER",
      title: "Will Germany win against Serbia?",
      subtitle: "Germany · UEFA Nations League",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      eventStartsAtMs: 1_000_000 - 20 * 60_000,
      price: { price: "0.640000", asOfMs: 999_500, change24h: null, stale: false },
    });
    renderLobby([started]);
    await screen.findAllByRole("article");
    const ger = within(cardOf("GER"));
    expect(ger.getByText("LIVE")).toBeInTheDocument();
    expect(ger.getByTitle("The game has kicked off")).toBeInTheDocument();
    expect(ger.queryByText(/Starts in/)).not.toBeInTheDocument();
  });

  it("an odds market's 24 h change is in percentage points: 0.64 → 0.65 is '+1.0 pt', not '+1.56%'", async () => {
    const odds = market("PM900101", {
      kind: "ODDS",
      category: "politics",
      symbol: "RUNOFF",
      title: "Will Brazil's 2026 presidential election go to a runoff?",
      subtitle: "Yes · Brazil election",
      source: "polymarket",
      priceDecimals: 6,
      maxLeverage: 10,
      price: { price: "0.650000", asOfMs: 999_500, change24h: 0.65 / 0.64 - 1, stale: false },
    });
    renderLobby([odds, ...catalogue().filter((m) => m.id === "BTC")]);
    await screen.findAllByRole("article");
    const runoff = within(cardOf("RUNOFF"));
    expect(runoff.getByText("+1.0 pt")).toBeInTheDocument();
    expect(runoff.queryByText(/1\.56/)).not.toBeInTheDocument();
    expect(runoff.getByText("24 hour change in percentage points")).toBeInTheDocument();
    // the perp beside it is still a percentage
    expect(within(cardOf("BTC")).getByText("+0.98%")).toBeInTheDocument();
  });

  it("a closed market with a reason from the server says that reason as it is, and when", async () => {
    const closed = market("PM900199", {
      kind: "ODDS",
      category: "sports",
      symbol: "OLD",
      title: "Will the old match end in a draw?",
      subtitle: "Draw · Friendly",
      source: "polymarket",
      status: "CLOSED",
      priceDecimals: 6,
      closedAtMs: 1_000_000 - 180_000,
      closeReason: "Price left the 3%–97% range",
      price: { price: "0.970000", asOfMs: 820_000, change24h: null, stale: false },
    });
    renderLobby([closed]);
    await screen.findAllByRole("article");
    const old = within(cardOf("OLD"));
    // (the mock clock runs 60× real time, so "3 minutes ago" can read 2 by the time the card is looked at)
    expect(old.getByRole("note")).toHaveTextContent(/Closed [23] min ago — Price left the 3%–97% range/);
    expect(old.getByText(/Open bets were refunded/)).toBeInTheDocument();
    expect(old.queryByRole("link", { name: /Bet UP/ })).not.toBeInTheDocument();
  });

  it("a closed market with no reason keeps the generic line", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    expect(within(cardOf("OLD")).getByText(/open bets refunded\. This market takes no more bets\./)).toBeInTheDocument();
  });
});

describe("Lobby — the card: which exchange the price is from (Р-76)", () => {
  /** The card of a market by its id: the new `spot_BTC` and the legacy `BTC` it replaced share a ticker. */
  const cardById = (id: string) => within(document.querySelector<HTMLElement>(`[data-market-id="${id}"]`)!);

  it("a price that is the backup exchange's says so under the number — the main exchange has no fresh price", async () => {
    renderLobby(spotCatalogue());
    await screen.findAllByRole("article");
    const eth = cardById("spot_ETH");
    const mark = eth.getByText("Kraken price");
    expect(mark).toHaveAttribute("title", "Coinbase has no fresh price right now, so this is Kraken's — its backup.");
    // a screen reader gets the reason too, not only the hover text
    expect(eth.getByText(/Coinbase has no fresh price right now, so this is Kraken's — its backup\./, { selector: ".visually-hidden" })).toBeInTheDocument();
    expect(eth.getByText("$2,696.80")).toBeInTheDocument();
  });

  it("a price from the main exchange is not marked: a null venue, an absent one, a market with no backup", async () => {
    renderLobby([...spotCatalogue(), ...catalogue().filter((m) => m.id === "xyz_NVDA")]);
    await screen.findAllByRole("article");
    for (const id of ["spot_BTC", "spot_DOGE", "spot_HYPE", "xyz_NVDA"]) {
      expect(document.querySelector(`[data-market-id="${id}"] .mk-card__venue`), id).toBeNull();
    }
    // …and exactly one card carries the mark: spot_ETH's, the only one whose price is Kraken's
    expect(document.querySelectorAll(".mk-card__venue")).toHaveLength(1);
    expect(document.querySelector('[data-market-id="spot_ETH"] .mk-card__venue')).not.toBeNull();
  });

  it("a stale backup price says both: it is old, and it is the backup's", async () => {
    const stale = market("spot_ETH", {
      symbol: "ETH",
      title: "Ethereum",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "2696.80", asOfMs: 1_000_000 - 90_000, change24h: -0.0141, stale: true, venue: "kraken" },
    });
    renderLobby([stale]);
    await screen.findAllByRole("article");
    const eth = cardById("spot_ETH");
    expect(eth.getByText(/^Stale · /)).toBeInTheDocument();
    expect(eth.getByText("Kraken price")).toBeInTheDocument();
  });

  it("the price of HYPE can be Coinbase's — its backup, behind Hyperliquid's spot market", async () => {
    const hype = market("spot_HYPE", {
      symbol: "HYPE",
      title: "Hyperliquid",
      source: "hyperliquid",
      backupSource: "coinbase",
      price: { price: "87.235", asOfMs: 999_500, change24h: 0.03, stale: false, venue: "coinbase" },
    });
    renderLobby([hype]);
    await screen.findAllByRole("article");
    expect(cardById("spot_HYPE").getByText("Coinbase price")).toHaveAttribute("title", "Hyperliquid has no fresh price right now, so this is Coinbase's — its backup.");
  });

  it("a venue equal to the market's own exchange is the main exchange's price — nothing to mark", async () => {
    const own = market("spot_BTC", {
      symbol: "BTC",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "84010.50", asOfMs: 999_500, change24h: 0, stale: false, venue: "coinbase" },
    });
    renderLobby([own]);
    await screen.findAllByRole("article");
    expect(document.querySelector(".mk-card__venue")).toBeNull();
  });

  it("a closed market's card marks no price: it is a last price, and says Last price", async () => {
    const closed = market("spot_ETH", {
      symbol: "ETH",
      status: "CLOSED",
      source: "coinbase",
      backupSource: "kraken",
      price: { price: "2696.80", asOfMs: 820_000, change24h: 0, stale: false, venue: "kraken" },
    });
    renderLobby([closed]);
    await screen.findAllByRole("article");
    const eth = cardById("spot_ETH");
    expect(eth.getByText("Last price")).toBeInTheDocument();
    expect(eth.queryByText("Kraken price")).not.toBeInTheDocument();
  });

  it("the market a new source replaced is closed like any closed market: the server's reason as it sent it, open bets refunded", async () => {
    renderLobby(spotCatalogue());
    await screen.findAllByRole("article");
    const legacy = cardById("BTC");
    expect(legacy.getByRole("note")).toHaveTextContent(/Closed [23] min ago — Moved to a new price source: Coinbase/);
    expect(legacy.getByText(/Open bets were refunded\. This market takes no more bets\./)).toBeInTheDocument();
    expect(legacy.queryByRole("link", { name: /Bet UP/ })).not.toBeInTheDocument();
    // …while the market that replaced it is open for bets, with the same ticker
    expect(cardById("spot_BTC").getByRole("button", { name: /^Bet UP on BTC/ })).toBeInTheDocument();
  });

  it("an odds tag names whose odds they are only when the market says: Polymarket's, a simulated one's", async () => {
    const odds = (id: string, source: "polymarket" | "synthetic") =>
      market(id, { kind: "ODDS", category: "politics", symbol: id, title: `Will ${id} happen?`, subtitle: "Yes · Politics", source, priceDecimals: 6, maxLeverage: 10, price: { price: "0.500000", asOfMs: 999_500, change24h: null, stale: false } });
    renderLobby([odds("REAL", "polymarket"), odds("FAKE", "synthetic")]);
    await screen.findAllByRole("article");
    expect(cardById("REAL").getByText("ODDS")).toHaveAttribute("title", "A Polymarket odds market: the price is a probability");
    expect(cardById("FAKE").getByText("ODDS")).toHaveAttribute("title", "A simulated odds market: the price is a probability");
  });
});

describe("Lobby — honest about the crowd", () => {
  it("a server that reports test bots says the counts and the split include them", async () => {
    renderLobby(catalogue(), "/", {
      serverInfo: vi.fn(async () => ({
        mode: "live" as const,
        roundDurationsSec: { HOURLY: 180, DAILY: 900 },
        bots: 20,
        oracle: { network: "testnet11" as const, launcherId: "0x", lastConfirmedSeq: null, lastConfirmedHeight: null, pendingRecords: 0, operatorBalanceMojos: null },
        serverTimeMs: 0,
      })),
    });
    expect(await screen.findByText(/include 20 labelled test bots/)).toBeInTheDocument();
  });

  it("says nothing without a server report (the mock) or with no bots", async () => {
    renderLobby();
    await screen.findAllByRole("article");
    expect(screen.queryByText(/test bots/)).not.toBeInTheDocument();
  });
});

describe("Lobby — states", () => {
  it("an empty market list says so", async () => {
    renderLobby([]);
    expect(await screen.findByText("No markets are open right now.")).toBeInTheDocument();
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByRole("tab", { name: "All, 0 open markets" })).toBeInTheDocument();
  });

  it("a failing first load is an error state with a retry that works", async () => {
    const user = userEvent.setup();
    const marketsMock = vi
      .fn<() => Promise<MarketsDto>>()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue(marketsOf([market("BTC", { symbol: "BTC", title: "Bitcoin" })]));
    renderLobby([], "/", { markets: marketsMock });
    expect(await screen.findByText("Something went wrong.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("article")).toBeInTheDocument();
  });
});

describe("Lobby — a poll that fails after a good load", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps the last cards and says the prices may be out of date", async () => {
    const marketsMock = vi
      .fn<() => Promise<MarketsDto>>()
      .mockResolvedValueOnce(marketsOf([market("BTC", { symbol: "BTC", title: "Bitcoin" })]))
      .mockRejectedValue(new Error("offline"));
    renderLobby([], "/", { markets: marketsMock });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(screen.queryByText(/Can't reach the server/)).not.toBeInTheDocument();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(screen.getByText(/Can't reach the server — showing the last prices we got/)).toBeInTheDocument();
  });
});

describe("Lobby — the intro line is off: Р-63 took such lines away on Alex's word, so it is his decision to bring one back (first tester pass, D10)", () => {
  it("a visitor who has not signed in sees the lobby as it was: no line above the ribbon, while the cards load and after", async () => {
    renderLobbyAs({});
    expect(screen.queryByText(/Call real prices UP or DOWN/i)).not.toBeInTheDocument();
    await screen.findAllByRole("article");
    expect(screen.queryByText(/Call real prices UP or DOWN/i)).not.toBeInTheDocument();
    expect(document.querySelector(".lobby-intro")).toBeNull();
    // the lobby's first things are still the heading and the ribbon of panels
    const root = document.querySelector(".lobby-root") as HTMLElement;
    expect(root.firstElementChild).toHaveTextContent("Markets");
    expect((root.children[1] as HTMLElement).className).toContain("lobby-ribbon-wrap");
  });

  it("the flag is false unless a caller says otherwise — the route in App.tsx renders <LobbyPage /> and nothing more", async () => {
    renderLobbyAs({}, false);
    await screen.findAllByRole("article");
    expect(screen.queryByText(/Call real prices UP or DOWN/i)).not.toBeInTheDocument();
  });
});

describe("Lobby — the intro line, when its flag is on: says what this is, once, to a visitor who has not signed in (first tester pass, D10)", () => {
  it("one small line above the markets: call real prices UP or DOWN, up to x40, the result in 3 minutes", async () => {
    renderLobbyAs({}, true);
    const intro = screen.getByText("Call real prices UP or DOWN:").closest("p") as HTMLElement;
    expect(intro).toHaveTextContent("Call real prices UP or DOWN: leverage up to each market's cap, the result in 3 minutes. How it works");
    expect(within(intro).getByRole("link", { name: "How it works" })).toHaveAttribute("href", "/how-it-works");
    await screen.findAllByRole("article");
    // above the ribbon of panels, which stays the lobby's first control
    expect(intro.compareDocumentPosition(screen.getByRole("tablist", { name: "Market categories" }))).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.getAllByText(/Call real prices UP or DOWN/i)).toHaveLength(1);
  });

  it("is also there while the first cards load", () => {
    renderLobbyAs({}, true);
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByText(/Call real prices UP or DOWN/i)).toBeInTheDocument();
  });

  it("a signed-in player does not get it", async () => {
    renderLobbyAs({ player: TEST_PLAYER }, true);
    await screen.findAllByRole("article");
    expect(screen.queryByText(/Call real prices UP or DOWN/i)).not.toBeInTheDocument();
  });

  it("nor does a session that is still being read, so a returning player never sees it flash", async () => {
    renderLobbyAs({ loading: true }, true);
    await screen.findAllByRole("article");
    expect(screen.queryByText(/Call real prices UP or DOWN/i)).not.toBeInTheDocument();
  });
});
