/**
 * `Api.serverInfo()` as a shared, polled React state: the Status page, the Sage hints (the PLAY asset id the
 * server sends) and anything else that wants the server's own description of itself read it through here, and
 * however many of them are mounted, one request goes out every 15 s per `Api` instance (`polledStore.ts`).
 *
 * `serverInfo` is optional on `Api` (`contract.ts`): a mock build has no server to describe, which is a state
 * of its own (`unsupported`), not an error. A server that is there but not answering is an `error` only until
 * the first answer; after that the last good one stays on screen and `refreshFailed` says it is old.
 */
import { useApi } from "./ApiContext";
import type { Api, ServerInfoDto } from "./contract";
import { createPolledStore, storeFor, usePolledStore, type PolledStore } from "./polledStore";

export const SERVER_INFO_POLL_MS = 15_000;

interface ServerInfoSnapshot {
  info: ServerInfoDto | null;
  /** The latest poll failed. */
  failed: boolean;
  /** At least one response (or failure) has come back. */
  settled: boolean;
}

const INITIAL: ServerInfoSnapshot = { info: null, failed: false, settled: false };

function getServerInfoStore(api: Api): PolledStore<ServerInfoSnapshot> {
  return storeFor(api, "serverInfo", () =>
    createPolledStore<ServerInfoSnapshot>({
      initial: INITIAL,
      intervalMs: SERVER_INFO_POLL_MS,
      fetch: async () => ({ info: await api.serverInfo!(), failed: false, settled: true }),
      onError: (current) => ({ ...current, failed: true, settled: true }),
    }),
  );
}

/**
 * Asks for `serverInfo()` once, now, without starting the poll: the answer lands in the shared snapshot, so whoever
 * subscribes next (the sign-in sheet, a moment after the app loads) already has it instead of waiting for it. A no-op
 * for an `Api` without `serverInfo()`.
 */
export function prefetchServerInfo(api: Api): void {
  if (typeof api.serverInfo === "function") getServerInfoStore(api).refresh();
}

/** A store that holds the initial snapshot and never fetches: what an `Api` without `serverInfo` watches. */
const IDLE_STORE: PolledStore<ServerInfoSnapshot> = {
  subscribe: () => () => {},
  getSnapshot: () => INITIAL,
  refresh: () => {},
};

export interface ServerInfoState {
  /** `unsupported`: this `Api` has no `serverInfo()` (the mock build). `loading`: no answer yet. `error`: no
   *  answer yet and the first poll failed. `ready`: there is an answer (maybe an old one — see `refreshFailed`). */
  status: "unsupported" | "loading" | "error" | "ready";
  info: ServerInfoDto | null;
  /** There is an answer, but the latest poll failed: what is shown may be out of date. */
  refreshFailed: boolean;
  /** Fetch now — the retry button of an error state. */
  refresh: () => void;
}

const UNSUPPORTED: ServerInfoState = { status: "unsupported", info: null, refreshFailed: false, refresh: () => {} };

export function useServerInfo(): ServerInfoState {
  const api = useApi();
  const supported = typeof api.serverInfo === "function";
  // Hooks cannot be conditional: an `Api` without `serverInfo` watches a store that never polls.
  const store = supported ? getServerInfoStore(api) : IDLE_STORE;
  const snapshot = usePolledStore(store);
  if (!supported) return UNSUPPORTED;
  const status = snapshot.info ? "ready" : snapshot.failed ? "error" : "loading";
  return { status, info: snapshot.info, refreshFailed: snapshot.info !== null && snapshot.failed, refresh: store.refresh };
}
