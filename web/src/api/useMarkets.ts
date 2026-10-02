/**
 * The market list (`Api.markets()`) as a shared, polled React state (Р-69): the lobby, the room
 * header, the bet slip's leverage cap and the "my positions" strip all read it through here, and
 * however many of them are mounted, one `markets()` call goes out every 2 s per `Api` instance
 * (`polledStore.ts`; the response is built from memory server-side, no source round-trip).
 */
import { useMemo } from "react";
import { useApi } from "./ApiContext";
import { isEngineNotReady } from "./apiErrors";
import type { Api, CategoryDto, EpochMs, MarketDto, MarketId, MarketsDto } from "./contract";
import { createPolledStore, storeFor, usePolledStore, type PolledStore } from "./polledStore";
import { clientNowMs } from "./useClock";

/** `markets()` is polled this often (the architecture's lobby cadence, `markets_architecture.md` §8). */
export const MARKETS_POLL_MS = 2000;

interface MarketsSnapshot {
  /** The last good response; null until the first one. */
  data: MarketsDto | null;
  /** The most recent poll failed (`data` is then the last good one, if any). */
  error: boolean;
  /** `serverTimeMs` minus the client clock, estimated at the middle of the round trip. */
  offsetMs: number;
  byId: ReadonlyMap<MarketId, MarketDto>;
  byLowerId: ReadonlyMap<string, MarketDto>;
}

const EMPTY: MarketsSnapshot = { data: null, error: false, offsetMs: 0, byId: new Map(), byLowerId: new Map() };

function getMarketsStore(api: Api): PolledStore<MarketsSnapshot> {
  return storeFor(api, "markets", () =>
    createPolledStore<MarketsSnapshot>({
      initial: EMPTY,
      intervalMs: MARKETS_POLL_MS,
      fetch: async () => {
        const sentAt = clientNowMs();
        const data = await api.markets();
        const receivedAt = clientNowMs();
        return {
          data,
          error: false,
          offsetMs: data.serverTimeMs - (sentAt + receivedAt) / 2,
          byId: new Map(data.markets.map((m) => [m.id, m] as const)),
          byLowerId: new Map(data.markets.map((m) => [m.id.toLowerCase(), m] as const)),
        };
      },
      // The server rebuilding its state (503 `ENGINE_NOT_READY`) is not an error of the list: what is held stays, an empty
      // list stays "loading" (a skeleton, not an error card), the next tick asks again and the banner says why.
      onError: (current, error) => (current.error || isEngineNotReady(error) ? current : { ...current, error: true }),
      onIdle: (current) => (current.data ? current : EMPTY),
    }),
  );
}

export interface MarketsState {
  /** No response has arrived yet. */
  loading: boolean;
  /** The latest poll failed; `markets` is still the last good list (empty if there never was one). */
  error: boolean;
  /** Only categories with a market in them, in Т-1 order (as the server sends them). */
  categories: readonly CategoryDto[];
  /** Active markets plus those closed within the last 10 minutes, in listing order. */
  markets: readonly MarketDto[];
  byId: ReadonlyMap<MarketId, MarketDto>;
  /** The server's clock at the last response; null before the first. */
  serverTimeMs: EpochMs | null;
  /** `serverTimeMs − client clock` at receipt: the server's "now" is `clientNowMs() + serverOffsetMs`
   *  (0 before the first response, and ~0 against the mock, whose clock is the client's). */
  serverOffsetMs: number;
  /** Fetch now instead of waiting for the next tick (e.g. right after placing a bet). */
  refresh: () => void;
}

const NO_CATEGORIES: readonly CategoryDto[] = [];
const NO_MARKETS: readonly MarketDto[] = [];

export function useMarkets(): MarketsState {
  const api = useApi();
  const store = getMarketsStore(api);
  const snapshot = usePolledStore(store);
  return useMemo<MarketsState>(
    () => ({
      loading: snapshot.data === null,
      error: snapshot.error,
      categories: snapshot.data?.categories ?? NO_CATEGORIES,
      markets: snapshot.data?.markets ?? NO_MARKETS,
      byId: snapshot.byId,
      serverTimeMs: snapshot.data?.serverTimeMs ?? null,
      serverOffsetMs: snapshot.offsetMs,
      refresh: store.refresh,
    }),
    [snapshot, store],
  );
}

export interface MarketLookup {
  /** The market, once the list has arrived and contains it. */
  market: MarketDto | undefined;
  /** The list has not arrived yet. */
  loading: boolean;
  /** The list has arrived and has no such market (or a closed one that already left it). */
  notFound: boolean;
  /** The latest poll of the list failed (with `loading` still true: nothing to show yet). */
  error: boolean;
  /** Fetch the list now — the retry button of an error state. */
  refresh: () => void;
}

/**
 * One market by id, from the shared list. The id is matched exactly first, then ignoring case, so
 * an old `/play/btc` link still lands on `BTC` (before markets the route param was lowercase).
 * `undefined`/`null` (a route without the param) is `notFound` once the list is loaded.
 */
export function useMarket(marketId: MarketId | null | undefined): MarketLookup {
  const api = useApi();
  const store = getMarketsStore(api);
  const snapshot = usePolledStore(store);
  return useMemo<MarketLookup>(() => {
    const loading = snapshot.data === null;
    const market = marketId ? (snapshot.byId.get(marketId) ?? snapshot.byLowerId.get(marketId.toLowerCase())) : undefined;
    return { market, loading, notFound: !loading && market === undefined, error: snapshot.error, refresh: store.refresh };
  }, [snapshot, marketId, store]);
}
