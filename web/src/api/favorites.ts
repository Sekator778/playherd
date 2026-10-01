/**
 * The player's favourite markets (the lobby's "Favorites" chip, Р-69): a per-viewer convenience,
 * kept in this browser's `localStorage` under `herd.favorites` as a JSON array of market ids — it
 * never reaches the server and does not follow the player to another device. Every storage access
 * is guarded (private windows, blocked site data and the like throw or come back empty): the
 * in-memory copy keeps toggling working for the life of the page either way.
 *
 * One module-level store, so every component (a card's star, the Favorites filter) sees the same set
 * at once, and the `storage` event keeps a second tab in step.
 */
import { useCallback, useMemo, useSyncExternalStore } from "react";
import type { MarketId } from "./contract";

export const FAVORITES_STORAGE_KEY = "herd.favorites";

function readStored(): Set<MarketId> {
  try {
    const raw = window.localStorage.getItem(FAVORITES_STORAGE_KEY);
    if (!raw) return new Set();
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? new Set(parsed.filter((id): id is MarketId => typeof id === "string")) : new Set();
  } catch {
    return new Set();
  }
}

function writeStored(favorites: ReadonlySet<MarketId>): void {
  try {
    window.localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify([...favorites]));
  } catch {
    // storage unavailable: the in-memory set still works for this page
  }
}

let current: ReadonlySet<MarketId> = readStored();
const listeners = new Set<() => void>();
let storageHandler: ((e: StorageEvent) => void) | undefined;

function emit(next: ReadonlySet<MarketId>): void {
  current = next;
  listeners.forEach((listener) => listener());
}

/** The current favourites (a new `Set` instance whenever it changes). */
export function getFavorites(): ReadonlySet<MarketId> {
  return current;
}

/** Adds or removes `marketId`; returns whether it is a favourite now. */
export function toggleFavorite(marketId: MarketId): boolean {
  const next = new Set(current);
  const nowFavorite = !next.delete(marketId);
  if (nowFavorite) next.add(marketId);
  writeStored(next);
  emit(next);
  return nowFavorite;
}

export function subscribeFavorites(listener: () => void): () => void {
  listeners.add(listener);
  if (listeners.size === 1 && typeof window !== "undefined") {
    storageHandler = (e) => {
      if (e.key === FAVORITES_STORAGE_KEY || e.key === null) emit(readStored());
    };
    window.addEventListener("storage", storageHandler);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && storageHandler) {
      window.removeEventListener("storage", storageHandler);
      storageHandler = undefined;
    }
  };
}

/** Test hook: drop the in-memory copy and re-read storage (a test that cleared it expects that). */
export function reloadFavoritesFromStorage(): void {
  emit(readStored());
}

export interface FavoritesState {
  favorites: ReadonlySet<MarketId>;
  isFavorite: (marketId: MarketId) => boolean;
  /** Adds or removes a market; returns whether it is a favourite now. */
  toggle: (marketId: MarketId) => boolean;
}

export function useFavorites(): FavoritesState {
  const favorites = useSyncExternalStore(subscribeFavorites, getFavorites);
  const isFavorite = useCallback((marketId: MarketId) => favorites.has(marketId), [favorites]);
  return useMemo(() => ({ favorites, isFavorite, toggle: toggleFavorite }), [favorites, isFavorite]);
}
