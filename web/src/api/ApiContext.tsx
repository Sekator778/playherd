import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { Api, PlayerSummaryDto } from "./contract";
import { mockApi } from "./mockApi";

/**
 * The single `Api` instance every screen reads from — today always {@link mockApi}, but every
 * component only ever imports this context, never `mockApi` directly, so swapping in a real
 * HTTP client later (Р-35) is a one-line change here.
 */
const ApiInstanceContext = createContext<Api>(mockApi);

export function useApi(): Api {
  return useContext(ApiInstanceContext);
}

interface SessionState {
  player: PlayerSummaryDto | null;
  loading: boolean;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  /** Opens the "Connect your wallet" flow modal (`ux_spec.md` §10) from anywhere — the header's
   *  button and every screen's "not signed in" CTA card share the one modal instance. */
  openConnectModal: () => void;
}

const SessionContext = createContext<SessionState>({
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
 *  re-fetching it themselves. Also owns whether the connect-wallet modal is open. */
export function ApiProvider({ children }: { children: ReactNode }) {
  const [player, setPlayer] = useState<PlayerSummaryDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [modalOpen, setModalOpen] = useState(false);

  const refresh = async () => {
    const current = await mockApi.currentPlayer();
    setPlayer(current);
    setLoading(false);
  };

  const signOut = async () => {
    await mockApi.signOut();
    await refresh();
  };

  // Inlined rather than calling `refresh()` (which the lint rule can't see is safe): the
  // linter only recognizes a bare promise chain with no synchronous setState before it as a
  // safe effect body.
  useEffect(() => {
    mockApi.currentPlayer().then((current) => {
      setPlayer(current);
      setLoading(false);
    });
  }, []);

  const session: SessionState = {
    player,
    loading,
    refresh,
    signOut,
    openConnectModal: () => setModalOpen(true),
  };

  return (
    <ApiInstanceContext.Provider value={mockApi}>
      <SessionContext.Provider value={session}>
        <ConnectModalContext.Provider value={{ isOpen: modalOpen, close: () => setModalOpen(false) }}>
          {children}
        </ConnectModalContext.Provider>
      </SessionContext.Provider>
    </ApiInstanceContext.Provider>
  );
}
