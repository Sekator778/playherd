import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { Api, PlayerSummaryDto } from "./contract";
import { API_MODE } from "./apiMode";
import { mockApi } from "./mockApi";
import { realApi } from "./realApi";
import { getPush } from "./push";

/**
 * The single `Api` instance every screen reads from — every component only ever imports this
 * context, never `mockApi`/`realApi` directly, so switching between them is a one-line change
 * here. Which one that is is decided once, at module load, by `VITE_API_MODE` (`apiMode.ts`,
 * task item 1) — `mockApi` unless the build was explicitly told `VITE_API_MODE=real`.
 */
const apiInstance: Api = API_MODE === "real" ? realApi : mockApi;

// Exported (only) so an integration test can wrap a subtree in its own `.Provider` with a fake
// `Api`/session — `useSettlementWatcher.test.tsx`'s "fake Api + fake timers" harness, e.g. — while
// every real screen keeps reading through `useApi()`/`useSession()` below as usual.
export const ApiInstanceContext = createContext<Api>(apiInstance);

export function useApi(): Api {
  return useContext(ApiInstanceContext);
}

export interface SessionState {
  player: PlayerSummaryDto | null;
  loading: boolean;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  /** Opens the "Connect your wallet" flow modal (`ux_spec.md` §10) from anywhere — the header's
   *  button and every screen's "not signed in" CTA card share the one modal instance. */
  openConnectModal: () => void;
}

export const SessionContext = createContext<SessionState>({
  player: null,
  loading: true,
  refresh: async () => {},
  signOut: async () => {},
  openConnectModal: () => {},
});

export function useSession(): SessionState {
  return useContext(SessionContext);
}

interface ConnectModalState {
  isOpen: boolean;
  close: () => void;
}

const ConnectModalContext = createContext<ConnectModalState>({ isOpen: false, close: () => {} });

export function useConnectModalState(): ConnectModalState {
  return useContext(ConnectModalContext);
}

/** Wraps the app once: provides the `Api` instance and a small session cache (current player) so
 *  the header, bet slip, and every "not signed in" state all agree on sign-in state without each
 *  re-fetching it themselves. Also owns whether the connect-wallet modal is open. `api` is for a test that
 *  needs this provider (the session and the modal) around a fake `Api`; the app never passes it. */
export function ApiProvider({ children, api = apiInstance }: { children: ReactNode; api?: Api }) {
  const [player, setPlayer] = useState<PlayerSummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);

  const refresh = async () => {
    const current = await api.currentPlayer();
    setPlayer(current);
    setLoading(false);
  };

  const signOut = async () => {
    await api.signOut();
    await refresh();
  };

  // Inlined rather than calling `refresh()` (which the lint rule can't see is safe): the
  // linter only recognizes a bare promise chain with no synchronous setState before it as a
  // safe effect body.
  //
  // A failed read (the server restarting, a dropped connection) must not leave the page looking signed out for good:
  // it is asked again — in 3 s, then 6, then every 15 — until the server answers.
  const [sessionFailures, setSessionFailures] = useState(0);
  useEffect(() => {
    let cancelled = false;
    let retry: number | undefined;
    api
      .currentPlayer()
      .then((current) => {
        if (cancelled) return;
        setPlayer(current);
        setLoading(false);
        setSessionFailures(0);
      })
      .catch(() => {
        if (cancelled) return;
        retry = window.setTimeout(() => setSessionFailures((n) => n + 1), Math.min(15_000, 3_000 * 2 ** sessionFailures));
      });
    return () => {
      cancelled = true;
      window.clearTimeout(retry);
    };
  }, [api, sessionFailures]);

  // The push socket authenticates with the session cookie at the upgrade: a new player (or none) needs a new socket.
  const playerId = player?.playerId ?? null;
  useEffect(() => {
    if (!loading) getPush()?.setIdentity(playerId);
  }, [loading, playerId]);

  const session: SessionState = {
    player,
    loading,
    refresh,
    signOut,
    openConnectModal: () => setModalOpen(true),
  };

  return (
    <ApiInstanceContext.Provider value={api}>
      <SessionContext.Provider value={session}>
        <ConnectModalContext.Provider value={{ isOpen: modalOpen, close: () => setModalOpen(false) }}>
          {children}
        </ConnectModalContext.Provider>
      </SessionContext.Provider>
    </ApiInstanceContext.Provider>
  );
}
