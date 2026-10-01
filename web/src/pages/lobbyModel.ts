/**
 * What the lobby shows for a given ribbon / chip / search state (Р-69): pure functions over the
 * `markets()` list, so the filtering rules can be tested without rendering anything. The state itself
 * lives in the URL (`?c=sports&sort=hot&fav=1&q=ger` — `parseLobbyParams`/`lobbyParams`), so a Back from a
 * room lands on the same panel, and a link can open the lobby on a panel.
 */
import type { CategoryDto, CategoryId, MarketDto } from "../api/contract";
import { categoryTitle } from "../api/marketMeta";
import { isOpenMarket, marketPool, nextCloseMs } from "../components/marketModel";

/** The ribbon's first tab ("All") or one of the categories the server lists. */
export type CategoryFilter = CategoryId | "all";
/** The two sort chips; `listing` is the server's own order (no chip pressed). */
export type LobbySort = "listing" | "hot" | "closing";

export interface LobbyState {
  category: CategoryFilter;
  sort: LobbySort;
  favoritesOnly: boolean;
  query: string;
}

export const DEFAULT_LOBBY_STATE: LobbyState = { category: "all", sort: "listing", favoritesOnly: false, query: "" };

/** Every whitespace-separated word of `query` must appear in the market's symbol, title, outcome
 *  subtitle, id or category name (case-insensitive) — so "sports" lists the sports panel, "ger" finds GER. */
export function matchesQuery(market: MarketDto, query: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const haystack = [market.symbol, market.title, market.subtitle ?? "", market.id, categoryTitle(market.category), market.kind === "ODDS" ? "odds" : ""]
    .join(" ")
    .toLowerCase();
  return words.every((word) => haystack.includes(word));
}

/**
 * The cards to show, in order. Filters: ribbon category, favourites, search. Sort chips never hide
 * anything — "Hot" orders by the open round's pool (biggest first), "Closing soon" by what closes
 * next (soonest first); ties keep the server's order, and closed markets always go last.
 */
export function visibleMarkets(markets: readonly MarketDto[], state: LobbyState, favorites: ReadonlySet<string>): MarketDto[] {
  const indexed = markets
    .map((market, index) => ({ market, index }))
    .filter(({ market }) => state.category === "all" || market.category === state.category)
    .filter(({ market }) => !state.favoritesOnly || favorites.has(market.id))
    .filter(({ market }) => matchesQuery(market, state.query));
  indexed.sort((a, b) => {
    const closedDelta = Number(!isOpenMarket(a.market)) - Number(!isOpenMarket(b.market));
    if (closedDelta !== 0) return closedDelta;
    if (state.sort === "hot") {
      const poolDelta = marketPool(b.market) - marketPool(a.market);
      if (poolDelta !== 0) return poolDelta;
    } else if (state.sort === "closing") {
      const closeDelta = nextCloseMs(a.market) - nextCloseMs(b.market);
      if (closeDelta !== 0 && Number.isFinite(closeDelta)) return closeDelta;
    }
    return a.index - b.index;
  });
  return indexed.map(({ market }) => market);
}

/** The ids of the (up to) `count` biggest pools — the cards that wear the HOT tag while the Hot chip is on. */
export function hottestIds(markets: readonly MarketDto[], count = 3): Set<string> {
  return new Set(
    markets
      .filter((m) => isOpenMarket(m) && marketPool(m) > 0)
      .sort((a, b) => marketPool(b) - marketPool(a))
      .slice(0, count)
      .map((m) => m.id),
  );
}

/** The ribbon's tabs: "All" (open markets, the sum of the categories' counts) then each category the server lists. */
export function ribbonTabs(categories: readonly CategoryDto[]): Array<{ id: CategoryFilter; title: string; count: number }> {
  const total = categories.reduce((sum, c) => sum + c.marketCount, 0);
  return [{ id: "all", title: "All", count: total }, ...categories.map((c) => ({ id: c.id, title: c.title || categoryTitle(c.id), count: c.marketCount }))];
}

// ---------------------------------------------------------------------------
// URL state
// ---------------------------------------------------------------------------

/** Lenient on purpose: any category name is accepted here (a newer server may list one this build
 *  does not know), and the page treats a category that is not on the ribbon as "All". */
export function parseLobbyParams(params: URLSearchParams): LobbyState {
  const c = params.get("c") ?? "";
  const sort = params.get("sort");
  return {
    category: c !== "" && c !== "all" ? (c as CategoryId) : "all",
    sort: sort === "hot" || sort === "closing" ? sort : "listing",
    favoritesOnly: params.get("fav") === "1",
    query: params.get("q") ?? "",
  };
}

/** The state as search params; defaults are left out so the plain lobby stays at `/`. */
export function lobbyParams(state: LobbyState): URLSearchParams {
  const params = new URLSearchParams();
  if (state.category !== "all") params.set("c", state.category);
  if (state.sort !== "listing") params.set("sort", state.sort);
  if (state.favoritesOnly) params.set("fav", "1");
  if (state.query.trim() !== "") params.set("q", state.query);
  return params;
}
