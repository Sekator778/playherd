/**
 * The room's live price chart data (`Api.priceSeries`, Р-70): the last 15 minutes of ticks on the
 * first load, then only what is new (`sinceMs` = the newest tick we hold), appended to a bounded
 * buffer. One store per (`Api`, market) is shared by everything in the room that reads the series
 * (chart, price header), and a store that nobody is watching any more forgets its buffer, so
 * coming back to a room starts from a fresh load rather than stitching onto a stale tail.
 *
 * What the server promises (`contract.ts`, `Api.priceSeries`): with `sinceMs` it returns the ticks with
 * `atMs > sinceMs` — strictly after, never the tick a poll already holds — at most 600, and when more exist the
 * 600 come thinned evenly so they still span the whole range. A poll after a long pause (a tab that was hidden:
 * the poll stops while it is) therefore brings a coarser stretch where the gap was, then the fine ticks again;
 * the chart draws on real time, so uneven spacing is fine and the merge below only has to keep the order.
 */
import { useApi } from "./ApiContext";
import type { Api, MarketId, PriceTickDto } from "./contract";
import { createPolledStore, storeFor, usePolledStore, type PolledStore } from "./polledStore";

export const PRICE_SERIES_POLL_MS = 2000;
/** The most ticks the buffer keeps — `priceSeries` itself never returns more than 600 either. */
export const PRICE_SERIES_MAX_TICKS = 600;

/**
 * Appends `incoming` (oldest first) to `existing` (oldest first), keeping the newest `max`.
 * Ticks that are not newer than the newest one already held are dropped. The server's `atMs > sinceMs` means
 * a poll never repeats the tick it was asked after, so this is a guard (two overlapping polls, a server that
 * is inclusive) rather than something every response needs — a point is never duplicated either way. If the
 * incoming batch ends BEFORE what we hold, the server's series has restarted (a new run, a clock reset): the
 * batch replaces the buffer instead of being ignored forever.
 */
export function mergeTicks(
  existing: readonly PriceTickDto[],
  incoming: readonly PriceTickDto[],
  max: number = PRICE_SERIES_MAX_TICKS,
): PriceTickDto[] {
  if (incoming.length === 0) return existing as PriceTickDto[];
  const lastHeld = existing.length > 0 ? existing[existing.length - 1].atMs : -Infinity;
  if (incoming[incoming.length - 1].atMs < lastHeld) return incoming.slice(-max);
  const fresh = incoming.filter((tick) => tick.atMs > lastHeld);
  if (fresh.length === 0) return existing as PriceTickDto[];
  const merged = existing.length === 0 ? fresh : [...existing, ...fresh];
  return merged.length > max ? merged.slice(merged.length - max) : merged;
}

interface PriceSeriesSnapshot {
  ticks: readonly PriceTickDto[];
  priceDecimals: number | null;
  /** The first response has arrived. */
  loaded: boolean;
  error: boolean;
}

const INITIAL: PriceSeriesSnapshot = { ticks: [], priceDecimals: null, loaded: false, error: false };

function getPriceSeriesStore(api: Api, marketId: MarketId): PolledStore<PriceSeriesSnapshot> {
  return storeFor(api, `priceSeries:${marketId}`, () =>
    createPolledStore<PriceSeriesSnapshot>({
      initial: INITIAL,
      intervalMs: PRICE_SERIES_POLL_MS,
      fetch: async (current) => {
        const newest = current.ticks.length > 0 ? current.ticks[current.ticks.length - 1].atMs : undefined;
        const response = await api.priceSeries(marketId, newest);
        const ticks = mergeTicks(current.ticks, response.ticks);
        if (ticks === current.ticks && current.loaded && !current.error && current.priceDecimals === response.priceDecimals) {
          return current; // nothing new: no re-render
        }
        return { ticks, priceDecimals: response.priceDecimals, loaded: true, error: false };
      },
      onError: (current) => (current.error ? current : { ...current, error: true }),
      onIdle: () => INITIAL,
    }),
  );
}

export interface PriceSeriesState {
  /** Oldest first, at most {@link PRICE_SERIES_MAX_TICKS}. The array identity changes only when a tick is added. */
  ticks: readonly PriceTickDto[];
  /** The market's price scale as the server reports it; null until the first response. */
  priceDecimals: number | null;
  /** No response has arrived yet. */
  loading: boolean;
  /** The latest poll failed; `ticks` is what was loaded before. */
  error: boolean;
}

const NO_STORE_STATE: PriceSeriesState = { ticks: [], priceDecimals: null, loading: false, error: false };

/** The live ticks of `marketId` (polled every 2 s while mounted). `null`/`undefined` → nothing is
 *  fetched and an empty, not-loading state comes back. */
export function usePriceSeries(marketId: MarketId | null | undefined): PriceSeriesState {
  const api = useApi();
  // One store per market; with no market the hook still has to call `usePolledStore` (hooks cannot
  // be conditional), so it watches a store that never polls.
  const store = marketId ? getPriceSeriesStore(api, marketId) : IDLE_STORE;
  const snapshot = usePolledStore(store);
  if (!marketId) return NO_STORE_STATE;
  return stateOf(snapshot);
}

const stateCache = new WeakMap<PriceSeriesSnapshot, PriceSeriesState>();

/** Same state object for the same snapshot, so consumers can use it as a dependency. */
function stateOf(snapshot: PriceSeriesSnapshot): PriceSeriesState {
  let state = stateCache.get(snapshot);
  if (!state) {
    state = { ticks: snapshot.ticks, priceDecimals: snapshot.priceDecimals, loading: !snapshot.loaded, error: snapshot.error };
    stateCache.set(snapshot, state);
  }
  return state;
}

/** A store that holds the initial snapshot and never fetches: what `usePriceSeries(null)` watches. */
const IDLE_STORE: PolledStore<PriceSeriesSnapshot> = {
  subscribe: () => () => {},
  getSnapshot: () => INITIAL,
  refresh: () => {},
};
