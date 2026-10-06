/**
 * Lobby (`/`) — Р-69: panels of categories → market cards → (in a card) a room with a bet. Navigation
 * the way Polymarket's mobile app does it (`docs/product/design/polymarket-mobile-reference.md`), plus
 * what Herd adds on top (Р-71, `docs/product/design/markets-lobby.md`): the live crowd split on every
 * card, "herd" return per market, one-tap direction, favourites kept on the device.
 *
 * - A horizontally scrollable ribbon "All · Crypto · Politics · …" with counts, sticky under the header.
 * - Chips: Hot (biggest pool in the open round first), Closing soon (what closes next first), Favorites;
 *   and a search field. The first two only reorder — nothing is ever hidden by a sort.
 * - The whole state lives in the URL (`lobbyModel.ts`), so Back from a room lands on the same panel.
 *
 * Data: `useMarkets()` (one shared 2 s poll), `useFavorites()` (this browser's `localStorage`).
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useSession } from "../api/ApiContext";
import { useMarkets } from "../api/useMarkets";
import { useFavorites } from "../api/favorites";
import { BotsNote, ErrorState, Skeleton } from "../components/Common";
import { ClockIcon, CloseIcon, FlameIcon, SearchIcon, StarIcon } from "../components/icons";
import { MarketCard, MarketCardSkeleton } from "../components/MarketCard";
import { isOpenMarket } from "../components/marketModel";
import { useServerNow } from "../components/useServerNow";
import { hottestIds, lobbyParams, parseLobbyParams, ribbonTabs, visibleMarkets, type LobbyState } from "./lobbyModel";
import "./LobbyPage.css";

type RibbonFade = "none" | "start" | "end" | "both";

/** Which end of a horizontally scrolling strip still has something hidden — drives the edge fade.
 *  `watch` changes when the strip's contents change (its tabs arrive after the first response). */
function useScrollFade(watch: unknown): [React.RefObject<HTMLDivElement | null>, RibbonFade] {
  const ref = useRef<HTMLDivElement>(null);
  const [fade, setFade] = useState<RibbonFade>("none");
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const atStart = el.scrollLeft <= 2;
      const atEnd = el.scrollLeft + el.clientWidth >= el.scrollWidth - 2;
      setFade(atStart && atEnd ? "none" : atStart ? "end" : atEnd ? "start" : "both");
    };
    const first = requestAnimationFrame(update);
    el.addEventListener("scroll", update, { passive: true });
    window.addEventListener("resize", update);
    return () => {
      cancelAnimationFrame(first);
      el.removeEventListener("scroll", update);
      window.removeEventListener("resize", update);
    };
  }, [watch]);
  return [ref, fade];
}

const SORT_NOTE = {
  listing: "",
  hot: "biggest pools first",
  closing: "closing first",
} as const;

/** What this is, in one small line above the markets, for a visitor who has not signed in (first tester pass, D10: nothing on the
 *  first screen said what the app is or that the money is toy money — that came after eighteen cards). A signed-in player knows,
 *  and a session still being read is not guessed at: no flash of it for a returning player.
 *
 *  OFF: `LobbyPage`'s `intro` flag defaults to false. Р-63 took lines of this kind off the screens on Alex's word, so whether the
 *  lobby gets one back is his decision, not a defect fix — the component, its style (`.lobby-intro`) and its tests are kept so that
 *  turning it on is `<LobbyPage intro />` in `App.tsx` and nothing else. */
function LobbyIntro() {
  const { player, loading } = useSession();
  if (loading || player) return null;
  return (
    <p className="lobby-intro">
      <strong>A toy-money game:</strong> bet UP or DOWN on real prices, with leverage.{" "}
      <Link to="/how-it-works" className="lobby-intro__link">
        How it works
      </Link>
    </p>
  );
}

interface LobbyPageProps {
  /** Say what this is to a visitor who has not signed in ({@link LobbyIntro}). Off unless a caller asks — see its note. */
  intro?: boolean;
}

export function LobbyPage({ intro = false }: LobbyPageProps) {
  const [params, setParams] = useSearchParams();
  const state = useMemo(() => parseLobbyParams(params), [params]);
  const nowMs = useServerNow(1000);
  const { markets, categories, loading, error, refresh } = useMarkets();
  const { favorites, toggle } = useFavorites();
  const activeTabRef = useRef<HTMLButtonElement>(null);

  const tabs = useMemo(() => ribbonTabs(categories), [categories]);
  const [ribbonRef, fade] = useScrollFade(tabs.length);
  // A category that is not on the ribbon (an old link, a panel that emptied) reads as "All".
  const category = tabs.some((t) => t.id === state.category) ? state.category : "all";
  const effective: LobbyState = useMemo(() => ({ ...state, category }), [state, category]);

  const set = (patch: Partial<LobbyState>) => setParams(lobbyParams({ ...effective, ...patch }), { replace: true });

  // Keep the chosen panel in view when the ribbon is wider than the screen (after a tap, or on a deep link).
  useEffect(() => {
    activeTabRef.current?.scrollIntoView?.({ inline: "center", block: "nearest" });
  }, [category, tabs.length]);

  const visible = useMemo(() => visibleMarkets(markets, effective, favorites), [markets, effective, favorites]);
  const closedCount = visible.filter((m) => !isOpenMarket(m)).length;
  const openCount = visible.length - closedCount;
  const hot = useMemo(() => (effective.sort === "hot" ? hottestIds(visible) : new Set<string>()), [effective.sort, visible]);
  const favoriteCount = markets.filter((m) => favorites.has(m.id)).length;

  if (error && loading) return <ErrorState onRetry={refresh} />;
  if (loading) {
    return (
      <div className="lobby-root" aria-busy="true">
        <h1 className="visually-hidden">Markets</h1>
        {intro && <LobbyIntro />}
        <Skeleton height={48} />
        <Skeleton height={44} />
        <ul className="lobby-grid">
          {[0, 1, 2, 3].map((i) => (
            <li key={i}>
              <MarketCardSkeleton />
            </li>
          ))}
        </ul>
      </div>
    );
  }

  const filtered = effective.category !== "all" || effective.favoritesOnly || effective.query.trim() !== "";
  const activeTitle = tabs.find((t) => t.id === category)?.title ?? "All";
  const reset = () => setParams(new URLSearchParams(), { replace: true });

  return (
    <div className="lobby-root">
      <h1 className="visually-hidden">Markets</h1>
      {intro && <LobbyIntro />}

      <div className="lobby-ribbon-wrap">
        <div ref={ribbonRef} className="lobby-ribbon" role="tablist" aria-label="Market categories" data-fade={fade}>
          {tabs.map((tab) => (
            <button
              key={tab.id}
              ref={tab.id === category ? activeTabRef : undefined}
              type="button"
              role="tab"
              id={`lobby-tab-${tab.id}`}
              aria-selected={tab.id === category}
              aria-controls="lobby-list"
              aria-label={`${tab.title}, ${tab.count} open ${tab.count === 1 ? "market" : "markets"}`}
              className="lobby-ribbon__tab"
              onClick={() => set({ category: tab.id })}
            >
              {tab.title}
              <span className="lobby-ribbon__count mono" aria-hidden="true">
                {tab.count}
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="lobby-toolbar">
        <div className="chip-row lobby-chips" role="group" aria-label="Sort and filter">
          <button type="button" className="chip" aria-pressed={effective.sort === "hot"} onClick={() => set({ sort: effective.sort === "hot" ? "listing" : "hot" })}>
            <FlameIcon /> Hot
          </button>
          <button
            type="button"
            className="chip"
            aria-pressed={effective.sort === "closing"}
            onClick={() => set({ sort: effective.sort === "closing" ? "listing" : "closing" })}
          >
            <ClockIcon /> Closing soon
          </button>
          <button type="button" className="chip" aria-pressed={effective.favoritesOnly} onClick={() => set({ favoritesOnly: !effective.favoritesOnly })}>
            <StarIcon filled={effective.favoritesOnly} /> Favorites
            {favoriteCount > 0 && <span className="chip__count mono">{favoriteCount}</span>}
          </button>
        </div>
        <label className="lobby-search">
          <SearchIcon />
          <input
            type="search"
            aria-label="Search markets"
            placeholder="Search markets"
            autoComplete="off"
            autoCorrect="off"
            spellCheck={false}
            value={effective.query}
            onChange={(e) => set({ query: e.target.value })}
          />
          {effective.query !== "" && (
            <button type="button" className="lobby-search__clear" aria-label="Clear search" onClick={() => set({ query: "" })}>
              <CloseIcon />
            </button>
          )}
        </label>
      </div>

      {error && (
        <p className="lobby-notice" role="status">
          Can't reach the server — showing the last prices we got, they may be out of date.
        </p>
      )}

      <p className="lobby-status" aria-live="polite">
        <strong>
          {activeTitle}
          {effective.favoritesOnly ? " · Favorites" : ""}
        </strong>
        <span>
          {/* The ribbon's chips count OPEN markets; a market that closed in the last ten minutes keeps its card, so the line
           *  says how many of the cards are open and how many are closed — the two numbers on the screen agree. */}
          {openCount} {openCount === 1 ? "market" : "markets"}
          {closedCount > 0 ? ` · ${closedCount} closed` : ""}
          {SORT_NOTE[effective.sort] ? ` · ${SORT_NOTE[effective.sort]}` : ""}
        </span>
      </p>

      <div id="lobby-list" role="tabpanel" aria-labelledby={`lobby-tab-${category}`}>
        {visible.length === 0 ? (
          <div className="lobby-empty">
            {markets.length === 0 ? (
              <p className="lobby-empty__title">No markets are open right now.</p>
            ) : effective.favoritesOnly && effective.query.trim() === "" && favoriteCount === 0 ? (
              <>
                <p className="lobby-empty__title">No favorites yet</p>
                <p>Tap the star on a market to keep it here.</p>
              </>
            ) : effective.query.trim() !== "" ? (
              <p className="lobby-empty__title">No markets match “{effective.query.trim()}”.</p>
            ) : (
              <p className="lobby-empty__title">Nothing here yet.</p>
            )}
            {filtered && markets.length > 0 && (
              <button type="button" className="btn btn--secondary" onClick={reset}>
                Show all markets
              </button>
            )}
          </div>
        ) : (
          <ul className="lobby-grid">
            {visible.map((market) => (
              <li key={market.id}>
                <MarketCard market={market} nowMs={nowMs} favorite={favorites.has(market.id)} onToggleFavorite={toggle} hot={hot.has(market.id)} />
              </li>
            ))}
          </ul>
        )}
      </div>

      {markets.length > 0 && (
        <>
          <BotsNote />
          <p className="faint" style={{ margin: 0 }}>
            Prices and odds are shown as the sources publish them — Herd's rounds settle on them, with PLAY (test tokens, not real money).{" "}
            <Link to="/how-it-works">How it works</Link>
          </p>
        </>
      )}
    </div>
  );
}
