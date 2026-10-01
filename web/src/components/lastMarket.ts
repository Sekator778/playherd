/**
 * "The market you came from" — the last room this browser session opened, so Stats can open on it
 * (`?m=` on the link wins when there is one). Per session on purpose (`sessionStorage`): a new visit starts
 * from BTC, not from whatever was open last week. Every access is guarded (private windows, blocked storage).
 */
const KEY = "herd.lastMarket";

export function rememberMarket(marketId: string): void {
  try {
    window.sessionStorage.setItem(KEY, marketId);
  } catch {
    // storage unavailable: Stats falls back to BTC
  }
}

export function lastMarket(): string | null {
  try {
    return window.sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}
