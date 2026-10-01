import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  FAVORITES_STORAGE_KEY,
  getFavorites,
  reloadFavoritesFromStorage,
  subscribeFavorites,
  toggleFavorite,
  useFavorites,
} from "./favorites";

beforeEach(() => {
  window.localStorage.clear();
  reloadFavoritesFromStorage();
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe("favorites store (localStorage 'herd.favorites')", () => {
  it("starts empty", () => {
    expect([...getFavorites()]).toEqual([]);
  });

  it("toggle adds, then removes, and reports the new state", () => {
    expect(toggleFavorite("ETH")).toBe(true);
    expect(getFavorites().has("ETH")).toBe(true);
    expect(toggleFavorite("xyz_NVDA")).toBe(true);
    expect(toggleFavorite("ETH")).toBe(false);
    expect([...getFavorites()]).toEqual(["xyz_NVDA"]);
  });

  it("persists as a JSON array of market ids under 'herd.favorites'", () => {
    toggleFavorite("BTC");
    toggleFavorite("PM900104");
    expect(FAVORITES_STORAGE_KEY).toBe("herd.favorites");
    expect(JSON.parse(window.localStorage.getItem("herd.favorites")!)).toEqual(["BTC", "PM900104"]);
    toggleFavorite("BTC");
    expect(JSON.parse(window.localStorage.getItem("herd.favorites")!)).toEqual(["PM900104"]);
  });

  it("reads what a previous visit stored, ignoring anything that is not a string id", () => {
    window.localStorage.setItem("herd.favorites", JSON.stringify(["BTC", 7, null, "SOL", { a: 1 }]));
    reloadFavoritesFromStorage();
    expect([...getFavorites()]).toEqual(["BTC", "SOL"]);
  });

  it("survives garbage in storage", () => {
    window.localStorage.setItem("herd.favorites", "{not json");
    reloadFavoritesFromStorage();
    expect([...getFavorites()]).toEqual([]);
    window.localStorage.setItem("herd.favorites", JSON.stringify({ not: "an array" }));
    reloadFavoritesFromStorage();
    expect([...getFavorites()]).toEqual([]);
  });

  it("still works in memory when storage refuses writes (private window, blocked site data)", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("QuotaExceededError");
    });
    expect(() => toggleFavorite("ETH")).not.toThrow();
    expect(getFavorites().has("ETH")).toBe(true);
  });

  it("still works when reading storage throws", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    expect(() => reloadFavoritesFromStorage()).not.toThrow();
    expect([...getFavorites()]).toEqual([]);
  });

  it("a toggle is a new Set instance (so subscribers see a change) and notifies subscribers", () => {
    const before = getFavorites();
    const listener = vi.fn();
    const unsubscribe = subscribeFavorites(listener);
    toggleFavorite("ETH");
    expect(listener).toHaveBeenCalledTimes(1);
    expect(getFavorites()).not.toBe(before);
    unsubscribe();
    toggleFavorite("SOL");
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("keeps a second tab in step via the storage event", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeFavorites(listener);
    window.localStorage.setItem("herd.favorites", JSON.stringify(["HYPE"]));
    window.dispatchEvent(new StorageEvent("storage", { key: "herd.favorites" }));
    expect(listener).toHaveBeenCalledTimes(1);
    expect([...getFavorites()]).toEqual(["HYPE"]);
    window.dispatchEvent(new StorageEvent("storage", { key: "something.else" }));
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
  });
});

describe("useFavorites", () => {
  it("re-renders on toggle and exposes isFavorite / toggle", () => {
    const { result } = renderHook(() => useFavorites());
    expect(result.current.isFavorite("ETH")).toBe(false);
    act(() => {
      expect(result.current.toggle("ETH")).toBe(true);
    });
    expect(result.current.isFavorite("ETH")).toBe(true);
    expect(result.current.favorites.has("ETH")).toBe(true);
    act(() => {
      result.current.toggle("ETH");
    });
    expect(result.current.isFavorite("ETH")).toBe(false);
  });

  it("every hook instance sees the same set at once", () => {
    const a = renderHook(() => useFavorites());
    const b = renderHook(() => useFavorites());
    act(() => {
      a.result.current.toggle("SOL");
    });
    expect(b.result.current.isFavorite("SOL")).toBe(true);
  });
});
