/**
 * The signed-in player's open bets and positions in EVERY market (`Api.myPositions`, D-71's "one
 * strip of open positions with live P&L"): polled every 2 s while a component reads it and a player
 * is signed in, shared per `Api` instance and player (`polledStore.ts`). Signed out, nothing is
 * fetched and the list is empty. Live P&L is not in the DTO — compute it from the market's current
 * price (`useMarkets().byId`) with `api/liquidation.ts`: size from `sizeForMargin(stake, leverage,
 * entryPrice, market)`, then `unrealizedPnl(...)`.
 */
import { useCallback, useMemo, useSyncExternalStore } from "react";
import { useApi, useSession } from "./ApiContext";
import { isEngineNotReady } from "./apiErrors";
import type { Api, MyPositionDto } from "./contract";
import { createPolledStore, storeFor, type PolledStore } from "./polledStore";

export const MY_POSITIONS_POLL_MS = 2000;

interface PositionsSnapshot {
  positions: readonly MyPositionDto[];
  loaded: boolean;
  error: boolean;
}

const INITIAL: PositionsSnapshot = { positions: [], loaded: false, error: false };

function getMyPositionsStore(api: Api, playerId: string): PolledStore<PositionsSnapshot> {
  return storeFor(api, `myPositions:${playerId}`, () =>
    createPolledStore<PositionsSnapshot>({
      initial: INITIAL,
      intervalMs: MY_POSITIONS_POLL_MS,
      pushTopics: ["me"],
      fetch: async (current) => {
        const positions = await api.myPositions();
        // Keep the old array when nothing changed, so consumers do not re-render on every tick.
        if (current.loaded && !current.error && JSON.stringify(current.positions) === JSON.stringify(positions)) return current;
        return { positions, loaded: true, error: false };
      },
      // A restarting server (503 `ENGINE_NOT_READY`) is not an error of the list: the positions held stay, the next
      // tick asks again (see `useMarkets`).
      onError: (current, error) => (current.error || isEngineNotReady(error) ? current : { ...current, error: true }),
      onIdle: () => INITIAL,
    }),
  );
}

export interface MyPositionsState {
  /** Soonest-ending first (the server's order is kept). Empty while signed out. */
  positions: readonly MyPositionDto[];
  /** Signed in and no response yet. */
  loading: boolean;
  error: boolean;
  /** Fetch now (e.g. right after placing or cancelling a bet). */
  refresh: () => void;
}

const SIGNED_OUT: MyPositionsState = { positions: [], loading: false, error: false, refresh: () => {} };

export function useMyPositions(): MyPositionsState {
  const api = useApi();
  const { player } = useSession();
  const playerId = player?.playerId ?? null;
  const store = useMemo(() => (playerId ? getMyPositionsStore(api, playerId) : null), [api, playerId]);

  // Signed out: subscribe to nothing, so no poll runs.
  const subscribe = useCallback((listener: () => void) => (store ? store.subscribe(listener) : () => {}), [store]);
  const getSnapshot = useCallback(() => (store ? store.getSnapshot() : INITIAL), [store]);
  const snapshot = useSyncExternalStore(subscribe, getSnapshot);

  return useMemo<MyPositionsState>(() => {
    if (!store) return SIGNED_OUT;
    return { positions: snapshot.positions, loading: !snapshot.loaded, error: snapshot.error, refresh: store.refresh };
  }, [store, snapshot]);
}
