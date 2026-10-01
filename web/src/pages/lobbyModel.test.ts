import { describe, expect, it } from "vitest";
import { catalogue } from "../components/testFixtures";
import { DEFAULT_LOBBY_STATE, hottestIds, lobbyParams, matchesQuery, parseLobbyParams, ribbonTabs, visibleMarkets, type LobbyState } from "./lobbyModel";

const MARKETS = catalogue();
const [BTC, , NVDA, , GER, OLD] = MARKETS;
const ids = (list: { id: string }[]) => list.map((m) => m.id);
const state = (patch: Partial<LobbyState> = {}): LobbyState => ({ ...DEFAULT_LOBBY_STATE, ...patch });
const NO_FAVORITES = new Set<string>();

describe("visibleMarkets — filters", () => {
  it("All keeps the server's order, with the closed market last", () => {
    expect(ids(visibleMarkets(MARKETS, state(), NO_FAVORITES))).toEqual(["BTC", "ETH", "xyz_NVDA", "PM900101", "PM900104", "PM900199"]);
    const closedFirst = [OLD, BTC, GER];
    expect(ids(visibleMarkets(closedFirst, state(), NO_FAVORITES))).toEqual(["BTC", "PM900104", "PM900199"]);
  });

  it("a category keeps only its markets, closed ones of that category included (last)", () => {
    expect(ids(visibleMarkets(MARKETS, state({ category: "sports" }), NO_FAVORITES))).toEqual(["PM900104", "PM900199"]);
    expect(ids(visibleMarkets(MARKETS, state({ category: "crypto" }), NO_FAVORITES))).toEqual(["BTC", "ETH"]);
    expect(visibleMarkets(MARKETS, state({ category: "commodities" }), NO_FAVORITES)).toEqual([]);
  });

  it("Favorites keeps only the starred ones, and combines with a category", () => {
    const favorites = new Set(["ETH", "PM900104"]);
    expect(ids(visibleMarkets(MARKETS, state({ favoritesOnly: true }), favorites))).toEqual(["ETH", "PM900104"]);
    expect(ids(visibleMarkets(MARKETS, state({ favoritesOnly: true, category: "sports" }), favorites))).toEqual(["PM900104"]);
    expect(visibleMarkets(MARKETS, state({ favoritesOnly: true }), NO_FAVORITES)).toEqual([]);
  });

  it("search matches symbol, title, outcome subtitle, id and category, every word of it", () => {
    expect(ids(visibleMarkets(MARKETS, state({ query: "ger" }), NO_FAVORITES))).toEqual(["PM900104"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "bitcoin" }), NO_FAVORITES))).toEqual(["BTC"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "brazil runoff" }), NO_FAVORITES))).toEqual(["PM900101"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "uefa" }), NO_FAVORITES))).toEqual(["PM900104"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "friendly draw" }), NO_FAVORITES))).toEqual(["PM900199"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "sports" }), NO_FAVORITES))).toEqual(["PM900104", "PM900199"]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "xyz_nvda" }), NO_FAVORITES))).toEqual(["xyz_NVDA"]);
    expect(visibleMarkets(MARKETS, state({ query: "brazil germany" }), NO_FAVORITES)).toEqual([]);
    expect(ids(visibleMarkets(MARKETS, state({ query: "   " }), NO_FAVORITES))).toHaveLength(6);
  });

  it("'odds' finds the odds markets", () => {
    expect(ids(visibleMarkets(MARKETS, state({ query: "odds" }), NO_FAVORITES))).toEqual(["PM900101", "PM900104", "PM900199"]);
    expect(matchesQuery(BTC, "odds")).toBe(false);
  });
});

describe("visibleMarkets — sort chips never hide anything", () => {
  it("Hot puts the biggest pool in the open round first, ties in server order", () => {
    const hot = visibleMarkets(MARKETS, state({ sort: "hot" }), NO_FAVORITES);
    // pools: BTC 4000, ETH 2000, GER 900, RUNOFF 400, NVDA 0; the closed market is last whatever it holds.
    expect(ids(hot)).toEqual(["BTC", "ETH", "PM900104", "PM900101", "xyz_NVDA", "PM900199"]);
    expect(hot).toHaveLength(MARKETS.length);
  });

  it("Closing soon puts what closes next first (betting window or the event's own end)", () => {
    const soon = visibleMarkets(MARKETS, state({ sort: "closing" }), NO_FAVORITES);
    // GER 30 s, ETH 60 s, then the 75 s ones in server order, the closed market last.
    expect(ids(soon)).toEqual(["PM900104", "ETH", "BTC", "xyz_NVDA", "PM900101", "PM900199"]);
  });

  it("a closed market stays last under every sort, even with the biggest pool", () => {
    const hugeClosed = { ...OLD, series: [{ ...OLD.series[0], upStake: "99999.000", downStake: "1.000" }, OLD.series[1]] };
    expect(visibleMarkets([hugeClosed, BTC], state({ sort: "hot" }), NO_FAVORITES).map((m) => m.id)).toEqual(["BTC", "PM900199"]);
  });

  it("sorts compose with the filters", () => {
    expect(ids(visibleMarkets(MARKETS, state({ sort: "hot", category: "crypto" }), NO_FAVORITES))).toEqual(["BTC", "ETH"]);
    expect(ids(visibleMarkets(MARKETS, state({ sort: "closing", category: "crypto" }), NO_FAVORITES))).toEqual(["ETH", "BTC"]);
  });
});

describe("hottestIds", () => {
  it("is the biggest open pools, only those with something staked, at most `count`", () => {
    expect([...hottestIds(MARKETS, 2)]).toEqual(["BTC", "ETH"]);
    expect([...hottestIds(MARKETS)]).toEqual(["BTC", "ETH", "PM900104"]);
    expect(hottestIds([NVDA]).size).toBe(0); // nothing staked: nothing is hot
    const hugeClosed = { ...OLD, series: [{ ...OLD.series[0], upStake: "99999.000" }, OLD.series[1]] };
    expect(hottestIds([hugeClosed]).size).toBe(0); // a closed market is never hot
  });
});

describe("ribbonTabs", () => {
  it("All counts the open markets of every category, then each category in the server's order", () => {
    const tabs = ribbonTabs([
      { id: "crypto", title: "Crypto", marketCount: 2 },
      { id: "sports", title: "Sports", marketCount: 1 },
    ]);
    expect(tabs).toEqual([
      { id: "all", title: "All", count: 3 },
      { id: "crypto", title: "Crypto", count: 2 },
      { id: "sports", title: "Sports", count: 1 },
    ]);
  });

  it("with no category at all there is just an empty All", () => {
    expect(ribbonTabs([])).toEqual([{ id: "all", title: "All", count: 0 }]);
  });
});

describe("URL state", () => {
  it("the plain lobby has no query string", () => {
    expect(lobbyParams(DEFAULT_LOBBY_STATE).toString()).toBe("");
    expect(parseLobbyParams(new URLSearchParams(""))).toEqual(DEFAULT_LOBBY_STATE);
  });

  it("round-trips every part of the state", () => {
    const full: LobbyState = { category: "sports", sort: "closing", favoritesOnly: true, query: "ger win" };
    const params = lobbyParams(full);
    expect(params.toString()).toBe("c=sports&sort=closing&fav=1&q=ger+win");
    expect(parseLobbyParams(params)).toEqual(full);
  });

  it("reads junk leniently: an unknown sort is none, an unknown category is kept for the page to judge, 'all' is all", () => {
    expect(parseLobbyParams(new URLSearchParams("sort=weird")).sort).toBe("listing");
    expect(parseLobbyParams(new URLSearchParams("c=gadgets")).category).toBe("gadgets");
    expect(parseLobbyParams(new URLSearchParams("c=all")).category).toBe("all");
    expect(parseLobbyParams(new URLSearchParams("fav=yes")).favoritesOnly).toBe(false);
  });

  it("a blank search is not written to the URL", () => {
    expect(lobbyParams(state({ query: "   " })).toString()).toBe("");
  });
});
